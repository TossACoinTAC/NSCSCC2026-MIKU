package miku.predict

import miku.core._
import spinal.core._

/** Registered branch facts carried beside one instruction-cache response lane. */
final case class OooFetchPredecode(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val branchType = UInt(OooPredictedBranchType.Width bits)
  val target = UInt(config.xlen bits)
  val staticTaken = Bool()
  val indirect = Bool()
}

object OooFetchPredecoder {
  private def opcode(instruction: Bits): UInt = instruction(31 downto 26).asUInt

  private def isDirect(instruction: Bits): Bool = {
    val op = opcode(instruction)
    op === U(0x14, 6 bits) || op === U(0x15, 6 bits)
  }

  private def isIndirect(instruction: Bits): Bool = opcode(instruction) === U(0x13, 6 bits)

  private def isConditional(instruction: Bits): Bool = {
    val op = opcode(instruction)
    op >= U(0x16, 6 bits) && op <= U(0x1b, 6 bits)
  }

  private def isReturn(instruction: Bits): Bool =
    isIndirect(instruction) && instruction(4 downto 0) === 0 &&
      instruction(9 downto 5) === 1 && instruction(25 downto 10) === 0

  private def isCall(instruction: Bits): Bool =
    opcode(instruction) === U(0x15, 6 bits) ||
      (isIndirect(instruction) && instruction(4 downto 0) === 1)

  private def directTarget(config: OooCoreConfig, pc: UInt, instruction: Bits): UInt = {
    val directOffset =
      (instruction(9 downto 0) ## instruction(25 downto 10) ## B(0, 2 bits)).asSInt
        .resize(config.xlen)
        .asUInt
    val conditionalOffset =
      (instruction(25 downto 10) ## B(0, 2 bits)).asSInt.resize(config.xlen).asUInt
    pc + Mux(isDirect(instruction), directOffset, conditionalOffset)
  }

  def drive(
      output: OooFetchPredecode,
      config: OooCoreConfig,
      pc: UInt,
      instruction: Bits
  ): Unit = {
    val direct = isDirect(instruction)
    val indirect = isIndirect(instruction)
    val conditional = isConditional(instruction)
    output.valid := direct || indirect || conditional
    output.branchType := OooPredictedBranchType.direct
    when(conditional) {
      output.branchType := OooPredictedBranchType.conditional
    }.elsewhen(isReturn(instruction)) {
      output.branchType := OooPredictedBranchType.ret
    }.elsewhen(isCall(instruction)) {
      output.branchType := OooPredictedBranchType.call
    }.elsewhen(indirect) {
      output.branchType := OooPredictedBranchType.indirect
    }
    output.target := pc + 4
    when(direct || conditional) {
      output.target := directTarget(config, pc, instruction)
    }
    output.staticTaken := direct || (conditional && instruction(25))
    output.indirect := indirect
  }
}
