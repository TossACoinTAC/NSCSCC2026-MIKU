package miku.backend

import miku.core._
import spinal.core._
import spinal.lib._

final case class PhysicalRegisterWrite(config: OooCoreConfig) extends Bundle {
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val data = Bits(config.xlen bits)
}

final class PhysicalRegisterFile(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val readAddress = in Vec (UInt(config.physicalRegIndexWidth bits), config.executionWidth * 2)
    val readData = out Vec (Bits(config.xlen bits), config.executionWidth * 2)
    val writeValid = in Bits (config.writebackWidth bits)
    val write = in Vec (PhysicalRegisterWrite(config), config.writebackWidth)
    val debugReadAddress = in UInt (config.physicalRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)
    val flush = in Bool ()
  }

  val registers = Vec.fill(config.physicalRegs)(Reg(Bits(config.xlen bits)))
  registers(0) := B(0, config.xlen bits)

  for (readPort <- 0 until config.executionWidth * 2) {
    val selected = Bits(config.xlen bits)
    selected := Mux(
      io.readAddress(readPort) === 0,
      B(0, config.xlen bits),
      registers(io.readAddress(readPort))
    )
    for (writePort <- (0 until config.writebackWidth).reverse) {
      when(
        io.writeValid(writePort) && io.write(writePort).pdst =/= 0 &&
          io.write(writePort).pdst === io.readAddress(readPort)
      ) {
        selected := io.write(writePort).data
      }
    }
    io.readData(readPort) := selected
  }

  io.debugReadData := Mux(
    io.debugReadAddress === 0,
    B(0, config.xlen bits),
    registers(io.debugReadAddress)
  )
  for (writePort <- (0 until config.writebackWidth).reverse) {
    when(
      io.writeValid(writePort) && io.write(writePort).pdst =/= 0 &&
        io.write(writePort).pdst === io.debugReadAddress
    ) {
      io.debugReadData := io.write(writePort).data
    }
  }

  for (writePort <- 0 until config.writebackWidth) {
    when(io.writeValid(writePort) && io.write(writePort).pdst =/= 0) {
      registers(io.write(writePort).pdst) := io.write(writePort).data
    }
  }
  when(io.flush) { registers(0) := B(0, config.xlen bits) }
}

