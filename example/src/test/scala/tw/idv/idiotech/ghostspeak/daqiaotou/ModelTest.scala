package tw.idv.idiotech.ghostspeak.daqiaotou

import json._
import io.circe.syntax._
import io.circe.parser.decode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.{Action => BaseAction, _}
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.{ActTime, Performance, defaultZone}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, LocalDateTime, LocalTime, ZonedDateTime}
import scala.concurrent.duration._
//import json.schema._
import com.github.andyglow.jsonschema.AsCirce._
import json.schema.Version._

class ModelTest extends AnyFlatSpec with Matchers {

      val action = BaseAction[Content](
        "action_1",
        "Romeo",
        "Juliet",
        Content(
          Task.Popup(
            Some("Do you love me?"),
            List("yes", "no"),
            false,
            List("https://cdn.pixabay.com/photo/2019/05/27/00/01/i-love-you-4231583_1280.jpg"),
            Set(Destination.Notification)
          ),
          Condition.Geofence(Location(24.0, 120.0), 5)
        ),
        Session("romeo and juliet", Some("chapter 1"))
      )

  "time" must "be serialized" in {

    val str = """{
                |          "target" : "21:12:00",
                |          "minAfter" : 20100,
                |          "range" : 20600
                |        }""".stripMargin
    println(decode[ActTime](str))
    val now = ZonedDateTime.now(defaultZone).truncatedTo(ChronoUnit.DAYS).withHour(20).withMinute(0)
    val clock = Clock.fixed(now.toInstant, defaultZone)
    val actTime = ActTime(Some(LocalTime.of(21, 10, 10, 10000)), 1.hours.toMillis, 0)
    println(actTime.asJson.toString())
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(actTime.time(clock)), defaultZone) mustBe now.withHour(21).withMinute(10)
    val actTime2 = ActTime(Some(LocalTime.of(20, 10)), 1.hours.toMillis, 0)
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(actTime2.time(clock)), defaultZone) mustBe now.plusDays(1).withHour(20).withMinute(10)
    for (_ <- 0 to 100) {
      val actTime3 = ActTime(Some(LocalTime.of(20, 10)), 1.hours.toMillis, 10.minutes.toMillis)
      val answer = now.plusDays(1).withHour(20).withMinute(10).toInstant.toEpochMilli
      val time = actTime3.time(clock)
      time + 10.minutes.toMillis must be > answer
      time - 10.minutes.toMillis must be < answer
    }
  }

//  "model" must "translate to json" in {
//    val action = BaseAction[Content](
//      "action_1",
//      "Romeo",
//      "Juliet",
//      Content(
////        Task.Popup(
////          Some("Do you love me?"),
////          List("yes", "no"),
////          false,
////          Some("https://cdn.pixabay.com/photo/2019/05/27/00/01/i-love-you-4231583_1280.jpg"),
////          Set(Destination.Notification)
////        ),
//        Task.Sound(
//          "http://test.sound",
//          Volume.StaticVolume(Some(10)),
//          SoundType.Main
//        ),
//        Condition.Geofence(Location(24.0, 120.0), 5)
//      ),
//      Session("romeo and juliet", Some("chapter 1"))
//    )
//    val message = Message[EventPayload](
//      "event_id",
//      Some("action_1"),
//      "Juliet",
//      "Romeo",
////      Right(Location(25.0, 121.0)),
//      Left(SystemPayload.Modal(Modality.Done)),
//      "romeo and juliet"
//    )
//    implicit val geofenceSchema = Json.schema[Condition.Geofence]("Geofence")
//    implicit val contentSchema = Json.schema[Content]("Content")
//    implicit val popupSchema = Json.schema[Content.Popup]("Popup")

//    val schema = Json.schema[BaseAction[Content]]
//    logger.info(schema.asCirce(Draft04()))
//    logger.info(message.asJson)
//    logger.info(action.asJson)

//    val messageSchema = Json.schema[Message[EventPayload]]
//    logger.info(messageSchema.asCirce(Draft04()))
//    logger.info(message.asJson)
//    logger.info(GraphScript.exampleNode("?u").replace("Emily").asJson)
//    logger.info(schema.asCirce(Draft04()).asJson.printWith(Printer.spaces2.copy(sortKeys = false)))

//  }

}
