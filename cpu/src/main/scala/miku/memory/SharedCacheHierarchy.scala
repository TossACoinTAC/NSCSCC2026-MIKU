package miku.memory

import miku.core._
import spinal.core._
import spinal.lib._

object SharedCacheMaintenanceState extends SpinalEnum {
  val idle, kickDataL1, waitDataL1, kickDataL2, waitDataL2, kickInstructionL1,
    waitInstructionL1, kickExactInstructionL1, waitExactInstructionL1,
    kickExactDataL1, waitExactDataL1, kickExactUnifiedL2, waitExactUnifiedL2,
    respondExact, kickUncachedDataL1, waitUncachedDataL1, kickUncachedDataL2,
    waitUncachedDataL2, forwardUncachedWrite = newElement()
}

/** Private L1I/L1D hierarchy sharing one nonblocking 64-byte-line L2 cache.
  *
  * Dirty L1D writebacks have priority. Four hierarchy-global read identities preserve the owning L1
  * and its local MSHR id across arbitrarily interleaved L2 response beats.
  */
final class SharedCacheHierarchy(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val instructionRequestValid = in Bool ()
    val instructionUncachedRequestValid = in Bool ()
    val instructionRequest = in(InstructionCacheRequest(config))
    val instructionRequestReady = out Bool ()
    val instructionRequestCapacityReady = out Bool ()
    val instructionHitResponsePending = out Bool ()
    val instructionResponseValid = out Bool ()
    val instructionResponse = out(InstructionCacheResponse(config))
    val instructionKill = in Bool ()

    val dataRequestValid = in Bool ()
    val dataRequest = in(CacheRequest(config))
    val dataRequestReady = out Bool ()
    val dataResponseValid = out Bool ()
    val dataResponse = out(CacheResponse(config))

    val uncachedInstructionRequestValid = out Bool ()
    val uncachedInstructionRequest = out(InstructionCacheRequest(config))
    val uncachedInstructionRequestReady = in Bool ()
    val uncachedInstructionResponseValid = in Bool ()
    val uncachedInstructionResponse = in(InstructionCacheResponse(config))

    val uncachedDataRequestValid = out Bool ()
    val uncachedDataRequest = out(CacheRequest(config))
    val uncachedDataRequestReady = in Bool ()
    val uncachedDataResponseValid = in Bool ()
    val uncachedDataResponse = in(CacheResponse(config))

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
    val dataInvalidate = in Bool ()
    val dataWritebackInvalidate = in Bool ()
    val level2Invalidate = in Bool ()
    val invalidateBusy = out Bool ()
    val barrierDrain = in Bool ()
    val instructionBarrierMaintenanceStart = in Bool ()
    val instructionBarrierMaintenanceReady = out Bool ()
    val instructionBarrierMaintenanceDone = out Bool ()
    val cacheMaintenanceRequest = slave(Stream(CacheMaintenanceRequest(config)))
    val cacheMaintenanceResponse = master(Stream(CacheMaintenanceResponse(config)))
    val idle = out Bool ()
  }

  val l1i = new L1InstructionCache(config)
  val l1d = new L1DataCache(config)
  val readMshrs = new SharedReadMshrRouter(config)
  val l2 = new L2Cache(config)
  // L1D and uncached AXI responses are pulse interfaces. Preserve the L1D
  // response when both sources complete on the same cycle while keeping the
  // normal single-source path combinational.
  val deferredDataResponseValid = RegInit(False)
  val deferredDataResponse = Reg(CacheResponse(config))
  val maintenanceState = RegInit(SharedCacheMaintenanceState.idle)
  val maintenanceIncludesInstruction = RegInit(False)
  val exactMaintenanceRequest = Reg(CacheMaintenanceRequest(config))
  val uncachedWriteRequest = Reg(CacheRequest(config))
  val maintenanceSeen = RegInit(False)
  val newDataWritebackInvalidate = io.dataWritebackInvalidate && !maintenanceSeen
  when(io.dataWritebackInvalidate) { maintenanceSeen := True }
    .otherwise { maintenanceSeen := False }
  when(newDataWritebackInvalidate && maintenanceState === SharedCacheMaintenanceState.idle) {
    maintenanceIncludesInstruction := False
    maintenanceState := SharedCacheMaintenanceState.kickDataL1
  }
  val instructionBarrierMaintenanceFire = io.instructionBarrierMaintenanceStart &&
    io.instructionBarrierMaintenanceReady
  when(instructionBarrierMaintenanceFire) {
    maintenanceIncludesInstruction := True
    maintenanceState := SharedCacheMaintenanceState.kickDataL1
  }
  val exactMaintenanceReady = maintenanceState === SharedCacheMaintenanceState.idle &&
    l1i.io.idle && l1d.io.idle && readMshrs.io.idle && l2.io.idle &&
    !deferredDataResponseValid &&
    !newDataWritebackInvalidate && !io.invalidate && !io.dataInvalidate &&
    !io.level2Invalidate && !io.instructionBarrierMaintenanceStart
  io.cacheMaintenanceRequest.ready := exactMaintenanceReady
  val exactMaintenanceFire = io.cacheMaintenanceRequest.valid &&
    io.cacheMaintenanceRequest.ready
  when(exactMaintenanceFire) {
    exactMaintenanceRequest := io.cacheMaintenanceRequest.payload
    switch(io.cacheMaintenanceRequest.code(2 downto 0).asUInt) {
      is(CacheMaintenanceTarget.instructionL1) {
        maintenanceState := SharedCacheMaintenanceState.kickExactInstructionL1
      }
      is(CacheMaintenanceTarget.dataL1) {
        maintenanceState := SharedCacheMaintenanceState.kickExactDataL1
      }
      is(CacheMaintenanceTarget.unifiedL2) {
        maintenanceState := SharedCacheMaintenanceState.kickExactUnifiedL2
      }
      default {
        maintenanceState := SharedCacheMaintenanceState.respondExact
      }
    }
    when(io.cacheMaintenanceRequest.code(4 downto 3).asUInt === U(3, 2 bits)) {
      maintenanceState := SharedCacheMaintenanceState.respondExact
    }
  }
  val dataUncached = io.dataRequestValid && io.dataRequest.uncached
  val uncachedWriteCaptureReady = exactMaintenanceReady &&
    !io.cacheMaintenanceRequest.valid
  val uncachedWriteCapture = dataUncached && io.dataRequest.isWrite &&
    uncachedWriteCaptureReady
  when(uncachedWriteCapture) {
    uncachedWriteRequest := io.dataRequest
    maintenanceState := SharedCacheMaintenanceState.kickUncachedDataL1
  }
  when(maintenanceState === SharedCacheMaintenanceState.kickDataL1) {
    maintenanceState := SharedCacheMaintenanceState.waitDataL1
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitDataL1 &&
      !l1d.io.invalidateBusy
  ) {
    maintenanceState := SharedCacheMaintenanceState.kickDataL2
  }
  when(maintenanceState === SharedCacheMaintenanceState.kickDataL2) {
    maintenanceState := SharedCacheMaintenanceState.waitDataL2
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitDataL2 &&
      !l2.io.invalidateBusy
  ) {
    maintenanceState := Mux(
      maintenanceIncludesInstruction,
      SharedCacheMaintenanceState.kickInstructionL1,
      SharedCacheMaintenanceState.idle
    )
  }
  when(maintenanceState === SharedCacheMaintenanceState.kickInstructionL1) {
    maintenanceState := SharedCacheMaintenanceState.waitInstructionL1
  }
  val instructionBarrierMaintenanceDone =
    maintenanceState === SharedCacheMaintenanceState.waitInstructionL1 &&
      !l1i.io.invalidateBusy
  when(instructionBarrierMaintenanceDone) {
    maintenanceIncludesInstruction := False
    maintenanceState := SharedCacheMaintenanceState.idle
  }
  l1i.io.maintenanceRequest.valid :=
    maintenanceState === SharedCacheMaintenanceState.kickExactInstructionL1
  l1i.io.maintenanceRequest.payload := exactMaintenanceRequest
  when(l1i.io.maintenanceRequest.fire) {
    maintenanceState := SharedCacheMaintenanceState.waitExactInstructionL1
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitExactInstructionL1 &&
      l1i.io.maintenanceDone
  ) {
    maintenanceState := SharedCacheMaintenanceState.respondExact
  }
  val uncachedLineMaintenanceRequest = CacheMaintenanceRequest(config)
  uncachedLineMaintenanceRequest.code := B(0x11, 5 bits)
  uncachedLineMaintenanceRequest.virtualAddress := uncachedWriteRequest.virtualAddress
  uncachedLineMaintenanceRequest.physicalAddress := uncachedWriteRequest.physicalAddress
  uncachedLineMaintenanceRequest.robPointer := uncachedWriteRequest.robPointer
  uncachedLineMaintenanceRequest.recoveryEpoch := uncachedWriteRequest.recoveryEpoch

  val kickExactDataL1 =
    maintenanceState === SharedCacheMaintenanceState.kickExactDataL1
  val kickUncachedDataL1 =
    maintenanceState === SharedCacheMaintenanceState.kickUncachedDataL1
  l1d.io.maintenanceRequest.valid := kickExactDataL1 || kickUncachedDataL1
  l1d.io.maintenanceRequest.payload := Mux(
    kickUncachedDataL1,
    uncachedLineMaintenanceRequest,
    exactMaintenanceRequest
  )
  when(l1d.io.maintenanceRequest.fire) {
    maintenanceState := Mux(
      kickUncachedDataL1,
      SharedCacheMaintenanceState.waitUncachedDataL1,
      SharedCacheMaintenanceState.waitExactDataL1
    )
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitExactDataL1 &&
      l1d.io.maintenanceDone
  ) {
    maintenanceState := SharedCacheMaintenanceState.respondExact
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitUncachedDataL1 &&
      l1d.io.maintenanceDone
  ) {
    maintenanceState := SharedCacheMaintenanceState.kickUncachedDataL2
  }

  val uncachedLevel2MaintenanceRequest = cloneOf(uncachedLineMaintenanceRequest)
  uncachedLevel2MaintenanceRequest.code := B(0x12, 5 bits)
  uncachedLevel2MaintenanceRequest.virtualAddress :=
    uncachedLineMaintenanceRequest.virtualAddress
  uncachedLevel2MaintenanceRequest.physicalAddress :=
    uncachedLineMaintenanceRequest.physicalAddress
  uncachedLevel2MaintenanceRequest.robPointer :=
    uncachedLineMaintenanceRequest.robPointer
  uncachedLevel2MaintenanceRequest.recoveryEpoch :=
    uncachedLineMaintenanceRequest.recoveryEpoch
  val kickExactUnifiedL2 =
    maintenanceState === SharedCacheMaintenanceState.kickExactUnifiedL2
  val kickUncachedDataL2 =
    maintenanceState === SharedCacheMaintenanceState.kickUncachedDataL2
  l2.io.maintenanceRequest.valid := kickExactUnifiedL2 || kickUncachedDataL2
  l2.io.maintenanceRequest.payload := Mux(
    kickUncachedDataL2,
    uncachedLevel2MaintenanceRequest,
    exactMaintenanceRequest
  )
  when(l2.io.maintenanceRequest.fire) {
    maintenanceState := Mux(
      kickUncachedDataL2,
      SharedCacheMaintenanceState.waitUncachedDataL2,
      SharedCacheMaintenanceState.waitExactUnifiedL2
    )
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitExactUnifiedL2 &&
      l2.io.maintenanceDone
  ) {
    maintenanceState := SharedCacheMaintenanceState.respondExact
  }
  when(
    maintenanceState === SharedCacheMaintenanceState.waitUncachedDataL2 &&
      l2.io.maintenanceDone
  ) {
    maintenanceState := SharedCacheMaintenanceState.forwardUncachedWrite
  }
  io.cacheMaintenanceResponse.valid :=
    maintenanceState === SharedCacheMaintenanceState.respondExact
  io.cacheMaintenanceResponse.robPointer := exactMaintenanceRequest.robPointer
  io.cacheMaintenanceResponse.recoveryEpoch := exactMaintenanceRequest.recoveryEpoch
  when(io.cacheMaintenanceResponse.fire) {
    maintenanceState := SharedCacheMaintenanceState.idle
  }
  val hierarchyMaintenanceBusy = l1i.io.invalidateBusy || l1d.io.invalidateBusy ||
    l2.io.invalidateBusy || maintenanceState =/= SharedCacheMaintenanceState.idle

  l1i.io.requestValid := io.instructionRequestValid && !io.barrierDrain
  l1i.io.request := io.instructionRequest
  io.uncachedInstructionRequestValid := io.instructionUncachedRequestValid &&
    !hierarchyMaintenanceBusy && !io.barrierDrain
  io.uncachedInstructionRequest := io.instructionRequest
  io.instructionRequestReady := !hierarchyMaintenanceBusy && !io.barrierDrain && Mux(
    io.instructionRequest.uncached,
    io.uncachedInstructionRequestReady,
    l1i.io.requestReady
  )
  io.instructionRequestCapacityReady :=
    !hierarchyMaintenanceBusy && !io.barrierDrain && Mux(
      io.instructionRequest.uncached,
      io.uncachedInstructionRequestReady,
      l1i.io.requestCapacityReady
    )
  io.instructionHitResponsePending := l1i.io.hitResponsePending &&
    !hierarchyMaintenanceBusy && !io.barrierDrain
  io.instructionResponseValid := l1i.io.responseValid || io.uncachedInstructionResponseValid
  io.instructionResponse := io.uncachedInstructionResponse
  // A killed uncached AXI transaction may return in the same cycle as a new L1I hit.  The
  // frontend can discard the stale uncached response by PC, but it cannot recover an L1I pulse
  // hidden behind that response, so the live private-cache response has priority.
  when(l1i.io.responseValid) {
    io.instructionResponse := l1i.io.response
  }
  l1i.io.kill := io.instructionKill

  l1d.io.requestValid := io.dataRequestValid && !io.dataRequest.uncached
  l1d.io.request := io.dataRequest
  val forwardUncachedWrite =
    maintenanceState === SharedCacheMaintenanceState.forwardUncachedWrite
  io.uncachedDataRequestValid :=
    (dataUncached && !io.dataRequest.isWrite && !hierarchyMaintenanceBusy) ||
      forwardUncachedWrite
  io.uncachedDataRequest := Mux(
    forwardUncachedWrite,
    uncachedWriteRequest,
    io.dataRequest
  )
  io.dataRequestReady := Mux(
    io.dataRequest.uncached,
    Mux(
      io.dataRequest.isWrite,
      uncachedWriteCaptureReady,
      !hierarchyMaintenanceBusy && io.uncachedDataRequestReady
    ),
    !hierarchyMaintenanceBusy && l1d.io.requestReady
  )
  when(forwardUncachedWrite && io.uncachedDataRequestReady) {
    maintenanceState := SharedCacheMaintenanceState.idle
  }
  val cachedDataResponseValid = l1d.io.responseValid
  val uncachedDataResponseValid = io.uncachedDataResponseValid
  io.dataResponseValid := deferredDataResponseValid || cachedDataResponseValid ||
    uncachedDataResponseValid
  io.dataResponse := l1d.io.response
  when(uncachedDataResponseValid) { io.dataResponse := io.uncachedDataResponse }
  when(deferredDataResponseValid) { io.dataResponse := deferredDataResponse }

  when(deferredDataResponseValid) {
    GenerationFlags.simulation {
      assert(
        !(cachedDataResponseValid && uncachedDataResponseValid),
        "data response skid cannot accept two new responses while occupied"
      )
    }
    when(cachedDataResponseValid || uncachedDataResponseValid) {
      deferredDataResponse := Mux(
        uncachedDataResponseValid,
        io.uncachedDataResponse,
        l1d.io.response
      )
    }.otherwise {
      deferredDataResponseValid := False
    }
  }.elsewhen(cachedDataResponseValid && uncachedDataResponseValid) {
    // Preserve existing uncached priority and defer the otherwise lost L1D pulse.
    deferredDataResponseValid := True
    deferredDataResponse := l1d.io.response
  }

  l2.io.writeValid := l1d.io.lineWriteValid
  l2.io.write := l1d.io.lineWrite
  l1d.io.lineWriteReady := l2.io.writeReady
  l1d.io.lineWriteResponseValid := l2.io.writeResponseValid
  l1d.io.lineWriteResponse := l2.io.writeResponse

  readMshrs.io.instructionReadValid := l1i.io.lineReadValid
  readMshrs.io.instructionRead := l1i.io.lineRead
  l1i.io.lineReadReady := readMshrs.io.instructionReadReady
  l1i.io.lineReadBeatValid := readMshrs.io.instructionReadBeatValid
  l1i.io.lineReadBeat := readMshrs.io.instructionReadBeat
  readMshrs.io.instructionReadBeatReady := l1i.io.lineReadBeatReady

  readMshrs.io.dataReadValid := l1d.io.lineReadValid
  readMshrs.io.dataRead := l1d.io.lineRead
  l1d.io.lineReadReady := readMshrs.io.dataReadReady
  l1d.io.lineReadBeatValid := readMshrs.io.dataReadBeatValid
  l1d.io.lineReadBeat := readMshrs.io.dataReadBeat
  readMshrs.io.dataReadBeatReady := l1d.io.lineReadBeatReady

  l2.io.readValid := readMshrs.io.lowerReadValid
  l2.io.read := readMshrs.io.lowerRead
  readMshrs.io.lowerReadReady := l2.io.readReady
  readMshrs.io.lowerReadBeatValid := l2.io.readBeatValid
  readMshrs.io.lowerReadBeat := l2.io.readBeat
  l2.io.readBeatReady := readMshrs.io.lowerReadBeatReady

  io.memoryReadValid := l2.io.memoryReadValid
  io.memoryRead := l2.io.memoryRead
  l2.io.memoryReadReady := io.memoryReadReady
  l2.io.memoryReadBeatValid := io.memoryReadBeatValid
  l2.io.memoryReadBeat := io.memoryReadBeat
  io.memoryReadBeatReady := l2.io.memoryReadBeatReady

  io.memoryWriteValid := l2.io.memoryWriteValid
  io.memoryWrite := l2.io.memoryWrite
  l2.io.memoryWriteReady := io.memoryWriteReady
  l2.io.memoryWriteResponseValid := io.memoryWriteResponseValid
  l2.io.memoryWriteResponse := io.memoryWriteResponse

  l1i.io.invalidate := io.invalidate ||
    maintenanceState === SharedCacheMaintenanceState.kickInstructionL1
  l1d.io.invalidate := io.dataInvalidate
  l1d.io.writebackInvalidate := maintenanceState ===
    SharedCacheMaintenanceState.kickDataL1
  l2.io.writebackInvalidate := maintenanceState ===
    SharedCacheMaintenanceState.kickDataL2
  l2.io.invalidate := io.invalidate || io.dataInvalidate || io.level2Invalidate
  io.invalidateBusy := hierarchyMaintenanceBusy
  io.instructionBarrierMaintenanceReady :=
    maintenanceState === SharedCacheMaintenanceState.idle &&
      !hierarchyMaintenanceBusy && !newDataWritebackInvalidate &&
      !io.invalidate && !io.dataInvalidate && !io.level2Invalidate
  io.instructionBarrierMaintenanceDone := instructionBarrierMaintenanceDone
  // A barrier deliberately backpressures the frontend before waiting for the
  // hierarchy to drain.  The frontend must keep an unaccepted request valid,
  // so that boundary payload is not outstanding cache work while barrierDrain
  // prevents it from entering either instruction path.
  val instructionIngressIdle = io.barrierDrain ||
    (!io.instructionRequestValid && !io.instructionUncachedRequestValid)
  val idleNow = l1i.io.idle && l1d.io.idle && readMshrs.io.idle && l2.io.idle &&
    maintenanceState === SharedCacheMaintenanceState.idle &&
    !deferredDataResponseValid &&
    instructionIngressIdle &&
    !io.dataRequestValid && !io.instructionBarrierMaintenanceStart &&
    !io.cacheMaintenanceRequest.valid &&
    !io.invalidate && !io.dataInvalidate && !io.dataWritebackInvalidate &&
    !io.level2Invalidate
  io.idle := RegNext(idleNow) init (False)
}
