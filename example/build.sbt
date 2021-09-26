libraryDependencies ++= Seq(
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "com.github.kolotaev" %% "ride" % "1.1.1",
  "com.google.cloud" % "google-cloud-texttospeech" % "2.0.3"
)
dockerBaseImage := "openjdk:15.0.2-slim"
Docker / packageName := "urbanbaker/ghostspeak-agent"
Docker / version := scala.sys.env.getOrElse("IMAGE_TAG", "localtest")
Docker / dockerRepository := Some("docker.floraland.tw")
Docker / daemonUser := "root"
Docker / daemonUserUid := Some("0")
enablePlugins(JavaAppPackaging)
enablePlugins(AshScriptPlugin)
enablePlugins(DockerPlugin)

