ThisBuild / version        := IO.read((ThisBuild / baseDirectory).value.getParentFile.getParentFile / "VERSION").trim
ThisBuild / scalaVersion   := "3.6.3"
ThisBuild / publish / skip := true
lazy val root = (project in file("."))
  .dependsOn(RootProject(file("../../scala-client")))
  .settings(
    name                 := "polyglot-im-scala-client-example",
    scalafmtConfig       := baseDirectory.value / ".scalafmt.conf",
    Compile / mainClass  := Some("polyglot.im.examples.LiveClient"),
    Compile / run / fork := true,
    Compile / run / javaOptions += "-Dpolyglot.im.root=" + baseDirectory.value.getParentFile.getParentFile.getAbsolutePath
  )
