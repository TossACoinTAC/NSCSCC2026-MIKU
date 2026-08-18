package miku.core

sealed trait CustomInstructionVerificationCase {
  def profileName: String
  def specificationName: String
  def instruction: BigInt
  def source1: BigInt
  def source2: BigInt
}

object CustomInstructionVerificationCase {
  final case class Compute private[core] (
      profileName: String,
      specificationName: String,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedResult: BigInt
  ) extends CustomInstructionVerificationCase

  final case class Branch private[core] (
      profileName: String,
      specificationName: String,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedTaken: Boolean,
      expectedTarget: BigInt
  ) extends CustomInstructionVerificationCase

  final case class Memory private[core] (
      profileName: String,
      specificationName: String,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedAddress: BigInt,
      expectedByteMask: BigInt,
      expectedWriteData: BigInt = 0
  ) extends CustomInstructionVerificationCase

  private val WordMask = (BigInt(1) << 32) - 1

  private def requireWord(value: BigInt, label: String): Unit =
    require(value >= 0 && value <= WordMask, s"$label must be an unsigned 32-bit value")

  private def validateCommon(
      profile: CustomInstructionProfile,
      specification: CustomInstructionSpec,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt
  ): Unit = {
    require(
      profile.specifications.contains(specification),
      s"instruction ${specification.name} is not registered in profile ${profile.name}"
    )
    requireWord(instruction, "verification instruction")
    requireWord(source1, "verification source1")
    requireWord(source2, "verification source2")
    require(
      (instruction & specification.matchMask) == specification.matchValue,
      f"verification instruction 0x$instruction%08x does not match ${specification.name}"
    )
  }

  def compute(
      profile: CustomInstructionProfile,
      specification: CustomInstructionSpec,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedResult: BigInt
  ): Compute = {
    validateCommon(profile, specification, instruction, source1, source2)
    require(
      specification.kind == CustomInstructionKind.Compute,
      s"${specification.name} is not a compute instruction"
    )
    requireWord(expectedResult, "verification compute result")
    Compute(
      profile.name,
      specification.name,
      instruction,
      source1,
      source2,
      expectedResult
    )
  }

  def branch(
      profile: CustomInstructionProfile,
      specification: CustomInstructionSpec,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedTaken: Boolean,
      expectedTarget: BigInt
  ): Branch = {
    validateCommon(profile, specification, instruction, source1, source2)
    require(
      specification.kind == CustomInstructionKind.Branch,
      s"${specification.name} is not a branch instruction"
    )
    requireWord(expectedTarget, "verification branch target")
    Branch(
      profile.name,
      specification.name,
      instruction,
      source1,
      source2,
      expectedTaken,
      expectedTarget
    )
  }

  def memory(
      profile: CustomInstructionProfile,
      specification: CustomInstructionSpec,
      instruction: BigInt,
      source1: BigInt,
      source2: BigInt,
      expectedAddress: BigInt,
      expectedByteMask: BigInt,
      expectedWriteData: BigInt = 0
  ): Memory = {
    validateCommon(profile, specification, instruction, source1, source2)
    require(
      specification.kind == CustomInstructionKind.Load ||
        specification.kind == CustomInstructionKind.Store,
      s"${specification.name} is not a memory instruction"
    )
    requireWord(expectedAddress, "verification memory address")
    require(expectedByteMask >= 0 && expectedByteMask <= 0xf, "byte mask must be four bits")
    requireWord(expectedWriteData, "verification store data")
    Memory(
      profile.name,
      specification.name,
      instruction,
      source1,
      source2,
      expectedAddress,
      expectedByteMask,
      expectedWriteData
    )
  }

  def validateCoverage(
      profiles: Vector[CustomInstructionProfile],
      cases: Vector[CustomInstructionVerificationCase]
  ): Unit = {
    val profilesByName = profiles.map(profile => profile.name -> profile).toMap
    require(
      profilesByName.size == profiles.size,
      "contest verification profiles must have unique names"
    )

    cases.foreach { verification =>
      val profile = profilesByName.getOrElse(
        verification.profileName,
        throw new IllegalArgumentException(
          s"verification case refers to unknown profile ${verification.profileName}"
        )
      )
      val specification = profile.specifications.find(_.name == verification.specificationName)
        .getOrElse(
          throw new IllegalArgumentException(
            s"verification case refers to unknown instruction ${verification.specificationName}"
          )
        )
      require(
        (verification.instruction & specification.matchMask) == specification.matchValue,
        s"verification case no longer matches instruction ${specification.name}"
      )
      (verification, specification.kind) match {
        case (_: Compute, CustomInstructionKind.Compute) =>
        case (_: Branch, CustomInstructionKind.Branch)   =>
        case (_: Memory, CustomInstructionKind.Load)     =>
        case (_: Memory, CustomInstructionKind.Store)    =>
        case _ =>
          throw new IllegalArgumentException(
            s"verification case kind does not match instruction ${specification.name}"
          )
      }
    }

    profiles.foreach { profile =>
      profile.specifications.foreach { specification =>
        require(
          cases.exists(verification =>
            verification.profileName == profile.name &&
              verification.specificationName == specification.name
          ),
          s"contest instruction ${profile.name}/${specification.name} has no verification case"
        )
      }
    }
  }
}
