package miku.backend

import miku.core._
import spinal.core._

object ExecutionUnitType {
  val Width = 4

  def alu: UInt = U(0, Width bits)
  def branch: UInt = U(1, Width bits)
  def multiply: UInt = U(2, Width bits)
  def divide: UInt = U(3, Width bits)
  def csr: UInt = U(4, Width bits)
  def loadStore: UInt = U(5, Width bits)
  def serial: UInt = U(6, Width bits)
  def barrier: UInt = U(8, Width bits)

  def isBarrier(fuType: UInt): Bool = fuType(Width - 1)
}

object RecoveryCause {
  val Width = 3

  def none: UInt = U(0, Width bits)
  def branchMispredict: UInt = U(1, Width bits)
  def exception: UInt = U(2, Width bits)
  def ertn: UInt = U(3, Width bits)
  def refetch: UInt = U(4, Width bits)
}

object SystemOperation {
  val Width = 5

  def none: UInt = U(0, Width bits)
  def csrRead: UInt = U(1, Width bits)
  def csrWrite: UInt = U(2, Width bits)
  def csrExchange: UInt = U(3, Width bits)
  def counterId: UInt = U(4, Width bits)
  def counterLow: UInt = U(5, Width bits)
  def counterHigh: UInt = U(6, Width bits)
  def cpuConfig: UInt = U(7, Width bits)
  def ertn: UInt = U(8, Width bits)
  def tlbSearch: UInt = U(9, Width bits)
  def tlbRead: UInt = U(10, Width bits)
  def tlbWrite: UInt = U(11, Width bits)
  def tlbFill: UInt = U(12, Width bits)
  def invalidateTlb: UInt = U(13, Width bits)
  def dataBarrier: UInt = U(14, Width bits)
  def instructionBarrier: UInt = U(15, Width bits)
  def idle: UInt = U(16, Width bits)
  def cacheOperation: UInt = U(17, Width bits)
  def preload: UInt = U(18, Width bits)
  def loadLinked: UInt = U(19, Width bits)
  def storeConditional: UInt = U(20, Width bits)
}

final case class ExceptionMetadata() extends Bundle {
  val valid = Bool()
  val ecode = UInt(6 bits)
  val esubcode = UInt(9 bits)
  val badVAddrValid = Bool()
  val badVAddr = UInt(32 bits)
  val tlbRefill = Bool()
}

/** Pure decode result. Architectural state lookup and side effects are deliberately absent. */
final case class DecodedMicroOp(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val instruction = Bits(32 bits)
  val fetchSlot = UInt(config.fetchSlotWidth bits)

  val rd = UInt(config.archRegIndexWidth bits)
  val rs1 = UInt(config.archRegIndexWidth bits)
  val rs2 = UInt(config.archRegIndexWidth bits)
  val immediate = Bits(config.xlen bits)
  val source1Used = Bool()
  val source2Used = Bool()
  val source1IsPc = Bool()
  val source2IsImmediate = Bool()

  val fuType = UInt(ExecutionUnitType.Width bits)
  val operation = Bits(14 bits)
  val mulDivOperation = Bits(4 bits)
  val mulDivSigned = Bool()
  val memorySize = Bits(2 bits)
  val memorySignExtend = Bool()
  val source2IsFour = Bool()
  val writesGpr = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val isBranch = Bool()
  val branchKind = UInt(3 bits)
  val isCsr = Bool()
  val isLl = Bool()
  val isSc = Bool()
  val isCacheOperation = Bool()
  val isPreload = Bool()
  val isErtn = Bool()
  val isTlbSearch = Bool()
  val isTlbWrite = Bool()
  val isTlbFill = Bool()
  val isTlbRead = Bool()
  val isTlbInvalidate = Bool()
  val isRefetch = Bool()
  val csrReadData = Bits(config.xlen bits)
  val csrAddress = UInt(14 bits)
  val csrWrite = Bool()
  val csrMask = Bool()
  val resultFromCsr = Bool()
  val systemOperation = UInt(SystemOperation.Width bits)
  val serializing = Bool()

  val predictedTaken = Bool()
  val predictedTarget = UInt(config.xlen bits)
  val predictorMetadata = Bits(16 bits)
  val exception = ExceptionMetadata()
}

