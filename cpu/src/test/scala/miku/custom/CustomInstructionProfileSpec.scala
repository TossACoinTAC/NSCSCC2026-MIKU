package miku.core

import java.nio.file.Files
import java.util.Comparator
import miku.compat.{CoreTopCompat, CoreTopCompatConfig}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.SpinalConfig

class CustomInstructionProfileSpec extends AnyFunSuite {
  private val template = CustomInstructionSpec.compute(
    name = "synthetic-compute",
    matchValue = BigInt("d0000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.xor
  )

  test("profiles reject overlapping encodings and duplicate names") {
    val overlapping = template.copy(
      name = "synthetic-overlap",
      matchMask = BigInt("ff000000", 16)
    )
    intercept[IllegalArgumentException] {
      CustomInstructionProfile("overlap", Vector(template, overlapping))
    }

    val disjoint = template.copy(
      name = "synthetic-disjoint",
      matchValue = BigInt("d4000000", 16)
    )
    intercept[IllegalArgumentException] {
      CustomInstructionProfile(
        "duplicate-name",
        Vector(template, disjoint.copy(name = template.name))
      )
    }
  }

  test("profiles assign internal compute operations in declaration order") {
    val second = template.copy(
      name = "synthetic-second",
      matchValue = BigInt("d4000000", 16)
    )
    val profile = CustomInstructionProfile("synthetic-operations", Vector(template, second))
    assert(profile.indexedSpecifications.map(_._2) == Vector(0x3000, 0x3001))
  }

  test("framework enabled profile may be empty while arbitrary profiles cannot") {
    assert(CustomInstructionProfile("enabled", Vector.empty).specifications.isEmpty)
    intercept[IllegalArgumentException] {
      CustomInstructionProfile("empty-enabled", Vector.empty)
    }
    intercept[IllegalArgumentException] {
      CustomInstructionProfile("disabled", Vector(template))
    }
    intercept[IllegalArgumentException] {
      CustomInstructionProfile("off", Vector(template))
    }
  }

  test("profile names use normalized lowercase identifiers") {
    assert(CustomInstructionProfile("final-2026.v1", Vector(template)).name == "final-2026.v1")
    Seq("Final-2026", " final-2026", "final/2026", "final 2026").foreach { name =>
      intercept[IllegalArgumentException] {
        CustomInstructionProfile(name, Vector(template))
      }
    }
  }

  test("encodings fix a complete reserved opcode by default") {
    intercept[IllegalArgumentException] {
      template.copy(
        name = "partial-opcode",
        matchValue = 0,
        matchMask = BigInt("03ffffff", 16)
      )
    }
    intercept[IllegalArgumentException] {
      template.copy(
        name = "standard-add",
        matchValue = BigInt("00100000", 16),
        matchMask = BigInt("ffff8000", 16)
      )
    }
    intercept[IllegalArgumentException] {
      template.copy(
        name = "unused-standard-major-subencoding",
        matchValue = BigInt("003f8000", 16),
        matchMask = BigInt("ffff8000", 16)
      )
    }

    val officialOverride = template.copy(
      name = "official-override",
      matchValue = BigInt("00100000", 16),
      matchMask = BigInt("ffff8000", 16),
      allowStandardOpcode = true
    )
    assert(officialOverride.allowStandardOpcode)
  }

  test("operand fields cannot overlap fixed bits or one another") {
    intercept[IllegalArgumentException] {
      template.copy(
        name = "fixed-rj",
        matchMask = BigInt("fc000020", 16)
      )
    }
    intercept[IllegalArgumentException] {
      template.copy(
        name = "overlapping-operands",
        source2 = CustomRegister.Rj
      )
    }
  }

  test("compute selectors are exclusive and require an evaluator") {
    intercept[IllegalArgumentException] {
      template.copy(name = "missing-evaluator", computeEvaluator = None)
    }
    intercept[IllegalArgumentException] {
      template.copy(name = "pc-and-gpr", source1IsPc = true)
    }
    intercept[IllegalArgumentException] {
      template.copy(
        name = "two-second-operands",
        source2 = CustomRegister.Unused,
        immediate = CustomImmediate.SignedI12,
        source2IsImmediate = true,
        source2IsFour = true
      )
    }
  }

  test("writeback behavior is derived from the instruction kind") {
    val load = CustomInstructionSpec.load(
      name = "synthetic-load",
      matchValue = BigInt("d8000000", 16),
      matchMask = BigInt("fc000000", 16),
      immediate = CustomImmediate.SignedI12
    )
    val store = CustomInstructionSpec.store(
      name = "synthetic-store",
      matchValue = BigInt("dc000000", 16),
      matchMask = BigInt("fc000000", 16),
      immediate = CustomImmediate.SignedI12
    )
    val branch = CustomInstructionSpec.branch(
      name = "synthetic-branch",
      matchValue = BigInt("e4000000", 16),
      matchMask = BigInt("fc000000", 16),
      immediate = CustomImmediate.SignedI16Shift2
    )
    val branchLink = branch.copy(
      name = "synthetic-branch-link",
      matchValue = BigInt("e8000000", 16),
      destination = CustomRegister.Fixed(1)
    )

    assert(template.writesGpr)
    assert(load.writesGpr)
    assert(!store.writesGpr)
    assert(!branch.writesGpr)
    assert(branchLink.writesGpr)
    assert(branchLink.branchLink)
  }

  test("branch and memory descriptors reject incomplete contracts") {
    intercept[IllegalArgumentException] {
      CustomInstructionSpec.load(
        name = "load-without-base",
        matchValue = BigInt("d8000000", 16),
        matchMask = BigInt("fc000000", 16),
        immediate = CustomImmediate.SignedI12,
        base = CustomRegister.Unused
      )
    }
    intercept[IllegalArgumentException] {
      CustomInstructionSpec.store(
        name = "store-without-data",
        matchValue = BigInt("dc000000", 16),
        matchMask = BigInt("fc000000", 16),
        immediate = CustomImmediate.SignedI12,
        data = CustomRegister.Unused
      )
    }
    intercept[IllegalArgumentException] {
      CustomInstructionSpec.branch(
        name = "conditional-without-second-source",
        matchValue = BigInt("e4000000", 16),
        matchMask = BigInt("fc000000", 16),
        immediate = CustomImmediate.SignedI16Shift2,
        source1 = CustomRegister.Rj,
        branchKind = CustomBranchKind.Equal
      )
    }
    intercept[IllegalArgumentException] {
      CustomInstructionSpec.branch(
        name = "indirect-without-target",
        matchValue = BigInt("e8000000", 16),
        matchMask = BigInt("fc000000", 16),
        immediate = CustomImmediate.SignedI16Shift2,
        branchKind = CustomBranchKind.RegisterIndirect,
        evaluator = Some(CustomBranchEvaluators.source1NonZero)
      )
    }
  }

  test("fixed registers and memory-size encodings are bounded") {
    assert(CustomRegister.Fixed(0).fieldMask == 0)
    assert(CustomRegister.Fixed(31).fieldMask == 0)
    intercept[IllegalArgumentException] { CustomRegister.Fixed(32) }
    assert(CustomMemorySize.Word == 0)
    assert(CustomMemorySize.Byte == 1)
    assert(CustomMemorySize.Half == 2)
  }

  test("verification cases cover every registered instruction") {
    val profile = CustomInstructionProfile("verified", Vector(template))
    val instruction = template.matchValue | (BigInt(5) << 10) | (BigInt(4) << 5) | 3
    val verification = CustomInstructionVerificationCase.compute(
      profile,
      template,
      instruction,
      source1 = BigInt("12345678", 16),
      source2 = BigInt("89abcdef", 16),
      expectedResult = BigInt("9b9f9b97", 16)
    )
    CustomInstructionVerificationCase.validateCoverage(Vector(profile), Vector(verification))

    intercept[IllegalArgumentException] {
      CustomInstructionVerificationCase.validateCoverage(Vector(profile), Vector.empty)
    }
    intercept[IllegalArgumentException] {
      CustomInstructionVerificationCase.compute(
        profile,
        template,
        BigInt("00100000", 16),
        source1 = 1,
        source2 = 2,
        expectedResult = 3
      )
    }
  }

  test("an enabled profile elaborates the complete compatibility top") {
    val profile = CustomInstructionProfile("elaboration", Vector(template))
    val output = Files.createTempDirectory("miku-custom-core-top-")
    try {
      val spinalConfig = SpinalConfig(
        targetDirectory = output.toString,
        oneFilePerComponent = false,
        headerWithDate = false,
        headerWithRepoHash = false
      )
      spinalConfig.withTimescale = false
      spinalConfig.generateVerilog {
        val dut = new CoreTopCompat(CoreTopCompatConfig(customInstructionProfile = profile))
        dut.setDefinitionName("core_top")
        dut
      }

      val generated = output.resolve("core_top.v")
      assert(Files.isRegularFile(generated))
      val text = Files.readString(generated)
      assert(text.contains("module core_top"))
      assert("(?m)^\\s*input\\s+(?:wire\\s+)?aclk\\b".r.findFirstIn(text).nonEmpty)
      assert(
        "(?m)^\\s*output\\s+(?:wire\\s+)?(?:\\[31:0\\]\\s+)?debug0_wb_inst\\b".r
          .findFirstIn(text)
          .nonEmpty
      )
    } finally {
      val paths = Files.walk(output)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  test("the contest profile catalog is internally consistent") {
    CustomInstructionVerificationCase.validateCoverage(
      ContestCustomInstructionProfiles.Available,
      ContestCustomInstructionProfiles.VerificationCases
    )
    assert(CustomInstructionProfile.Available.head == CustomInstructionProfile.Disabled)
    assert(
      CustomInstructionProfile.Available.tail == ContestCustomInstructionProfiles.Available
    )
    assert(CustomInstructionProfile.fromName("disabled") == CustomInstructionProfile.Disabled)
    assert(CustomInstructionProfile.fromName("off") == CustomInstructionProfile.Disabled)
    ContestCustomInstructionProfiles.Available.foreach { profile =>
      assert(CustomInstructionProfile.fromName(profile.name) == profile)
      assert(CustomInstructionProfile.fromName(profile.name.toUpperCase) == profile)
    }
    intercept[IllegalArgumentException] {
      CustomInstructionProfile.fromName("unregistered")
    }
  }
}
