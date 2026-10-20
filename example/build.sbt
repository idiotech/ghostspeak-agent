
import com.typesafe.sbt.packager.docker.DockerVersion
libraryDependencies ++= Seq(
  "com.softwaremill.quicklens" %% "quicklens" % "1.9.9",
  "org.scalikejdbc" %% "scalikejdbc"       % "4.0.0",
  "com.mysql" % "mysql-connector-j" % "8.0.32"
)
dockerBaseImage := "openjdk:15.0.2-slim"
dockerVersion := Some(DockerVersion(20, 10, 7, None))
Docker / packageName := "urbanbaker/ghostspeak-agent"
Docker / version := scala.sys.env.getOrElse("IMAGE_TAG", "localtest")
Docker / dockerRepository := Some("docker.urbanbaker.tw")
Docker / daemonUser := "root"
Docker / daemonUserUid := Some("0")
enablePlugins(JavaAppPackaging)
enablePlugins(AshScriptPlugin)
enablePlugins(DockerPlugin)

