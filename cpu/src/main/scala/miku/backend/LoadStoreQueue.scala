package miku.backend

import miku.core._
import miku.execute.AddressGenerationRequest
import miku.memory._
import miku.observe.PerfObservationV1
import miku.privileged._
import spinal.core._
import spinal.lib._

final case class StoreQueueEntry(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val addressReady = Bool()
  val dataReady = Bool()
  val completed = Bool()
  val committed = Bool()
  val requestSent = Bool()
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val memoryEpoch = UInt(config.memoryEpochWidth bits)
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val translationDone = Bool()
  val uncached = Bool()
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val writesPdst = Bool()
  val isSc = Bool()
  val scSuccess = Bool()
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val writeData = Bits(config.xlen bits)
}

final case class LoadQueueEntry(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val addressReady = Bool()
  val requestSent = Bool()
  val completed = Bool()
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val memoryEpoch = UInt(config.memoryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val writesPdst = Bool()
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val translationDone = Bool()
  val uncached = Bool()
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val signExtend = Bool()
  val isLl = Bool()
}

// The selected load crosses two timing domains: age/epoch qualification and
// the wide address/data payload. Keep those domains in separate registers so
// owner comparisons do not become the enable cone for every payload bit.
final case class ScheduledLoadOwner(config: OooCoreConfig) extends Bundle {
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val memoryEpoch = UInt(config.memoryEpochWidth bits)
}

final case class ScheduledLoadPayload(config: OooCoreConfig) extends Bundle {
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val writesPdst = Bool()
  val virtualAddress = UInt(config.xlen bits)
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val signExtend = Bool()
  val isLl = Bool()
}

final case class ScheduledLoadTranslation(config: OooCoreConfig) extends Bundle {
  val physicalAddress = UInt(config.xlen bits)
  val done = Bool()
  val uncached = Bool()
}

final class LoadStoreQueue(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private def isOlder(older: UInt, younger: UInt): Bool = {
    val distance = (younger - older).resize(config.robPointerWidth)
    (distance =/= U(0, config.robPointerWidth bits)) && !distance.msb
  }

  private def formatLoad(word: Bits, address: UInt, size: Bits, signExtend: Bool): Bits = {
    val shift = (address(1 downto 0) ## U(0, 3 bits)).asUInt
    val shifted = word |>> shift
    val byteUpper = Bits(24 bits)
    val halfUpper = Bits(16 bits)
    byteUpper := B(0, 24 bits)
    halfUpper := B(0, 16 bits)
    when(signExtend && shifted(7)) { byteUpper := B((BigInt(1) << 24) - 1, 24 bits) }
    when(signExtend && shifted(15)) { halfUpper := B((BigInt(1) << 16) - 1, 16 bits) }
    val result = Bits(config.xlen bits)
    result := shifted(config.xlen - 1 downto 0)
    when(size === B(0, 3 bits)) {
      result := byteUpper ## shifted(7 downto 0)
    }.elsewhen(size === B(1, 3 bits)) {
      result := halfUpper ## shifted(15 downto 0)
    }
    result
  }

  private def formatStore(data: Bits, address: UInt, size: Bits): Bits = {
    val shifted = Bits(config.xlen bits)
    shifted := data
    when(size === B(0, 3 bits)) {
      switch(address(1 downto 0)) {
        is(U(0, 2 bits)) { shifted := B(0, 24 bits) ## data(7 downto 0) }
        is(U(1, 2 bits)) {
          shifted := B(0, 16 bits) ## data(7 downto 0) ## B(0, 8 bits)
        }
        is(U(2, 2 bits)) {
          shifted := B(0, 8 bits) ## data(7 downto 0) ## B(0, 16 bits)
        }
        default { shifted := data(7 downto 0) ## B(0, 24 bits) }
      }
    }.elsewhen(size === B(1, 3 bits)) {
      shifted := Mux(
        address(1),
        data(15 downto 0) ## B(0, 16 bits),
        B(0, 16 bits) ## data(15 downto 0)
      )
    }
    shifted
  }

  private def clearCompletion(completion: Completion): Unit = {
    completion.robPointer := U(0, config.robPointerWidth bits)
    completion.recoveryEpoch := U(0, config.recoveryEpochWidth bits)
    completion.pdst := U(0, config.physicalRegIndexWidth bits)
    completion.writesPdst := False
    completion.data := B(0, config.xlen bits)
    completion.sideEffectData := B(0, config.xlen bits)
    completion.exception.valid := False
    completion.exception.ecode := U(0, 6 bits)
    completion.exception.esubcode := U(0, 9 bits)
    completion.exception.badVAddrValid := False
    completion.exception.badVAddr := U(0, config.xlen bits)
    completion.exception.tlbRefill := False
    completion.branchResolved := False
    completion.branchTaken := False
    completion.branchTarget := U(0, config.xlen bits)
    completion.branchMispredict := False
  }

  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocate = in Vec (LoadStoreQueueAllocate(config), config.renameWidth)
    val storeDataValid = in Bool ()
    val storeDataRobPointer = in UInt (config.robPointerWidth bits)
    val storeDataStoreQueueIndex = in UInt (config.storeQueueIndexWidth bits)
    val storeData = in Bits (config.xlen bits)
    val storeDataReady = out Bool ()
    val aguValid = in Bool ()
    val agu = in(AddressGenerationRequest(config))
    val aguReady = out Bool ()
    val commitValid = in Bits (config.commitWidth bits)
    val commit = in Vec (CommitRecord(config), config.commitWidth)
    val translationRequest = master(Stream(TranslationRequest(config)))
    val translationResponse = slave(Stream(TranslationResponse(config)))
    val translationBypassAddress = out UInt (config.xlen bits)
    val translationBypass = in(TranslationBypass(config))
    val reservationValid = in Bool ()
    val reservationLineAddress = in Bits (config.reservationAddressWidth bits)
    val dataRequestValid = out Bool ()
    val dataRequest = out(CacheRequest(config))
    val dataRequestReady = in Bool ()
    val dataResponseValid = in Bool ()
    val dataResponse = in(CacheResponse(config))
    val completionValid = out Bool ()
    val completion = out(Completion(config))
    val storeCompletionBypassValid = out Bool ()
    val storeCompletionBypass = out(StoreCompletionIdentity(config))
    val loadWakeupValid = out Bool ()
    val loadWakeupPdst = out UInt (config.physicalRegIndexWidth bits)
    val loadWakeupRecoveryEpoch = out UInt (config.recoveryEpochWidth bits)
    val loadWakeupEpochCurrent = out Bool ()
    val currentRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val releaseLoadValid = out Bits (config.commitWidth bits)
    val releaseStoreValid = out Bits (config.commitWidth bits)
    val commitObservation = out Vec (MemoryCommitObservation(config), config.commitWidth)
    val storeDrainBusy = out Bool ()
    // Separate consumers keep recovery-drain gating out of one wide backend
    // control cone. Both signals intentionally retain identical semantics.
    val storeDrainRenameBlock = out Bool ()
    val storeDrainReleaseBlock = out Bool ()
    val committedMemoryEpoch = in UInt (config.memoryEpochWidth bits)
    val robHeadPointer = in UInt (config.robPointerWidth bits)
    val orderingRobPointer = in UInt (config.robPointerWidth bits)
    val olderStorePending = out Bool ()
    val flush = in Bool ()
  }

  val loadReleaseValid = Bits(config.commitWidth bits)
  val storeReleaseValid = Bits(config.commitWidth bits)
  val aguMisaligned = (io.agu.size === B(2, 3 bits) && io.agu.virtualAddress(1 downto 0) =/= 0) ||
    (io.agu.size === B(1, 3 bits) && io.agu.virtualAddress(0))
  io.translationBypassAddress := io.agu.virtualAddress
  val aguBypassEligible = if (config.enableDirectDmwPretranslation) {
    io.translationBypass.eligible
  } else {
    False
  }
  val aguTranslationBypass = aguBypassEligible && !aguMisaligned
  val aguBypassScSuccess = !io.translationBypass.uncached && io.reservationValid &&
    io.reservationLineAddress === io.translationBypass.physicalAddress(
      config.xlen - 1 downto config.dataCache.offsetWidth
    ).asBits
  val aguFire = Bool()

  val stores = Vec.fill(config.storeQueueEntries)(Reg(StoreQueueEntry(config)))
  val loads = Vec.fill(config.loadQueueEntries)(Reg(LoadQueueEntry(config)))
  for (entry <- stores) {
    entry.valid.init(False)
    entry.addressReady.init(False)
    entry.dataReady.init(False)
    entry.completed.init(False)
    entry.committed.init(False)
    entry.requestSent.init(False)
    entry.translationDone.init(False)
  }
  for (entry <- loads) {
    entry.valid.init(False)
    entry.addressReady.init(False)
    entry.requestSent.init(False)
    entry.completed.init(False)
    entry.translationDone.init(False)
  }
  val storeHead = Reg(UInt(config.storeQueueIndexWidth bits)) init (0)
  // The allocator releases load slots in retirement order.  Keeping the
  // oldest live slot explicitly lets the scheduler rotate a small pending
  // bitmap instead of comparing every load ROB pointer against every other
  // load on every cycle.
  val loadBase = Reg(UInt(config.loadQueueIndexWidth bits)) init (0)
  val drainAfterFlush = RegInit(False)
  val scheduledLoadValid = RegInit(False)
  val loadHead = Reg(UInt(config.loadQueueIndexWidth bits)) init (0)
  val scheduledLoadOwner = Reg(ScheduledLoadOwner(config))
  val scheduledLoadPayload = Reg(ScheduledLoadPayload(config))
  val scheduledLoadTranslation = Reg(ScheduledLoadTranslation(config))
  // Younger-retry selection only needs the age token. Keeping a local copy
  // avoids feeding the full scheduled-load owner bundle into every load CAM
  // comparator while preserving the same registered retry boundary.
  val youngerRetryOwnerRobPointer = Reg(UInt(config.robPointerWidth bits)) init (0)

  // Completed loads remain allocated until commit.  The allocator therefore
  // advances the base only on commit, and a rotated priority select preserves
  // program order across physical slot wrap-around.
  val pendingLoads = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    pendingLoads(entry) := loads(entry).valid && !loads(entry).requestSent &&
      !loads(entry).completed
  }
  // A blocked oldest Load may request one alternate scheduling attempt through a
  // registered token.  Restrict the alternate selection to younger, translated
  // cacheable ordinary Loads, then run that one candidate through the unchanged
  // Store/load ordering cone in the following cycle.
  val tryYoungerLoad = RegInit(False)
  val youngerRetryIndex = Reg(UInt(config.loadQueueIndexWidth bits)) init (0)
  val scheduledLoadWasYoungerBypass = RegInit(False)
  val youngerReadyLoads = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    val load = loads(entry)
    youngerReadyLoads(entry) := pendingLoads(entry) && load.addressReady &&
      load.translationDone && !load.uncached && !load.isLl && scheduledLoadValid &&
      isOlder(youngerRetryOwnerRobPointer, load.robPointer)
  }
  val youngerSearchBase = (loadHead + 1).resize(config.loadQueueIndexWidth)
  val rotatedYoungerReady = ((youngerReadyLoads ## youngerReadyLoads) |>> youngerSearchBase)
    .resize(config.loadQueueEntries)
  val youngerLoadOffset = OHToUInt(OHMasking.first(rotatedYoungerReady))
  val selectedYoungerLoadHead =
    (youngerSearchBase + youngerLoadOffset).resize(config.loadQueueIndexWidth)
  val selectYoungerLoad = if (config.enableYoungerReadyLoadBypass) {
    tryYoungerLoad && scheduledLoadValid && pendingLoads(youngerRetryIndex)
  } else {
    False
  }
  val rotatedPending = ((pendingLoads ## pendingLoads) |>> loadBase)
    .resize(config.loadQueueEntries)
  val loadHeadOffset = OHToUInt(OHMasking.first(rotatedPending))
  val oldestPendingLoadHead =
    (loadBase + loadHeadOffset).resize(config.loadQueueIndexWidth)
  val selectedLoadHead = Mux(selectYoungerLoad, youngerRetryIndex, oldestPendingLoadHead)
  val selectedLoadMask = UIntToOh(selectedLoadHead, config.loadQueueEntries)
  val selectedLoad = LoadQueueEntry(config)
  selectedLoad := loads(0)
  for (entry <- 0 until config.loadQueueEntries) {
    when(selectedLoadMask(entry)) { selectedLoad := loads(entry) }
  }
  val selectedLoadValid = pendingLoads.orR
  // Match the registered uop boundary used by the reference LoadQueue.  The
  // selected index and immutable payload are state: translation, forwarding,
  // and cache request ownership no longer re-read wide queue fields through a
  // second asynchronous loadHead mux.
  when(io.flush) {
    scheduledLoadValid := False
    scheduledLoadWasYoungerBypass := False
  }.otherwise {
    scheduledLoadValid := selectedLoadValid
    scheduledLoadWasYoungerBypass := selectYoungerLoad
    when(selectedLoadValid) {
      loadHead := selectedLoadHead
      youngerRetryOwnerRobPointer := selectedLoad.robPointer
      scheduledLoadOwner.robPointer := selectedLoad.robPointer
      scheduledLoadOwner.recoveryEpoch := selectedLoad.recoveryEpoch
      scheduledLoadOwner.memoryEpoch := selectedLoad.memoryEpoch
      scheduledLoadPayload.pdst := selectedLoad.pdst
      scheduledLoadPayload.writesPdst := selectedLoad.writesPdst
      scheduledLoadPayload.virtualAddress := selectedLoad.virtualAddress
      scheduledLoadTranslation.physicalAddress := selectedLoad.physicalAddress
      scheduledLoadTranslation.done := selectedLoad.translationDone
      scheduledLoadTranslation.uncached := selectedLoad.uncached
      scheduledLoadPayload.size := selectedLoad.size
      scheduledLoadPayload.byteMask := selectedLoad.byteMask
      scheduledLoadPayload.signExtend := selectedLoad.signExtend
      scheduledLoadPayload.isLl := selectedLoad.isLl

      // AGU and scheduler can target the same newly-ready entry on one edge.
      // Bypass that write into the registered payload so this timing cut does
      // not add a cycle to the normal address-to-translation path.
      when(
        aguFire && !io.agu.isWrite && !aguMisaligned &&
          io.agu.uop.loadQueueIndex === selectedLoadHead &&
          selectedLoad.valid && selectedLoad.robPointer === io.agu.uop.robPointer
      ) {
        youngerRetryOwnerRobPointer := io.agu.uop.robPointer
        scheduledLoadOwner.robPointer := io.agu.uop.robPointer
        scheduledLoadOwner.recoveryEpoch := io.agu.uop.recoveryEpoch
        scheduledLoadOwner.memoryEpoch := selectedLoad.memoryEpoch
        scheduledLoadPayload.pdst := io.agu.uop.pdst
        scheduledLoadPayload.writesPdst := io.agu.uop.pdst =/= 0
        scheduledLoadPayload.virtualAddress := io.agu.virtualAddress
        scheduledLoadTranslation.physicalAddress := Mux(
          aguTranslationBypass,
          io.translationBypass.physicalAddress,
          U(0, config.xlen bits)
        )
        scheduledLoadTranslation.done := aguTranslationBypass
        scheduledLoadTranslation.uncached := aguTranslationBypass && io.translationBypass.uncached
        scheduledLoadPayload.size := io.agu.size
        scheduledLoadPayload.byteMask := io.agu.byteMask
        scheduledLoadPayload.signExtend := io.agu.uop.decoded.memorySignExtend
        scheduledLoadPayload.isLl := io.agu.uop.decoded.isLl
      }
    }
  }

  // A direct LSQ probe can present a recycled slot without the allocator's
  // preceding history.  Initialize the base from the first allocation group
  // once, then keep it purely pointer-driven during normal execution.  The
  // age comparisons here terminate at the loadBase register and are not in
  // the completion-to-ROB path.
  val allocationLoads = Bits(config.renameWidth bits)
  private val allocationBankCount = 4
  private val allocationBankWidth = log2Up(allocationBankCount)
  require(config.loadQueueEntries % allocationBankCount == 0)
  require(config.storeQueueEntries % allocationBankCount == 0)
  val loadAllocationTargets = Vec(Bits(config.loadQueueEntries bits), config.renameWidth)
  val storeAllocationTargets = Vec(Bits(config.storeQueueEntries bits), config.renameWidth)
  val loadAllocationBankPayload = Vec.fill(config.renameWidth)(
    Vec(LoadStoreQueueAllocate(config), allocationBankCount)
  )
  val storeAllocationBankPayload = Vec.fill(config.renameWidth)(
    Vec(LoadStoreQueueAllocate(config), allocationBankCount)
  )
  for (lane <- 0 until config.renameWidth) {
    allocationLoads(lane) := io.allocateValid(lane) && io.allocate(lane).isLoad
    loadAllocationTargets(lane) := Mux(
      allocationLoads(lane),
      UIntToOh(io.allocate(lane).loadQueueIndex, config.loadQueueEntries),
      B(0, config.loadQueueEntries bits)
    )
    storeAllocationTargets(lane) := Mux(
      io.allocateValid(lane) && io.allocate(lane).isStore,
      UIntToOh(io.allocate(lane).storeQueueIndex, config.storeQueueEntries),
      B(0, config.storeQueueEntries bits)
    )
    for (bank <- 0 until allocationBankCount) {
      loadAllocationBankPayload(lane)(bank).assignFromBits(
        Mux(
          allocationLoads(lane) &&
            io.allocate(lane).loadQueueIndex(allocationBankWidth - 1 downto 0) ===
              U(bank, allocationBankWidth bits),
          io.allocate(lane).asBits,
          B(0, io.allocate(lane).getBitsWidth bits)
        )
      )
      storeAllocationBankPayload(lane)(bank).assignFromBits(
        Mux(
          io.allocateValid(lane) && io.allocate(lane).isStore &&
            io.allocate(lane).storeQueueIndex(allocationBankWidth - 1 downto 0) ===
              U(bank, allocationBankWidth bits),
          io.allocate(lane).asBits,
          B(0, io.allocate(lane).getBitsWidth bits)
        )
      )
    }
  }
  val initialOldest = Bits(config.renameWidth bits)
  for (lane <- 0 until config.renameWidth) {
    val olderCandidate = Bits(config.renameWidth bits)
    olderCandidate := B(0, config.renameWidth bits)
    for (other <- 0 until config.renameWidth if other != lane) {
      olderCandidate(other) := allocationLoads(other) &&
        isOlder(io.allocate(other).robPointer, io.allocate(lane).robPointer)
    }
    initialOldest(lane) := allocationLoads(lane) && !olderCandidate.orR
  }
  val initialLoadSelect = Mux(
    initialOldest.orR,
    OHMasking.first(initialOldest),
    OHMasking.first(allocationLoads)
  )
  val initialLoadIndex = OHToUInt(initialLoadSelect)
  val liveLoads = loads.map(_.valid).reduce(_ || _)
  when(io.flush) {
    loadBase := U(0, config.loadQueueIndexWidth bits)
  }.otherwise {
    when(!liveLoads && allocationLoads.orR) {
      loadBase := io.allocate(initialLoadIndex).loadQueueIndex
    }.elsewhen(loadReleaseValid.orR) {
      loadBase := (loadBase + CountOne(loadReleaseValid)).resized
    }
  }

  val headStore = stores(storeHead)
  val headLoadState = loads(loadHead)
  // A retired cached Store owns this buffer after leaving the speculative SQ.
  // Its payload remains stable under backpressure and survives recovery, which
  // lets the allocator reuse the SQ slot without widening the ordering CAM.
  val requestBufferValid = RegInit(False)
  val requestBuffer = Reg(CacheRequest(config))
  val requestBufferLoadIndex = Reg(UInt(config.loadQueueIndexWidth bits))
  val requestBufferStoreIndex = Reg(UInt(config.storeQueueIndexWidth bits))
  val bufferedCommittedStore = requestBufferValid && requestBuffer.isWrite &&
    !requestBuffer.uncached
  val committedStorePresent = stores
    .map(entry => entry.valid && (entry.committed || (entry.uncached && entry.requestSent)))
    .reduce(_ || _) || bufferedCommittedStore
  io.storeDrainBusy := drainAfterFlush
  io.storeDrainRenameBlock := drainAfterFlush
  io.storeDrainReleaseBlock := drainAfterFlush
  io.olderStorePending := stores
    .map(entry => entry.valid && isOlder(entry.robPointer, io.orderingRobPointer))
    .reduce(_ || _) ||
    (bufferedCommittedStore && isOlder(requestBuffer.robPointer, io.orderingRobPointer))
  val loadHeadReady = scheduledLoadValid && headLoadState.valid &&
    headLoadState.robPointer === scheduledLoadOwner.robPointer && headLoadState.addressReady &&
    !headLoadState.requestSent && !headLoadState.completed &&
    scheduledLoadOwner.memoryEpoch === io.committedMemoryEpoch

  val unknownOlderStore = Bits(config.storeQueueEntries bits)
  val partialOverlapStore = Bits(config.storeQueueEntries bits)
  val pendingDataStore = Bits(config.storeQueueEntries bits)
  val forwardingStore = Bits(config.storeQueueEntries bits)
  val olderUncachedStore = Bits(config.storeQueueEntries bits)
  for (entry <- 0 until config.storeQueueEntries) {
    val store = stores(entry)
    val older = store.valid && isOlder(store.robPointer, scheduledLoadOwner.robPointer)
    // A virtual synonym is not an alias decision.  Hold every younger load
    // until the older store has a translation, then compare the physical word
    // addresses that the cache and external memory will actually observe.
    val physicalAddressesKnown = store.translationDone && scheduledLoadTranslation.done
    val sameWord = store.physicalAddress(config.xlen - 1 downto 2) ===
      scheduledLoadTranslation.physicalAddress(config.xlen - 1 downto 2)
    val overlap = (store.byteMask & scheduledLoadPayload.byteMask).orR
    val covers = (store.byteMask & scheduledLoadPayload.byteMask) === scheduledLoadPayload.byteMask
    unknownOlderStore(entry) := older && (!store.addressReady || !store.translationDone)
    olderUncachedStore(entry) := older && store.translationDone && store.uncached &&
      !store.completed
    partialOverlapStore(entry) := older && physicalAddressesKnown && sameWord && overlap && !covers
    pendingDataStore(entry) := older && physicalAddressesKnown && sameWord && overlap &&
      !store.dataReady
    forwardingStore(entry) := older && physicalAddressesKnown && store.dataReady && sameWord && covers
  }

  val olderLoadOrderBlock = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    val load = loads(entry)
    val older = load.valid && isOlder(load.robPointer, scheduledLoadOwner.robPointer)
    // Cached requests may overlap once translated. SUC requests retain the
    // order token until their response completes.
    olderLoadOrderBlock(entry) := older && !load.completed &&
      (!load.addressReady || !load.translationDone || load.uncached)
  }

  val forwardingCount = CountOne(forwardingStore)
  val forwardingId = OHToUInt(OHMasking.first(forwardingStore))
  val loadOrderClear = !bufferedCommittedStore && !unknownOlderStore.orR &&
    !olderUncachedStore.orR && !olderLoadOrderBlock.orR &&
    !partialOverlapStore.orR && !pendingDataStore.orR
  val forwardCandidate = loadHeadReady && scheduledLoadTranslation.done &&
    !scheduledLoadTranslation.uncached && !scheduledLoadPayload.isLl && loadOrderClear &&
    forwardingCount === 1
  val cacheLoadBase = loadHeadReady && loadOrderClear && forwardingCount === 0
  val loadAtRequiredOrderPoint = !scheduledLoadTranslation.uncached ||
    scheduledLoadOwner.robPointer === io.robHeadPointer
  val cacheLoadCandidate = cacheLoadBase && scheduledLoadTranslation.done &&
    loadAtRequiredOrderPoint
  val uncachedStoreAtHead = headStore.uncached && !headStore.completed &&
    !headStore.requestSent && headStore.robPointer === io.robHeadPointer
  val cachedStoreCommitted = !headStore.uncached && headStore.completed &&
    headStore.committed
  val storeRequest = headStore.valid && headStore.addressReady && headStore.dataReady &&
    headStore.translationDone && (uncachedStoreAtHead || cachedStoreCommitted) &&
    headStore.memoryEpoch === io.committedMemoryEpoch && (!headStore.isSc || headStore.scSuccess)
  val failedScRelease = headStore.valid && headStore.addressReady &&
    headStore.translationDone && headStore.completed && headStore.committed &&
    headStore.isSc && !headStore.scSuccess

  val translationActive = RegInit(False)
  // A redirect can invalidate the LSQ owner after a translation request has
  // fired, while the translator still owes one response. Keep consuming that
  // response as a cancelled transaction so the shared translator cannot be
  // left permanently backpressured.
  val translationCancelPending = RegInit(False)
  val translationOwnerStore = RegInit(False)
  val translationOwnerRobPointer = Reg(UInt(config.robPointerWidth bits))
  val translationOwnerRecoveryEpoch = Reg(UInt(config.recoveryEpochWidth bits))
  val translationOwnerLoadIndex = Reg(UInt(config.loadQueueIndexWidth bits))
  val translationOwnerStoreIndex = Reg(UInt(config.storeQueueIndexWidth bits))
  val headStoreNeedsTranslation = headStore.valid && headStore.addressReady &&
    !headStore.translationDone
  // Translation has no memory side effect, so overlap it with the unresolved-store window.
  // Store ordering and forwarding are still checked before a translated load reaches D-cache.
  val loadNeedsTranslation = loadHeadReady && !scheduledLoadTranslation.done
  val pendingStoreTranslations = Bits(config.storeQueueEntries bits)
  for (entry <- 0 until config.storeQueueEntries) {
    pendingStoreTranslations(entry) := stores(entry).valid && stores(entry).addressReady &&
      !stores(entry).translationDone
  }
  val rotatedPendingStoreTranslations =
    ((pendingStoreTranslations ## pendingStoreTranslations) |>> storeHead)
      .resize(config.storeQueueEntries)
  val storeTranslationOffset = OHToUInt(OHMasking.first(rotatedPendingStoreTranslations))
  val lookaheadStoreIndex = (storeHead + storeTranslationOffset).resized
  val lookaheadStoreNeedsTranslation = if (config.enableStoreTranslationLookahead) {
    !headStoreNeedsTranslation && !loadNeedsTranslation && pendingStoreTranslations.orR
  } else {
    False
  }
  val selectStoreTranslation = headStoreNeedsTranslation || lookaheadStoreNeedsTranslation
  val storeTranslationIndex = Mux(
    headStoreNeedsTranslation,
    storeHead,
    lookaheadStoreIndex
  )
  val selectedStoreTranslation = stores(storeTranslationIndex)
  io.translationRequest.valid := !io.flush && !translationActive && !translationCancelPending &&
    (selectStoreTranslation || loadNeedsTranslation)
  io.translationRequest.virtualAddress := Mux(
    selectStoreTranslation,
    selectedStoreTranslation.virtualAddress,
    scheduledLoadPayload.virtualAddress
  )
  io.translationRequest.isWrite := selectStoreTranslation
  val translationRequestFire = io.translationRequest.valid && io.translationRequest.ready
  when(translationRequestFire) {
    translationActive := True
    translationOwnerStore := selectStoreTranslation
    translationOwnerRobPointer := Mux(
      selectStoreTranslation,
      selectedStoreTranslation.robPointer,
      scheduledLoadOwner.robPointer
    )
    translationOwnerRecoveryEpoch := Mux(
      selectStoreTranslation,
      selectedStoreTranslation.recoveryEpoch,
      scheduledLoadOwner.recoveryEpoch
    )
    translationOwnerLoadIndex := loadHead
    translationOwnerStoreIndex := storeTranslationIndex
  }

  val requestCandidate = CacheRequest(config)
  requestCandidate.virtualAddress := scheduledLoadPayload.virtualAddress
  requestCandidate.physicalAddress := scheduledLoadTranslation.physicalAddress
  requestCandidate.isWrite := False
  requestCandidate.size := scheduledLoadPayload.size
  requestCandidate.byteMask := scheduledLoadPayload.byteMask
  requestCandidate.writeData := B(0, config.xlen bits)
  requestCandidate.uncached := scheduledLoadTranslation.uncached
  requestCandidate.robPointer := scheduledLoadOwner.robPointer
  requestCandidate.recoveryEpoch := scheduledLoadOwner.recoveryEpoch
  requestCandidate.pdst := scheduledLoadPayload.pdst
  requestCandidate.loadQueueIndex := loadHead
  when(storeRequest) {
    requestCandidate.virtualAddress := headStore.virtualAddress
    requestCandidate.physicalAddress := headStore.physicalAddress
    requestCandidate.isWrite := True
    requestCandidate.size := headStore.size
    requestCandidate.byteMask := headStore.byteMask
    requestCandidate.writeData := formatStore(
      headStore.writeData,
      headStore.virtualAddress,
      headStore.size
    )
    requestCandidate.uncached := headStore.uncached
    requestCandidate.robPointer := headStore.robPointer
    requestCandidate.recoveryEpoch := headStore.recoveryEpoch
    requestCandidate.pdst := U(0, config.physicalRegIndexWidth bits)
    requestCandidate.loadQueueIndex := 0
  }

  // Cut the oldest-load/store-ordering cone before cache and AXI backpressure.
  val requestCapture = !io.flush && !requestBufferValid &&
    (storeRequest || cacheLoadCandidate)
  val earlyCachedStoreRelease = requestCapture && storeRequest && !headStore.uncached
  io.dataRequestValid := requestBufferValid && !io.flush
  io.dataRequest := requestBuffer
  val dataRequestFire = io.dataRequestValid && io.dataRequestReady
  val storeRequestFire = dataRequestFire && requestBuffer.isWrite
  val loadRequestFire = dataRequestFire && !requestBuffer.isWrite

  // The accepted cache request carries its LQ owner through cached and uncached
  // response paths. Keep ROB pointer and epoch checks as the authoritative stale-
  // response guard, while avoiding a 16-way associative search before load formatting.
  val responseLoadIndex = io.dataResponse.loadQueueIndex
  val responseLoad = loads(responseLoadIndex)
  val responseLoadValid = responseLoad.valid && responseLoad.requestSent &&
    !responseLoad.completed && io.dataResponse.robPointer === responseLoad.robPointer &&
    io.dataResponse.recoveryEpoch === responseLoad.recoveryEpoch
  val responseLoadRobPointer = responseLoad.robPointer
  val responseLoadRecoveryEpoch = responseLoad.recoveryEpoch
  val responseLoadPdst = responseLoad.pdst
  val responseLoadWritesPdst = responseLoad.writesPdst
  val responseLoadVirtualAddress = responseLoad.virtualAddress
  val responseLoadSize = responseLoad.size
  val responseLoadSignExtend = responseLoad.signExtend
  val responseLoadIsLl = responseLoad.isLl
  val responseLoadPhysicalAddress = responseLoad.physicalAddress
  val responseLoadUncached = responseLoad.uncached
  val responseLoadAccepted = io.dataResponseValid && responseLoadValid
  val responseStoreValid = headStore.valid && headStore.uncached &&
    headStore.requestSent && !headStore.completed &&
    io.dataResponse.robPointer === headStore.robPointer &&
    io.dataResponse.recoveryEpoch === headStore.recoveryEpoch
  val responseStoreAccepted = io.dataResponseValid && responseStoreValid
  // A flush-retained uncached write is already irreversible but no longer owns
  // a live ROB entry. Consume its B response to release the SQ slot without
  // emitting a completion into the new recovery epoch.
  val responseStoreArchitectural = responseStoreAccepted && !headStore.committed
  val responseAccepted = responseLoadAccepted || responseStoreArchitectural

  val aguTargetAvailable = Mux(
    io.agu.isWrite,
    stores(io.agu.uop.storeQueueIndex).valid &&
      stores(io.agu.uop.storeQueueIndex).robPointer === io.agu.uop.robPointer &&
      !stores(io.agu.uop.storeQueueIndex).addressReady,
    loads(io.agu.uop.loadQueueIndex).valid &&
      loads(io.agu.uop.loadQueueIndex).robPointer === io.agu.uop.robPointer &&
      !loads(io.agu.uop.loadQueueIndex).addressReady
  )
  val storeDataTarget = stores(io.storeDataStoreQueueIndex)
  io.storeDataReady := !io.flush && storeDataTarget.valid &&
    storeDataTarget.robPointer === io.storeDataRobPointer && !storeDataTarget.dataReady
  val storeDataFire = io.storeDataValid && io.storeDataReady
  val translatedScSuccess = !io.translationResponse.cancelled &&
    !io.translationResponse.exception.valid &&
    !io.translationResponse.uncached && io.reservationValid &&
    io.reservationLineAddress === io.translationResponse.physicalAddress(
      config.xlen - 1 downto config.dataCache.offsetWidth
    ).asBits
  val translationResponseCandidate = io.translationResponse.valid && translationActive
  val translationStore = stores(translationOwnerStoreIndex)
  val translationStoreCanComplete = translationStore.dataReady ||
    (translationStore.isSc && !translatedScSuccess)
  val translationProducesCompletion = !io.translationResponse.cancelled &&
    (io.translationResponse.exception.valid ||
      (translationOwnerStore && translationStoreCanComplete &&
        (!io.translationResponse.uncached || translationStore.isSc)))
  val storeCompletionCandidate = headStore.valid && headStore.addressReady &&
    headStore.translationDone && !headStore.completed &&
    (headStore.dataReady || (headStore.isSc && !headStore.scSuccess)) &&
    (!headStore.uncached || headStore.isSc)
  // Cache responses cannot be backpressured. After those, complete the older
  // Store before a younger forwarded Load. Besides preserving age priority,
  // this keeps the deep forwarding/alias predicate out of the direct Store-to-ROB path.
  val storeCompletionFire = storeCompletionCandidate && !io.dataResponseValid
  val forwardFire = forwardCandidate && !io.dataResponseValid &&
    !storeCompletionCandidate
  val baseCompletionBusy = io.dataResponseValid || forwardCandidate ||
    storeCompletionCandidate
  io.translationResponse.ready := translationCancelPending ||
    (translationActive && (!translationProducesCompletion || !baseCompletionBusy))
  val translationResponseFire = io.translationResponse.valid && io.translationResponse.ready
  val translationCompletionFire = translationResponseFire && !io.flush &&
    translationActive && translationProducesCompletion
  val translationCompletionCandidate = translationResponseCandidate && translationProducesCompletion
  // Capture the resident payload through the registered owner tuple.  A
  // completion-producing response may be held while another completion wins;
  // Stream keeps its payload stable, so the address fields can be captured
  // idempotently without putting completion arbitration in every wide LQ CE.
  val residentLoadTranslation = io.translationResponse.valid && translationActive &&
    !io.translationResponse.cancelled && !translationOwnerStore
  val residentLoadTranslationOwner = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    residentLoadTranslationOwner(entry) := residentLoadTranslation &&
      translationOwnerLoadIndex === U(entry, config.loadQueueIndexWidth bits) &&
      loads(entry).valid && loads(entry).robPointer === translationOwnerRobPointer &&
      loads(entry).recoveryEpoch === translationOwnerRecoveryEpoch
  }
  val scheduledLoadTranslationOwner = residentLoadTranslation && scheduledLoadValid &&
    loadHead === translationOwnerLoadIndex &&
    scheduledLoadOwner.robPointer === translationOwnerRobPointer &&
    scheduledLoadOwner.recoveryEpoch === translationOwnerRecoveryEpoch
  // Misaligned accesses are rare and already terminal exceptions. Buffer that
  // completion instead of feeding the current load/translation arbitration
  // back into aguReady. This keeps an older load's forwarding cone out of the
  // store-entry write enable while preserving every exceptional completion.
  val aguExceptionCompletionValid = RegInit(False)
  val aguExceptionRobPointer = Reg(UInt(config.robPointerWidth bits))
  val aguExceptionRecoveryEpoch = Reg(UInt(config.recoveryEpochWidth bits))
  val aguExceptionPdst = Reg(UInt(config.physicalRegIndexWidth bits))
  val aguExceptionIsSc = Reg(Bool())
  val aguExceptionBadVAddr = Reg(UInt(config.xlen bits))
  val aguExceptionCompletionReady = aguExceptionCompletionValid &&
    !baseCompletionBusy && !translationCompletionCandidate
  io.aguReady := !io.flush && aguTargetAvailable &&
    (!aguMisaligned || !aguExceptionCompletionValid)
  aguFire := io.aguValid && io.aguReady
  val aguExceptionCapture = aguFire && aguMisaligned

  val generatedCompletionValid = responseAccepted ||
    (if (config.enableBankedLoadForwardCompletion) False else forwardFire) ||
    storeCompletionFire || translationCompletionFire || aguExceptionCompletionReady
  val generatedCompletion = Completion(config)
  val generatedCompletionIsLoad = Bool()
  clearCompletion(generatedCompletion)
  generatedCompletionIsLoad := False
  when(responseStoreArchitectural) {
    generatedCompletion.robPointer := headStore.robPointer
    generatedCompletion.recoveryEpoch := headStore.recoveryEpoch
    generatedCompletion.pdst := headStore.pdst
    generatedCompletion.writesPdst := headStore.writesPdst
    generatedCompletion.data := 0
    when(io.dataResponse.error) {
      generatedCompletion.exception.valid := True
      generatedCompletion.exception.ecode := U(8, 6 bits)
      generatedCompletion.exception.esubcode := U(1, 9 bits)
      generatedCompletion.exception.badVAddrValid := True
      generatedCompletion.exception.badVAddr := headStore.virtualAddress
    }
  }.elsewhen(responseLoadAccepted) {
    generatedCompletionIsLoad := True
    generatedCompletion.robPointer := responseLoadRobPointer
    generatedCompletion.recoveryEpoch := responseLoadRecoveryEpoch
    generatedCompletion.pdst := responseLoadPdst
    generatedCompletion.writesPdst := responseLoadWritesPdst
    generatedCompletion.data := formatLoad(
      io.dataResponse.data,
      responseLoadVirtualAddress,
      responseLoadSize,
      responseLoadSignExtend
    )
    when(io.dataResponse.error) {
      generatedCompletion.exception.valid := True
      generatedCompletion.exception.ecode := U(8, 6 bits)
      generatedCompletion.exception.esubcode := U(1, 9 bits)
      generatedCompletion.exception.badVAddrValid := True
      generatedCompletion.exception.badVAddr := responseLoadVirtualAddress
    }
  }.elsewhen(forwardFire) {
    if (!config.enableBankedLoadForwardCompletion) {
      generatedCompletionIsLoad := True
      generatedCompletion.robPointer := scheduledLoadOwner.robPointer
      generatedCompletion.recoveryEpoch := scheduledLoadOwner.recoveryEpoch
      generatedCompletion.pdst := scheduledLoadPayload.pdst
      generatedCompletion.writesPdst := scheduledLoadPayload.writesPdst
      generatedCompletion.data := formatLoad(
        formatStore(
          stores(forwardingId).writeData,
          stores(forwardingId).virtualAddress,
          stores(forwardingId).size
        ),
        scheduledLoadPayload.virtualAddress,
        scheduledLoadPayload.size,
        scheduledLoadPayload.signExtend
      )
    }
  }.elsewhen(storeCompletionFire) {
    generatedCompletion.robPointer := headStore.robPointer
    generatedCompletion.recoveryEpoch := headStore.recoveryEpoch
    generatedCompletion.pdst := headStore.pdst
    generatedCompletion.writesPdst := headStore.writesPdst
    generatedCompletion.data := Mux(
      headStore.isSc,
      headStore.scSuccess.asBits.resize(config.xlen),
      B(0, config.xlen bits)
    )
  }.elsewhen(translationCompletionFire) {
    val store = stores(translationOwnerStoreIndex)
    val load = loads(translationOwnerLoadIndex)
    generatedCompletionIsLoad := !translationOwnerStore
    generatedCompletion.robPointer := translationOwnerRobPointer
    generatedCompletion.recoveryEpoch := translationOwnerRecoveryEpoch
    generatedCompletion.pdst := Mux(
      translationOwnerStore,
      store.pdst,
      load.pdst
    )
    generatedCompletion.writesPdst := Mux(
      translationOwnerStore,
      store.writesPdst,
      load.writesPdst
    )
    generatedCompletion.data := Mux(
      translationOwnerStore && store.isSc,
      translatedScSuccess.asBits.resize(config.xlen),
      B(0, config.xlen bits)
    )
    generatedCompletion.exception := io.translationResponse.exception
  }.elsewhen(aguExceptionCompletionReady) {
    generatedCompletion.robPointer := aguExceptionRobPointer
    generatedCompletion.recoveryEpoch := aguExceptionRecoveryEpoch
    generatedCompletion.pdst := aguExceptionPdst
    generatedCompletion.writesPdst := aguExceptionPdst =/= 0
    generatedCompletion.data := Mux(
      aguExceptionIsSc,
      B(1, config.xlen bits),
      B(0, config.xlen bits)
    )
    generatedCompletion.exception.valid := True
    generatedCompletion.exception.ecode := U(9, 6 bits)
    generatedCompletion.exception.esubcode := U(0, 9 bits)
    generatedCompletion.exception.badVAddrValid := True
    generatedCompletion.exception.badVAddr := aguExceptionBadVAddr
    generatedCompletion.exception.tlbRefill := False
  }
  // Only LL consumes the LSQ side-effect sidecar at retirement. Keep it out of
  // the store-forwarding/completion-arbitration cone so ordinary completions do
  // not turn the entire 32-bit field into a timing-critical conditional clear.
  when(responseLoadAccepted && responseLoadIsLl) {
    generatedCompletion.sideEffectData :=
      responseLoadPhysicalAddress(31 downto 1).asBits ## responseLoadUncached.asBits
  }

  val bankedForwardCompletionValid = Bool()
  val bankedForwardCompletion = Completion(config)
  val bankedForwardWakeupEpochCurrent = Bool()
  if (config.enableBankedLoadForwardCompletion) {
    val valid = RegInit(False)
    val selectedStore = Reg(Bits(config.storeQueueEntries bits)) init (0)
    val robPointer = Reg(UInt(config.robPointerWidth bits)) init (0)
    val recoveryEpoch = Reg(UInt(config.recoveryEpochWidth bits)) init (0)
    val pdst = Reg(UInt(config.physicalRegIndexWidth bits)) init (0)
    val writesPdst = RegInit(False)
    val epochCurrent = RegInit(False)
    val dataBanks = Vec.fill(config.storeQueueEntries)(Reg(Bits(config.xlen bits)))

    selectedStore := forwardingStore
    robPointer := scheduledLoadOwner.robPointer
    recoveryEpoch := scheduledLoadOwner.recoveryEpoch
    pdst := scheduledLoadPayload.pdst
    writesPdst := scheduledLoadPayload.writesPdst
    epochCurrent := scheduledLoadOwner.recoveryEpoch === io.currentRecoveryEpoch
    for (entry <- 0 until config.storeQueueEntries) {
      dataBanks(entry) := formatLoad(
        formatStore(
          stores(entry).writeData,
          stores(entry).virtualAddress,
          stores(entry).size
        ),
        scheduledLoadPayload.virtualAddress,
        scheduledLoadPayload.size,
        scheduledLoadPayload.signExtend
      )
    }
    when(io.flush) {
      valid := False
    }.otherwise {
      valid := forwardFire
    }

    bankedForwardCompletionValid := valid
    bankedForwardCompletion.robPointer := robPointer
    bankedForwardCompletion.recoveryEpoch := recoveryEpoch
    bankedForwardCompletion.pdst := pdst
    bankedForwardCompletion.writesPdst := writesPdst
    bankedForwardCompletion.data := dataBanks(OHToUInt(selectedStore))
    bankedForwardCompletion.sideEffectData := 0
    bankedForwardCompletion.exception.valid := False
    bankedForwardCompletion.exception.ecode := 0
    bankedForwardCompletion.exception.esubcode := 0
    bankedForwardCompletion.exception.badVAddrValid := False
    bankedForwardCompletion.exception.badVAddr := 0
    bankedForwardCompletion.exception.tlbRefill := False
    bankedForwardCompletion.branchResolved := False
    bankedForwardCompletion.branchTaken := False
    bankedForwardCompletion.branchTarget := 0
    bankedForwardCompletion.branchMispredict := False
    bankedForwardWakeupEpochCurrent := epochCurrent
  } else {
    bankedForwardCompletionValid := False
    bankedForwardWakeupEpochCurrent := False
    clearCompletion(bankedForwardCompletion)
  }

  val completionValid = RegInit(False)
  val completion = Reg(Completion(config))
  val completionLoadWakeup = RegInit(False)
  val completionLoadWakeupEpochCurrent = RegInit(False)
  val translatedFastStore = translationCompletionFire && translationOwnerStore &&
    !translationStore.isSc && !io.translationResponse.cancelled &&
    !io.translationResponse.exception.valid && !io.translationResponse.uncached
  val alreadyTranslatedFastStore = storeCompletionFire && !headStore.isSc
  val fastStoreCompletionCandidate = if (config.enableFastStoreCompletion) {
    !io.flush && (translatedFastStore || alreadyTranslatedFastStore)
  } else {
    False
  }
  // Keep the direct path compact: ordinary Stores have no destination data or
  // exception payload. Any exceptional, SC, uncached, or collision case keeps
  // using the fully registered completion path below.
  val fastStoreCompletionValid = fastStoreCompletionCandidate && !completionValid &&
    !bankedForwardCompletionValid
  val fastStoreCompletionRobPointer = Mux(
    translatedFastStore,
    translationOwnerRobPointer,
    headStore.robPointer
  )
  val fastStoreCompletionRecoveryEpoch = Mux(
    translatedFastStore,
    translationOwnerRecoveryEpoch,
    headStore.recoveryEpoch
  )
  when(io.flush) {
    aguExceptionCompletionValid := False
    // Cached writes only enter this buffer after retirement and must survive a
    // redirect. An uncached write has no side effect until the hierarchy
    // accepts it, so a still-buffered request remains speculative and is
    // discarded with the ROB entry.
    when(requestBufferValid && (!requestBuffer.isWrite || requestBuffer.uncached)) {
      requestBufferValid := False
    }
    completionValid := False
    completionLoadWakeup := False
    completionLoadWakeupEpochCurrent := False
  }.otherwise {
    when(aguExceptionCompletionReady) {
      aguExceptionCompletionValid := False
    }
    when(aguExceptionCapture) {
      aguExceptionCompletionValid := True
      aguExceptionRobPointer := io.agu.uop.robPointer
      aguExceptionRecoveryEpoch := io.agu.uop.recoveryEpoch
      aguExceptionPdst := io.agu.uop.pdst
      aguExceptionIsSc := io.agu.uop.decoded.isSc
      aguExceptionBadVAddr := io.agu.virtualAddress
    }
    when(requestCapture) {
      requestBufferValid := True
      requestBuffer := requestCandidate
      requestBufferLoadIndex := loadHead
      requestBufferStoreIndex := storeHead
      // The registered buffer now owns this Load even if the cache is
      // backpressured.  Retiring it from the scheduler here avoids selecting
      // the same Load again while preserving one stable external request.
      when(!storeRequest) {
        val entry = loads(loadHead)
        when(entry.valid && entry.robPointer === scheduledLoadOwner.robPointer) {
          entry.requestSent := True
        }
      }
    }
    when(earlyCachedStoreRelease) {
      headStore.valid := False
      headStore.addressReady := False
      headStore.dataReady := False
      headStore.completed := False
      headStore.committed := False
      headStore.requestSent := False
      headStore.translationDone := False
    }
    when(dataRequestFire) {
      requestBufferValid := False
    }
    completionValid := generatedCompletionValid && !fastStoreCompletionValid
    // responseLoadAccepted and forwardFire already include live LQ identity
    // checks. Register that qualification before the wakeup network so the
    // completion-to-IQ path does not asynchronously read the LQ again.
    completionLoadWakeup := generatedCompletionValid && !fastStoreCompletionValid &&
      generatedCompletionIsLoad && !generatedCompletion.exception.valid &&
      generatedCompletion.writesPdst && generatedCompletion.pdst =/= 0
    // Qualify the epoch on the same existing LSQ completion boundary. Backend
    // broadcasts this registered Boolean instead of placing the global epoch
    // comparator on every IQ select path.
    completionLoadWakeupEpochCurrent :=
      generatedCompletion.recoveryEpoch === io.currentRecoveryEpoch
    // Validity, not payload clock-enables, defines whether this register is
    // observable. Sampling every cycle prevents the deep forwarding predicate
    // from being replicated onto every completion payload register.
    completion := generatedCompletion
  }
  io.completionValid := completionValid || bankedForwardCompletionValid
  io.completion := completion
  when(bankedForwardCompletionValid) {
    io.completion := bankedForwardCompletion
  }
  io.storeCompletionBypassValid := fastStoreCompletionValid
  io.storeCompletionBypass.robPointer := fastStoreCompletionRobPointer
  io.storeCompletionBypass.recoveryEpoch := fastStoreCompletionRecoveryEpoch
  io.loadWakeupValid := (completionValid && completionLoadWakeup) ||
    (bankedForwardCompletionValid && bankedForwardCompletion.writesPdst &&
      bankedForwardCompletion.pdst =/= 0)
  io.loadWakeupPdst := completion.pdst
  io.loadWakeupRecoveryEpoch := completion.recoveryEpoch
  io.loadWakeupEpochCurrent := completionLoadWakeupEpochCurrent
  when(bankedForwardCompletionValid) {
    io.loadWakeupPdst := bankedForwardCompletion.pdst
    io.loadWakeupRecoveryEpoch := bankedForwardCompletion.recoveryEpoch
    io.loadWakeupEpochCurrent := bankedForwardWakeupEpochCurrent
  }

  loadReleaseValid := B(0, config.commitWidth bits)
  storeReleaseValid := B(0, config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    val observation = io.commitObservation(lane)
    val loadEntry = loads(io.commit(lane).loadQueueIndex)
    val storeEntry = stores(io.commit(lane).storeQueueIndex)
    val loadCommitMatch = io.commitValid(lane) && io.commit(lane).isLoad &&
      loadEntry.valid && loadEntry.robPointer === io.commit(lane).robPointer
    val storeCommitMatch = io.commitValid(lane) && io.commit(lane).isStore &&
      storeEntry.valid && storeEntry.robPointer === io.commit(lane).robPointer
    loadReleaseValid(lane) := !io.flush && loadCommitMatch

    observation.loadInstructionMask := B(0, 8 bits)
    observation.storeInstructionMask := B(0, 8 bits)
    observation.physicalAddress := U(0, config.xlen bits)
    observation.virtualAddress := U(0, config.xlen bits)
    observation.storeData := B(0, config.xlen bits)
    observation.storeByteMask := B(0, config.xlen / 8 bits)

    // A redirect may flush younger work in the same cycle that older instructions retire.
    // Those retiring memory operations remain architectural and must still be observed.
    val observationValid = io.commit(lane).retired && !io.commit(lane).exception.valid
    when(observationValid && loadCommitMatch) {
      observation.physicalAddress := loadEntry.physicalAddress
      observation.virtualAddress := loadEntry.virtualAddress
      when(loadEntry.isLl) {
        observation.loadInstructionMask(5) := True
      }.elsewhen(loadEntry.size === B(2, 3 bits)) {
        observation.loadInstructionMask(4) := True
      }.elsewhen(loadEntry.size === B(1, 3 bits)) {
        when(loadEntry.signExtend) {
          observation.loadInstructionMask(2) := True
        }.otherwise {
          observation.loadInstructionMask(3) := True
        }
      }.otherwise {
        when(loadEntry.signExtend) {
          observation.loadInstructionMask(0) := True
        }.otherwise {
          observation.loadInstructionMask(1) := True
        }
      }
    }
    when(observationValid && storeCommitMatch) {
      observation.physicalAddress := storeEntry.physicalAddress
      observation.virtualAddress := storeEntry.virtualAddress
      observation.storeData := formatStore(
        storeEntry.writeData,
        storeEntry.virtualAddress,
        storeEntry.size
      )
      observation.storeByteMask := storeEntry.byteMask
      when(storeEntry.isSc) {
        observation.storeInstructionMask(3) := storeEntry.scSuccess
      }.elsewhen(storeEntry.size === B(2, 3 bits)) {
        observation.storeInstructionMask(2) := True
      }.elsewhen(storeEntry.size === B(1, 3 bits)) {
        observation.storeInstructionMask(1) := True
      }.otherwise {
        observation.storeInstructionMask(0) := True
      }
    }
  }
  val failedScReleaseFire = failedScRelease
  val uncachedStoreRelease = headStore.valid && headStore.uncached &&
    headStore.requestSent && headStore.completed && headStore.committed
  storeReleaseValid(0) := !io.flush &&
    (earlyCachedStoreRelease || failedScReleaseFire || uncachedStoreRelease)
  io.releaseLoadValid := loadReleaseValid
  io.releaseStoreValid := storeReleaseValid

  when(io.flush) {
    // Preserve a cancellation token until the translator's outstanding
    // response is consumed. A response consumed on the flush edge itself does
    // not need a token.
    translationCancelPending :=
      (translationActive || translationCancelPending) && !translationResponseFire
    translationActive := False
    // Retired stores are architectural state.  Preserve the committed prefix
    // across recovery and drain it before admitting the new speculative epoch.
    drainAfterFlush := committedStorePresent
    when(!committedStorePresent) {
      storeHead := 0
    }
    for (entry <- stores) {
      val irreversibleUncachedWrite = entry.uncached && entry.requestSent
      when(irreversibleUncachedWrite) {
        entry.committed := True
        when(
          responseStoreAccepted &&
            entry.robPointer === io.dataResponse.robPointer &&
            entry.recoveryEpoch === io.dataResponse.recoveryEpoch
        ) {
          entry.completed := True
        }
      }
      when(!entry.committed && !irreversibleUncachedWrite) {
        entry.valid := False
        entry.addressReady := False
        entry.dataReady := False
        entry.completed := False
        entry.committed := False
        entry.requestSent := False
        entry.translationDone := False
      }
    }
    for (entry <- loads) {
      entry.valid := False
      entry.addressReady := False
      entry.requestSent := False
      entry.completed := False
      entry.translationDone := False
    }
  }.otherwise {
    when(translationCancelPending && translationResponseFire) {
      translationCancelPending := False
    }
    when(drainAfterFlush && !committedStorePresent) {
      drainAfterFlush := False
      storeHead := 0
    }
    for (lane <- 0 until config.renameWidth) {
      for (index <- 0 until config.storeQueueEntries) {
        when(storeAllocationTargets(lane)(index)) {
          val allocation = storeAllocationBankPayload(lane)(index % allocationBankCount)
          stores(index).valid := True
          stores(index).addressReady := False
          stores(index).dataReady := False
          stores(index).completed := False
          stores(index).committed := False
          stores(index).requestSent := False
          stores(index).translationDone := False
          stores(index).scSuccess := False
          stores(index).robPointer := allocation.robPointer
          stores(index).recoveryEpoch := allocation.recoveryEpoch
          stores(index).memoryEpoch := allocation.memoryEpoch
        }
      }
      for (index <- 0 until config.loadQueueEntries) {
        when(loadAllocationTargets(lane)(index)) {
          val allocation = loadAllocationBankPayload(lane)(index % allocationBankCount)
          loads(index).valid := True
          loads(index).addressReady := False
          loads(index).requestSent := False
          loads(index).completed := False
          loads(index).translationDone := False
          loads(index).robPointer := allocation.robPointer
          loads(index).recoveryEpoch := allocation.recoveryEpoch
          loads(index).memoryEpoch := allocation.memoryEpoch
        }
      }
    }

    when(aguFire && io.agu.isWrite) {
      val index = io.agu.uop.storeQueueIndex
      stores(index).virtualAddress := io.agu.virtualAddress
      stores(index).pdst := io.agu.uop.pdst
      stores(index).writesPdst := io.agu.uop.pdst =/= 0
      stores(index).isSc := io.agu.uop.decoded.isSc
      stores(index).size := io.agu.size
      stores(index).byteMask := io.agu.byteMask
      stores(index).addressReady := !aguMisaligned
      stores(index).physicalAddress := Mux(
        aguTranslationBypass,
        io.translationBypass.physicalAddress,
        U(0, config.xlen bits)
      )
      stores(index).translationDone := aguTranslationBypass
      stores(index).uncached := aguTranslationBypass && io.translationBypass.uncached
      stores(index).scSuccess := io.agu.uop.decoded.isSc && aguTranslationBypass &&
        aguBypassScSuccess
    }
    when(storeDataFire) {
      val index = io.storeDataStoreQueueIndex
      stores(index).writeData := io.storeData
      stores(index).dataReady := True
    }
    when(aguFire && !io.agu.isWrite) {
      val index = io.agu.uop.loadQueueIndex
      loads(index).pdst := io.agu.uop.pdst
      loads(index).writesPdst := io.agu.uop.pdst =/= 0
      loads(index).virtualAddress := io.agu.virtualAddress
      loads(index).size := io.agu.size
      loads(index).byteMask := io.agu.byteMask
      loads(index).signExtend := io.agu.uop.decoded.memorySignExtend
      loads(index).isLl := io.agu.uop.decoded.isLl
      loads(index).addressReady := !aguMisaligned
      loads(index).completed := aguMisaligned
      loads(index).physicalAddress := Mux(
        aguTranslationBypass,
        io.translationBypass.physicalAddress,
        U(0, config.xlen bits)
      )
      loads(index).translationDone := aguTranslationBypass
      loads(index).uncached := aguTranslationBypass && io.translationBypass.uncached
    }

    for (index <- 0 until config.loadQueueEntries) {
      when(residentLoadTranslationOwner(index)) {
        loads(index).physicalAddress := io.translationResponse.physicalAddress
        loads(index).uncached := io.translationResponse.uncached
        when(translationResponseFire) {
          loads(index).translationDone := True
          when(io.translationResponse.exception.valid) { loads(index).completed := True }
        }
      }
    }
    when(scheduledLoadTranslationOwner) {
      scheduledLoadTranslation.physicalAddress := io.translationResponse.physicalAddress
      scheduledLoadTranslation.uncached := io.translationResponse.uncached
      when(translationResponseFire) { scheduledLoadTranslation.done := True }
    }

    when(translationResponseFire && translationActive) {
      translationActive := False
      when(!io.translationResponse.cancelled && translationOwnerStore) {
        val entry = stores(translationOwnerStoreIndex)
        when(
          entry.valid && entry.robPointer === translationOwnerRobPointer &&
            entry.recoveryEpoch === translationOwnerRecoveryEpoch
        ) {
          entry.physicalAddress := io.translationResponse.physicalAddress
          entry.uncached := io.translationResponse.uncached
          entry.translationDone := True
          when(entry.isSc) { entry.scSuccess := translatedScSuccess }
          when(translationCompletionFire) { entry.completed := True }
        }
      }
    }
    when(storeCompletionFire) {
      stores(storeHead).completed := True
    }

    for (lane <- 0 until config.commitWidth) {
      when(io.releaseLoadValid(lane)) {
        loads(io.commit(lane).loadQueueIndex).valid := False
      }
      when(
        io.commitValid(lane) && io.commit(lane).isStore &&
          !io.commit(lane).exception.valid &&
          stores(io.commit(lane).storeQueueIndex).valid &&
          stores(io.commit(lane).storeQueueIndex).robPointer === io.commit(lane).robPointer
      ) {
        stores(io.commit(lane).storeQueueIndex).committed := True
      }
    }

    when(responseLoadAccepted) {
      loads(responseLoadIndex).completed := True
    }
    when(responseStoreAccepted) {
      stores(storeHead).completed := True
    }
    when(forwardFire) {
      loads(loadHead).completed := True
    }
    when(failedScReleaseFire) {
      stores(storeHead).valid := False
      stores(storeHead).addressReady := False
      stores(storeHead).dataReady := False
      stores(storeHead).completed := False
      stores(storeHead).committed := False
      stores(storeHead).requestSent := False
      stores(storeHead).translationDone := False
    }
    val uncachedStoreRequestFire = storeRequestFire && requestBuffer.uncached
    when(uncachedStoreRequestFire) {
      stores(requestBufferStoreIndex).requestSent := True
    }
    when(uncachedStoreRelease) {
      stores(storeHead).valid := False
      stores(storeHead).addressReady := False
      stores(storeHead).dataReady := False
      stores(storeHead).completed := False
      stores(storeHead).committed := False
      stores(storeHead).requestSent := False
      stores(storeHead).translationDone := False
    }
    when(earlyCachedStoreRelease || failedScReleaseFire ||
      uncachedStoreRelease) {
      storeHead := storeHead + 1
    }
  }

  val perfObservationV1Word5 = Bits(PerfObservationV1.WordWidth bits)
  perfObservationV1Word5 := 0
  val observationLoadsValid = Bits(config.loadQueueEntries bits)
  val observationStoresValid = Bits(config.storeQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    observationLoadsValid(entry) := loads(entry).valid
  }
  for (entry <- 0 until config.storeQueueEntries) {
    observationStoresValid(entry) := stores(entry).valid
  }
  perfObservationV1Word5(4 downto 0) := CountOne(observationLoadsValid).resize(5).asBits
  perfObservationV1Word5(8 downto 5) := CountOne(observationStoresValid).resize(4).asBits
  perfObservationV1Word5(9) := scheduledLoadValid
  perfObservationV1Word5(10) := translationActive
  perfObservationV1Word5(11) := translationCancelPending
  perfObservationV1Word5(12) := io.translationRequest.valid
  perfObservationV1Word5(13) := io.translationRequest.ready
  perfObservationV1Word5(14) := translationRequestFire
  perfObservationV1Word5(15) := io.translationResponse.valid
  perfObservationV1Word5(16) := io.translationResponse.ready
  perfObservationV1Word5(17) := translationResponseFire
  perfObservationV1Word5(18) := io.dataRequestValid
  perfObservationV1Word5(19) := io.dataRequestReady
  perfObservationV1Word5(20) := dataRequestFire
  perfObservationV1Word5(21) := io.dataRequest.isWrite
  perfObservationV1Word5(22) := io.dataRequest.uncached
  perfObservationV1Word5(23) := io.dataResponseValid
  perfObservationV1Word5(24) := loadRequestFire
  perfObservationV1Word5(25) := storeRequestFire
  perfObservationV1Word5(26) := forwardFire
  perfObservationV1Word5(27) := storeCompletionFire
  perfObservationV1Word5(28) := translationCompletionFire
  perfObservationV1Word5(29) := io.completionValid
  perfObservationV1Word5(30) := io.loadWakeupValid
  perfObservationV1Word5(31) := storeDataFire
  perfObservationV1Word5(32) := aguFire
  perfObservationV1Word5(33) := io.olderStorePending
  perfObservationV1Word5(34) := io.storeDrainBusy
  perfObservationV1Word5(35) := requestBufferValid
  perfObservationV1Word5(36) := storeRequestFire && !requestBuffer.uncached
  perfObservationV1Word5(37) := loadHeadReady
  perfObservationV1Word5(38) := loadNeedsTranslation
  perfObservationV1Word5(39) := loadHeadReady && unknownOlderStore.orR
  perfObservationV1Word5(40) := loadHeadReady && olderUncachedStore.orR
  perfObservationV1Word5(41) := loadHeadReady && olderLoadOrderBlock.orR
  perfObservationV1Word5(42) := loadHeadReady && partialOverlapStore.orR
  perfObservationV1Word5(43) := loadHeadReady && pendingDataStore.orR
  perfObservationV1Word5(44) := forwardCandidate
  perfObservationV1Word5(45) := cacheLoadCandidate
  perfObservationV1Word5(46) := requestCapture && !storeRequest
  perfObservationV1Word5(47) := cacheLoadCandidate && requestBufferValid
  perfObservationV1Word5(48) := cacheLoadCandidate && storeRequest
  val rawLoadCompletion = responseLoadAccepted || forwardFire ||
    (translationCompletionFire && !translationOwnerStore)
  val rawOrdinaryLoadCompletion =
    (responseLoadAccepted && !io.dataResponse.error && !responseLoadUncached && !responseLoadIsLl) ||
      forwardFire
  val rawOrdinaryLoadCompletionRobPointer = Mux(
    responseLoadAccepted,
    responseLoadRobPointer,
    scheduledLoadOwner.robPointer
  )
  val alternatePendingLoadAddressReady = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    alternatePendingLoadAddressReady(entry) := pendingLoads(entry) &&
      U(entry, config.loadQueueIndexWidth bits) =/= loadHead && loads(entry).addressReady
  }
  val oldestLoadAddressNotReady = scheduledLoadValid && headLoadState.valid &&
    headLoadState.robPointer === scheduledLoadOwner.robPointer && !headLoadState.addressReady
  val oldestLoadOrderBlocked = loadHeadReady && !loadOrderClear
  val multipleForwardingStoresBlock = loadHeadReady && scheduledLoadTranslation.done &&
    !scheduledLoadTranslation.uncached && !scheduledLoadPayload.isLl && forwardingCount > 1
  val oldestLoadLocalAliasBlocked = loadHeadReady && scheduledLoadTranslation.done &&
    !scheduledLoadTranslation.uncached && !scheduledLoadPayload.isLl && !bufferedCommittedStore &&
    !unknownOlderStore.orR && !olderUncachedStore.orR && !olderLoadOrderBlock.orR &&
    (partialOverlapStore.orR || pendingDataStore.orR || forwardingCount > 1)
  when(io.flush) {
    tryYoungerLoad := False
  }.elsewhen(tryYoungerLoad) {
    // A token is a single attempt even if the candidate disappears before use.
    tryYoungerLoad := False
  }.otherwise {
    val armYoungerRetry = oldestLoadLocalAliasBlocked && !scheduledLoadWasYoungerBypass &&
      youngerReadyLoads.orR
    tryYoungerLoad := armYoungerRetry
    when(armYoungerRetry) {
      // Terminate the rotated ready-map and priority select at a narrow state
      // boundary. The following cycle reads the wide Load payload through this
      // stable index, keeping the selection network out of every payload D path.
      youngerRetryIndex := selectedYoungerLoadHead
    }
  }
  perfObservationV1Word5(49) := rawLoadCompletion
  perfObservationV1Word5(50) := rawOrdinaryLoadCompletion
  perfObservationV1Word5(51) := rawOrdinaryLoadCompletion &&
    rawOrdinaryLoadCompletionRobPointer === io.robHeadPointer
  perfObservationV1Word5(52) := oldestLoadAddressNotReady
  perfObservationV1Word5(53) := alternatePendingLoadAddressReady.orR
  perfObservationV1Word5(54) :=
    (oldestLoadAddressNotReady || oldestLoadOrderBlocked) &&
      alternatePendingLoadAddressReady.orR
  // Emit configured-capacity and progressively qualified bypass observations
  // from the DUT so the external monitor never guesses microarchitectural sizes.
  perfObservationV1Word5(55) := CountOne(observationLoadsValid) === config.loadQueueEntries
  perfObservationV1Word5(56) := CountOne(observationStoresValid) === config.storeQueueEntries
  perfObservationV1Word5(57) := oldestLoadAddressNotReady &&
    alternatePendingLoadAddressReady.orR
  perfObservationV1Word5(58) := oldestLoadOrderBlocked &&
    alternatePendingLoadAddressReady.orR
  perfObservationV1Word5(59) := oldestLoadLocalAliasBlocked &&
    alternatePendingLoadAddressReady.orR
  perfObservationV1Word5(60) := multipleForwardingStoresBlock
  perfObservationV1Word5(61) := multipleForwardingStoresBlock &&
    alternatePendingLoadAddressReady.orR
  // Encode log2(capacity) - 3 so the observation harness follows the elaborated DUT.
  // Codes 0..3 represent 8, 16, 32 and 64 entries respectively.
  require(
    config.loadQueueEntries >= 8 && config.loadQueueEntries <= 64,
    "PerfObservationV1 supports load queues from 8 through 64 entries"
  )
  perfObservationV1Word5(63 downto 62) :=
    B(log2Up(config.loadQueueEntries) - 3, 2 bits)
  PerfObservationV1.expose(perfObservationV1Word5, 5)
}
