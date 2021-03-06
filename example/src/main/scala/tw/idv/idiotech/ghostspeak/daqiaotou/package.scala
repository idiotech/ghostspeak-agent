package tw.idv.idiotech.ghostspeak

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }
import json.schema.{ description, title, typeHint }
import tw.idv.idiotech.ghostspeak.agent.{ Action, Message }

import scala.collection.immutable

package object daqiaotou {

  implicit val configuration = Configuration.default
    .withDiscriminator("type")
    .withScreamingSnakeCaseConstructorNames
    .withDefaults

  @typeHint[String]
  sealed trait Destination extends EnumEntry

  object Destination extends Enum[Destination] with CirceEnum[Destination] {
    val values: immutable.IndexedSeq[Destination] = findValues

    case object App extends Destination with UpperSnakecase

    case object Intro extends Destination with UpperSnakecase

    case object Welcome extends Destination with UpperSnakecase

    case object Notification extends Destination with UpperSnakecase

  }

  @typeHint[String]
  sealed trait SoundType extends EnumEntry

  object SoundType extends Enum[SoundType] with CirceEnum[SoundType] {
    val values: immutable.IndexedSeq[SoundType] = findValues

    case object Main extends SoundType with UpperSnakecase

    case object Background extends SoundType with UpperSnakecase

    case object Loop extends SoundType with UpperSnakecase

  }

  @typeHint[String]
  sealed trait BeaconType extends EnumEntry

  object BeaconType extends Enum[BeaconType] with CirceEnum[BeaconType] {
    val values: immutable.IndexedSeq[BeaconType] = findValues

    case object Enter extends BeaconType with UpperSnakecase

    case object Exit extends BeaconType with UpperSnakecase

  }

  @ConfiguredJsonCodec
  case class Location(lat: Double, lon: Double) extends EventPayload

  @ConfiguredJsonCodec
  sealed trait Volume

  object Volume {

    case class StaticVolume(
      speechLength: Option[Int],
      fadeOutSeconds: Int = 10,
      fadeInSeconds: Int = 0
    ) extends Volume
    case class DynamicVolume(center: Location, radius: Int, minVolume: Double) extends Volume
  }

  @ConfiguredJsonCodec
  sealed trait Task

  object Task {

    @title("Popup message")
    @description("Client should show it as a notification or a popup window in the app.")
    case class Popup(
      @description("This is the message to be shown. Can be null.")
      text: Option[String] = None,
      @description(
        "They are acceptable choices from the user. They can be shown as buttons or drop-down menu depending on client design."
      )
      choices: List[String] = Nil,
      @description("If true, user is allowed to enter a text reply")
      allowTextReply: Boolean = false,
      @title("Picture URL to be shown")
      pictures: List[String] = Nil,
      @title("Destinations to be shown")
      destinations: Set[Destination] = Set.empty
    ) extends Task

    @title("Sound message")
    @description("Client should play the sound.")
    case class Sound(
      @description("URL of the sound file")
      url: String,
      @description("The length of the part of the sound file that contains speech")
      volumeSetting: Volume,
      @description("Controls whether the sound should be queued or looped")
      mode: SoundType
    ) extends Task

    @title("Marker message")
    @description("Client should display the marker on map.")
    case class Marker(
      @description("The marker should be shown at this location.")
      location: Location,
      @description("The icon image URL for the marker.")
      icon: String,
      @description("The marker title.")
      title: String
    ) extends Task

    @title("Marker removal")
    @description("Client should remove the marker from map.")
    case class MarkerRemoval(
      @description("The marker to delete; should be the action id that adds the marker.")
      id: String
    ) extends Task

  }

  @ConfiguredJsonCodec
  sealed trait Condition

  object Condition {

    @typeHint[String]
    case object Always extends Condition

    @title("Geofence")
    @description("Location condition for action.")
    case class Geofence(
      @description("Geographic coordinates")
      location: Location,
      @description("The radius of the geofence in meters.")
      radius: Int
    ) extends Condition

    @title("Beacon")
    @description("Beacon condition for action")
    case class Beacon(
      @description("Beacon ID")
      beaconId: String,
      @description("Threshold value for triggering")
      threshold: Int,
      @description("Whether to activate on enter or on exit")
      mode: BeaconType
    ) extends Condition

  }

  @ConfiguredJsonCodec
  case class Content(task: Task, condition: Condition)

  type Action = tw.idv.idiotech.ghostspeak.agent.Action[Content]
  type Message = tw.idv.idiotech.ghostspeak.agent.Message[EventPayload]

  @ConfiguredJsonCodec
  sealed trait EventPayload

  object EventPayload {

    case class Text(text: String) extends EventPayload

  }

  implicit lazy val contentEncoder = Action.encoder[Content]
  implicit lazy val contentDecoder = Action.decoder[Content]
  implicit lazy val messageEncoder = Message.encoder[EventPayload]
  implicit lazy val messageDecoder = Message.decoder[EventPayload]
}
