import scala.io.Source

def loadManifestLock(lockFile: File): Map[String, String] = {
  if (!lockFile.isFile) {
    sys.error(s"Missing version lock: ${lockFile.getAbsolutePath}")
  }

  val source = Source.fromFile(lockFile, "UTF-8")
  try {
    source
      .getLines()
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map { line =>
        line.split("=", 2) match {
          case Array(key, value) => key.trim -> value.trim
          case _                 => sys.error(s"Invalid manifest.lock line: $line")
        }
      }
      .foldLeft(Map.empty[String, String]) { case (values, (key, value)) =>
        if (values.contains(key)) {
          sys.error(s"Duplicate '$key' in ${lockFile.getAbsolutePath}")
        }
        values.updated(key, value)
      }
  } finally {
    source.close()
  }
}

val manifestLock = loadManifestLock(file("reference/manifest.lock"))

def lockedVersion(key: String): String =
  manifestLock.getOrElse(key, sys.error(s"Missing '$key' in reference/manifest.lock"))

name := "miku-spinal"
version := "1.0"
scalaVersion := lockedVersion("scala")

val spinalVersion = lockedVersion("spinalhdl")

ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror")

target := file(sys.env.getOrElse("CPU_SBT_TARGET", "target"))

libraryDependencies ++= Seq(
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
  "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
  "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
  "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion % Test,
  "org.scalatest" %% "scalatest" % lockedVersion("scalatest") % Test
)

Compile / run / fork := true
Test / fork := true
Test / parallelExecution := false
