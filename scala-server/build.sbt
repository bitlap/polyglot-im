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
  .dependsOn(RootProject(file("../scala-client")))
  .settings(distributionSettings)
  .settings(
    name := "polyglot-im-scala-server",
    libraryDependencies ++= Seq(
      "com.github.pureconfig" %% "pureconfig-core"           % "0.17.8",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.8",
      "org.postgresql"         % "postgresql"                % "42.7.5",
      "com.zaxxer"             % "HikariCP"                  % "5.1.0",
      "io.zonky.test"          % "embedded-postgres"         % "2.0.7"  % Test,
      "org.http4s"            %% "http4s-dsl"                % "0.23.30",
      "org.http4s"            %% "http4s-ember-server"       % "0.23.30",
      "org.http4s"            %% "http4s-circe"              % "0.23.30",
      "ch.qos.logback"         % "logback-classic"           % "1.5.17" % Runtime,
      "org.typelevel"         %% "munit-cats-effect"         % "2.0.0"  % Test
    ),
    Test / javaOptions += "-Dpolyglot.im.root=" + baseDirectory.value.getParentFile.getAbsolutePath,
    Test / fork          := true,
    Compile / mainClass  := Some("polyglot.im.server.Main"),
    Compile / run / fork := true,
    Compile / run / javaOptions ++= sys.props.toSeq.collect {
      case (key, value)
          if Set("config.file", "config.resource", "logback.configurationFile")(key) || key
            .startsWith("polyglot-im.") =>
        s"-D$key=$value"
    }
  )
