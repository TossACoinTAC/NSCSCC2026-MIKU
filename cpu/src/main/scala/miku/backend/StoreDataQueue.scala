package miku.backend

import miku.core._
import spinal.core._
import spinal.lib._

/** Tracks Store data independently from the LSU address issue queue.
  *
  * Address generation may run as soon as psrc1 is ready. This queue waits for psrc2, then presents
  * one registered PRF-read request. The entry remains in that output stage until the LSQ confirms
  * that the reserved Store slot still matches, so recovery cannot silently drop a required data
  * update.
  */
final class StoreDataQueue(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val entryCount = config.storeQueueEntries
  private val slotWidth = log2Up(entryCount)

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(slotWidth bits)
    selected := 0
    for (index <- (0 until entryCount).reverse) {
      when(mask(index)) { selected := U(index, slotWidth bits) }
    }
    selected
  }

  val io = new Bundle {
    val enqueueValid = in Bool ()
    val enqueue = in(RenamedMicroOp(config))
    val enqueueReady = out Bool ()

    val wakeupValid = in Bits (config.writebackWidth bits)
    val wakeupPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)

    val readValid = out Bool ()
    val readPsrc = out UInt (config.physicalRegIndexWidth bits)
    val readRobPointer = out UInt (config.robPointerWidth bits)
    val readStoreQueueIndex = out UInt (config.storeQueueIndexWidth bits)
    val readReady = in Bool ()

    val flush = in Bool ()
    val occupancy = out UInt (log2Up(entryCount + 1) bits)
  }

  val slotValid = Vec.fill(entryCount)(Reg(Bool()) init (False))
  val slotPsrc = Vec.fill(entryCount)(Reg(UInt(config.physicalRegIndexWidth bits)))
  val slotReady = Vec.fill(entryCount)(Reg(Bool()) init (False))
  val slotRobPointer = Vec.fill(entryCount)(Reg(UInt(config.robPointerWidth bits)))
  val slotStoreQueueIndex =
    Vec.fill(entryCount)(Reg(UInt(config.storeQueueIndexWidth bits)))

  val slotWake = Bits(entryCount bits)
  val readyMap = Bits(entryCount bits)
  for (slot <- 0 until entryCount) {
    slotWake(slot) := io.wakeupValid.asBools
      .zip(io.wakeupPdst)
      .map { case (valid, pdst) =>
        valid && pdst =/= 0 && pdst === slotPsrc(slot)
      }
      .reduce(_ || _)
    readyMap(slot) := slotValid(slot) && (slotReady(slot) || slotWake(slot))
  }

  val outputValid = RegInit(False)
  val outputPsrc = Reg(UInt(config.physicalRegIndexWidth bits))
  val outputRobPointer = Reg(UInt(config.robPointerWidth bits))
  val outputStoreQueueIndex = Reg(UInt(config.storeQueueIndexWidth bits))
  val outputReady = !outputValid || io.readReady
  val selectedSlot = selectLowest(readyMap)
  val dequeue = outputReady && readyMap.orR

  val enqueueReadyReg = RegInit(True)
  enqueueReadyReg := CountOne(slotValid.asBits) < U(entryCount - 1)
  io.enqueueReady := enqueueReadyReg
  val enqueue = io.enqueueValid && io.enqueueReady
  val enqueueSlot = selectLowest(~slotValid.asBits)
  val enqueueWake = io.wakeupValid.asBools
    .zip(io.wakeupPdst)
    .map { case (valid, pdst) =>
      valid && pdst =/= 0 && pdst === io.enqueue.psrc2
    }
    .reduce(_ || _)

  when(io.flush) {
    outputValid := False
    for (slot <- 0 until entryCount) { slotValid(slot) := False }
  }.otherwise {
    when(outputReady) {
      outputValid := readyMap.orR
      when(dequeue) {
        outputPsrc := slotPsrc(selectedSlot)
        outputRobPointer := slotRobPointer(selectedSlot)
        outputStoreQueueIndex := slotStoreQueueIndex(selectedSlot)
      }
    }

    for (slot <- 0 until entryCount) {
      val slotEnqueue = enqueue && enqueueSlot === U(slot, slotWidth bits)
      val slotDequeue = dequeue && selectedSlot === U(slot, slotWidth bits)
      when(slotEnqueue) {
        slotValid(slot) := True
        slotPsrc(slot) := io.enqueue.psrc2
        slotReady(slot) := io.enqueue.source2Ready || io.enqueue.psrc2 === 0 || enqueueWake
        slotRobPointer(slot) := io.enqueue.robPointer
        slotStoreQueueIndex(slot) := io.enqueue.storeQueueIndex
      }.elsewhen(slotDequeue) {
        slotValid(slot) := False
      }.elsewhen(slotWake(slot)) {
        slotReady(slot) := True
      }
    }
  }

  io.readValid := outputValid && !io.flush
  io.readPsrc := outputPsrc
  io.readRobPointer := outputRobPointer
  io.readStoreQueueIndex := outputStoreQueueIndex
  io.occupancy := (CountOne(slotValid.asBits) + outputValid.asUInt).resized
}
