package tw.idv.idiotech.ghostspeak.agent

import org.apache.pekko.actor.typed.{ BackoffSupervisorStrategy, SupervisorStrategy }

import scala.concurrent.duration.*

object PersistFailure {

  /** What every persistent actor here does when the journal fails it, in a write or in recovery:
    * restart and recover from the journal, backing off while the database stays away. The default
    * is to stop for good, so that one write failing while MySQL restarted left the root sensor
    * dead, and the server answering errors, until the process was restarted.
    *
    * A restart stops the actor's children first. A sensor spawns its scenarios again once it has
    * recovered; anything else it needs must not be its child (see [[Forward]]).
    *
    * The command whose write failed gets no reply, and commands already waiting behind that write
    * may be dropped with it. Those arriving during the backoff are kept, up to
    * pekko.actor.typed.restart-stash-capacity.
    */
  val restart: BackoffSupervisorStrategy =
    SupervisorStrategy.restartWithBackoff(minBackoff = 1.second, maxBackoff = 30.seconds, 0.2)
}
