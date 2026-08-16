package miku.memory

import miku.core._
import spinal.core._
import spinal.lib._

object L2CacheState extends SpinalEnum {
  val normal, maintenanceHitLookup, maintenanceLookup, maintenanceWriteback,
    maintenanceWritebackWait, maintenanceInvalidate = newElement()
}

/** Nonblocking 64-KiB shared L2 indexed by the hierarchy-global MSHR identity.
  *
  * Different sets may be looked up and refilled concurrently. Same-set requests are held above L2
  * until the active owner installs, which prevents two MSHRs from selecting one physical victim.
  */
final class L2Cache(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val geometry = config.level2Cache
  private val wayWidth = log2Up(geometry.ways)
  private val indexWidth = geometry.indexWidth
  private val offsetWidth = geometry.offsetWidth
  private val mshrIdWidth = log2Up(config.mshrEntries)

  require(geometry.capacityBytes == 64 * 1024)
  require(geometry.lineBytes == CacheContract.LineBytes)

  private def lineAddress(address: UInt): UInt =
    address & U(((BigInt(1) << config.xlen) - 1) ^ (geometry.lineBytes - 1), config.xlen bits)

  private def indexOf(address: UInt): UInt =
    address(offsetWidth + indexWidth - 1 downto offsetWidth)

  private def tagOf(address: UInt): UInt =
    address(config.xlen - 1 downto offsetWidth + indexWidth)

  private def selectLowest(mask: Bits, count: Int): UInt = {
    val selected = UInt(log2Up(count) bits)
    selected := 0
    for (index <- (0 until count).reverse) {
      when(mask(index)) { selected := U(index, log2Up(count) bits) }
    }
    selected
  }

  val io = new Bundle {
    val readValid = in Bool ()
    val read = in(LineReadRequest(config))
    val readReady = out Bool ()
    val readBeatValid = out Bool ()
    val readBeat = out(LineReadBeat(config))
    val readBeatReady = in Bool ()

    val writeValid = in Bool ()
    val write = in(LineWriteRequest(config))
    val writeReady = out Bool ()
    val writeResponseValid = out Bool ()
    val writeResponse = out(LineWriteResponse(config))

    val memoryReadValid = out Bool ()
    val memoryRead = out(LineReadRequest(config))
    val memoryReadReady = in Bool ()
    val memoryReadBeatValid = in Bool ()
    val memoryReadBeat = in(LineReadBeat(config))
    val memoryReadBeatReady = out Bool ()

    val memoryWriteValid = out Bool ()
    val memoryWrite = out(LineWriteRequest(config))
    val memoryWriteReady = in Bool ()
    val memoryWriteResponseValid = in Bool ()
    val memoryWriteResponse = in(LineWriteResponse(config))

    val invalidate = in Bool ()
    val writebackInvalidate = in Bool ()
    val maintenanceRequest = slave(Stream(CacheMaintenanceRequest(config)))
    val maintenanceDone = out Bool ()
    val invalidateBusy = out Bool ()
    val idle = out Bool ()
  }

  val cacheArray = new CacheArray(
    geometry,
    decoupleDataReadEnable = config.enableDataArrayDataReadDecoupling
  )
  val state = RegInit(L2CacheState.normal)
  val misses = Vec.fill(config.mshrEntries)(Reg(L2Mshr(config)))
  val lineMemories = Array.fill(CacheContract.BeatsPerLine)(
    Mem(Bits(CacheContract.BeatBits bits), config.mshrEntries)
  )
  // Keep the victim line beside its owning MSHR.  Different-set read misses
  // may capture several dirty victims before the shared write port drains one.
  val missVictimData = Vec.fill(config.mshrEntries)(
    Reg(Bits(CacheContract.LineBits bits))
  )
  for (entry <- misses) {
    entry.valid.init(False)
    entry.state.init(L2MshrState.readRequest)
  }

  val lookupPending = RegInit(False)
  val lookupIsWrite = RegInit(False)
  val lookupMshrId = Reg(UInt(mshrIdWidth bits))
  val lookupAddress = Reg(UInt(config.xlen bits))
  val lookupCriticalBeat = Reg(UInt(CacheContract.BeatIndexWidth bits))

  val writeState = RegInit(L2WriteState.idle)
  val writeAddress = Reg(UInt(config.xlen bits))
  val writeMshrId = Reg(UInt(mshrIdWidth bits))
  val writeData = Reg(Bits(CacheContract.LineBits bits))
  val writeWay = Reg(UInt(wayWidth bits))
  val writeVictimAddress = Reg(UInt(config.xlen bits))
  val writeVictimData = Reg(Bits(CacheContract.LineBits bits))
  val writeResponseValid = RegInit(False)
  val writeResponse = Reg(LineWriteResponse(config))
  writeResponseValid := False
  io.writeResponseValid := writeResponseValid
  io.writeResponse := writeResponse

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
  for (entry <- 0 until config.mshrEntries) {
    activeMissMask(entry) := misses(entry).valid
  }
  val normalBusy = lookupPending || activeMissMask.orR ||
    writeState =/= L2WriteState.idle
  val maintenanceRequest = invalidatePending || newInvalidate ||
    writebackInvalidatePending || newWritebackInvalidate
  val startInvalidate = (invalidatePending || newInvalidate) && !normalBusy &&
    state === L2CacheState.normal && !cacheArray.io.invalidateBusy &&
    !io.maintenanceRequest.valid
  val startWritebackInvalidate = (writebackInvalidatePending || newWritebackInvalidate) &&
    !normalBusy && state === L2CacheState.normal &&
    !(invalidatePending || newInvalidate) && !cacheArray.io.invalidateBusy &&
    !io.maintenanceRequest.valid
  when(startInvalidate) { invalidatePending := False }
  when(startWritebackInvalidate) {
    writebackInvalidatePending := False
    exactMaintenance := False
    maintenanceMode := CacheMaintenanceMode.index
    maintenanceIndex := 0
    maintenanceWay := 0
    state := L2CacheState.maintenanceLookup
  }

  val installMask = Bits(config.mshrEntries bits)
  val missWritebackMask = Bits(config.mshrEntries bits)
  val readRequestMask = Bits(config.mshrEntries bits)
  val hitResponseMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    installMask(entry) := misses(entry).valid && misses(entry).state === L2MshrState.install
    missWritebackMask(entry) := misses(entry).valid &&
      misses(entry).state === L2MshrState.writeback
    readRequestMask(entry) := misses(entry).valid &&
      misses(entry).state === L2MshrState.readRequest
    hitResponseMask(entry) := misses(entry).valid &&
      misses(entry).state === L2MshrState.respond
  }

  val readSetConflict = Bits(config.mshrEntries bits)
  val writeSetConflict = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    readSetConflict(entry) := misses(entry).valid &&
      indexOf(misses(entry).lineAddress) === indexOf(io.read.lineAddress)
    writeSetConflict(entry) := misses(entry).valid &&
      indexOf(misses(entry).lineAddress) === indexOf(io.write.lineAddress)
  }
  val writeContextConflictsRead = writeState =/= L2WriteState.idle &&
    indexOf(writeAddress) === indexOf(io.read.lineAddress)
  val canStartLookup = state === L2CacheState.normal && !maintenanceRequest &&
    !cacheArray.io.invalidateBusy && !lookupPending && !installMask.orR &&
    !missWritebackMask.orR && writeState =/= L2WriteState.install &&
    cacheArray.io.lookupReady && !io.maintenanceRequest.valid

  // A dirty L1D eviction has priority over a read lookup.  The write decision is intentionally
  // independent of readValid: otherwise the L1D state crosses the shared MSHR router and returns
  // to the 512-bit write-data register enable in the same cycle.
  io.writeReady := canStartLookup && writeState === L2WriteState.idle &&
    !writeSetConflict.orR && io.write.byteMask.andR
  val writeFire = io.writeValid && io.writeReady
  io.readReady := canStartLookup && !writeFire && !misses(io.read.mshrId).valid &&
    !readSetConflict.orR && !writeContextConflictsRead
  val readFire = io.readValid && io.readReady
  io.maintenanceRequest.ready := state === L2CacheState.normal &&
    !normalBusy && !maintenanceRequest && !cacheArray.io.invalidateBusy &&
    cacheArray.io.lookupReady
  val exactMaintenanceFire = io.maintenanceRequest.valid &&
    io.maintenanceRequest.ready

  cacheArray.io.lookupValid := readFire || writeFire
  cacheArray.io.lookupAddress := Mux(readFire, io.read.lineAddress, io.write.lineAddress)
  cacheArray.io.writeValid := False
  cacheArray.io.writeIndex := 0
  cacheArray.io.writeWay := 0
  cacheArray.io.writeTag := 0
  cacheArray.io.writeData := 0
  cacheArray.io.writeEntryValid := True
  cacheArray.io.writeDirty := False
  cacheArray.io.invalidate := startInvalidate
  cacheArray.io.maintenanceReadValid := state === L2CacheState.maintenanceLookup
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
      state := L2CacheState.maintenanceHitLookup
    }.otherwise {
      state := L2CacheState.maintenanceLookup
    }
  }

  when(readFire) {
    lookupPending := True
    lookupIsWrite := False
    lookupMshrId := io.read.mshrId
    lookupAddress := lineAddress(io.read.lineAddress)
    lookupCriticalBeat := io.read.criticalBeat
  }.elsewhen(writeFire) {
    lookupPending := True
    lookupIsWrite := True
    lookupMshrId := io.write.mshrId
    lookupAddress := lineAddress(io.write.lineAddress)
    writeState := L2WriteState.lookup
    writeAddress := lineAddress(io.write.lineAddress)
    writeMshrId := io.write.mshrId
    writeData := io.write.data
  }

  val lookupResponse = lookupPending && cacheArray.io.responseValid
  when(lookupResponse) {
    lookupPending := False
    when(lookupIsWrite) {
      writeWay := Mux(cacheArray.io.hit, cacheArray.io.hitWay, cacheArray.io.victimWay)
      writeVictimAddress := cacheArray.io.victimAddress
      writeVictimData := cacheArray.io.victimData
      val cleanVictimState = if (config.enableL2WriteBack) {
        L2WriteState.install
      } else {
        L2WriteState.writeThrough
      }
      writeState := Mux(
        !cacheArray.io.hit && cacheArray.io.victimValid && cacheArray.io.victimDirty,
        L2WriteState.victimWriteback,
        cleanVictimState
      )
    }.otherwise {
      val entry = misses(lookupMshrId)
      entry.valid := True
      entry.lineAddress := lookupAddress
      entry.criticalBeat := lookupCriticalBeat
      entry.returnBeat := lookupCriticalBeat
      entry.returnCount := U(0, CacheContract.BeatIndexWidth bits)
      entry.error := False
      entry.refillMask := B(0, CacheContract.BeatsPerLine bits)
      when(cacheArray.io.hit) {
        entry.state := L2MshrState.respond
      }.otherwise {
        entry.victimWay := cacheArray.io.victimWay
        entry.victimAddress := cacheArray.io.victimAddress
        entry.state := Mux(
          cacheArray.io.victimValid && cacheArray.io.victimDirty,
          L2MshrState.writeback,
          L2MshrState.readRequest
        )
        when(cacheArray.io.victimValid && cacheArray.io.victimDirty) {
          missVictimData(lookupMshrId) := cacheArray.io.victimData
        }
      }
    }
  }
  when(
    state === L2CacheState.maintenanceHitLookup &&
      cacheArray.io.responseValid
  ) {
    when(cacheArray.io.hit) {
      maintenanceWay := cacheArray.io.hitWay
      state := L2CacheState.maintenanceLookup
    }.otherwise {
      maintenanceDone := True
      exactMaintenance := False
      state := L2CacheState.normal
    }
  }

  val captureHitLine = lookupResponse && !lookupIsWrite && cacheArray.io.hit
  val hitCaptureValid = RegInit(False)
  val hitCaptureMshrId = Reg(UInt(mshrIdWidth bits))
  val hitCaptureData = Reg(Bits(CacheContract.LineBits bits))
  when(hitCaptureValid) { hitCaptureValid := False }
  when(captureHitLine) {
    hitCaptureValid := True
    hitCaptureMshrId := lookupMshrId
    hitCaptureData := cacheArray.io.hitData
  }

  val missWritebackId = selectLowest(missWritebackMask, config.mshrEntries)
  io.memoryWriteValid := False
  io.memoryWrite.lineAddress := misses(missWritebackId).victimAddress
  io.memoryWrite.data := missVictimData(missWritebackId)
  io.memoryWrite.byteMask := B(
    (BigInt(1) << CacheContract.LineBytes) - 1,
    CacheContract.LineBytes bits
  )
  io.memoryWrite.mshrId := missWritebackId
  when(state === L2CacheState.normal) {
    when(writeState === L2WriteState.victimWriteback) {
      io.memoryWriteValid := True
      io.memoryWrite.lineAddress := writeVictimAddress
      io.memoryWrite.data := writeVictimData
      io.memoryWrite.mshrId := writeMshrId
    }.elsewhen(writeState === L2WriteState.writeThrough) {
      io.memoryWriteValid := True
      io.memoryWrite.lineAddress := writeAddress
      io.memoryWrite.data := writeData
      io.memoryWrite.mshrId := writeMshrId
    }.otherwise {
      io.memoryWriteValid := missWritebackMask.orR
    }
  }
  when(state === L2CacheState.maintenanceWriteback) {
    io.memoryWriteValid := True
    io.memoryWrite.lineAddress := maintenanceVictimAddress
    io.memoryWrite.data := maintenanceVictimData
    io.memoryWrite.mshrId := 0
  }
  val memoryWriteFire = io.memoryWriteValid && io.memoryWriteReady
  when(state === L2CacheState.normal && memoryWriteFire) {
    when(writeState === L2WriteState.victimWriteback) {
      writeState := L2WriteState.victimWritebackWait
    }.elsewhen(writeState === L2WriteState.writeThrough) {
      writeState := L2WriteState.writeThroughWait
    }.otherwise {
      misses(missWritebackId).state := L2MshrState.writebackWait
    }
  }
  when(state === L2CacheState.normal && io.memoryWriteResponseValid) {
    when(
      writeState === L2WriteState.victimWritebackWait &&
        io.memoryWriteResponse.mshrId === writeMshrId
    ) {
      when(io.memoryWriteResponse.error) {
        writeState := L2WriteState.idle
        writeResponseValid := True
        writeResponse.mshrId := writeMshrId
        writeResponse.error := True
      }.otherwise {
        writeState := (if (config.enableL2WriteBack) {
          L2WriteState.install
        } else {
          L2WriteState.writeThrough
        })
      }
    }.elsewhen(
      writeState === L2WriteState.writeThroughWait &&
        io.memoryWriteResponse.mshrId === writeMshrId
    ) {
      when(io.memoryWriteResponse.error) {
        writeState := L2WriteState.idle
        writeResponseValid := True
        writeResponse.mshrId := writeMshrId
        writeResponse.error := True
      }.otherwise {
        writeState := L2WriteState.install
      }
    }.elsewhen(
      misses(io.memoryWriteResponse.mshrId).valid &&
        misses(io.memoryWriteResponse.mshrId).state === L2MshrState.writebackWait
    ) {
      misses(io.memoryWriteResponse.mshrId).state := Mux(
        io.memoryWriteResponse.error,
        L2MshrState.writeback,
        L2MshrState.readRequest
      )
    }
  }

  val readRequestId = selectLowest(readRequestMask, config.mshrEntries)
  io.memoryReadValid := state === L2CacheState.normal && readRequestMask.orR
  io.memoryRead.lineAddress := misses(readRequestId).lineAddress
  io.memoryRead.mshrId := readRequestId
  io.memoryRead.criticalBeat := misses(readRequestId).criticalBeat
  val memoryReadFire = io.memoryReadValid && io.memoryReadReady
  when(memoryReadFire) {
    misses(readRequestId).state := L2MshrState.refill
    misses(readRequestId).refillMask := B(0, CacheContract.BeatsPerLine bits)
    misses(readRequestId).error := False
  }

  val memoryResponseId = io.memoryReadBeat.mshrId
  val eligibleHitResponseMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) {
    eligibleHitResponseMask(entry) := hitResponseMask(entry) &&
      !(hitCaptureValid && hitCaptureMshrId === U(entry, mshrIdWidth bits))
  }
  val hitResponseId = selectLowest(eligibleHitResponseMask, config.mshrEntries)
  val hitResponseBeats = Vec(Bits(CacheContract.BeatBits bits), CacheContract.BeatsPerLine)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    hitResponseBeats(beat) := lineMemories(beat).readAsync(hitResponseId)
  }

  // Both hit and refill responses cross a local register boundary.  The refill slot can retire
  // and accept a replacement in one cycle, so it still sustains one beat per cycle while cutting
  // the AXI/L2 state -> L1I predecode path seen after route.
  val refillOutputValid = RegInit(False)
  val refillOutput = Reg(LineReadBeat(config))
  val hitOutputValid = RegInit(False)
  val hitOutput = Reg(LineReadBeat(config))
  io.readBeatValid := refillOutputValid || hitOutputValid
  io.readBeat := hitOutput
  when(refillOutputValid) { io.readBeat := refillOutput }

  val refillOutputFire = refillOutputValid && io.readBeatReady
  val refillOutputReady = !refillOutputValid || refillOutputFire
  val hitOutputFire = !refillOutputValid && hitOutputValid && io.readBeatReady
  val hitOutputReady = !hitOutputValid || hitOutputFire
  val hitResponseLoad = eligibleHitResponseMask.orR && hitOutputReady
  io.memoryReadBeatReady := !hitCaptureValid && refillOutputReady
  val memoryRefillFire = io.memoryReadBeatValid && io.memoryReadBeatReady

  when(refillOutputReady) {
    refillOutputValid := memoryRefillFire
    when(memoryRefillFire) { refillOutput := io.memoryReadBeat }
  }
  when(hitOutputReady) {
    hitOutputValid := eligibleHitResponseMask.orR
    when(eligibleHitResponseMask.orR) {
      hitOutput.mshrId := hitResponseId
      hitOutput.beat := misses(hitResponseId).returnBeat
      hitOutput.data := hitResponseBeats(misses(hitResponseId).returnBeat)
      hitOutput.last := misses(hitResponseId).returnCount ===
        CacheContract.BeatsPerLine - 1
      hitOutput.error := misses(hitResponseId).error
    }
  }

  // A hit captures all banks in parallel. A simultaneous external refill is backpressured for
  // that cycle, keeping each shallow memory bank single-write while preserving four line IDs.
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    val refillSelect = memoryRefillFire &&
      io.memoryReadBeat.beat === U(beat, CacheContract.BeatIndexWidth bits)
    lineMemories(beat).write(
      address = Mux(refillSelect, memoryResponseId, hitCaptureMshrId),
      data = Mux(
        refillSelect,
        io.memoryReadBeat.data,
        hitCaptureData(
          beat * CacheContract.BeatBits + CacheContract.BeatBits - 1 downto
            beat * CacheContract.BeatBits
        )
      ),
      enable = refillSelect || hitCaptureValid
    )
  }

  when(memoryRefillFire) {
    val entry = misses(memoryResponseId)
    val nextError = entry.error || io.memoryReadBeat.error
    entry.error := nextError
    val nextMask = entry.refillMask | UIntToOh(
      io.memoryReadBeat.beat,
      CacheContract.BeatsPerLine
    )
    entry.refillMask := nextMask
    when(nextMask.andR) {
      when(nextError) {
        entry.valid := False
      }.otherwise {
        entry.state := L2MshrState.install
      }
    }
  }
  when(hitResponseLoad) {
    when(misses(hitResponseId).returnCount === CacheContract.BeatsPerLine - 1) {
      misses(hitResponseId).valid := False
    }.otherwise {
      misses(hitResponseId).returnBeat := misses(hitResponseId).returnBeat + 1
      misses(hitResponseId).returnCount := misses(hitResponseId).returnCount + 1
    }
  }

  val installId = selectLowest(installMask, config.mshrEntries)
  val installLine = Bits(CacheContract.LineBits bits)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    installLine(
      beat * CacheContract.BeatBits + CacheContract.BeatBits - 1 downto
        beat * CacheContract.BeatBits
    ) := lineMemories(beat).readAsync(installId)
  }
  val writeInstall = state === L2CacheState.normal &&
    writeState === L2WriteState.install && !lookupResponse
  val missInstall = state === L2CacheState.normal && installMask.orR &&
    !writeInstall && !lookupResponse
  when(writeInstall) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeIndex := indexOf(writeAddress)
    cacheArray.io.writeWay := writeWay
    cacheArray.io.writeTag := tagOf(writeAddress)
    cacheArray.io.writeData := writeData
    cacheArray.io.writeEntryValid := True
    cacheArray.io.writeDirty := (if (config.enableL2WriteBack) True else False)
    writeState := L2WriteState.idle
    writeResponseValid := True
    writeResponse.mshrId := writeMshrId
    writeResponse.error := False
  }.elsewhen(missInstall) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeIndex := indexOf(misses(installId).lineAddress)
    cacheArray.io.writeWay := misses(installId).victimWay
    cacheArray.io.writeTag := tagOf(misses(installId).lineAddress)
    cacheArray.io.writeData := installLine
    cacheArray.io.writeEntryValid := True
    cacheArray.io.writeDirty := False
    misses(installId).valid := False
  }

  when(
    state === L2CacheState.maintenanceLookup &&
      cacheArray.io.maintenanceResponseValid
  ) {
    when(
      maintenanceMode =/= CacheMaintenanceMode.storeTag &&
        cacheArray.io.maintenanceEntryValid && cacheArray.io.maintenanceEntryDirty
    ) {
      maintenanceVictimAddress := cacheArray.io.maintenanceEntryAddress
      maintenanceVictimData := cacheArray.io.maintenanceEntryData
      state := L2CacheState.maintenanceWriteback
    }.otherwise {
      state := L2CacheState.maintenanceInvalidate
    }
  }
  when(state === L2CacheState.maintenanceWriteback && memoryWriteFire) {
    state := L2CacheState.maintenanceWritebackWait
  }
  when(
    state === L2CacheState.maintenanceWritebackWait &&
      io.memoryWriteResponseValid
  ) {
    state := Mux(
      io.memoryWriteResponse.error,
      L2CacheState.maintenanceWriteback,
      L2CacheState.maintenanceInvalidate
    )
  }
  when(state === L2CacheState.maintenanceInvalidate) {
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
      state := L2CacheState.normal
    }.elsewhen(maintenanceWay === U(geometry.ways - 1, wayWidth bits)) {
      maintenanceWay := 0
      when(maintenanceIndex === U(geometry.sets - 1, indexWidth bits)) {
        state := L2CacheState.normal
      }.otherwise {
        maintenanceIndex := maintenanceIndex + 1
        state := L2CacheState.maintenanceLookup
      }
    }.otherwise {
      maintenanceWay := maintenanceWay + 1
      state := L2CacheState.maintenanceLookup
    }
  }

  io.invalidateBusy := cacheArray.io.invalidateBusy || maintenanceRequest ||
    state =/= L2CacheState.normal
  val idleNow = state === L2CacheState.normal && !normalBusy &&
    !maintenanceRequest && !cacheArray.io.invalidateBusy &&
    !refillOutputValid && !hitOutputValid && !hitCaptureValid &&
    !io.readValid && !io.writeValid && !io.invalidate && !io.writebackInvalidate &&
    !io.maintenanceRequest.valid && !maintenanceDone
  io.idle := RegNext(idleNow) init (False)
}
