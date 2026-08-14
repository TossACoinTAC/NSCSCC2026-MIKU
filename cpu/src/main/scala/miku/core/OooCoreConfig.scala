package miku.core

import spinal.core.log2Up

sealed trait ExecutionUnitKind

object ExecutionUnitKind {
  case object Alu extends ExecutionUnitKind
  case object Branch extends ExecutionUnitKind
  case object Multiply extends ExecutionUnitKind
  case object Divide extends ExecutionUnitKind
  case object Csr extends ExecutionUnitKind
  case object Serial extends ExecutionUnitKind
  case object LoadStore extends ExecutionUnitKind
}

final case class ExecutionPortConfig(
    name: String,
    capabilities: Set[ExecutionUnitKind],
    registeredIssueOutput: Boolean = false
) {
  require(name.nonEmpty, "an execution port must have a name")
  require(capabilities.nonEmpty, s"execution port '$name' must accept at least one FU kind")
}

final case class CoreCacheGeometry(ways: Int, sets: Int, lineBytes: Int) {
  private def isPowerOfTwo(value: Int): Boolean = value > 0 && (value & (value - 1)) == 0

  require(isPowerOfTwo(ways), "cache ways must be a positive power of two")
  require(isPowerOfTwo(sets), "cache sets must be a positive power of two")
  require(isPowerOfTwo(lineBytes), "cache-line bytes must be a positive power of two")
  require(lineBytes >= 16, "the OoO memory hierarchy requires at least a 16-byte line")
  require(ways - 1 <= 0xffff, "CPUCFG cache ways field is only 16 bits")
  require(log2Up(sets) <= 0xff, "CPUCFG cache sets field is only 8 bits")
  require(log2Up(lineBytes) <= 0x7f, "CPUCFG cache line-size field is only 7 bits")

  val capacityBytes: Int = ways * sets * lineBytes
  val indexWidth: Int = log2Up(sets)
  val offsetWidth: Int = log2Up(lineBytes)
  val tagWidth: Int = 32 - indexWidth - offsetWidth
}

/** LoongArch CPUCFG values implemented by this configured core. */
object CpuConfigEncoding {
  private def cacheGeometry(geometry: CoreCacheGeometry): BigInt =
    BigInt(geometry.ways - 1) |
      (BigInt(log2Up(geometry.sets)) << 16) |
      (BigInt(log2Up(geometry.lineBytes)) << 24)

  def value(config: OooCoreConfig, index: Int): BigInt = index match {
    case 1 =>
      val addressBitsEncoding = BigInt(config.xlen - 1)
      BigInt(1) | BigInt(1 << 2) |
        (addressBitsEncoding << 4) | (addressBitsEncoding << 12)
    case 2 => BigInt(0)
    case 16 => BigInt(0x1d)
    case 17 => cacheGeometry(config.instructionCache)
    case 18 => cacheGeometry(config.dataCache)
    case 19 => cacheGeometry(config.level2Cache)
    case _ => BigInt(0)
  }
}

/** Elaboration-time contract for the new out-of-order backend.
  *
  * The contest implementation is deliberately fixed at four execution issue ports, three-wide
  * decode/rename/dispatch, and three-wide in-order commit. Other parameters describe storage
  * geometry; they do not select a second backend datapath.
  */
