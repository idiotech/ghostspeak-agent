package tw.idv.idiotech.ghostspeak.agent

import io.circe.{ Codec, Decoder, Encoder }
import io.circe.generic.extras.codec.ConfiguredAsObjectCodec
import io.circe.generic.extras.decoding.ConfiguredDecoder
import io.circe.generic.extras.encoding.ConfiguredAsObjectEncoder
import shapeless.Lazy

/** Configured circe derivation, Scala 2 side: circe-generic-extras. The Scala 3 side
  * (src/main/scala-3) exposes the same names on top of circe's built-in io.circe.derivation, so
  * the codecs are written once for both versions.
  */
package object jsoncodec {

  type Configuration = io.circe.generic.extras.Configuration

  val Configuration: io.circe.generic.extras.Configuration.type =
    io.circe.generic.extras.Configuration

  object semiauto {

    def deriveConfiguredCodec[A](implicit
      codec: Lazy[ConfiguredAsObjectCodec[A]]
    ): Codec.AsObject[A] =
      io.circe.generic.extras.semiauto.deriveConfiguredCodec[A]

    def deriveConfiguredEncoder[A](implicit
      encoder: Lazy[ConfiguredAsObjectEncoder[A]]
    ): Encoder.AsObject[A] =
      io.circe.generic.extras.semiauto.deriveConfiguredEncoder[A]

    def deriveConfiguredDecoder[A](implicit decoder: Lazy[ConfiguredDecoder[A]]): Decoder[A] =
      io.circe.generic.extras.semiauto.deriveConfiguredDecoder[A]
  }
}
