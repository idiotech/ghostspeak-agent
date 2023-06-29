package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.PersistenceId
import io.circe.generic.extras.ConfiguredJsonCodec

object CategoryManager {

  @ConfiguredJsonCodec
  sealed trait Event extends EventBase

  object Event {

    @ConfiguredJsonCodec
    case class Uploaded(state: State) extends Event
  }

  sealed trait Command extends CommandBase

  object Command {
    case class Upload(state: State, replyTo: ActorRef[StatusReply[String]]) extends Command
    case class GetAll(replyTo: ActorRef[StatusReply[State]]) extends Command
  }

  @ConfiguredJsonCodec
  case class Category(id: String, name: String, order: Int, hidden: Boolean)

  @ConfiguredJsonCodec
  case class State(categories: List[Category]) extends EventBase

  private def onCommand(state: State, command: Command): ReplyEffect[Event, State] = command match {
    case Command.Upload(state, replyTo) =>
      Effect.persist(Event.Uploaded(state)).thenReply(replyTo)(_ => StatusReply.success("done"))
    case Command.GetAll(replyTo) =>
      Effect.reply(replyTo)(
        StatusReply.success(State(state.categories.filter(!_.hidden).sortBy(_.order)))
      )
  }

  private def onEvent(state: State, event: Event) = event match {
    case Event.Uploaded(s) => s
  }

  def apply(): Behavior[Command] = Behaviors.setup { context: ActorContext[Command] =>
    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("category-manager"),
      emptyState = State(Nil),
      commandHandler = onCommand,
      eventHandler = onEvent
    )
  }

}
