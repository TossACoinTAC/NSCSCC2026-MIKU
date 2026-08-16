package miku.compat

import java.nio.file.{Files, Path, Paths}
import miku.core.OooCoreConfig
import spinal.core._

private object CoreTopCompatGeneratorSupport {
  private final case class GeneratorArguments(outputDirectory: String, coreVariant: String)

  private def generatorArguments(args: Array[String]): GeneratorArguments =
    args match {
      case Array(path) if path.nonEmpty => GeneratorArguments(path, "default")
      case Array("--out-dir", path) if path.nonEmpty =>
        GeneratorArguments(path, "default")
      case Array("--out-dir", path, "--core-variant", variant)
          if path.nonEmpty && variant.nonEmpty =>
        GeneratorArguments(path, variant)
      case Array() =>
        GeneratorArguments(
          sys.env
            .get("OUT_DIR")
            .filter(_.nonEmpty)
            .getOrElse(
            throw new IllegalArgumentException(
              "output directory is required as an argument or OUT_DIR"
            )
            ),
          "default"
        )
      case _ =>
        throw new IllegalArgumentException(
          "usage: GenerateCoreTopCompat [--out-dir] <output-directory> " +
            "[--core-variant default|expanded-window]"
        )
    }

  private def coreConfig(variant: String): OooCoreConfig =
    variant match {
      case "default"         => OooCoreConfig.FourIssueThreeCommit
      case "expanded-window" => OooCoreConfig.ExpandedWindow
      case other =>
        throw new IllegalArgumentException(s"unsupported core variant: $other")
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

  def generate(args: Array[String]): Unit = {
    val arguments = generatorArguments(args)
    val outputDirectory = Paths.get(arguments.outputDirectory).toAbsolutePath.normalize()
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

    val spinalConfig = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val dut = new CoreTopCompat(CoreTopCompatConfig(), coreConfig(arguments.coreVariant))
      dut.setDefinitionName("core_top")
      dut
    }
  }
}

object GenerateCoreTopCompat {
  def main(args: Array[String]): Unit =
    CoreTopCompatGeneratorSupport.generate(args)
}
