package miku.backend

import miku.core._
import miku.memory._
import miku.privileged._
import spinal.core._
import spinal.lib._

/** OoO execution backend connected to the shared 64-byte L1I/L1D/L2 hierarchy. */
final class OooBackendWithDataCache(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val rename = in Vec (OooDecodedUop(config), config.renameWidth)
    val renameReady = out Bits (config.renameWidth bits)

    val instructionRequestValid = in Bool ()
    val instructionUncachedRequestValid = in Bool ()
    val instructionRequest = in(OooInstructionCacheRequest(config))
    val instructionRequestReady = out Bool ()
    val instructionRequestCapacityReady = out Bool ()
    val instructionHitResponsePending = out Bool ()
    val instructionResponseValid = out Bool ()
    val instructionResponse = out(OooInstructionCacheResponse(config))
    val instructionKill = in Bool ()

    val dataTranslationRequest = master(Stream(OooTranslationRequest(config)))
    val dataTranslationResponse = slave(Stream(OooTranslationResponse(config)))
    val dataTranslationBypassAddress = out UInt (config.xlen bits)
    val dataTranslationBypass = in(OooTranslationBypass(config))
    val reservationValid = in Bool ()
    val reservationLineAddress = in Bits (config.reservationAddressWidth bits)

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

    val systemReadValid = out Bool ()
    val systemReadAddress = out UInt (14 bits)
    val systemReadData = in Bits (config.xlen bits)
    val timer = in Bits (64 bits)
    val timerId = in Bits (config.xlen bits)
    val debugReadAddress = in UInt (config.archRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)

    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (OooCommitRecord(config), config.commitWidth)
    val commitMemory = out Vec (OooMemoryCommitObservation(config), config.commitWidth)
    val recoveryValid = out Bool ()
    val recovery = out(OooRecoveryRequest(config))
    val predictorUpdateCapacity = in UInt (log2Up(config.commitWidth + 1) bits)
    val debugCommitValid = out Bool ()
    val debugCommit = out(OooCommitRecord(config))
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
    val exception = out(OooExceptionMeta())

    val cacheInvalidate = in Bool ()
    val dataCacheInvalidate = in Bool ()
    val dataCacheWritebackInvalidate = in Bool ()
    val level2CacheInvalidate = in Bool ()
    val cacheInvalidateBusy = out Bool ()
    val memoryBusIdle = in Bool ()
    val flush = in Bool ()
  }

  val backend = new OooBackendWithExecution(config)
  val cacheHierarchy = new OooSharedCacheHierarchy(config)

  backend.io.renameValid := io.renameValid
  backend.io.rename := io.rename
  backend.io.predictorUpdateCapacity := io.predictorUpdateCapacity
  io.renameReady := backend.io.renameReady

  cacheHierarchy.io.instructionRequestValid := io.instructionRequestValid
  cacheHierarchy.io.instructionUncachedRequestValid := io.instructionUncachedRequestValid
  cacheHierarchy.io.instructionRequest := io.instructionRequest
  cacheHierarchy.io.instructionKill := io.instructionKill
  io.instructionRequestReady := cacheHierarchy.io.instructionRequestReady
  io.instructionRequestCapacityReady := cacheHierarchy.io.instructionRequestCapacityReady
  io.instructionHitResponsePending := cacheHierarchy.io.instructionHitResponsePending
  io.instructionResponseValid := cacheHierarchy.io.instructionResponseValid
  io.instructionResponse := cacheHierarchy.io.instructionResponse
  io.uncachedInstructionRequestValid :=
    cacheHierarchy.io.uncachedInstructionRequestValid
  io.uncachedInstructionRequest := cacheHierarchy.io.uncachedInstructionRequest
  cacheHierarchy.io.uncachedInstructionRequestReady :=
    io.uncachedInstructionRequestReady
  cacheHierarchy.io.uncachedInstructionResponseValid :=
    io.uncachedInstructionResponseValid
  cacheHierarchy.io.uncachedInstructionResponse := io.uncachedInstructionResponse

  cacheHierarchy.io.dataRequestValid := backend.io.dataRequestValid
  cacheHierarchy.io.dataRequest := backend.io.dataRequest
  backend.io.dataRequestReady := cacheHierarchy.io.dataRequestReady
  backend.io.dataResponseValid := cacheHierarchy.io.dataResponseValid
  backend.io.dataResponse := cacheHierarchy.io.dataResponse
  io.uncachedDataRequestValid := cacheHierarchy.io.uncachedDataRequestValid
  io.uncachedDataRequest := cacheHierarchy.io.uncachedDataRequest
  cacheHierarchy.io.uncachedDataRequestReady := io.uncachedDataRequestReady
  cacheHierarchy.io.uncachedDataResponseValid := io.uncachedDataResponseValid
  cacheHierarchy.io.uncachedDataResponse := io.uncachedDataResponse
  io.dataTranslationRequest.valid := backend.io.translationRequest.valid
  io.dataTranslationRequest.payload := backend.io.translationRequest.payload
  backend.io.translationRequest.ready := io.dataTranslationRequest.ready
  backend.io.translationResponse.valid := io.dataTranslationResponse.valid
  backend.io.translationResponse.payload := io.dataTranslationResponse.payload
  io.dataTranslationResponse.ready := backend.io.translationResponse.ready
  io.dataTranslationBypassAddress := backend.io.translationBypassAddress
  backend.io.translationBypass := io.dataTranslationBypass
  backend.io.reservationValid := io.reservationValid
  backend.io.reservationLineAddress := io.reservationLineAddress

  backend.io.memorySubsystemIdle :=
    backend.io.barrierActive && cacheHierarchy.io.idle && io.memoryBusIdle
  cacheHierarchy.io.barrierDrain := backend.io.barrierActive
  cacheHierarchy.io.instructionBarrierMaintenanceStart :=
    backend.io.instructionBarrierMaintenanceStart
  backend.io.instructionBarrierMaintenanceReady :=
    cacheHierarchy.io.instructionBarrierMaintenanceReady
  backend.io.instructionBarrierMaintenanceDone :=
    cacheHierarchy.io.instructionBarrierMaintenanceDone
  cacheHierarchy.io.cacheMaintenanceRequest.valid :=
    backend.io.cacheMaintenanceRequest.valid
  cacheHierarchy.io.cacheMaintenanceRequest.payload :=
    backend.io.cacheMaintenanceRequest.payload
  backend.io.cacheMaintenanceRequest.ready :=
    cacheHierarchy.io.cacheMaintenanceRequest.ready
  backend.io.cacheMaintenanceResponse.valid :=
    cacheHierarchy.io.cacheMaintenanceResponse.valid
  backend.io.cacheMaintenanceResponse.payload :=
    cacheHierarchy.io.cacheMaintenanceResponse.payload
  cacheHierarchy.io.cacheMaintenanceResponse.ready :=
    backend.io.cacheMaintenanceResponse.ready

  io.memoryReadValid := cacheHierarchy.io.memoryReadValid
  io.memoryRead := cacheHierarchy.io.memoryRead
  cacheHierarchy.io.memoryReadReady := io.memoryReadReady
  cacheHierarchy.io.memoryReadBeatValid := io.memoryReadBeatValid
  cacheHierarchy.io.memoryReadBeat := io.memoryReadBeat
  io.memoryReadBeatReady := cacheHierarchy.io.memoryReadBeatReady
  io.memoryWriteValid := cacheHierarchy.io.memoryWriteValid
  io.memoryWrite := cacheHierarchy.io.memoryWrite
  cacheHierarchy.io.memoryWriteReady := io.memoryWriteReady
  cacheHierarchy.io.memoryWriteResponseValid := io.memoryWriteResponseValid
  cacheHierarchy.io.memoryWriteResponse := io.memoryWriteResponse

  backend.io.systemReadData := io.systemReadData
  backend.io.timer := io.timer
  backend.io.timerId := io.timerId
  io.systemReadValid := backend.io.systemReadValid
  io.systemReadAddress := backend.io.systemReadAddress
  backend.io.debugReadAddress := io.debugReadAddress
  io.debugReadData := backend.io.debugReadData

  io.commitValid := backend.io.commitValid
  io.commit := backend.io.commit
  io.commitMemory := backend.io.commitMemory
  io.recoveryValid := backend.io.recoveryValid
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

  cacheHierarchy.io.invalidate := io.cacheInvalidate
  cacheHierarchy.io.dataInvalidate := io.dataCacheInvalidate
  cacheHierarchy.io.dataWritebackInvalidate := io.dataCacheWritebackInvalidate
  cacheHierarchy.io.level2Invalidate := io.level2CacheInvalidate
  io.cacheInvalidateBusy := cacheHierarchy.io.invalidateBusy
  backend.io.flush := io.flush
}
