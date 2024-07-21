name := "ghostspeak-agent"

version := "0.1"

scalaVersion in ThisBuild := "2.13.14"

val pekkoVersion = "1.1.0-M1"
val pekkoHttpVersion = "1.0.1"
val enumeratumVersion = "1.7.2"
val circeVersion = "0.14.6"
val slickVersion = "3.3.3"

//import org.virtuslab.ash.PekkoSerializationHelperPlugin

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
  "org.virtuslab.psh" %% "circe-pekko-serializer" % "0.8.0",
  "org.virtuslab.psh" %% "annotation" % "0.8.0",
  "org.apache.pekko" %% "pekko-connectors-google-fcm" % "1.0.2",
  "net.debasishg" %% "redisclient" % "3.41",
//  "com.twitter" %% "chill" % "0.10.0",
//  "com.twitter" %% "chill-pekko" % "0.10.0",
//  "io.altoo" %% "pekko-kryo-serialization" && "",
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "com.github.pjfanning" %% "pekko-http-circe" % "2.4.0",
  "com.github.andyglow" %% "scala-jsonschema-circe-json" % "0.7.11",
  "com.chuusai" %% "shapeless" % "2.3.9",
  "com.softwaremill.retry" %% "retry" % "0.3.6",
  "io.circe"        %% "circe-generic"       % circeVersion,
  "io.circe"        %% "circe-generic-extras"       % "0.14.3",
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
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
) ++ log


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

val core = project("core")
val example = project("example").dependsOn(core)
