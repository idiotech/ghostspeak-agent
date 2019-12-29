name := "ghostspeak-agent"

version := "0.1"

scalaVersion := "2.13.1"
scalacOptions += "-Ypartial-unification"

val akkaVersion = "2.6.1"
val enumeratumVersion = "1.5.14"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.beachape" %% "enumeratum" % enumeratumVersion,
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
