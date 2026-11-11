ThisBuild / organization := "cn.dreamylost"
ThisBuild / version      := IO.read((LocalRootProject / baseDirectory).value.getParentFile / "VERSION").trim
ThisBuild / scalaVersion := "3.6.3"
ThisBuild / scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-unchecked")
ThisBuild / homepage := Some(url("https://github.com/bitlap/polyglot-im"))
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
// 发布前需确认 Maven 命名空间和签名；当前仅构建本地产物。
ThisBuild / publish / skip := true

lazy val noticeMappings = Def.setting {
  val repository = (LocalRootProject / baseDirectory).value.getParentFile
  Seq("LICENSE", "NOTICE").map(name => (repository / name) -> s"META-INF/$name")
}

lazy val distributionSettings = Seq(
  Compile / packageBin / mappings ++= noticeMappings.value,
  Compile / packageSrc / mappings ++= noticeMappings.value
)

lazy val root = (project in file("."))
  .aggregate(scalaSdk, scalaServer, benchmark)
  .settings(distributionSettings)
  .settings(name := "polyglot-im")

lazy val scalaSdk = (project in file("scala-sdk"))
  .settings(distributionSettings)
  .settings(
    name := "polyglot-im-scala-sdk",
    libraryDependencies ++= Seq(
      "co.fs2"        %% "fs2-core"          % "3.11.0",
      "org.typelevel" %% "cats-core"         % "2.12.0",
      "org.typelevel" %% "cats-effect"       % "3.5.7",
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      "io.circe"      %% "circe-core"        % "0.14.10",
      "io.circe"      %% "circe-parser"      % "0.14.10",
      "org.scalameta" %% "munit"             % "1.0.3" % Test
    ),
    Test / javaOptions += "-Dpolyglot.im.root=" + baseDirectory.value.getParentFile.getParentFile.getAbsolutePath,
    Test / fork := true
  )

lazy val scalaServer = (project in file("scala-server"))
  .dependsOn(scalaSdk)
  .settings(distributionSettings)
  .settings(
    name := "polyglot-im-scala-server",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"          % "42.7.5",
      "com.zaxxer"     % "HikariCP"            % "5.1.0",
      "io.zonky.test"  % "embedded-postgres"   % "2.0.7"  % Test,
      "org.http4s"    %% "http4s-dsl"          % "0.23.30",
      "org.http4s"    %% "http4s-ember-server" % "0.23.30",
      "org.http4s"    %% "http4s-circe"        % "0.23.30",
      "ch.qos.logback" % "logback-classic"     % "1.5.17" % Runtime,
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0"  % Test
    ),
    Compile / mainClass  := Some("polyglot.im.server.Main"),
    Compile / run / fork := true
  )

lazy val benchmark = (project in file("benchmark"))
  .dependsOn(scalaServer)
  .settings(distributionSettings)
  .settings(
    name                  := "polyglot-im-benchmark",
    Compile / scalaSource := baseDirectory.value.getParentFile.getParentFile / "benchmark/src/main/scala",
    target                := baseDirectory.value.getParentFile.getParentFile / "benchmark/target",
    libraryDependencies += "io.zonky.test" % "embedded-postgres" % "2.0.7",
    Compile / mainClass                   := Some("polyglot.im.benchmark.BenchmarkServer"),
    Compile / run / fork                  := true,
    Compile / run / javaOptions ++= Seq("-Xms512m", "-Xmx2g", "-XX:ActiveProcessorCount=4")
  )
