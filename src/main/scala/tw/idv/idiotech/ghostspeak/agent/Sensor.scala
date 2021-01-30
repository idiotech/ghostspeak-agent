package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import io.circe.generic.JsonCodec
import akka.persistence.typed.scaladsl.{ Effect, EffectBuilder, EventSourcedBehavior }

object Sensor {

  sealed trait Command
  case class Sense(message: Message) extends Command
  case class Destroy(scenarioId: String) extends Command

  sealed trait Event
  case class Created(id: String, scenario: ActorRef[Scenario.Command]) extends Event

  type State = Map[String, ActorRef[Scenario.Command]]

  def spawnScenario(
    context: ActorContext[Command],
    message: Message
  ): Option[ActorRef[Scenario.Command]] = ???

  def onCommand(
    ctx: ActorContext[Command]
  )(state: State, cmd: Command): EffectBuilder[Event, State] = cmd match {
    case Sense(message) =>
      val scenarioId = message.scenarioId
      state
        .get(scenarioId)
        .fold(
          spawnScenario(ctx, message).fold(Effect.none[Event, State]) { scenario =>
            scenario ! Scenario.Sense(message)
            Effect.persist(Created(scenarioId, scenario))
          }
        ) { scenario =>
          scenario ! Scenario.Sense(message)
          Effect.none
        }
    case Destroy(scenarioId) =>
      state.get(scenarioId).foreach(_ ! Scenario.Destroy)
      Effect.none
  }

  def onEvent(state: State, evt: Event): State = evt match {
    case Created(id, scenario) => state + (id -> scenario)
  }

  def apply(): Behavior[Command] =
    Behaviors.setup { context: ActorContext[Command] =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("sensor"),
        emptyState = Map.empty,
        commandHandler = onCommand(context),
        eventHandler = onEvent
      )
    }

}
