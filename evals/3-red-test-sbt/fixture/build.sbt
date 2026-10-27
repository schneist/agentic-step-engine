ThisBuild / scalaVersion := "3.5.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "interest",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.3" % Test
  )
