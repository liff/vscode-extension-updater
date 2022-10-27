package updater

import cats.Order
import cats.effect.Concurrent
import org.http4s.{EntityDecoder, Uri}
import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}

import java.time.Instant
import java.util.UUID

opaque type Sri = String

object Sri:
  given Order[Sri] = cats.instances.string.catsKernelStdOrderForString

  given Encoder[Sri] = Encoder.encodeString
  given Decoder[Sri] = Decoder.decodeString

  given [F[_]: Concurrent]: EntityDecoder[F, Sri] = EntityDecoder.text[F].map(_.takeWhile(_ != ' '))

  def sha256(enc: String): Sri = s"sha256-$enc"

enum NixSystem(override val toString: String):
  case X86_64_Linux   extends NixSystem("x86_64-linux")
  case Aarch64_Linux  extends NixSystem("aarch64-linux")
  case X86_64_Darwin  extends NixSystem("x86_64-darwin")
  case Aarch64_Darwin extends NixSystem("aarch64-darwin")
  case All            extends NixSystem("all")

object NixSystem:
  def fromGallery(targetPlatform: Option[String]): Option[NixSystem] =
    targetPlatform.match
      case Some("linux-x64")    => Some(X86_64_Linux)
      case Some("linux-arm64")  => Some(Aarch64_Linux)
      case Some("darwin-x64")   => Some(X86_64_Darwin)
      case Some("darwin-arm64") => Some(Aarch64_Darwin)
      case None                 => Some(All)
      case _                    => None

  def fromString(string: String): Option[NixSystem] = string.match {
    case X86_64_Linux.toString   => Some(X86_64_Linux)
    case Aarch64_Linux.toString  => Some(Aarch64_Linux)
    case X86_64_Darwin.toString  => Some(X86_64_Darwin)
    case Aarch64_Darwin.toString => Some(Aarch64_Darwin)
    case All.toString            => Some(All)
    case _                       => None
  }

  given KeyEncoder[NixSystem] = KeyEncoder[String].contramap(_.toString)
  given KeyDecoder[NixSystem] = KeyDecoder.instance(fromString)

  given Encoder[NixSystem] = Encoder[String].contramap(_.toString)
  given Decoder[NixSystem] = Decoder[String].emap(fromString(_).toRight(s"Unrecognized Nix system"))

type Publisher = String
type Name      = String

case class Package(
    publisher: Publisher,
    name: Name,
    version: String,
    arch: String,
    sha256: Sri,
) derives Codec.AsObject

type Packages = Map[Publisher, Map[Name, Map[NixSystem, Package]]]

extension (pkgs: Packages)
  def get(publisher: Publisher, name: Name, system: NixSystem): Option[Package] =
    for
      names   <- pkgs.get(publisher)
      systems <- names.get(name)
      pkg     <- systems.get(system)
    yield pkg
