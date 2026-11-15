package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.StatusReply
import org.scalatest.wordspec.AnyWordSpecLike

import tw.idv.idiotech.ghostspeak.agent.{ CategoryManager, Sensor }
import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.Inspect

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

/** The example's routes, served over HTTP to a stub sensor, as the core's EventRoutesTest does. */
class EventRoutesTest extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private val previousCorsDomains = Option(System.getProperty("app.corsDomains"))
  System.setProperty("app.corsDomains", "http://localhost")
  ConfigFactory.invalidateCaches()

  override def afterAll(): Unit = {
    previousCorsDomains match {
      case Some(value) => System.setProperty("app.corsDomains", value)
      case None        => System.clearProperty("app.corsDomains")
    }
    ConfigFactory.invalidateCaches()
    super.afterAll()
  }

  val sensor = createTestProbe[Sensor.Command[EventPayload]]()
  val categories = createTestProbe[CategoryManager.Command]()
  val routes = new EventRoutes[EventPayload](sensor.ref, categories.ref, system).routes

  val binding =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(concat(routes*)), 10.seconds)
  val base = s"http://127.0.0.1:${binding.localAddress.getPort}"

  def get(path: String) = Http().singleRequest(HttpRequest(HttpMethods.GET, s"$base$path"))

  def finish(f: Future[HttpResponse]): (StatusCode, Option[ContentType], String) = {
    val r = Await.result(f, 10.seconds)
    (r.status, Some(r.entity.contentType), Await.result(Unmarshal(r.entity).to[String], 10.seconds))
  }

  "GET .../user/{userId}/variables" should {
    "ask the sensor for that player and serve the answer as JSON" in {
      val f = get("/v1/scenario/graphscript/s1/user/u1/variables")
      val i = sensor.expectMessageType[Inspect[EventPayload]]
      i.scenarioId shouldBe "s1"
      i.userId shouldBe "u1"
      i.replyTo ! StatusReply.Success("""{"variables":{"score":3},"strings":{}}""")
      finish(f) shouldBe (
        StatusCodes.OK,
        Some(ContentTypes.`application/json`),
        """{"variables":{"score":3},"strings":{}}"""
      )
    }

    "answer 404 for an unknown player or scenario" in {
      for (reason <- Seq("no such user", "no such scenario")) {
        val f = get("/v1/scenario/graphscript/s1/user/u1/variables")
        sensor.expectMessageType[Inspect[EventPayload]].replyTo ! StatusReply.Error(reason)
        val (status, _, body) = finish(f)
        status shouldBe StatusCodes.NotFound
        // the reason, as a JSON string: the routes' marshaller encodes every String reply so
        body shouldBe s"\"$reason\""
      }
    }

    "answer 500 for any other failure" in {
      val f = get("/v1/scenario/graphscript/s1/user/u1/variables")
      sensor.expectMessageType[Inspect[EventPayload]].replyTo ! StatusReply.Error("boom")
      finish(f)._1 shouldBe StatusCodes.InternalServerError
    }

    "reach nothing with an empty user segment" in {
      val f = get("/v1/scenario/graphscript/s1/user//variables")
      finish(f)._1 shouldBe StatusCodes.NotFound
      sensor.expectNoMessage(200.millis)
    }
  }
}
