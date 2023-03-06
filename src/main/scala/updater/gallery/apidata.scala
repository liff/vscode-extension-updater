package updater
package gallery

import io.circe.{Codec, Decoder, Encoder}
import org.http4s.Uri
import cats.data.{NonEmptySet, Validated}
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID
import io.circe.KeyDecoder
import io.circe.KeyEncoder
import just.semver.{ParseError, SemVer}
import just.semver.matcher.SemVerMatchers
import scala.collection.immutable.SortedSet
import just.semver.matcher.SemVerMatcher

case class Request(
    filters: Seq[Filter],
    flags: Int,
) derives CanEqual,
      Codec.AsObject

object Request:
  def apply(flags: Set[Flag], filters: Filter*): Request =
    new Request(filters = filters, flags = flags.toInt)

case class Filter(
    criteria: Seq[Criteria],
    pageNumber: Option[Int],
    pageSize: Option[Int],
) derives CanEqual,
      Codec.AsObject

object Filter:
  def apply(criteria: Criteria*): Filter =
    Filter(criteria = criteria, pageNumber = None, pageSize = None)

case class Criteria(
    filterType: FilterType,
    value: String,
) derives CanEqual,
      Codec.AsObject

object Criteria:
  def target(value: String): Criteria =
    Criteria(filterType = FilterType.Target, value = value)

  def excludeWithFlags(value: Flag*): Criteria =
    Criteria(filterType = FilterType.ExcludeWithFlags, value = value.toSet.toInt.toString)

  def extensionName(publisher: String, name: String): Criteria =
    Criteria(filterType = FilterType.ExtensionName, value = s"$publisher.$name")

case class Response(
    results: Seq[Result]
) derives CanEqual,
      Codec.AsObject

case class Result(
    extensions: Seq[Extension],
    pagingToken: Option[String],
    resultMetadata: Seq[Metadata],
) derives CanEqual,
      Codec.AsObject

case class Metadata(
    metadataType: String,
    metadataItems: Seq[MetadataItem],
) derives CanEqual,
      Codec.AsObject

case class MetadataItem(name: String, count: Int) derives CanEqual, Codec.AsObject

extension (versions: Iterable[Version])
  def latestRelease: Option[Version] =
    versions.filterNot(_.properties.exists(_.preRelease)).toSeq.sortBy(_.version).lastOption

case class Extension(
    publisher: Publisher,
    extensionId: UUID,      // "f1f59ae4-9318-4f3c-a9b5-81b2eaa5f8a5"
    extensionName: String,  // "python"
    displayName: String,    // "Python"
    flags: String,          // "validated, public"
    lastUpdated: Instant,   // "2022-09-20T10:17:06.127Z"
    publishedDate: Instant, // "2016-01-19T15:03:11.337Z"
    releaseDate: Instant,   // "2016-01-19T15:03:11.337Z"
    shortDescription: Option[String],
    versions: Seq[Version],
    deploymentType: Int, // 0
) derives CanEqual,
      Codec.AsObject {
  val itemName: String = s"${publisher.publisherName}.$extensionName"

  def byTargetPlatform: Map[Option[String], Seq[Version]] =
    versions.groupBy(_.targetPlatform)

  def latestByTargetPlatform: Seq[Version] =
    byTargetPlatform.flatMap { case (p, vs) => vs.latestRelease.map(p -> _) }.values.toSeq
}
case class Publisher(
    publisherId: UUID,        // "998b010b-e2af-44a5-a6cd-0b5fd3b9b6f8"
    publisherName: String,    // "ms-python"
    displayName: String,      // "Microsoft"
    flags: String,            // "verified"
    domain: Option[Uri],      // "https://microsoft.com"
    isDomainVerified: Boolean,// true,
) derives CanEqual,
      Codec.AsObject

case class Version(
    version: SemVer,                // "1.13.0"
    targetPlatform: Option[String], // "alpine-arm64"
    flags: String,                  // "validated"
    lastUpdated: Instant,           // "2022-09-07T23:08:57.267Z"
    properties: Option[Properties],
    assetUri: Uri, // "https://ms-vscode.gallerycdn.vsassets.io/extensions/ms-vscode/cpptools/1.13.0/1662591842265"
    fallbackAssetUri: Uri, // "https://ms-vscode.gallery.vsassets.io/_apis/public/gallery/publisher/ms-vscode/extension/cpptools/1.13.0/assetbyname"
) derives CanEqual,
      Codec.AsObject {

  val key = s"${version}_${targetPlatform.getOrElse("all")}"

  val packageUri: Uri =
    (assetUri / "Microsoft.VisualStudio.Services.VSIXPackage")
      .withOptionQueryParam("targetPlatform", targetPlatform)
}

