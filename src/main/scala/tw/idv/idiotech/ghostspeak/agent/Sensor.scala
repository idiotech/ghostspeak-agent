package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}

object Sensor {
  sealed trait Command
  case class Sense(message: Message, replyTo: ActorRef[StatusReply[String]]) extends Command
  case class Create(scenarioId: String, template: String, replyTo: ActorRef[StatusReply[String]]) extends Command
  case class Destroy(scenarioId: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  sealed trait Event
  case class Created(id: String, scenario: ActorRef[Scenario.Command]) extends Event
  case class Destroyed(id: String) extends Event

  type State = Map[String, ActorRef[Scenario.Command]]

  def onCommand(
    ctx: ActorContext[Command], scenarioManager: ScenarioManager
  )(state: State, cmd: Command): ReplyEffect[Event, State] = cmd match {
    case Sense(message, replyTo) =>
      Effect.reply(replyTo){
        state.get(message.scenarioId).fold[StatusReply[String]](
          StatusReply.Error("no such scenario")
        ){ scn =>
          scn ! Scenario.Sense(message)
          StatusReply.success("OK")
        }
      }
    case Create(id, template, replyTo) =>
      if (!state.contains(id)) {
        scenarioManager.createScenario(ctx, id, template).fold[ReplyEffect[Event, State]] {
          Effect.reply(replyTo)(StatusReply.Error("no such template"))
        } { scn =>
          Effect.persist(Created(id, scn)).thenReply(replyTo)(_ => StatusReply.Success("created"))
        }
      } else {
        Effect.reply(replyTo)(StatusReply.Error("already exists"))
      }
    case Destroy(id, replyTo) =>
      if (state.contains(id))
        Effect.persist(Destroyed(id)).thenReply(replyTo)(_ => StatusReply.Success("destroying"))
      else
        Effect.reply(replyTo)(StatusReply.Error("already exists"))
  }

  def onEvent(state: State, evt: Event): State = evt match {
    case Created(id, scenario) => state + (id -> scenario)
  }

  def apply(scenarioManager: ScenarioManager): Behavior[Command] =
    Behaviors.setup { context: ActorContext[Command] =>
      EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("sensor"),
        emptyState = Map.empty,
        commandHandler = onCommand(context, scenarioManager),
        eventHandler = onEvent
      )
    }

}