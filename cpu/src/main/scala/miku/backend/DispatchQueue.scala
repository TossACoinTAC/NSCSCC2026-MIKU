package miku.backend

import miku.core._
import spinal.core._
import spinal.lib._

/** Circular queue separating rename allocation from execution-port routing. */
final class DispatchQueue(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val pointerWidth = log2Up(config.dispatchQueueEntries)
  private val countWidth = log2Up(config.dispatchQueueEntries + 1)
  private val prefixWidth = log2Up(config.renameWidth + 1)

  val io = new Bundle {
    val enqueueValid = in Bits (config.renameWidth bits)
    val enqueue = in Vec (RenamedMicroOp(config), config.renameWidth)
    val enqueueReady = out Bool ()
    val enqueueOldestReady = out Bool ()
    val enqueueAccept = in Bool ()
    val enqueueAcceptMask = in Bits (config.renameWidth bits)

    val dequeueValid = out Bits (config.dispatchWidth bits)
    val dequeue = out Vec (RenamedMicroOp(config), config.dispatchWidth)
    val dequeueReady = in Bits (config.dispatchWidth bits)

    val flush = in Bool ()
    val occupancy = out UInt (countWidth bits)
  }

  val entries = Vec.fill(config.dispatchQueueEntries)(Reg(RenamedMicroOp(config)))
  val head = Reg(UInt(pointerWidth bits)) init (0)
  val tail = Reg(UInt(pointerWidth bits)) init (0)
  val count = Reg(UInt(countWidth bits)) init (0)

  val enqueuePrefix = Vec(UInt(prefixWidth bits), config.renameWidth + 1)
  enqueuePrefix(0) := U(0, prefixWidth bits)
  for (lane <- 0 until config.renameWidth) {
    enqueuePrefix(lane + 1) := enqueuePrefix(lane) + io.enqueueValid(lane).asUInt
  }
  val enqueueCount = enqueuePrefix(config.renameWidth)
  val freeSlots = U(config.dispatchQueueEntries, countWidth bits) - count
  io.enqueueReady := freeSlots >= enqueueCount
  io.enqueueOldestReady := freeSlots =/= 0

  for (lane <- 0 until config.dispatchWidth) {
    io.dequeueValid(lane) := count > U(lane, countWidth bits)
    val source = (head + U(lane, pointerWidth bits)).resized
    io.dequeue(lane) := entries(source)
  }
  val dequeueFire = io.dequeueValid & io.dequeueReady
  val dequeueCount = CountOne(dequeueFire)
  val acceptedMask = Bits(config.renameWidth bits)
  acceptedMask := Mux(
    io.enqueueAcceptMask.orR,
    io.enqueueAcceptMask,
    Mux(io.enqueueAccept, io.enqueueValid, B(0, config.renameWidth bits))
  )

  when(io.flush) {
    head := tail
    count := U(0, countWidth bits)
  }.otherwise {
    when(acceptedMask.orR) {
      for (lane <- 0 until config.renameWidth) {
        when(acceptedMask(lane)) {
          val destination = (tail + enqueuePrefix(lane)).resized
          entries(destination) := io.enqueue(lane)
        }
      }
      tail := tail + CountOne(acceptedMask)
    }
    head := head + dequeueCount
    count := count + CountOne(acceptedMask) -
      dequeueCount
  }

  io.occupancy := count
}

/** Compact registered window between the circular dispatch queue and port routing.
  *
  * The circular queue may require a head-dependent mux to expose its oldest entries. This window
  * keeps the router inputs on direct register outputs while still refilling every slot vacated by
  * the in-order dispatch prefix in the same cycle.
  */
final class DispatchWindow(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val width = config.dispatchWidth
  private val countWidth = log2Up(width + 1)
  require(width == 3, "the fixed OoO backend has a three-wide dispatch window")

  val io = new Bundle {
    val inputValid = in Bits (width bits)
    val input = in Vec (RenamedMicroOp(config), width)
    val inputReady = out Bits (width bits)

    val outputValid = out Bits (width bits)
    val output = out Vec (RenamedMicroOp(config), width)
    val outputReady = in Bits (width bits)

    val flush = in Bool ()
  }

  val entries = Vec.fill(width)(Reg(RenamedMicroOp(config)))
  val count = Reg(UInt(countWidth bits)) init (0)

  for (lane <- 0 until width) {
    io.outputValid(lane) := count > U(lane, countWidth bits)
    io.output(lane) := entries(lane)
  }

  // DispatchRouter only accepts an in-order prefix. Decode that prefix explicitly so the
  // router-ready path does not cross a population count before reaching every payload CE.
  val outputFire = io.outputValid & io.outputReady
  val outputCount = UInt(countWidth bits)
  outputCount := 0
  when(outputFire(0)) { outputCount := 1 }
  when(outputFire(1)) { outputCount := 2 }
  when(outputFire(2)) { outputCount := 3 }
  val remainingCount = count - outputCount
  val availableCount = U(width, countWidth bits) - remainingCount
  for (lane <- 0 until width) {
    io.inputReady(lane) := availableCount > U(lane, countWidth bits)
  }
  val inputCount = CountOne(io.inputValid & io.inputReady)
  val nextCount = remainingCount + inputCount

  when(io.flush) {
    count := 0
  }.otherwise {
    count := nextCount
  }

  // The payload table is deliberately explicit. Invalid tail entries may be overwritten; count
  // alone defines validity. This keeps input-valid/count arithmetic out of the wide payload write
  // enables while preserving every survivor and appending queue entries in program order.
  switch(count) {
    is(0) {
      entries(0) := io.input(0)
      entries(1) := io.input(1)
      entries(2) := io.input(2)
    }
    is(1) {
      when(outputCount === 0) {
        entries(1) := io.input(0)
        entries(2) := io.input(1)
      }.otherwise {
        entries(0) := io.input(0)
        entries(1) := io.input(1)
        entries(2) := io.input(2)
      }
    }
    is(2) {
      switch(outputCount) {
        is(0) {
          entries(2) := io.input(0)
        }
        is(1) {
          entries(0) := entries(1)
          entries(1) := io.input(0)
          entries(2) := io.input(1)
        }
        default {
          entries(0) := io.input(0)
          entries(1) := io.input(1)
          entries(2) := io.input(2)
        }
      }
    }
    default {
      switch(outputCount) {
        is(1) {
          entries(0) := entries(1)
          entries(1) := entries(2)
          entries(2) := io.input(0)
        }
        is(2) {
          entries(0) := entries(2)
          entries(1) := io.input(0)
          entries(2) := io.input(1)
        }
        is(3) {
          entries(0) := io.input(0)
          entries(1) := io.input(1)
          entries(2) := io.input(2)
        }
      }
    }
  }
}
