package miku.execute

import miku.backend._
import miku.core._
import miku.frontend._
import miku.memory._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class OooExecutionClusterProbe(config: OooCoreConfig) extends Component {
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  private val csrPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Csr))
  private val aluPort =
    config.executionPorts.indexWhere(port =>
      port.capabilities.contains(ExecutionUnitKind.Alu) && !port.capabilities.contains(ExecutionUnitKind.Csr)
    )

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val issueValid = in Bool ()
    val aguReady = in Bool ()
    val loadStoreCompletionValid = in Bool ()
    val issueReady = out Bool ()
    val aguValid = out Bool ()
    val completionValid = out Bool ()
    val systemOperation = out UInt (SystemOperation.Width bits)
    val isLoad = out Bool ()
    val isStore = out Bool ()
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  val decodedIsBarrier = ExecutionUnitType.isBarrier(decoder.io.decoded.fuType)
  val decodedUsesLsu = decoder.io.decoded.fuType === ExecutionUnitType.loadStore
  execution.io.issueValid(loadStorePort) := io.issueValid && decodedUsesLsu
  execution.io.issueValid(aluPort) := io.issueValid && !decodedUsesLsu && !decodedIsBarrier
  execution.io.issueValid(csrPort) := io.issueValid && decodedIsBarrier
  for (port <- 0 until config.executionWidth) {
    if (port == loadStorePort || port == csrPort || port == aluPort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := 0
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 3
      execution.io.issue(port).recoveryEpoch := 0
      execution.io.issue(port).loadQueueIndex := 1
      execution.io.issue(port).storeQueueIndex := 2
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
    }
    execution.io.source1(port) := 0
    execution.io.source2(port) := 0
  }
  execution.io.flush := False
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := io.aguReady
  execution.io.loadStoreCompletionValid := io.loadStoreCompletionValid
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := False
  execution.io.memorySubsystemIdle := True
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  val maintenanceResponseValid = RegNext(execution.io.cacheMaintenanceRequest.fire) init (False)
  val maintenanceResponseRobPointer = RegNextWhen(
    execution.io.cacheMaintenanceRequest.robPointer,
    execution.io.cacheMaintenanceRequest.fire
  )
  val maintenanceResponseRecoveryEpoch = RegNextWhen(
    execution.io.cacheMaintenanceRequest.recoveryEpoch,
    execution.io.cacheMaintenanceRequest.fire
  )
  execution.io.cacheMaintenanceResponse.valid := maintenanceResponseValid
  execution.io.cacheMaintenanceResponse.robPointer := maintenanceResponseRobPointer
  execution.io.cacheMaintenanceResponse.recoveryEpoch := maintenanceResponseRecoveryEpoch

  io.issueReady := Mux(
    decodedIsBarrier,
    execution.io.issueReady(csrPort),
    Mux(decodedUsesLsu, execution.io.issueReady(loadStorePort), execution.io.issueReady(aluPort))
  )
  io.aguValid := execution.io.aguValid
  io.completionValid := execution.io.completionValid(loadStorePort) ||
    execution.io.completionValid(csrPort) || execution.io.completionValid(aluPort)
  io.systemOperation := decoder.io.decoded.systemOperation
  io.isLoad := decoder.io.decoded.isLoad
  io.isStore := decoder.io.decoded.isStore
}

private final class OooDivideCompletionCollisionProbe(config: OooCoreConfig) extends Component {
  private val dividePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Divide))

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val issueValid = in Bool ()
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val robPointer = in UInt (config.robPointerWidth bits)
    val recoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val pdst = in UInt (config.physicalRegIndexWidth bits)
    val flush = in Bool ()
    val issueReady = out Bool ()
    val directWakeupValid = out Bool ()
    val directWakeupPdst = out UInt (config.physicalRegIndexWidth bits)
    val completionValid = out Bool ()
    val completionRobPointer = out UInt (config.robPointerWidth bits)
    val completionRecoveryEpoch = out UInt (config.recoveryEpochWidth bits)
    val completionPdst = out UInt (config.physicalRegIndexWidth bits)
    val completionData = out Bits (config.xlen bits)
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  execution.io.issueValid(dividePort) := io.issueValid
  for (port <- 0 until config.executionWidth) {
    if (port == dividePort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := io.pdst
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := io.robPointer
      execution.io.issue(port).recoveryEpoch := io.recoveryEpoch
      execution.io.issue(port).loadQueueIndex := 0
      execution.io.issue(port).storeQueueIndex := 0
      execution.io.source1(port) := io.source1
      execution.io.source2(port) := io.source2
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
      execution.io.source1(port) := 0
      execution.io.source2(port) := 0
    }
  }
  execution.io.flush := io.flush
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := False
  execution.io.memorySubsystemIdle := True
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  execution.io.cacheMaintenanceResponse.valid := False
  execution.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, execution.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )

  io.issueReady := execution.io.issueReady(dividePort)
  io.directWakeupValid := execution.io.directWakeupValid(dividePort)
  io.directWakeupPdst := execution.io.directWakeupPdst(dividePort)
  io.completionValid := execution.io.completionValid(dividePort)
  io.completionRobPointer := execution.io.completion(dividePort).robPointer
  io.completionRecoveryEpoch := execution.io.completion(dividePort).recoveryEpoch
  io.completionPdst := execution.io.completion(dividePort).pdst
  io.completionData := execution.io.completion(dividePort).data
}

