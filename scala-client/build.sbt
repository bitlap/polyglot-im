ThisBuild / organization := "cn.dreamylost"
ThisBuild / version      := IO.read((ThisBuild / baseDirectory).value.getParentFile / "VERSION").trim
ThisBuild / scalaVersion := "3.6.3"
ThisBuild / scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-unchecked")
ThisBuild / homepage       := Some(url("https://github.com/bitlap/polyglot-im"))
ThisBuild / licenses       := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / publish / skip := false
ThisBuild / publish        := sys.error("远程发布未配置；使用 just publish-local-scala")

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
  .settings(distributionSettings)
  .settings(
    name := "polyglot-im-scala-client",
    libraryDependencies ++= Seq(
      "co.fs2"        %% "fs2-core"          % "3.11.0",
      "org.typelevel" %% "cats-core"         % "2.12.0",
      "org.typelevel" %% "cats-effect"       % "3.5.7",
      "org.xerial"     % "sqlite-jdbc"       % "3.54.1.0",
      "org.postgresql" % "postgresql"        % "42.7.5",
      "io.zonky.test"  % "embedded-postgres" % "2.0.7" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      "io.circe"      %% "circe-core"        % "0.14.10",
      "io.circe"      %% "circe-parser"      % "0.14.10",
      "org.scalameta" %% "munit"             % "1.0.3" % Test
    ),
    Test / javaOptions += "-Dpolyglot.im.root=" + baseDirectory.value.getParentFile.getAbsolutePath,
    Test / fork := true
  )
