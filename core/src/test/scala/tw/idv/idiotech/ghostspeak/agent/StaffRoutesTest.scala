package tw.idv.idiotech.ghostspeak.agent

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport.*
import io.circe.Json
import io.circe.syntax.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ ContentTypes, HttpEntity, StatusCodes }
import org.apache.pekko.http.scaladsl.model.headers.{
  Authorization,
  BasicHttpCredentials,
  OAuth2BearerToken
}
import org.apache.pekko.http.scaladsl.server.Directives.concat
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.pattern.StatusReply
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StaffRoutesTest extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private val published = Scenario(
    "pub",
    "graphscript",
    "[]",
    public = true,
    metadata = Metadata(public = true, passcode = Some("p1"))
  )

  private val unpublished = Scenario(
    "unpub",
    "graphscript",
    "[]",
    metadata = Metadata(passcode = Some("p2"))
  )

  // answers queries the way Sensor does, over two fixed stories
  private val sensor = system.spawnAnonymous(Behaviors.receiveMessage[Sensor.Command[String]] {
    case Sensor.Command.Query(isPublic, isFeatured, _, identifier, passcode, replyTo) =>
      val found = List(published, unpublished)
        .filter(s => isPublic.fold(true)(_ == s.public))
        .filter(s => isFeatured.fold(true)(_ == s.metadata.featured))
        .filter(s => passcode.fold(true)(s.metadata.passcode.contains(_)))
        .filter(s => identifier.fold(true)(i => i.scenarioId == s.id && i.engine == s.engine))
      replyTo ! StatusReply.success(found.asJson.toString)
      Behaviors.same
    case _ => Behaviors.same
  })

  private val categories = system.spawnAnonymous(Behaviors.ignore[CategoryManager.Command])

  private val now = 1_800_000_000_000L
  private val staffAuth = StaffAuth(Some("right"), () => now)

  private val route: Route =
    concat(new EventRoutes[String](sensor, categories, system.toTyped, staffAuth).routes*)

  private val token = staffAuth.issue("right").get.token
  private val staff = Authorization(OAuth2BearerToken(token))

  private def ids(json: Json): List[String] =
    json.asArray.get.toList.map(_.hcursor.get[String]("id").toOption.get)

  private def passcodes(json: Json): List[Option[String]] =
    json.asArray.get.toList.map(_.hcursor.downField("metadata").get[String]("passcode").toOption)

  private def codeBody(code: String) =
    HttpEntity(ContentTypes.`application/json`, Json.obj("code" -> code.asJson).noSpaces)

  "POST v1/staff/session" should {
    "give a token for the right code" in {
      Post("/v1/staff/session", codeBody("right")) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val t = responseAs[StaffAuth.Token]
        t.expiresAt shouldBe now + StaffAuth.Lifetime
        staffAuth.isValid(t.token) shouldBe true
      }
    }

    "refuse a wrong code" in {
      Post("/v1/staff/session", codeBody("wrong")) ~> route ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }
  }

  "GET v1/scenario" should {
    "list only published stories, without passcodes, when no token is sent" in {
      Get("/v1/scenario") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val json = responseAs[Json]
        ids(json) shouldBe List("pub")
        passcodes(json) shouldBe List(None)
      }
    }

    "list only published stories for public=false when no token is sent" in {
      Get("/v1/scenario?public=false") ~> route ~> check {
        ids(responseAs[Json]) shouldBe List("pub")
      }
    }

    "list unpublished stories, with passcodes, for a valid token" in {
      Get("/v1/scenario") ~> staff ~> route ~> check {
        val json = responseAs[Json]
        ids(json) shouldBe List("pub", "unpub")
        passcodes(json) shouldBe List(Some("p1"), Some("p2"))
      }
      Get("/v1/scenario?public=false") ~> staff ~> route ~> check {
        ids(responseAs[Json]) shouldBe List("unpub")
      }
    }

    "refuse an invalid, expired or non-Bearer credential" in {
      val expired = StaffAuth(Some("right"), () => now - StaffAuth.Lifetime).issue("right").get
      val credentials = List(
        Authorization(OAuth2BearerToken("garbage")),
        Authorization(OAuth2BearerToken(expired.token)),
        Authorization(BasicHttpCredentials("staff", "right"))
      )
      credentials.foreach { c =>
        Get("/v1/scenario") ~> c ~> route ~> check {
          withClue(c.toString)(status shouldBe StatusCodes.Unauthorized)
        }
      }
    }

    "leave public=true unchanged" in {
      Get("/v1/scenario?public=true") ~> route ~> check {
        ids(responseAs[Json]) shouldBe List("pub")
      }
    }

    "still find an unpublished story by its passcode, without echoing the passcode" in {
      Get("/v1/scenario?passcode=p2") ~> route ~> check {
        val json = responseAs[Json]
        ids(json) shouldBe List("unpub")
        passcodes(json) shouldBe List(None)
      }
    }
  }

  "GET v1/scenario/{engine}/{id}" should {
    "answer without a token, without the passcode" in {
      Get("/v1/scenario/graphscript/unpub") ~> route ~> check {
        status shouldBe StatusCodes.OK
        passcodes(responseAs[Json]) shouldBe List(None)
      }
    }

    "include the passcode for a valid token" in {
      Get("/v1/scenario/graphscript/unpub") ~> staff ~> route ~> check {
        passcodes(responseAs[Json]) shouldBe List(Some("p2"))
      }
    }

    "answer the health probe without a token" in {
      Get("/v1/scenario/graphscript/test") ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }
}
