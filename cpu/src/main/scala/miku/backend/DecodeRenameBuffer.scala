package miku.backend

import miku.core._
import spinal.core._

/** One-entry, three-wide elastic register between decode and rename.
  *
  * The entry can be consumed and replaced in the same cycle, preserving three-uop steady-state
  * throughput while breaking the instruction-buffer-to-rename timing path.
  */
final class DecodeRenameBuffer(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.decodeWidth bits)
    val input = in Vec (DecodedMicroOp(config), config.decodeWidth)
    val inputReady = out Bits (config.decodeWidth bits)
    val outputValid = out Bits (config.renameWidth bits)
    val output = out Vec (DecodedMicroOp(config), config.renameWidth)
    val outputReady = in Bits (config.renameWidth bits)
    val flush = in Bool ()
  }

  val valid = Reg(Bits(config.renameWidth bits)) init (0)
  val payload = Vec.fill(config.renameWidth)(Reg(DecodedMicroOp(config)))
  val outputAccepted = (valid & ~io.outputReady) === 0
  // A01/A02 consume the oldest accepted prefix when backend resources cannot
  // accept the complete group. Keep younger entries and refill the freed tail.
  val partialTwo = if (config.renameWidth >= 3) {
    valid(2) && io.outputReady(1 downto 0) === B"11" && !io.outputReady(2)
  } else {
    False
  }
  val partialOne = valid(0) && io.outputReady(0) &&
    !partialTwo && ((valid & ~io.outputReady) =/= 0)
  val partialOutput = partialTwo || partialOne
  val replace = !valid.orR || outputAccepted
  val allLanes = B((BigInt(1) << config.decodeWidth) - 1, config.decodeWidth bits)
  val partialInputReady = Bits(config.decodeWidth bits)
  partialInputReady := 0
  when(partialTwo) {
    partialInputReady := B(3, config.decodeWidth bits)
  }.elsewhen(valid(2)) {
    partialInputReady := B(1, config.decodeWidth bits)
  }.elsewhen(valid(1)) {
    partialInputReady := B(3, config.decodeWidth bits)
  }

  io.inputReady := Mux(
    partialOutput && !io.flush,
    partialInputReady,
    Mux(replace && !io.flush, allLanes, B(0, config.decodeWidth bits))
  )
  io.outputValid := valid
  io.output := payload

  when(io.flush) {
    valid := 0
  }.elsewhen(partialOutput) {
    when(partialTwo) {
      payload(0) := payload(2)
      when(io.inputValid(0)) { payload(1) := io.input(0) }
      when(io.inputValid(1)) { payload(2) := io.input(1) }
      valid := Cat(io.inputValid(1), io.inputValid(0), True)
    }.elsewhen(valid(2)) {
      payload(0) := payload(1)
      payload(1) := payload(2)
      when(io.inputValid(0)) { payload(2) := io.input(0) }
      valid := Cat(io.inputValid(0), B(3, 2 bits))
    }.elsewhen(valid(1)) {
      payload(0) := payload(1)
      when(io.inputValid(0)) { payload(1) := io.input(0) }
      when(io.inputValid(1)) { payload(2) := io.input(1) }
      valid := Cat(io.inputValid(1), io.inputValid(0), True)
    }
  }.elsewhen(replace) {
    valid := io.inputValid
    for (lane <- 0 until config.renameWidth) {
      when(io.inputValid(lane)) { payload(lane) := io.input(lane) }
    }
  }
}
