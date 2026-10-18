package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

object PersistenceSettings {

  val PoolSizePath = "pekko-persistence-jdbc.shared-databases.slick.db.numThreads"

  /** Sets pekko.persistence.max-concurrent-recoveries: ghostspeak.max-concurrent-recoveries
    * (MAX_CONCURRENT_RECOVERIES) when set, otherwise half the shared connection pool (at least
    * one). Every recovery holds a pool connection while it loads its snapshot and replays its
    * journal, so after a restart with many actors, recoveries capped at the pool size would take
    * every connection and stall journal writes and queries until they finish.
    */
  def withRecoveryLimit(config: Config): Config = {
    val limit =
      if (config.hasPath("ghostspeak.max-concurrent-recoveries"))
        config.getInt("ghostspeak.max-concurrent-recoveries")
      else math.max(1, config.getInt(PoolSizePath) / 2)
    config.withValue(
      "pekko.persistence.max-concurrent-recoveries",
      ConfigValueFactory.fromAnyRef(limit)
    )
  }

  /** The application config with the recovery limit applied, for creating the actor system. */
  def load(): Config = withRecoveryLimit(ConfigFactory.load())
}
