package miku.memory

import java.nio.file.{Files, Path, Paths}
import spinal.core._

object GenerateOpenLa500DCache {
  private def outputArgument(args: Array[String]): String = args match {
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
        "usage: GenerateOpenLa500DCache [--out-dir] <output-directory>"
      )
  }

  private def findRepositoryRoot(path: Path): Option[Path] =
    if (path == null) None
    else if (Files.exists(path.resolve(".git"))) Some(path)
    else findRepositoryRoot(path.getParent)

  private def prospectiveRealPath(path: Path): Path =
    if (Files.exists(path)) path.toRealPath()
    else
      Option(path.getParent)
        .map(prospectiveRealPath)
        .map(_.resolve(path.getFileName).normalize())
        .getOrElse(
          throw new IllegalArgumentException(s"output path has no existing ancestor: $path")
        )

  def main(args: Array[String]): Unit = {
    val outputDirectory = Paths.get(outputArgument(args)).toAbsolutePath.normalize()
    val roots = Seq(
      Paths.get("").toAbsolutePath.normalize(),
      Paths
        .get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
        .toAbsolutePath
        .normalize()
    ).flatMap(findRepositoryRoot).distinct
    val prospectiveOutput = prospectiveRealPath(outputDirectory)
    roots.foreach { root =>
      val protectedRtl = prospectiveRealPath(root.resolve("rtl"))
      require(
        prospectiveOutput != protectedRtl && !prospectiveOutput.startsWith(protectedRtl),
        s"refusing to write generated RTL under the repository RTL directory: $protectedRtl"
      )
    }
    Files.createDirectories(outputDirectory)
    require(
      outputDirectory.toRealPath() == prospectiveOutput,
      s"output directory changed while it was being created: $outputDirectory"
    )
    val config =
      SpinalConfig(targetDirectory = outputDirectory.toString, oneFilePerComponent = false)
    config.withTimescale = false
    config.generateVerilog {
      val dut = new OpenLa500DCache
      dut.setDefinitionName("dcache")
      dut
    }
  }
}
