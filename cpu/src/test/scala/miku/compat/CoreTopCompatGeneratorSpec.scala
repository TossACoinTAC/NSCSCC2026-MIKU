package miku.compat

import miku.core.CustomInstructionProfile
import org.scalatest.funsuite.AnyFunSuite

class CoreTopCompatGeneratorSpec extends AnyFunSuite {
  test("generator arguments select an explicit profile and output directory") {
    val arguments = CoreTopCompatGeneratorSupport.parseArguments(
      Array("--out-dir", "build/rtl-test", "--custom-profile", "off")
    )

    assert(arguments.outputDirectory == "build/rtl-test")
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
        Array("--out-dir", "build/rtl-test", "--custom-profile", "off", "--custom-profile", "off")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--out-dir", "build/rtl-test", "--unknown")
      )
    }
    intercept[IllegalArgumentException] {
      CoreTopCompatGeneratorSupport.parseArguments(
        Array("--out-dir", "build/rtl-test", "--custom-profile", "not-registered")
      )
    }
  }
}
