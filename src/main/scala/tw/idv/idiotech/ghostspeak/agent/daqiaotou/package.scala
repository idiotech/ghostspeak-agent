package tw.idv.idiotech.ghostspeak.agent

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{CirceEnum, Enum, EnumEntry}
import io.circe.generic.extras.{Configuration, ConfiguredJsonCodec}

import scala.collection.immutable
import json.schema._
package object daqiaotou {

  private implicit val configuration = Configuration.default
    .withDiscriminator("type")
    .copy(transformConstructorNames = _.toUpperCase())
    @typeHint[String]
    sealed trait Destination extends EnumEntry

    object Destination extends Enum[Destination] with CirceEnum[Destination] {
      val values: immutable.IndexedSeq[Destination] = findValues
      case object App extends Destination with UpperSnakecase
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
    sealed trait OperationType extends EnumEntry

    object OperationType extends Enum[OperationType] with CirceEnum[OperationType] {
      val values: immutable.IndexedSeq[OperationType] = findValues
      case object Add extends OperationType with UpperSnakecase
      case object Delete extends OperationType with UpperSnakecase
    }

    @typeHint[String]
    sealed trait BeaconType extends EnumEntry

    object BeaconType extends Enum[BeaconType] with CirceEnum[BeaconType] {
      val values: immutable.IndexedSeq[BeaconType] = findValues
      case object Enter extends BeaconType with UpperSnakecase
      case object Exit extends BeaconType with UpperSnakecase
    }

    @ConfiguredJsonCodec
    case class Location(lat: Double, lon: Double) extends DaqiaotouPayload

    @ConfiguredJsonCodec
    sealed trait Task

    object Task {

      @title("Popup message")
      @description("Client should show it as a notification or a popup window in the app.")
      case class Popup(
        @description("This is the message to be shown. Can be null.")
        text: Option[String],
        @description(
          "They are acceptable choices from the user. They can be shown as buttons or drop-down menu depending on client design."
        )
        choices: List[String],
        @description("If true, user is allowed to enter a text reply")
        allowTextReply: Boolean,
        @title("Picture URL to be shown")
        picture: Option[String],
        @title("Destinations to be shown")
        destinations: Set[Destination]
      ) extends Task

      @title("Sound message")
      @description("Client should play the sound.")
      case class Sound(
        @description("URL of the sound file")
        url: String,
        @description("The length of the part of the sound file that contains speech")
        speechLength: Option[Int],
        @description("Controls whether the sound should be queued or looped")
        `type`: SoundType
      ) extends Task

      @title("Marker message")
      @description("Client should display the marker on map.")
      case class Marker(
        @description("Unique ID for a marker instance.")
        id: String,
        @description("The marker should be shown at this location.")
        location: Location,
        @description("The icon image URL for the marker.")
        icon: String,
        @description("This field tells the client whether to add or delete this marker.")
        operation: OperationType
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
        id: String,
        @description("Threshold value for triggering")
        threshold: Int,
        @description("Whether to activate on enter or on exit")
        `type`: BeaconType
      ) extends Condition
    }

  @ConfiguredJsonCodec
  case class Content(task: Task, condition: Condition)

  @ConfiguredJsonCodec
  sealed trait DaqiaotouPayload

  object DaqiaotouPayload {

    case class Text(text: String) extends DaqiaotouPayload
  }

}
