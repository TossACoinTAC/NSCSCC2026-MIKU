package miku.memory

import miku.core._
import miku.predict._
import spinal.core._
import spinal.lib._

object L1InstructionCacheState extends SpinalEnum {
  val idle, lookup, refillRequest, refillData, install, maintenanceHitLookup,
    maintenanceInvalidate = newElement()
}

/** Set-associative L1 instruction cache with 64-byte lines.
  *
  * A killed request is allowed to finish its refill so a redirect back to the same line can hit,
  * but no response is emitted for the stale fetch group. Once the requested 16-byte group has
  * returned, another request to the line being refilled may take ownership without waiting for
  * installation; requests to a different line remain blocked.
  */
final class L1InstructionCache(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val geometry = config.instructionCache
  private val wayWidth = log2Up(geometry.ways)
  private val indexWidth = geometry.indexWidth
  private val offsetWidth = geometry.offsetWidth
  private val fetchGroupBits = config.fetchWidth * 32
  private val fetchGroupBytes = fetchGroupBits / 8
  private val fetchGroupOffsetWidth = log2Up(fetchGroupBytes)

  require(geometry.lineBytes == CacheContract.LineBytes)
  require(fetchGroupBytes == 16)
  require(geometry.lineBytes % fetchGroupBytes == 0)

  private def lineAddress(address: UInt): UInt =
    address & U(((BigInt(1) << config.xlen) - 1) ^ (geometry.lineBytes - 1), config.xlen bits)

  // Same-line refill ownership sits on the frontend TLB-bypass to L1I lookup
  // enable cone.  Use an XOR/NOR LUT tree instead of Vivado's CARRY4 compare
  // chain; behavior is unchanged.
  private def lutTreeEqual(a: UInt, b: UInt): Bool =
    !((a ^ b).asBits.orR)

  private def indexOf(address: UInt): UInt =
    address(offsetWidth + indexWidth - 1 downto offsetWidth)

  private def tagOf(address: UInt): UInt =
    address(config.xlen - 1 downto offsetWidth + indexWidth)

  private def selectFetchGroup(line: Bits, address: UInt): Vec[Bits] = {
    val group = Vec(Bits(32 bits), config.fetchWidth)
    val shift = (address(offsetWidth - 1 downto fetchGroupOffsetWidth) ##
      U(0, log2Up(fetchGroupBits) bits)).asUInt
    val selected = line |>> shift
    for (lane <- 0 until config.fetchWidth) {
      group(lane) := selected(lane * 32 + 31 downto lane * 32)
    }
    group
  }

  private def driveResponse(
      output: InstructionCacheResponse,
      context: InstructionCacheRequest,
      group: Vec[Bits],
      error: Bool
  ): Unit = {
    val groupBase = context.virtualAddress &
      U(((BigInt(1) << config.xlen) - 1) ^ (fetchGroupBytes - 1), config.xlen bits)
    output.virtualAddress := context.virtualAddress
    output.physicalAddress := context.physicalAddress
    output.error := error
    for (lane <- 0 until config.fetchWidth) {
      output.instructions(lane) := group(lane)
      FetchPredecoder.drive(
        output.predecode(lane),
        config,
        groupBase + U(lane * 4, config.xlen bits),
        group(lane)
      )
    }
  }

  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(InstructionCacheRequest(config))
    val requestReady = out Bool ()
    val requestCapacityReady = out Bool ()
    val hitResponsePending = out Bool ()
    val responseValid = out Bool ()
    val response = out(InstructionCacheResponse(config))
    val kill = in Bool ()

    val lineReadValid = out Bool ()
    val lineRead = out(LineReadRequest(config))
    val lineReadReady = in Bool ()
    val lineReadBeatValid = in Bool ()
    val lineReadBeat = in(LineReadBeat(config))
    val lineReadBeatReady = out Bool ()

    val invalidate = in Bool ()
    val maintenanceRequest = slave(Stream(CacheMaintenanceRequest(config)))
    val maintenanceDone = out Bool ()
    val invalidateBusy = out Bool ()
    val idle = out Bool ()
  }

  val cacheArray = new CacheArray(
    geometry,
    decoupleDataReadEnable = config.enableInstructionArrayDataReadDecoupling
  )
  val state = RegInit(L1InstructionCacheState.idle)
  val invalidateSeen = RegInit(False)
  val invalidatePending = RegInit(False)
  val request = Reg(InstructionCacheRequest(config))
  val requestKilled = RegInit(False)
  val victimWay = Reg(UInt(wayWidth bits))
  val refillBeats = Vec.fill(CacheContract.BeatsPerLine)(
    Reg(Bits(CacheContract.BeatBits bits))
  )
  val refillMask = Reg(Bits(CacheContract.BeatsPerLine bits)) init (0)
  val refillError = RegInit(False)
  val refillResponseSent = RegInit(False)
  val refillReplayPending = RegInit(False)
  val maintenanceIndex = Reg(UInt(indexWidth bits)) init (0)
  val maintenanceWay = Reg(UInt(wayWidth bits)) init (0)
  val maintenanceDone = RegInit(False)
  maintenanceDone := False
  io.maintenanceDone := maintenanceDone

  val refillLine = Bits(CacheContract.LineBits bits)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    refillLine(
      beat * CacheContract.BeatBits + CacheContract.BeatBits - 1 downto
        beat * CacheContract.BeatBits
    ) := refillBeats(beat)
  }

  val responseValid = RegInit(False)
  val response = Reg(InstructionCacheResponse(config))
  responseValid := False
  // Keep the L1I-to-frontend response boundary registered.  The controller may
  // still accept the next synchronous lookup while the current response is in
  // this register, avoiding the direct BRAM-to-frontend correction path.
  io.responseValid := responseValid
  io.response := response

  // Compute each way's response in parallel with the tag comparison.  The hit way then selects
  // between already decoded candidates instead of serializing tag compare, line selection and
  // branch-target addition on the L1I response-register input.
  val hitResponseByWay = Vec(InstructionCacheResponse(config), geometry.ways)
  for (way <- 0 until geometry.ways) {
    driveResponse(
      hitResponseByWay(way),
      request,
      selectFetchGroup(cacheArray.io.wayData(way), request.physicalAddress),
      False
    )
  }

  private def writeResponse(
      context: InstructionCacheRequest,
      group: Vec[Bits],
      error: Bool
  ): Unit = driveResponse(response, context, group, error)

  val newInvalidate = io.invalidate && !invalidateSeen
  when(io.invalidate) { invalidateSeen := True }.otherwise { invalidateSeen := False }
  val invalidateRequest = invalidatePending || newInvalidate
  val startInvalidate = invalidateRequest && state === L1InstructionCacheState.idle &&
    !cacheArray.io.invalidateBusy && !io.maintenanceRequest.valid
  when(newInvalidate) { invalidatePending := True }
  when(startInvalidate) { invalidatePending := False }

  cacheArray.io.lookupAddress := io.request.physicalAddress
  cacheArray.io.writeValid := False
  cacheArray.io.writeIndex := indexOf(request.physicalAddress)
  cacheArray.io.writeWay := victimWay
  cacheArray.io.writeTag := tagOf(request.physicalAddress)
  cacheArray.io.writeData := refillLine
  cacheArray.io.writeEntryValid := True
  cacheArray.io.writeDirty := False
  cacheArray.io.invalidate := startInvalidate
  cacheArray.io.maintenanceReadValid := False
  cacheArray.io.maintenanceReadIndex := 0
  cacheArray.io.maintenanceReadWay := 0

  val idleRequestReady = state === L1InstructionCacheState.idle &&
    cacheArray.io.lookupReady
  // Reuse a completed hit's array-response cycle for the next synchronous lookup.
  val lookupHitTurnoverCapacityReady = state === L1InstructionCacheState.lookup &&
    cacheArray.io.responseValid && cacheArray.io.hit && !requestKilled &&
    !newInvalidate && cacheArray.io.lookupReady
  val lookupHitTurnoverReady = lookupHitTurnoverCapacityReady && !io.kill
  io.hitResponsePending := lookupHitTurnoverReady
  val refillSameLineReady = state === L1InstructionCacheState.refillData &&
    refillResponseSent && !refillReplayPending && !requestKilled && !io.request.uncached &&
    lutTreeEqual(
      lineAddress(io.request.physicalAddress),
      lineAddress(request.physicalAddress)
    )
  io.requestCapacityReady :=
    (idleRequestReady || lookupHitTurnoverCapacityReady || refillSameLineReady) &&
      !invalidateRequest && !io.maintenanceRequest.valid
  io.requestReady := (idleRequestReady || lookupHitTurnoverReady || refillSameLineReady) &&
    !invalidateRequest && !io.maintenanceRequest.valid
  io.maintenanceRequest.ready := state === L1InstructionCacheState.idle &&
    !invalidateRequest && !cacheArray.io.invalidateBusy && cacheArray.io.lookupReady
  val maintenanceFire = io.maintenanceRequest.valid && io.maintenanceRequest.ready
  val requestFire = io.requestValid && io.requestReady
  val lookupHitTurnoverFire = requestFire && lookupHitTurnoverReady
  val refillRequestFire = requestFire && refillSameLineReady
  val speculativeHitTurnoverLookup = if (config.enableSpeculativeInstructionArrayRead) {
    state === L1InstructionCacheState.lookup && cacheArray.io.responseValid &&
      io.requestValid && !io.request.uncached && !requestKilled && !io.kill &&
      !newInvalidate && !io.maintenanceRequest.valid && cacheArray.io.lookupReady
  } else {
    False
  }
  cacheArray.io.lookupValid := speculativeHitTurnoverLookup
  when(requestFire) {
    request := io.request
    requestKilled := io.kill
    when(refillRequestFire) {
      refillResponseSent := False
    }.otherwise {
      cacheArray.io.lookupValid := True
      cacheArray.io.lookupAddress := io.request.physicalAddress
      state := L1InstructionCacheState.lookup
    }
  }
  when(maintenanceFire) {
    maintenanceIndex := indexOf(io.maintenanceRequest.virtualAddress)
    maintenanceWay := io.maintenanceRequest.virtualAddress(wayWidth - 1 downto 0)
    when(
      io.maintenanceRequest.code(4 downto 3).asUInt ===
        CacheMaintenanceMode.hit
    ) {
      maintenanceIndex := indexOf(io.maintenanceRequest.physicalAddress)
      cacheArray.io.lookupValid := True
      cacheArray.io.lookupAddress := io.maintenanceRequest.physicalAddress
      state := L1InstructionCacheState.maintenanceHitLookup
    }.otherwise {
      state := L1InstructionCacheState.maintenanceInvalidate
    }
  }
  when(
    (io.kill || newInvalidate) &&
      (state =/= L1InstructionCacheState.idle || requestFire)
  ) {
    requestKilled := True
  }

  io.lineReadValid := state === L1InstructionCacheState.refillRequest
  io.lineRead.lineAddress := lineAddress(request.physicalAddress)
  io.lineRead.mshrId := 0
  io.lineRead.criticalBeat := U(0, CacheContract.BeatIndexWidth bits)
  io.lineReadBeatReady := state === L1InstructionCacheState.refillData &&
    io.lineReadBeat.mshrId === 0

  when(state === L1InstructionCacheState.lookup && cacheArray.io.responseValid) {
    when(requestKilled || io.kill || newInvalidate) {
      state := L1InstructionCacheState.idle
    }.elsewhen(cacheArray.io.hit) {
      responseValid := True
      response := hitResponseByWay(cacheArray.io.hitWay)
      state := Mux(
        lookupHitTurnoverFire,
        L1InstructionCacheState.lookup,
        L1InstructionCacheState.idle
      )
    }.otherwise {
      victimWay := cacheArray.io.victimWay
      state := L1InstructionCacheState.refillRequest
    }
  }
  when(
    state === L1InstructionCacheState.maintenanceHitLookup &&
      cacheArray.io.responseValid
  ) {
    when(cacheArray.io.hit) {
      maintenanceWay := cacheArray.io.hitWay
      state := L1InstructionCacheState.maintenanceInvalidate
    }.otherwise {
      maintenanceDone := True
      state := L1InstructionCacheState.idle
    }
  }

  when(state === L1InstructionCacheState.refillRequest && io.lineReadReady) {
    refillMask := 0
    refillError := False
    refillResponseSent := False
    state := L1InstructionCacheState.refillData
  }

  val refillBeatFire = io.lineReadBeatValid && io.lineReadBeatReady
  val refillLineWithAcceptedBeat = Bits(CacheContract.LineBits bits)
  for (beat <- 0 until CacheContract.BeatsPerLine) {
    refillLineWithAcceptedBeat(
      beat * CacheContract.BeatBits + CacheContract.BeatBits - 1 downto
        beat * CacheContract.BeatBits
    ) := Mux(
      refillBeatFire && io.lineReadBeat.beat === beat,
      io.lineReadBeat.data,
      refillBeats(beat)
    )
  }
  val requestedGroup = request.physicalAddress(offsetWidth - 1 downto fetchGroupOffsetWidth)
  val requestedBeatBase = (requestedGroup ## U(0, 1 bits)).asUInt
  val requestedBeatMask = (B(3, CacheContract.BeatsPerLine bits) |<< requestedBeatBase).resized
  val acceptedBeatMask = Mux(
    refillBeatFire,
    UIntToOh(io.lineReadBeat.beat, CacheContract.BeatsPerLine),
    B(0, CacheContract.BeatsPerLine bits)
  )
  val refillMaskWithAcceptedBeat = refillMask | acceptedBeatMask
  val refillRequestGroup =
    io.request.physicalAddress(offsetWidth - 1 downto fetchGroupOffsetWidth)
  val refillRequestBeatBase = (refillRequestGroup ## U(0, 1 bits)).asUInt
  val refillRequestBeatMask =
    (B(3, CacheContract.BeatsPerLine bits) |<< refillRequestBeatBase).resized
  val refillRequestGroupReady =
    (refillMaskWithAcceptedBeat & refillRequestBeatMask) === refillRequestBeatMask
  when(refillReplayPending) {
    refillReplayPending := False
    when(!requestKilled && !io.kill) {
      responseValid := True
      writeResponse(request, selectFetchGroup(refillLine, request.physicalAddress), refillError)
      refillResponseSent := True
    }
  }
  when(refillBeatFire) {
    refillBeats(io.lineReadBeat.beat) := io.lineReadBeat.data
    refillError := refillError || io.lineReadBeat.error
    refillMask := refillMaskWithAcceptedBeat
    val requestedGroupReady =
      (refillMaskWithAcceptedBeat & requestedBeatMask) === requestedBeatMask
    when(requestedGroupReady && !refillResponseSent && !requestKilled && !io.kill) {
      responseValid := True
      writeResponse(
        request,
        selectFetchGroup(refillLineWithAcceptedBeat, request.physicalAddress),
        refillError || io.lineReadBeat.error
      )
      refillResponseSent := True
    }
    when(refillMaskWithAcceptedBeat.andR) {
      state := Mux(
        refillError || io.lineReadBeat.error,
        L1InstructionCacheState.idle,
        L1InstructionCacheState.install
      )
    }
  }
  when(refillRequestFire && refillRequestGroupReady) {
    refillReplayPending := True
    refillResponseSent := True
  }

  when(state === L1InstructionCacheState.install) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeWay := victimWay
    cacheArray.io.writeData := refillLine
    cacheArray.io.writeEntryValid := True
    cacheArray.io.writeDirty := False
    when(!refillResponseSent && !requestKilled && !io.kill) {
      responseValid := True
      writeResponse(request, selectFetchGroup(refillLine, request.physicalAddress), refillError)
    }
    state := L1InstructionCacheState.idle
  }

  when(state === L1InstructionCacheState.maintenanceInvalidate) {
    cacheArray.io.writeValid := True
    cacheArray.io.writeIndex := maintenanceIndex
    cacheArray.io.writeWay := maintenanceWay
    cacheArray.io.writeTag := 0
    cacheArray.io.writeData := 0
    cacheArray.io.writeEntryValid := False
    cacheArray.io.writeDirty := False
    maintenanceDone := True
    state := L1InstructionCacheState.idle
  }

  io.invalidateBusy := cacheArray.io.invalidateBusy || invalidateRequest ||
    state === L1InstructionCacheState.maintenanceHitLookup ||
    state === L1InstructionCacheState.maintenanceInvalidate
  val idleNow = state === L1InstructionCacheState.idle && !invalidateRequest &&
    !cacheArray.io.invalidateBusy && !responseValid && !io.requestValid &&
    !io.invalidate && !io.maintenanceRequest.valid && !maintenanceDone
  io.idle := RegNext(idleNow) init (False)
}
