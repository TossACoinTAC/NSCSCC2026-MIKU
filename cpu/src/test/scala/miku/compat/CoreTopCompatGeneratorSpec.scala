package miku.compat

import miku.core.CustomInstructionProfile
import org.scalatest.funsuite.AnyFunSuite

class CoreTopCompatGeneratorSpec extends AnyFunSuite {
  test("generator arguments select output directory, branch trace, and custom profile") {
    val arguments = CoreTopCompatGeneratorSupport.parseArguments(
      Array("--out-dir", "build/rtl-test", "--branch-trace", "--custom-profile", "off")
    )

    assert(arguments.outputDirectory == "build/rtl-test")
    assert(arguments.branchTrace)
    assert(arguments.customInstructionProfile == CustomInstructionProfile.Disabled)
  }

  test("generator arguments reject duplicate and unknown options") {
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--out-dir", "first", "--out-dir", "second")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--branch-trace", "--branch-trace")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--custom-profile", "off", "--custom-profile", "off")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--out-dir", "build/rtl-test", "--custom-profile", "not-registered")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--out-dir", "build/rtl-test", "--unknown")
      )
    }
  }
}
