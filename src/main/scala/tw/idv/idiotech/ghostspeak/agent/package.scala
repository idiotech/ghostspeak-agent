package tw.idv.idiotech.ghostspeak

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{CirceEnum, Enum, EnumEntry}
import io.circe.generic.extras.{Configuration, ConfiguredJsonCodec}

import scala.collection.immutable

package object agent {

  private implicit val configuration = Configuration.default.withDiscriminator("type")

  sealed trait Destination extends EnumEntry
  object Destination extends Enum[Destination] with CirceEnum[Destination] {
    val values: immutable.IndexedSeq[Destination] = findValues
    case object App extends Destination with UpperSnakecase
    case object Notification extends Destination with UpperSnakecase
  }
  sealed trait SoundType extends EnumEntry
  object SoundType extends Enum[SoundType] with CirceEnum[SoundType] {
    val values: immutable.IndexedSeq[SoundType] = findValues
    case object Main extends SoundType with UpperSnakecase
    case object Background extends SoundType with UpperSnakecase
    case object Loop extends SoundType with UpperSnakecase
  }
  @ConfiguredJsonCodec
  sealed trait Content
  object Content {
    case class Text(text: String, choices: List[String], allowTextReply: Boolean, destinations: Set[Destination]) extends Content
    case class Sound(url: String, speechLength: Option[Int], `type`: SoundType) extends Content
  }
  @ConfiguredJsonCodec
  case class Geofence(lat: Double, lon: Double, radius: Int)
  @ConfiguredJsonCodec
  case class Action(receiver: String, sender: String, geofence: Option[Geofence], content: Content)


}
