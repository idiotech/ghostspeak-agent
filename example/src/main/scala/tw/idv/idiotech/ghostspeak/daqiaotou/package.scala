package tw.idv.idiotech.ghostspeak

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.generic.extras.{ Configuration, ConfiguredJsonCodec }
import json.schema.{ description, title, typeHint }
import com.redis._
import com.typesafe.config.ConfigFactory
import tw.idv.idiotech.ghostspeak.agent.{ Action, Message }
import io.circe.config.syntax._

import scala.collection.immutable

package object daqiaotou {

  implicit val configuration = Configuration.default
    .withDiscriminator("type")
    .withScreamingSnakeCaseConstructorNames
    .withDefaults

  @ConfiguredJsonCodec
  case class RedisConf(host: String, port: Int)

  @ConfiguredJsonCodec
  case class IconConf(pending: String, arrived: String)

  @ConfiguredJsonCodec
  case class MysqlConf(
    host: String,
    port: Int,
    username: String,
    password: String,
    database: String
  )

  @ConfiguredJsonCodec
  case class DaqiaotouConf(
    engine: String = "graphscript",
    redis: RedisConf,
    icon: IconConf,
    mysql: MysqlConf
  )

  lazy val config: DaqiaotouConf = {
    ConfigFactory.load().as[DaqiaotouConf]("app").fold(throw _, identity)
  }

  val redis = new RedisClientPool(config.redis.host, config.redis.port)

  @typeHint[String]
  sealed trait Destination extends EnumEntry

  object Destination extends Enum[Destination] with CirceEnum[Destination] {
    val values: immutable.IndexedSeq[Destination] = findValues

    case object App extends Destination with UpperSnakecase

    case object Alert extends Destination with UpperSnakecase

    case object Intro extends Destination with UpperSnakecase

    case object Welcome extends Destination with UpperSnakecase

    case object Notification extends Destination with UpperSnakecase

    case object Dialer extends Destination with UpperSnakecase
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

  @typeHint[String]
  sealed trait IncomingCallType extends EnumEntry

  object IncomingCallType extends Enum[IncomingCallType] with CirceEnum[IncomingCallType] {
    val values: immutable.IndexedSeq[IncomingCallType] = findValues

    case object Connecting extends IncomingCallType with UpperSnakecase
    case object Connected extends IncomingCallType with UpperSnakecase
    case object Disconnected extends IncomingCallType with UpperSnakecase

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

  @typeHint[String]
  sealed trait Operation extends EnumEntry

  object Operation extends Enum[Operation] with CirceEnum[Operation] {
    val values: immutable.IndexedSeq[Operation] = findValues
    case object + extends Operation with UpperSnakecase
    case object - extends Operation with UpperSnakecase
    case object `=` extends Operation with UpperSnakecase
  }

  @ConfiguredJsonCodec
  case class VariableUpdate(name: String, operation: Operation, value: Int) extends Task

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
      destinations: Set[Destination] = Set.empty,
      @title("Close alert popup after reply")
      closeAlertAfterReply: Boolean = true,
      @title("Clear dialog")
      clearDialog: Boolean = false
    ) extends Task

    @title("Close popup message")
    @description("Client should close popup message")
    case class PopupDismissal(
      @description("Destinations to be dismissed")
      destinations: Set[Destination] = Set.empty
    ) extends Task

    @title("Incoming call")
    @description("Client should show an incoming call message.")
    case class IncomingCall(
      @description("The marker to delete; should be the action id that adds the marker.")
      caller: String,
      status: IncomingCallType,
      portrait: String
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
      title: String,
      @description("The action to call on click")
      onClickActionId: Option[String] = None
    ) extends Task

    @title("Marker removal")
    @description("Client should remove the marker from map.")
    case class MarkerRemoval(
      @description("The marker to delete; should be the action id that adds the marker.")
      id: String
    ) extends Task

    @title("Map style")
    @description("Client should change the style of the map")
    case class MapStyle(
      url: Option[String],
      satellite: Boolean = false
    ) extends Task

    @title("Intro image")
    @description("The background image to show ")
    case class IntroImage(
      backgroundUrl: Option[String],
      logoUrl: Option[String],
      logoMarginTop: Option[Float] = None,
      logoWidth: Option[Float] = None,
      logoHeight: Option[Float] = None,
      mapLogoUrl: Option[String]
    ) extends Task

    @title("Button style")
    @description("The colors of a button")
    case class ButtonStyle(backgroundColor: String, textColor: String) extends Task

    @title("Popup style")
    @description("The colors of a popup window")
    case class PopupStyle(alertTextColor: String) extends Task

    @title("Variable update")
    @description("The operation to update the value of a variable")
    case class VariableUpdates(updates: List[VariableUpdate]) extends Task

    @title("Finish the scenario")
    @description("Client should return to homepage")
    case object EndGame extends Task

    @title("A image on the bottom of the map to guide user to go somewhere")
    @description("Add the guide image")
    case class GuideImage(image: String) extends Task

    @title("guide image removal")
    @description("Remove the guide image")
    case object GuideImageRemoval extends Task

    @title("Silence a sound")
    @description("Make a sound by the ID stop")
    case class Silence(id: String, fadeOutSeconds: Int) extends Task
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
  case class Content(task: Task, condition: Condition, exclusiveWith: List[String] = Nil)

  type Action = tw.idv.idiotech.ghostspeak.agent.Action[Content]
  type Message = tw.idv.idiotech.ghostspeak.agent.Message[EventPayload]

  @ConfiguredJsonCodec
  sealed trait EventPayload

  object EventPayload {

    case class Text(text: String, asVariable: Option[String] = None) extends EventPayload
    case object GoldenFinger extends EventPayload
    case class PerformDirectly(action: Action) extends EventPayload

  }

  implicit lazy val contentEncoder = Action.encoder[Content]
  implicit lazy val contentDecoder = Action.decoder[Content]
  implicit lazy val messageEncoder = Message.encoder[EventPayload]
  implicit lazy val messageDecoder = Message.decoder[EventPayload]

  @ConfiguredJsonCodec
  case class PerfLog(user: String, action: String, stage: String)
}
