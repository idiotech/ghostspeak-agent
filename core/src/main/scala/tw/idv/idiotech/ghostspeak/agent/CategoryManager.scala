package tw.idv.idiotech.ghostspeak.agent

import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.persistence.typed.PersistenceId
import enumeratum.EnumEntry.UpperSnakecase
import enumeratum._

import scala.collection.immutable

object CategoryManager {

  sealed trait DisplayType extends EnumEntry

  object DisplayType extends Enum[DisplayType] with CirceEnum[DisplayType] {
    val values: immutable.IndexedSeq[DisplayType] = findValues

    case object Carousel extends DisplayType with UpperSnakecase

    case object TwoInARow extends DisplayType with UpperSnakecase

    case object OneInARow extends DisplayType with UpperSnakecase

    case object LIST extends DisplayType with UpperSnakecase
  }

  sealed trait Event extends EventBase

  object Event {
    case class Uploaded(state: State) extends Event

    object Uploaded {
      implicit val codecForUploaded: Codec.AsObject[Uploaded] = deriveConfiguredCodec[Uploaded]
    }

    implicit val codecForEvent: Codec.AsObject[Event] = deriveConfiguredCodec[Event]
  }

  sealed trait Command extends CommandBase

  object Command {
    case class Upload(state: State, replyTo: ActorRef[StatusReply[String]]) extends Command
    case class GetAll(replyTo: ActorRef[StatusReply[State]]) extends Command
  }

  case class Category(
    id: String,
    name: String,
    order: Int,
    hidden: Boolean,
    displayType: DisplayType = DisplayType.OneInARow,
    displayCount: Int = 3
  )

  object Category {
    implicit val codecForCategory: Codec.AsObject[Category] = deriveConfiguredCodec[Category]
  }

  case class State(categories: List[Category]) extends EventBase

  object State {
    implicit val codecForState: Codec.AsObject[State] = deriveConfiguredCodec[State]
  }

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
