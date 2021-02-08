package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import tw.idv.idiotech.ghostspeak.agent

object Sensor {
  sealed trait Command[P]
  case class Sense[P](message: Message[P], replyTo: ActorRef[StatusReply[String]])
      extends Command[P]
  case class Create[P](scenarioId: String, template: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[Nothing]
  case class Destroy(scenarioId: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[Nothing]

  sealed trait Event[P]
  case class Created[P](id: String, scenario: ActorRef[Scenario.Command[P]]) extends Event[P]
  case class Destroyed(id: String) extends Event[Nothing]

  type State[P] = Map[String, ActorRef[Scenario.Command[P]]]

  def onCommand[P](
    ctx: ActorContext[Command[P]],
    createScenario: agent.Scenario.Creator[P]
  )(state: State[P], cmd: Command[P]): ReplyEffect[Event[P], State[P]] = cmd match {
    case Sense(message, replyTo) =>
      Effect.reply(replyTo) {
        state
          .get(message.scenarioId)
          .fold[StatusReply[String]](
            StatusReply.Error("no such scenario")
          ) { scn =>
            scn ! Scenario.Sense(message)
            StatusReply.success("OK")
          }
      }
    case Create(id, template, replyTo) =>
      if (!state.contains(id)) {
        createScenario(ctx, id, template)
          .fold[ReplyEffect[Event[P], State[P]]] {
            Effect.reply(replyTo)(StatusReply.Error("no such template"))
          } { scn =>
            Effect
              .persist(Created[P](id, scn))
              .thenReply(replyTo)(_ => StatusReply.Success("created"))
          }
      } else {
        Effect.reply(replyTo)(StatusReply.Error("already exists"))
      }
    case Destroy(id, replyTo) =>
      if (state.contains(id))
        Effect
          .persist[Event[P], State[P]](Destroyed(id))
          .thenReply(replyTo)(_ => StatusReply.Success("destroying"))
      else
        Effect.reply(replyTo)(StatusReply.Error("already exists"))
  }

  def onEvent[P](state: State[P], evt: Event[P]): State[P] = evt match {
    case Created(id, scenario) => state + (id -> scenario)
  }

  def apply[P](createScenario: Scenario.Creator[P]): Behavior[Command[P]] =
    Behaviors.setup { context: ActorContext[Command[P]] =>
      EventSourcedBehavior.withEnforcedReplies[Command[P], Event[P], State[P]](
        persistenceId = PersistenceId.ofUniqueId("sensor"),
        emptyState = Map.empty,
        commandHandler = onCommand(context, createScenario),
        eventHandler = onEvent
      )
    }

}
