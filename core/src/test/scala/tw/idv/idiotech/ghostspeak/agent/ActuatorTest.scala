package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command
import tw.idv.idiotech.ghostspeak.agent.Actuator.Command.{ Cancel, Perform }

import scala.concurrent.Future

class ActuatorTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit("ActuatorTest", EventSourcedBehaviorTestKit.config)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val actuator = new Actuator[String, String]

  private def action(id: String, receiver: String, scenario: String) =
    Action[String](id, receiver, "ghost", "content", Session(scenario, None))

  private def actuatorKit() = {
    val sensor = testKit.createTestProbe[Sensor.Command[String]]()
    EventSourcedBehaviorTestKit[Command[String], actuator.Event, actuator.State](
      testKit.system,
      actuator.behaviorFromFuture("test", sensor.ref, _ => Future.successful(())),
      SerializationSettings.disabled
    )
  }

  // far enough ahead that no timer fires during the test
  private val later = System.currentTimeMillis() + 3600000

  "Cancel" must "drop only the pending actions of the user in the scenario" in {
    val kit = actuatorKit()
    val left = action("a", "user1", "story1")
    val otherUser = action("b", "user2", "story1")
    val otherScenario = action("c", "user1", "story2")
    Seq(left, otherUser, otherScenario).foreach(a => kit.runCommand(Perform(a, later)))

    val result = kit.runCommand(Cancel[String]("user1", "story1"))

    result.events must contain only actuator.Event.ActionDone(Set(actuator.Event.Performance(left, later)))
    result.state.pendingActions.map(_.action) must contain theSameElementsAs Seq(otherUser, otherScenario)
  }

  it must "persist nothing when the user has nothing pending" in {
    val kit = actuatorKit()
    kit.runCommand(Perform(action("a", "user2", "story1"), later))

    val result = kit.runCommand(Cancel[String]("user1", "story1"))

    result.hasNoEvents mustBe true
    result.state.pendingActions.map(_.action.id) must contain only "a"
  }

  it must "stay cancelled after a restart" in {
    val kit = actuatorKit()
    kit.runCommand(Perform(action("a", "user1", "story1"), later))
    kit.runCommand(Cancel[String]("user1", "story1"))

    kit.restart().state.pendingActions mustBe empty
  }
}
