package updater

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, MonadCancelThrow, Sync}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.http4s.Method.POST
import org.http4s.Status.Successful
import org.http4s.circe.{jsonEncoderOf, jsonOfWithMedia}
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.middleware.GZip
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.{`Accept-Encoding`, `Content-Length`, `Content-Type`}
import org.http4s.implicits.*
import org.http4s.*
import org.typelevel.ci.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}
import updater.gallery.{Extension, FilterType, Flag, Response, Result, Version}

class Gallery[F[_]: LoggerFactory](client: Client[F], pageSize: Int = 3) extends Http4sClientDsl[F]:
  private implicit val logger: Logger[F] = LoggerFactory[F].getLogger

  private val firstPage = 1

  private def post(request: gallery.Request): Request[F] =
    POST(request, baseUri).putHeaders(accept, contentType)

  private def page(number: Int): Request[F] =
    post(ExtensionRequests.all(number, pageSize))

  private def fetchPage(number: Int)(using F: Concurrent[F]): F[gallery.Response] =
    client.expectDirect(page(number))

  def byName(publisher: String, name: String)(using F: Concurrent[F]): F[gallery.Extension[Version]] =
    client.expectDirect[gallery.Response](post(ExtensionRequests.byName(publisher, name))).flatMap {
      case Response(Seq(Result(Seq(extension), _, _))) => F.pure(extension)
      case Response(Seq())                             => F.raiseError(NoResults())
      case Response(Seq(Result(Seq(), _, _)))          => F.raiseError(NoExtensions())
      case unexpected @ Response(_)                    => F.raiseError(UnexpectedResponse(unexpected))
    }

  def all(using F: Concurrent[F]): Stream[F, Extension[Version]] =
    Stream.unfoldChunkEval(firstPage) { n =>
      if n == -1 then None.pure[F]
      else
        info"getting page($n)" *> fetchPage(n).map {
          case Response(Seq(Result(extensions, _, _))) if extensions.nonEmpty =>
            Some((Chunk.seq(extensions), n + 1))

          case _ => None
        }
    }

case class NoResults()    extends RuntimeException("No results to extension query")
case class NoExtensions() extends RuntimeException("No extensions listed in extension query result")
case class UnexpectedResponse(response: gallery.Response) extends RuntimeException(s"Unexpected response: $response")

private object ExtensionRequests:
  def all(pageNumber: Int, pageSize: Int): gallery.Request =
    all(pageNumber, pageSize, defaultFlags)

  def all(pageNumber: Int, pageSize: Int, flags: Set[Flag]): gallery.Request =
    all(Some(pageNumber), Some(pageSize), flags)

  def all(
      pageNumber: Option[Int],
      pageSize: Option[Int],
      flags: Set[Flag] = defaultFlags,
  ): gallery.Request = {
    import gallery.*

    gallery.Request(
      flags,
      Filter(
        Criteria.target("Microsoft.VisualStudio.Code"),
        Criteria.excludeWithFlags(Flag.Unpublished),
      ).copy(pageNumber = pageNumber, pageSize = pageSize),
    )
  }

  def byName(publisher: String, name: String, flags: Set[Flag] = defaultFlags): gallery.Request = {
    import gallery.*

    gallery.Request(
      flags,
      Filter(
        Criteria.target("Microsoft.VisualStudio.Code"),
        Criteria.excludeWithFlags(Flag.Unpublished),
        Criteria.extensionName(publisher, name),
      ),
    )
  }

  val defaultFlags: Set[Flag] = Set(
    Flag.IncludeVersions,
    Flag.ExcludeNonValidated,
    Flag.IncludeAssetUri,
    Flag.IncludeLatestVersionOnly,
  )

private val baseUri = uri"https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery"

private val apiVersion = Map("api-version" -> "3.0-preview.1")

private val media = MediaType.application.json.withExtensions(apiVersion)

private given galleryEntityDecoder[F[_]: Concurrent, A: Decoder]: EntityDecoder[F, A] = jsonOfWithMedia[F, A](media)
private given galleryEntityEncoder[A: Encoder]: EntityEncoder.Pure[A] =
  jsonEncoderOf[A].withContentType(`Content-Type`(media))

private val accept      = Header.Raw(ci"Accept", "application/json; api-version=3.0-preview.1")
private val contentType = Header.Raw(ci"Content-Type", "application/json; api-version=3.0-preview.1")
