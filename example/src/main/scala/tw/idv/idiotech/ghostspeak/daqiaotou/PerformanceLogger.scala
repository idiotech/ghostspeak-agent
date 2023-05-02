package tw.idv.idiotech.ghostspeak.daqiaotou

import scalikejdbc._

object PerformanceLogger {
  Class.forName("com.mysql.jdbc.Driver")
  val conf = config.mysql
  implicit val session = AutoSession
  ConnectionPool.singleton(s"jdbc:mysql://${conf.host}:${conf.port}/${conf.database}", conf.username, conf.password)
  def insert(user: String, action: String, stage: String): Unit = {
    val shortId = user.take(5)
    sql"insert into action_events (username, action, stage) values ($shortId, $action, $stage)".update.apply()
  }
}
