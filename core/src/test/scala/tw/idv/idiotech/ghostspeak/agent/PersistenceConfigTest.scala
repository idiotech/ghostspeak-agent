package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PersistenceConfigTest extends AnyFlatSpec with Matchers {

  def load(overrides: String = ""): Config =
    ConfigFactory
      .parseString(overrides)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve()

  "the DB pool" must "default to 20 connections, with recoveries capped to match" in {
    val config = load()
    config.getInt("slick.db.numThreads") mustBe 20
    config.getInt("slick.db.maxConnections") mustBe 20
    config.getInt("jdbc-journal.slick.db.maxConnections") mustBe 20
    config.getInt("pekko.persistence.max-concurrent-recoveries") mustBe 20
  }

  it must "follow MYSQL_POOL_SIZE" in {
    val config = load("MYSQL_POOL_SIZE = 8")
    config.getInt("slick.db.numThreads") mustBe 8
    config.getInt("slick.db.maxConnections") mustBe 8
    config.getInt("jdbc-snapshot-store.slick.db.maxConnections") mustBe 8
    config.getInt("pekko.persistence.max-concurrent-recoveries") mustBe 8
  }
}
