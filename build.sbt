name := "ghostspeak-agent"

version := "0.1"

scalaVersion in ThisBuild := "2.13.10"

val akkaVersion = "2.7.0"
val akkaHttpVersion = "10.4.0"
val enumeratumVersion = "1.7.0"
val circeVersion = "0.13.0"
val slickVersion = "3.4.1"

import org.virtuslab.ash.AkkaSerializationHelperPlugin

scalacOptions in ThisBuild ++= Seq(
  "-Ybackend-parallelism",
  "8",
  "-deprecation",
  "-encoding",
  "utf-8",
  "-explaintypes",
  "-feature",
  "-Ywarn-unused:imports",
  "-Yrangepos",
  "-Ymacro-annotations",
  "-language:implicitConversions",
  "-language:higherKinds"
)
val log4jVersion = "2.13.0"

def log = Seq(
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  // log errors to Sentry
)
libraryDependencies in ThisBuild ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  AkkaSerializationHelperPlugin.circeAkkaSerializer,
  "ch.megard" %% "akka-http-cors" % "1.1.1",
  "com.lightbend.akka" %% "akka-stream-alpakka-google-fcm" % "5.0.0",
  "net.debasishg" %% "redisclient" % "3.41",
  "com.twitter" %% "chill" % "0.10.0",
  "com.twitter" %% "chill-akka" % "0.10.0",
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "de.heikoseeberger" %% "akka-http-circe" % "1.31.0",
  "com.github.idiotech.scala-jsonschema" %% "scala-jsonschema-circe-json" % "ordering-v0.6.1-g0f3353d-60",
  "com.chuusai" %% "shapeless" % "2.3.9",
  "com.softwaremill.retry" %% "retry" % "0.3.6",
  "io.circe"        %% "circe-generic"       % circeVersion,
  "io.circe"        %% "circe-generic-extras"       % "0.13.1-M4",
  "com.typesafe" % "config" % "1.4.1",
  "com.hunorkovacs" %% "circe-config" % "0.10.0",
  "org.typelevel" %% "cats-core" % "2.8.0",
  "org.typelevel" %% "cats-effect" % "3.3.14",
  "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.2.1",
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.slick" %% "slick" % slickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
) ++ log


resolvers in ThisBuild ++= List(
  Resolver.sonatypeRepo("releases"),
  "jitpack" at "https://jitpack.io"
)

def project(projectName: String) = Project(projectName, new File(projectName)).settings(
  name := projectName,
  version := "0.1"
).enablePlugins(AkkaSerializationHelperPlugin)

val core = project("core")
val example = project("example").dependsOn(core)
