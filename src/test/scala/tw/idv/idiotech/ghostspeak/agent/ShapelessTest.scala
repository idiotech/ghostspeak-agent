package tw.idv.idiotech.ghostspeak.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import shapeless.Generic
import tw.idv.idiotech.ghostspeak.agent.util.substitute

class ShapelessTest extends AnyFlatSpec with Matchers {

  import tw.idv.idiotech.ghostspeak.agent.util.Instantiation._
  import scala.reflect.runtime.universe._

  sealed trait Action
  case class Speak(actor: String, receiver: String, content: String) extends Action
  case class Hit(actor: String, receiver: String) extends Action

  "test" should "work" in {
    implicit val genAction = Generic[Action]
    val action: Action = Speak("?a", "vivian", "hello")
    println(action.replace("?a", "amy"))
//    substitute(action, "?a", "amy") mustBe Speak("amy", "vivian", "hello")
    println(action.getClass)
    println(classOf[Speak])
  }

}
