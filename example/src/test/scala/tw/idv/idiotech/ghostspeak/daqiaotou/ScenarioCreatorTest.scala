package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.wordspec.AnyWordSpecLike

import tw.idv.idiotech.ghostspeak.agent.{
  Action as BaseAction,
  Actuator,
  Message as BaseMessage,
  Scenario,
  Sensor,
  Session,
  SystemPayload
}
import tw.idv.idiotech.ghostspeak.daqiaotou.GraphScript.{ Node, Performance }

class ScenarioCreatorTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("""
          pekko.persistence.journal.auto-start-journals = []
          pekko.persistence.snapshot-store.auto-start-snapshot-stores = []
          pekko.persistence.testkit.events.serialize = off
          pekko.persistence.testkit.snapshots.serialize = off
          # the user-scenario actors use their own snapshot store id
          jdbc-snapshot-store-replay-on-error = ${pekko.persistence.testkit.snapshotstore.pluginid}
          """)
        .withFallback(PersistenceTestKitSnapshotPlugin.config)
        .withFallback(EventSourcedBehaviorTestKit.config)
        .resolve()
    )
    with AnyWordSpecLike {

  val creator = new ScenarioCreator(new Sensor[EventPayload], new Actuator[Content, EventPayload])

  def message(payload: Either[SystemPayload, EventPayload], actionId: Option[String] = None) =
    BaseMessage[EventPayload]("", actionId, "ghost", "?u", payload, "")

  def popup(id: String) = Performance(
    BaseAction[Content](
      id,
      "?u",
      "ghost",
      Content(Task.Popup(Some(id)), Condition.Always),
      Session("scn", None)
    )
  )

  // the initial node waits for a location or a text reply, and thanks the user for either
  val script: Map[String, Node] = Map(
    "initial" -> Node(
      "initial",
      List(
        message(Right(Location(24.0, 120.0))),
        message(Right(EventPayload.Text("yes")), Some("ask"))
      ),
      List(popup("thanks")),
      Nil,
      Set.empty,
      Nil
    )
  )

  "a user scenario" should {
    "handle a text reply while a non-text trigger is also pending" in {
      val actuator = createTestProbe[Actuator.Command[Content]]()
      val user = EventSourcedBehaviorTestKit[
        Sensor.Command[EventPayload],
        creator.Event,
        creator.State
      ](
        system,
        creator.ub(Scenario("u1", "graphscript", ""), actuator.ref)(using script),
        SerializationSettings.disabled
      )
      val reply =
        message(Right(EventPayload.Text("yes")), Some("ask")).copy(sender = "u1", id = "m1")
      user.runCommand(Sensor.Command.Sense(reply))
      actuator.expectMessageType[Actuator.Command.Perform[Content]].action.id shouldBe "thanks"
    }
  }

  "getCommandName" should {
    "name every command, Destroy included" in {
      creator.getCommandName(
        Sensor.Command.Destroy[EventPayload]("scn", createTestProbe[Any]().ref)
      ) shouldBe "destroy"
    }
  }

  "Resources" should {
    "accept every task, a single variable update included" in {
      val node = Node(
        "n",
        Nil,
        List(
          popup("p"),
          Performance(
            BaseAction[Content](
              "v",
              "?u",
              "ghost",
              Content(VariableUpdate("score", Operation.+, 1), Condition.Always),
              Session("scn", None)
            )
          )
        ),
        Nil,
        Set.empty,
        Nil
      )
      EventRoutes.Resources(List(node)) shouldBe EventRoutes.Resources(Nil, Nil)
    }
  }
}
