enablePlugins(
  BuildInfoPlugin,
  JavaAppPackaging,
)

name         := "vscode-extension-updater"
description  := "Update checksums of extensions from Visual Studio Code marketplace."
version      := "1"
scalaVersion := "3.2.0"

buildInfoKeys    := Seq[BuildInfoKey](name, version, description)
buildInfoPackage := "updater"

scalacOptions ++= Seq(
  "-deprecation",
//  "-explain",
  "-feature",
  "-new-syntax",
  "-source",
  "future",
  "-release",
  "17",
  "-unchecked",
  "-Wunused:all",
  "-Xcheck-macros",
  "-Xverify-signatures",
  "-Ycook-docs",
  "-Ysafe-init",
//  "-Yexplicit-nulls",
  "-Ykind-projector:underscores",
)

libraryDependencies ++= Seq(
  "co.fs2"        %% "fs2-io"              % "3.3.0",
  "org.typelevel" %% "cats-core"           % "2.8.0",
  "org.typelevel" %% "cats-effect"         % "3.3.14",
  "org.typelevel" %% "kittens"             % "3.0.0",
  "io.circe"      %% "circe-core"          % "0.14.3",
  "io.circe"      %% "circe-jawn"          % "0.14.3",
  "io.circe"      %% "circe-generic"       % "0.14.3",
  "org.typelevel" %% "log4cats-core"       % "2.5.0",
  "org.typelevel" %% "log4cats-slf4j"      % "2.5.0",
  "ch.qos.logback" % "logback-classic"     % "1.4.4" % Runtime,
  "org.http4s"    %% "http4s-circe"        % "1.0.0-M37",
  "org.http4s"    %% "http4s-ember-client" % "1.0.0-M37",
  "org.http4s"    %% "http4s-dsl"          % "1.0.0-M37",
  "com.monovore"  %% "decline-effect"      % "2.3.1",
  "io.circe"      %% "circe-fs2"           % "0.14.0",
)
