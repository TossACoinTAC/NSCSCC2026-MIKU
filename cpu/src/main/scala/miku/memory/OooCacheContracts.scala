package miku.memory

import miku.core._
import miku.predict._
import spinal.core._
import spinal.lib._

object OooCacheContract {
  val LineBytes = 64
  val LineBits = LineBytes * 8
  val BeatBytes = 8
  val BeatBits = BeatBytes * 8
  val BeatsPerLine = LineBytes / BeatBytes
  val BeatIndexWidth = log2Up(BeatsPerLine)
}

object OooCacheMaintenanceTarget {
  def instructionL1: UInt = U(0, 3 bits)
  def dataL1: UInt = U(1, 3 bits)
  def unifiedL2: UInt = U(2, 3 bits)
}

object OooCacheMaintenanceMode {
  def storeTag: UInt = U(0, 2 bits)
  def index: UInt = U(1, 2 bits)
  def hit: UInt = U(2, 2 bits)
}

/** One architected CACOP operation after decode and optional address translation. */
final case class OooCacheMaintenanceRequest(config: OooCoreConfig) extends Bundle {
  val code = Bits(5 bits)
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
}

/** Completion token for an accepted CACOP operation. */
final case class OooCacheMaintenanceResponse(config: OooCoreConfig) extends Bundle {
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
}

final case class OooCacheRequest(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val isWrite = Bool()
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val writeData = Bits(config.xlen bits)
  val uncached = Bool()
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
}

final case class OooCacheResponse(config: OooCoreConfig) extends Bundle {
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val data = Bits(config.xlen bits)
  val error = Bool()
}

/** One aligned frontend fetch group.
  *
  * The cache selects the 16-byte group containing `physicalAddress`. The frontend keeps the
  * original virtual address to mask instructions preceding an unaligned branch target.
  */
final case class OooInstructionCacheRequest(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val uncached = Bool()
}

final case class OooInstructionCacheResponse(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val instructions = Vec(Bits(32 bits), config.fetchWidth)
  val predecode = Vec(OooFetchPredecode(config), config.fetchWidth)
  val error = Bool()
}

final case class OooLineReadRequest(config: OooCoreConfig) extends Bundle {
  val lineAddress = UInt(config.xlen bits)
  val mshrId = UInt(log2Up(config.mshrEntries) bits)
  val criticalBeat = UInt(OooCacheContract.BeatIndexWidth bits)
}

final case class OooLineReadBeat(config: OooCoreConfig) extends Bundle {
  val mshrId = UInt(log2Up(config.mshrEntries) bits)
  val beat = UInt(OooCacheContract.BeatIndexWidth bits)
  val data = Bits(OooCacheContract.BeatBits bits)
  val last = Bool()
  val error = Bool()
}

final case class OooLineWriteRequest(config: OooCoreConfig) extends Bundle {
  val lineAddress = UInt(config.xlen bits)
  val data = Bits(OooCacheContract.LineBits bits)
  val byteMask = Bits(OooCacheContract.LineBytes bits)
  val mshrId = UInt(log2Up(config.mshrEntries) bits)
}

final case class OooLineWriteResponse(config: OooCoreConfig) extends Bundle {
  val mshrId = UInt(log2Up(config.mshrEntries) bits)
  val error = Bool()
}

final class OooMshrTable(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val idWidth = log2Up(config.mshrEntries)
  private val countWidth = log2Up(config.mshrEntries + 1)

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(idWidth bits)
    selected := U(0, idWidth bits)
    for (index <- (0 until config.mshrEntries).reverse) {
      when(mask(index)) { selected := U(index, idWidth bits) }
    }
    selected
  }

  val io = new Bundle {
    val allocateValid = in Bool ()
    val allocateLineAddress = in UInt (config.xlen bits)
    val allocateReady = out Bool ()
    val allocateId = out UInt (idWidth bits)
    val allocateMerged = out Bool ()

    val completeValid = in Bool ()
    val completeId = in UInt (idWidth bits)
    val flush = in Bool ()
    val activeCount = out UInt (countWidth bits)
  }

  val valid = Vec.fill(config.mshrEntries)(Reg(Bool()) init (False))
  val lineAddress = Vec.fill(config.mshrEntries)(Reg(UInt(config.xlen bits)))

  val mergeMask = Bits(config.mshrEntries bits)
  val freeMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    mergeMask(entry) := valid(entry) &&
      lineAddress(entry)(config.xlen - 1 downto 6) ===
      io.allocateLineAddress(config.xlen - 1 downto 6)
    freeMask(entry) := !valid(entry)
  }
  val merge = mergeMask.orR
  val free = freeMask.orR
  val mergeId = selectLowest(mergeMask)
  val freeId = selectLowest(freeMask)
  io.allocateReady := !io.flush && (merge || free)
  io.allocateMerged := merge
  io.allocateId := Mux(merge, mergeId, freeId)

  when(io.flush) {
    for (entry <- 0 until config.mshrEntries) { valid(entry) := False }
  }.otherwise {
    when(io.completeValid) { valid(io.completeId) := False }
    when(io.allocateValid && io.allocateReady && !merge) {
      valid(freeId) := True
      lineAddress(freeId) := io.allocateLineAddress &
        U(((BigInt(1) << config.xlen) - 1) ^ 0x3f, config.xlen bits)
    }
  }

  io.activeCount := CountOne(valid.asBits)
}
