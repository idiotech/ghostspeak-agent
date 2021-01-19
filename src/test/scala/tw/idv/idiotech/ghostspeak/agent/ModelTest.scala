package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import io.circe.syntax._
import json._
//import json.schema._
import com.github.andyglow.jsonschema.AsCirce._
import io.circe.Printer
import json.schema.Version._


class ModelTest extends AnyFlatSpec with Matchers {

  "model" must "translate to json" in {
    val action = Action(
      "action_1",
      "Romeo",
      "Juliet",
      Some(Condition.Geofence(Location(24.0, 120.0), 5)),
      Content.Popup(
        Some("Do you love me?"),
        List("yes", "no"),
        false,
        Some("https://cdn.pixabay.com/photo/2019/05/27/00/01/i-love-you-4231583_1280.jpg"),
        Set(Destination.Notification)
      ),
      Some("chapter 1")

    )
    val event = Event(
      "event_id",
      Some("action_1"),
      "Juliet",
      "Romeo",
      Location(25.0, 121.0)
//      Payload.Text("I love you too")
    )
//    implicit val geofenceSchema = Json.schema[Condition.Geofence]("Geofence")
//    implicit val contentSchema = Json.schema[Content]("Content")
//    implicit val popupSchema = Json.schema[Content.Popup]("Popup")


    val schema = Json.schema[Event]
    println(schema.asCirce(Draft04()))
    println(event.asJson)
//    println(action.asJson)
//    println(schema.asCirce(Draft04()).asJson.printWith(Printer.spaces2.copy(sortKeys = false)))

  }

}
