ThisBuild / version := "1.0.0"
ThisBuild / scalaVersion := "2.13.10"

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")


lazy val root = (project in file("."))
  .settings(
    name := "s3-cloudsearch-lambda"
  )

libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"

libraryDependencies += "com.amazonaws" % "aws-lambda-java-core" % "1.2.2"
libraryDependencies += "com.amazonaws" % "aws-lambda-java-events" % "3.11.1"
libraryDependencies += "com.amazonaws" % "aws-lambda-java-serialization" % "1.1.2"

libraryDependencies += "com.amazonaws" % "aws-java-sdk-s3" % "1.12.469"
libraryDependencies += "com.amazonaws" % "aws-java-sdk-cloudsearch" % "1.12.469"

libraryDependencies += "com.google.code.gson" % "gson" % "2.10.1"
libraryDependencies += "com.google.guava" % "guava" % "31.1-jre"
libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.12.0"
