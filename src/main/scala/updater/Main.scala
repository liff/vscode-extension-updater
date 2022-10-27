package updater

import cats.data.OptionT
import cats.effect.{Concurrent, ExitCode, IO, Resource}
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import org.http4s.Status.{ClientError, NotFound, Successful}
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.middleware.GZip
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.`User-Agent`
import org.http4s.{EntityDecoder, ProductId}
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import updater.gallery.{Extension, Version}

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

  implicit val logging: LoggerFactory[IO] = Slf4jFactory[IO]

  private val stdin = fs2.io.stdin[IO](32 * 1024)

  override val main: Opts[IO[ExitCode]] =
    (O.debug, O.parallelism, O.stateFile).mapN { (debug, parallelism, stateFile) =>
      implicit val logger: Logger[IO] = logging.getLogger
      httpClientResource[IO](logging = debug, max = parallelism).map(GZip(1024 * 1024)).use { http =>
        val gallery = Gallery[IO](http)
        for
          current <- readJsonFile[IO, Packages](stateFile).handleError(_ => Map.empty)

          pkgsV <- stdin
            .through(fs2.text.utf8.decode)
            .through(fs2.text.lines)
            .map(_.trim)
            .filterNot(line => line.isEmpty || line.startsWith("#"))
            .map(_.split('.').toList)
            .evalMap {
              case List(publisher, name) => gallery.byName(publisher, name)
              case invalid =>
                IO.raiseError(RuntimeException(s"entry format must be `publisher.name`, got '$invalid'"))
            }
            .parEvalMapUnordered(parallelism) { ext =>
              toPackage[IO](http, current, ext).map { pkg =>
                Map(ext.publisher.publisherName -> Map(ext.extensionName -> pkg))
              }
            }
            .compile
            .foldMonoid: IO[Map[String, Map[String, Vector[(NixSystem, Package)]]]] // sucks

          pkgs = pkgsV.mapV(_.mapV(_.toMap)) // get rid of innermost Vector

          _ <- pkgs.writeTo[IO](stateFile)
        yield ExitCode.Success
      }
    }
end Main

def toPackage[F[_]: Concurrent: Logger](
    http: Client[F],
    current: Packages,
    extension: Extension[Version],
): F[Vector[(NixSystem, Package)]] =
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

        pkg <- if (known.exists(_.version == version.version)) then OptionT.fromOption(known) else downloaded
      yield system -> pkg).value
    }
    .map(_.unite.toVector)
