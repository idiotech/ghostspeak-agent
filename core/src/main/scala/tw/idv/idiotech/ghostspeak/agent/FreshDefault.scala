package tw.idv.idiotech.ghostspeak.agent

import io.circe.{ Codec, Decoder, Json }

/** Configured decoding fills a missing field with the case class default, but computes that default
  * once per decoder (circe-generic-extras and Scala 3 io.circe.derivation alike). A default that
  * must be new on every decode (a random ID, the current time) is filled in here instead, before
  * the derived decoder runs. Encoding is unchanged, and the encoder always writes the field, so
  * only input that lacks it (a client request, data written before the field existed) gets a fresh
  * value; a journal entry or snapshot written by this code decodes to the value it was written
  * with.
  */
object FreshDefault {

  /** Adds `field` with a fresh `value` to a JSON object that lacks it; with `onlyForType`, only to
    * objects whose "type" discriminator has that value (one case of a sealed trait).
    */
  def apply[A](
    codec: Codec.AsObject[A],
    field: String,
    onlyForType: Option[String] = None
  )(value: => Json): Codec.AsObject[A] = {
    val decoder: Decoder[A] = codec.prepare(
      _.withFocus(_.mapObject { o =>
        val applies = onlyForType.forall(t => o("type").flatMap(_.asString).contains(t))
        if (applies && !o.contains(field)) o.add(field, value) else o
      })
    )
    Codec.AsObject.from(decoder, codec)
  }
}
