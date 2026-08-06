package miku.memory

import miku.core._
import spinal.core._
import spinal.lib._

object OooSharedCacheMaintenanceState extends SpinalEnum {
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
final class OooSharedCacheHierarchy(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val instructionRequestValid = in Bool ()
    val instructionUncachedRequestValid = in Bool ()
    val instructionRequest = in(OooInstructionCacheRequest(config))
    val instructionRequestReady = out Bool ()
    val instructionRequestCapacityReady = out Bool ()
    val instructionHitResponsePending = out Bool ()
    val instructionResponseValid = out Bool ()
    val instructionResponse = out(OooInstructionCacheResponse(config))
    val instructionKill = in Bool ()

    val dataRequestValid = in Bool ()
    val dataRequest = in(OooCacheRequest(config))
    val dataRequestReady = out Bool ()
    val dataResponseValid = out Bool ()
    val dataResponse = out(OooCacheResponse(config))

    val uncachedInstructionRequestValid = out Bool ()
    val uncachedInstructionRequest = out(OooInstructionCacheRequest(config))
    val uncachedInstructionRequestReady = in Bool ()
    val uncachedInstructionResponseValid = in Bool ()
    val uncachedInstructionResponse = in(OooInstructionCacheResponse(config))

    val uncachedDataRequestValid = out Bool ()
    val uncachedDataRequest = out(OooCacheRequest(config))
    val uncachedDataRequestReady = in Bool ()
    val uncachedDataResponseValid = in Bool ()
    val uncachedDataResponse = in(OooCacheResponse(config))

    val memoryReadValid = out Bool ()
    val memoryRead = out(OooLineReadRequest(config))
    val memoryReadReady = in Bool ()
    val memoryReadBeatValid = in Bool ()
    val memoryReadBeat = in(OooLineReadBeat(config))
    val memoryReadBeatReady = out Bool ()

    val memoryWriteValid = out Bool ()
    val memoryWrite = out(OooLineWriteRequest(config))
    val memoryWriteReady = in Bool ()
    val memoryWriteResponseValid = in Bool ()
    val memoryWriteResponse = in(OooLineWriteResponse(config))

    val invalidate = in Bool ()
    val dataInvalidate = in Bool ()
    val dataWritebackInvalidate = in Bool ()
    val level2Invalidate = in Bool ()
    val invalidateBusy = out Bool ()
    val barrierDrain = in Bool ()
    val instructionBarrierMaintenanceStart = in Bool ()
    val instructionBarrierMaintenanceReady = out Bool ()
    val instructionBarrierMaintenanceDone = out Bool ()
    val cacheMaintenanceRequest = slave(Stream(OooCacheMaintenanceRequest(config)))
    val cacheMaintenanceResponse = master(Stream(OooCacheMaintenanceResponse(config)))
    val idle = out Bool ()
  }

