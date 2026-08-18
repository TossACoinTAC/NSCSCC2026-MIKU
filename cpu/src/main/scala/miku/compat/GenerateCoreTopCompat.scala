package miku.compat

import java.nio.file.{Files, Path, Paths}
import spinal.core._

private object CoreTopCompatGeneratorSupport {
  private final case class GeneratorArguments(outputDirectory: String, branchTrace: Boolean)

  private def parseArguments(args: Array[String]): GeneratorArguments = {
    var outputDirectory: Option[String] = None
    var branchTrace = false
    var index = 0
    while (index < args.length) {
      args(index) match {
        case "--out-dir" =>
          require(index + 1 < args.length, "--out-dir requires a directory")
          outputDirectory = Some(args(index + 1))
          index += 2
        case "--branch-trace" =>
          branchTrace = true
          index += 1
        case value if outputDirectory.isEmpty && value.nonEmpty =>
          outputDirectory = Some(value)
          index += 1
        case value =>
          throw new IllegalArgumentException(s"unknown generator argument: $value")
      }
    }
    val directory = outputDirectory
      .orElse(sys.env.get("OUT_DIR").filter(_.nonEmpty))
      .getOrElse(
        throw new IllegalArgumentException(
          "output directory is required as an argument or OUT_DIR"
        )
      )
    GeneratorArguments(directory, branchTrace)
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
    val generatorArguments = parseArguments(args)
    val outputDirectory = Paths.get(generatorArguments.outputDirectory).toAbsolutePath.normalize()
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
      val dut = new CoreTopCompat(
        CoreTopCompatConfig(branchTraceObserver = generatorArguments.branchTrace)
      )
      dut.setDefinitionName("core_top")
      dut
    }
  }
}

object GenerateCoreTopCompat {
  def main(args: Array[String]): Unit =
    CoreTopCompatGeneratorSupport.generate(args)
}
