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
  .aggregate(sdk, server)
  .settings(distributionSettings)
  .settings(name := "polyglot-im")

lazy val sdk = (project in file("sdk"))
  .settings(distributionSettings)
  .settings(
    name := "polyglot-im-scala",
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % "0.14.10",
      "io.circe"      %% "circe-parser" % "0.14.10",
      "org.scalameta" %% "munit"        % "1.0.3" % Test
    ),
    Test / javaOptions += "-Dpolyglot.im.root=" + baseDirectory.value.getParentFile.getParentFile.getAbsolutePath,
    Test / fork := true
  )

lazy val server = (project in file("server"))
  .dependsOn(sdk)
  .settings(distributionSettings)
  .settings(
    name                 := "polyglot-im-server",
    Compile / mainClass  := Some("polyglot.im.server.Main"),
    Compile / run / fork := true
  )
