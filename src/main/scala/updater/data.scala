package updater

import cats.derived.*
import cats.syntax.all.*
import cats.Order
import cats.Show
import cats.effect.Concurrent
import io.circe.*
import org.http4s.{EntityDecoder, Uri}

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import cats.data.Validated
import cats.data.Validated.Valid
import cats.data.Validated.Invalid
import com.monovore.decline.Argument
import cats.Monoid

opaque type Sri = String

object Sri:
  given CanEqual[Sri, Sri] = CanEqual.derived
  given Order[Sri]         = cats.instances.string.catsKernelStdOrderForString

  given Encoder[Sri] = Encoder.encodeString
  given Decoder[Sri] = Decoder.decodeString

  given [F[_]: Concurrent]: EntityDecoder[F, Sri] = EntityDecoder.text[F].map(_.takeWhile(_ != ' '))

  def sha256(enc: String): Sri = s"sha256-$enc"

enum NixSystem(override val toString: String) derives CanEqual, Order:
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

case class ExtensionId(
    publisher: Publisher,
    name: Name,
) derives CanEqual,
      Order:
  override def toString(): String = s"$publisher.$name"

object ExtensionId:
  def parse(string: String): Validated[String, ExtensionId] =
    string
      .trim()
      .split('.')
      .match
        case Array(publisher, name) if publisher.nonEmpty && name.nonEmpty => Valid(ExtensionId(publisher, name))
        case _ => Invalid(s"extension ID format must be `publisher.name`, got '$string'")

  given Show[ExtensionId] = Show.fromToString

  given Argument[ExtensionId] = new Argument[ExtensionId] {
    override def read(string: String)   = parse(string).toValidatedNel
    override val defaultMetavar: String = "EXTENSION-ID"
  }

case class Package(
    publisher: Publisher,
    name: Name,
    version: String,
    arch: String,
    sha256: Sri,
) derives CanEqual,
      Order,
      Codec.AsObject:
  val extensionId: ExtensionId = ExtensionId(publisher, name)

type Packages = SortedMap[Publisher, SortedMap[Name, SortedMap[NixSystem, Package]]]

given CanEqual[Packages, Packages] = CanEqual.derived

object Packages:
  val empty: Packages = SortedMap.empty

given Monoid[SortedMap[NixSystem, Package]] =
  Monoid.instance(SortedMap.empty, _ ++ _)

extension (pkgs: Packages)
  def allPackages: SortedSet[Package] =
    SortedSet.from(pkgs.flatMap { (_, names) => names.flatMap { (_, systems) => systems.values } })

  def extensionIds: SortedSet[ExtensionId] =
    allPackages.map(_.extensionId)

  def get(publisher: Publisher, name: Name, system: NixSystem): Option[Package] =
    for
      names   <- pkgs.get(publisher)
      systems <- names.get(name)
      pkg     <- systems.get(system)
    yield pkg
