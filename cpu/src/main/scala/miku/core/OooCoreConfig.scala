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
    // Admit the oldest rename uop when a complete group cannot fit, then compact
    // the remaining decode-buffer entries behind it.
    enableRenameOldestFallback: Boolean = true,
    // If a complete group cannot fit, admit the longest two-lane prefix that the
    // backend can accept before falling back to the oldest lane only.
    enableRenameTwoWideFallback: Boolean = false,
    robEntries: Int = 32,
    // Track the next ROB state slot with a one-hot sidecar. Binary allocation pointers remain
    // authoritative tags and RAM addresses, but no longer drive every resident state decoder.
    enableOneHotRobAllocationState: Boolean = true,
    // Carry the prefetched commit pointer's bank/row ownership as local one-hot sidecars so ROB
    // state selection does not decode the binary pointer again on the commit prefix.
    enableOneHotRobCandidateStateRead: Boolean = false,
    instructionBufferEntries: Int = 16,
    dispatchQueueEntries: Int = 8,
    issueQueueEntriesPerPort: Int = 8,
    loadQueueEntries: Int = 16,
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
    // Capture completion data beside the PRF while the ROB stages the same lane identity.
    // ROB epoch/flush qualification remains the sole write-valid authority one cycle later.
    enableLocalPrfCompletionDataCapture: Boolean = true,
    // Stage architectural RenameMap updates at its local boundary.  The map exposes the pending
    // batch combinationally so consecutive retirement and recovery keep their original semantics.
    enableRegisteredArchitecturalCommit: Boolean = true,
    // Keep the registered map on an unconditional local next-state mux instead of distributing
    // the commit qualifier to every architectural register clock enable.
    enableArchitecturalCommitDataMux: Boolean = true,
    // Decode each compact commit destination once, then distribute local one-hot ownership bits
    // instead of rebuilding three equality comparators at every architectural map entry.
    enableOneHotArchitecturalCommit: Boolean = true,
    enableStoreDataDirectWakeup: Boolean = true,
    enableLsuRegisteredWakeSelectDecoupling: Boolean = true,
    // Registered wakeups still update resident source-ready state, but only direct/early
    // wakeups may make an ordinary IQ entry selectable in the same cycle.
    enableOrdinaryRegisteredWakeSelectDecoupling: Boolean = true,
    // Port 2 accepts only one-cycle ALU/branch operations or the fixed-latency multiplier. Capture
    // its accepted destination beside each consumer IQ so same-cycle select does not route the
    // execution-cluster wake tag back across the backend. Persistent source-ready state continues
    // to use the qualified execution/ROB wakeup network.
    enableLocalFixedPortSelectWakeup: Boolean = true,
    // Ordinary direct producers already sit behind the Backend operand register.  Keep the
    // execution cluster's qualified valid, but replicate the select-only destination locally.
    enableLocalOrdinaryDirectSelectWakeup: Boolean = true,
    enableTokenizedOrdinaryIssueOutput: Boolean = true,
    // Capture tokenized issue source tags through the existing one-hot selected-slot mask rather
    // than a second binary physical-slot read at the operand boundary.
    enableOneHotTokenPsrcCapture: Boolean = true,
    enableBalancedIssueSelection: Boolean = true,
    enableBankedLoadForwardCompletion: Boolean = true,
    // Store-forwarded Loads retain their normal completion, but their consumers
    // use the following ROB wakeup by default.  This keeps one narrow valid bit
    // from driving every IQ fast-select payload register across the backend.
    enableBankedForwardLoadFastWakeup: Boolean = false,
    // Select the scheduled Load payload with its binary queue index.  The legacy one-hot decode
    // followed by conditional bundle overrides forms a wide priority mux after the age selector.
    enableIndexedScheduledLoadSelection: Boolean = true,
    // Keep the scheduled-load owner/payload registers clock-enabled only in the legacy path.
    // The D-mux variant preserves the registered boundary while moving the reselect qualifier
    // out of the high-fanout CE network.
    enableScheduledLoadDataMux: Boolean = true,
    // Encode the rotated 16-entry pending maps as two 4-way priority levels. This preserves the
    // lowest-set-bit result while avoiding a device carry chain in the scheduled-Load head path.
    enableBalancedLoadPrioritySelect: Boolean = true,
    // Maintain the scheduler-visible pending state as a narrow sidecar. Resident LQ payload and
    // completion state no longer feed the oldest-load selection cone on every cycle.
    enableRegisteredLoadPendingMap: Boolean = true,
    // Retry one younger, already translated cached Load when the oldest pending Load is blocked
    // only by a local Store alias.  The alternate still traverses the ordinary LSQ order checks.
    enableYoungerReadyLoadBypass: Boolean = true,
    enableFlushDecoupledDirectWakeup: Boolean = true,
    enableHeadCompletionCommitBypass: Boolean = true,
    enableBranchHeadCompletionBypass: Boolean = true,
    // Register speculative RAS operations before they drive the RAS array write enable.  The
    // pending-push top bypass keeps the following synchronous predictor response cycle-equivalent.
    // The direct input path remains available for predictor timing A/B and older configurations.
    enableRegisteredSpeculativeRasUpdate: Boolean = true,
    // Redirect validity still carries the architectural request. Sample the associated target
    // every cycle so the ROB commit selector does not fan out onto a 32-bit register CE network.
    enableUnconditionalPrivilegedRedirectTargetCapture: Boolean = true,
    enableDirectDmwPretranslation: Boolean = true,
    enableLoadCompletionEarlyWakeup: Boolean = true,
    // Preload the data-side micro-TLB probe key while the request stream is ready.  The request
    // fire still owns probe state; disabling this flag preserves the legacy fire-time key capture.
    enableDataTranslationProbePreload: Boolean = true,
    enableFrontendTranslationResponseBypass: Boolean = true,
    enableFrontendTranslationTurnover: Boolean = true,
    enableFrontendHistoryTurnover: Boolean = true,
    // Count the four visible response lanes with a balanced population count instead of a
    // lane-serial prefix adder. Enqueue timing and the visible taken-prefix are unchanged.
    enableBalancedFrontendResponseCount: Boolean = true,
    // Select the oldest taken/learn lane through one local encoder instead of a serial chain of
    // wide target overrides after L1I response predecode.
    enableIndexedFrontendResponseTargets: Boolean = true,
    enableBalancedFrontendPredictionSelect: Boolean = true,
    // B02-F: widen the gshare history/PHT.  Its deterministic background initialization keeps
    // fetch active on BTFNT and does not propagate startup latency into predictor-update commit.
    enableLargeGshare: Boolean = true,
    largeGshareHistoryWidth: Int = 10,
    enableFrontendCacheHitTurnover: Boolean = true,
    enableSpeculativeInstructionArrayRead: Boolean = true,
    // L1I never performs a lookup and a refill install in the same controller state.  Keep the
    // data-array read enable independent of the install write qualifier so the BRAM CE cone is
    // driven by lookup/maintenance acceptance only.
    enableInstructionArrayDataReadDecoupling: Boolean = true,
    // L1I data reads have no architectural side effect and are observed only behind a qualified
    // tag response. Tie their read enable high to remove hit-turnover control from the BRAM CE.
    enableInstructionArrayAlwaysOnDataRead: Boolean = true,
    // The same L1I controller exclusion applies to tag reads and installs. Do not feed a tag-read
    // result through miss/install control and back into another tag RAM's read enable.
    enableInstructionArrayTagReadDecoupling: Boolean = true,
    // Capture branch predecode beside the selected instruction group at the existing response
    // boundary. Frontend target/count logic then starts from registered branch facts.
    enableRegisteredInstructionPredecode: Boolean = true,
    // L1D/L2 arbitration keeps lookup response and line installation mutually exclusive.  Keep
    // data-array reads qualified by lookup/maintenance acceptance independently of write enable.
    enableDataArrayDataReadDecoupling: Boolean = true,
    // When multiple refill waiters are ready, prefer the oldest ROB entry if all candidates share
    // one recovery epoch.  Fall back to physical waiter order across epochs.
    enableL1DWaiterAgeSelect: Boolean = false,
    // Keep the refill-waiter arbitration local to L1D, then publish its response
    // from a one-entry buffer. Hits retain their existing response cycle.
    enableRegisteredL1DWaiterResponse: Boolean = true,
    enableDeferredFrontendCorrectionCleanup: Boolean = true,
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
  require(
    !enableLargeGshare || OooCoreConfig.SupportedLargeGshareHistoryWidths.contains(
      largeGshareHistoryWidth
    ),
    s"large gshare history must be one of ${OooCoreConfig.SupportedLargeGshareHistoryWidths.mkString(", ")}"
  )
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
  // Pointer age uses the high bit of a full circular distance.  The memory
  // epoch proof must have at least twice as many representable states as the
  // maximum live ROB window; this also permits the 64-entry R02 experiment.
  require(
    robEntries < (1 << (memoryEpochWidth - 1)),
    "the memory epoch must distinguish every live ROB entry across wraparound"
  )
  val loadQueueIndexWidth: Int = log2Up(loadQueueEntries)
  val storeQueueIndexWidth: Int = log2Up(storeQueueEntries)
  val fetchSlotWidth: Int = log2Up(fetchWidth)
  val predictorPhtEntriesPerBank: Int = if (enableLargeGshare) 4096 else 1024
  val predictorHistoryWidth: Int = if (enableLargeGshare) largeGshareHistoryWidth else 8
  val predictorPhtIndexWidth: Int = log2Up(predictorPhtEntriesPerBank)
  val predictorMetadataStateLsb: Int = predictorPhtIndexWidth
  val predictorMetadataValidBit: Int = predictorPhtIndexWidth + 2
  require(
    predictorMetadataValidBit < 16,
    "the predictor update metadata must fit the internal 16-bit contract"
  )
}

