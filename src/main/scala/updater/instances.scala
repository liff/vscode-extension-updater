package updater

import cats.data.ValidatedNel
import cats.{Order, Show}
import cats.syntax.all.*
import com.monovore.decline.Argument
import fs2.io.file.Path
import just.semver.SemVer
import io.circe.{Codec, Decoder, Encoder}
import scala.util.matching.Regex

export org.http4s.circe.{decodeUri, encodeUri}
export org.typelevel.log4cats.slf4j.loggerFactoryforSync
export cats.instances.order.catsKernelOrderingForOrder

given Argument[Path] = Argument.readPath.map(Path.fromNioPath)

private val patchless: Regex = """^(\d+)\.(\d+)$""".r

private def addPatchIfMissing(str: String): String =
  if patchless.matches(str) then str ++ ".0" else str

given Codec[SemVer] = Codec.from(
  decodeA = Decoder[String].emap(str => SemVer.parse(addPatchIfMissing(str)).leftMap(_.render)),
  encodeA = Encoder[String].contramap(_.render),
)

given Argument[SemVer] = new Argument[SemVer]:
  override def read(string: String): ValidatedNel[String, SemVer] =
    SemVer.parse(string).leftMap(_.render).toValidatedNel

  override val defaultMetavar: String = "SEMVER"

given Order[SemVer] = Order.fromComparable[SemVer]
