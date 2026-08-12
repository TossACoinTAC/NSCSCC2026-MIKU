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
  val replace = !valid.orR || outputAccepted
  val allLanes = B((BigInt(1) << config.decodeWidth) - 1, config.decodeWidth bits)

  io.inputReady := Mux(replace && !io.flush, allLanes, B(0, config.decodeWidth bits))
  io.outputValid := valid
  io.output := payload

  when(io.flush) {
    valid := 0
  }.elsewhen(replace) {
    valid := io.inputValid
    for (lane <- 0 until config.renameWidth) {
      when(io.inputValid(lane)) { payload(lane) := io.input(lane) }
    }
  }
}