object OooCoreConfig {
  import ExecutionUnitKind._

  val SupportedLargeGshareHistoryWidths: Set[Int] = Set(8, 10, 12, 14, 16)

  val DefaultExecutionPorts: Vector[ExecutionPortConfig] = Vector(
    ExecutionPortConfig("alu-csr", Set(Alu, Csr, Serial)),
    ExecutionPortConfig("alu-div", Set(Alu, Divide)),
    ExecutionPortConfig("alu-branch-mul", Set(Alu, Branch, Multiply)),
    ExecutionPortConfig("load-store", Set(LoadStore), registeredIssueOutput = true)
  )

  val FourIssueThreeCommit: OooCoreConfig = OooCoreConfig()

  val ExpandedRob: OooCoreConfig = FourIssueThreeCommit.copy(
    robEntries = 64
  )

  // L15 experiment only.  Both the speculative Store queue and the Store-data
  // queue use this capacity; the public/default core remains unchanged.
  val ExpandedStores: OooCoreConfig = FourIssueThreeCommit.copy(
    storeQueueEntries = 16
  )

  // R02 experiment only.  The public/default core remains FourIssueThreeCommit;
  // this variant is selected explicitly by the core-top generator.
  val ExpandedWindow: OooCoreConfig = ExpandedRob.copy(
    physicalRegs = 128
  )
}
