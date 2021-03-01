package tw.idv.idiotech.ghostspeak.daqiaotou

import json._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.{Action => BaseAction, _}
//import json.schema._
import com.github.andyglow.jsonschema.AsCirce._
import json.schema.Version._

class ModelTest extends AnyFlatSpec with Matchers {

  "model" must "translate to json" in {
    val action = BaseAction[Content](
      "action_1",
      "Romeo",
      "Juliet",
      Content(
//        Task.Popup(
//          Some("Do you love me?"),
//          List("yes", "no"),
//          false,
//          Some("https://cdn.pixabay.com/photo/2019/05/27/00/01/i-love-you-4231583_1280.jpg"),
//          Set(Destination.Notification)
//        ),
        Task.Sound(
          "http://test.sound",
          Volume.StaticVolume(Some(10)),
          SoundType.Main
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
      Left(SystemPayload.Join),
      "romeo and juliet"
    )
//    implicit val geofenceSchema = Json.schema[Condition.Geofence]("Geofence")
//    implicit val contentSchema = Json.schema[Content]("Content")
//    implicit val popupSchema = Json.schema[Content.Popup]("Popup")

    val schema = Json.schema[BaseAction[Content]]
    println(schema.asCirce(Draft04()))
//    println(event.asJson)
    println(action.asJson)

//    val messageSchema = Json.schema[Message[EventPayload]]
//    println(messageSchema.asCirce(Draft04()))
//    println(message.asJson)
//    println(GraphScript.exampleNode("?u").replace("Emily").asJson)
//    println(schema.asCirce(Draft04()).asJson.printWith(Printer.spaces2.copy(sortKeys = false)))

  }

}
