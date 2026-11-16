package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }

object Forward {

  /** The behavior of an actor that spawns `child` and hands it every message.
    *
    * For a root sensor that needs other actors beside it, such as its actuator: a sensor that
    * restarts after a journal failure stops all its children (see [[PersistFailure]]), so those
    * actors are spawned by this parent and stay up, and the sensor's address stays the same.
    *
    * The child is watched and its termination is not handled, so this actor fails with a
    * DeathPactException when the child stops for good rather than forwarding to nobody. The same
    * goes for any other actor the caller watches from `ctx`.
    */
  def toChild[T](ctx: ActorContext[T], child: Behavior[T], name: String): Behavior[T] = {
    val target = ctx.spawn(child, name)
    ctx.watch(target)
    Behaviors.receiveMessage { message =>
      target ! message
      Behaviors.same
    }
  }
}
