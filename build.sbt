val scala3Version = "3.3.0-RC2"

organizationName := "Nigel Eke"
organization     := "nigeleke"

val bsd3License = Some(HeaderLicense.BSD3Clause("2022", "Nigel Eke"))

val scalatestVersion = "3.2.14"

lazy val root = project
  .in(file("."))
  .disablePlugins(HeaderPlugin)
  .settings(
    name           := "cribbage",
    publish / skip := true
  )
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(
    name           := "cribbage-core",
    scalaVersion   := scala3Version,
    headerLicense  := bsd3License,
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scalactic" %% "scalactic" % scalatestVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % "test"
    )
  )
