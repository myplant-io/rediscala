import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._

releaseTagName := (ThisBuild / version).value

releaseCrossBuild := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  setNextVersion,
  commitNextVersion,
  pushChanges,
)

val akka = Def.setting(
  scalaBinaryVersion.value match {
    case "2.12" =>
      Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.5.32",
        "com.typesafe.akka" %% "akka-testkit" % "2.5.32" % "test"
      )
    case _ =>
      Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.6.19",
        "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % "test"
      )
  }
)

val specs2 = "org.specs2" %% "specs2-core" % "4.20.0" cross CrossVersion.for3Use2_13

val scalacheck = Def.setting(
  "org.scalacheck" %% "scalacheck" % "1.17.0"
)

val rediscalaDependencies = Def.setting(
  akka.value ++ Seq(
    "com.dimafeng" %% "testcontainers-scala" % "0.40.14" % Test,
    specs2 % "test",
    scalacheck.value % "test"
  )
)

val baseSourceUrl = "https://github.com/rediscala/rediscala/tree/"

val Scala212 = "2.12.17"
val Scala213 = "2.13.10"
val Scala3 = "3.2.2"

lazy val standardSettings = Def.settings(
  name := "rediscala",
  organization := "io.myplant",
  scalaVersion := Scala212,
  crossScalaVersions := Seq(Scala212, Scala213, Scala3),
  addCommandAlias("SetScala2_12", s"++ ${Scala212}! -v"),
  addCommandAlias("SetScala2_13", s"++ ${Scala213}! -v"),
  addCommandAlias("SetScala3", s"++ ${Scala3}! -v"),
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/rediscala/rediscala")),
  scmInfo := Some(ScmInfo(url("https://github.com/rediscala/rediscala"), "scm:git:git@github.com:rediscala/rediscala.git")),
  pomExtra := (
    <developers>
      <developer>
        <id>xuwei-k</id>
        <name>Kenji Yoshida</name>
        <url>https://github.com/xuwei-k</url>
      </developer>
    </developers>
  ),
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "nexus-dev.myplant.io",
    sys.env.get("NEXUS_MYPLANT_USERNAME").getOrElse("please set environment variable: NEXUS_MYPLANT_USERNAME"),
    sys.env.get("NEXUS_MYPLANT_PASSWORD").getOrElse("pleaseset environment variable: NEXUS_MYPLANT_PASSWORD")
  ),
  publishTo := Some("Sonatype Nexus Repository Manager" at "https://nexus-dev.myplant.io/repository/maven-releases/"),
  publishMavenStyle := true,
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
  scalacOptions ++= Seq(
    "-encoding",
    "UTF-8",
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  scalacOptions ++= PartialFunction
    .condOpt(CrossVersion.partialVersion(scalaVersion.value)) { case Some((2, _)) =>
      Seq("-Xlint")
    }
    .toList
    .flatten,
  Compile / doc / scalacOptions ++= {
    Seq(
      "-sourcepath",
      (LocalRootProject / baseDirectory).value.getAbsolutePath
    )
  },
  autoAPIMappings := true,
  TaskKey[Unit]("runDockerTests") := Def.taskDyn {
    val dockerTests = (Test / compile).value
      .asInstanceOf[sbt.internal.inc.Analysis]
      .apis
      .internal
      .collect {
        case (className, analyzed) if analyzed.api.classApi.structure.parents.collect { case p: xsbti.api.Projection =>
              p.id
            }.exists(Set("RedisDockerServer")) =>
          className
      }
      .toList
      .sorted
    assert(dockerTests.nonEmpty)
    streams.value.log.info(dockerTests.mkString("testOnly ", ", ", ""))
    Def.task {
      (Test / testOnly).toTask(dockerTests.mkString(" ", " ", "")).value
    }
  }.value,
  Compile / doc / scalacOptions ++= {
    val branch = {
      if (isSnapshot.value) sys.process.Process("git rev-parse HEAD").lineStream_!.head
      else version.value
    }
    Seq[String](
      "-doc-source-url",
      baseSourceUrl + branch + "€{FILE_PATH}.scala"
    )
  },
)

lazy val root = Project(id = "rediscala", base = file(".")).settings(
  standardSettings,
  libraryDependencies ++= rediscalaDependencies.value,
)
