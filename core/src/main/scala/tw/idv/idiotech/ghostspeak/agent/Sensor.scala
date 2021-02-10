package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

object Sensor {
  sealed trait Command[P]
  case class Sense[P](message: Message[P], replyTo: Option[ActorRef[StatusReply[String]]] = None)
      extends Command[P]
  case class Create[P](scenarioId: String, template: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[Nothing]
  case class Destroy(scenarioId: String, replyTo: ActorRef[StatusReply[String]])
      extends Command[Nothing]

  sealed trait Event[P]
  case class Created[P](id: String, scenario: ActorRef[Command[P]]) extends Event[P]
  case class Destroyed(id: String) extends Event[Nothing]

  type State[P] = Map[String, ActorRef[Command[P]]]

  type Creator[P] = (
    ActorContext[_],
      String, // scenario id
      String  // template
    ) => Option[ActorRef[Command[P]]]

  type GetId[P] = Message[P] => String

  def onCommand[P](
    ctx: ActorContext[Command[P]],
    createScenario: Creator[P],
    getId: GetId[P]
  )(state: State[P], cmd: Command[P]): ReplyEffect[Event[P], State[P]] = cmd match {
    case Sense(message, replyTo) =>
      val reply: StatusReply[String] = state
        .get(getId(message))
        .fold[StatusReply[String]](
          StatusReply.Error("no such scenario")
        ) { scn =>
          scn ! Sense(message)
          StatusReply.success("OK")
        }
      replyTo.fold[ReplyEffect[Event[P], State[P]]] {
        Effect.noReply
      }{ r =>
        Effect.reply[StatusReply[String], Event[P], State[P]](r)(reply)
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

  type OnCommand[P] = (ActorContext[Command[P]], Creator[P], GetId[P]) => (State[P], Command[P]) => ReplyEffect[Event[P], State[P]]

  def onEvent[P](state: State[P], evt: Event[P]): State[P] = evt match {
    case Created(id, scenario) => state + (id -> scenario)
  }

  def getScenarioId[P] = (m: Message[P]) => m.scenarioId

  def apply[P](createScenario: Creator[P], getId: GetId[P] = getScenarioId[P], handle: OnCommand[P] = onCommand[P] _): Behavior[Command[P]] =
    Behaviors.setup { context: ActorContext[Command[P]] =>
      EventSourcedBehavior.withEnforcedReplies[Command[P], Event[P], State[P]](
        persistenceId = PersistenceId.ofUniqueId("sensor"),
        emptyState = Map.empty,
        commandHandler = handle(context, createScenario, getId),
        eventHandler = onEvent
      )
    }

  def onCommandPerUser[P](
                    ctx: ActorContext[Command[P]],
                    create: Creator[P],
                    unused: GetId[P]
                  )(state: State[P], cmd: Command[P]): ReplyEffect[Event[P], State[P]] = {
    val general = onCommand[P](ctx, create, _.receiver) _
    cmd match {
      case Sense(message, _) =>
        val maybeScn = state.get(message.receiver)
        val scn = maybeScn.orElse {
          create(ctx, message.scenarioId, message.receiver)
        }
        scn.foreach(_ ! Sense(message))
        if (maybeScn.nonEmpty) {
          scn.fold[ReplyEffect[Event[P], State[P]]](Effect.noReply){s =>
            Effect.persist(Created(message.receiver, s)).thenNoReply()
          }
        }
        else Effect.noReply
      case _ => general(state, cmd)
    }
  }


  def perUser[P](createPerUser: Creator[P]) = apply[P](
    (ctx, id, _) => Some(ctx.spawn(apply[P](createPerUser, _.receiver, onCommandPerUser[P]), id))
  )

}
