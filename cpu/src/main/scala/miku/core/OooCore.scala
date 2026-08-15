package miku.core

import miku.backend._
import miku.frontend._
import miku.memory._
import miku.observe.PerfObservationV1
import miku.predict._
import miku.privileged._
import spinal.core._
import spinal.lib._

private[core] final case class RetiredPredictorUpdate(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val taken = Bool()
  val target = UInt(config.xlen bits)
  val branchType = UInt(PredictedBranchType.Width bits)
  val metadata = Bits(16 bits)
  val isCall = Bool()
  val isReturn = Bool()
}

/** Lossless width adapter from three-wide retirement to the predictor's single table-write port. */
private[core] final class PredictorUpdateQueue(
    config: OooCoreConfig,
    depth: Int = 8
) extends Component {
  require(isPow2(depth))
  require(depth >= config.commitWidth * 2)
  private val pointerWidth = log2Up(depth)
  private val countWidth = log2Up(depth + 1)
  private val capacityWidth = log2Up(config.commitWidth + 1)

  val io = new Bundle {
    val pushValid = in Bits (config.commitWidth bits)
    val push = in Vec (RetiredPredictorUpdate(config), config.commitWidth)
    val pushCapacity = out UInt (capacityWidth bits)
    val popValid = out Bool ()
    val pop = out(RetiredPredictorUpdate(config))
    val popReady = in Bool ()
    val occupancy = out UInt (countWidth bits)
  }

  val entries = Vec.fill(depth)(Reg(RetiredPredictorUpdate(config)))
  val head = Reg(UInt(pointerWidth bits)) init (0)
  val tail = Reg(UInt(pointerWidth bits)) init (0)
  val count = Reg(UInt(countWidth bits)) init (0)

  io.popValid := count =/= 0
  io.pop := entries(head)
  io.occupancy := count
  val popFire = io.popValid && io.popReady

  val available = UInt(countWidth bits)
  available := U(depth, countWidth bits) - count + popFire.asUInt
  io.pushCapacity := Mux(
    available >= U(config.commitWidth, countWidth bits),
    U(config.commitWidth, capacityWidth bits),
    available.resized
  )

  val pushCount = CountOne(io.pushValid)
  for (lane <- 0 until config.commitWidth) {
    val earlierCount = if (lane == 0) {
      U(0, capacityWidth bits)
    } else {
      CountOne(io.pushValid(lane - 1 downto 0)).resize(capacityWidth)
    }
    when(io.pushValid(lane)) {
      entries((tail + earlierCount).resized) := io.push(lane)
    }
  }

  when(popFire) {
    head := head + 1
  }
  when(pushCount =/= 0) {
    tail := tail + pushCount.resized
  }
  count := count + pushCount - popFire.asUInt
}

/** Self-fetching four-issue, three-commit out-of-order core.
  *
  * Branch recovery is handled internally. Precise exception entry and privileged redirects remain
  * explicit inputs until the architectural CSR/MMU block is connected at the final core boundary.
  */
