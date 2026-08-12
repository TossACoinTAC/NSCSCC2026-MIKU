package miku.observe

import spinal.core._
import spinal.lib.Flow

/** One architectural GPR write performed at the commit boundary. */
final case class GprWrite() extends Bundle {
  val valid = Bool()
  val index = UInt(5 bits)
  val data = Bits(32 bits)
}

/** One architectural CSR write performed at the commit boundary. */
final case class CsrWrite() extends Bundle {
  val valid = Bool()
  val address = UInt(14 bits)
  val data = Bits(32 bits)
}

/** Precise exception metadata; it remains meaningful when the instruction did not retire. */
final case class ExceptionEvent() extends Bundle {
  val valid = Bool()
  val ecode = UInt(6 bits)
  val esubcode = UInt(9 bits)
  val badVAddrValid = Bool()
  val badVAddr = UInt(32 bits)
  val tlbRefill = Bool()
  val tlbException = Bool()
  val tlbVppn = UInt(19 bits)
}

/** Architectural load observation associated with the commit boundary. */
final case class LoadEvent() extends Bundle {
  val instructionMask = Bits(8 bits)
  val pAddr = UInt(32 bits)
  val vAddr = UInt(32 bits)

  def active: Bool = instructionMask.orR
}

/** Architectural store observation associated with the commit boundary. */
final case class StoreEvent() extends Bundle {
  val instructionMask = Bits(8 bits)
  val pAddr = UInt(32 bits)
  val vAddr = UInt(32 bits)
  val data = Bits(32 bits)
  val byteMask = Bits(4 bits)

  def active: Bool = instructionMask.orR
}

/** TLB fill observation for the active 32-entry MIKU configuration. */
final case class TlbFillEvent() extends Bundle {
  val valid = Bool()
  val index = UInt(5 bits)
}

/** A single ordered architectural boundary event.
  *
  * `Flow.valid` announces an event. `retired` only announces normal instruction retirement: a
  * precise exception or ERTN may make the event valid while `retired` is false.
  */
final case class CommitEvent() extends Bundle {
  val pc = UInt(32 bits)
  val instruction = Bits(32 bits)
  val retired = Bool()
  val ertn = Bool()
  val isCounterInstruction = Bool()
  val csrRstat = Bool()
  val csrReadData = Bits(32 bits)
  val gprWrite = GprWrite()
  val csrWrite = CsrWrite()
  val exception = ExceptionEvent()
  val timer = UInt(64 bits)
  val load = LoadEvent()
  val store = StoreEvent()
  val tlbFill = TlbFillEvent()
}

object CommitEvent {

  /** Direction is assigned by `master`/`slave` only at a producer or consumer IO boundary. */
  def flow(): Flow[CommitEvent] = Flow(CommitEvent())
}

/** Full architectural state sampled by an observation adapter. Producers must drive `gpr(0)` to
  * zero.
  */
final case class ArchState() extends Bundle {
  val gpr = Vec(Bits(32 bits), 32)

  val crmd = Bits(32 bits)
  val prmd = Bits(32 bits)
  val euen = Bits(32 bits)
  val ecfg = Bits(32 bits)
  val estat = Bits(32 bits)
  val era = Bits(32 bits)
  val badv = Bits(32 bits)
  val eentry = Bits(32 bits)
  val tlbidx = Bits(32 bits)
  val tlbehi = Bits(32 bits)
  val tlbelo0 = Bits(32 bits)
  val tlbelo1 = Bits(32 bits)
  val asid = Bits(32 bits)
  val pgdl = Bits(32 bits)
  val pgdh = Bits(32 bits)
  val save0 = Bits(32 bits)
  val save1 = Bits(32 bits)
  val save2 = Bits(32 bits)
  val save3 = Bits(32 bits)
  val tid = Bits(32 bits)
  val tcfg = Bits(32 bits)
  val tval = Bits(32 bits)
  val ticlr = Bits(32 bits)
  val llbctl = Bits(32 bits)
  val tlbrentry = Bits(32 bits)
  val dmw0 = Bits(32 bits)
  val dmw1 = Bits(32 bits)

  def x0: Bits = gpr(0)
  def x0IsZero: Bool = x0 === B(0, 32 bits)
}
