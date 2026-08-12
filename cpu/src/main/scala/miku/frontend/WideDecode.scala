package miku.frontend

import miku.backend._
import miku.core._
import spinal.core._

/** Explicit fetch4 to decode3 boundary. The fourth fetch slot remains owned by the frontend buffer.
  */
final class WideDecode(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.fetchWidth bits)
    val pc = in Vec (UInt(config.xlen bits), config.fetchWidth)
    val instruction = in Vec (Bits(32 bits), config.fetchWidth)
    val predictedTaken = in Bits (config.fetchWidth bits)
    val predictedTarget = in Vec (UInt(config.xlen bits), config.fetchWidth)
    val predictorMetadata = in Vec (Bits(16 bits), config.fetchWidth)
    val fetchException = in Vec (ExceptionMetadata(), config.fetchWidth)
    val privilege = in Bits (2 bits)
    val interruptPending = in Bool ()
    val outputValid = out Bits (config.decodeWidth bits)
    val decoded = out Vec (DecodedMicroOp(config), config.decodeWidth)
  }

  for (lane <- 0 until config.decodeWidth) {
    val decoder = new La32rDecoder(config)
    decoder.io.pc := io.pc(lane)
    decoder.io.instruction := io.instruction(lane)
    decoder.io.fetchSlot := U(lane, config.fetchSlotWidth bits)
    decoder.io.predictedTaken := io.predictedTaken(lane)
    decoder.io.predictedTarget := io.predictedTarget(lane)
    decoder.io.predictorMetadata := io.predictorMetadata(lane)
    decoder.io.fetchException := io.fetchException(lane)
    decoder.io.privilege := io.privilege
    decoder.io.interruptPending := (if (lane == 0) io.interruptPending else False)
    io.outputValid(lane) := io.inputValid(lane)
    io.decoded(lane) := decoder.io.decoded
  }
}
