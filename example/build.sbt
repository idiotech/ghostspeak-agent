
import com.typesafe.sbt.packager.docker.DockerVersion
libraryDependencies ++= Seq(
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "com.github.kolotaev" %% "ride" % "1.1.1",
  "com.google.cloud" % "google-cloud-texttospeech" % "2.0.3",
  "org.scalikejdbc" %% "scalikejdbc"       % "4.0.0",
  "com.mysql" % "mysql-connector-j" % "8.0.32"
)
dockerBaseImage := "openjdk:15.0.2-slim"
dockerVersion := Some(DockerVersion(20, 10, 7, None))
Docker / packageName := "urbanbaker/ghostspeak-agent"
Docker / version := scala.sys.env.getOrElse("IMAGE_TAG", "localtest")
Docker / dockerRepository := Some("docker.floraland.tw")
Docker / daemonUser := "root"
Docker / daemonUserUid := Some("0")
enablePlugins(JavaAppPackaging)
enablePlugins(AshScriptPlugin)
enablePlugins(DockerPlugin)