final class OooCore(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit) extends Component {
  val io = new Bundle {
    val instructionTranslationRequest = master(Stream(TranslationRequest(config)))
    val instructionTranslationResponse = slave(Stream(TranslationResponse(config)))
    val dataTranslationRequest = master(Stream(TranslationRequest(config)))
    val dataTranslationResponse = slave(Stream(TranslationResponse(config)))
    val dataTranslationBypassAddress = out UInt (config.xlen bits)
    val dataTranslationBypass = in(TranslationBypass(config))
    val reservationValid = in Bool ()
    val reservationLineAddress = in Bits (config.reservationAddressWidth bits)

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
    val memoryBusIdle = in Bool ()

    val systemReadValid = out Bool ()
    val systemReadAddress = out UInt (14 bits)
    val systemReadData = in Bits (config.xlen bits)
    val timer = in Bits (64 bits)
    val timerId = in Bits (config.xlen bits)
    val debugReadAddress = in UInt (config.archRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)

    val privilege = in Bits (2 bits)
    val interruptPending = in Bool ()
    val exceptionEntryTarget = in UInt (config.xlen bits)
    val tlbRefillTarget = in UInt (config.xlen bits)
    val externalRedirectValid = in Bool ()
    val externalRedirectTarget = in UInt (config.xlen bits)

    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (CommitRecord(config), config.commitWidth)
    val commitMemory = out Vec (MemoryCommitObservation(config), config.commitWidth)
    val recoveryValid = out Bool ()
    val recovery = out(RecoveryRequest(config))
    val debugCommitValid = out Bool ()
    val debugCommit = out(CommitRecord(config))
    val csrWriteValid = out Bool ()
    val csrWriteAddress = out UInt (14 bits)
    val csrWriteData = out Bits (config.xlen bits)
    val csrWriteMask = out Bool ()
    val serialCommitPc = out UInt (config.xlen bits)
    val ertnValid = out Bool ()
    val idleValid = out Bool ()
    val refetchValid = out Bool ()
    val cacheInvalidateValid = out Bool ()
    val dataCacheInvalidateValid = out Bool ()
    val dataCacheWritebackInvalidateValid = out Bool ()
    val level2CacheInvalidateValid = out Bool ()
    val tlbSearchValid = out Bool ()
    val tlbReadValid = out Bool ()
    val tlbWriteValid = out Bool ()
    val tlbFillValid = out Bool ()
    val tlbInvalidateValid = out Bool ()
    val tlbInvalidateAsid = out Bits (10 bits)
    val tlbInvalidateVpn = out Bits (19 bits)
    val tlbInvalidateOperation = out Bits (5 bits)
    val reservationBitSet = out Bool ()
    val reservationBitValue = out Bool ()
    val reservationAddressSet = out Bool ()
    val reservationLineAddressUpdate = out Bits (config.reservationAddressWidth bits)
    val exceptionValid = out Bool ()
    val exceptionPc = out UInt (config.xlen bits)
    val exception = out(ExceptionMetadata())

    val cacheInvalidate = in Bool ()
    val dataCacheInvalidate = in Bool ()
    val dataCacheWritebackInvalidate = in Bool ()
    val level2CacheInvalidate = in Bool ()
    val cacheInvalidateBusy = out Bool ()
    val fetchPc = out UInt (config.xlen bits)
    val frontendOccupancy = out UInt (log2Up(config.instructionBufferEntries + 1) bits)
  }

  val frontend = new OooFrontend(config)
  val decodeRenameBuffer = new DecodeRenameBuffer(config)
  val backend = new OooBackendWithDataCache(config)
  val predictorUpdateQueue = new PredictorUpdateQueue(config)
  backend.io.predictorUpdateCapacity := predictorUpdateQueue.io.pushCapacity

  io.instructionTranslationRequest.valid := frontend.io.translationRequest.valid
  io.instructionTranslationRequest.payload := frontend.io.translationRequest.payload
  frontend.io.translationRequest.ready := io.instructionTranslationRequest.ready
  frontend.io.translationResponse.valid := io.instructionTranslationResponse.valid
  frontend.io.translationResponse.payload := io.instructionTranslationResponse.payload
  io.instructionTranslationResponse.ready := frontend.io.translationResponse.ready
  io.dataTranslationRequest.valid := backend.io.dataTranslationRequest.valid
  io.dataTranslationRequest.payload := backend.io.dataTranslationRequest.payload
  backend.io.dataTranslationRequest.ready := io.dataTranslationRequest.ready
  backend.io.dataTranslationResponse.valid := io.dataTranslationResponse.valid
  backend.io.dataTranslationResponse.payload := io.dataTranslationResponse.payload
  io.dataTranslationResponse.ready := backend.io.dataTranslationResponse.ready
  io.dataTranslationBypassAddress := backend.io.dataTranslationBypassAddress
  backend.io.dataTranslationBypass := io.dataTranslationBypass
  backend.io.reservationValid := io.reservationValid
  backend.io.reservationLineAddress := io.reservationLineAddress

  decodeRenameBuffer.io.inputValid := frontend.io.decodeValid
  decodeRenameBuffer.io.input := frontend.io.decoded
  frontend.io.decodeReady := decodeRenameBuffer.io.inputReady
  backend.io.renameValid := decodeRenameBuffer.io.outputValid
  backend.io.rename := decodeRenameBuffer.io.output
  decodeRenameBuffer.io.outputReady := backend.io.renameReady

