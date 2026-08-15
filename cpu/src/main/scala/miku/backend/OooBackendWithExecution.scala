package miku.backend

import miku.core._
import miku.execute._
import miku.memory._
import miku.privileged._
import spinal.core._
import spinal.lib._

final class OooBackendWithExecution(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val rename = in Vec (DecodedMicroOp(config), config.renameWidth)
    val renameReady = out Bits (config.renameWidth bits)
    val dataRequestValid = out Bool ()
    val dataRequest = out(CacheRequest(config))
    val dataRequestReady = in Bool ()
    val dataResponseValid = in Bool ()
    val dataResponse = in(CacheResponse(config))
    val translationRequest = master(Stream(TranslationRequest(config)))
    val translationResponse = slave(Stream(TranslationResponse(config)))
    val translationBypassAddress = out UInt (config.xlen bits)
    val translationBypass = in(TranslationBypass(config))
    val reservationValid = in Bool ()
    val reservationLineAddress = in Bits (config.reservationAddressWidth bits)
    val systemReadValid = out Bool ()
    val systemReadAddress = out UInt (14 bits)
    val systemReadData = in Bits (config.xlen bits)
    val timer = in Bits (64 bits)
    val timerId = in Bits (config.xlen bits)
    val debugReadAddress = in UInt (config.archRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)
    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (CommitRecord(config), config.commitWidth)
    val commitMemory = out Vec (MemoryCommitObservation(config), config.commitWidth)
    val recoveryValid = out Bool ()
    val recovery = out(RecoveryRequest(config))
    val predictorUpdateCapacity = in UInt (log2Up(config.commitWidth + 1) bits)
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
    val memorySubsystemIdle = in Bool ()
    val barrierActive = out Bool ()
    val instructionBarrierMaintenanceStart = out Bool ()
    val instructionBarrierMaintenanceReady = in Bool ()
    val instructionBarrierMaintenanceDone = in Bool ()
    val cacheMaintenanceRequest = master(Stream(CacheMaintenanceRequest(config)))
    val cacheMaintenanceResponse = slave(Stream(CacheMaintenanceResponse(config)))
    val flush = in Bool ()
  }

  val backend = new OooBackend(config)
  val execution = new OooExecutionCluster(config)
  val loadStoreQueue = new LoadStoreQueue(config)
  val commitAdapter = new OooCommitAdapter(config)

  val storeDrainBusy = loadStoreQueue.io.storeDrainBusy
  backend.io.renameValid := Mux(
    storeDrainBusy,
    B(0, config.renameWidth bits),
    io.renameValid
  )
  backend.io.rename := io.rename
  backend.io.predictorUpdateCapacity := io.predictorUpdateCapacity
  backend.io.releaseLoadValid := loadStoreQueue.io.releaseLoadValid
  backend.io.releaseStoreValid := Mux(
    storeDrainBusy,
    B(0, config.commitWidth bits),
    loadStoreQueue.io.releaseStoreValid
  )
  io.renameReady := Mux(
    storeDrainBusy,
    B(0, config.renameWidth bits),
    backend.io.renameReady
  )
  loadStoreQueue.io.allocateValid := backend.io.memoryAllocateValid
  loadStoreQueue.io.allocate := backend.io.memoryAllocate
  loadStoreQueue.io.committedMemoryEpoch := backend.io.committedMemoryEpoch
  loadStoreQueue.io.currentRecoveryEpoch := backend.io.currentRecoveryEpoch
  loadStoreQueue.io.robHeadPointer := backend.io.robHeadPointer
  loadStoreQueue.io.storeDataValid := backend.io.storeDataValid
  loadStoreQueue.io.storeDataRobPointer := backend.io.storeDataRobPointer
  loadStoreQueue.io.storeDataStoreQueueIndex := backend.io.storeDataStoreQueueIndex
  loadStoreQueue.io.storeData := backend.io.storeData
  backend.io.storeDataReady := loadStoreQueue.io.storeDataReady

  execution.io.issueValid := backend.io.issueValid
  execution.io.issue := backend.io.issue
  execution.io.source1 := backend.io.issueSource1
  execution.io.source2 := backend.io.issueSource2
  backend.io.issueReady := execution.io.issueReady
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  loadStoreQueue.io.orderingRobPointer := Mux(
    execution.io.barrierActive,
    execution.io.barrierRobPointer,
    backend.io.issue(loadStorePort).robPointer
  )

  execution.io.aguReady := loadStoreQueue.io.aguReady
  execution.io.loadStoreCompletionValid := loadStoreQueue.io.completionValid
  execution.io.loadStoreCompletion := loadStoreQueue.io.completion
  execution.io.systemReadData := io.systemReadData
  execution.io.timer := io.timer
  execution.io.timerId := io.timerId
  loadStoreQueue.io.aguValid := execution.io.aguValid
  loadStoreQueue.io.agu := execution.io.agu
  io.translationBypassAddress := loadStoreQueue.io.translationBypassAddress
  loadStoreQueue.io.translationBypass := io.translationBypass
  loadStoreQueue.io.commitValid := backend.io.commitValid
  loadStoreQueue.io.commit := backend.io.commit
  loadStoreQueue.io.dataRequestReady := io.dataRequestReady
  loadStoreQueue.io.dataResponseValid := io.dataResponseValid
  loadStoreQueue.io.dataResponse := io.dataResponse
  val translationOwnerValid = RegInit(False)
  val translationOwnerCacheOperation = RegInit(False)
  val selectCacheTranslation = execution.io.cacheTranslationRequest.valid &&
    !loadStoreQueue.io.translationRequest.valid
  io.translationRequest.valid := !translationOwnerValid &&
    (loadStoreQueue.io.translationRequest.valid ||
      execution.io.cacheTranslationRequest.valid)
  io.translationRequest.payload := loadStoreQueue.io.translationRequest.payload
  when(selectCacheTranslation) {
    io.translationRequest.payload := execution.io.cacheTranslationRequest.payload
  }
  loadStoreQueue.io.translationRequest.ready := !translationOwnerValid &&
    !selectCacheTranslation && io.translationRequest.ready
  execution.io.cacheTranslationRequest.ready := !translationOwnerValid &&
    selectCacheTranslation && io.translationRequest.ready
  val translationRequestFire = io.translationRequest.valid && io.translationRequest.ready
  when(translationRequestFire) {
    translationOwnerValid := True
    translationOwnerCacheOperation := selectCacheTranslation
  }

  loadStoreQueue.io.translationResponse.valid := io.translationResponse.valid &&
    translationOwnerValid && !translationOwnerCacheOperation
  loadStoreQueue.io.translationResponse.payload := io.translationResponse.payload
  execution.io.cacheTranslationResponse.valid := io.translationResponse.valid &&
    translationOwnerValid && translationOwnerCacheOperation
  execution.io.cacheTranslationResponse.payload := io.translationResponse.payload
  io.translationResponse.ready := translationOwnerValid && Mux(
    translationOwnerCacheOperation,
    execution.io.cacheTranslationResponse.ready,
    loadStoreQueue.io.translationResponse.ready
  )
  when(io.translationResponse.valid && io.translationResponse.ready) {
    translationOwnerValid := False
  }
  loadStoreQueue.io.reservationValid := io.reservationValid
  loadStoreQueue.io.reservationLineAddress := io.reservationLineAddress
  execution.io.olderStorePending := loadStoreQueue.io.olderStorePending
  execution.io.memorySubsystemIdle := io.memorySubsystemIdle
  io.barrierActive := execution.io.barrierActive
  io.instructionBarrierMaintenanceStart :=
    execution.io.instructionBarrierMaintenanceStart
  execution.io.instructionBarrierMaintenanceReady :=
    io.instructionBarrierMaintenanceReady
  execution.io.instructionBarrierMaintenanceDone := io.instructionBarrierMaintenanceDone
  io.cacheMaintenanceRequest.valid := execution.io.cacheMaintenanceRequest.valid
  io.cacheMaintenanceRequest.payload := execution.io.cacheMaintenanceRequest.payload
  execution.io.cacheMaintenanceRequest.ready := io.cacheMaintenanceRequest.ready
  execution.io.cacheMaintenanceResponse.valid := io.cacheMaintenanceResponse.valid
  execution.io.cacheMaintenanceResponse.payload := io.cacheMaintenanceResponse.payload
  io.cacheMaintenanceResponse.ready := execution.io.cacheMaintenanceResponse.ready
  io.dataRequestValid := loadStoreQueue.io.dataRequestValid
  io.dataRequest := loadStoreQueue.io.dataRequest
  io.systemReadValid := execution.io.systemReadValid
  io.systemReadAddress := execution.io.systemReadAddress
  backend.io.debugReadAddress := io.debugReadAddress
  io.debugReadData := backend.io.debugReadData

  backend.io.completionValid := execution.io.completionValid
  backend.io.completion := execution.io.completion
  backend.io.storeCompletionBypassValid := loadStoreQueue.io.storeCompletionBypassValid
  backend.io.storeCompletionBypass := loadStoreQueue.io.storeCompletionBypass
  backend.io.headLoadCompletionBypassValid :=
    loadStoreQueue.io.headLoadCompletionBypassValid
  backend.io.headLoadCompletionBypass := loadStoreQueue.io.headLoadCompletionBypass
  backend.io.directWakeupValid := execution.io.directWakeupValid
  backend.io.directWakeupPdst := execution.io.directWakeupPdst
  backend.io.loadWakeupValid := loadStoreQueue.io.loadWakeupValid
  backend.io.loadWakeupPdst := loadStoreQueue.io.loadWakeupPdst
  backend.io.loadWakeupRecoveryEpoch := loadStoreQueue.io.loadWakeupRecoveryEpoch
  backend.io.loadWakeupEpochCurrent := loadStoreQueue.io.loadWakeupEpochCurrent
  backend.io.resultForwardValid :=
    execution.io.completionValid(config.executionWidth) &&
      execution.io.completion(config.executionWidth).writesPdst
  backend.io.resultForwardPdst := execution.io.completion(config.executionWidth).pdst
  backend.io.resultForwardData := execution.io.completion(config.executionWidth).data
  io.commitValid := backend.io.commitValid
  io.commit := backend.io.commit
  io.commitMemory := loadStoreQueue.io.commitObservation
  io.recoveryValid := backend.io.recoveryValid
  io.recovery := backend.io.recovery

  commitAdapter.io.commitValid := backend.io.commitValid
  commitAdapter.io.commit := backend.io.commit
  commitAdapter.io.flush := io.flush
  io.debugCommitValid := commitAdapter.io.debugCommitValid
  io.debugCommit := commitAdapter.io.debugCommit
  io.csrWriteValid := commitAdapter.io.csrWriteValid
  io.csrWriteAddress := commitAdapter.io.csrAddress
  io.csrWriteData := commitAdapter.io.csrWriteData
  io.csrWriteMask := commitAdapter.io.csrMask
  io.serialCommitPc := commitAdapter.io.serialCommitPc
  io.ertnValid := commitAdapter.io.ertnValid
  io.idleValid := commitAdapter.io.idleValid
  io.refetchValid := commitAdapter.io.refetchValid
  io.cacheInvalidateValid := commitAdapter.io.cacheInvalidateValid
  io.dataCacheInvalidateValid := commitAdapter.io.dataCacheInvalidateValid
  io.dataCacheWritebackInvalidateValid :=
    commitAdapter.io.dataCacheWritebackInvalidateValid
  io.level2CacheInvalidateValid := commitAdapter.io.level2CacheInvalidateValid
  io.tlbSearchValid := commitAdapter.io.tlbSearchValid
  io.tlbReadValid := commitAdapter.io.tlbReadValid
  io.tlbWriteValid := commitAdapter.io.tlbWriteValid
  io.tlbFillValid := commitAdapter.io.tlbFillValid
  io.tlbInvalidateValid := commitAdapter.io.tlbInvalidateValid
  io.tlbInvalidateAsid := commitAdapter.io.tlbInvalidateAsid
  io.tlbInvalidateVpn := commitAdapter.io.tlbInvalidateVpn
  io.tlbInvalidateOperation := commitAdapter.io.tlbInvalidateOperation
  io.reservationBitSet := commitAdapter.io.reservationBitSet
  io.reservationBitValue := commitAdapter.io.reservationBitValue
  io.reservationAddressSet := commitAdapter.io.reservationAddressSet
  io.reservationLineAddressUpdate := commitAdapter.io.reservationLineAddress
  io.exceptionValid := commitAdapter.io.exceptionValid
  io.exceptionPc := commitAdapter.io.exceptionPc
  io.exception := commitAdapter.io.exception

  backend.io.flush := io.flush
  execution.io.flush := io.flush
  loadStoreQueue.io.flush := io.flush
}
