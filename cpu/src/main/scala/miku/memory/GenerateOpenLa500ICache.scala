package miku.memory

import java.nio.file.{Files, Path, Paths}
import spinal.core._

object GenerateOpenLa500ICache {
  private def outputArgument(args: Array[String]): String =
    args match {
      case Array(path) if path.nonEmpty              => path
      case Array("--out-dir", path) if path.nonEmpty => path
      case Array() =>
        sys.env
          .get("OUT_DIR")
          .filter(_.nonEmpty)
          .getOrElse(
            throw new IllegalArgumentException(
              "output directory is required as an argument or OUT_DIR"
            )
          )
      case _ =>
        throw new IllegalArgumentException(
          "usage: GenerateOpenLa500ICache [--out-dir] <output-directory>"
        )
    }

  private def findRepositoryRoot(path: Path): Option[Path] =
    if (path == null) None
    else if (Files.exists(path.resolve(".git"))) Some(path)
    else findRepositoryRoot(path.getParent)

  private def prospectiveRealPath(path: Path): Path =
    if (Files.exists(path)) path.toRealPath()
    else {
      val parent = Option(path.getParent).getOrElse {
        throw new IllegalArgumentException(s"output path has no existing ancestor: $path")
      }
      prospectiveRealPath(parent).resolve(path.getFileName).normalize()
    }

  def main(args: Array[String]): Unit = {
    val outputDirectory = Paths.get(outputArgument(args)).toAbsolutePath.normalize()
    val workingDirectory = Paths.get("").toAbsolutePath.normalize()
    val classDirectory = Paths
      .get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
      .toAbsolutePath
      .normalize()
    val repositoryRoots = Seq(workingDirectory, classDirectory).flatMap(findRepositoryRoot).distinct
    val prospectiveOutput = prospectiveRealPath(outputDirectory)

    repositoryRoots.foreach { repositoryRoot =>
      val protectedRtl = prospectiveRealPath(repositoryRoot.resolve("rtl"))
      require(
        prospectiveOutput != protectedRtl && !prospectiveOutput.startsWith(protectedRtl),
        s"refusing to write generated RTL under the repository RTL directory: $protectedRtl"
      )
    }

    Files.createDirectories(outputDirectory)
    require(Files.isDirectory(outputDirectory), s"output path is not a directory: $outputDirectory")
    require(
      outputDirectory.toRealPath() == prospectiveOutput,
      s"output directory changed while it was being created: $outputDirectory"
    )

    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      oneFilePerComponent = false
    )
    config.withTimescale = false
    config.generateVerilog {
      val dut = new OpenLa500ICache
      dut.setDefinitionName("icache")
      dut
    }
  }
}
