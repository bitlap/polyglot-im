ThisBuild / organization := "cn.dreamylost"
ThisBuild / version      := IO.read((ThisBuild / baseDirectory).value.getParentFile / "VERSION").trim
ThisBuild / scalaVersion := "3.6.3"
ThisBuild / scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-unchecked")
ThisBuild / homepage       := Some(url("https://github.com/bitlap/polyglot-im"))
ThisBuild / licenses       := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / publish / skip := true

lazy val distributionSettings = Seq(
  Compile / packageBin / mappings ++= {
    val repository = baseDirectory.value.getParentFile
    Seq("LICENSE", "NOTICE").map(name => (repository / name) -> s"META-INF/$name")
  },
  Compile / packageSrc / mappings ++= {
    val repository = baseDirectory.value.getParentFile
    Seq("LICENSE", "NOTICE").map(name => (repository / name) -> s"META-INF/$name")
  },
  scalafmtConfig := baseDirectory.value / ".scalafmt.conf"
)

lazy val root = (project in file("."))
  .dependsOn(RootProject(file("../scala-server")))
  .settings(distributionSettings)
  .settings(
    name                                  := "polyglot-im-benchmark",
    libraryDependencies += "io.zonky.test" % "embedded-postgres" % "2.0.7",
    Compile / mainClass                   := Some("polyglot.im.benchmark.BenchmarkServer"),
    Compile / run / fork                  := true,
    Compile / run / javaOptions ++= Seq("-Xms512m", "-Xmx2g", "-XX:ActiveProcessorCount=4")
  )
