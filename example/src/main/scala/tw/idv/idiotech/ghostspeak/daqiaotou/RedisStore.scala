package tw.idv.idiotech.ghostspeak.daqiaotou

import com.redis.RedisClientPool

/** The Redis operations the server uses: one hash per user and scenario
  * ("action-<scenario>-<user>"), mapping action IDs to the action JSON not yet fetched by the
  * client.
  */
class RedisStore(host: String, port: Int) {
  private val pool = new RedisClientPool(host, port)

  def hset(key: String, field: String, value: String): Unit =
    pool.withClient(_.hset(key, field, value))

  /** All fields of the hash; empty if the key does not exist. */
  def hgetAll(key: String): Map[String, String] =
    pool.withClient(_.hgetall[String, String](key)).getOrElse(Map.empty)

  def hdel(key: String, field: String): Unit =
    pool.withClient(_.hdel(key, field))

  def del(key: String): Unit =
    pool.withClient(_.del(key))

  def close(): Unit = pool.close()
}
