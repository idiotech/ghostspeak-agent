package tw.idv.idiotech.ghostspeak.daqiaotou

import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ConfigTest extends AnyFlatSpec with Matchers {

  // root keys stand in for environment variables; noSystem() keeps the real environment out
  def load(env: String) =
    ConfigFactory
      .parseResources("application.conf")
      .withFallback(ConfigFactory.parseString(env))
      .resolve(ConfigResolveOptions.noSystem())

  "the Redis settings" must "default to localhost:6379" in {
    val c = load("")
    c.getString("app.redis.host") mustBe "localhost"
    c.getInt("app.redis.port") mustBe 6379
  }

  they must "take REDIS_HOST and REDIS_PORT separately" in {
    val c = load("REDIS_HOST = redis.internal\nREDIS_PORT = 6380")
    c.getString("app.redis.host") mustBe "redis.internal"
    c.getInt("app.redis.port") mustBe 6380
  }
}
