package updater

import cats.compat.SortedSet
import cats.data.NonEmptyList
import cats.data.NonEmptySet
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Console
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import fs2.Pipe
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path
import io.circe.syntax.*
import org.http4s.EntityDecoder
import org.http4s.ProductId
import org.http4s.Status.ClientError
import org.http4s.Status.NotFound
import org.http4s.Status.Successful
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.client.UnexpectedStatus
import org.http4s.client.middleware.GZip
import org.http4s.headers.`User-Agent`
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.syntax.*
import updater.gallery.Extension
import updater.gallery.Version

import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

object Main
    extends CommandIOApp(
      name = BuildInfo.name,
      header = BuildInfo.description,
      helpFlag = true,
      version = BuildInfo.version,
    ):

  private object O:
    val stateFile = Opts.argument[Path](metavar = "STATE-FILE")

    val parallelism =
      Opts.option[Int]("parallelism", "Maximum number of requests in flight", short = "p").withDefault(3)

    val debug: Opts[Boolean] = Opts.flag("debug", help = "Enable debug logging.", short = "d").orFalse

    enum Action:
      case List()
      case Update()
      case Add(extensionIds: NonEmptySet[ExtensionId])

    val list = Opts.subcommand("list", "List extensions in the state") {
      Opts(Action.List())
    }

    val update: Opts[Action] = Opts.subcommand("update", "Update extension versions") {
      Opts(Action.Update())
    }

    val add: Opts[Action] = Opts.subcommand("add", "Add extensions") {
      (Opts.arguments[ExtensionId]("extension-id")).map(ids => Action.Add(ids.toNes))
    }

    val action = list.orElse(update).orElse(add)

  implicit val logging: LoggerFactory[IO] = Slf4jFactory[IO]

  override val main: Opts[IO[ExitCode]] =
    (O.debug, O.parallelism, O.stateFile, O.action).mapN { (debug, parallelism, stateFile, action) =>
      implicit val logger: Logger[IO] = logging.getLogger

      httpClientResource[IO](logging = debug).map(GZip(1024 * 1024)).use { http =>
        for
          current <- readJsonFile[IO, Packages](stateFile).handleError(_ => Packages.empty)
          updater = Updater[IO](http, parallelism, current)

          pkgs <- action.match
            case O.Action.List()            => updater.list
            case O.Action.Update()          => updater.update
            case O.Action.Add(extensionIds) => updater.add(extensionIds)

          _ <- if pkgs != current then pkgs.writeTo[IO](stateFile) else IO.unit
        yield ExitCode.Success
      }
    }
end Main

class Updater[F[_]: Concurrent: Console: LoggerFactory](
    http: Client[F],
    parallelism: Int,
    current: Packages,
):
  private implicit val logger: Logger[F] = LoggerFactory[F].getLogger
  private val gallery                    = Gallery[F](http)

  private val packagesFromExtension: Pipe[F, Extension[Version], Packages] =
    _.parEvalMapUnordered(parallelism) { ext =>
      toPackage(ext).map { pkg =>
        SortedMap(ext.publisher.publisherName -> SortedMap(ext.extensionName -> pkg))
      }
    }

  private val packagesFromExtensionId: Pipe[F, ExtensionId, Packages] =
    _.evalMap(gallery.byExtensionId)
      .through(packagesFromExtension)

  private def fetchOrUpdate(extensionIds: SortedSet[ExtensionId]): F[Packages] =
    Stream
      .emits(extensionIds.toSeq)
      .through(packagesFromExtensionId)
      .compile
      .foldMonoid

  private def toPackage(extension: Extension[Version]): F[SortedMap[NixSystem, Package]] =
    extension.versions
      .traverse { version =>
        (for
          system <- OptionT.fromOption(NixSystem.fromGallery(version.targetPlatform))

          publisher = extension.publisher.publisherName
          name      = extension.extensionName
          known     = current.get(publisher, name, system)

          hash = OptionT(
            http
              .getSri(version.packageUri)
              .notFoundIsNone
              .flatTap {
                case None =>
                  warn"not found ${extension.itemName} ${version.version} ${version.targetPlatform}"
                case _ =>
                  info"downloaded ${extension.itemName} ${version.version} ${version.targetPlatform}"
              }
          )

          downloaded = hash.map { it =>
            Package(
              publisher = publisher,
              name = name,
              version = version.version,
              arch = version.targetPlatform.getOrElse(""),
              sha256 = it,
            )
          }

          pkg <- if known.exists(_.version == version.version) then OptionT.fromOption(known) else downloaded
        yield system -> pkg).value
      }
      .map(it => SortedMap.from(it.unite))

  val list: F[Packages] = current.extensionIds.toSeq.traverse(Console[F].println).as(current)

  val update: F[Packages] = fetchOrUpdate(current.extensionIds)

  def add(extensionIds: NonEmptySet[ExtensionId]): F[Packages] =
    for added <- fetchOrUpdate(extensionIds.toSortedSet)
    yield current |+| added