private final class OooMultiplyWakeupProbe(config: OooCoreConfig) extends Component {
  private val multiplyPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Multiply))

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val issueValid = in Bool ()
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val pdst = in UInt (config.physicalRegIndexWidth bits)
    val directWakeupValid = out Bool ()
    val directWakeupPdst = out UInt (config.physicalRegIndexWidth bits)
    val completionValid = out Bool ()
    val completionPdst = out UInt (config.physicalRegIndexWidth bits)
    val completionData = out Bits (config.xlen bits)
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  execution.io.issueValid(multiplyPort) := io.issueValid
  for (port <- 0 until config.executionWidth) {
    if (port == multiplyPort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := io.pdst
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 3
      execution.io.issue(port).recoveryEpoch := 0
      execution.io.issue(port).loadQueueIndex := 0
      execution.io.issue(port).storeQueueIndex := 0
      execution.io.source1(port) := io.source1
      execution.io.source2(port) := io.source2
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
      execution.io.source1(port) := 0
      execution.io.source2(port) := 0
    }
  }
  execution.io.flush := False
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := False
  execution.io.memorySubsystemIdle := True
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  execution.io.cacheMaintenanceResponse.valid := False
  execution.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, execution.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )

  io.directWakeupValid := execution.io.directWakeupValid(multiplyPort)
  io.directWakeupPdst := execution.io.directWakeupPdst(multiplyPort)
  io.completionValid := execution.io.completionValid(config.executionWidth)
  io.completionPdst := execution.io.completion(config.executionWidth).pdst
  io.completionData := execution.io.completion(config.executionWidth).data
}

private final class OooBarrierExecutionProbe(config: OooCoreConfig) extends Component {
  private val csrPort = config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Csr))

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val issueValid = in Bool ()
    val pdst = in UInt (config.physicalRegIndexWidth bits)
    val olderStorePending = in Bool ()
    val memorySubsystemIdle = in Bool ()
    val maintenanceReady = in Bool ()
    val maintenanceDone = in Bool ()
    val flush = in Bool ()
    val issueReady = out Bool ()
    val barrierActive = out Bool ()
    val barrierRobPointer = out UInt (config.robPointerWidth bits)
    val maintenanceStart = out Bool ()
    val completionValid = out Bool ()
    val completionRobPointer = out UInt (config.robPointerWidth bits)
    val completionRecoveryEpoch = out UInt (config.recoveryEpochWidth bits)
    val directWakeupValid = out Bool ()
    val directWakeupPdst = out UInt (config.physicalRegIndexWidth bits)
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  execution.io.issueValid(csrPort) := io.issueValid
  for (port <- 0 until config.executionWidth) {
    if (port == csrPort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := io.pdst
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 11
      execution.io.issue(port).recoveryEpoch := 7
      execution.io.issue(port).loadQueueIndex := 0
      execution.io.issue(port).storeQueueIndex := 0
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
    }
    execution.io.source1(port) := 0
    execution.io.source2(port) := 0
  }
  execution.io.flush := io.flush
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := io.olderStorePending
  execution.io.memorySubsystemIdle := io.memorySubsystemIdle
  execution.io.instructionBarrierMaintenanceReady := io.maintenanceReady
  execution.io.instructionBarrierMaintenanceDone := io.maintenanceDone
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  execution.io.cacheMaintenanceResponse.valid := False
  execution.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, execution.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )

  io.issueReady := execution.io.issueReady(csrPort)
  io.barrierActive := execution.io.barrierActive
  io.barrierRobPointer := execution.io.barrierRobPointer
  io.maintenanceStart := execution.io.instructionBarrierMaintenanceStart
  io.completionValid := execution.io.completionValid(csrPort)
  io.completionRobPointer := execution.io.completion(csrPort).robPointer
  io.completionRecoveryEpoch := execution.io.completion(csrPort).recoveryEpoch
  io.directWakeupValid := execution.io.directWakeupValid(csrPort)
  io.directWakeupPdst := execution.io.directWakeupPdst(csrPort)
}

