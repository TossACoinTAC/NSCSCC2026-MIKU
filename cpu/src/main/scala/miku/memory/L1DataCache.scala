package miku.memory

import miku.core._
import spinal.core._
import spinal.lib._

object L1DataCacheState extends SpinalEnum {
  val normal, maintenanceHitLookup, maintenanceLookup, maintenanceWriteback,
    maintenanceWritebackWait, maintenanceInvalidate = newElement()
}

/** Nonblocking two-way L1 data cache with four miss-status entries.
  *
  * Requests to an in-flight line merge into its MSHR. Requests to a different line in the same set
  * wait until installation so two misses can never select the same physical cache location. All
  * other sets remain available for hit lookup while writeback and refill traffic progress below L1.
  */
final class L1DataCache(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val geometry = config.dataCache
  private val wayWidth = log2Up(geometry.ways)
  private val indexWidth = geometry.indexWidth
  private val offsetWidth = geometry.offsetWidth
  private val mshrIdWidth = log2Up(config.mshrEntries)
  private val waiterIndexWidth = log2Up(config.loadQueueEntries)

  require(geometry.lineBytes == CacheContract.LineBytes)
  require(CacheContract.BeatsPerLine == 8)
  require(config.loadQueueEntries >= config.mshrEntries)

  private def lineAddress(address: UInt): UInt =
    address & U(((BigInt(1) << config.xlen) - 1) ^ (geometry.lineBytes - 1), config.xlen bits)

  private def indexOf(address: UInt): UInt =
    address(offsetWidth + indexWidth - 1 downto offsetWidth)

  private def tagOf(address: UInt): UInt =
    address(config.xlen - 1 downto offsetWidth + indexWidth)

  private def wordShift(address: UInt): UInt =
    (address(offsetWidth - 1 downto 2) ## U(0, 5 bits)).asUInt

  private def selectWord(line: Bits, address: UInt): Bits =
    (line |>> wordShift(address))(config.xlen - 1 downto 0)

  private def refillBeatIndex(address: UInt): UInt =
    address(offsetWidth - 1 downto 3)

  private def selectBeatWord(beat: Bits, address: UInt): Bits =
    Mux(address(2), beat(63 downto 32), beat(31 downto 0))

  private def storeBitMask(address: UInt, byteMask: Bits): Bits = {
    val wordMask = Bits(config.xlen bits)
    for (byte <- 0 until config.xlen / 8) {
      wordMask(byte * 8 + 7 downto byte * 8) := B(0xff, 8 bits).andMask(byteMask(byte))
    }
    wordMask.resize(CacheContract.LineBits) |<< wordShift(address)
  }

  private def storeLineData(address: UInt, writeData: Bits): Bits =
    writeData.resize(CacheContract.LineBits) |<< wordShift(address)

  private def lineWordByteShift(address: UInt): UInt =
    (address(offsetWidth - 1 downto 2) ## U(0, 2 bits)).asUInt

  private def storeLineByteMask(address: UInt, byteMask: Bits): Bits =
    // byteMask is already aligned within its 32-bit word by the LSQ.
    byteMask.resize(CacheContract.LineBytes) |<< lineWordByteShift(address)

  private def storeBeatIndex(address: UInt): UInt =
    address(offsetWidth - 1 downto 3)

  private def beatWordShift(address: UInt): UInt =
    (address(2 downto 2) ## U(0, 5 bits)).asUInt

  private def beatWordByteShift(address: UInt): UInt =
    (address(2 downto 2) ## U(0, 2 bits)).asUInt

  private def storeBeatBitMask(address: UInt, byteMask: Bits): Bits = {
    val wordMask = Bits(config.xlen bits)
    for (byte <- 0 until config.xlen / 8) {
      wordMask(byte * 8 + 7 downto byte * 8) := B(0xff, 8 bits).andMask(byteMask(byte))
    }
    wordMask.resize(CacheContract.BeatBits) |<< beatWordShift(address)
  }

  private def storeBeatData(address: UInt, writeData: Bits): Bits =
    writeData.resize(CacheContract.BeatBits) |<< beatWordShift(address)

  private def mergeStore(line: Bits, address: UInt, byteMask: Bits, writeData: Bits): Bits = {
    val mask = storeBitMask(address, byteMask)
    val data = storeLineData(address, writeData)
    (line & ~mask) | (data & mask)
  }

  private def selectLowest(mask: Bits, count: Int): UInt = {
    val selected = UInt(log2Up(count) bits)
    selected := 0
    for (index <- (0 until count).reverse) {
      when(mask(index)) { selected := U(index, log2Up(count) bits) }
    }
    selected
  }

  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(CacheRequest(config))
    val requestReady = out Bool ()
    val responseValid = out Bool ()
    val response = out(CacheResponse(config))

    val lineReadValid = out Bool ()
    val lineRead = out(LineReadRequest(config))
    val lineReadReady = in Bool ()
    val lineReadBeatValid = in Bool ()
    val lineReadBeat = in(LineReadBeat(config))
    val lineReadBeatReady = out Bool ()

    val lineWriteValid = out Bool ()
    val lineWrite = out(LineWriteRequest(config))
    val lineWriteReady = in Bool ()
    val lineWriteResponseValid = in Bool ()
    val lineWriteResponse = in(LineWriteResponse(config))

    val invalidate = in Bool ()
    val writebackInvalidate = in Bool ()
    val maintenanceRequest = slave(Stream(CacheMaintenanceRequest(config)))
    val maintenanceDone = out Bool ()
    val invalidateBusy = out Bool ()
    val idle = out Bool ()
  }

  val cacheArray = new CacheArray(geometry)
  val state = RegInit(L1DataCacheState.normal)
  val misses = Vec.fill(config.mshrEntries)(Reg(L1DataMshr(config)))
  val waiters = Vec.fill(config.loadQueueEntries)(Reg(L1DataMshrWaiter(config)))
  val waiterBeatReady = Vec.fill(config.loadQueueEntries)(RegInit(False))
  val refillMemories = Array.fill(CacheContract.BeatsPerLine)(
    Mem(Bits(CacheContract.BeatBits bits), config.mshrEntries)
  )
  val pendingStoreValid = RegInit(False)
  val pendingStoreMshrId = Reg(UInt(mshrIdWidth bits))
  val pendingStoreAddress = Reg(UInt(offsetWidth bits))
  val pendingStoreByteMask = Reg(Bits(4 bits))
  val pendingStoreData = Reg(Bits(config.xlen bits))
  val missVictimData = Reg(Bits(CacheContract.LineBits bits))
  for (entry <- misses) {
    entry.valid.init(False)
    entry.state.init(L1DataMshrState.readRequest)
    entry.readRequestPending.init(False)
  }
  for (waiter <- waiters) { waiter.valid.init(False) }

  val lookupPending = RegInit(False)
  val lookupRequest = Reg(L1DataLookupRequest(config))
  val lookupMshrId = Reg(UInt(mshrIdWidth bits))
  val lookupWaiterId = Reg(UInt(waiterIndexWidth bits))

  val responseValid = RegInit(False)
  val response = Reg(CacheResponse(config))
  responseValid := False
  io.responseValid := responseValid
  io.response := response

  val invalidateSeen = RegInit(False)
  val invalidatePending = RegInit(False)
  val writebackInvalidateSeen = RegInit(False)
  val writebackInvalidatePending = RegInit(False)
  val maintenanceIndex = Reg(UInt(indexWidth bits)) init (0)
  val maintenanceWay = Reg(UInt(wayWidth bits)) init (0)
  val maintenanceVictimAddress = Reg(UInt(config.xlen bits))
  val maintenanceVictimData = Reg(Bits(CacheContract.LineBits bits))
  val maintenanceMode = Reg(UInt(2 bits)) init (0)
  val exactMaintenance = RegInit(False)
  val maintenanceDone = RegInit(False)
  maintenanceDone := False
  io.maintenanceDone := maintenanceDone

  val newInvalidate = io.invalidate && !invalidateSeen
  when(io.invalidate) { invalidateSeen := True }.otherwise { invalidateSeen := False }
  when(newInvalidate) { invalidatePending := True }
  val newWritebackInvalidate = io.writebackInvalidate && !writebackInvalidateSeen
  when(io.writebackInvalidate) { writebackInvalidateSeen := True }
    .otherwise { writebackInvalidateSeen := False }
  when(newWritebackInvalidate) { writebackInvalidatePending := True }

  val activeMissMask = Bits(config.mshrEntries bits)
  val freeMissMask = Bits(config.mshrEntries bits)
  val activeWritebackMask = Bits(config.mshrEntries bits)
  val activeWritebackWaitMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    activeMissMask(entry) := misses(entry).valid
    freeMissMask(entry) := !misses(entry).valid
    activeWritebackMask(entry) := misses(entry).valid &&
      misses(entry).state === L1DataMshrState.writeback
    activeWritebackWaitMask(entry) := misses(entry).valid &&
      misses(entry).state === L1DataMshrState.writebackWait
  }
  val activeWaiterMask = Bits(config.loadQueueEntries bits)
  val freeWaiterMask = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    activeWaiterMask(entry) := waiters(entry).valid
    freeWaiterMask(entry) := !waiters(entry).valid
  }
  val normalBusy = lookupPending || activeMissMask.orR || activeWaiterMask.orR ||
    pendingStoreValid
  val maintenanceRequest = invalidatePending || newInvalidate ||
    writebackInvalidatePending || newWritebackInvalidate
  val startInvalidate = (invalidatePending || newInvalidate) && !normalBusy &&
    state === L1DataCacheState.normal && !cacheArray.io.invalidateBusy &&
    !io.maintenanceRequest.valid
  val startWritebackInvalidate = (writebackInvalidatePending || newWritebackInvalidate) &&
    !normalBusy && state === L1DataCacheState.normal &&
    !(invalidatePending || newInvalidate) && !cacheArray.io.invalidateBusy &&
    !io.maintenanceRequest.valid
  when(startInvalidate) { invalidatePending := False }
  when(startWritebackInvalidate) {
    writebackInvalidatePending := False
    exactMaintenance := False
    maintenanceMode := CacheMaintenanceMode.index
    maintenanceIndex := 0
    maintenanceWay := 0
    state := L1DataCacheState.maintenanceLookup
  }

  val requestLineAddress = lineAddress(io.request.physicalAddress)
  val lineMatchMask = Bits(config.mshrEntries bits)
  val setConflictMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    lineMatchMask(entry) := misses(entry).valid &&
      misses(entry).lineAddress === requestLineAddress
    setConflictMask(entry) := misses(entry).valid &&
      indexOf(misses(entry).lineAddress) === indexOf(requestLineAddress)
  }
  val lineMatch = lineMatchMask.orR
  val lineMatchId = selectLowest(lineMatchMask, config.mshrEntries)
  val freeMissId = selectLowest(freeMissMask, config.mshrEntries)
  val freeWaiterId = selectLowest(freeWaiterMask, config.loadQueueEntries)

  val installMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    installMask(entry) := misses(entry).valid &&
      misses(entry).state === L1DataMshrState.install
  }
  val lookupResponse = lookupPending && cacheArray.io.responseValid
  val lookupHitLoad = lookupResponse && cacheArray.io.hit && !lookupRequest.isWrite
  val refillId = io.lineReadBeat.mshrId
  val refillBaseReady = state === L1DataCacheState.normal &&
    misses(refillId).valid && misses(refillId).state === L1DataMshrState.refill
  val pendingStoreApply = pendingStoreValid && state === L1DataCacheState.normal &&
    misses(pendingStoreMshrId).valid &&
    misses(pendingStoreMshrId).state =/= L1DataMshrState.install &&
    misses(pendingStoreMshrId).state =/= L1DataMshrState.respond
  val pendingStoreReady = !pendingStoreValid || pendingStoreApply

  val commonRequestReady = state === L1DataCacheState.normal && !maintenanceRequest &&
    !cacheArray.io.invalidateBusy && !lookupPending && !installMask.orR && !io.request.uncached &&
    !io.maintenanceRequest.valid
  val mergeLoadReady = lineMatch && !io.request.isWrite && freeWaiterMask.orR
  val mergeStoreReady = lineMatch && io.request.isWrite &&
    misses(lineMatchId).state =/= L1DataMshrState.install &&
    misses(lineMatchId).state =/= L1DataMshrState.respond &&
    pendingStoreReady
  val newLookupReady = !lineMatch && !setConflictMask.orR && freeMissMask.orR &&
    !(activeWritebackMask | activeWritebackWaitMask).orR &&
    (io.request.isWrite || freeWaiterMask.orR) &&
    (!io.request.isWrite || pendingStoreReady) && cacheArray.io.lookupReady
  io.requestReady := commonRequestReady &&
    (mergeLoadReady || mergeStoreReady || newLookupReady)
  val requestFire = io.requestValid && io.requestReady
  val mergeLoadFire = requestFire && mergeLoadReady
  val mergeStoreFire = requestFire && mergeStoreReady
  val newLookupFire = requestFire && newLookupReady
  io.maintenanceRequest.ready := state === L1DataCacheState.normal &&
    !normalBusy && !maintenanceRequest && !cacheArray.io.invalidateBusy &&
    cacheArray.io.lookupReady
  val exactMaintenanceFire = io.maintenanceRequest.valid &&
    io.maintenanceRequest.ready

  cacheArray.io.lookupValid := newLookupFire
  cacheArray.io.lookupAddress := io.request.physicalAddress
  cacheArray.io.writeValid := False
  cacheArray.io.writeIndex := 0
  cacheArray.io.writeWay := 0
  cacheArray.io.writeTag := 0
  cacheArray.io.writeData := 0
  cacheArray.io.writeEntryValid := True
  cacheArray.io.writeDirty := False
  cacheArray.io.invalidate := startInvalidate
  cacheArray.io.maintenanceReadValid := state === L1DataCacheState.maintenanceLookup
  cacheArray.io.maintenanceReadIndex := maintenanceIndex
  cacheArray.io.maintenanceReadWay := maintenanceWay

  when(exactMaintenanceFire) {
    exactMaintenance := True
    maintenanceMode := io.maintenanceRequest.code(4 downto 3).asUInt
    maintenanceIndex := indexOf(io.maintenanceRequest.virtualAddress)
    maintenanceWay := io.maintenanceRequest.virtualAddress(wayWidth - 1 downto 0)
    when(
      io.maintenanceRequest.code(4 downto 3).asUInt ===
        CacheMaintenanceMode.hit
    ) {
      maintenanceIndex := indexOf(io.maintenanceRequest.physicalAddress)
      cacheArray.io.lookupValid := True
      cacheArray.io.lookupAddress := io.maintenanceRequest.physicalAddress
      state := L1DataCacheState.maintenanceHitLookup
    }.otherwise {
      state := L1DataCacheState.maintenanceLookup
    }
  }

  when(newLookupFire) {
    lookupPending := True
    lookupRequest.physicalAddress := io.request.physicalAddress
    lookupRequest.isWrite := io.request.isWrite
    lookupRequest.byteMask := io.request.byteMask
    lookupRequest.writeData := io.request.writeData
    lookupRequest.robPointer := io.request.robPointer
    lookupRequest.recoveryEpoch := io.request.recoveryEpoch
    lookupRequest.pdst := io.request.pdst
    lookupRequest.loadQueueIndex := io.request.loadQueueIndex
    lookupMshrId := freeMissId
    lookupWaiterId := freeWaiterId
  }
  when(mergeLoadFire) {
    waiters(freeWaiterId).valid := True
    waiters(freeWaiterId).mshrId := lineMatchId
    waiters(freeWaiterId).physicalAddress := io.request.physicalAddress
    waiters(freeWaiterId).robPointer := io.request.robPointer
    waiters(freeWaiterId).recoveryEpoch := io.request.recoveryEpoch
    waiters(freeWaiterId).pdst := io.request.pdst
    waiters(freeWaiterId).loadQueueIndex := io.request.loadQueueIndex
    waiterBeatReady(freeWaiterId) :=
      misses(lineMatchId).refillMask(refillBeatIndex(io.request.physicalAddress)) ||
        (io.lineReadBeatValid && io.lineReadBeatReady && refillId === lineMatchId &&
          io.lineReadBeat.beat === refillBeatIndex(io.request.physicalAddress))
  }
  when(pendingStoreApply) {
    misses(pendingStoreMshrId).storeByteMask :=
      misses(pendingStoreMshrId).storeByteMask |
        storeLineByteMask(pendingStoreAddress, pendingStoreByteMask)
    when(
      misses(pendingStoreMshrId).state === L1DataMshrState.refill &&
        misses(pendingStoreMshrId).refillMask.andR &&
        !(mergeStoreFire && lineMatchId === pendingStoreMshrId)
    ) {
      misses(pendingStoreMshrId).state := L1DataMshrState.install
    }
    pendingStoreValid := False
  }
  when(mergeStoreFire) {
    pendingStoreValid := True
    pendingStoreMshrId := lineMatchId
    pendingStoreAddress := io.request.physicalAddress(offsetWidth - 1 downto 0)
    pendingStoreByteMask := io.request.byteMask
    pendingStoreData := io.request.writeData
  }

  when(lookupResponse) {
    lookupPending := False
    when(cacheArray.io.hit) {
      when(lookupRequest.isWrite) {
        cacheArray.io.writeValid := True
        cacheArray.io.writeIndex := indexOf(lookupRequest.physicalAddress)
        cacheArray.io.writeWay := cacheArray.io.hitWay
        cacheArray.io.writeTag := tagOf(lookupRequest.physicalAddress)
        cacheArray.io.writeData := mergeStore(
          cacheArray.io.hitData,
          lookupRequest.physicalAddress,
          lookupRequest.byteMask,
          lookupRequest.writeData
        )
        cacheArray.io.writeEntryValid := True
        cacheArray.io.writeDirty := True
      }
    }.otherwise {
      val entry = misses(lookupMshrId)
      entry.valid := True
      entry.state := Mux(
        cacheArray.io.victimValid && cacheArray.io.victimDirty,
        L1DataMshrState.writeback,
        L1DataMshrState.readRequest
      )
      entry.readRequestPending := !(cacheArray.io.victimValid && cacheArray.io.victimDirty)
      entry.lineAddress := lineAddress(lookupRequest.physicalAddress)
      entry.criticalBeat := refillBeatIndex(lookupRequest.physicalAddress)
      entry.victimWay := cacheArray.io.victimWay
      entry.victimAddress := cacheArray.io.victimAddress
      entry.refillMask := B(0, CacheContract.BeatsPerLine bits)
      entry.refillError := False
      entry.storeByteMask := Mux(
        lookupRequest.isWrite,
        storeLineByteMask(lookupRequest.physicalAddress, lookupRequest.byteMask),
        B(0, CacheContract.LineBytes bits)
      )
      when(cacheArray.io.victimValid && cacheArray.io.victimDirty) {
        missVictimData := cacheArray.io.victimData
      }
      when(lookupRequest.isWrite) {
        pendingStoreValid := True
        pendingStoreMshrId := lookupMshrId
        pendingStoreAddress := lookupRequest.physicalAddress(offsetWidth - 1 downto 0)
        pendingStoreByteMask := lookupRequest.byteMask
        pendingStoreData := lookupRequest.writeData
      }
      when(!lookupRequest.isWrite) {
        waiters(lookupWaiterId).valid := True
        waiters(lookupWaiterId).mshrId := lookupMshrId
        waiters(lookupWaiterId).physicalAddress := lookupRequest.physicalAddress
        waiters(lookupWaiterId).robPointer := lookupRequest.robPointer
        waiters(lookupWaiterId).recoveryEpoch := lookupRequest.recoveryEpoch
        waiters(lookupWaiterId).pdst := lookupRequest.pdst
        waiters(lookupWaiterId).loadQueueIndex := lookupRequest.loadQueueIndex
        waiterBeatReady(lookupWaiterId) := False
      }
    }
  }
  when(
    state === L1DataCacheState.maintenanceHitLookup &&
      cacheArray.io.responseValid
  ) {
    when(cacheArray.io.hit) {
      maintenanceWay := cacheArray.io.hitWay
      state := L1DataCacheState.maintenanceLookup
    }.otherwise {
      maintenanceDone := True
      exactMaintenance := False
      state := L1DataCacheState.normal
    }
  }

  val writebackMask = activeWritebackMask
  val readRequestMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    readRequestMask(entry) := misses(entry).valid && misses(entry).readRequestPending
  }
  val writebackId = selectLowest(writebackMask, config.mshrEntries)
  val readRequestId = selectLowest(readRequestMask, config.mshrEntries)

  io.lineWriteValid := False
  io.lineWrite.lineAddress := misses(writebackId).victimAddress
  io.lineWrite.data := missVictimData
  io.lineWrite.byteMask := B(
    (BigInt(1) << CacheContract.LineBytes) - 1,
    CacheContract.LineBytes bits
  )
  io.lineWrite.mshrId := writebackId
  when(state === L1DataCacheState.normal) {
    io.lineWriteValid := writebackMask.orR
  }
  when(state === L1DataCacheState.maintenanceWriteback) {
    io.lineWriteValid := True
    io.lineWrite.lineAddress := maintenanceVictimAddress
    io.lineWrite.data := maintenanceVictimData
    io.lineWrite.mshrId := 0
  }
  val lineWriteFire = io.lineWriteValid && io.lineWriteReady
  when(state === L1DataCacheState.normal && lineWriteFire) {
    misses(writebackId).state := L1DataMshrState.writebackWait
  }
  when(
    state === L1DataCacheState.normal && io.lineWriteResponseValid &&
      misses(io.lineWriteResponse.mshrId).valid &&
      misses(io.lineWriteResponse.mshrId).state === L1DataMshrState.writebackWait
  ) {
    val entry = misses(io.lineWriteResponse.mshrId)
    entry.state := Mux(io.lineWriteResponse.error, L1DataMshrState.writeback, L1DataMshrState.readRequest)
    entry.readRequestPending := !io.lineWriteResponse.error
  }

  io.lineReadValid := state === L1DataCacheState.normal && readRequestMask.orR
  io.lineRead.lineAddress := misses(readRequestId).lineAddress
  io.lineRead.mshrId := readRequestId
  io.lineRead.criticalBeat := misses(readRequestId).criticalBeat
  val lineReadFire = io.lineReadValid && io.lineReadReady
  when(lineReadFire) {
    misses(readRequestId).state := L1DataMshrState.refill
    misses(readRequestId).readRequestPending := False
    misses(readRequestId).refillMask := B(0, CacheContract.BeatsPerLine bits)
    misses(readRequestId).refillError := False
  }

  val pendingStoreRefillBankConflict = pendingStoreApply &&
    pendingStoreMshrId =/= refillId &&
    storeBeatIndex(pendingStoreAddress) === io.lineReadBeat.beat
  io.lineReadBeatReady := refillBaseReady && !pendingStoreRefillBankConflict
  val refillBeatFire = io.lineReadBeatValid && io.lineReadBeatReady

  // Eight shallow banks hold all four refill lines. Each bank has at most one write per cycle:
  // a conflicting merged store is held, while a store sharing the returned MSHR/beat is folded
  // into the refill write. A lookup-time store capture temporarily backpressures that beat bank.
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    val selectedBeat = U(beat, CacheContract.BeatIndexWidth bits)
    val refillSelect = refillBeatFire && io.lineReadBeat.beat === selectedBeat
    val mergeStoreSelect = pendingStoreApply &&
      storeBeatIndex(pendingStoreAddress) === selectedBeat
    val sameCycleStore = refillSelect && mergeStoreSelect &&
      pendingStoreMshrId === refillId
    val writeMshrId = Mux(
      refillSelect,
      refillId,
      pendingStoreMshrId
    )
    val priorStoredBeat = refillMemories(beat).readAsync(writeMshrId)
    val mergeMask = storeBeatBitMask(pendingStoreAddress, pendingStoreByteMask)
    val mergeData = storeBeatData(pendingStoreAddress, pendingStoreData)
    val storedBeat = Mux(
      sameCycleStore,
      (priorStoredBeat & ~mergeMask) | (mergeData & mergeMask),
      priorStoredBeat
    )
    val priorStoredByteMask = misses(refillId).storeByteMask(
      beat * CacheContract.BeatBytes + CacheContract.BeatBytes - 1 downto
        beat * CacheContract.BeatBytes
    )
    val storedByteMask = Mux(
      sameCycleStore,
      priorStoredByteMask |
        (pendingStoreByteMask.resize(CacheContract.BeatBytes) |<<
          beatWordByteShift(pendingStoreAddress)),
      priorStoredByteMask
    )
    val storedBitMask = Bits(CacheContract.BeatBits bits)
    for (byte <- 0 until CacheContract.BeatBytes) {
      storedBitMask(byte * 8 + 7 downto byte * 8) :=
        B(0xff, 8 bits).andMask(storedByteMask(byte))
    }
    val refillWriteData =
      (io.lineReadBeat.data & ~storedBitMask) | (storedBeat & storedBitMask)
    val mergeWriteData = (priorStoredBeat & ~mergeMask) | (mergeData & mergeMask)
    refillMemories(beat).write(
      address = writeMshrId,
      data = Mux(refillSelect, refillWriteData, mergeWriteData),
      enable = refillSelect || mergeStoreSelect
    )
  }

  when(refillBeatFire) {
    val entry = misses(refillId)
    val nextError = entry.refillError || io.lineReadBeat.error
    entry.refillError := nextError
    val nextMask = entry.refillMask | UIntToOh(
      io.lineReadBeat.beat,
      CacheContract.BeatsPerLine
    )
    entry.refillMask := nextMask
    when(
      nextMask.andR && !(mergeStoreFire && lineMatchId === refillId)
    ) {
      entry.state := Mux(
        nextError,
        Mux(
          entry.storeByteMask.orR,
          L1DataMshrState.readRequest,
          L1DataMshrState.respond
        ),
        L1DataMshrState.install
      )
      entry.readRequestPending := nextError && entry.storeByteMask.orR
    }
  }
  for (entry <- 0 until config.loadQueueEntries) {
    when(
      refillBeatFire && waiters(entry).valid && waiters(entry).mshrId === refillId &&
        refillBeatIndex(waiters(entry).physicalAddress) === io.lineReadBeat.beat
    ) {
      waiterBeatReady(entry) := True
    }
  }

  val installId = selectLowest(installMask, config.mshrEntries)
  val installRefillLine = Bits(CacheContract.LineBits bits)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    installRefillLine(
      beat * CacheContract.BeatBits + CacheContract.BeatBits - 1 downto
        beat * CacheContract.BeatBits
    ) := refillMemories(beat).readAsync(installId)
  }
  val installLine = installRefillLine
  val installFire = state === L1DataCacheState.normal && installMask.orR && !lookupResponse
  when(installFire) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeIndex := indexOf(misses(installId).lineAddress)
    cacheArray.io.writeWay := misses(installId).victimWay
    cacheArray.io.writeTag := tagOf(misses(installId).lineAddress)
    cacheArray.io.writeData := installLine
    cacheArray.io.writeEntryValid := True
    cacheArray.io.writeDirty := misses(installId).storeByteMask.orR
    misses(installId).state := L1DataMshrState.respond
  }

  val waiterReadyMask = Bits(config.loadQueueEntries bits)
  for (entry <- 0 until config.loadQueueEntries) {
    waiterReadyMask(entry) := waiters(entry).valid && waiterBeatReady(entry)
  }
  val responseWaiterId = selectLowest(waiterReadyMask, config.loadQueueEntries)
  val responseMshrId = waiters(responseWaiterId).mshrId
  val responseRefillBeats = Vec(Bits(CacheContract.BeatBits bits), CacheContract.BeatsPerLine)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    responseRefillBeats(beat) := refillMemories(beat).readAsync(responseMshrId)
  }
  val responseBeatIndex = refillBeatIndex(waiters(responseWaiterId).physicalAddress)
  val responseBeat = responseRefillBeats(responseBeatIndex)
  val waiterResponseFire = waiterReadyMask.orR && !lookupHitLoad

  when(lookupHitLoad) {
    responseValid := True
    response.robPointer := lookupRequest.robPointer
    response.recoveryEpoch := lookupRequest.recoveryEpoch
    response.pdst := lookupRequest.pdst
    response.loadQueueIndex := lookupRequest.loadQueueIndex
    response.data := selectWord(cacheArray.io.hitData, lookupRequest.physicalAddress)
    response.error := False
  }.elsewhen(waiterResponseFire) {
    responseValid := True
    response.robPointer := waiters(responseWaiterId).robPointer
    response.recoveryEpoch := waiters(responseWaiterId).recoveryEpoch
    response.pdst := waiters(responseWaiterId).pdst
    response.loadQueueIndex := waiters(responseWaiterId).loadQueueIndex
    response.data := selectBeatWord(responseBeat, waiters(responseWaiterId).physicalAddress)
    response.error := misses(responseMshrId).refillError
    waiters(responseWaiterId).valid := False
    waiterBeatReady(responseWaiterId) := False

    val otherWaiters = Bits(config.loadQueueEntries bits)
    for (entry <- 0 until config.loadQueueEntries) {
      otherWaiters(entry) := waiters(entry).valid &&
        U(entry, waiterIndexWidth bits) =/= responseWaiterId &&
        waiters(entry).mshrId === responseMshrId
    }
    when(
      !otherWaiters.orR &&
        !(mergeLoadFire && lineMatchId === responseMshrId) &&
        misses(responseMshrId).state === L1DataMshrState.respond
    ) {
      misses(responseMshrId).valid := False
    }
  }

  for (entry <- 0 until config.mshrEntries) {
    val entryWaiters = Bits(config.loadQueueEntries bits)
    for (waiter <- 0 until config.loadQueueEntries) {
      entryWaiters(waiter) := waiters(waiter).valid &&
        waiters(waiter).mshrId === U(entry, mshrIdWidth bits)
    }
    when(
      misses(entry).valid && misses(entry).state === L1DataMshrState.respond &&
        !entryWaiters.orR &&
        !(mergeLoadFire && lineMatchId === U(entry, mshrIdWidth bits))
    ) {
      misses(entry).valid := False
    }
  }

  when(
    state === L1DataCacheState.maintenanceLookup &&
      cacheArray.io.maintenanceResponseValid
  ) {
    when(
      maintenanceMode =/= CacheMaintenanceMode.storeTag &&
        cacheArray.io.maintenanceEntryValid && cacheArray.io.maintenanceEntryDirty
    ) {
      maintenanceVictimAddress := cacheArray.io.maintenanceEntryAddress
      maintenanceVictimData := cacheArray.io.maintenanceEntryData
      state := L1DataCacheState.maintenanceWriteback
    }.otherwise {
      state := L1DataCacheState.maintenanceInvalidate
    }
  }
  when(state === L1DataCacheState.maintenanceWriteback && lineWriteFire) {
    state := L1DataCacheState.maintenanceWritebackWait
  }
  when(
    state === L1DataCacheState.maintenanceWritebackWait &&
      io.lineWriteResponseValid
  ) {
    state := Mux(
      io.lineWriteResponse.error,
      L1DataCacheState.maintenanceWriteback,
      L1DataCacheState.maintenanceInvalidate
    )
  }
  when(state === L1DataCacheState.maintenanceInvalidate) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeIndex := maintenanceIndex
    cacheArray.io.writeWay := maintenanceWay
    cacheArray.io.writeTag := 0
    cacheArray.io.writeData := 0
    cacheArray.io.writeEntryValid := False
    cacheArray.io.writeDirty := False
    when(exactMaintenance) {
      exactMaintenance := False
      maintenanceDone := True
      state := L1DataCacheState.normal
    }.elsewhen(maintenanceWay === U(geometry.ways - 1, wayWidth bits)) {
      maintenanceWay := 0
      when(maintenanceIndex === U(geometry.sets - 1, indexWidth bits)) {
        state := L1DataCacheState.normal
      }.otherwise {
        maintenanceIndex := maintenanceIndex + 1
        state := L1DataCacheState.maintenanceLookup
      }
    }.otherwise {
      maintenanceWay := maintenanceWay + 1
      state := L1DataCacheState.maintenanceLookup
    }
  }

  io.invalidateBusy := cacheArray.io.invalidateBusy || maintenanceRequest ||
    state =/= L1DataCacheState.normal
  val idleNow = state === L1DataCacheState.normal && !normalBusy &&
    !maintenanceRequest && !cacheArray.io.invalidateBusy && !responseValid &&
    !io.requestValid && !io.invalidate && !io.writebackInvalidate &&
    !io.maintenanceRequest.valid && !maintenanceDone
  io.idle := RegNext(idleNow) init (False)
}