  val l1i = new OooL1InstructionCache(config)
  val l1d = new OooL1DataCache(config)
  val readMshrs = new OooSharedReadMshrRouter(config)
  val l2 = new OooL2Cache(config)
  // L1D and uncached AXI responses are pulse interfaces. Preserve the L1D
  // response when both sources complete on the same cycle while keeping the
  // normal single-source path combinational.
  val deferredDataResponseValid = RegInit(False)
  val deferredDataResponse = Reg(OooCacheResponse(config))
  val maintenanceState = RegInit(OooSharedCacheMaintenanceState.idle)
  val maintenanceIncludesInstruction = RegInit(False)
  val exactMaintenanceRequest = Reg(OooCacheMaintenanceRequest(config))
  val uncachedWriteRequest = Reg(OooCacheRequest(config))
  val maintenanceSeen = RegInit(False)
  val newDataWritebackInvalidate = io.dataWritebackInvalidate && !maintenanceSeen
  when(io.dataWritebackInvalidate) { maintenanceSeen := True }
    .otherwise { maintenanceSeen := False }
  when(newDataWritebackInvalidate && maintenanceState === OooSharedCacheMaintenanceState.idle) {
    maintenanceIncludesInstruction := False
    maintenanceState := OooSharedCacheMaintenanceState.kickDataL1
  }
  val instructionBarrierMaintenanceFire = io.instructionBarrierMaintenanceStart &&
    io.instructionBarrierMaintenanceReady
  when(instructionBarrierMaintenanceFire) {
    maintenanceIncludesInstruction := True
    maintenanceState := OooSharedCacheMaintenanceState.kickDataL1
  }
  val exactMaintenanceReady = maintenanceState === OooSharedCacheMaintenanceState.idle &&
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
      is(OooCacheMaintenanceTarget.instructionL1) {
        maintenanceState := OooSharedCacheMaintenanceState.kickExactInstructionL1
      }
      is(OooCacheMaintenanceTarget.dataL1) {
        maintenanceState := OooSharedCacheMaintenanceState.kickExactDataL1
      }
      is(OooCacheMaintenanceTarget.unifiedL2) {
        maintenanceState := OooSharedCacheMaintenanceState.kickExactUnifiedL2
      }
      default {
        maintenanceState := OooSharedCacheMaintenanceState.respondExact
      }
    }
    when(io.cacheMaintenanceRequest.code(4 downto 3).asUInt === U(3, 2 bits)) {
      maintenanceState := OooSharedCacheMaintenanceState.respondExact
    }
  }
  val dataUncached = io.dataRequestValid && io.dataRequest.uncached
  val uncachedWriteCaptureReady = exactMaintenanceReady &&
    !io.cacheMaintenanceRequest.valid
  val uncachedWriteCapture = dataUncached && io.dataRequest.isWrite &&
    uncachedWriteCaptureReady
  when(uncachedWriteCapture) {
    uncachedWriteRequest := io.dataRequest
    maintenanceState := OooSharedCacheMaintenanceState.kickUncachedDataL1
  }
  when(maintenanceState === OooSharedCacheMaintenanceState.kickDataL1) {
    maintenanceState := OooSharedCacheMaintenanceState.waitDataL1
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitDataL1 &&
      !l1d.io.invalidateBusy
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.kickDataL2
  }
  when(maintenanceState === OooSharedCacheMaintenanceState.kickDataL2) {
    maintenanceState := OooSharedCacheMaintenanceState.waitDataL2
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitDataL2 &&
      !l2.io.invalidateBusy
  ) {
    maintenanceState := Mux(
      maintenanceIncludesInstruction,
      OooSharedCacheMaintenanceState.kickInstructionL1,
      OooSharedCacheMaintenanceState.idle
    )
  }
  when(maintenanceState === OooSharedCacheMaintenanceState.kickInstructionL1) {
    maintenanceState := OooSharedCacheMaintenanceState.waitInstructionL1
  }
  val instructionBarrierMaintenanceDone =
    maintenanceState === OooSharedCacheMaintenanceState.waitInstructionL1 &&
      !l1i.io.invalidateBusy
  when(instructionBarrierMaintenanceDone) {
    maintenanceIncludesInstruction := False
    maintenanceState := OooSharedCacheMaintenanceState.idle
  }
  l1i.io.maintenanceRequest.valid :=
    maintenanceState === OooSharedCacheMaintenanceState.kickExactInstructionL1
  l1i.io.maintenanceRequest.payload := exactMaintenanceRequest
  when(l1i.io.maintenanceRequest.fire) {
    maintenanceState := OooSharedCacheMaintenanceState.waitExactInstructionL1
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitExactInstructionL1 &&
      l1i.io.maintenanceDone
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.respondExact
  }
  val uncachedLineMaintenanceRequest = OooCacheMaintenanceRequest(config)
  uncachedLineMaintenanceRequest.code := B(0x11, 5 bits)
  uncachedLineMaintenanceRequest.virtualAddress := uncachedWriteRequest.virtualAddress
  uncachedLineMaintenanceRequest.physicalAddress := uncachedWriteRequest.physicalAddress
  uncachedLineMaintenanceRequest.robPointer := uncachedWriteRequest.robPointer
  uncachedLineMaintenanceRequest.recoveryEpoch := uncachedWriteRequest.recoveryEpoch

  val kickExactDataL1 =
    maintenanceState === OooSharedCacheMaintenanceState.kickExactDataL1
  val kickUncachedDataL1 =
    maintenanceState === OooSharedCacheMaintenanceState.kickUncachedDataL1
  l1d.io.maintenanceRequest.valid := kickExactDataL1 || kickUncachedDataL1
  l1d.io.maintenanceRequest.payload := Mux(
    kickUncachedDataL1,
    uncachedLineMaintenanceRequest,
    exactMaintenanceRequest
  )
  when(l1d.io.maintenanceRequest.fire) {
    maintenanceState := Mux(
      kickUncachedDataL1,
      OooSharedCacheMaintenanceState.waitUncachedDataL1,
      OooSharedCacheMaintenanceState.waitExactDataL1
    )
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitExactDataL1 &&
      l1d.io.maintenanceDone
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.respondExact
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitUncachedDataL1 &&
      l1d.io.maintenanceDone
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.kickUncachedDataL2
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
    maintenanceState === OooSharedCacheMaintenanceState.kickExactUnifiedL2
  val kickUncachedDataL2 =
    maintenanceState === OooSharedCacheMaintenanceState.kickUncachedDataL2
  l2.io.maintenanceRequest.valid := kickExactUnifiedL2 || kickUncachedDataL2
  l2.io.maintenanceRequest.payload := Mux(
    kickUncachedDataL2,
    uncachedLevel2MaintenanceRequest,
    exactMaintenanceRequest
  )
  when(l2.io.maintenanceRequest.fire) {
    maintenanceState := Mux(
      kickUncachedDataL2,
      OooSharedCacheMaintenanceState.waitUncachedDataL2,
      OooSharedCacheMaintenanceState.waitExactUnifiedL2
    )
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitExactUnifiedL2 &&
      l2.io.maintenanceDone
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.respondExact
  }
  when(
    maintenanceState === OooSharedCacheMaintenanceState.waitUncachedDataL2 &&
      l2.io.maintenanceDone
  ) {
    maintenanceState := OooSharedCacheMaintenanceState.forwardUncachedWrite
  }
  io.cacheMaintenanceResponse.valid :=
    maintenanceState === OooSharedCacheMaintenanceState.respondExact
  io.cacheMaintenanceResponse.robPointer := exactMaintenanceRequest.robPointer
  io.cacheMaintenanceResponse.recoveryEpoch := exactMaintenanceRequest.recoveryEpoch
  when(io.cacheMaintenanceResponse.fire) {
    maintenanceState := OooSharedCacheMaintenanceState.idle
  }
  val hierarchyMaintenanceBusy = l1i.io.invalidateBusy || l1d.io.invalidateBusy ||
    l2.io.invalidateBusy || maintenanceState =/= OooSharedCacheMaintenanceState.idle

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
    maintenanceState === OooSharedCacheMaintenanceState.forwardUncachedWrite
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
    maintenanceState := OooSharedCacheMaintenanceState.idle
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
    maintenanceState === OooSharedCacheMaintenanceState.kickInstructionL1
  l1d.io.invalidate := io.dataInvalidate
  l1d.io.writebackInvalidate := maintenanceState ===
    OooSharedCacheMaintenanceState.kickDataL1
  l2.io.writebackInvalidate := maintenanceState ===
    OooSharedCacheMaintenanceState.kickDataL2
  l2.io.invalidate := io.invalidate || io.dataInvalidate || io.level2Invalidate
  io.invalidateBusy := hierarchyMaintenanceBusy
  io.instructionBarrierMaintenanceReady :=
    maintenanceState === OooSharedCacheMaintenanceState.idle &&
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
    maintenanceState === OooSharedCacheMaintenanceState.idle &&
    !deferredDataResponseValid &&
    instructionIngressIdle &&
    !io.dataRequestValid && !io.instructionBarrierMaintenanceStart &&
    !io.cacheMaintenanceRequest.valid &&
    !io.invalidate && !io.dataInvalidate && !io.dataWritebackInvalidate &&
    !io.level2Invalidate
  io.idle := RegNext(idleNow) init (False)
}