final case class OooCoreConfig(
    xlen: Int = 32,
    archRegs: Int = 32,
    fetchWidth: Int = 4,
    decodeWidth: Int = 3,
    renameWidth: Int = 3,
    dispatchWidth: Int = 3,
    commitWidth: Int = 3,
    writebackWidth: Int = 5,
    physicalRegs: Int = 64,
    robEntries: Int = 32,
    instructionBufferEntries: Int = 16,
    dispatchQueueEntries: Int = 8,
    issueQueueEntriesPerPort: Int = 8,
    loadQueueEntries: Int = 8,
    storeQueueEntries: Int = 8,
    mshrEntries: Int = 4,
    enableDivideFastPath: Boolean = false,
    enableFastStoreCompletion: Boolean = true,
    enableStoreTranslationLookahead: Boolean = true,
    // Keep first-time variable-latency completions while suppressing only the
    // registered echo of a tag that was already broadcast by a direct wake.
    enableDirectWakeupEchoSuppression: Boolean = true,
    enableDirectOnlyPortEchoSuppression: Boolean = true,
    enableMultiplyCompletionEchoSuppression: Boolean = false,
    enableLsuRegisteredWakeSelectDecoupling: Boolean = true,
    enableTokenizedOrdinaryIssueOutput: Boolean = true,
    enableBalancedIssueSelection: Boolean = true,
    enableFlushDecoupledDirectWakeup: Boolean = true,
    enableHeadCompletionCommitBypass: Boolean = true,
    enableBranchHeadCompletionBypass: Boolean = true,
    enableDirectDmwPretranslation: Boolean = true,
    enableLoadCompletionEarlyWakeup: Boolean = true,
    enableFrontendTranslationResponseBypass: Boolean = true,
    enableFrontendTranslationTurnover: Boolean = true,
    enableFrontendHistoryTurnover: Boolean = true,
    enableBalancedFrontendPredictionSelect: Boolean = true,
    enableFrontendCacheHitTurnover: Boolean = true,
    enableInstructionOwnerLateBypassPayload: Boolean = true,
    enableRecoveryBranchTrainingPriority: Boolean = true,
    enableL2WriteBack: Boolean = true,
    resetVector: BigInt = BigInt("1c000000", 16),
    instructionCache: CoreCacheGeometry = CoreCacheGeometry(ways = 2, sets = 128, lineBytes = 64),
    dataCache: CoreCacheGeometry = CoreCacheGeometry(ways = 2, sets = 128, lineBytes = 64),
    level2Cache: CoreCacheGeometry = CoreCacheGeometry(ways = 2, sets = 512, lineBytes = 64),
    executionPorts: Vector[ExecutionPortConfig] = OooCoreConfig.DefaultExecutionPorts
) {
  private def isPowerOfTwo(value: Int): Boolean = value > 0 && (value & (value - 1)) == 0

  require(xlen == 32, "the contest target is LA32R")
  require(archRegs == 32, "LA32R has 32 architectural GPRs")
  require(fetchWidth == 4, "the OoO frontend fetches four instruction slots")
  require(decodeWidth == 3, "the OoO backend decodes three instructions per cycle")
  require(renameWidth == 3, "the OoO backend renames three instructions per cycle")
  require(dispatchWidth == 3, "the OoO backend dispatches three instructions per cycle")
  require(commitWidth == 3, "the OoO backend commits three instructions per cycle")
  require(writebackWidth == 5, "four issue ports plus pipelined multiply require five writebacks")
  require(executionPorts.size == 4, "the OoO backend has four execution issue ports")
  require(isPowerOfTwo(fetchWidth), "fetch width must be a power of two")
  require(decodeWidth <= fetchWidth, "decode width cannot exceed fetch width")
  require(renameWidth == decodeWidth, "the first OoO backend keeps decode and rename widths equal")
  require(
    dispatchWidth == renameWidth,
    "the first OoO backend keeps rename and dispatch widths equal"
  )
  require(commitWidth <= renameWidth, "commit width cannot exceed the allocation width")
  require(writebackWidth >= executionPorts.size, "every execution port needs a completion path")
  require(physicalRegs > archRegs, "out-of-order execution requires spare physical registers")
  require(isPowerOfTwo(physicalRegs), "the physical register count must be a power of two")
  require(isPowerOfTwo(robEntries), "the ROB size must be a power of two")
  require(robEntries >= renameWidth * 4, "the ROB is too small for the configured width")
  require(isPowerOfTwo(instructionBufferEntries), "instruction buffer size must be a power of two")
  require(
    instructionBufferEntries >= fetchWidth + decodeWidth,
    "the instruction buffer must hold one fetch group plus one decode group"
  )
  require(isPowerOfTwo(dispatchQueueEntries), "the dispatch queue size must be a power of two")
  require(
    dispatchQueueEntries >= dispatchWidth * 2,
    "the dispatch queue must absorb at least two allocation groups"
  )
  require(isPowerOfTwo(issueQueueEntriesPerPort), "issue queue size must be a power of two")
  require(isPowerOfTwo(loadQueueEntries), "load queue size must be a power of two")
  require(isPowerOfTwo(storeQueueEntries), "store queue size must be a power of two")
  require(isPowerOfTwo(mshrEntries), "MSHR count must be a power of two")
  require(instructionCache.lineBytes == 64, "the OoO instruction cache uses 64-byte lines")
  require(dataCache.lineBytes == 64, "the OoO data cache uses 64-byte lines")
  require(level2Cache.lineBytes == 64, "the OoO L2 cache uses 64-byte lines")
  require(
    executionPorts.count(_.capabilities.contains(ExecutionUnitKind.LoadStore)) == 1,
    "the initial backend requires exactly one LSU port"
  )
  require(
    executionPorts.exists(_.capabilities.contains(ExecutionUnitKind.Branch)),
    "at least one execution port must resolve branches"
  )

  val executionWidth: Int = executionPorts.size
  val archRegIndexWidth: Int = log2Up(archRegs)
  val physicalRegIndexWidth: Int = log2Up(physicalRegs)
  val robIndexWidth: Int = log2Up(robEntries)
  val robPointerWidth: Int = robIndexWidth + 1
  val recoveryEpochWidth: Int = 8
  val memoryEpochWidth: Int = 8
  val reservationAddressWidth: Int = xlen - dataCache.offsetWidth
  require(robEntries <= 32, "the memory epoch proof assumes at most 32 live ROB entries")
  require(
    robEntries < (1 << (memoryEpochWidth - 1)),
    "the memory epoch must distinguish every live ROB entry across wraparound"
  )
  val loadQueueIndexWidth: Int = log2Up(loadQueueEntries)
  val storeQueueIndexWidth: Int = log2Up(storeQueueEntries)
  val fetchSlotWidth: Int = log2Up(fetchWidth)
}

object OooCoreConfig {
  import ExecutionUnitKind._

  val DefaultExecutionPorts: Vector[ExecutionPortConfig] = Vector(
    ExecutionPortConfig("alu-csr", Set(Alu, Csr, Serial)),
    ExecutionPortConfig("alu-div", Set(Alu, Divide)),
    ExecutionPortConfig("alu-branch-mul", Set(Alu, Branch, Multiply)),
    ExecutionPortConfig("load-store", Set(LoadStore), registeredIssueOutput = true)
  )

  val FourIssueThreeCommit: OooCoreConfig = OooCoreConfig()
}
