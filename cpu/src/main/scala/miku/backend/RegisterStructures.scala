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

  // Keep the 8R5W PRF physically partitioned by destination low bits.  A flat
  // 128-entry Vec makes every completion address/data network cross the whole
  // PRF footprint; the bank/row form keeps write decode and data enables local
  // while preserving the same asynchronous read and bypass contract.
  private val storageBankCount = 4
  private val storageBankWidth = log2Up(storageBankCount)
  private val storageBankDepth = config.physicalRegs / storageBankCount
  require(config.physicalRegs % storageBankCount == 0)
  val registerBanks = Vec.fill(storageBankCount)(
    Vec.fill(storageBankDepth)(Reg(Bits(config.xlen bits)))
  )
  val writeBankValid = Vec(Bits(storageBankCount bits), config.writebackWidth)
  val writeBankRowTargets = Vec.fill(config.writebackWidth)(
    Vec(Bits(storageBankDepth bits), storageBankCount)
  )
  val writeBankData = Vec.fill(config.writebackWidth)(
    Vec(Bits(config.xlen bits), storageBankCount)
  )

  def storageBank(address: UInt): UInt =
    address(storageBankWidth - 1 downto 0)

  def storageRow(address: UInt): UInt =
    address(config.physicalRegIndexWidth - 1 downto storageBankWidth)

  for (writePort <- 0 until config.writebackWidth) {
    writeBankValid(writePort) := Mux(
      io.writeValid(writePort) && io.write(writePort).pdst =/= 0,
      UIntToOh(
        storageBank(io.write(writePort).pdst),
        storageBankCount
      ),
      B(0, storageBankCount bits)
    )
    for (bank <- 0 until storageBankCount) {
      writeBankRowTargets(writePort)(bank) := Mux(
        writeBankValid(writePort)(bank),
        UIntToOh(storageRow(io.write(writePort).pdst), storageBankDepth),
        B(0, storageBankDepth bits)
      )
      writeBankData(writePort)(bank) := Mux(
        writeBankValid(writePort)(bank),
        io.write(writePort).data,
        B(0, config.xlen bits)
      )
    }
  }

  for (readPort <- 0 until config.executionWidth * 2) {
    val bankReadData = Vec(Bits(config.xlen bits), storageBankCount)
    for (bank <- 0 until storageBankCount) {
      bankReadData(bank) := registerBanks(bank)(storageRow(io.readAddress(readPort)))
      // Keep bypass selection inside the destination bank.  The preceding
      // write decode already produces bank-local row tokens and data, so a
      // full pdst comparator plus 32-bit mux at every PRF read port merely
      // reconstructs a global completion network.  Retain reverse port
      // ordering to preserve the existing same-cycle bypass priority.
      for (writePort <- (0 until config.writebackWidth).reverse) {
        when(writeBankRowTargets(writePort)(bank)(storageRow(io.readAddress(readPort)))) {
          bankReadData(bank) := writeBankData(writePort)(bank)
        }
      }
    }
    io.readData(readPort) := Mux(
      io.readAddress(readPort) === 0,
      B(0, config.xlen bits),
      bankReadData(storageBank(io.readAddress(readPort)))
    )
  }

  val debugBankReadData = Vec(Bits(config.xlen bits), storageBankCount)
  for (bank <- 0 until storageBankCount) {
    debugBankReadData(bank) := registerBanks(bank)(storageRow(io.debugReadAddress))
    for (writePort <- (0 until config.writebackWidth).reverse) {
      when(writeBankRowTargets(writePort)(bank)(storageRow(io.debugReadAddress))) {
        debugBankReadData(bank) := writeBankData(writePort)(bank)
      }
    }
  }
  io.debugReadData := Mux(
    io.debugReadAddress === 0,
    B(0, config.xlen bits),
    debugBankReadData(storageBank(io.debugReadAddress))
  )

  // Completion data only reaches the matching bank/row.  Write-port order is
  // unchanged: a later port still wins if multiple producers target the same
  // physical register in the same cycle.
  for (bank <- 0 until storageBankCount; row <- 0 until storageBankDepth) {
    if (bank != 0 || row != 0) {
      for (writePort <- 0 until config.writebackWidth) {
        when(writeBankRowTargets(writePort)(bank)(row)) {
          registerBanks(bank)(row) := writeBankData(writePort)(bank)
        }
      }
    }
  }
  registerBanks(0)(0) := B(0, config.xlen bits)
  when(io.flush) { registerBanks(0)(0) := B(0, config.xlen bits) }
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
  val ready = Reg(Bits(config.physicalRegs bits)) init (
    B((BigInt(1) << config.physicalRegs) - 1, config.physicalRegs bits)
  )
  speculative(0).init(U(0, config.physicalRegIndexWidth bits))
  architectural(0).init(U(0, config.physicalRegIndexWidth bits))

  for (arch <- 0 until config.archRegs) {
    io.architecturalMappings(arch) := architectural(arch)
  }
  io.physicalReady := ready

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

  def balancedOr(values: Seq[Bits]): Bits = {
    require(values.nonEmpty)
    if (values.size == 1) {
      values.head
    } else {
      val (lower, upper) = values.splitAt(values.size / 2)
      balancedOr(lower) | balancedOr(upper)
    }
  }

  val allocationMasks = Vec(Bits(config.physicalRegs bits), config.renameWidth)
  for (lane <- 0 until config.renameWidth) {
    allocationMasks(lane) := Mux(
      io.renameValid(lane) && io.renameDestination(lane) =/= 0,
      UIntToOh(io.renamePdst(lane), config.physicalRegs),
      B(0, config.physicalRegs bits)
    )
  }
  val completionMasks = Vec(Bits(config.physicalRegs bits), config.writebackWidth)
  for (write <- 0 until config.writebackWidth) {
    completionMasks(write) := Mux(
      io.writebackValid(write),
      UIntToOh(io.writebackPdst(write), config.physicalRegs),
      B(0, config.physicalRegs bits)
    )
  }
  val allocatedMask = balancedOr(allocationMasks.toSeq)
  val completedMask = balancedOr(completionMasks.toSeq)

  when(io.flush) {
    for (arch <- 1 until config.archRegs) {
      speculative(arch) := architectural(arch)
    }
    ready := B((BigInt(1) << config.physicalRegs) - 1, config.physicalRegs bits)
  }.otherwise {
    for (lane <- 0 until config.renameWidth) {
      when(io.renameValid(lane) && io.renameDestination(lane) =/= 0) {
        speculative(io.renameDestination(lane)) := io.renamePdst(lane)
      }
    }
    ready := (ready | completedMask) & ~allocatedMask
    for (lane <- 0 until config.commitWidth) {
      when(io.commitValid(lane) && io.commitArch(lane) =/= 0) {
        architectural(io.commitArch(lane)) := io.commitPdst(lane)
      }
    }
  }
  ready(0) := True
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
  private val bankCount = 4
  private val bankSelectWidth = log2Up(bankCount)
  private val bankDepth = storageCapacity / bankCount
  private val bankRowWidth = log2Up(bankDepth)

  require(usableCapacity > 0)
  require(
    (storageCapacity & (storageCapacity - 1)) == 0,
    "the free-list storage uses natural binary pointer wrap"
  )
  require(storageCapacity >= bankCount && storageCapacity % bankCount == 0)
  require(config.renameWidth < bankCount && config.commitWidth < bankCount)

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
  val freeEntryBanks = Vec((0 until bankCount).map { bank =>
    Vec((0 until bankDepth).map { row =>
      val index = row * bankCount + bank
      Reg(UInt(config.physicalRegIndexWidth bits)) init U(
        if (index < usableCapacity) index + 1 else 0,
        config.physicalRegIndexWidth bits
      )
    })
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
  }
  // Always read the next three physical queue entries. Destination decode
  // only selects among these candidates at the final narrow mux, rather than
  // driving the free-list bank and row address cones.
  val candidateAddress = Vec(UInt(pointerWidth bits), config.renameWidth)
  val candidateBank = Vec(UInt(bankSelectWidth bits), config.renameWidth)
  val candidateRow = Vec(UInt(bankRowWidth bits), config.renameWidth)
  for (candidate <- 0 until config.renameWidth) {
    candidateAddress(candidate) := advance(
      headPtr,
      U(candidate, pointerWidth bits)
    )
    candidateBank(candidate) := candidateAddress(candidate)(bankSelectWidth - 1 downto 0)
    candidateRow(candidate) :=
      candidateAddress(candidate)(pointerWidth - 1 downto bankSelectWidth)
  }
  val bankReadData = Vec(UInt(config.physicalRegIndexWidth bits), bankCount)
  for (bank <- 0 until bankCount) {
    val selectedRow = UInt(bankRowWidth bits)
    selectedRow := candidateRow(0)
    for (candidate <- 0 until config.renameWidth) {
      when(candidateBank(candidate) === U(bank, bankSelectWidth bits)) {
        selectedRow := candidateRow(candidate)
      }
    }
    bankReadData(bank) := freeEntryBanks(bank)(selectedRow)
  }
  val candidatePdst = Vec(UInt(config.physicalRegIndexWidth bits), config.renameWidth)
  for (candidate <- 0 until config.renameWidth) {
    candidatePdst(candidate) := bankReadData(candidateBank(candidate))
  }
  for (lane <- 0 until config.renameWidth) {
    val writerIndex = UInt(log2Up(config.renameWidth) bits)
    writerIndex := allocateOffset(lane).resized
    io.allocatePdst(lane) := candidatePdst(writerIndex)
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

  val releaseAddress = Vec(UInt(pointerWidth bits), config.commitWidth)
  val releaseBank = Vec(UInt(bankSelectWidth bits), config.commitWidth)
  val releaseRow = Vec(UInt(bankRowWidth bits), config.commitWidth)
  for (lane <- 0 until config.commitWidth) {
    val releaseOffset = (if (lane == 0) U(0)
                         else CountOne(releaseValid(lane - 1 downto 0))).resized
    releaseAddress(lane) := advance(tailPtr, releaseOffset)
    releaseBank(lane) := releaseAddress(lane)(bankSelectWidth - 1 downto 0)
    releaseRow(lane) := releaseAddress(lane)(pointerWidth - 1 downto bankSelectWidth)
  }
  for (bank <- 0 until bankCount) {
    val writeValid = Bool()
    val writeRow = UInt(bankRowWidth bits)
    val writePdst = UInt(config.physicalRegIndexWidth bits)
    writeValid := False
    writeRow := releaseRow(0)
    writePdst := io.commitFreePdst(0)
    for (lane <- 0 until config.commitWidth) {
      when(releaseValid(lane) && releaseBank(lane) === U(bank, bankSelectWidth bits)) {
        writeValid := True
        writeRow := releaseRow(lane)
        writePdst := io.commitFreePdst(lane)
      }
    }
    when(writeValid) {
      freeEntryBanks(bank)(writeRow) := writePdst
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
