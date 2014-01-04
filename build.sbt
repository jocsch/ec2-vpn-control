name := """ec2-vpn-control"""

version := "1.0"

scalaVersion := "2.10.2"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.0.2",
  "com.amazonaws" % "aws-java-sdk" % "1.6.9.1"
)
