package miku.compat

import java.nio.file.{Files, Path, Paths}
import miku.core.CustomInstructionProfile
import spinal.core._

private[compat] object CoreTopCompatGeneratorSupport {
  private[compat] final case class GeneratorArguments(
      outputDirectory: String,
      customInstructionProfile: CustomInstructionProfile
  )

  private val usage =
    "usage: GenerateCoreTopCompat [--out-dir] <output-directory> " +
      "[--custom-profile PROFILE_NAME]"

  private[compat] def parseArguments(args: Array[String]): GeneratorArguments = {
    var outputDirectory = Option.empty[String]
    var customProfile = Option.empty[CustomInstructionProfile]
    var index = 0

    while (index < args.length) {
      args(index) match {
        case "--out-dir" =>
          require(index + 1 < args.length, s"--out-dir requires a directory; $usage")
          require(outputDirectory.isEmpty, s"output directory was specified more than once; $usage")
          outputDirectory = Some(args(index + 1)).filter(_.nonEmpty)
          index += 2
        case "--custom-profile" =>
          require(index + 1 < args.length, s"--custom-profile requires a profile name; $usage")
          require(customProfile.isEmpty, s"custom profile was specified more than once; $usage")
          customProfile = Some(CustomInstructionProfile.fromName(args(index + 1)))
          index += 2
        case option if option.startsWith("--") =>
          throw new IllegalArgumentException(s"unknown option '$option'; $usage")
        case path =>
          require(outputDirectory.isEmpty, s"output directory was specified more than once; $usage")
          outputDirectory = Some(path).filter(_.nonEmpty)
          index += 1
      }
    }

    val selectedOutput = outputDirectory
      .orElse(sys.env.get("OUT_DIR").filter(_.nonEmpty))
      .getOrElse(throw new IllegalArgumentException(s"output directory is required; $usage"))
    GeneratorArguments(
      selectedOutput,
      customProfile.getOrElse(CustomInstructionProfile.Disabled)
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

  def generate(args: Array[String]): Unit = {
    val arguments = parseArguments(args)
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
      val dut = new CoreTopCompat(
        CoreTopCompatConfig(customInstructionProfile = arguments.customInstructionProfile)
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
