package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.StatusReply
import org.scalatest.wordspec.AnyWordSpecLike

import tw.idv.idiotech.ghostspeak.agent.Sensor.Command.*
import tw.idv.idiotech.ghostspeak.agent.Sensor.Identifier

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

/** The core routes, served over HTTP to a stub sensor: which request reaches the sensor as what,
  * and which reaches nothing. The example's longer routes (`.../resources`,
  * `.../user/{token}/actions`) are concatenated in front of these in production.
  */
class EventRoutesTest extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  // the routes' CORS settings read app.corsDomains from the default config; the property is put
  // back in afterAll so that suites sharing this JVM see the config they would see on their own
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

  val sensor = createTestProbe[Sensor.Command[String]]()
  val categories = createTestProbe[CategoryManager.Command]()
  val routes = new EventRoutes[String](sensor.ref, categories.ref, system).routes

  val binding =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(concat(routes*)), 10.seconds)
  val base = s"http://127.0.0.1:${binding.localAddress.getPort}"
  val payload = HttpEntity(ContentTypes.`application/json`, """{"template":[1],"metadata":{}}""")

  def send(method: HttpMethod, path: String, entity: RequestEntity = HttpEntity.Empty) =
    Http().singleRequest(HttpRequest(method, s"$base$path", entity = entity))

  def finish(f: Future[HttpResponse]): (StatusCode, String) = {
    val r = Await.result(f, 10.seconds)
    (r.status, Await.result(Unmarshal(r.entity).to[String], 10.seconds))
  }

  def answerQuery(json: String): Unit =
    sensor.expectMessageType[Query[String]].replyTo ! StatusReply.Success(json)

  "GET /v1/scenario" should {
    "list the scenarios" in {
      val f = send(HttpMethods.GET, "/v1/scenario?public=true")
      val q = sensor.expectMessageType[Query[String]]
      q.isPublic shouldBe Some(true)
      q.scenarioId shouldBe None
      q.replyTo ! StatusReply.Success("[]")
      finish(f) shouldBe (StatusCodes.OK, "[]")
    }

    "list the scenarios under an engine too, as the app asks by passcode" in {
      val f = send(HttpMethods.GET, "/v1/scenario/graphscript?passcode=1234")
      val q = sensor.expectMessageType[Query[String]]
      q.passcode shouldBe Some("1234")
      q.scenarioId shouldBe None
      q.replyTo ! StatusReply.Success("[]")
      finish(f) shouldBe (StatusCodes.OK, "[]")
    }
  }

  "GET /v1/scenario with a trailing slash" should {
    "still list the scenarios, as the prefix route did" in {
      for (path <- Seq("/v1/scenario/?public=true", "/v1/scenario/graphscript/?passcode=1234")) {
        val f = send(HttpMethods.GET, path)
        val q = sensor.expectMessageType[Query[String]]
        q.scenarioId shouldBe None
        q.replyTo ! StatusReply.Success("[]")
        finish(f) shouldBe (StatusCodes.OK, "[]")
      }
    }
  }

  "the routes for one scenario" should {
    "GET exactly that scenario" in {
      val f = send(HttpMethods.GET, "/v1/scenario/graphscript/s1")
      val q = sensor.expectMessageType[Query[String]]
      q.scenarioId shouldBe Some(Identifier("graphscript", "s1"))
      q.replyTo ! StatusReply.Success("[]")
      finish(f)._1 shouldBe StatusCodes.OK
    }

    "DELETE exactly that scenario" in {
      val f = send(HttpMethods.DELETE, "/v1/scenario/graphscript/s1")
      val d = sensor.expectMessageType[Destroy[String]]
      d.scenarioId shouldBe "s1"
      d.replyTo ! StatusReply.Success("destroying")
      finish(f)._1 shouldBe StatusCodes.OK
    }

    "replace the scenario on PUT with overwrite" in {
      val f = send(HttpMethods.PUT, "/v1/scenario/graphscript/s1?overwrite=true", payload)
      val r = sensor.expectMessageType[Replace[String]]
      r.scenario.id shouldBe "s1"
      r.replyTo ! StatusReply.Success("created")
      sensor.expectNoMessage(200.millis)
      finish(f)._1 shouldBe StatusCodes.OK
    }

    "create the scenario on PUT without overwrite" in {
      val f = send(HttpMethods.PUT, "/v1/scenario/graphscript/s1", payload)
      val c = sensor.expectMessageType[Create[String]]
      c.scenario.id shouldBe "s1"
      c.replyTo ! StatusReply.Success("created")
      finish(f)._1 shouldBe StatusCodes.OK
    }
  }

  "a longer path under a scenario that no route claims" should {
    "reach nothing on GET, instead of listing every scenario" in {
      val f = send(HttpMethods.GET, "/v1/scenario/graphscript/s1/typo")
      sensor.expectNoMessage(500.millis)
      finish(f)._1 shouldBe StatusCodes.NotFound
    }

    "reach nothing on DELETE, as the actions poll with an empty user segment" in {
      val f = send(HttpMethods.DELETE, "/v1/scenario/graphscript/s1/user//actions")
      sensor.expectNoMessage(500.millis)
      finish(f)._1 shouldBe StatusCodes.NotFound
    }

    "reach nothing on PUT" in {
      val f = send(HttpMethods.PUT, "/v1/scenario/graphscript/s1/typo", payload)
      sensor.expectNoMessage(500.millis)
      finish(f)._1 shouldBe StatusCodes.NotFound
    }
  }
}
