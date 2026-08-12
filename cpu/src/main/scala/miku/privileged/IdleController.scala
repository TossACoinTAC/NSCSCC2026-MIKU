package miku.privileged

import miku.core._
import spinal.core._

/** Delays an IDLE redirect past retirement, then holds the frontend flushed until an interrupt. */
final class IdleController(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val enterValid = in Bool ()
    val enterPc = in UInt (config.xlen bits)
    val interruptPending = in Bool ()
    val redirectValid = out Bool ()
    val redirectTarget = out UInt (config.xlen bits)
    val sleeping = out Bool ()
  }

  val enterPending = RegNext(io.enterValid) init (False)
  val resumePc = Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val sleeping = RegInit(False)

  when(io.enterValid) {
    resumePc := io.enterPc + 4
  }
  when(io.interruptPending) {
    sleeping := False
  }.elsewhen(enterPending) {
    sleeping := True
  }

  io.redirectValid := enterPending || sleeping
  io.redirectTarget := resumePc
  io.sleeping := sleeping
}
