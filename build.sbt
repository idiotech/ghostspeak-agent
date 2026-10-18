name := "ghostspeak-agent"

version := "0.1"

val scala2 = "2.13.14"
val scala3 = "3.3.4"

// Cross-built during the move to Scala 3: the same sources compile with both. `++3.3.4 test`
// runs the tests on Scala 3; version-specific code lives in src/main/scala-2 and scala-3.
scalaVersion in ThisBuild := scala2
crossScalaVersions in ThisBuild := Seq(scala2, scala3)

val pekkoVersion = "1.1.0-M1"
val pekkoHttpVersion = "1.0.1"
val enumeratumVersion = "1.7.2"
val circeVersion = "0.14.6"
val slickVersion = "3.3.3"

//import org.virtuslab.ash.PekkoSerializationHelperPlugin

scalacOptions in ThisBuild ++= Seq(
  "-deprecation",
  "-encoding",
  "utf-8",
  "-feature",
  "-language:implicitConversions",
  "-language:higherKinds"
) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, _)) =>
    Seq(
      "-Ybackend-parallelism",
      "8",
      "-explaintypes",
      "-Ywarn-unused:imports",
      "-Yrangepos",
      // report code that Scala 3 compiles differently, as errors
      "-Xsource:3"
    )
  case _ => Seq("-Wunused:imports")
})
val log4jVersion = "2.13.0"

def log = Seq(
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  // log errors to Sentry
)
libraryDependencies in ThisBuild ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-cors" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
//  PekkoSerializationHelperPlugin.circePekkoSerializer,
  "org.apache.pekko" %% "pekko-connectors-google-fcm" % "1.0.2",
  "redis.clients" % "jedis" % "5.1.5",
//  "com.twitter" %% "chill" % "0.10.0",
//  "com.twitter" %% "chill-pekko" % "0.10.0",
//  "io.altoo" %% "pekko-kryo-serialization" && "",
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "com.github.pjfanning" %% "pekko-http-circe" % "2.4.0",
  "com.softwaremill.retry" %% "retry" % "0.3.6",
  "io.circe"        %% "circe-generic"       % circeVersion,
  "com.typesafe" % "config" % "1.4.2",
  "com.hunorkovacs" %% "circe-config" % "0.10.0",
  "org.typelevel" %% "cats-core" % "2.8.0",
  "org.typelevel" %% "cats-effect" % "3.3.14",
  "org.apache.pekko" %% "pekko-persistence-jdbc" % "1.1.0-M1",
  "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
  "org.apache.pekko" %% "pekko-connectors-slick" % "1.1.0-M1",
  "com.typesafe" %% "ssl-config-core" % "0.6.1",
  "org.openapitools" % "onesignal-java-client" % "1.2.2",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.23.1",
  "org.apache.logging.log4j" % "log4j-core" % "2.23.1",
  "org.apache.logging.log4j" % "log4j-api" % "2.23.1",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
) ++ log

// configured derivation: circe-generic-extras on Scala 2, built into circe on Scala 3
libraryDependencies in ThisBuild ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, _)) => Seq("io.circe" %% "circe-generic-extras" % "0.14.3")
  case _            => Nil
})


resolvers in ThisBuild ++= List(
  Resolver.sonatypeRepo("releases"),
  Resolver.mavenLocal,
  "jitpack" at "https://jitpack.io"
)

def project(projectName: String) = Project(projectName, new File(projectName)).settings(
  name := projectName,
  version := "0.1"
)
//  .enablePlugins(PekkoSerializationHelperPlugin)

val core = project("core").settings(
  // for MySqlPersistenceTest; the example module ships the driver at runtime
  libraryDependencies += "com.mysql" % "mysql-connector-j" % "8.0.32" % Test
)
val example = project("example").dependsOn(core)
