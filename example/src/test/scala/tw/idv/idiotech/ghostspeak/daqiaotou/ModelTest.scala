package tw.idv.idiotech.ghostspeak.daqiaotou

import json._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent._
//import json.schema._
import com.github.andyglow.jsonschema.AsCirce._
import json.schema.Version._

class ModelTest extends AnyFlatSpec with Matchers {

  "model" must "translate to json" in {
    val action = Action[Content](
      "action_1",
      "Romeo",
      "Juliet",
      Content(
        Task.Popup(
          Some("Do you love me?"),
          List("yes", "no"),
          false,
          Some("https://cdn.pixabay.com/photo/2019/05/27/00/01/i-love-you-4231583_1280.jpg"),
          Set(Destination.Notification)
        ),
        Condition.Geofence(Location(24.0, 120.0), 5)
      ),
      Session("romeo and juliet", Some("chapter 1"))
    )
    val message = Message[EventPayload](
      "event_id",
      Some("action_1"),
      "Juliet",
      "Romeo",
//      Right(Location(25.0, 121.0)),
      Left(SystemPayload.Ack),
      "romeo and juliet"
    )
//    implicit val geofenceSchema = Json.schema[Condition.Geofence]("Geofence")
//    implicit val contentSchema = Json.schema[Content]("Content")
//    implicit val popupSchema = Json.schema[Content.Popup]("Popup")

    val schema = Json.schema[Action[Content]]
    println(schema.asCirce(Draft04()))
//    println(event.asJson)
    implicit val contentEncoder = Action.encoder[Content]
    implicit val contentDecoder = Action.decoder[Content]
    println(action.asJson)

    val messageSchema = Json.schema[Message[EventPayload]]
    implicit val messageEncoder = Message.encoder[EventPayload]
    implicit val messageDecoder = Message.decoder[EventPayload]
    println(messageSchema.asCirce(Draft04()))
    println(message.asJson)
//    println(schema.asCirce(Draft04()).asJson.printWith(Printer.spaces2.copy(sortKeys = false)))

  }

}
