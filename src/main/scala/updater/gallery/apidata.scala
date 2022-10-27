package updater
package gallery

import io.circe.{Codec, Decoder, Encoder}
import org.http4s.Uri

import java.time.Instant
import java.util.UUID

case class Request(
    filters: Seq[Filter],
    flags: Int,
) derives Codec.AsObject

object Request:
  def apply(flags: Set[Flag], filters: Filter*): Request =
    new Request(filters = filters, flags = flags.toInt)

case class Filter(
    criteria: Seq[Criteria],
    pageNumber: Option[Int],
    pageSize: Option[Int],
) derives Codec.AsObject

object Filter:
  def apply(criteria: Criteria*): Filter =
    Filter(criteria = criteria, pageNumber = None, pageSize = None)

case class Criteria(
    filterType: FilterType,
    value: String,
) derives Codec.AsObject

object Criteria:
  def target(value: String): Criteria =
    Criteria(filterType = FilterType.Target, value = value)

  def excludeWithFlags(value: Flag*): Criteria =
    Criteria(filterType = FilterType.ExcludeWithFlags, value = value.toSet.toInt.toString)

  def extensionName(publisher: String, name: String): Criteria =
    Criteria(filterType = FilterType.ExtensionName, value = s"$publisher.$name")

case class Response(
    results: Seq[Result]
) derives Codec.AsObject

case class Result(
    extensions: Seq[Extension[Version]],
    pagingToken: Option[String],
    resultMetadata: Seq[Metadata],
) derives Codec.AsObject

case class Metadata(
    metadataType: String,
    metadataItems: Seq[MetadataItem],
) derives Codec.AsObject

case class MetadataItem(name: String, count: Int) derives CanEqual, Codec.AsObject

case class Extension[V](
    publisher: Publisher,
    extensionId: UUID,      // "f1f59ae4-9318-4f3c-a9b5-81b2eaa5f8a5"
    extensionName: String,  // "python"
    displayName: String,    // "Python"
    flags: String,          // "validated, public"
    lastUpdated: Instant,   // "2022-09-20T10:17:06.127Z"
    publishedDate: Instant, // "2016-01-19T15:03:11.337Z"
    releaseDate: Instant,   // "2016-01-19T15:03:11.337Z"
    shortDescription: Option[
      String
    ], // "IntelliSense (Pylance), Linting, Debugging (multi-threaded, remote), Jupyter Notebooks, code formatting, refactoring, unit tests, and more."
    versions: Seq[V],
    deploymentType: Int, // 0
) derives Codec.AsObject {
  val itemName: String = s"${publisher.publisherName}.$extensionName"
}

case class Publisher(
    publisherId: UUID,        // "998b010b-e2af-44a5-a6cd-0b5fd3b9b6f8"
    publisherName: String,    // "ms-python"
    displayName: String,      // "Microsoft"
    flags: String,            // "verified"
    domain: Option[Uri],      // "https://microsoft.com"
    isDomainVerified: Boolean,// true,
) derives Codec.AsObject

case class Version(
    version: String,                // "1.13.0"
    targetPlatform: Option[String], // "alpine-arm64"
    flags: String,                  // "validated"
    lastUpdated: Instant,           // "2022-09-07T23:08:57.267Z"
    assetUri: Uri, // "https://ms-vscode.gallerycdn.vsassets.io/extensions/ms-vscode/cpptools/1.13.0/1662591842265"
    fallbackAssetUri: Uri, // "https://ms-vscode.gallery.vsassets.io/_apis/public/gallery/publisher/ms-vscode/extension/cpptools/1.13.0/assetbyname"
) derives Codec.AsObject {

  val key = s"${version}_${targetPlatform.getOrElse("all")}"

  val packageUri: Uri =
    (assetUri / "Microsoft.VisualStudio.Services.VSIXPackage")
      .withOptionQueryParam("targetPlatform", targetPlatform)
}

enum Flag(val bits: Int):
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

enum FilterType(val toInt: Int):
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
    decodeA = Decoder[Int].emap(int => FilterType.values.find(_.toInt == int).toRight("Blerp")),
    encodeA = Encoder[Int].contramap(_.toInt),
  )

extension (flags: Set[Flag]) def toInt: Int = flags.map(_.bits).fold(0)(_ | _)
