package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit

object PersistenceTestConfig {

  // In-memory journal and snapshot store instead of the JDBC plugins from application.conf.
  // Events and snapshots are kept as objects: the circe serializer lives in the example module,
  // so the JSON encoding of snapshots is covered by separate round-trip tests.
  val config: Config = ConfigFactory
    .parseString("""
      pekko.persistence.journal.auto-start-journals = []
      pekko.persistence.snapshot-store.auto-start-snapshot-stores = []
      pekko.persistence.testkit.events.serialize = off
      pekko.persistence.testkit.snapshots.serialize = off
      pekko.actor.allow-java-serialization = on
      """)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
    .withFallback(EventSourcedBehaviorTestKit.config)
}
