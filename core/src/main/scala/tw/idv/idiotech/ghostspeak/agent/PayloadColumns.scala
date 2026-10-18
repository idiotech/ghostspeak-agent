package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.db.SlickExtension
import slick.jdbc.MySQLProfile

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.control.NonFatal

/** The MySQL schema that pekko-persistence-jdbc creates stores journal events and snapshots in
  * BLOB columns, which hold at most 64 KB. A Created event carries a whole scenario template, and
  * a snapshot of a sensor holds every scenario, so either can be bigger: in strict mode the insert
  * fails, and otherwise MySQL truncates it into JSON that no longer decodes.
  *
  * At startup the payload columns are checked. If one is still a BLOB, the server logs the SQL
  * that widens it to LONGBLOB, and runs it only when ghostspeak.widen-payload-columns
  * (WIDEN_PAYLOAD_COLUMNS=true) is set: the ALTER copies the whole table and locks it meanwhile,
  * so it belongs in one deliberate deploy (or done by hand), not in every pod's startup. Nothing
  * here stops the server from starting: a failed check or ALTER is logged.
  */
object PayloadColumns extends LazyLogging {

  case class Column(schema: String, table: String, column: String) {
    def qualifiedTable: String = if (schema.isEmpty) s"`$table`" else s"`$schema`.`$table`"
    def widenSql: String = s"ALTER TABLE $qualifiedTable MODIFY `$column` LONGBLOB NOT NULL"
    override def toString: String = s"$qualifiedTable.`$column`"
  }

  /** The event payload and snapshot payload columns, with the tables and schemas configured. */
  def columns(config: Config): List[Column] = {
    val journal = config.getConfig("jdbc-journal.tables.event_journal")
    val snapshot = config.getConfig("jdbc-snapshot-store.tables.snapshot")
    List(
      Column(
        journal.getString("schemaName"),
        journal.getString("tableName"),
        journal.getString("columnNames.eventPayload")
      ),
      Column(
        snapshot.getString("schemaName"),
        snapshot.getString("tableName"),
        snapshot.getString("columnNames.snapshotPayload")
      )
    )
  }

  /** At startup: check, and widen if ghostspeak.widen-payload-columns is set. */
  def check()(implicit system: ActorSystem[_]): Unit = {
    val config = system.settings.config
    val widen = config.hasPath("ghostspeak.widen-payload-columns") &&
      config.getBoolean("ghostspeak.widen-payload-columns")
    check(config, widen, checkTimeout = 30.seconds)
  }

  def check(config: Config, widen: Boolean, checkTimeout: FiniteDuration)(implicit
    system: ActorSystem[_]
  ): Unit = {
    val slickDb =
      SlickExtension(system).database(system.settings.config.getConfig("jdbc-snapshot-store"))
    slickDb.profile match {
      case _: MySQLProfile =>
        import MySQLProfile.api._
        import system.executionContext
        val db = slickDb.database
        columns(config).foreach { c =>
          try {
            val dataType = Await.result(
              db.run(
                sql"""SELECT DATA_TYPE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = IF(${c.schema} = '', DATABASE(), ${c.schema})
                  AND TABLE_NAME = ${c.table} AND COLUMN_NAME = ${c.column}"""
                  .as[String]
                  .headOption
              ),
              checkTimeout
            )
            dataType match {
              case Some(t) if t.equalsIgnoreCase("longblob") => ()
              case Some(t) if widen =>
                logger.warn(s"widening $c from $t to LONGBLOB: ${c.widenSql}")
                // no timeout: the ALTER takes as long as copying the table does
                Await.result(db.run(sqlu"#${c.widenSql}").map(_ => ()), Duration.Inf)
                logger.warn(s"widened $c to LONGBLOB")
              case Some(t) =>
                logger.error(
                  s"$c is $t, which holds at most 64 KB; larger events or snapshots will fail " +
                    s"or be truncated. Widen it with: ${c.widenSql}; or restart once with " +
                    "WIDEN_PAYLOAD_COLUMNS=true"
                )
              case None =>
                logger.warn(s"payload column $c not found; not checking it")
            }
          } catch {
            case NonFatal(e) => logger.error(s"could not check or widen $c", e)
          }
        }
      case _ => ()
    }
  }
}
