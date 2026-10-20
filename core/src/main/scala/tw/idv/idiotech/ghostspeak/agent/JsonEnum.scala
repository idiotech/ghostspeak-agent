package tw.idv.idiotech.ghostspeak.agent

import io.circe.{ Codec, Decoder, Encoder }

/** For the companion of a Scala 3 enum: a JSON codec that writes each case as a string, by default
  * its name in UPPER_SNAKE_CASE (TwoInARow is "TWO_IN_A_ROW").
  */
trait JsonEnum[E <: scala.reflect.Enum] {
  def values: Array[E]

  def jsonName(e: E): String = JsonEnum.upperSnakeCase(e.toString)

  private lazy val byJsonName: Map[String, E] = values.map(e => jsonName(e) -> e).toMap

  given Codec[E] = Codec.from(
    Decoder.decodeString.emap(s =>
      byJsonName.get(s).toRight(s"'$s' is not one of ${byJsonName.keys.mkString(", ")}")
    ),
    Encoder.encodeString.contramap(jsonName)
  )
}

object JsonEnum {

  def upperSnakeCase(name: String): String =
    name
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .toUpperCase
}
