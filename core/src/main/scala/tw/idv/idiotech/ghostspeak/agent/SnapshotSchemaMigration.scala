package tw.idv.idiotech.ghostspeak.agent

import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.db.SlickExtension
import slick.jdbc.MySQLProfile

import scala.concurrent.Future

/** The MySQL schema that pekko-persistence-jdbc creates stores snapshots in a BLOB column, which
  * holds at most 64 KB. A snapshot of a root sensor (every scenario's template) or of a per-user
  * sensor with a few hundred users is bigger than that: in strict mode the insert fails, and
  * otherwise MySQL truncates it into JSON that no longer decodes. Widen the column to LONGBLOB.
  */
object SnapshotSchemaMigration extends LazyLogging {

  def apply()(implicit system: ActorSystem[_]): Future[Unit] = {
    import system.executionContext
    val pluginConfig = system.settings.config.getConfig("jdbc-snapshot-store")
    val slickDb = SlickExtension(system).database(pluginConfig)
    slickDb.profile match {
      case _: MySQLProfile =>
        import MySQLProfile.api._
        val table = pluginConfig.getString("tables.snapshot.tableName")
        val column = pluginConfig.getString("tables.snapshot.columnNames.snapshotPayload")
        val db = slickDb.database
        val columnType = sql"""SELECT DATA_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = $table AND COLUMN_NAME = $column"""
          .as[String]
          .headOption
        db.run(columnType).flatMap {
          case Some(t) if t.equalsIgnoreCase("longblob") =>
            Future.unit
          case Some(t) =>
            logger.warn(s"widening $table.$column from $t to LONGBLOB")
            db.run(sqlu"ALTER TABLE #$table MODIFY #$column LONGBLOB NOT NULL").map(_ => ())
          case None =>
            logger.warn(s"snapshot column $table.$column not found; not widening it")
            Future.unit
        }
      case _ => Future.unit
    }
  }
}
