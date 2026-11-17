package tw.idv.idiotech.ghostspeak.daqiaotou

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import tw.idv.idiotech.ghostspeak.agent.Session

class PendingActionsTest extends AnyFlatSpec with Matchers {

  private def action(id: String): Json =
    Json.obj("id" -> id.asJson, "receiver" -> "u".asJson)

  "PendingActions" must "give the stored actions back in the order they were numbered" in {
    val ids = (1 to 20).map(i => s"line-$i")
    val stored = ids.map(id => id -> PendingActions.stamp(action(id), PendingActions.nextOrder()))
    // a hash gives its fields back in any order
    val shuffled = scala.util.Random(7).shuffle(stored).map(_._2.noSpaces)
    val fetched = PendingActions.inOrder(shuffled).toOption.get
    fetched.map(_.hcursor.get[String]("id").toOption.get) mustBe ids
  }

  it must "take the number out again, so the client gets the action as it was" in {
    val stamped = PendingActions.stamp(action("a"), 42).noSpaces
    stamped must include(PendingActions.OrderKey)
    PendingActions.inOrder(List(stamped)) mustBe Right(List(action("a")))
  }

  it must "keep counting up when the clock stands still or goes back" in {
    val a = PendingActions.nextOrder(now = 5_000_000_000_000L)
    val b = PendingActions.nextOrder(now = 5_000_000_000_000L)
    val c = PendingActions.nextOrder(now = 1L)
    b mustBe a + 1
    c mustBe b + 1
  }

  it must "put actions stored before the numbering first" in {
    val old = action("old").noSpaces
    val newer = PendingActions.stamp(action("new"), PendingActions.nextOrder()).noSpaces
    PendingActions
      .inOrder(List(newer, old))
      .map(_.map(_.hcursor.get[String]("id").toOption.get)) mustBe
    Right(List("old", "new"))
  }

  it must "report JSON it cannot parse" in {
    PendingActions.inOrder(List("{not json")).isLeft mustBe true
  }

  it must "number a whole action the clients decode unchanged" in {
    val full: Action = tw.idv.idiotech.ghostspeak.agent.Action(
      "p1",
      "u",
      "ghost",
      Content(Task.Popup(Some("hi"), self = true), Condition.Always),
      Session("s", None)
    )
    val stored = PendingActions.stamp(full.asJson, 1).noSpaces
    PendingActions.inOrder(List(stored)).map(_.map(_.as[Action])) mustBe Right(List(Right(full)))
  }

  "Popup" must "keep the player's own line flag" in {
    val json = parse("""{"text":"me","self":true,"type":"POPUP"}""").toOption.get
    json.as[Task].map { case p: Task.Popup => p.self; case _ => false } mustBe Right(true)
    (Task.Popup(Some("me"), self = true): Task).asJson.hcursor.get[Boolean]("self") mustBe Right(
      true
    )
  }

  it must "read a popup without the flag as a character's line" in {
    val json = parse("""{"text":"npc","type":"POPUP"}""").toOption.get
    json.as[Task].map { case p: Task.Popup => p.self; case _ => true } mustBe Right(false)
  }
}
