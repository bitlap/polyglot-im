ThisBuild / organization := "cn.dreamylost"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.3"
ThisBuild / scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-unchecked")
ThisBuild / homepage := Some(url("https://github.com/bitlap/sf-im"))
// 发布前需确认许可证、Maven 命名空间和签名；当前仅构建本地产物。
ThisBuild / publish / skip := true

lazy val root = (project in file("."))
  .aggregate(sdk, server)
  .settings(name := "sf-im")

lazy val sdk = (project in file("sdk"))
  .settings(
    name := "sf-im-scala",
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % "0.14.10",
      "io.circe"      %% "circe-parser" % "0.14.10",
      "org.scalameta" %% "munit"        % "1.0.3" % Test
    ),
    Test / javaOptions += "-Dsf.im.root=" + baseDirectory.value.getParentFile.getParentFile.getAbsolutePath,
    Test / fork := true
  )

lazy val server = (project in file("server"))
  .dependsOn(sdk)
  .settings(
    name                 := "sf-im-server",
    Compile / mainClass  := Some("sf.im.server.Main"),
    Compile / run / fork := true
  )
