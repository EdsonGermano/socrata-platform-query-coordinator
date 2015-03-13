resolvers ++= Seq(
  "socrata releases" at "https://repository-socrata-oss.forge.cloudbees.com/release"
)

addSbtPlugin("com.socrata" % "socrata-cloudbees-sbt" % "1.3.1")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")

libraryDependencies ++= Seq(
  "com.rojoma" %% "rojoma-json-v3" % "3.2.2",
  "com.rojoma" %% "simple-arm" % "1.1.10"
)
