package miku.execute

import miku.backend.DecodedMicroOp
import miku.core._
import spinal.core._

object CustomExecution {
  def computeResult(
      config: OooCoreConfig,
      decoded: DecodedMicroOp,
      source1: Bits,
      source2: Bits,
      defaultResult: Bits
  ): Bits = {
    val evaluators = config.customInstructionProfile.indexedSpecifications.flatMap {
      case (specification, operation) =>
        specification.computeEvaluator.map(evaluator => (operation, evaluator))
    }
    if (evaluators.nonEmpty) {
      val selectedResult = Bits(config.xlen bits)
      selectedResult := defaultResult
      for ((operation, evaluator) <- evaluators) {
        when(decoded.operation === B(operation, decoded.operation.getWidth bits)) {
          selectedResult := evaluator(source1, source2, decoded.instruction)
        }
      }
      selectedResult
    } else {
      defaultResult
    }
  }

  def branchTaken(
      config: OooCoreConfig,
      decoded: DecodedMicroOp,
      source1: Bits,
      source2: Bits,
      defaultTaken: Bool
  ): Bool = {
    val evaluators = config.customInstructionProfile.specifications.flatMap { specification =>
      specification.branchEvaluator.map(specification -> _)
    }
    if (evaluators.nonEmpty) {
      val selectedTaken = Bool()
      selectedTaken := defaultTaken
      for ((specification, evaluator) <- evaluators) {
        val matched = CustomInstructionDecode.matches(decoded.instruction, specification)
        when(decoded.isBranch && matched) {
          selectedTaken := evaluator(source1, source2, decoded.instruction)
        }
      }
      selectedTaken
    } else {
      defaultTaken
    }
  }

  def branchTarget(
      config: OooCoreConfig,
      decoded: DecodedMicroOp,
      source1: Bits,
      source2: Bits,
      defaultTarget: UInt
  ): UInt = {
    val evaluators = config.customInstructionProfile.branchSpecifications.flatMap {
      specification => specification.branchTargetEvaluator.map(specification -> _)
    }
    if (evaluators.nonEmpty) {
      val selectedTarget = UInt(config.xlen bits)
      selectedTarget := defaultTarget
      for ((specification, evaluator) <- evaluators) {
        val matched = CustomInstructionDecode.matches(decoded.instruction, specification)
        when(decoded.isBranch && matched) {
          selectedTarget := evaluator(
            decoded.pc,
            source1,
            source2,
            decoded.immediate,
            decoded.instruction
          ).resize(config.xlen)
        }
      }
      selectedTarget
    } else {
      defaultTarget
    }
  }

  def memoryAddress(
      config: OooCoreConfig,
      decoded: DecodedMicroOp,
      source1: Bits,
      source2: Bits,
      defaultAddress: UInt
  ): UInt = {
    val evaluators = config.customInstructionProfile.memorySpecifications.flatMap {
      specification => specification.memoryAddressEvaluator.map(specification -> _)
    }
    if (evaluators.nonEmpty) {
      val selectedAddress = UInt(config.xlen bits)
      selectedAddress := defaultAddress
      for ((specification, evaluator) <- evaluators) {
        val matched = CustomInstructionDecode.matches(decoded.instruction, specification)
        when((decoded.isLoad || decoded.isStore) && matched) {
          selectedAddress := evaluator(
            source1,
            source2,
            decoded.immediate,
            decoded.instruction
          ).resize(config.xlen)
        }
      }
      selectedAddress
    } else {
      defaultAddress
    }
  }
}
