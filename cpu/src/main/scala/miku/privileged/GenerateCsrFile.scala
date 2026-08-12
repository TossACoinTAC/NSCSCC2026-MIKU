package miku.privileged

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import spinal.core._

private object CsrGenerator {
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

  private def addLockedLegacyParameter(rtl: Path): Unit = {
    val marker = "module csr ("
    val replacement = "module csr #(\n  parameter integer TLBNUM = 32\n) ("
    val source = Files.readString(rtl, StandardCharsets.UTF_8)
    require(
      source.indexOf(marker) >= 0 && source.indexOf(marker) == source.lastIndexOf(marker),
      s"generated csr module declaration is not unique: $rtl"
    )
    Files.writeString(rtl, source.replace(marker, replacement), StandardCharsets.UTF_8)
  }

  def generate(args: Array[String], diffTestEnabled: Boolean): Unit = {
    val output = args match {
      case Array(path) if path.nonEmpty              => path
      case Array("--out-dir", path) if path.nonEmpty => path
      case _ => throw new IllegalArgumentException("usage: generator [--out-dir] <directory>")
    }
    val directory = Paths.get(output).toAbsolutePath.normalize()
    val prospectiveOutput = prospectiveRealPath(directory)
    val roots = Seq(
      Paths.get("").toAbsolutePath.normalize(),
      Paths
        .get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
        .toAbsolutePath
        .normalize()
    ).flatMap(findRepositoryRoot).distinct
    roots.foreach { root =>
      val protectedRtl = prospectiveRealPath(root.resolve("rtl"))
      require(
        prospectiveOutput != protectedRtl && !prospectiveOutput.startsWith(protectedRtl),
        s"refusing to write generated RTL under the repository RTL directory: $protectedRtl"
      )
    }
    Files.createDirectories(directory)
    require(Files.isDirectory(directory), s"output path is not a directory: $directory")
    require(directory.toRealPath() == prospectiveOutput, s"output path changed: $directory")
    val config = SpinalConfig(targetDirectory = directory.toString, oneFilePerComponent = false)
    config.withTimescale = false
    config.generateVerilog {
      val dut = new CsrFile(diffTestEnabled = diffTestEnabled)
      dut.setDefinitionName("csr")
      dut
    }
    addLockedLegacyParameter(directory.resolve("csr.v"))
  }
}

object GenerateCsrFile {
  def main(args: Array[String]): Unit = CsrGenerator.generate(args, diffTestEnabled = false)
}

object GenerateCsrFileDiff {
  def main(args: Array[String]): Unit = CsrGenerator.generate(args, diffTestEnabled = true)
}
