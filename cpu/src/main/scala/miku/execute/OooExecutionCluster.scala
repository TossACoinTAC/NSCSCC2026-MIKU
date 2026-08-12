package miku.execute

import miku.backend._
import miku.core._
import miku.memory._
import miku.privileged._
import spinal.core._
import spinal.lib._

final case class AddressGenerationRequest(config: OooCoreConfig) extends Bundle {
  val uop = RenamedMicroOp(config)
  val virtualAddress = UInt(config.xlen bits)
  val isWrite = Bool()
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val writeData = Bits(config.xlen bits)
}

final class MultiplyPipeline(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val valid = in Bool ()
    val uop = in(RenamedMicroOp(config))
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val flush = in Bool ()
    val completionValid = out Bool ()
    val completion = out(Completion(config))
  }

  val valid = RegInit(False)
  val uop = Reg(RenamedMicroOp(config))
  val result = Reg(Bits(config.xlen bits))
  val unsignedProduct = (io.source1.asUInt * io.source2.asUInt).resize(64).asBits
  val signedProduct = (io.source1.asSInt * io.source2.asSInt).resize(64).asBits
  val product = Mux(io.uop.decoded.mulDivSigned, signedProduct, unsignedProduct)
  val selectedResult =
    Mux(io.uop.decoded.mulDivOperation(1), product(63 downto 32), product(31 downto 0))

  valid := io.valid
  when(io.valid) {
    uop := io.uop
    result := selectedResult
  }
  when(io.flush) { valid := False }

  io.completionValid := valid
  io.completion.robPointer := uop.robPointer
  io.completion.recoveryEpoch := uop.recoveryEpoch
  io.completion.pdst := uop.pdst
  io.completion.writesPdst := uop.pdst =/= 0
  io.completion.data := result
  io.completion.sideEffectData := B(0, config.xlen bits)
  io.completion.exception := uop.decoded.exception
  io.completion.branchResolved := False
  io.completion.branchTaken := False
  io.completion.branchTarget := U(0, config.xlen bits)
  io.completion.branchMispredict := False
}