enum Flag(val bits: Int) derives CanEqual:
  /** None is used to retrieve only the basic extension details. */
  case None extends Flag(0x0)

  /** IncludeVersions will return version information for extensions returned */
  case IncludeVersions extends Flag(0x1)

  /** IncludeFiles will return information about which files were found within the extension that were stored
    * independent of the manifest. When asking for files, versions will be included as well since files are returned as
    * a property of the versions. These files can be retrieved using the path to the file without requiring the entire
    * manifest be downloaded.
    */
  case IncludeFiles extends Flag(0x2)

  /** Include the Categories and Tags that were added to the extension definition.
    */
  case IncludeCategoryAndTags extends Flag(0x4)

  /** Include the details about which accounts the extension has been shared with if the extension is a private
    * extension.
    */
  case IncludeSharedAccounts extends Flag(0x8)

  /** Include properties associated with versions of the extension */
  case IncludeVersionProperties extends Flag(0x10)

  /** Excluding non-validated extensions will remove any extension versions that either are in the process of being
    * validated or have failed validation.
    */
  case ExcludeNonValidated extends Flag(0x20)

  /** Include the set of installation targets the extension has requested. */
  case IncludeInstallationTargets extends Flag(0x40)

  /** Include the base uri for assets of this extension */
  case IncludeAssetUri extends Flag(0x80)

  /** Include the statistics associated with this extension */
  case IncludeStatistics extends Flag(0x100)

  /** When retrieving versions from a query, only include the latest version of the extensions that matched. This is
    * useful when the caller doesn't need all the published versions. It will save a significant size in the returned
    * payload.
    */
  case IncludeLatestVersionOnly extends Flag(0x200)

  /** This flag switches the asset uri to use GetAssetByName instead of CDN When this is used, values of base asset uri
    * and base asset uri fallback are switched When this is used, source of asset files are pointed to Gallery service
    * always even if CDN is available
    */
  case Unpublished extends Flag(0x1000)

  /** Include the details if an extension is in conflict list or not */
  case IncludeNameConflictInfo extends Flag(0x8000)
end Flag

enum FilterType(val toInt: Int) derives CanEqual:
  case Tag              extends FilterType(1)
  case ExtensionId      extends FilterType(4)
  case Category         extends FilterType(5)
  case ExtensionName    extends FilterType(7)
  case Target           extends FilterType(8)
  case Featured         extends FilterType(9)
  case SearchText       extends FilterType(10)
  case ExcludeWithFlags extends FilterType(12)

object FilterType:
  given Codec[FilterType] = Codec.from(
    decodeA = Decoder[Int].emap(int => FilterType.values.find(_.toInt == int).toRight("Unrecognized FilterType")),
    encodeA = Encoder[Int].contramap(_.toInt),
  )

extension (flags: Set[Flag]) def toInt: Int = flags.map(_.bits).fold(0)(_ | _)

private case class KeyValue(key: String, value: String) derives CanEqual, Codec.AsObject:
  def tupled: (String, String) = (key, value)

case class Properties(
    extensionDependencies: SortedSet[ExtensionId],
    extensionPack: SortedSet[ExtensionId],
    engine: Option[String],
    preRelease: Boolean,
) derives CanEqual

extension (properties: Map[String, String])
  private def extensionIds(key: String): Validated[String, SortedSet[ExtensionId]] =
    properties
      .get(key)
      .map(_.trim().nn)
      .filter(_.nonEmpty)
      .map(
        _.split(',').toSeq
          .traverse(ExtensionId.parse)
          .map(SortedSet.from)
      )
      .getOrElse(Validated.Valid(SortedSet.empty))

object Properties:
  given Decoder[Properties] = Decoder[Seq[KeyValue]].map(_.map(_.tupled).toMap).emap { entries =>
    val extensionDependencies: Validated[String, SortedSet[ExtensionId]] =
      entries.extensionIds("Microsoft.VisualStudio.Code.ExtensionDependencies")
    val extensionPack: Validated[String, SortedSet[ExtensionId]] =
      entries.extensionIds("Microsoft.VisualStudio.Code.ExtensionPack")
    val engine: Validated[String, Option[String]] =
      Validated.Valid(entries.get("Microsoft.VisualStudio.Code.Engine"))
    val preRelease = Validated
      .catchNonFatal(entries.getOrElse("Microsoft.VisualStudio.Code.PreRelease", "false").toBoolean)
      .leftMap(_.getMessage.nn)
    (extensionDependencies, extensionPack, engine, preRelease).mapN(Properties.apply).toEither
  }

  given Encoder[Properties] = Encoder[Seq[KeyValue]].contramap { properties =>
    val b = Seq.newBuilder[KeyValue]
    if (properties.extensionDependencies.nonEmpty) then
      b += KeyValue(
        "Microsoft.VisualStudio.Code.ExtensionDependencies",
        properties.extensionDependencies.mkString_(","),
      )
    if (properties.extensionPack.nonEmpty) then
      b += KeyValue("Microsoft.VisualStudio.Code.ExtensionPack", properties.extensionPack.mkString_(","))
    properties.engine.foreach { engine =>
      b += KeyValue("Microsoft.VisualStudio.Code.Engine", engine)
    }
    if (properties.preRelease) then b += KeyValue("Microsoft.VisualStudio.Code.PreRelease", "true")
    b.result()
  }
