package tw.idv.idiotech.ghostspeak.agent.jsoncodec

import io.circe.{ Codec, Decoder, Encoder }
import io.circe.derivation.{ ConfiguredCodec, ConfiguredDecoder, ConfiguredEncoder }

import scala.deriving.Mirror

/** Configured circe derivation, Scala 3 side: circe's built-in io.circe.derivation. Same names
  * as the Scala 2 side (src/main/scala-2), which uses circe-generic-extras.
  */
type Configuration = io.circe.derivation.Configuration
val Configuration: io.circe.derivation.Configuration.type = io.circe.derivation.Configuration

object semiauto {

  inline def deriveConfiguredCodec[A](using
    inline mirror: Mirror.Of[A],
    conf: Configuration
  ): Codec.AsObject[A] =
    ConfiguredCodec.derived[A]

  inline def deriveConfiguredEncoder[A](using
    inline mirror: Mirror.Of[A],
    conf: Configuration
  ): Encoder.AsObject[A] =
    ConfiguredEncoder.derived[A]

  inline def deriveConfiguredDecoder[A](using
    inline mirror: Mirror.Of[A],
    conf: Configuration
  ): Decoder[A] =
    ConfiguredDecoder.derived[A]
}
