package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.util.substitute

// Node.replace uses substitute to put the user ID into a script's "?u" placeholders.
class SubstituteTest extends AnyFlatSpec with Matchers {

  val message = Message[String](
    "?u",
    Some("?u"),
    "?u",
    "?u",
    Right("?u"),
    "?u"
  )

  "substitute" must "replace top-level String fields of a Message that equal the placeholder" in {
    substitute(message, "?u", "amy") mustBe Message[String](
      "amy",
      Some("?u"), // inside an Option: left alone
      "amy",
      "amy",
      Right("?u"), // inside the payload: left alone
      "amy"
    )
  }

  it must "replace top-level String fields of an Action that equal the placeholder" in {
    val action = Action[String]("?u", "?u", "?u", "?u", Session("?u", Some("?u")), Some("?u"))
    // content is replaced too when it is itself a String (in production it is a Content)
    substitute(action, "?u", "amy") mustBe
      Action[String]("amy", "amy", "amy", "amy", Session("?u", Some("?u")), Some("?u"))
  }

  it must "leave a non-String content alone" in {
    val action = Action[Int]("?u", "r", "s", 7, Session("?u", None))
    substitute(action, "?u", "amy") mustBe Action[Int]("amy", "r", "s", 7, Session("?u", None))
  }

  it must "only replace whole values, not substrings" in {
    val m = message.copy(id = "?u-1", receiver = "ghost", sender = "?u", scenarioId = "x?u")
    val replaced = substitute(m, "?u", "amy")
    replaced.id mustBe "?u-1"
    replaced.receiver mustBe "ghost"
    replaced.sender mustBe "amy"
    replaced.scenarioId mustBe "x?u"
  }

  it must "leave a value without the placeholder unchanged" in {
    val m = Message[String]("m", None, "ghost", "u1", Left(SystemPayload.Start), "s")
    substitute(m, "?u", "amy") mustBe m
  }
}
