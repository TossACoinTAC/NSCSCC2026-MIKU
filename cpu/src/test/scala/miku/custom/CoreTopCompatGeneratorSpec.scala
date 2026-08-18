package miku.compat

import org.scalatest.funsuite.AnyFunSuite

class CoreTopCompatGeneratorSpec extends AnyFunSuite {
  test("generator arguments select output directory and optional branch trace") {
    val arguments = CoreTopCompatGeneratorSupport.parseArguments(
      Array("--out-dir", "build/rtl-test", "--branch-trace")
    )

    assert(arguments.outputDirectory == "build/rtl-test")
    assert(arguments.branchTrace)
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
        Array("--out-dir", "build/rtl-test", "--unknown")
      )
    }
  }
}