private final class OooCacopExecutionProbe(config: OooCoreConfig) extends Component {
  private val csrPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Csr))

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val source1 = in Bits (config.xlen bits)
    val privilege = in Bits (2 bits)
    val issueValid = in Bool ()
    val olderStorePending = in Bool ()
    val memorySubsystemIdle = in Bool ()
    val flush = in Bool ()
    val issueReady = out Bool ()
    val translationReady = in Bool ()
    val translationResponseValid = in Bool ()
    val translationCancelled = in Bool ()
    val translationPhysicalAddress = in UInt (config.xlen bits)
    val translationException = in(ExceptionMetadata())
    val translationRequestValid = out Bool ()
    val translationVirtualAddress = out UInt (config.xlen bits)
    val maintenanceReady = in Bool ()
    val maintenanceValid = out Bool ()
    val maintenanceRequest = out(CacheMaintenanceRequest(config))
    val maintenanceResponseValid = in Bool ()
    val maintenanceResponseRobPointer = in UInt (config.robPointerWidth bits)
    val maintenanceResponseRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val maintenanceResponseReady = out Bool ()
    val completionValid = out Bool ()
    val completion = out(Completion(config))
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := io.privilege
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  execution.io.issueValid(csrPort) := io.issueValid
  for (port <- 0 until config.executionWidth) {
    if (port == csrPort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := 0
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 11
      execution.io.issue(port).recoveryEpoch := 7
      execution.io.issue(port).loadQueueIndex := 0
      execution.io.issue(port).storeQueueIndex := 0
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
    }
    if (port == csrPort) {
      execution.io.source1(port) := io.source1
    } else {
      execution.io.source1(port) := 0
    }
    execution.io.source2(port) := 0
  }
  execution.io.flush := io.flush
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := io.olderStorePending
  execution.io.memorySubsystemIdle := io.memorySubsystemIdle
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := io.translationReady
  execution.io.cacheTranslationResponse.valid := io.translationResponseValid
  execution.io.cacheTranslationResponse.virtualAddress :=
    execution.io.cacheTranslationRequest.virtualAddress
  execution.io.cacheTranslationResponse.physicalAddress := io.translationPhysicalAddress
  execution.io.cacheTranslationResponse.uncached := False
  execution.io.cacheTranslationResponse.cancelled := io.translationCancelled
  execution.io.cacheTranslationResponse.exception := io.translationException
  execution.io.cacheMaintenanceRequest.ready := io.maintenanceReady
  execution.io.cacheMaintenanceResponse.valid := io.maintenanceResponseValid
  execution.io.cacheMaintenanceResponse.robPointer := io.maintenanceResponseRobPointer
  execution.io.cacheMaintenanceResponse.recoveryEpoch :=
    io.maintenanceResponseRecoveryEpoch

  io.issueReady := execution.io.issueReady(csrPort)
  io.translationRequestValid := execution.io.cacheTranslationRequest.valid
  io.translationVirtualAddress := execution.io.cacheTranslationRequest.virtualAddress
  io.maintenanceValid := execution.io.cacheMaintenanceRequest.valid
  io.maintenanceRequest := execution.io.cacheMaintenanceRequest.payload
  io.maintenanceResponseReady := execution.io.cacheMaintenanceResponse.ready
  io.completionValid := execution.io.completionValid(csrPort)
  io.completion := execution.io.completion(csrPort)
}

private final class OooCpuCfgExecutionProbe(config: OooCoreConfig) extends Component {
  private val csrPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Csr))

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val source1 = in Bits (config.xlen bits)
    val systemReadData = in Bits (config.xlen bits)
    val issueValid = in Bool ()
    val issueReady = out Bool ()
    val systemReadValid = out Bool ()
    val systemReadAddress = out UInt (14 bits)
    val completionValid = out Bool ()
    val completion = out(Completion(config))
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector + 4, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 1
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 8, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  execution.io.issueValid(csrPort) := io.issueValid
  for (port <- 0 until config.executionWidth) {
    if (port == csrPort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := 33
      execution.io.issue(port).oldPdst := 17
      execution.io.issue(port).psrc1 := 12
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 1
      execution.io.issue(port).recoveryEpoch := 2
      execution.io.issue(port).loadQueueIndex := 0
      execution.io.issue(port).storeQueueIndex := 0
      execution.io.source1(port) := io.source1
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
      execution.io.source1(port) := 0
    }
    execution.io.source2(port) := 0
  }
  execution.io.flush := False
  execution.io.systemReadData := io.systemReadData
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := False
  execution.io.memorySubsystemIdle := True
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  execution.io.cacheMaintenanceResponse.valid := False
  execution.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, execution.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )

  io.issueReady := execution.io.issueReady(csrPort)
  io.systemReadValid := execution.io.systemReadValid
  io.systemReadAddress := execution.io.systemReadAddress
  io.completionValid := execution.io.completionValid(csrPort)
  io.completion := execution.io.completion(csrPort)
}

class OooExecutionClusterSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("CPUCFG reads the selected configuration word and completes directly") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster-cpucfg")
      .compile(new OooCpuCfgExecutionProbe(config))
      .doSim("ooo-execution-cluster-cpucfg", 0x4350) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= BigInt("00006d91", 16)
        dut.io.source1 #= 16
        dut.io.systemReadData #= BigInt("0000001d", 16)
        dut.io.issueValid #= true

        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        assert(dut.io.systemReadValid.toBoolean)
        assert(dut.io.systemReadAddress.toBigInt == 0xc0)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.recoveryEpoch.toBigInt == 2)
        assert(dut.io.completion.pdst.toBigInt == 33)
        assert(dut.io.completion.writesPdst.toBoolean)
        assert(dut.io.completion.data.toBigInt == BigInt("0000001d", 16))
      }
  }

  test("CACOP serializes while PRELD completes without allocating an LSQ entry") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooExecutionClusterProbe(config))
      .doSim("ooo-execution-cluster-cache-hints", 0x4c67) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.issueValid #= false
        dut.io.aguReady #= false
        dut.io.loadStoreCompletionValid #= false

        dut.io.instruction #= BigInt("06000000", 16)
        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.systemOperation.toBigInt == 17)
        assert(dut.io.issueReady.toBoolean)
        assert(!dut.io.aguValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.issueValid #= false
        var cacopCycles = 0
        while (!dut.io.completionValid.toBoolean && cacopCycles < 20) {
          dut.clockDomain.waitSampling()
          cacopCycles += 1
        }
        assert(dut.io.completionValid.toBoolean)

        for (code <- Seq(0x17, 0x19)) {
          dut.clockDomain.waitSampling()
          dut.io.instruction #= BigInt("06000000", 16) | code
          dut.io.issueValid #= true
          sleep(1)
          assert(dut.io.systemOperation.toBigInt == 0)
          assert(dut.io.issueReady.toBoolean)
          assert(!dut.io.aguValid.toBoolean)
          assert(dut.io.completionValid.toBoolean)
          dut.clockDomain.waitSampling()
          dut.io.issueValid #= false
        }

        dut.io.instruction #= BigInt("2ac00000", 16)
        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.systemOperation.toBigInt == 18)
        assert(!dut.io.isLoad.toBoolean)
        assert(!dut.io.isStore.toBoolean)
        assert(dut.io.issueReady.toBoolean)
        assert(!dut.io.aguValid.toBoolean)
        assert(dut.io.completionValid.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.issueValid #= false

        dut.io.instruction #= BigInt("28800000", 16)
        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.isLoad.toBoolean)
        assert(!dut.io.issueReady.toBoolean)
        assert(!dut.io.aguValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)

        dut.io.aguReady #= true
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        assert(dut.io.aguValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)

        dut.io.issueValid #= false
      }
  }

  test("a divider return backpressures a direct ALU sharing its writeback lane") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooDivideCompletionCollisionProbe(config))
      .doSim("ooo-execution-cluster-divider-return-arbitration", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.issueValid #= false
        dut.io.instruction #= 0
        dut.io.source1 #= 0
        dut.io.source2 #= 0
        dut.io.robPointer #= 0
        dut.io.recoveryEpoch #= 0
        dut.io.pdst #= 0
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        // mod.w r15, r12, r13
        dut.io.instruction #= BigInt("0020b58f", 16)
        dut.io.source1 #= 100
        dut.io.source2 #= 3
        dut.io.robPointer #= 5
        dut.io.pdst #= 10
        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        assert(!dut.io.directWakeupValid.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.issueValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 40) {
          dut.clockDomain.waitSampling()
          sleep(1)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completionRobPointer.toBigInt == 5)
        assert(!dut.io.directWakeupValid.toBoolean)

        // ori r12, r12, imm would otherwise be accepted and overwritten by
        // the divider result on this exact cycle.
        dut.io.instruction #= BigInt("039b658c", 16)
        dut.io.robPointer #= 6
        dut.io.pdst #= 11
        dut.io.issueValid #= true
        sleep(1)
        assert(!dut.io.issueReady.toBoolean)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completionRobPointer.toBigInt == 5)
        assert(!dut.io.directWakeupValid.toBoolean)

        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completionRobPointer.toBigInt == 6)
        assert(dut.io.directWakeupValid.toBoolean)
        assert(dut.io.directWakeupPdst.toBigInt == 11)
      }
  }

  test("recovery can be removed from the direct wake candidate without accepting work") {
    for ((decoupled, name, seed) <- Seq(
        (false, "fire-qualified", 0x4c69),
        (true, "flush-decoupled", 0x4c6a)
      )) {
      val testConfig = config.copy(enableFlushDecoupledDirectWakeup = decoupled)
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-execution-cluster-wake-$name"
        )
        .compile(new OooDivideCompletionCollisionProbe(testConfig))
        .doSim(s"ooo-execution-cluster-wake-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          dut.io.instruction #= BigInt("039b658c", 16) // ori r12, r12, imm
          dut.io.source1 #= 0
          dut.io.source2 #= 0
          dut.io.robPointer #= 6
          dut.io.recoveryEpoch #= 0
          dut.io.pdst #= 11
          dut.io.issueValid #= true
          dut.io.flush #= true
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sleep(1)

          assert(!dut.io.issueReady.toBoolean)
          assert(!dut.io.completionValid.toBoolean)
          assert(dut.io.directWakeupValid.toBoolean == decoupled)

          dut.clockDomain.waitSampling()
          dut.io.flush #= false
          sleep(1)
          assert(dut.io.issueReady.toBoolean)
          assert(dut.io.directWakeupValid.toBoolean)
          assert(dut.io.directWakeupPdst.toBigInt == 11)
          assert(dut.io.completionValid.toBoolean)
          assert(dut.io.completionRobPointer.toBigInt == 6)
        }
    }
  }

  test("the active divider implements all DIV/MOD modes and flushes every iteration") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(
        new OooDivideCompletionCollisionProbe(config.copy(enableDivideFastPath = false))
      )
      .doSim("ooo-divider-architecture-and-flush", 0x4c77) { dut =>
        val mask = (BigInt(1) << 32) - 1
        val signBit = BigInt(1) << 31
        val modulus = BigInt(1) << 32
        val divW = BigInt("00200000", 16)
        val modW = BigInt("00208000", 16)
        val divWu = BigInt("00210000", 16)
        val modWu = BigInt("00218000", 16)
        val operandFields = (BigInt(13) << 10) | (BigInt(12) << 5) | BigInt(15)

        def bits32(value: BigInt): BigInt = value & mask
        def signed32(value: BigInt): BigInt = {
          val unsigned = bits32(value)
          if ((unsigned & signBit) != 0) unsigned - modulus else unsigned
        }
        def expected(dividend: BigInt, divisor: BigInt, signed: Boolean, remainder: Boolean): BigInt = {
          val lhs = if (signed) signed32(dividend) else bits32(dividend)
          val rhs = if (signed) signed32(divisor) else bits32(divisor)
          if (rhs == 0) {
            if (remainder) bits32(dividend) else mask
          } else if (remainder) {
            bits32(lhs % rhs)
          } else {
            bits32(lhs / rhs)
          }
        }

        dut.clockDomain.forkStimulus(period = 10)
        dut.io.issueValid #= false
        dut.io.instruction #= 0
        dut.io.source1 #= 0
        dut.io.source2 #= 0
        dut.io.robPointer #= 0
        dut.io.recoveryEpoch #= 0
        dut.io.pdst #= 0
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        var nextToken = 1
        def launch(opcode: BigInt, dividend: BigInt, divisor: BigInt): (Int, Int, Int) = {
          val rob = nextToken & ((1 << config.robPointerWidth) - 1)
          val epoch = nextToken & ((1 << config.recoveryEpochWidth) - 1)
          val pdst = 1 + (nextToken % (config.physicalRegs - 1))
          nextToken += 1
          dut.io.instruction #= opcode | operandFields
          dut.io.source1 #= bits32(dividend)
          dut.io.source2 #= bits32(divisor)
          dut.io.robPointer #= rob
          dut.io.recoveryEpoch #= epoch
          dut.io.pdst #= pdst
          dut.io.issueValid #= true
          sleep(1)
          assert(dut.io.issueReady.toBoolean)
          assert(!dut.io.completionValid.toBoolean)
          dut.clockDomain.waitSampling()
          dut.io.issueValid #= false
          (rob, epoch, pdst)
        }

        def awaitExactlyOneCompletion(
            token: (Int, Int, Int),
            expectedResult: BigInt
        ): Unit = {
          var waited = 0
          while (!dut.io.completionValid.toBoolean && waited < 40) {
            dut.clockDomain.waitSampling()
            sleep(1)
            waited += 1
          }
          assert(dut.io.completionValid.toBoolean)
          assert(dut.io.completionRobPointer.toBigInt == token._1)
          assert(dut.io.completionRecoveryEpoch.toBigInt == token._2)
          assert(dut.io.completionPdst.toBigInt == token._3)
          assert(dut.io.completionData.toBigInt == expectedResult)
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(!dut.io.completionValid.toBoolean)
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(!dut.io.completionValid.toBoolean)
        }

        val directed = Seq(
          (BigInt(100), BigInt(3)),
          (BigInt(-100), BigInt(3)),
          (BigInt(100), BigInt(-3)),
          (BigInt(-100), BigInt(-3)),
          (BigInt(0), BigInt(7)),
          (BigInt(7), BigInt(0)),
          (BigInt(-7), BigInt(0)),
          (-signBit, BigInt(-1)),
          (-signBit, BigInt(1)),
          (mask, BigInt(1)),
          (mask, mask),
          (mask, BigInt(2))
        )
        val random = new scala.util.Random(0x4c77)
        val randomVectors = Seq.fill(64) {
          (BigInt(random.nextInt()) & mask, BigInt(random.nextInt()) & mask)
        }
        val operations = Seq(
          (divW, true, false),
          (modW, true, true),
          (divWu, false, false),
          (modWu, false, true)
        )
        for ((opcode, signed, remainder) <- operations; (dividend, divisor) <- directed ++ randomVectors) {
          val token = launch(opcode, dividend, divisor)
          awaitExactlyOneCompletion(token, expected(dividend, divisor, signed, remainder))
        }

        for (flushIteration <- 0 until 32) {
          launch(divW, BigInt(-0x40000000), BigInt(37))
          for (_ <- 0 until flushIteration) {
            dut.clockDomain.waitSampling()
            sleep(1)
            assert(!dut.io.completionValid.toBoolean)
          }
          dut.io.flush #= true
          dut.clockDomain.waitSampling()
          dut.io.flush #= false
          for (_ <- 0 until 3) {
            sleep(1)
            assert(!dut.io.completionValid.toBoolean)
            dut.clockDomain.waitSampling()
          }

          val restartDividend = BigInt(0x70000000L + flushIteration)
          val restartDivisor = BigInt(flushIteration + 1)
          val restartToken = launch(modWu, restartDividend, restartDivisor)
          awaitExactlyOneCompletion(
            restartToken,
            expected(restartDividend, restartDivisor, signed = false, remainder = true)
          )
        }
      }
  }

  test("divider fast paths are one-cycle and independently configurable") {
    val divW = BigInt("00200000", 16)
    val modW = BigInt("00208000", 16)
    val divWu = BigInt("00210000", 16)
    val modWu = BigInt("00218000", 16)
    val operandFields = (BigInt(13) << 10) | (BigInt(12) << 5) | BigInt(15)
    val mask = (BigInt(1) << 32) - 1

    for ((enabled, name, seed) <- Seq(
        (false, "iterative", 0x4c7a),
        (true, "fast", 0x4c7b)
      )) {
      val testConfig = config.copy(enableDivideFastPath = enabled)
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-divider-$name")
        .compile(new OooDivideCompletionCollisionProbe(testConfig))
        .doSim(s"ooo-divider-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          dut.io.issueValid #= false
          dut.io.instruction #= 0
          dut.io.source1 #= 0
          dut.io.source2 #= 0
          dut.io.robPointer #= 9
          dut.io.recoveryEpoch #= 3
          dut.io.pdst #= 17
          dut.io.flush #= false
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()

          def launchAndCheck(
              opcode: BigInt,
              dividend: BigInt,
              divisor: BigInt,
              expected: BigInt,
              expectImmediate: Boolean
          ): Unit = {
            dut.io.instruction #= opcode | operandFields
            dut.io.source1 #= dividend & mask
            dut.io.source2 #= divisor & mask
            dut.io.issueValid #= true
            sleep(1)
            assert(dut.io.issueReady.toBoolean)
            dut.clockDomain.waitSampling()
            dut.io.issueValid #= false
            sleep(1)
            assert(dut.io.completionValid.toBoolean == expectImmediate)
            var waited = 0
            while (!dut.io.completionValid.toBoolean && waited < 40) {
              dut.clockDomain.waitSampling()
              sleep(1)
              waited += 1
            }
            assert(dut.io.completionValid.toBoolean)
            assert(dut.io.completionData.toBigInt == (expected & mask))
            assert(dut.io.completionRobPointer.toBigInt == 9)
            dut.clockDomain.waitSampling()
            sleep(1)
            assert(!dut.io.completionValid.toBoolean)
          }

          launchAndCheck(divW, 7, 0, mask, expectImmediate = enabled)
          launchAndCheck(modWu, BigInt("fedcba98", 16), 0, BigInt("fedcba98", 16), enabled)
          launchAndCheck(divW, -123, -1, 123, expectImmediate = enabled)
          launchAndCheck(modW, -123, -1, 0, expectImmediate = enabled)
          launchAndCheck(divWu, 0, 37, 0, expectImmediate = enabled)
          launchAndCheck(divWu, 100, 3, 33, expectImmediate = false)
        }
    }
  }

  test("multiply wakes on issue and forwards its registered result one cycle later") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooMultiplyWakeupProbe(config))
      .doSim("ooo-execution-cluster-multiply-forward", 0x4c69) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= BigInt("001c082d", 16) // mul.w r13,r1,r2
        dut.io.source1 #= 7
        dut.io.source2 #= 9
        dut.io.pdst #= 10
        dut.io.issueValid #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.directWakeupValid.toBoolean)
        assert(dut.io.directWakeupPdst.toBigInt == 10)
        assert(!dut.io.completionValid.toBoolean)

        dut.clockDomain.waitSampling()
        dut.io.issueValid #= false
        sleep(1)
        assert(!dut.io.directWakeupValid.toBoolean)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completionPdst.toBigInt == 10)
        assert(dut.io.completionData.toBigInt == 63)
      }
  }

  test("DBAR registers subsystem quiescence before two stable observations") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooBarrierExecutionProbe(config))
      .doSim("ooo-dbar-quiescent", 0x4c62) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= BigInt("38720000", 16)
        dut.io.issueValid #= false
        dut.io.pdst #= 0
        dut.io.olderStorePending #= true
        dut.io.memorySubsystemIdle #= false
        dut.io.maintenanceReady #= true
        dut.io.maintenanceDone #= false
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.issueValid #= false
        assert(dut.io.barrierActive.toBoolean)
        assert(dut.io.barrierRobPointer.toBigInt == 11)

        dut.clockDomain.waitSampling(3)
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        dut.io.olderStorePending #= false
        dut.clockDomain.waitSampling(2)
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)

        dut.io.memorySubsystemIdle #= true
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completionRobPointer.toBigInt == 11)
        assert(dut.io.completionRecoveryEpoch.toBigInt == 7)
        assert(!dut.io.maintenanceStart.toBoolean)
      }
  }

  test("a P0 producer cannot wake before a busy barrier accepts it") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooBarrierExecutionProbe(config))
      .doSim("ooo-p0-accept-before-wakeup", 0x4c76) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= BigInt("38720000", 16) // dbar
        dut.io.issueValid #= false
        dut.io.pdst #= 0
        dut.io.olderStorePending #= true
        dut.io.memorySubsystemIdle #= false
        dut.io.maintenanceReady #= true
        dut.io.maintenanceDone #= false
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        dut.io.issueValid #= true
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.issueValid #= false
        sleep(1)
        assert(dut.io.barrierActive.toBoolean)

        dut.io.instruction #= BigInt("039b658c", 16) // ori r12, r12, imm
        dut.io.pdst #= 17
        dut.io.issueValid #= true
        sleep(1)
        assert(!dut.io.issueReady.toBoolean)
        assert(!dut.io.directWakeupValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)

        dut.clockDomain.waitSampling(2)
        sleep(1)
        assert(!dut.io.issueReady.toBoolean)
        assert(!dut.io.directWakeupValid.toBoolean)

        dut.io.flush #= true
        sleep(1)
        assert(!dut.io.directWakeupValid.toBoolean)
      }
  }

  test("IBAR waits for maintenance and drops completion after a maintenance-time flush") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-execution-cluster")
      .compile(new OooBarrierExecutionProbe(config))
      .doSim("ooo-ibar-maintenance-flush", 0x4c63) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= BigInt("38728000", 16)
        dut.io.issueValid #= false
        dut.io.pdst #= 0
        dut.io.olderStorePending #= false
        dut.io.memorySubsystemIdle #= true
        dut.io.maintenanceReady #= true
        dut.io.maintenanceDone #= false
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        def startBarrier(): Unit = {
          dut.io.issueValid #= true
          dut.clockDomain.waitSampling()
          sleep(1)
          dut.io.issueValid #= false
          dut.clockDomain.waitSampling(2)
          sleep(1)
          assert(dut.io.maintenanceStart.toBoolean)
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(!dut.io.maintenanceStart.toBoolean)
        }

        startBarrier()
        dut.clockDomain.waitSampling(3)
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        dut.io.maintenanceDone #= true
        dut.clockDomain.waitSampling()
        dut.io.maintenanceDone #= false
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.completionValid.toBoolean)

        dut.clockDomain.waitSampling()
        startBarrier()
        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.flush #= false
        sleep(1)
        assert(dut.io.barrierActive.toBoolean)
        dut.io.maintenanceDone #= true
        dut.clockDomain.waitSampling()
        dut.io.maintenanceDone #= false
        for (_ <- 0 until 4) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(!dut.io.completionValid.toBoolean)
        }
        assert(!dut.io.barrierActive.toBoolean)
      }
  }

  test("CACOP translates only Hit operations and preserves precise recovery tokens") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-cacop-execution")
      .compile(new OooCacopExecutionProbe(config))
      .doSim("ooo-cacop-translation-recovery", 0x4c64) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }
        def clearInputs(): Unit = {
          dut.io.instruction #= 0
          dut.io.source1 #= 0
          dut.io.privilege #= 0
          dut.io.issueValid #= false
          dut.io.olderStorePending #= false
          dut.io.memorySubsystemIdle #= true
          dut.io.flush #= false
          dut.io.translationReady #= true
          dut.io.translationResponseValid #= false
          dut.io.translationCancelled #= false
          dut.io.translationPhysicalAddress #= 0
          dut.io.translationException.valid #= false
          dut.io.translationException.ecode #= 0
          dut.io.translationException.esubcode #= 0
          dut.io.translationException.badVAddrValid #= false
          dut.io.translationException.badVAddr #= 0
          dut.io.translationException.tlbRefill #= false
          dut.io.maintenanceReady #= true
          dut.io.maintenanceResponseValid #= false
          dut.io.maintenanceResponseRobPointer #= 11
          dut.io.maintenanceResponseRecoveryEpoch #= 7
        }
        def issue(code: Int, address: BigInt, privilege: Int = 0): Unit = {
          dut.io.instruction #= BigInt("06000000", 16) | code
          dut.io.source1 #= address
          dut.io.privilege #= privilege
          dut.io.issueValid #= true
          sleep(1)
          assert(dut.io.issueReady.toBoolean)
          sample()
          dut.io.issueValid #= false
        }
        def respondMaintenance(): Unit = {
          dut.io.maintenanceResponseValid #= true
          sleep(1)
          assert(dut.io.maintenanceResponseReady.toBoolean)
          sample()
          dut.io.maintenanceResponseValid #= false
        }
        def waitForCompletion(): Unit = {
          var cycles = 0
          while (!dut.io.completionValid.toBoolean && cycles < 16) {
            sample()
            cycles += 1
          }
          assert(dut.io.completionValid.toBoolean)
          assert(dut.io.completion.robPointer.toBigInt == 11)
          assert(dut.io.completion.recoveryEpoch.toBigInt == 7)
        }

        dut.clockDomain.forkStimulus(period = 10)
        clearInputs()
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        // Unaligned Index requests use VA directly and never ask the TLB.
        issue(code = 0x09, address = 0x123)
        var cycles = 0
        while (!dut.io.maintenanceValid.toBoolean && cycles < 8) {
          assert(!dut.io.translationRequestValid.toBoolean)
          sample()
          cycles += 1
        }
        assert(dut.io.maintenanceValid.toBoolean)
        assert(dut.io.maintenanceRequest.code.toBigInt == 0x09)
        assert(dut.io.maintenanceRequest.virtualAddress.toBigInt == 0x123)
        assert(dut.io.maintenanceRequest.physicalAddress.toBigInt == 0)
        sample()
        respondMaintenance()
        waitForCompletion()
        assert(!dut.io.completion.exception.valid.toBoolean)
        sample()

        // A PLV3 Hit request behaves like a load translation and reports its exact fault.
        dut.io.translationReady #= false
        issue(code = 0x11, address = 0x125, privilege = 3)
        cycles = 0
        while (!dut.io.translationRequestValid.toBoolean && cycles < 8) {
          sample()
          cycles += 1
        }
        assert(dut.io.translationRequestValid.toBoolean)
        assert(dut.io.translationVirtualAddress.toBigInt == 0x125)
        assert(!dut.io.maintenanceValid.toBoolean)
        dut.io.translationReady #= true
        sample()
        dut.io.translationResponseValid #= true
        dut.io.translationException.valid #= true
        dut.io.translationException.ecode #= 1
        dut.io.translationException.badVAddrValid #= true
        dut.io.translationException.badVAddr #= 0x125
        sample()
        dut.io.translationResponseValid #= false
        dut.io.translationException.valid #= false
        waitForCompletion()
        assert(dut.io.completion.exception.valid.toBoolean)
        assert(dut.io.completion.exception.ecode.toBigInt == 1)
        assert(dut.io.completion.exception.badVAddr.toBigInt == 0x125)
        assert(!dut.io.maintenanceValid.toBoolean)
        sample()

        // Successful Hit translation forwards PA, then a flush drops the late response token.
        dut.io.translationReady #= false
        issue(code = 0x12, address = 0x127)
        while (!dut.io.translationRequestValid.toBoolean) sample()
        dut.io.translationReady #= true
        sample()
        dut.io.translationReady #= false
        dut.io.translationCancelled #= true
        dut.io.translationResponseValid #= true
        sample()
        dut.io.translationResponseValid #= false
        dut.io.translationCancelled #= false
        sleep(1)
        assert(dut.io.translationRequestValid.toBoolean)
        assert(!dut.io.maintenanceValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)
        dut.io.translationReady #= true
        sample()
        dut.io.translationResponseValid #= true
        dut.io.translationPhysicalAddress #= 0x40127
        sample()
        dut.io.translationResponseValid #= false
        while (!dut.io.maintenanceValid.toBoolean) sample()
        assert(dut.io.maintenanceRequest.code.toBigInt == 0x12)
        assert(dut.io.maintenanceRequest.virtualAddress.toBigInt == 0x127)
        assert(dut.io.maintenanceRequest.physicalAddress.toBigInt == 0x40127)
        sample()
        dut.io.flush #= true
        sample()
        dut.io.flush #= false
        respondMaintenance()
        for (_ <- 0 until 6) {
          sample()
          assert(!dut.io.completionValid.toBoolean)
        }
      }
  }
}
