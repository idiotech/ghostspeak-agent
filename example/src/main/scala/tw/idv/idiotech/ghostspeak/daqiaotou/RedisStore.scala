package tw.idv.idiotech.ghostspeak.daqiaotou

// _root_: the package object has a `redis` value that would shadow the redis package
import _root_.redis.clients.jedis.{ Jedis, JedisPool, JedisPoolConfig }

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The Redis operations the server uses: one hash per user and scenario
  * ("action-<scenario>-<user>"), mapping action IDs to the action JSON not yet fetched by the
  * client.
  */
class RedisStore(host: String, port: Int, timeoutMs: Int = 0) {

  // Like the redisclient pool this replaces: no cap on connections, up to 8 kept idle, and by
  // default (timeoutMs = 0) no connect or read timeout.
  private val poolConfig = {
    val c = new JedisPoolConfig()
    c.setMaxTotal(-1)
    c.setMaxIdle(8)
    c
  }
  private val pool = new JedisPool(poolConfig, host, port, timeoutMs)

  private def withClient[A](f: Jedis => A): A = Using.resource(pool.getResource)(f)

  def hset(key: String, field: String, value: String): Unit =
    withClient(_.hset(key, field, value))

  /** All fields of the hash; empty if the key does not exist. */
  def hgetAll(key: String): Map[String, String] =
    withClient(_.hgetAll(key).asScala.toMap)

  /** Deletes the given fields of the hash, in one command. */
  def hdel(key: String, fields: String*): Unit =
    if (fields.nonEmpty) withClient(_.hdel(key, fields*))

  def del(key: String): Unit =
    withClient(_.del(key))

  def close(): Unit = pool.close()
}
