name := "ghostspeak-agent"

version := "0.1"

scalaVersion in ThisBuild := "2.13.4"

val akkaVersion = "2.6.18"
val akkaHttpVersion = "10.2.2"
val enumeratumVersion = "1.6.1"
val circeVersion = "0.13.0"

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
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
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
  "com.lightbend.akka" %% "akka-stream-alpakka-google-fcm" % "3.0.3",
  "net.debasishg" %% "redisclient" % "3.41",
  "com.twitter" %% "chill" % "0.9.5",
  "com.twitter" %% "chill-akka" % "0.9.5",
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "de.heikoseeberger" %% "akka-http-circe" % "1.31.0",
  "com.github.idiotech.scala-jsonschema" %% "scala-jsonschema-circe-json" % "ordering-v0.6.1-g0f3353d-60",
  "com.github.ceratech" % "fcm-scala" % "1.7",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "com.softwaremill.retry" %% "retry" % "0.3.3",
  "io.circe"        %% "circe-generic"       % circeVersion,
  "io.circe"        %% "circe-generic-extras"       % "0.13.1-M4",
  "org.typelevel" %% "cats-core" % "2.1.0",
  "org.typelevel" %% "cats-effect" % "2.0.0",
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