  backend.io.instructionRequestValid := frontend.io.cacheRequestValid
  backend.io.instructionUncachedRequestValid := frontend.io.cacheUncachedRequestValid
  backend.io.instructionRequest := frontend.io.cacheRequest
  // Redirect/correction kill suppresses requestValid.  Use the kill-independent
  // capacity path for frontend ownership so redirect cannot loop through L1I
  // turnover ready and back into nextFetchPc enables.
  frontend.io.cacheRequestReady := backend.io.instructionRequestCapacityReady
  frontend.io.cacheHitResponsePending := backend.io.instructionHitResponsePending
  frontend.io.cacheResponseValid := backend.io.instructionResponseValid
  frontend.io.cacheResponse := backend.io.instructionResponse
  backend.io.instructionKill := frontend.io.cacheKill
  io.uncachedInstructionRequestValid := backend.io.uncachedInstructionRequestValid
  io.uncachedInstructionRequest := backend.io.uncachedInstructionRequest
  backend.io.uncachedInstructionRequestReady := io.uncachedInstructionRequestReady
  backend.io.uncachedInstructionResponseValid := io.uncachedInstructionResponseValid
  backend.io.uncachedInstructionResponse := io.uncachedInstructionResponse
  io.uncachedDataRequestValid := backend.io.uncachedDataRequestValid
  io.uncachedDataRequest := backend.io.uncachedDataRequest
  backend.io.uncachedDataRequestReady := io.uncachedDataRequestReady
  backend.io.uncachedDataResponseValid := io.uncachedDataResponseValid
  backend.io.uncachedDataResponse := io.uncachedDataResponse

  val recoveryPending = RegInit(False)
  val recoveryPayload = Reg(RecoveryRequest(config))
  val recoveryCapture = backend.io.recoveryValid && !recoveryPending &&
    !io.externalRedirectValid
  recoveryPending := recoveryCapture
  when(recoveryCapture) { recoveryPayload := backend.io.recovery }

  val exceptionRecovery = recoveryPending &&
    recoveryPayload.cause === RecoveryCause.exception
  val internalRedirectValid = recoveryPending || io.externalRedirectValid
  val internalRedirectTarget = UInt(config.xlen bits)
  internalRedirectTarget := recoveryPayload.target
  when(exceptionRecovery) {
    internalRedirectTarget := Mux(
      recoveryPayload.exception.tlbRefill,
      io.tlbRefillTarget,
      io.exceptionEntryTarget
    )
  }
  when(io.externalRedirectValid) {
    internalRedirectTarget := io.externalRedirectTarget
  }

