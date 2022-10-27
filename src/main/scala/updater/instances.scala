package updater

import com.monovore.decline.Argument
import fs2.io.file.Path

export org.http4s.circe.{decodeUri, encodeUri}
export org.typelevel.log4cats.slf4j.loggerFactoryforSync

given Argument[Path] = Argument.readPath.map(Path.fromNioPath)