final class DivideUnit(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val start = in Bool ()
    val uop = in(RenamedMicroOp(config))
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val flush = in Bool ()
    val ready = out Bool ()
    val completionValid = out Bool ()
    val completion = out(Completion(config))
  }

  val busy = RegInit(False)
  val completionValid = RegInit(False)
  val uop = Reg(RenamedMicroOp(config))
  val divisor = Reg(UInt(config.xlen bits))
  val quotient = Reg(UInt(config.xlen bits))
  val remainder = Reg(UInt((config.xlen + 1) bits))
  val originalDividend = Reg(UInt(config.xlen bits))
  val quotientNegative = Reg(Bool())
  val remainderNegative = Reg(Bool())
  val divideByZero = Reg(Bool())
  val count = Reg(UInt(6 bits))
  val result = Reg(Bits(config.xlen bits))

  val source1Magnitude = UInt(config.xlen bits)
  val source2Magnitude = UInt(config.xlen bits)
  source1Magnitude := io.source1.asUInt
  source2Magnitude := io.source2.asUInt
  when(io.uop.decoded.mulDivSigned && io.source1.msb) {
    source1Magnitude := U(0, config.xlen bits) - io.source1.asUInt
  }
  when(io.uop.decoded.mulDivSigned && io.source2.msb) {
    source2Magnitude := U(0, config.xlen bits) - io.source2.asUInt
  }

  val shiftedRemainder = (remainder(config.xlen - 1 downto 0) ## quotient.msb).asUInt
  val trial = shiftedRemainder - divisor.resize(config.xlen + 1)
  val trialNegative = trial.msb
  val nextRemainder = Mux(trialNegative, shiftedRemainder, trial)
  val nextQuotient = (quotient(config.xlen - 2 downto 0) ## !trialNegative).asUInt

  val fastDivideByZero = io.source2 === 0
  val fastDividendZero = io.source1 === 0
  val fastPositiveOne = io.source2 === B(1, config.xlen bits)
  val fastNegativeOne = io.uop.decoded.mulDivSigned && io.source2.andR
  val fastPath = if (config.enableDivideFastPath) {
    fastDivideByZero || fastDividendZero || fastPositiveOne || fastNegativeOne
  } else {
    False
  }
  val fastQuotient = Bits(config.xlen bits)
  fastQuotient := io.source1
  when(fastNegativeOne) {
    fastQuotient := (U(0, config.xlen bits) - io.source1.asUInt).asBits
  }
  val fastResult = Bits(config.xlen bits)
  fastResult := Mux(io.uop.decoded.mulDivOperation(3), B(0, config.xlen bits), fastQuotient)
  when(fastDivideByZero) {
    fastResult := Mux(
      io.uop.decoded.mulDivOperation(3),
      io.source1,
      B((BigInt(1) << config.xlen) - 1, config.xlen bits)
    )
  }.elsewhen(fastDividendZero) {
    fastResult := 0
  }

  completionValid := False
  when(io.flush) {
    busy := False
    completionValid := False
  }.elsewhen(io.start && !busy) {
    uop := io.uop
    when(fastPath) {
      busy := False
      result := fastResult
      completionValid := True
    }.otherwise {
      busy := True
      divisor := source2Magnitude
      quotient := source1Magnitude
      remainder := U(0, config.xlen + 1 bits)
      originalDividend := io.source1.asUInt
      quotientNegative := io.uop.decoded.mulDivSigned && (io.source1.msb =/= io.source2.msb)
      remainderNegative := io.uop.decoded.mulDivSigned && io.source1.msb
      divideByZero := io.source2 === 0
      count := U(0, count.getWidth bits)
    }
  }.elsewhen(busy) {
    quotient := nextQuotient
    remainder := nextRemainder
    count := count + 1
    when(count === U(config.xlen - 1, count.getWidth bits)) {
      val quotientMagnitude = UInt(config.xlen bits)
      val remainderMagnitude = UInt(config.xlen bits)
      quotientMagnitude := nextQuotient
      remainderMagnitude := nextRemainder(config.xlen - 1 downto 0)
      when(quotientNegative) {
        quotientMagnitude := U(0, config.xlen bits) - nextQuotient
      }
      when(remainderNegative) {
        remainderMagnitude := U(0, config.xlen bits) - nextRemainder(config.xlen - 1 downto 0)
      }
      when(divideByZero) {
        quotientMagnitude := U((BigInt(1) << config.xlen) - 1, config.xlen bits)
        remainderMagnitude := originalDividend
      }
      result := Mux(
        uop.decoded.mulDivOperation(3),
        remainderMagnitude.asBits,
        quotientMagnitude.asBits
      )
      busy := False
      completionValid := True
    }
  }

  io.ready := !busy
  io.completionValid := completionValid
  io.completion.robPointer := uop.robPointer
  io.completion.recoveryEpoch := uop.recoveryEpoch
  io.completion.pdst := uop.pdst
  io.completion.writesPdst := uop.pdst =/= 0
  io.completion.data := result
  io.completion.sideEffectData := B(0, config.xlen bits)
  io.completion.exception := uop.decoded.exception
  io.completion.branchResolved := False
  io.completion.branchTaken := False
  io.completion.branchTarget := U(0, config.xlen bits)
  io.completion.branchMispredict := False
}

object BarrierState extends SpinalEnum {
  val idle, drain, translationRequest, translationResponse,
    startInstructionMaintenance, waitInstructionMaintenance,
    startCacheMaintenance, waitCacheMaintenance, postDrain, complete,
    dropTranslationResponse, dropInstructionMaintenance, dropCacheMaintenance,
    dropPostDrain = newElement()
}

final class OooExecutionCluster(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val multiplyPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Multiply))
  private val dividePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Divide))
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  private val dedicatedLoadStorePort =
    config.executionPorts(loadStorePort).capabilities == Set(ExecutionUnitKind.LoadStore)
  private val csrPort = config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Csr))
  require(Seq(multiplyPort, dividePort, loadStorePort, csrPort).forall(_ >= 0))
  require(config.writebackWidth >= config.executionWidth + 1)

  val io = new Bundle {
    val issueValid = in Bits (config.executionWidth bits)
    val issue = in Vec (RenamedMicroOp(config), config.executionWidth)
    val source1 = in Vec (Bits(config.xlen bits), config.executionWidth)
    val source2 = in Vec (Bits(config.xlen bits), config.executionWidth)
    val issueReady = out Bits (config.executionWidth bits)
    val flush = in Bool ()
    val systemReadValid = out Bool ()
    val systemReadAddress = out UInt (14 bits)
    val systemReadData = in Bits (config.xlen bits)
    val timer = in Bits (64 bits)
    val timerId = in Bits (config.xlen bits)
    val aguValid = out Bool ()
    val agu = out(AddressGenerationRequest(config))
    val aguReady = in Bool ()
    val loadStoreCompletionValid = in Bool ()
    val loadStoreCompletion = in(Completion(config))
    val olderStorePending = in Bool ()
    val memorySubsystemIdle = in Bool ()
    val barrierActive = out Bool ()
    val barrierRobPointer = out UInt (config.robPointerWidth bits)
    val instructionBarrierMaintenanceStart = out Bool ()
    val instructionBarrierMaintenanceReady = in Bool ()
    val instructionBarrierMaintenanceDone = in Bool ()
    val cacheMaintenanceRequest = master(Stream(CacheMaintenanceRequest(config)))
    val cacheMaintenanceResponse = slave(Stream(CacheMaintenanceResponse(config)))
    val cacheTranslationRequest = master(Stream(TranslationRequest(config)))
    val cacheTranslationResponse = slave(Stream(TranslationResponse(config)))
    val completionValid = out Bits (config.writebackWidth bits)
    val completion = out Vec (Completion(config), config.writebackWidth)
    val directWakeupValid = out Bits (config.executionWidth bits)
    val directWakeupPdst =
      out Vec (UInt(config.physicalRegIndexWidth bits), config.executionWidth)
  }

  private def clearCompletion(completion: Completion): Unit = {
    completion.robPointer := 0
    completion.recoveryEpoch := 0
    completion.pdst := 0
    completion.writesPdst := False
    completion.data := 0
    completion.sideEffectData := 0
    completion.exception.valid := False
    completion.exception.ecode := 0
    completion.exception.esubcode := 0
    completion.exception.badVAddrValid := False
    completion.exception.badVAddr := 0
    completion.exception.tlbRefill := False
    completion.branchResolved := False
    completion.branchTaken := False
    completion.branchTarget := 0
    completion.branchMispredict := False
  }

  val multiplier = new MultiplyPipeline(config)
  multiplier.io.valid := io.issueValid(multiplyPort) && io.issueReady(multiplyPort) &&
    io.issue(multiplyPort).decoded.fuType === ExecutionUnitType.multiply
  multiplier.io.uop := io.issue(multiplyPort)
  multiplier.io.source1 := io.source1(multiplyPort)
  multiplier.io.source2 := io.source2(multiplyPort)
  multiplier.io.flush := io.flush

  val divider = new DivideUnit(config)
  divider.io.start := io.issueValid(dividePort) && io.issueReady(dividePort) &&
    io.issue(dividePort).decoded.fuType === ExecutionUnitType.divide
  divider.io.uop := io.issue(dividePort)
  divider.io.source1 := io.source1(dividePort)
  divider.io.source2 := io.source2(dividePort)
  divider.io.flush := io.flush

  val csrDecoded = io.issue(csrPort).decoded
  val cpuConfigRead = csrDecoded.systemOperation === SystemOperation.cpuConfig
  io.systemReadValid := io.issueValid(csrPort) && io.issueReady(csrPort) &&
    csrDecoded.fuType === ExecutionUnitType.csr
  io.systemReadAddress := Mux(
    cpuConfigRead,
    io.source1(csrPort)(13 downto 0).asUInt + U(0x00b0, 14 bits),
    csrDecoded.csrAddress
  )

  val barrierState = RegInit(BarrierState.idle)
  // Keep the high-fanout P0 acceptance/wakeup cone off the multi-bit barrier
  // FSM.  A captured barrier clears this token on the same edge; after the FSM
  // returns to idle, one conservative recovery cycle restores availability.
  // The extra cycle is paid only after a serializing operation completes.
  val barrierPortAvailable = RegInit(True)
  val barrierUop = Reg(RenamedMicroOp(config))
  val barrierIsInstruction = RegInit(False)
  val barrierIsCache = RegInit(False)
  val barrierVirtualAddress = Reg(UInt(config.xlen bits))
  val barrierPhysicalAddress = Reg(UInt(config.xlen bits))
  val barrierException = Reg(ExceptionMetadata())
  val barrierIdleObserved = RegInit(False)
  val csrIsBarrier = ExecutionUnitType.isBarrier(csrDecoded.fuType)
  val barrierAccept = io.issueValid(csrPort) && io.issueReady(csrPort) && csrIsBarrier
  // Serializing operations already require two consecutive quiescent observations.
  // Register the subsystem-wide reduction before it reaches the multi-bit FSM.
  val barrierQuiescent = RegNext(!io.olderStorePending && io.memorySubsystemIdle) init (False)
  val barrierCacheTarget = barrierUop.decoded.rd(2 downto 0)
  val barrierCacheTargetDefined =
    barrierCacheTarget === CacheMaintenanceTarget.instructionL1 ||
      barrierCacheTarget === CacheMaintenanceTarget.dataL1 ||
      barrierCacheTarget === CacheMaintenanceTarget.unifiedL2
  val instructionMaintenanceFire = io.instructionBarrierMaintenanceStart &&
    io.instructionBarrierMaintenanceReady
  val cacheMaintenanceFire = io.cacheMaintenanceRequest.valid &&
    io.cacheMaintenanceRequest.ready
  val cacheMaintenanceResponseFire = io.cacheMaintenanceResponse.valid &&
    io.cacheMaintenanceResponse.ready
  val translationRequestFire = io.cacheTranslationRequest.valid &&
    io.cacheTranslationRequest.ready
  val translationResponseFire = io.cacheTranslationResponse.valid &&
    io.cacheTranslationResponse.ready

  val barrierReturnsIdle =
    barrierState === BarrierState.complete ||
      (!io.flush &&
        ((barrierState === BarrierState.dropTranslationResponse &&
          translationResponseFire) ||
          (barrierState === BarrierState.dropPostDrain &&
            io.memorySubsystemIdle && barrierIdleObserved))) ||
      (io.flush &&
        (barrierState === BarrierState.idle ||
          barrierState === BarrierState.drain ||
          barrierState === BarrierState.complete ||
          (barrierState === BarrierState.translationRequest &&
            !translationRequestFire) ||
          (barrierState === BarrierState.translationResponse &&
            translationResponseFire) ||
          (barrierState === BarrierState.startInstructionMaintenance &&
            !instructionMaintenanceFire) ||
          (barrierState === BarrierState.startCacheMaintenance &&
            !cacheMaintenanceFire) ||
          (barrierState === BarrierState.dropTranslationResponse &&
            translationResponseFire)))

  when(barrierAccept) {
    barrierPortAvailable := False
  }.elsewhen(barrierState === BarrierState.idle || barrierReturnsIdle) {
    barrierPortAvailable := True
  }

  // Active denotes a captured barrier token.  Keeping acceptance out of this
  // signal lets the LSQ query the incoming ROB pointer on the capture cycle
  // without feeding its result back through issue readiness.
  io.barrierActive := barrierState =/= BarrierState.idle
  io.barrierRobPointer := Mux(
    barrierState === BarrierState.idle,
    io.issue(csrPort).robPointer,
    barrierUop.robPointer
  )
  io.instructionBarrierMaintenanceStart :=
    barrierState === BarrierState.startInstructionMaintenance && !io.flush
  io.cacheMaintenanceRequest.valid :=
    barrierState === BarrierState.startCacheMaintenance && !io.flush
  io.cacheMaintenanceRequest.code := barrierUop.decoded.rd.asBits
  io.cacheMaintenanceRequest.virtualAddress := barrierVirtualAddress
  io.cacheMaintenanceRequest.physicalAddress := barrierPhysicalAddress
  io.cacheMaintenanceRequest.robPointer := barrierUop.robPointer
  io.cacheMaintenanceRequest.recoveryEpoch := barrierUop.recoveryEpoch
  io.cacheMaintenanceResponse.ready :=
    barrierState === BarrierState.waitCacheMaintenance ||
      barrierState === BarrierState.dropCacheMaintenance

  io.cacheTranslationRequest.valid :=
    barrierState === BarrierState.translationRequest && !io.flush
  io.cacheTranslationRequest.virtualAddress := barrierVirtualAddress
  io.cacheTranslationRequest.isWrite := False
  io.cacheTranslationResponse.ready :=
    barrierState === BarrierState.translationResponse ||
      barrierState === BarrierState.dropTranslationResponse

  when(barrierAccept) {
    barrierUop := io.issue(csrPort)
    barrierIsInstruction :=
      csrDecoded.systemOperation === SystemOperation.instructionBarrier
    barrierIsCache := csrDecoded.systemOperation === SystemOperation.cacheOperation
    barrierVirtualAddress := io.source1(csrPort).asUInt + csrDecoded.immediate.asUInt
    barrierPhysicalAddress := 0
    barrierException.valid := False
    barrierException.ecode := 0
    barrierException.esubcode := 0
    barrierException.badVAddrValid := False
    barrierException.badVAddr := 0
    barrierException.tlbRefill := False
    barrierIdleObserved := False
    barrierState := BarrierState.drain
  }
  when(barrierState === BarrierState.drain) {
    when(barrierQuiescent) {
      when(barrierIdleObserved) {
        barrierIdleObserved := False
        when(barrierIsCache) {
          barrierState := Mux(
            barrierUop.decoded.rd(4 downto 3) === CacheMaintenanceMode.hit &&
              barrierCacheTargetDefined,
            BarrierState.translationRequest,
            BarrierState.startCacheMaintenance
          )
        }.otherwise {
          barrierState := Mux(
            barrierIsInstruction,
            BarrierState.startInstructionMaintenance,
            BarrierState.complete
          )
        }
      }.otherwise {
        barrierIdleObserved := True
      }
    }.otherwise {
      barrierIdleObserved := False
    }
  }
  when(
    barrierState === BarrierState.translationRequest && translationRequestFire
  ) {
    barrierState := BarrierState.translationResponse
  }
  when(
    barrierState === BarrierState.translationResponse && translationResponseFire
  ) {
    when(io.cacheTranslationResponse.cancelled) {
      barrierState := BarrierState.translationRequest
    }.otherwise {
      barrierPhysicalAddress := io.cacheTranslationResponse.physicalAddress
      barrierException := io.cacheTranslationResponse.exception
      barrierState := Mux(
        io.cacheTranslationResponse.exception.valid,
        BarrierState.complete,
        BarrierState.startCacheMaintenance
      )
    }
  }
  when(
    barrierState === BarrierState.startInstructionMaintenance &&
      instructionMaintenanceFire
  ) {
    barrierState := BarrierState.waitInstructionMaintenance
  }
  when(
    barrierState === BarrierState.waitInstructionMaintenance &&
      io.instructionBarrierMaintenanceDone
  ) {
    barrierIdleObserved := False
    barrierState := BarrierState.postDrain
  }
  when(
    barrierState === BarrierState.startCacheMaintenance && cacheMaintenanceFire
  ) {
    barrierState := BarrierState.waitCacheMaintenance
  }
  when(
    barrierState === BarrierState.waitCacheMaintenance &&
      cacheMaintenanceResponseFire
  ) {
    GenerationFlags.simulation {
      assert(
        io.cacheMaintenanceResponse.robPointer === barrierUop.robPointer &&
          io.cacheMaintenanceResponse.recoveryEpoch === barrierUop.recoveryEpoch,
        "CACOP completion token must match the captured ROB entry"
      )
    }
    barrierIdleObserved := False
    barrierState := BarrierState.postDrain
  }
  when(barrierState === BarrierState.postDrain) {
    when(barrierQuiescent) {
      when(barrierIdleObserved) {
        barrierIdleObserved := False
        barrierState := BarrierState.complete
      }.otherwise {
        barrierIdleObserved := True
      }
    }.otherwise {
      barrierIdleObserved := False
    }
  }
  when(barrierState === BarrierState.complete) {
    barrierState := BarrierState.idle
  }
  when(
    barrierState === BarrierState.dropInstructionMaintenance &&
      io.instructionBarrierMaintenanceDone
  ) {
    barrierIdleObserved := False
    barrierState := BarrierState.dropPostDrain
  }
  when(
    barrierState === BarrierState.dropCacheMaintenance &&
      cacheMaintenanceResponseFire
  ) {
    barrierIdleObserved := False
    barrierState := BarrierState.dropPostDrain
  }
  when(
    barrierState === BarrierState.dropTranslationResponse && translationResponseFire
  ) {
    barrierState := BarrierState.idle
  }
  when(barrierState === BarrierState.dropPostDrain) {
    when(io.memorySubsystemIdle) {
      when(barrierIdleObserved) {
        barrierIdleObserved := False
        barrierState := BarrierState.idle
      }.otherwise {
        barrierIdleObserved := True
      }
    }.otherwise {
      barrierIdleObserved := False
    }
  }
  when(io.flush) {
    barrierIdleObserved := False
    switch(barrierState) {
      is(BarrierState.translationRequest) {
        barrierState := Mux(
          translationRequestFire,
          BarrierState.dropTranslationResponse,
          BarrierState.idle
        )
      }
      is(BarrierState.translationResponse) {
        barrierState := Mux(
          translationResponseFire,
          BarrierState.idle,
          BarrierState.dropTranslationResponse
        )
      }
      is(BarrierState.startInstructionMaintenance) {
        barrierState := Mux(
          instructionMaintenanceFire,
          BarrierState.dropInstructionMaintenance,
          BarrierState.idle
        )
      }
      is(BarrierState.waitInstructionMaintenance) {
        barrierState := Mux(
          io.instructionBarrierMaintenanceDone,
          BarrierState.dropPostDrain,
          BarrierState.dropInstructionMaintenance
        )
      }
      is(BarrierState.startCacheMaintenance) {
        barrierState := Mux(
          cacheMaintenanceFire,
          BarrierState.dropCacheMaintenance,
          BarrierState.idle
        )
      }
      is(BarrierState.waitCacheMaintenance) {
        barrierState := Mux(
          cacheMaintenanceResponseFire,
          BarrierState.dropPostDrain,
          BarrierState.dropCacheMaintenance
        )
      }
      is(BarrierState.postDrain) {
        barrierState := BarrierState.dropPostDrain
      }
      is(BarrierState.dropInstructionMaintenance) {
        when(io.instructionBarrierMaintenanceDone) {
          barrierState := BarrierState.dropPostDrain
        }
      }
      is(BarrierState.dropCacheMaintenance) {
        when(cacheMaintenanceResponseFire) {
          barrierState := BarrierState.dropPostDrain
        }
      }
      is(BarrierState.dropTranslationResponse) {
        when(translationResponseFire) { barrierState := BarrierState.idle }
      }
      is(BarrierState.dropPostDrain) {
        barrierState := BarrierState.dropPostDrain
      }
      default {
        barrierState := BarrierState.idle
      }
    }
  }

  val barrierCompletionValid = barrierState === BarrierState.complete && !io.flush
  val barrierCompletion = Completion(config)
  barrierCompletion.robPointer := barrierUop.robPointer
  barrierCompletion.recoveryEpoch := barrierUop.recoveryEpoch
  barrierCompletion.pdst := barrierUop.pdst
  barrierCompletion.writesPdst := False
  barrierCompletion.data := 0
  barrierCompletion.sideEffectData := 0
  barrierCompletion.exception := barrierException
  barrierCompletion.branchResolved := False
  barrierCompletion.branchTaken := False
  barrierCompletion.branchTarget := 0
  barrierCompletion.branchMispredict := False

  val lsuDecoded = io.issue(loadStorePort).decoded
  val lsuAddress = io.source1(loadStorePort).asUInt + lsuDecoded.immediate.asUInt

  val directCompletionValid = Bits(config.executionWidth bits)
  val directCompletion = Vec(Completion(config), config.executionWidth)
  for (port <- 0 until config.executionWidth) {
    val decoded = io.issue(port).decoded
    val alu = new Alu
    val aluSource1 = Mux(decoded.source1IsPc, decoded.pc.asBits, io.source1(port))
    val aluSource2 = Mux(
      decoded.source2IsImmediate,
      decoded.immediate,
      Mux(decoded.source2IsFour, B(4, config.xlen bits), io.source2(port))
    )
    alu.io.alu_op := decoded.operation
    alu.io.alu_src1 := aluSource1
    alu.io.alu_src2 := aluSource2

    val isMultiply = decoded.fuType === ExecutionUnitType.multiply
    val isDivide = decoded.fuType === ExecutionUnitType.divide
    val usesAgu = decoded.fuType === ExecutionUnitType.loadStore &&
      (decoded.isLoad || decoded.isStore)
    val isBarrier = ExecutionUnitType.isBarrier(decoded.fuType)
    val direct = !isMultiply && !isDivide && !usesAgu && !isBarrier
    if (port == dividePort) {
      // The divider result and direct ALU result share this writeback lane.
      // Hold a direct issue for one cycle when an older divide completes so
      // the mux below cannot silently discard the direct completion.
      io.issueReady(port) := !io.flush && Mux(
        isDivide,
        divider.io.ready,
        !divider.io.completionValid
      )
    } else if (port == loadStorePort) {
      if (dedicatedLoadStorePort) {
        // The router can only send real Loads/Stores to this lane. PRELD uses
        // an ALU lane, so completion arbitration cannot feed back into LSU
        // issue readiness.
        io.issueReady(port) := !io.flush && io.aguReady
      } else {
        io.issueReady(port) := !io.flush && Mux(
          usesAgu,
          io.aguReady,
          !io.loadStoreCompletionValid
        )
      }
    } else if (port == csrPort) {
      io.issueReady(port) := !io.flush && barrierPortAvailable
    } else {
      io.issueReady(port) := !io.flush
    }

    val fire = io.issueValid(port) && io.issueReady(port)
    val systemReadResult = Bits(config.xlen bits)
    systemReadResult := io.systemReadData
    when(decoded.systemOperation === SystemOperation.counterId) { systemReadResult := io.timerId }
      .elsewhen(decoded.systemOperation === SystemOperation.counterLow) {
        systemReadResult := io.timer(31 downto 0)
      }
      .elsewhen(decoded.systemOperation === SystemOperation.counterHigh) {
        systemReadResult := io.timer(63 downto 32)
      }
    val csrMaskResult = (io.source1(port) & io.source2(port)) |
      (~io.source1(port) & io.systemReadData)
    directCompletionValid(port) := fire && direct
    directCompletion(port).robPointer := io.issue(port).robPointer
    directCompletion(port).recoveryEpoch := io.issue(port).recoveryEpoch
    directCompletion(port).pdst := io.issue(port).pdst
    directCompletion(port).writesPdst := io.issue(port).pdst =/= 0
    directCompletion(port).data := Mux(decoded.resultFromCsr, systemReadResult, alu.io.alu_result)
    directCompletion(port).sideEffectData := Mux(decoded.csrMask, csrMaskResult, io.source2(port))
    when(decoded.systemOperation === SystemOperation.invalidateTlb) {
      directCompletion(port).sideEffectData :=
        io.source2(port)(31 downto 13) ## B(0, 3 bits) ## io.source1(port)(9 downto 0)
    }
    directCompletion(port).exception := decoded.exception

    val equal = io.source1(port) === io.source2(port)
    val lessSigned = io.source1(port).asSInt < io.source2(port).asSInt
    val lessUnsigned = io.source1(port).asUInt < io.source2(port).asUInt
    val branchTaken = Bool()
    branchTaken := decoded.branchKind === 0 || decoded.branchKind === 7
    switch(decoded.branchKind) {
      is(U(1, 3 bits)) { branchTaken := equal }
      is(U(2, 3 bits)) { branchTaken := !equal }
      is(U(3, 3 bits)) { branchTaken := lessSigned }
      is(U(4, 3 bits)) { branchTaken := !lessSigned }
      is(U(5, 3 bits)) { branchTaken := lessUnsigned }
      is(U(6, 3 bits)) { branchTaken := !lessUnsigned }
    }
    val takenTarget = Mux(
      decoded.branchKind === 7,
      io.source1(port).asUInt + decoded.immediate.asUInt,
      decoded.pc + decoded.immediate.asUInt
    )
    val resolvedTarget = Mux(branchTaken, takenTarget, decoded.pc + 4)
    val targetMismatch = branchTaken && decoded.predictedTarget =/= takenTarget
    val branchMispredict = decoded.isBranch &&
      ((decoded.predictedTaken =/= branchTaken) || (decoded.predictedTaken && targetMismatch))
    directCompletion(port).branchResolved := decoded.isBranch
    directCompletion(port).branchTaken := branchTaken
    directCompletion(port).branchTarget := resolvedTarget
    directCompletion(port).branchMispredict := branchMispredict
    // Only one-cycle operations and the fixed-latency multiplier may wake when
    // the issue port accepts them. Keep flush out of this narrow event: IQ
    // flush has priority over ready-bit updates. The shared DIV lane still
    // suppresses a direct wake while its older divide completion owns the lane.
    val singleCycleWake = if (port == dividePort) {
      fire && direct && !divider.io.completionValid &&
      directCompletion(port).writesPdst
    } else {
      fire && direct && directCompletion(port).writesPdst
    }
    val fixedLatencyWake = if (port == multiplyPort) {
      fire && (direct || isMultiply) && directCompletion(port).writesPdst
    } else {
      singleCycleWake
    }
    io.directWakeupValid(port) := fixedLatencyWake
    io.directWakeupPdst(port) := directCompletion(port).pdst
  }

  val addressLow = lsuAddress(1 downto 0)
  val byteMask = (B(1, 4 bits) |<< addressLow).resize(4)
  val halfMask = Mux(addressLow(1), B"1100", B"0011")
  val selectedMask =
    Mux(lsuDecoded.memorySize(0), byteMask, Mux(lsuDecoded.memorySize(1), halfMask, B"1111"))
  val storeData = Bits(config.xlen bits)
  storeData := io.source2(loadStorePort)
  when(lsuDecoded.memorySize(0)) {
    switch(addressLow) {
      is(U(0, 2 bits)) { storeData := B(0, 24 bits) ## io.source2(loadStorePort)(7 downto 0) }
      is(U(1, 2 bits)) {
        storeData := B(0, 16 bits) ## io.source2(loadStorePort)(7 downto 0) ## B(0, 8 bits)
      }
      is(U(2, 2 bits)) {
        storeData := B(0, 8 bits) ## io.source2(loadStorePort)(7 downto 0) ## B(0, 16 bits)
      }
      default { storeData := io.source2(loadStorePort)(7 downto 0) ## B(0, 24 bits) }
    }
  }.elsewhen(lsuDecoded.memorySize(1)) {
    storeData := Mux(
      addressLow(1),
      io.source2(loadStorePort)(15 downto 0) ## B(0, 16 bits),
      B(0, 16 bits) ## io.source2(loadStorePort)(15 downto 0)
    )
  }
  io.aguValid := io.issueValid(loadStorePort) && io.issueReady(loadStorePort) &&
    lsuDecoded.fuType === ExecutionUnitType.loadStore &&
    (lsuDecoded.isLoad || lsuDecoded.isStore)
  io.agu.uop := io.issue(loadStorePort)
  io.agu.virtualAddress := lsuAddress
  io.agu.isWrite := lsuDecoded.isStore
  io.agu.size := Mux(
    lsuDecoded.memorySize(0),
    B(0, 3 bits),
    Mux(lsuDecoded.memorySize(1), B(1, 3 bits), B(2, 3 bits))
  )
  io.agu.byteMask := selectedMask
  io.agu.writeData := storeData

  for (port <- 0 until config.executionWidth) {
    if (port == loadStorePort) {
      io.completionValid(port) := io.loadStoreCompletionValid || directCompletionValid(port)
      io.completion(port) := directCompletion(port)
      when(io.loadStoreCompletionValid) { io.completion(port) := io.loadStoreCompletion }
    } else if (port == dividePort) {
      io.completionValid(port) := directCompletionValid(port) || divider.io.completionValid
      io.completion(port) := directCompletion(port)
      when(divider.io.completionValid) { io.completion(port) := divider.io.completion }
    } else if (port == csrPort) {
      io.completionValid(port) := directCompletionValid(port) || barrierCompletionValid
      io.completion(port) := directCompletion(port)
      when(barrierCompletionValid) { io.completion(port) := barrierCompletion }
    } else {
      io.completionValid(port) := directCompletionValid(port)
      io.completion(port) := directCompletion(port)
    }
  }
  io.completionValid(config.executionWidth) := multiplier.io.completionValid
  io.completion(config.executionWidth) := multiplier.io.completion
  for (lane <- config.executionWidth + 1 until config.writebackWidth) {
    io.completionValid(lane) := False
    clearCompletion(io.completion(lane))
  }
}
