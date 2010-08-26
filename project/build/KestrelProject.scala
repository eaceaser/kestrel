import sbt._
import com.twitter.sbt.StandardProject


class KestrelProject(info: ProjectInfo) extends StandardProject(info) {
  val specs = "org.scala-tools.testing" % "specs" % "1.6.2.1" % "test"
  val vscaladoc = "org.scala-tools" % "vscaladoc" % "1.1-md-3" % "test"

  val configgy = "net.lag" % "configgy" % "1.5.3"
  val naggati = "net.lag" % "naggati" % "0.7.3"
  val twitterActors = "com.twitter" % "twitteractors" % "1.0.0"
  val mina = "org.apache.mina" % "mina-core" % "2.0.0-M6"
  val slf4j_api = "org.slf4j" % "slf4j-api" % "1.5.2"
  val slf4j_jdk14 = "org.slf4j" % "slf4j-jdk14" % "1.5.2"
  val util = "com.twitter" % "util" % "1.1-SNAPSHOT"

  override def mainClass = Some("net.lag.kestrel.Kestrel")

  override def pomExtra =
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
  val publishTo = Resolver.sftp("green.lag.net", "green.lag.net", "/web/repo")

  override def releaseBuild = true
}