final class RenameMap(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val renameSource1 = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renameSource2 = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renameDestination = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renamePdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renamePsrc1 = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renamePsrc2 = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renameSource1Ready = out Bits (config.renameWidth bits)
    val renameSource2Ready = out Bits (config.renameWidth bits)
    val renameOldPdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)

    val writebackValid = in Bits (config.writebackWidth bits)
    val writebackPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)

    val commitValid = in Bits (config.commitWidth bits)
    val commitArch = in Vec (UInt(config.archRegIndexWidth bits), config.commitWidth)
    val commitPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.commitWidth)
    val commitPreviousPdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.commitWidth)

    val architecturalMappings = out Vec (UInt(config.physicalRegIndexWidth bits), config.archRegs)
    val physicalReady = out Bits (config.physicalRegs bits)
    val flush = in Bool ()
  }

  val speculative = Vec.fill(config.archRegs)(Reg(UInt(config.physicalRegIndexWidth bits)) init (0))
  val architectural =
    Vec.fill(config.archRegs)(Reg(UInt(config.physicalRegIndexWidth bits)) init (0))
  val ready = Vec.fill(config.physicalRegs)(Reg(Bool()) init (True))
  speculative(0).init(U(0, config.physicalRegIndexWidth bits))
  architectural(0).init(U(0, config.physicalRegIndexWidth bits))
  ready(0).init(True)
  ready(0) := True

  for (arch <- 0 until config.archRegs) {
    io.architecturalMappings(arch) := architectural(arch)
  }
  io.physicalReady := ready.asBits

  for (lane <- 0 until config.renameWidth) {
    io.renamePsrc1(lane) := speculative(io.renameSource1(lane))
    io.renamePsrc2(lane) := speculative(io.renameSource2(lane))
    io.renameSource1Ready(lane) := ready(io.renamePsrc1(lane))
    io.renameSource2Ready(lane) := ready(io.renamePsrc2(lane))
    io.renameOldPdst(lane) := Mux(
      io.renameDestination(lane) === 0,
      U(0, config.physicalRegIndexWidth bits),
      speculative(io.renameDestination(lane))
    )

    for (older <- 0 until lane) {
      when(
        io.renameValid(older) && io.renameDestination(older) =/= 0 &&
          io.renameDestination(older) === io.renameSource1(lane)
      ) {
        io.renamePsrc1(lane) := io.renamePdst(older)
        io.renameSource1Ready(lane) := False
      }
      when(
        io.renameValid(older) && io.renameDestination(older) =/= 0 &&
          io.renameDestination(older) === io.renameSource2(lane)
      ) {
        io.renamePsrc2(lane) := io.renamePdst(older)
        io.renameSource2Ready(lane) := False
      }
      when(
        io.renameValid(older) && io.renameDestination(older) =/= 0 &&
          io.renameDestination(older) === io.renameDestination(lane)
      ) {
        io.renameOldPdst(lane) := io.renamePdst(older)
      }
    }
  }

  for (lane <- 0 until config.renameWidth) {
    for (write <- 0 until config.writebackWidth) {
      when(io.writebackValid(write) && io.writebackPdst(write) === io.renamePsrc1(lane)) {
        io.renameSource1Ready(lane) := True
      }
      when(io.writebackValid(write) && io.writebackPdst(write) === io.renamePsrc2(lane)) {
        io.renameSource2Ready(lane) := True
      }
    }
  }

  for (lane <- 0 until config.commitWidth) {
    io.commitPreviousPdst(lane) := architectural(io.commitArch(lane))
    for (older <- 0 until lane) {
      when(io.commitValid(older) && io.commitArch(older) === io.commitArch(lane)) {
        io.commitPreviousPdst(lane) := io.commitPdst(older)
      }
    }
  }

  when(io.flush) {
    for (arch <- 1 until config.archRegs) {
      speculative(arch) := architectural(arch)
    }
    for (phys <- 1 until config.physicalRegs) { ready(phys) := True }
  }.otherwise {
    for (lane <- 0 until config.renameWidth) {
      when(io.renameValid(lane) && io.renameDestination(lane) =/= 0) {
        speculative(io.renameDestination(lane)) := io.renamePdst(lane)
      }
    }
    for (phys <- 1 until config.physicalRegs) {
      val allocated = (0 until config.renameWidth)
        .map { lane =>
          io.renameValid(lane) && io.renameDestination(lane) =/= 0 &&
          io.renamePdst(lane) === U(phys, config.physicalRegIndexWidth bits)
        }
        .reduce(_ || _)
      val completed = (0 until config.writebackWidth)
        .map { write =>
          io.writebackValid(write) &&
          io.writebackPdst(write) === U(phys, config.physicalRegIndexWidth bits)
        }
        .reduce(_ || _)
      when(allocated) {
        ready(phys) := False
      }.elsewhen(completed) {
        ready(phys) := True
      }
    }
    for (lane <- 0 until config.commitWidth) {
      when(io.commitValid(lane) && io.commitArch(lane) =/= 0) {
        architectural(io.commitArch(lane)) := io.commitPdst(lane)
      }
    }
  }
}

