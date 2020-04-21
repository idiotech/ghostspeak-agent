package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import shapeless._
import shapeless.ops.hlist.RightFolder
import tw.idv.idiotech.ghostspeak.agent.util.{Action, Speak, substitute}

class ShapelessTest extends AnyFlatSpec with Matchers {

  import tw.idv.idiotech.ghostspeak.agent.util.Subber._

  "test" should "work" in {
    case class Ghostspeak(sender: String, receiver: String)
    val ghostspeak = Ghostspeak("?a", "sheila")
    val action = Speak("?a", "vivian", "hello")
    println(action.sub("?a", "Ellen"))
    println(ghostspeak.sub("?a", "sheila"))
    substitute(ghostspeak, "?a", "amy") mustBe Ghostspeak("amy", "sheila")
  }

}
