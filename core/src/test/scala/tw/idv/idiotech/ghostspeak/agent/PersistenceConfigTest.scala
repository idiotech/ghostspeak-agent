package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.{ Config, ConfigFactory, ConfigResolveOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PersistenceConfigTest extends AnyFlatSpec with Matchers {

  // `env` stands in for environment variables. At runtime an optional substitution such as
  // ${?MYSQL_POOL_SIZE} is looked up in the config first and in the environment second, so
  // a root key gives the same result. noSystem() keeps the real environment out of the test.
  def load(env: (String, String)*): Config =
    ConfigFactory
      .parseResources("application.conf")
      .withFallback(ConfigFactory.parseString(env.map { case (k, v) => s"$k = $v" }.mkString("\n")))
      .resolve(ConfigResolveOptions.noSystem())

  // as ConfigFactory.load does at runtime: application.conf over the libraries' reference.conf,
  // resolved together
  def loadWithReference(): Config =
    ConfigFactory
      .parseResources("application.conf")
      .withFallback(ConfigFactory.defaultReference())
      .resolve(ConfigResolveOptions.noSystem())

  def recoveryLimit(config: Config): Int =
    PersistenceSettings
      .withRecoveryLimit(config.withFallback(ConfigFactory.defaultReference()))
      .getInt("pekko.persistence.max-concurrent-recoveries")

  "max-concurrent-recoveries" must "be at least 1, even with a pool of one connection" in {
    recoveryLimit(load("MYSQL_POOL_SIZE" -> "1")) mustBe 1
  }

  "the persistence plugins" must "all use the one shared database" in {
    val config = load()
    for (plugin <- Seq("jdbc-journal", "jdbc-snapshot-store", "jdbc-read-journal")) {
      config.getString(s"$plugin.use-shared-db") mustBe "slick"
      config.hasPath(s"$plugin.slick") mustBe false
    }
    config.getString("pekko-persistence-jdbc.shared-databases.slick.profile") mustBe
    "slick.jdbc.MySQLProfile$"
  }

  "the shared pool" must "default to 20 connections, with recoveries capped at half of it" in {
    val config = load()
    config.getInt("pekko-persistence-jdbc.shared-databases.slick.db.numThreads") mustBe 20
    config.getInt("pekko-persistence-jdbc.shared-databases.slick.db.maxConnections") mustBe 20
    recoveryLimit(config) mustBe 10
  }

  it must "follow MYSQL_POOL_SIZE" in {
    val config = load("MYSQL_POOL_SIZE" -> "8")
    config.getInt("pekko-persistence-jdbc.shared-databases.slick.db.numThreads") mustBe 8
    config.getInt("pekko-persistence-jdbc.shared-databases.slick.db.maxConnections") mustBe 8
    recoveryLimit(config) mustBe 4
  }

  "max-concurrent-recoveries" must "follow MAX_CONCURRENT_RECOVERIES" in {
    val config = load("MYSQL_POOL_SIZE" -> "30", "MAX_CONCURRENT_RECOVERIES" -> "10")
    recoveryLimit(config) mustBe 10
    config.getInt("pekko-persistence-jdbc.shared-databases.slick.db.maxConnections") mustBe 30
  }

  "the default snapshot store" must "treat an unreadable snapshot as an error" in {
    // not set on the store, so Pekko's plugin fallback (false) applies
    val config = loadWithReference()
    config.hasPath("jdbc-snapshot-store.snapshot-is-optional") mustBe false
    config.getBoolean(
      "pekko.persistence.snapshot-store-plugin-fallback.snapshot-is-optional"
    ) mustBe
    false
  }

  "the replay-on-error snapshot store" must "be the same store, skipping unreadable snapshots" in {
    val config = loadWithReference()
    val store = config.getConfig("jdbc-snapshot-store-replay-on-error")
    store.getBoolean("snapshot-is-optional") mustBe true
    store.getString("use-shared-db") mustBe "slick"
    store.getString("class") mustBe classOf[ReplayOnErrorSnapshotStore].getName
    Class
      .forName(config.getString("jdbc-snapshot-store.class"))
      .isAssignableFrom(classOf[ReplayOnErrorSnapshotStore]) mustBe true
  }
}