final case class RenamedMicroOp(config: OooCoreConfig) extends Bundle {
  val decoded = DecodedMicroOp(config)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val oldPdst = UInt(config.physicalRegIndexWidth bits)
  val psrc1 = UInt(config.physicalRegIndexWidth bits)
  val psrc2 = UInt(config.physicalRegIndexWidth bits)
  val source1Ready = Bool()
  val source2Ready = Bool()
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val storeQueueIndex = UInt(config.storeQueueIndexWidth bits)
}

final case class IssuedUop(config: OooCoreConfig) extends Bundle {
  val renamed = RenamedMicroOp(config)
  val source1 = Bits(config.xlen bits)
  val source2 = Bits(config.xlen bits)
}

final case class Completion(config: OooCoreConfig) extends Bundle {
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val writesPdst = Bool()
  val data = Bits(config.xlen bits)
  val sideEffectData = Bits(config.xlen bits)
  val exception = ExceptionMetadata()
  val branchResolved = Bool()
  val branchTaken = Bool()
  val branchTarget = UInt(config.xlen bits)
  val branchMispredict = Bool()
}

final case class RecoveryRequest(config: OooCoreConfig) extends Bundle {
  val cause = UInt(RecoveryCause.Width bits)
  val robPointer = UInt(config.robPointerWidth bits)
  val pc = UInt(config.xlen bits)
  val taken = Bool()
  val target = UInt(config.xlen bits)
  val exception = ExceptionMetadata()
}

final case class CommitRecord(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val instruction = Bits(32 bits)
  val robPointer = UInt(config.robPointerWidth bits)
  val rd = UInt(config.archRegIndexWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val oldPdst = UInt(config.physicalRegIndexWidth bits)
  val writesGpr = Bool()
  val result = Bits(config.xlen bits)
  val retired = Bool()
  val serializing = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val isBranch = Bool()
  val predictorType = UInt(3 bits)
  val branchTaken = Bool()
  val branchTarget = UInt(config.xlen bits)
  val predictorMetadata = Bits(16 bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val storeQueueIndex = UInt(config.storeQueueIndexWidth bits)
  val exception = ExceptionMetadata()
  val systemOperation = UInt(SystemOperation.Width bits)
  val csrAddress = UInt(14 bits)
  val csrWrite = Bool()
  val csrMask = Bool()
  val sideEffectData = Bits(config.xlen bits)
}

/** Memory metadata sampled from the LSQ for one retiring instruction.
  *
  * This is an observation-only path for the simulator-owned DiffTest adapter. The instruction
  * masks follow Chiplab's DifftestLoadEvent and DifftestStoreEvent contracts.
  */
final case class MemoryCommitObservation(config: OooCoreConfig) extends Bundle {
  val loadInstructionMask = Bits(8 bits)
  val storeInstructionMask = Bits(8 bits)
  val physicalAddress = UInt(config.xlen bits)
  val virtualAddress = UInt(config.xlen bits)
  val storeData = Bits(config.xlen bits)
  val storeByteMask = Bits(config.xlen / 8 bits)
}

final case class ReorderBufferAllocate(config: OooCoreConfig) extends Bundle {
  val uop = RenamedMicroOp(config)
}

/** Rename-time memory queue reservation. Address and data arrive later through the AGU. */
final case class LoadStoreQueueAllocate(config: OooCoreConfig) extends Bundle {
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val memoryEpoch = UInt(config.memoryEpochWidth bits)
  val isLoad = Bool()
  val isStore = Bool()
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val storeQueueIndex = UInt(config.storeQueueIndexWidth bits)
}
