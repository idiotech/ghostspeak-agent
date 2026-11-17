package tw.idv.idiotech.ghostspeak.daqiaotou

// _root_: the package object has a `redis` value that would shadow the redis package
import _root_.redis.clients.jedis.{ Jedis, JedisPool, JedisPoolConfig }

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The Redis operations the server uses: one list per user and scenario, holding the action JSON
  * not yet fetched by the client in the order it was stored (see [[PendingActions]]). The hash
  * operations read what servers before the list left behind.
  */
class RedisStore(host: String, port: Int, timeoutMs: Int = RedisStore.DefaultTimeoutMs) {

  // No cap on connections, up to 8 kept idle. The calls block, so callers that can run them off
  // the actor threads do so on Pekko's blocking-I/O dispatcher.
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

  /** Adds the value at the end of the list. */
  def rpush(key: String, value: String): Unit =
    withClient(_.rpush(key, value))

  /** The whole list, first stored first, deleting it in the same transaction: a value pushed
    * meanwhile is either in the answer or still in Redis for the next call, never lost. Empty if
    * the key does not exist.
    */
  def takeList(key: String): List[String] =
    withClient { client =>
      val transaction = client.multi()
      val values = transaction.lrange(key, 0, -1)
      transaction.del(key)
      transaction.exec()
      values.get().asScala.toList
    }

  def del(keys: String*): Unit =
    if (keys.nonEmpty) withClient(_.del(keys*))

  def close(): Unit = pool.close()
}

object RedisStore {
  // long enough for a slow call, short enough that a Redis that stopped answering does not hold
  // threads forever (Jedis's own default is 2 s); 0 waits indefinitely
  val DefaultTimeoutMs = 10000
}
