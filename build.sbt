name := "ghostspeak-agent"

version := "0.1"

ThisBuild / scalaVersion := "3.3.4"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "utf-8",
  "-feature",
  "-Wunused:imports"
)

val pekkoVersion = "1.1.0-M1"
val pekkoHttpVersion = "1.0.1"
val enumeratumVersion = "1.7.2"
val circeVersion = "0.14.6"
val log4jVersion = "2.23.1"

ThisBuild / libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-cors" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-jdbc" % "1.1.0-M1",
  "org.apache.pekko" %% "pekko-connectors-google-fcm" % "1.0.2",
  "com.github.pjfanning" %% "pekko-http-circe" % "2.4.0",
  "com.typesafe" %% "ssl-config-core" % "0.6.1",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "com.typesafe" % "config" % "1.4.2",
  "com.hunorkovacs" %% "circe-config" % "0.10.0",
  "org.typelevel" %% "cats-core" % "2.8.0",
  "redis.clients" % "jedis" % "5.1.5",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

def project(projectName: String) = Project(projectName, new File(projectName)).settings(
  name := projectName,
  version := "0.1"
)

val core = project("core").settings(
  // for MySqlPersistenceTest; the example module ships the driver at runtime
  libraryDependencies += "com.mysql" % "mysql-connector-j" % "8.0.32" % Test
)
val example = project("example").dependsOn(core)
