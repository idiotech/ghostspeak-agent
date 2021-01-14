name := "ghostspeak-agent"

version := "0.1"

scalaVersion := "2.13.1"

val akkaVersion = "2.6.8"
val akkaHttpVersion = "10.2.2"
val enumeratumVersion = "1.6.1"
val circeVersion = "0.13.0"

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
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.beachape" %% "enumeratum" % enumeratumVersion,
  "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
  "de.heikoseeberger" %% "akka-http-circe" % "1.31.0",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "io.circe"        %% "circe-generic"       % circeVersion,
  "io.circe"        %% "circe-generic-extras"       % circeVersion,
  "org.typelevel" %% "cats-core" % "2.1.0",
  "org.typelevel" %% "cats-effect" % "2.0.0",
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)

val log4jVersion = "2.13.0"

def log = Seq(
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  // log errors to Sentry
)

libraryDependencies ++= log

resolvers += Resolver.sonatypeRepo("releases")
