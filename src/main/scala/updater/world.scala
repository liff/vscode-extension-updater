package updater

import cats.Monad
import cats.effect.Sync
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.text.utf8
import io.circe.fs2.{byteStreamParser, decoder}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

import scala.collection.immutable.SortedMap

def readJsonFile[F[_]: Files: Sync, A: Decoder](path: Path): F[A] =
  Files[F].readAll(path).through(byteStreamParser).through(decoder[F, A]).compile.lastOrError

extension [A: Encoder](a: A)
  def writeTo[F[_]: Files: Sync](path: Path): F[Unit] =
    Stream.emit(a.asJson.spaces4).through(utf8.encode).through(Files[F].writeAll(path)).compile.drain

extension [K: Ordering, V](m: SortedMap[K, V]) def mapV[W](f: V => W): SortedMap[K, W] = m.map((k, v) => k -> f(v))
