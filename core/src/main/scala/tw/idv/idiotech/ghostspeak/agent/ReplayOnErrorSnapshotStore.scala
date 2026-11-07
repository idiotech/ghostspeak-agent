package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.Config
import org.apache.pekko.persistence.{ SelectedSnapshot, SnapshotSelectionCriteria }
import org.apache.pekko.persistence.jdbc.snapshot.JdbcSnapshotStore

import scala.concurrent.Future

/** The JDBC snapshot store, except that a snapshot that does not decode loads as no snapshot, so
  * the actor recovers by replaying its whole journal: jdbc-snapshot-store-replay-on-error in
  * application.conf.
  *
  * snapshot-is-optional alone recovers the same way, but only after the load has failed, and the
  * store's circuit breaker counts every failed load: a server starting with more than max-failures
  * (5) old snapshots opened it, and while it was open, saving the new snapshots failed too. Here
  * such a load succeeds, so the breaker only counts what it is for, the database failing.
  */
class ReplayOnErrorSnapshotStore(config: Config) extends JdbcSnapshotStore(config) {

  override def loadAsync(
    persistenceId: String,
    criteria: SnapshotSelectionCriteria
  ): Future[Option[SelectedSnapshot]] =
    super
      .loadAsync(persistenceId, criteria)
      .recover {
        // what the circe serializers throw for a snapshot of an older shape, or of a class that
        // no longer exists
        case e @ (_: io.circe.Error | _: ClassNotFoundException) =>
          log.info(
            "Snapshot of {} does not decode ({}); replaying its whole journal instead",
            persistenceId,
            e.getMessage
          )
          None
      }(using ec)
}