final class PhysicalRegisterFreeList(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  // Keep the free physical registers in the same circular queue shape as the
  // ysyx rename stage.  A bitmap needs a priority encoder for every allocation
  // lane and then converts the one-hot result back to an index.  The queue only
  // performs a bounded indexed read and advances one pointer per accepted uop.
  private val storageCapacity = config.physicalRegs
  private val usableCapacity = config.physicalRegs - 1
  private val pointerWidth = log2Up(storageCapacity)
  private val countWidth = log2Up(usableCapacity + 1)

  require(usableCapacity > 0)
  require(
    (storageCapacity & (storageCapacity - 1)) == 0,
    "the free-list storage uses natural binary pointer wrap"
  )

  private def advance(pointer: UInt, amount: UInt): UInt = {
    (pointer + amount.resize(pointerWidth)).resized
  }

  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocatePdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val allocateReady = out Bool ()
    val allocateCapacityReady = out Bool ()
    val allocateOldestReady = out Bool ()
    val allocateTwoReady = out Bool ()
    val allocateAccept = in Bool ()
    val allocateAcceptMask = in Bits (config.renameWidth bits)
    val commitFreeValid = in Bits (config.commitWidth bits)
    val commitFreePdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.commitWidth)
    val flush = in Bool ()
  }

  // One physical slot is a sentinel and is never included in freeCount.  The
  // power-of-two storage removes modulo-63 compare/subtract logic from every
  // three-wide release address while preserving exactly p1..p63 as allocatable.
  val freeEntries = Vec((0 until storageCapacity).map { index =>
    Reg(UInt(config.physicalRegIndexWidth bits)) init U(
      if (index < usableCapacity) index + 1 else 0,
      config.physicalRegIndexWidth bits
    )
  })
  val headPtr = Reg(UInt(pointerWidth bits)) init U(0, pointerWidth bits)
  val architecturalHeadPtr = Reg(UInt(pointerWidth bits)) init U(0, pointerWidth bits)
  val tailPtr =
    Reg(UInt(pointerWidth bits)) init U(usableCapacity, pointerWidth bits)
  val freeCount = Reg(UInt(countWidth bits)) init U(usableCapacity, countWidth bits)
  val architecturalFreeCount =
    Reg(UInt(countWidth bits)) init U(usableCapacity, countWidth bits)

  val allocateOffset = Vec(UInt(pointerWidth bits), config.renameWidth)
  for (lane <- 0 until config.renameWidth) {
    allocateOffset(lane) := (if (lane == 0) U(0)
                             else
                               CountOne(
                                 io.allocateValid(lane - 1 downto 0)
                               )).resized
    io.allocatePdst(lane) := freeEntries(advance(headPtr, allocateOffset(lane)))
  }

  val requested = CountOne(io.allocateValid)
  io.allocateReady := !io.flush && freeCount >= requested
  io.allocateOldestReady := !io.flush && freeCount >= io.allocateValid(0).asUInt
  if (config.renameWidth >= 2) {
    io.allocateTwoReady := !io.flush && freeCount >= U(2, countWidth bits)
  } else {
    io.allocateTwoReady := False
  }
  // The global rename decision must not depend on destination decode and its
  // CountOne cone. Reserve enough registers for a worst-case rename group;
  // the exact ready signal remains available for local contract checks.
  io.allocateCapacityReady := freeCount >= U(config.renameWidth, countWidth bits)

  // Keep standalone structure tests and older wrappers source-compatible: a
  // legacy boolean accept still means the complete request group is accepted.
  val acceptedMask = Bits(config.renameWidth bits)
  acceptedMask := Mux(
    io.allocateAcceptMask.orR,
    io.allocateAcceptMask,
    Mux(io.allocateAccept, io.allocateValid, B(0, config.renameWidth bits))
  )
  // An accepted uop without a GPR destination consumes no physical register.
  // Qualify both here and at the backend boundary so a uop-accept mask can
  // never drain the FreeList with stores, branches, or serial operations.
  val acceptedCount = CountOne(acceptedMask & io.allocateValid)
  val releaseValid = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    releaseValid(lane) := io.commitFreeValid(lane) && io.commitFreePdst(lane) =/= 0
  }
  val releaseCount = CountOne(releaseValid)
  val confirmedCount = CountOne(io.commitFreeValid)
  val confirmedArchitecturalHead = advance(architecturalHeadPtr, confirmedCount)
  val confirmedArchitecturalFreeCount = UInt(countWidth bits)
  confirmedArchitecturalFreeCount :=
    (architecturalFreeCount - confirmedCount + releaseCount).resized

  for (lane <- 0 until config.commitWidth) {
    val releaseOffset = (if (lane == 0) U(0)
                         else
                           CountOne(
                             releaseValid(lane - 1 downto 0)
                           )).resized
    when(releaseValid(lane)) {
      freeEntries(advance(tailPtr, releaseOffset)) := io.commitFreePdst(lane)
    }
  }

  when(io.flush) {
    // commitFree* is a registered retirement batch from the preceding cycle.
    // It remains architectural even when the recovery generated by that batch
    // asserts flush now, so restore the speculative view to the post-commit
    // snapshot rather than to the stale pre-commit pointers.
    headPtr := confirmedArchitecturalHead
    freeCount := confirmedArchitecturalFreeCount
  }.otherwise {
    headPtr := advance(headPtr, acceptedCount)
    freeCount := freeCount - acceptedCount + releaseCount
  }

  architecturalHeadPtr := confirmedArchitecturalHead
  architecturalFreeCount := confirmedArchitecturalFreeCount
  tailPtr := advance(tailPtr, releaseCount)
}
