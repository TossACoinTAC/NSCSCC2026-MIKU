package miku.backend

import miku.core._
import miku.execute._
import miku.memory._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class LoadStoreQueueProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocate = in Vec (LoadStoreQueueAllocate(config), config.renameWidth)
    val storeDataEnable = in Bool ()
    val aguValid = in Bool ()
    val agu = in(AddressGenerationRequest(config))
    val aguReady = out Bool ()
    val commitValid = in Bits (config.commitWidth bits)
    val commit = in Vec (CommitRecord(config), config.commitWidth)
    val dataRequestValid = out Bool ()
    val dataRequest = out(CacheRequest(config))
    val dataRequestReady = in Bool ()
    val dataResponseValid = in Bool ()
    val dataResponse = in(CacheResponse(config))
    val translationFault = in Bool ()
    val translationEcode = in UInt (6 bits)
    val translationResponseEnable = in Bool ()
    val translationUncached = in Bool ()
    val translationCancelled = in Bool ()
    val translationPhysicalAddressEnable = in Bool ()
    val translationPhysicalAddress = in UInt (config.xlen bits)
    val translationRequestValid = out Bool ()
    val translationRequestAddress = out UInt (config.xlen bits)
    val translationBypassEligible = in Bool ()
    val translationBypassPhysicalAddress = in UInt (config.xlen bits)
    val translationBypassUncached = in Bool ()
    val reservationValid = in Bool ()
    val reservationLineAddress = in Bits (config.reservationAddressWidth bits)
    val completionValid = out Bool ()
    val completion = out(Completion(config))
    val storeCompletionBypassValid = out Bool ()
    val storeCompletionBypass = out(StoreCompletionIdentity(config))
    val loadWakeupValid = out Bool ()
    val loadWakeupPdst = out UInt (config.physicalRegIndexWidth bits)
    val loadWakeupRecoveryEpoch = out UInt (config.recoveryEpochWidth bits)
    val loadWakeupEpochCurrent = out Bool ()
    val releaseLoadValid = out Bits (config.commitWidth bits)
    val releaseStoreValid = out Bits (config.commitWidth bits)
    val commitMemory = out Vec (MemoryCommitObservation(config), config.commitWidth)
    val storeDrainBusy = out Bool ()
    val olderStorePending = out Bool ()
    val committedMemoryEpoch = in UInt (config.memoryEpochWidth bits)
    val currentRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val robHeadPointer = in UInt (config.robPointerWidth bits)
    val orderingRobPointer = in UInt (config.robPointerWidth bits)
    val flush = in Bool ()
  }
  noIoPrefix()

  val lsq = new LoadStoreQueue(config)
  val translationValid = RegInit(False)
  val translationAddress = Reg(UInt(config.xlen bits))
  lsq.io.allocateValid := io.allocateValid
  lsq.io.allocate := io.allocate
  lsq.io.storeDataValid := io.aguValid && io.agu.isWrite && io.storeDataEnable
  lsq.io.storeDataRobPointer := io.agu.uop.robPointer
  lsq.io.storeDataStoreQueueIndex := io.agu.uop.storeQueueIndex
  lsq.io.storeData := io.agu.writeData
  lsq.io.aguValid := io.aguValid
  lsq.io.agu := io.agu
  lsq.io.commitValid := io.commitValid
  lsq.io.commit := io.commit
  lsq.io.dataRequestReady := io.dataRequestReady
  lsq.io.dataResponseValid := io.dataResponseValid
  lsq.io.dataResponse := io.dataResponse
  lsq.io.flush := io.flush
  lsq.io.translationRequest.ready := !translationValid ||
    (lsq.io.translationResponse.valid && lsq.io.translationResponse.ready)
  io.translationRequestValid := lsq.io.translationRequest.valid
  val translationRequestFire =
    lsq.io.translationRequest.valid && lsq.io.translationRequest.ready
  when(lsq.io.translationResponse.valid && lsq.io.translationResponse.ready) {
    translationValid := False
  }
  when(translationRequestFire) {
    translationValid := True
    translationAddress := lsq.io.translationRequest.virtualAddress
  }
  lsq.io.translationResponse.valid := translationValid && io.translationResponseEnable
  lsq.io.translationResponse.virtualAddress := translationAddress
  lsq.io.translationResponse.physicalAddress := Mux(
    io.translationPhysicalAddressEnable,
    io.translationPhysicalAddress,
    translationAddress
  )
  lsq.io.translationResponse.uncached := io.translationUncached
  lsq.io.translationResponse.cancelled := io.translationCancelled
  lsq.io.translationResponse.exception.valid := io.translationFault
  lsq.io.translationResponse.exception.ecode := io.translationEcode
  lsq.io.translationResponse.exception.esubcode := 0
  lsq.io.translationResponse.exception.badVAddrValid := io.translationFault
  lsq.io.translationResponse.exception.badVAddr := translationAddress
  lsq.io.translationResponse.exception.tlbRefill := False
  lsq.io.translationBypass.eligible := io.translationBypassEligible
  lsq.io.translationBypass.physicalAddress := io.translationBypassPhysicalAddress
  lsq.io.translationBypass.uncached := io.translationBypassUncached
  lsq.io.reservationValid := io.reservationValid
  lsq.io.reservationLineAddress := io.reservationLineAddress
  lsq.io.committedMemoryEpoch := io.committedMemoryEpoch
  lsq.io.currentRecoveryEpoch := io.currentRecoveryEpoch
  lsq.io.robHeadPointer := io.robHeadPointer
  lsq.io.orderingRobPointer := io.orderingRobPointer

  io.aguReady := lsq.io.aguReady
  io.translationRequestAddress := lsq.io.translationRequest.virtualAddress
  io.dataRequestValid := lsq.io.dataRequestValid
  io.dataRequest := lsq.io.dataRequest
  // Present one semantic completion stream to behavior tests while exposing
  // the narrow Store path separately for structural assertions. This probe
  // adapter keeps tests independent of the production writeback encoding.
  val semanticStoreCompletion = Completion(config)
  semanticStoreCompletion.robPointer := lsq.io.storeCompletionBypass.robPointer
  semanticStoreCompletion.recoveryEpoch := lsq.io.storeCompletionBypass.recoveryEpoch
  semanticStoreCompletion.pdst := 0
  semanticStoreCompletion.writesPdst := False
  semanticStoreCompletion.data := 0
  semanticStoreCompletion.sideEffectData := 0
  semanticStoreCompletion.exception.valid := False
  semanticStoreCompletion.exception.ecode := 0
  semanticStoreCompletion.exception.esubcode := 0
  semanticStoreCompletion.exception.badVAddrValid := False
  semanticStoreCompletion.exception.badVAddr := 0
  semanticStoreCompletion.exception.tlbRefill := False
  semanticStoreCompletion.branchResolved := False
  semanticStoreCompletion.branchTaken := False
  semanticStoreCompletion.branchTarget := 0
  semanticStoreCompletion.branchMispredict := False
  io.completionValid := lsq.io.completionValid || lsq.io.storeCompletionBypassValid
  io.completion := lsq.io.completion
  when(lsq.io.storeCompletionBypassValid) {
    io.completion := semanticStoreCompletion
  }
  io.storeCompletionBypassValid := lsq.io.storeCompletionBypassValid
  io.storeCompletionBypass := lsq.io.storeCompletionBypass
  io.loadWakeupValid := lsq.io.loadWakeupValid
  io.loadWakeupPdst := lsq.io.loadWakeupPdst
  io.loadWakeupRecoveryEpoch := lsq.io.loadWakeupRecoveryEpoch
  io.loadWakeupEpochCurrent := lsq.io.loadWakeupEpochCurrent
  io.releaseLoadValid := lsq.io.releaseLoadValid
  io.releaseStoreValid := lsq.io.releaseStoreValid
  io.commitMemory := lsq.io.commitObservation
  io.storeDrainBusy := lsq.io.storeDrainBusy
  io.olderStorePending := lsq.io.olderStorePending
}

class LoadStoreQueueSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def clearInputs(dut: LoadStoreQueueProbe): Unit = {
    dut.io.allocateValid #= 0
    dut.io.storeDataEnable #= true
    dut.io.aguValid #= false
    dut.io.commitValid #= 0
    dut.io.dataRequestReady #= false
    dut.io.dataResponseValid #= false
    dut.io.translationFault #= false
    dut.io.translationEcode #= 0
    dut.io.translationResponseEnable #= true
    dut.io.translationUncached #= false
    dut.io.translationCancelled #= false
    dut.io.translationPhysicalAddressEnable #= false
    dut.io.translationPhysicalAddress #= 0
    dut.io.translationBypassEligible #= false
    dut.io.translationBypassPhysicalAddress #= 0
    dut.io.translationBypassUncached #= false
    dut.io.reservationValid #= false
    dut.io.reservationLineAddress #= 0
    dut.io.committedMemoryEpoch #= 0
    dut.io.currentRecoveryEpoch #= 0
    dut.io.robHeadPointer #= 0
    dut.io.orderingRobPointer #= 0
    dut.io.flush #= false
    for (lane <- 0 until config.renameWidth) {
      dut.io.allocate(lane).robPointer #= 0
      dut.io.allocate(lane).recoveryEpoch #= 0
      dut.io.allocate(lane).memoryEpoch #= 0
      dut.io.allocate(lane).isLoad #= false
      dut.io.allocate(lane).isStore #= false
      dut.io.allocate(lane).loadQueueIndex #= 0
      dut.io.allocate(lane).storeQueueIndex #= 0
    }
    dut.io.agu.isWrite #= false
    dut.io.agu.virtualAddress #= 0
    dut.io.agu.size #= 2
    dut.io.agu.byteMask #= 0xf
    dut.io.agu.writeData #= 0
    dut.io.agu.uop.robPointer #= 0
    dut.io.agu.uop.recoveryEpoch #= 0
    dut.io.agu.uop.pdst #= 0
    dut.io.agu.uop.loadQueueIndex #= 0
    dut.io.agu.uop.storeQueueIndex #= 0
    dut.io.agu.uop.decoded.isSc #= false
    dut.io.agu.uop.decoded.isLl #= false
    dut.io.agu.uop.decoded.isLoad #= false
    dut.io.agu.uop.decoded.isStore #= false
    dut.io.agu.uop.decoded.writesGpr #= false
    dut.io.agu.uop.decoded.memorySignExtend #= false
    dut.io.agu.uop.decoded.exception.valid #= false
    for (lane <- 0 until config.commitWidth) {
      dut.io.commit(lane).robPointer #= 0
      dut.io.commit(lane).isLoad #= false
      dut.io.commit(lane).isStore #= false
      dut.io.commit(lane).loadQueueIndex #= 0
      dut.io.commit(lane).storeQueueIndex #= 0
      dut.io.commit(lane).retired #= false
      dut.io.commit(lane).exception.valid #= false
    }
    dut.io.dataResponse.robPointer #= 0
    dut.io.dataResponse.recoveryEpoch #= 0
    dut.io.dataResponse.pdst #= 0
    dut.io.dataResponse.loadQueueIndex #= 0
    dut.io.dataResponse.data #= 0
    dut.io.dataResponse.error #= false
  }

  test("retirement observation reports Chiplab load and store events") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-commit-memory-observation", 0x4c76) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 5
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, pointer = 5, address = 0x1fe001e5L, loadIndex = 0)
        dut.io.agu.size #= 0
        dut.io.agu.byteMask #= 2
        dut.io.agu.uop.decoded.memorySignExtend #= true
        sample(dut)
        dut.io.aguValid #= false
        dut.clockDomain.waitSampling(3)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 5
        dut.io.commit(0).retired #= true
        dut.io.commit(0).isLoad #= true
        dut.io.commit(0).loadQueueIndex #= 0
        sleep(1)
        assert(dut.io.commitMemory(0).loadInstructionMask.toBigInt == 1)
        assert(dut.io.commitMemory(0).physicalAddress.toBigInt == 0x1fe001e5L)
        assert(dut.io.commitMemory(0).virtualAddress.toBigInt == 0x1fe001e5L)

        dut.io.commit(0).exception.valid #= true
        sleep(1)
        assert(dut.io.commitMemory(0).loadInstructionMask.toBigInt == 0)
        dut.io.commit(0).exception.valid #= false
        dut.io.flush #= true
        sleep(1)
        assert(dut.io.commitMemory(0).loadInstructionMask.toBigInt == 1)
        assert(dut.io.commitMemory(0).physicalAddress.toBigInt == 0x1fe001e5L)
        dut.io.flush #= false
        dut.io.commit(0).robPointer #= 6
        sleep(1)
        assert(dut.io.commitMemory(0).loadInstructionMask.toBigInt == 0)

        dut.io.commit(0).robPointer #= 5
        sample(dut)
        dut.io.commitValid #= 0
        dut.io.commit(0).retired #= false
        dut.io.commit(0).isLoad #= false

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 7
        dut.io.allocate(0).isLoad #= false
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setStoreAgu(
          dut,
          pointer = 7,
          address = 0x101,
          data = BigInt("aabbccdd", 16),
          storeIndex = 0
        )
        dut.io.agu.size #= 0
        dut.io.agu.byteMask #= 2
        sample(dut)
        dut.io.aguValid #= false
        dut.clockDomain.waitSampling(3)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 7
        dut.io.commit(0).retired #= true
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sleep(1)
        assert(dut.io.commitMemory(0).storeInstructionMask.toBigInt == 1)
        assert(dut.io.commitMemory(0).physicalAddress.toBigInt == 0x101)
        assert(dut.io.commitMemory(0).virtualAddress.toBigInt == 0x101)
        assert(dut.io.commitMemory(0).storeData.toBigInt == 0x0000dd00)
        assert(dut.io.commitMemory(0).storeByteMask.toBigInt == 2)
      }
  }

  private def sample(dut: LoadStoreQueueProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def setStoreAgu(
      dut: LoadStoreQueueProbe,
      pointer: BigInt,
      address: BigInt,
      data: BigInt,
      storeIndex: Int = 0,
      isSc: Boolean = false,
      pdst: Int = 0
  ): Unit = {
    dut.io.aguValid #= true
    dut.io.agu.isWrite #= true
    dut.io.agu.virtualAddress #= address
    dut.io.agu.size #= 2
    dut.io.agu.byteMask #= 0xf
    dut.io.agu.writeData #= data
    dut.io.agu.uop.robPointer #= pointer
    dut.io.agu.uop.pdst #= pdst
    dut.io.agu.uop.storeQueueIndex #= storeIndex
    dut.io.agu.uop.decoded.isStore #= true
    dut.io.agu.uop.decoded.isSc #= isSc
    dut.io.agu.uop.decoded.writesGpr #= isSc
  }

  private def setLoadAgu(
      dut: LoadStoreQueueProbe,
      pointer: BigInt,
      address: BigInt,
      isLl: Boolean = false,
      loadIndex: Int = 0,
      pdst: Int = 7
  ): Unit = {
    dut.io.aguValid #= true
    dut.io.agu.isWrite #= false
    dut.io.agu.virtualAddress #= address
    dut.io.agu.size #= 2
    dut.io.agu.byteMask #= 0xf
    dut.io.agu.uop.robPointer #= pointer
    dut.io.agu.uop.pdst #= pdst
    dut.io.agu.uop.loadQueueIndex #= loadIndex
    dut.io.agu.uop.decoded.isLoad #= true
    dut.io.agu.uop.decoded.isLl #= isLl
    dut.io.agu.uop.decoded.writesGpr #= true
  }

  test("direct and DMW preview translates Load, Store, and SC before owner allocation") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq-translation-bypass")
      .compile(new LoadStoreQueueProbe(config))

    compiled.doSim("ooo-lsq-translation-bypass-load", 0x4c7b) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      SimTimeout(2000)
      clearInputs(dut)
      dut.io.translationResponseEnable #= false
      dut.io.translationBypassEligible #= true
      dut.io.translationBypassPhysicalAddress #= 0x20000400
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample(dut)

      dut.io.allocateValid #= 1
      dut.io.allocate(0).robPointer #= 4
      dut.io.allocate(0).isLoad #= true
      dut.io.allocate(0).loadQueueIndex #= 0
      dut.io.robHeadPointer #= 4
      sample(dut)
      dut.io.allocateValid #= 0
      setLoadAgu(dut, pointer = 4, address = 0x80000400L, loadIndex = 0)
      sleep(1)
      assert(dut.io.aguReady.toBoolean)
      sample(dut)
      dut.io.aguValid #= false

      var loadWait = 0
      while (!dut.io.dataRequestValid.toBoolean && loadWait < 8) {
        assert(!dut.io.translationRequestValid.toBoolean)
        sample(dut)
        loadWait += 1
      }
      assert(dut.io.dataRequestValid.toBoolean)
      assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x20000400)
      assert(!dut.io.dataRequest.uncached.toBoolean)
      assert(!dut.io.translationRequestValid.toBoolean)
    }

    compiled.doSim("ooo-lsq-translation-bypass-store", 0x4c7d) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      SimTimeout(2000)
      clearInputs(dut)
      dut.io.translationResponseEnable #= false
      dut.io.translationBypassEligible #= true
      dut.io.translationBypassPhysicalAddress #= 0x20000800
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample(dut)

      dut.io.allocateValid #= 1
      dut.io.allocate(0).robPointer #= 6
      dut.io.allocate(0).isStore #= true
      dut.io.allocate(0).storeQueueIndex #= 0
      sample(dut)
      dut.io.allocateValid #= 0
      setStoreAgu(dut, pointer = 6, address = 0x80000800L, data = 0x11223344)
      sleep(1)
      assert(dut.io.aguReady.toBoolean)
      sample(dut)
      dut.io.aguValid #= false

      var storeWait = 0
      while (!dut.io.completionValid.toBoolean && storeWait < 8) {
        assert(!dut.io.translationRequestValid.toBoolean)
        sample(dut)
        storeWait += 1
      }
      assert(dut.io.completionValid.toBoolean)
      assert(dut.io.completion.robPointer.toBigInt == 6)
      assert(!dut.io.completion.exception.valid.toBoolean)
      assert(!dut.io.translationRequestValid.toBoolean)
    }

    compiled.doSim("ooo-lsq-translation-bypass-sc", 0x4c7e) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      SimTimeout(2000)
      clearInputs(dut)
      dut.io.translationResponseEnable #= false
      dut.io.translationBypassEligible #= true
      dut.io.translationBypassPhysicalAddress #= 0x20000c00
      dut.io.reservationValid #= true
      dut.io.reservationLineAddress #= (BigInt(0x20000c00L) >> config.dataCache.offsetWidth)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample(dut)

      dut.io.allocateValid #= 1
      dut.io.allocate(0).robPointer #= 8
      dut.io.allocate(0).isStore #= true
      dut.io.allocate(0).storeQueueIndex #= 0
      sample(dut)
      dut.io.allocateValid #= 0
      setStoreAgu(
        dut,
        pointer = 8,
        address = 0x80000c00L,
        data = 0x55667788,
        isSc = true,
        pdst = 9
      )
      sample(dut)
      dut.io.aguValid #= false

      var scWait = 0
      while (!dut.io.completionValid.toBoolean && scWait < 8) {
        assert(!dut.io.translationRequestValid.toBoolean)
        sample(dut)
        scWait += 1
      }
      assert(dut.io.completionValid.toBoolean)
      assert(dut.io.completion.robPointer.toBigInt == 8)
      assert(dut.io.completion.data.toBigInt == 1)
      assert(!dut.io.translationRequestValid.toBoolean)
    }

    compiled.doSim("ooo-lsq-translation-bypass-uncached-sc", 0x4c7f) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      SimTimeout(2000)
      clearInputs(dut)
      dut.io.translationResponseEnable #= false
      dut.io.translationBypassEligible #= true
      dut.io.translationBypassPhysicalAddress #= 0x20001000
      dut.io.translationBypassUncached #= true
      dut.io.reservationValid #= true
      dut.io.reservationLineAddress #= (BigInt(0x20001000L) >> config.dataCache.offsetWidth)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample(dut)

      dut.io.allocateValid #= 1
      dut.io.allocate(0).robPointer #= 10
      dut.io.allocate(0).isStore #= true
      dut.io.allocate(0).storeQueueIndex #= 0
      sample(dut)
      dut.io.allocateValid #= 0
      setStoreAgu(
        dut,
        pointer = 10,
        address = 0x80001000L,
        data = 0x99aabbccL,
        isSc = true,
        pdst = 11
      )
      sample(dut)
      dut.io.aguValid #= false

      var waitCycles = 0
      while (!dut.io.completionValid.toBoolean && waitCycles < 8) {
        assert(!dut.io.translationRequestValid.toBoolean)
        assert(!dut.io.dataRequestValid.toBoolean)
        sample(dut)
        waitCycles += 1
      }
      assert(dut.io.completionValid.toBoolean)
      assert(dut.io.completion.robPointer.toBigInt == 10)
      assert(dut.io.completion.data.toBigInt == 0)
      assert(!dut.io.translationRequestValid.toBoolean)
      assert(!dut.io.dataRequestValid.toBoolean)
    }

    val disabled = config.copy(enableDirectDmwPretranslation = false)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq-translation-bypass-disabled")
      .compile(new LoadStoreQueueProbe(disabled))
      .doSim("ooo-lsq-translation-bypass-disabled", 0x4c7c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        SimTimeout(1000)
        clearInputs(dut)
        dut.io.translationResponseEnable #= false
        dut.io.translationBypassEligible #= true
        dut.io.translationBypassPhysicalAddress #= 0x20000400
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 4
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, pointer = 4, address = 0x80000400L, loadIndex = 0)
        sample(dut)
        dut.io.aguValid #= false

        var waitCycles = 0
        var sawTranslation = false
        while (!sawTranslation && waitCycles < 8) {
          sawTranslation ||= dut.io.translationRequestValid.toBoolean
          sample(dut)
          waitCycles += 1
        }
        assert(sawTranslation)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("memory epoch blocks cached, uncached, and committed-store requests") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))

    for ((uncached, name, seed) <- Seq(
        (false, "cached-load", 0x4c70),
        (true, "uncached-load", 0x4c71)
      )) {
      compiled.doSim(s"ooo-lsq-memory-epoch-$name", seed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationUncached #= uncached
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 4
        dut.io.allocate(0).memoryEpoch #= 1
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        dut.io.robHeadPointer #= 4
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, pointer = 4, address = 0x400, loadIndex = 0)
        sample(dut)
        dut.io.aguValid #= false

        for (_ <- 0 until 6) {
          sample(dut)
          assert(!dut.io.dataRequestValid.toBoolean)
          assert(!dut.io.translationRequestValid.toBoolean)
        }

        dut.io.committedMemoryEpoch #= 1
        var waitCycles = 0
        while (!dut.io.dataRequestValid.toBoolean && waitCycles < 16) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 4)
        assert(dut.io.dataRequest.uncached.toBoolean == uncached)
      }
    }

    compiled.doSim("ooo-lsq-memory-epoch-committed-store", 0x4c72) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      clearInputs(dut)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample(dut)

      dut.io.allocateValid #= 1
      dut.io.allocate(0).robPointer #= 5
      dut.io.allocate(0).memoryEpoch #= 1
      dut.io.allocate(0).isStore #= true
      dut.io.allocate(0).storeQueueIndex #= 0
      sample(dut)
      dut.io.allocateValid #= 0
      setStoreAgu(dut, pointer = 5, address = 0x500, data = 0x12345678)
      sample(dut)
      dut.io.aguValid #= false

      var completionWait = 0
      while (!dut.io.completionValid.toBoolean && completionWait < 16) {
        sample(dut)
        completionWait += 1
      }
      assert(dut.io.completionValid.toBoolean)
      dut.io.commitValid #= 1
      dut.io.commit(0).robPointer #= 5
      dut.io.commit(0).isStore #= true
      dut.io.commit(0).storeQueueIndex #= 0
      sample(dut)
      dut.io.commitValid #= 0

      for (_ <- 0 until 4) {
        sample(dut)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
      dut.io.committedMemoryEpoch #= 1
      var requestWait = 0
      while (!dut.io.dataRequestValid.toBoolean && requestWait < 12) {
        sample(dut)
        requestWait += 1
      }
      assert(dut.io.dataRequestValid.toBoolean)
      assert(dut.io.dataRequest.isWrite.toBoolean)
      assert(dut.io.dataRequest.robPointer.toBigInt == 5)
    }
  }

  test("recycled load slots initialize and advance the circular scheduling base") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-recycled-slot-age", 0x4c58) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        // This is the state reached when commit-time slot reuse laps the old
        // execution pointer: the older load occupies a numerically later slot.
        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 34
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 18
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 4
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, pointer = 34, address = 0x340, loadIndex = 0, pdst = 9)
        sample(dut)
        dut.io.aguValid #= false
        setLoadAgu(dut, pointer = 18, address = 0x180, loadIndex = 4, pdst = 8)
        sample(dut)
        dut.io.aguValid #= false

        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 12) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 18)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == 0x180)

        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 18
        dut.io.dataResponse.loadQueueIndex #= 4
        dut.io.dataResponse.data #= BigInt("18181818", 16)
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 18)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 18
        dut.io.commit(0).isLoad #= true
        dut.io.commit(0).loadQueueIndex #= 4
        sample(dut)
        dut.io.commitValid #= 0

        requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 12) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 34)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == 0x340)
      }
  }

  test("load scheduling preserves circular age across both queue banks") {
    for (sidecar <- Seq(false, true)) {
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-lsq-banked-circular-select-$sidecar"
        )
        .compile(
          new LoadStoreQueueProbe(config.copy(enableLoadPendingStateSidecar = sidecar))
        )
        .doSim(s"ooo-lsq-banked-circular-select-$sidecar", 0x4c82 + sidecar.hashCode()) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut)
          dut.io.translationBypassEligible #= true
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          // Starting at slot 6, the exact circular order is the bank-0 suffix,
          // bank 1, then the bank-0 prefix: 6 -> 9 -> 2.
          val loads = Seq(
            (6, 10, BigInt(0x600), BigInt(0x80000600L), 8),
            (9, 11, BigInt(0x900), BigInt(0x80000900L), 9),
            (2, 12, BigInt(0x200), BigInt(0x80000200L), 10)
          )
          dut.io.allocateValid #= 7
          for (((loadIndex, pointer, _, _, _), lane) <- loads.zipWithIndex) {
            dut.io.allocate(lane).robPointer #= pointer
            dut.io.allocate(lane).isLoad #= true
            dut.io.allocate(lane).loadQueueIndex #= loadIndex
          }
          sample(dut)
          dut.io.allocateValid #= 0

          for ((loadIndex, pointer, virtualAddress, physicalAddress, pdst) <- loads) {
            dut.io.translationBypassPhysicalAddress #= physicalAddress
            setLoadAgu(
              dut,
              pointer = pointer,
              address = virtualAddress,
              loadIndex = loadIndex,
              pdst = pdst
            )
            sample(dut)
          }
          dut.io.aguValid #= false
          dut.io.dataRequestReady #= true

          val requests = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
          var requestWait = 0
          while (requests.size < loads.size && requestWait < 32) {
            if (dut.io.dataRequestValid.toBoolean) {
              requests += ((
                dut.io.dataRequest.robPointer.toBigInt,
                dut.io.dataRequest.virtualAddress.toBigInt,
                dut.io.dataRequest.physicalAddress.toBigInt
              ))
            }
            sample(dut)
            requestWait += 1
          }
          assert(
            requests.toSeq == Seq(
              (BigInt(10), BigInt(0x600), BigInt(0x80000600L)),
              (BigInt(11), BigInt(0x900), BigInt(0x80000900L)),
              (BigInt(12), BigInt(0x200), BigInt(0x80000200L))
            )
          )
        }
    }
  }

  test("independent loads complete only with matching LQ owner and ROB tag") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-multiple-outstanding-loads", 0x4c5b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 1
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, pointer = 0, address = 0x100, loadIndex = 0, pdst = 8)
        sample(dut)
        setLoadAgu(dut, pointer = 1, address = 0x180, loadIndex = 1, pdst = 9)
        sample(dut)
        dut.io.aguValid #= false
        dut.io.dataRequestReady #= true

        val requests = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var requestWait = 0
        while (requests.size < 2 && requestWait < 24) {
          if (dut.io.dataRequestValid.toBoolean) {
            requests += dut.io.dataRequest.robPointer.toBigInt
          }
          sample(dut)
          requestWait += 1
        }
        assert(requests.toSeq == Seq(BigInt(0), BigInt(1)))
        dut.io.dataRequestReady #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 1
        // A matching ROB tag attached to the wrong LQ owner is stale and must
        // not complete either entry.
        dut.io.dataResponse.loadQueueIndex #= 0
        dut.io.dataResponse.data #= BigInt("11111111", 16)
        sample(dut)
        assert(!dut.io.completionValid.toBoolean)

        dut.io.dataResponseValid #= false
        sample(dut)
        dut.io.dataResponseValid #= true
        dut.io.dataResponse.loadQueueIndex #= 1
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.pdst.toBigInt == 9)
        assert(dut.io.loadWakeupValid.toBoolean)
        assert(dut.io.loadWakeupPdst.toBigInt == 9)
        assert(dut.io.loadWakeupRecoveryEpoch.toBigInt == 0)

        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.loadQueueIndex #= 0
        dut.io.dataResponse.data #= BigInt("01010101", 16)
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        assert(dut.io.completion.pdst.toBigInt == 8)
        assert(dut.io.loadWakeupValid.toBoolean)
        assert(dut.io.loadWakeupPdst.toBigInt == 8)
        assert(dut.io.loadWakeupRecoveryEpoch.toBigInt == 0)
      }
  }

  test("a data read error reports precise ADEM metadata") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-load-read-error", 0x4c5c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val address = BigInt("1fe00110", 16)
        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 3
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, pointer = 3, address = address, loadIndex = 0, pdst = 9)
        sample(dut)
        dut.io.aguValid #= false

        var requestCycles = 0
        while (!dut.io.dataRequestValid.toBoolean && requestCycles < 16) {
          sample(dut)
          requestCycles += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 3
        dut.io.dataResponse.pdst #= 9
        dut.io.dataResponse.error #= true
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 3)
        assert(dut.io.completion.exception.valid.toBoolean)
        assert(dut.io.completion.exception.ecode.toBigInt == 8)
        assert(dut.io.completion.exception.esubcode.toBigInt == 1)
        assert(dut.io.completion.exception.badVAddrValid.toBoolean)
        assert(dut.io.completion.exception.badVAddr.toBigInt == address)
        assert(!dut.io.completion.exception.tlbRefill.toBoolean)
        assert(!dut.io.loadWakeupValid.toBoolean)
      }
  }

  test("a committed store survives a recovery flush and drains before restart") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-committed-store-flush-drain", 0x4c59) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 9
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 9, 0x1d0000, 0xf)
        sample(dut)
        dut.io.aguValid #= false
        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 9
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0

        sleep(1)
        assert(dut.io.releaseStoreValid.toBigInt == 1)
        sample(dut)
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.releaseStoreValid.toBigInt == 0)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        sleep(1)
        assert(dut.io.storeDrainBusy.toBoolean)
        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 4) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == 0x1d0000)
        assert(dut.io.dataRequest.writeData.toBigInt == 0xf)

        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        var drainWait = 0
        while (dut.io.storeDrainBusy.toBoolean && drainWait < 4) {
          sample(dut)
          drainWait += 1
        }
        assert(!dut.io.storeDrainBusy.toBoolean)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 10
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setStoreAgu(dut, 10, 0x1d0004, 0x10)
        sample(dut)
        dut.io.aguValid #= false
        var reusedCompletionWait = 0
        while (!dut.io.completionValid.toBoolean && reusedCompletionWait < 8) {
          sample(dut)
          reusedCompletionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
      }
  }

  test("a translation response invalidated by flush is drained before slot reuse") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-translation-flush-cancel", 0x4c5a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 9
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 9, 0x1d0000, 0xf)
        sample(dut)
        dut.io.aguValid #= false
        dut.io.translationResponseEnable #= false
        sample(dut)

        // Redirect after the request handshake but before its response. The
        // response belongs to the discarded epoch and must only be consumed.
        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        dut.io.translationResponseEnable #= true
        sample(dut)
        assert(!dut.io.completionValid.toBoolean)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 10
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setStoreAgu(dut, 10, 0x1d0004, 0x10)
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 10)
      }
  }

  test("a cache response from an old recovery epoch cannot complete a recycled ROB pointer") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-cache-response-epoch", 0x4c5c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 9
        dut.io.allocate(0).recoveryEpoch #= 3
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, pointer = 9, address = 0x100, loadIndex = 0, pdst = 7)
        dut.io.agu.uop.recoveryEpoch #= 3
        sample(dut)
        dut.io.aguValid #= false
        dut.io.dataRequestReady #= true
        var oldRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && oldRequestWait < 12) {
          sample(dut)
          oldRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.recoveryEpoch.toBigInt == 3)
        sample(dut)
        dut.io.dataRequestReady #= false

        // The cache cannot cancel this accepted miss when recovery discards the load.
        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false

        // Recovery can recycle both the LQ slot and full ROB pointer before DDR returns.
        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 9
        dut.io.allocate(0).recoveryEpoch #= 4
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, pointer = 9, address = 0x200, loadIndex = 0, pdst = 8)
        dut.io.agu.uop.recoveryEpoch #= 4
        sample(dut)
        dut.io.aguValid #= false
        dut.io.dataRequestReady #= true
        var newRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && newRequestWait < 12) {
          sample(dut)
          newRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.recoveryEpoch.toBigInt == 4)
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 9
        dut.io.dataResponse.recoveryEpoch #= 3
        dut.io.dataResponse.data #= BigInt("11111111", 16)
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(!dut.io.completionValid.toBoolean)
        assert(!dut.io.loadWakeupValid.toBoolean)

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.recoveryEpoch #= 4
        dut.io.dataResponse.data #= BigInt("22222222", 16)
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 9)
        assert(dut.io.completion.recoveryEpoch.toBigInt == 4)
        assert(dut.io.completion.pdst.toBigInt == 8)
        assert(dut.io.completion.data.toBigInt == BigInt("22222222", 16))
        assert(dut.io.loadWakeupValid.toBoolean)
        assert(dut.io.loadWakeupPdst.toBigInt == 8)
        assert(dut.io.loadWakeupRecoveryEpoch.toBigInt == 4)
      }
  }

  test("store waits for commit and holds a stable request under backpressure") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-commit-boundary", 0x4c51) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 0, 0x100, BigInt("deadbeef", 16))
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        dut.io.aguValid #= false
        assert(!dut.io.dataRequestValid.toBoolean)

        var storeCompletionWait = 0
        while (!dut.io.completionValid.toBoolean && storeCompletionWait < 8) {
          sample(dut)
          storeCompletionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        assert(!dut.io.completion.exception.valid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0
        sleep(1)
        assert(dut.io.releaseStoreValid.toBigInt == 1)
        assert(!dut.io.dataRequestValid.toBoolean)
        sample(dut)
        assert(dut.io.releaseStoreValid.toBigInt == 0)
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == 0x100)
        assert(dut.io.dataRequest.writeData.toBigInt == BigInt("deadbeef", 16))
        dut.io.orderingRobPointer #= 1
        sleep(1)
        assert(dut.io.olderStorePending.toBoolean)

        val heldAddress = dut.io.dataRequest.virtualAddress.toBigInt
        val heldData = dut.io.dataRequest.writeData.toBigInt
        sample(dut)
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == heldAddress)
        assert(dut.io.dataRequest.writeData.toBigInt == heldData)

        dut.io.dataRequestReady #= true
        sleep(1)
        assert(dut.io.releaseStoreValid.toBigInt == 0)
        sample(dut)
        assert(!dut.io.dataRequestValid.toBoolean)
        assert(!dut.io.olderStorePending.toBoolean)
        sample(dut)
        assert(dut.io.releaseStoreValid.toBigInt == 0)
      }
  }

  test("a buffered Load releases the scheduler before cache acceptance") {
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-ooo-lsq-load-buffer-ownership"
      )
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-load-buffer-ownership", 0x4c81) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationBypassEligible #= true
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        for (lane <- 0 until 2) {
          dut.io.allocate(lane).robPointer #= lane
          dut.io.allocate(lane).isLoad #= true
          dut.io.allocate(lane).loadQueueIndex #= lane
        }
        sample(dut)
        dut.io.allocateValid #= 0

        dut.io.translationBypassPhysicalAddress #= 0x80000100L
        setLoadAgu(dut, pointer = 0, address = 0x100, loadIndex = 0, pdst = 8)
        sample(dut)
        dut.io.aguValid #= false
        dut.io.translationBypassPhysicalAddress #= 0x80000200L
        setLoadAgu(dut, pointer = 1, address = 0x200, loadIndex = 1, pdst = 9)
        sample(dut)
        dut.io.aguValid #= false

        var firstRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && firstRequestWait < 8) {
          sample(dut)
          firstRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 0)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x80000100L)

        // Backpressure must retain one copy of the first request while the
        // scheduler advances to the second resident Load.
        for (_ <- 0 until 3) {
          sample(dut)
          assert(dut.io.dataRequestValid.toBoolean)
          assert(dut.io.dataRequest.robPointer.toBigInt == 0)
        }

        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        assert(!dut.io.dataRequestValid.toBoolean)

        // One empty registered-buffer cycle is sufficient.  The old fire-time
        // ownership update needed another cycle to reselect the first Load.
        sample(dut)
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 1)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x80000200L)
      }
  }

  test("a buffered committed store blocks younger loads until cache acceptance") {
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-ooo-lsq-buffered-store-order"
      )
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-buffered-store-order", 0x4c80) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationBypassEligible #= true
        dut.io.translationBypassPhysicalAddress #= 0x80000100L
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 0, 0x100, BigInt("deadbeef", 16))
        sample(dut)
        dut.io.aguValid #= false
        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0
        sleep(1)
        assert(dut.io.releaseStoreValid.toBigInt == 1)
        sample(dut)
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 1
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationBypassPhysicalAddress #= 0x80000200L
        setLoadAgu(dut, pointer = 1, address = 0x200, loadIndex = 0, pdst = 8)
        sample(dut)
        dut.io.aguValid #= false

        for (_ <- 0 until 4) {
          assert(dut.io.dataRequestValid.toBoolean)
          assert(dut.io.dataRequest.isWrite.toBoolean)
          assert(dut.io.dataRequest.robPointer.toBigInt == 0)
          assert(!dut.io.completionValid.toBoolean)
          sample(dut)
        }

        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        var loadRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && loadRequestWait < 8) {
          sample(dut)
          loadRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(!dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 1)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x80000200L)
      }
  }

  test("ordinary cached Store completion bypass is independently configurable") {
    for ((enabled, name, seed) <- Seq(
        (false, "registered", 0x4c78),
        (true, "direct", 0x4c79)
      )) {
      val testConfig = config.copy(enableFastStoreCompletion = enabled)
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-lsq-store-completion-$name")
        .compile(new LoadStoreQueueProbe(testConfig))
        .doSim(s"ooo-lsq-store-completion-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut)
          dut.io.translationResponseEnable #= false
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          dut.io.allocateValid #= 1
          dut.io.allocate(0).robPointer #= 6
          dut.io.allocate(0).isStore #= true
          dut.io.allocate(0).storeQueueIndex #= 0
          sample(dut)
          dut.io.allocateValid #= 0

          setStoreAgu(dut, 6, 0x180, BigInt("12345678", 16))
          sample(dut)
          dut.io.aguValid #= false
          while (!dut.io.translationRequestValid.toBoolean) {
            sample(dut)
          }
          sample(dut)

          dut.io.translationResponseEnable #= true
          sleep(1)
          assert(dut.io.completionValid.toBoolean == enabled)
          assert(dut.io.storeCompletionBypassValid.toBoolean == enabled)
          if (enabled) {
            assert(dut.io.storeCompletionBypass.robPointer.toBigInt == 6)
            assert(dut.io.storeCompletionBypass.recoveryEpoch.toBigInt == 0)
            assert(dut.io.completion.robPointer.toBigInt == 6)
            assert(!dut.io.completion.writesPdst.toBoolean)
            assert(!dut.io.completion.exception.valid.toBoolean)
          }

          sample(dut)
          dut.io.translationResponseEnable #= false
          assert(!dut.io.storeCompletionBypassValid.toBoolean)
          assert(dut.io.completionValid.toBoolean != enabled)
          if (!enabled) {
            assert(dut.io.completion.robPointer.toBigInt == 6)
          }
          sample(dut)
          assert(!dut.io.completionValid.toBoolean)
        }
    }
  }

  test("idle translation bandwidth can look ahead past the untranslated Store head") {
    for ((enabled, name, seed) <- Seq(
        (false, "head-only", 0x4c7a),
        (true, "lookahead", 0x4c7b)
      )) {
      val testConfig = config.copy(
        enableFastStoreCompletion = false,
        enableStoreTranslationLookahead = enabled
      )
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-lsq-store-translation-$name")
        .compile(new LoadStoreQueueProbe(testConfig))
        .doSim(s"ooo-lsq-store-translation-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          dut.io.allocateValid #= 3
          dut.io.allocate(0).robPointer #= 6
          dut.io.allocate(0).isStore #= true
          dut.io.allocate(0).storeQueueIndex #= 0
          dut.io.allocate(1).robPointer #= 7
          dut.io.allocate(1).isStore #= true
          dut.io.allocate(1).storeQueueIndex #= 1
          sample(dut)
          dut.io.allocateValid #= 0

          setStoreAgu(dut, 6, 0x180, BigInt("11111111", 16), storeIndex = 0)
          sample(dut)
          setStoreAgu(dut, 7, 0x280, BigInt("22222222", 16), storeIndex = 1)
          sample(dut)
          dut.io.aguValid #= false

          var sawLookahead = false
          for (_ <- 0 until 12) {
            if (
              dut.io.translationRequestValid.toBoolean &&
              dut.io.translationRequestAddress.toBigInt == 0x280
            ) {
              sawLookahead = true
            }
            sample(dut)
          }
          assert(sawLookahead == enabled)
        }
    }
  }

  test("Store translation may finish before its independently scheduled data") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-address-before-data", 0x4c5a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 4
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        dut.io.storeDataEnable #= false
        setStoreAgu(dut, 4, 0x104, BigInt("a5a55a5a", 16))
        sample(dut)
        dut.io.aguValid #= false

        // Address translation is allowed to finish, but the ROB completion
        // must wait because recovery would otherwise be able to discard the
        // only pending copy of architectural Store data.
        for (_ <- 0 until 5) {
          sample(dut)
          assert(!dut.io.completionValid.toBoolean)
        }

        dut.io.storeDataEnable #= true
        dut.io.aguValid #= true
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 6) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 4)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 4
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0
        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 6) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.writeData.toBigInt == BigInt("a5a55a5a", 16))
      }
  }

  test("a byte Store aligns raw data to its selected write lane") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-byte-store-alignment", 0x4c5e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 4
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 4, 0x102, BigInt("25474bf0", 16))
        dut.io.agu.size #= 0
        dut.io.agu.byteMask #= 4
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 4
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0

        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 8) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.byteMask.toBigInt == 4)
        assert(dut.io.dataRequest.writeData.toBigInt == BigInt("00f00000", 16))
      }
  }

  test("a single older covering store forwards to a younger load") {
    for (banked <- Seq(false, true)) {
      val testConfig = config.copy(enableBankedLoadForwardCompletion = banked)
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-lsq-forward-$banked"
        )
        .compile(new LoadStoreQueueProbe(testConfig))
        .doSim(s"ooo-lsq-store-forwarding-$banked", if (banked) 0x4c53 else 0x4c52) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 0, 0x200, BigInt("12345678", 16))
        sleep(1)
        sample(dut)
        dut.io.aguValid #= false

        setLoadAgu(dut, 1, 0x200)
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        dut.io.aguValid #= false
        var forwardingWait = 0
        while (
          (!dut.io.completionValid.toBoolean || dut.io.completion.robPointer.toBigInt != 1) &&
          forwardingWait < 8
        ) {
          sample(dut)
          forwardingWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.pdst.toBigInt == 7)
        assert(dut.io.completion.data.toBigInt == BigInt("12345678", 16))
        assert(dut.io.loadWakeupValid.toBoolean)
        assert(dut.io.loadWakeupPdst.toBigInt == 7)
        assert(dut.io.loadWakeupRecoveryEpoch.toBigInt == 0)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
    }
  }

  test("an older Store completion wins a collision with younger Load forwarding") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config.copy(enableFastStoreCompletion = true)))
      .doSim("ooo-lsq-store-forwarding-completion-priority", 0x4c7c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        dut.io.storeDataEnable #= false
        setStoreAgu(dut, 0, 0x200, BigInt("12345678", 16))
        sample(dut)
        dut.io.aguValid #= false
        sample(dut)

        setLoadAgu(dut, 1, 0x200)
        sample(dut)
        dut.io.aguValid #= false
        sample(dut)

        dut.io.storeDataEnable #= true
        setStoreAgu(dut, 0, 0x200, BigInt("12345678", 16))
        sample(dut)
        dut.io.aguValid #= false

        assert(dut.io.storeCompletionBypassValid.toBoolean)
        assert(dut.io.storeCompletionBypass.robPointer.toBigInt == 0)
        sample(dut)
        assert(!dut.io.storeCompletionBypassValid.toBoolean)
        // Forwarded Loads use the registered completion path. The cycle after
        // the direct Store completion therefore remains empty before the Load appears.
        assert(!dut.io.completionValid.toBoolean)
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.pdst.toBigInt == 7)
        assert(dut.io.completion.data.toBigInt == BigInt("12345678", 16))
        sample(dut)
        assert(!dut.io.completionValid.toBoolean)
      }
  }

  test("physical synonyms forward only after both Store and Load translations complete") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-physical-synonym-forwarding", 0x4c79) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationResponseEnable #= false
        dut.io.translationPhysicalAddressEnable #= true
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 0, 0x1000, BigInt("12345678", 16))
        sample(dut)
        dut.io.aguValid #= false
        var storeTranslationWait = 0
        while (!dut.io.translationRequestValid.toBoolean && storeTranslationWait < 8) {
          sample(dut)
          storeTranslationWait += 1
        }
        assert(dut.io.translationRequestValid.toBoolean)
        assert(dut.io.translationRequestAddress.toBigInt == 0x1000)
        sample(dut)

        setLoadAgu(dut, 1, 0x2000)
        sample(dut)
        dut.io.aguValid #= false
        for (_ <- 0 until 3) {
          assert(!dut.io.dataRequestValid.toBoolean)
          sample(dut)
        }

        dut.io.translationPhysicalAddress #= 0x8000
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false
        var loadTranslationWait = 0
        while (!dut.io.translationRequestValid.toBoolean && loadTranslationWait < 8) {
          sample(dut)
          loadTranslationWait += 1
        }
        assert(dut.io.translationRequestValid.toBoolean)
        assert(dut.io.translationRequestAddress.toBigInt == 0x2000)
        sample(dut)
        assert(!dut.io.dataRequestValid.toBoolean)

        dut.io.translationPhysicalAddress #= 0x8000
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false

        var completionWait = 0
        while (
          (!dut.io.completionValid.toBoolean || dut.io.completion.robPointer.toBigInt != 1) &&
          completionWait < 8
        ) {
          assert(!dut.io.dataRequestValid.toBoolean)
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.data.toBigInt == BigInt("12345678", 16))
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("a physical-synonym partial overlap waits until the older Store drains") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-physical-synonym-partial-overlap", 0x4c7a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationPhysicalAddressEnable #= true
        dut.io.translationPhysicalAddress #= 0x9000
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 0, 0x3000, BigInt("000000ab", 16))
        dut.io.agu.size #= 0
        dut.io.agu.byteMask #= 1
        sample(dut)
        dut.io.aguValid #= false
        var storeCompletionWait = 0
        while (
          (!dut.io.completionValid.toBoolean || dut.io.completion.robPointer.toBigInt != 0) &&
          storeCompletionWait < 12
        ) {
          sample(dut)
          storeCompletionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        sample(dut)

        setLoadAgu(dut, 1, 0x4000)
        sample(dut)
        dut.io.aguValid #= false
        for (_ <- 0 until 8) {
          assert(!dut.io.dataRequestValid.toBoolean)
          assert(!dut.io.completionValid.toBoolean)
          sample(dut)
        }

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0
        dut.io.dataRequestReady #= true

        var storeRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && storeRequestWait < 8) {
          sample(dut)
          storeRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x9000)
        sample(dut)

        var loadRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && loadRequestWait < 10) {
          sample(dut)
          loadRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(!dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x9000)
      }
  }

  test("an older SUC Store orders younger cached and SUC Loads through its response") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))

    for ((youngerUncached, name, seed) <- Seq(
        (false, "cached", 0x4c7b),
        (true, "suc", 0x4c7c)
      )) {
      compiled.doSim(s"ooo-lsq-suc-store-orders-$name-load", seed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.translationResponseEnable #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        // Pointer 0 models a delayed older instruction. The Store and Load are
        // already in the window, but neither may escape the SUC order domain.
        dut.io.robHeadPointer #= 0
        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 1
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 2
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setStoreAgu(dut, 1, 0x5000, BigInt("a5a55a5a", 16))
        sample(dut)
        dut.io.aguValid #= false
        while (!dut.io.translationRequestValid.toBoolean) sample(dut)
        assert(dut.io.translationRequestAddress.toBigInt == 0x5000)
        sample(dut)

        setLoadAgu(dut, 2, 0x6000)
        sample(dut)
        dut.io.aguValid #= false
        for (_ <- 0 until 3) {
          assert(!dut.io.dataRequestValid.toBoolean)
          sample(dut)
        }

        dut.io.translationUncached #= true
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false
        while (!dut.io.translationRequestValid.toBoolean) sample(dut)
        assert(dut.io.translationRequestAddress.toBigInt == 0x6000)
        sample(dut)

        dut.io.translationUncached #= youngerUncached
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false
        for (_ <- 0 until 4) {
          assert(!dut.io.dataRequestValid.toBoolean)
          sample(dut)
        }

        dut.io.robHeadPointer #= 1
        dut.io.dataRequestReady #= true
        var storeRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && storeRequestWait < 10) {
          sample(dut)
          storeRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.uncached.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 1)
        sample(dut)
        dut.io.dataRequestReady #= false

        for (_ <- 0 until 4) {
          assert(!dut.io.dataRequestValid.toBoolean)
          sample(dut)
        }

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 1
        dut.io.dataResponse.recoveryEpoch #= 0
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)

        if (youngerUncached) {
          for (_ <- 0 until 3) {
            assert(!dut.io.dataRequestValid.toBoolean)
            sample(dut)
          }
          dut.io.robHeadPointer #= 2
        }

        var loadRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && loadRequestWait < 10) {
          sample(dut)
          loadRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(!dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.uncached.toBoolean == youngerUncached)
        assert(dut.io.dataRequest.robPointer.toBigInt == 2)
      }
    }
  }

  test("unknown older stores block loads and stale cache responses are rejected") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-ordering-and-stale-response", 0x4c53) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, 1, 0x300)
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        dut.io.aguValid #= false
        var translationWait = 0
        while (!dut.io.translationRequestValid.toBoolean && translationWait < 4) {
          sample(dut)
          translationWait += 1
        }
        assert(dut.io.translationRequestValid.toBoolean)
        assert(!dut.io.completionValid.toBoolean)
        assert(!dut.io.dataRequestValid.toBoolean)

        setStoreAgu(dut, 0, 0x400, BigInt("a5a5a5a5", 16))
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        dut.io.aguValid #= false
        var storeTranslationWait = 0
        while (!dut.io.completionValid.toBoolean && storeTranslationWait < 4) {
          sample(dut)
          storeTranslationWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        sample(dut)
        var loadRequestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && loadRequestWait < 12) {
          sample(dut)
          loadRequestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(!dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 1)
        assert(dut.io.dataRequest.virtualAddress.toBigInt == 0x300)

        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        assert(!dut.io.dataRequestValid.toBoolean)

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.data #= BigInt("89abcdef", 16)
        sleep(1)
        assert(!dut.io.completionValid.toBoolean)
        sample(dut)

        dut.io.dataResponse.robPointer #= 1
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.data.toBigInt == BigInt("89abcdef", 16))
      }
  }

  test("a misaligned AGU completion is buffered behind a cache response") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-misaligned-completion-buffer", 0x4c5c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isStore #= true
        dut.io.allocate(1).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        setLoadAgu(dut, 0, 0x180)
        sample(dut)
        dut.io.aguValid #= false
        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 10) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.data #= BigInt("12345678", 16)
        setStoreAgu(dut, 1, 0x201, BigInt("89abcdef", 16))
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        assert(dut.io.completion.data.toBigInt == BigInt("12345678", 16))

        dut.io.dataResponseValid #= false
        dut.io.aguValid #= false
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.exception.valid.toBoolean)
        assert(dut.io.completion.exception.ecode.toBigInt == 9)
        assert(dut.io.completion.exception.badVAddrValid.toBoolean)
        assert(dut.io.completion.exception.badVAddr.toBigInt == 0x201)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("a Store completion retries after a simultaneous Load response") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-load-completion-collision", 0x4c72) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 3
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        dut.io.allocate(1).robPointer #= 1
        dut.io.allocate(1).isLoad #= true
        dut.io.allocate(1).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        dut.io.storeDataEnable #= false
        setStoreAgu(dut, 0, 0x100, BigInt("89abcdef", 16))
        sample(dut)
        dut.io.aguValid #= false
        sample(dut)

        setLoadAgu(dut, 1, 0x200, loadIndex = 0, pdst = 8)
        sample(dut)
        dut.io.aguValid #= false
        dut.io.dataRequestReady #= true
        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 12) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.robPointer.toBigInt == 1)
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.storeDataEnable #= true
        setStoreAgu(dut, 0, 0x100, BigInt("89abcdef", 16))
        sample(dut)
        dut.io.aguValid #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 1
        dut.io.dataResponse.data #= BigInt("12345678", 16)
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 1)
        assert(dut.io.completion.data.toBigInt == BigInt("12345678", 16))

        dut.io.dataResponseValid #= false
        sample(dut)
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        assert(!dut.io.completion.exception.valid.toBoolean)
      }
  }

  test("a store translation exception completes without issuing a memory request") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-translation-exception", 0x4c54) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0

        dut.io.translationFault #= true
        dut.io.translationEcode #= 3
        setStoreAgu(dut, 0, 0x12345678L, BigInt("cafebabe", 16))
        sleep(1)
        assert(dut.io.aguReady.toBoolean)
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.robPointer.toBigInt == 0)
        assert(dut.io.completion.exception.valid.toBoolean)
        assert(dut.io.completion.exception.ecode.toBigInt == 3)
        assert(dut.io.completion.exception.badVAddrValid.toBoolean)
        assert(dut.io.completion.exception.badVAddr.toBigInt == BigInt("12345678", 16))
        assert(!dut.io.dataRequestValid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        dut.io.commit(0).exception.valid #= true
        dut.io.dataRequestReady #= true
        for (_ <- 0 until 3) sample(dut)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("load-linked completion carries its physical reservation line") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-load-linked-reservation", 0x4c55) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, 0, 0x2340, isLl = true)
        sample(dut)
        dut.io.aguValid #= false

        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 10) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.pdst #= 7
        dut.io.dataResponse.data #= BigInt("76543210", 16)
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.data.toBigInt == BigInt("76543210", 16))
        assert(dut.io.completion.sideEffectData.toBigInt == 0x2340)
      }
  }

  test("a failed store-conditional writes zero and never reaches memory") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-conditional-failure", 0x4c56) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.reservationValid #= true
        dut.io.reservationLineAddress #= 0x8
        setStoreAgu(dut, 0, 0x240, BigInt("11223344", 16), isSc = true, pdst = 11)
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.pdst.toBigInt == 11)
        assert(dut.io.completion.writesPdst.toBoolean)
        assert(dut.io.completion.data.toBigInt == 0)
        assert(!dut.io.dataRequestValid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0
        sleep(1)
        assert(dut.io.releaseStoreValid.toBigInt == 1)
        assert(!dut.io.dataRequestValid.toBoolean)
        sample(dut)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("a matching store-conditional writes one and issues the store after commit") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-store-conditional-success", 0x4c57) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.reservationValid #= true
        dut.io.reservationLineAddress #= 0x8
        setStoreAgu(dut, 0, 0x23c, BigInt("55667788", 16), isSc = true, pdst = 12)
        sample(dut)
        dut.io.aguValid #= false

        var completionWait = 0
        while (!dut.io.completionValid.toBoolean && completionWait < 8) {
          sample(dut)
          completionWait += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.data.toBigInt == 1)
        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.commitValid #= 0

        var requestWait = 0
        while (!dut.io.dataRequestValid.toBoolean && requestWait < 5) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.isWrite.toBoolean)
        assert(dut.io.dataRequest.writeData.toBigInt == BigInt("55667788", 16))
      }
  }

  test("an uncached store completes only after its matching B response") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-uncached-store-response", 0x4c58) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationUncached #= true
        setStoreAgu(dut, 0, 0x1fe00100L, BigInt("12345678", 16))
        sample(dut)
        dut.io.aguValid #= false

        var cycles = 0
        while (!dut.io.dataRequestValid.toBoolean && cycles < 10) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.dataRequestValid.toBoolean)
        assert(dut.io.dataRequest.uncached.toBoolean)
        assert(!dut.io.completionValid.toBoolean)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        assert(!dut.io.dataRequestValid.toBoolean)
        for (_ <- 0 until 3) {
          sample(dut)
          assert(!dut.io.completionValid.toBoolean)
          assert(dut.io.releaseStoreValid.toBigInt == 0)
        }

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.recoveryEpoch #= 0
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(!dut.io.completion.exception.valid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.commit(0).robPointer #= 0
        dut.io.commit(0).isStore #= true
        dut.io.commit(0).storeQueueIndex #= 0
        dut.io.commit(0).retired #= true
        sample(dut)
        dut.io.commitValid #= 0
        dut.io.commit(0).retired #= false
        assert(dut.io.releaseStoreValid.toBigInt == 1)
        sample(dut)
        assert(dut.io.releaseStoreValid.toBigInt == 0)
      }
  }

  test("an uncached store B error becomes precise ADEM") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-uncached-store-error", 0x4c59) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationUncached #= true
        setStoreAgu(dut, 0, 0x1fe00104L, BigInt("89abcdef", 16))
        sample(dut)
        dut.io.aguValid #= false
        while (!dut.io.dataRequestValid.toBoolean) sample(dut)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.error #= true
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.exception.valid.toBoolean)
        assert(dut.io.completion.exception.ecode.toBigInt == 8)
        assert(dut.io.completion.exception.esubcode.toBigInt == 1)
        assert(dut.io.completion.exception.badVAddrValid.toBoolean)
        assert(dut.io.completion.exception.badVAddr.toBigInt == BigInt("1fe00104", 16))
      }
  }

  test("flush drains an irreversible uncached store without a stale completion") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-uncached-store-flush", 0x4c5a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationUncached #= true
        setStoreAgu(dut, 0, 0x1fe00108L, BigInt("feedface", 16))
        sample(dut)
        dut.io.aguValid #= false
        while (!dut.io.dataRequestValid.toBoolean) sample(dut)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        assert(dut.io.storeDrainBusy.toBoolean)
        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(!dut.io.completionValid.toBoolean)
        var drainCycles = 0
        while (dut.io.storeDrainBusy.toBoolean && drainCycles < 3) {
          sample(dut)
          assert(!dut.io.completionValid.toBoolean)
          drainCycles += 1
        }
        assert(!dut.io.storeDrainBusy.toBoolean)
      }
  }

  test("flush cancels an uncached store before the hierarchy accepts it") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-uncached-store-preaccept-flush", 0x4c5d) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationUncached #= true
        setStoreAgu(dut, 0, 0x1fe0010cL, BigInt("decafbad", 16))
        sample(dut)
        dut.io.aguValid #= false
        while (!dut.io.dataRequestValid.toBoolean) sample(dut)
        assert(!dut.io.dataRequestReady.toBoolean)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        dut.io.dataRequestReady #= true
        for (_ <- 0 until 3) {
          sample(dut)
          assert(!dut.io.dataRequestValid.toBoolean)
          assert(!dut.io.storeDrainBusy.toBoolean)
          assert(!dut.io.completionValid.toBoolean)
        }
      }
  }

  test("uncached LL and SC do not create or consume a reservation") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-uncached-atomic", 0x4c5b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.translationUncached #= true
        setLoadAgu(dut, 0, 0x2340, isLl = true)
        sample(dut)
        dut.io.aguValid #= false
        while (!dut.io.dataRequestValid.toBoolean) sample(dut)
        dut.io.dataRequestReady #= true
        sample(dut)
        dut.io.dataRequestReady #= false
        dut.io.dataResponseValid #= true
        dut.io.dataResponse.robPointer #= 0
        dut.io.dataResponse.pdst #= 7
        sample(dut)
        dut.io.dataResponseValid #= false
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.sideEffectData.toBigInt == 0x2341)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 1
        dut.io.allocate(0).isStore #= true
        dut.io.allocate(0).storeQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        dut.io.reservationValid #= true
        dut.io.reservationLineAddress #= 0x8d
        setStoreAgu(dut, 1, 0x2340, BigInt("11223344", 16), isSc = true, pdst = 9)
        sample(dut)
        dut.io.aguValid #= false
        var cycles = 0
        while (!dut.io.completionValid.toBoolean && cycles < 8) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.completionValid.toBoolean)
        assert(dut.io.completion.data.toBigInt == 0)
        assert(!dut.io.dataRequestValid.toBoolean)
      }
  }

  test("a cancelled translation releases the LSQ owner without architectural completion") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-lsq")
      .compile(new LoadStoreQueueProbe(config))
      .doSim("ooo-lsq-translation-cancel", 0x4c7b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.translationResponseEnable #= false
        dut.io.allocateValid #= 1
        dut.io.allocate(0).robPointer #= 0
        dut.io.allocate(0).isLoad #= true
        dut.io.allocate(0).loadQueueIndex #= 0
        sample(dut)
        dut.io.allocateValid #= 0
        setLoadAgu(dut, 0, 0x2460)
        sample(dut)
        dut.io.aguValid #= false

        while (!dut.io.translationRequestValid.toBoolean) sample(dut)
        assert(dut.io.translationRequestAddress.toBigInt == 0x2460)
        sample(dut)
        dut.io.translationCancelled #= true
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false
        dut.io.translationCancelled #= false
        assert(!dut.io.completionValid.toBoolean)
        assert(!dut.io.loadWakeupValid.toBoolean)
        assert(!dut.io.dataRequestValid.toBoolean)

        var cycles = 0
        while (!dut.io.translationRequestValid.toBoolean && cycles < 8) {
          sample(dut)
          cycles += 1
          assert(!dut.io.completionValid.toBoolean)
          assert(!dut.io.dataRequestValid.toBoolean)
        }
        assert(dut.io.translationRequestValid.toBoolean)
        assert(dut.io.translationRequestAddress.toBigInt == 0x2460)
        sample(dut)
        dut.io.translationResponseEnable #= true
        sample(dut)
        dut.io.translationResponseEnable #= false
        while (!dut.io.dataRequestValid.toBoolean) sample(dut)
        assert(!dut.io.completionValid.toBoolean)
        assert(dut.io.dataRequest.physicalAddress.toBigInt == 0x2460)
      }
  }
}
