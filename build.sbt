ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "dev.schneist"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val core = project
  .in(file("core"))
  .settings(
    name := "ase-core",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    // Der Schema-Validator prüft tief verschachtelte anyOf-Alternativen; mit dem Standard-Heap von sbt reicht das nicht.
    Test / fork := true,
    Test / javaOptions += "-Xmx2g",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"        % "2.12.0",
      "org.typelevel" %% "cats-effect"      % "3.5.7",
      "io.circe"      %% "circe-core"       % "0.14.10",
      "io.circe"      %% "circe-parser"     % "0.14.10",
      "org.scalameta" %% "munit"            % "1.0.3"  % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.0.0"  % Test,
      "org.typelevel" %% "discipline-munit" % "2.0.0"  % Test,
      "com.networknt" %  "json-schema-validator" % "1.5.6" % Test
    )
  )

lazy val model = project
  .in(file("model"))
  .dependsOn(core)
  .settings(name := "ase-model")

lazy val runner = project
  .in(file("runner"))
  .dependsOn(core, model)
  .settings(name := "ase-runner")

lazy val root = project
  .in(file("."))
  .aggregate(core, model, runner)
  .settings(name := "agentic-step-engine", publish / skip := true)