  frontend.io.redirectValid := internalRedirectValid
  frontend.io.redirectTarget := internalRedirectTarget
  val committedBranch = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    committedBranch(lane) := backend.io.commitValid(lane) &&
      backend.io.commit(lane).retired && backend.io.commit(lane).isBranch &&
      !internalRedirectValid
  }
  val retiredPredictorUpdate = Vec(RetiredPredictorUpdate(config), config.commitWidth)
  for (lane <- 0 until config.commitWidth) {
    val commit = backend.io.commit(lane)
    retiredPredictorUpdate(lane).pc := commit.pc
    retiredPredictorUpdate(lane).taken := commit.branchTaken
    retiredPredictorUpdate(lane).target := commit.branchTarget
    retiredPredictorUpdate(lane).branchType := commit.predictorType
    retiredPredictorUpdate(lane).metadata := commit.predictorMetadata
    retiredPredictorUpdate(lane).isCall := commit.predictorType === PredictedBranchType.call
    retiredPredictorUpdate(lane).isReturn := commit.predictorType === PredictedBranchType.ret
    predictorUpdateQueue.io.push(lane) := retiredPredictorUpdate(lane)
  }
  predictorUpdateQueue.io.pushValid := committedBranch

  // Table writes drain in program order. Architectural history and RAS state use the unqueued
  // batch below so a recovery observes every branch retired in the recovery branch's cycle.
  frontend.io.predictorUpdateValid := predictorUpdateQueue.io.popValid
  frontend.io.predictorUpdatePc := predictorUpdateQueue.io.pop.pc
  frontend.io.predictorUpdateTaken := predictorUpdateQueue.io.pop.taken
  frontend.io.predictorUpdateTarget := predictorUpdateQueue.io.pop.target
  frontend.io.predictorUpdateType := predictorUpdateQueue.io.pop.branchType
  frontend.io.predictorUpdateMetadata := predictorUpdateQueue.io.pop.metadata
  frontend.io.predictorUpdateIsCall := predictorUpdateQueue.io.pop.isCall
  frontend.io.predictorUpdateIsReturn := predictorUpdateQueue.io.pop.isReturn
  predictorUpdateQueue.io.popReady := frontend.io.predictorUpdateReady

  // Recovery redirects are already staged through recoveryPending.  Stage the retirement
  // batch on the same boundary so ROB payloads and pc+4 do not feed the three-lane RAS fold
  // combinationally; the batch still reaches the predictor in the recovery/flush cycle.
  val stagedPredictorRetireValid = Reg(Bits(config.commitWidth bits)) init (0)
  val stagedPredictorRetireTaken = Reg(Bits(config.commitWidth bits)) init (0)
  val stagedPredictorRetireType = Vec.fill(config.commitWidth)(
    Reg(UInt(PredictedBranchType.Width bits)) init (PredictedBranchType.direct)
  )
  val stagedPredictorRetireIsCall = Reg(Bits(config.commitWidth bits)) init (0)
  val stagedPredictorRetireIsReturn = Reg(Bits(config.commitWidth bits)) init (0)
  val stagedPredictorRetireReturnAddress = Vec.fill(config.commitWidth)(
    Reg(UInt(config.xlen bits)) init (0)
  )
  stagedPredictorRetireValid := committedBranch
  for (lane <- 0 until config.commitWidth) {
    stagedPredictorRetireTaken(lane) := retiredPredictorUpdate(lane).taken
    stagedPredictorRetireType(lane) := retiredPredictorUpdate(lane).branchType
    stagedPredictorRetireIsCall(lane) := retiredPredictorUpdate(lane).isCall
    stagedPredictorRetireIsReturn(lane) := retiredPredictorUpdate(lane).isReturn
    stagedPredictorRetireReturnAddress(lane) := retiredPredictorUpdate(lane).pc + 4
  }
  frontend.io.predictorRetireValid := stagedPredictorRetireValid
  frontend.io.predictorRetireTaken := stagedPredictorRetireTaken
  frontend.io.predictorRetireIsCall := stagedPredictorRetireIsCall
  frontend.io.predictorRetireIsReturn := stagedPredictorRetireIsReturn
  for (lane <- 0 until config.commitWidth) {
    frontend.io.predictorRetireType(lane) := stagedPredictorRetireType(lane)
    frontend.io.predictorRetireReturnAddress(lane) :=
      stagedPredictorRetireReturnAddress(lane)
  }
  frontend.io.privilege := io.privilege
  frontend.io.interruptPending := io.interruptPending
  decodeRenameBuffer.io.flush := internalRedirectValid
  backend.io.flush := internalRedirectValid

  io.memoryReadValid := backend.io.memoryReadValid
  io.memoryRead := backend.io.memoryRead
  backend.io.memoryReadReady := io.memoryReadReady
  backend.io.memoryReadBeatValid := io.memoryReadBeatValid
  backend.io.memoryReadBeat := io.memoryReadBeat
  io.memoryReadBeatReady := backend.io.memoryReadBeatReady
  io.memoryWriteValid := backend.io.memoryWriteValid
  io.memoryWrite := backend.io.memoryWrite
  backend.io.memoryWriteReady := io.memoryWriteReady
  backend.io.memoryWriteResponseValid := io.memoryWriteResponseValid
  backend.io.memoryWriteResponse := io.memoryWriteResponse
  backend.io.memoryBusIdle := io.memoryBusIdle

  backend.io.systemReadData := io.systemReadData
  backend.io.timer := io.timer
  backend.io.timerId := io.timerId
  io.systemReadValid := backend.io.systemReadValid
  io.systemReadAddress := backend.io.systemReadAddress
  backend.io.debugReadAddress := io.debugReadAddress
  io.debugReadData := backend.io.debugReadData

  io.commitValid := Mux(
    internalRedirectValid,
    B(0, config.commitWidth bits),
    backend.io.commitValid
  )
  io.commit := backend.io.commit
  io.commitMemory := backend.io.commitMemory
  io.recoveryValid := backend.io.recoveryValid && !internalRedirectValid
  io.recovery := backend.io.recovery
  io.debugCommitValid := backend.io.debugCommitValid
  io.debugCommit := backend.io.debugCommit
  io.csrWriteValid := backend.io.csrWriteValid
  io.csrWriteAddress := backend.io.csrWriteAddress
  io.csrWriteData := backend.io.csrWriteData
  io.csrWriteMask := backend.io.csrWriteMask
  io.serialCommitPc := backend.io.serialCommitPc
  io.ertnValid := backend.io.ertnValid
  io.idleValid := backend.io.idleValid
  io.refetchValid := backend.io.refetchValid
  io.cacheInvalidateValid := backend.io.cacheInvalidateValid
  io.dataCacheInvalidateValid := backend.io.dataCacheInvalidateValid
  io.dataCacheWritebackInvalidateValid :=
    backend.io.dataCacheWritebackInvalidateValid
  io.level2CacheInvalidateValid := backend.io.level2CacheInvalidateValid
  io.tlbSearchValid := backend.io.tlbSearchValid
  io.tlbReadValid := backend.io.tlbReadValid
  io.tlbWriteValid := backend.io.tlbWriteValid
  io.tlbFillValid := backend.io.tlbFillValid
  io.tlbInvalidateValid := backend.io.tlbInvalidateValid
  io.tlbInvalidateAsid := backend.io.tlbInvalidateAsid
  io.tlbInvalidateVpn := backend.io.tlbInvalidateVpn
  io.tlbInvalidateOperation := backend.io.tlbInvalidateOperation
  io.reservationBitSet := backend.io.reservationBitSet
  io.reservationBitValue := backend.io.reservationBitValue
  io.reservationAddressSet := backend.io.reservationAddressSet
  io.reservationLineAddressUpdate := backend.io.reservationLineAddressUpdate
  io.exceptionValid := backend.io.exceptionValid
  io.exceptionPc := backend.io.exceptionPc
  io.exception := backend.io.exception

  backend.io.cacheInvalidate := io.cacheInvalidate
  backend.io.dataCacheInvalidate := io.dataCacheInvalidate
  backend.io.dataCacheWritebackInvalidate := io.dataCacheWritebackInvalidate
  backend.io.level2CacheInvalidate := io.level2CacheInvalidate
  io.cacheInvalidateBusy := backend.io.cacheInvalidateBusy
  io.fetchPc := frontend.io.fetchPc
  io.frontendOccupancy := frontend.io.occupancy

  require(config.commitWidth == 3)
  require(config.robPointerWidth == 6)
  val perfObservationV1Word1 = Bits(PerfObservationV1.WordWidth bits)
  perfObservationV1Word1 := 0
  val observationRetired = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    observationRetired(lane) := io.commitValid(lane) && io.commit(lane).retired
  }
  perfObservationV1Word1(2 downto 0) := io.commitValid
  perfObservationV1Word1(5 downto 3) := observationRetired
  perfObservationV1Word1(6) := io.recoveryValid
  perfObservationV1Word1(9 downto 7) := io.recovery.cause.asBits
  perfObservationV1Word1(10) := io.exceptionValid
  perfObservationV1Word1(11) := io.ertnValid
  perfObservationV1Word1(12) := io.idleValid
  perfObservationV1Word1(13) := io.refetchValid
  perfObservationV1Word1(14) := internalRedirectValid
  perfObservationV1Word1(19 downto 15) := frontend.io.occupancy.asBits.resized
  perfObservationV1Word1(22 downto 20) := frontend.io.decodeValid
  perfObservationV1Word1(26 downto 23) :=
    predictorUpdateQueue.io.occupancy.asBits.resized
  perfObservationV1Word1(27) := predictorUpdateQueue.io.popValid
  perfObservationV1Word1(28) :=
    predictorUpdateQueue.io.popValid && predictorUpdateQueue.io.popReady
  perfObservationV1Word1(34 downto 29) := io.recovery.robPointer.asBits
  perfObservationV1Word1(37 downto 35) := committedBranch
  PerfObservationV1.expose(perfObservationV1Word1, 1)
}
