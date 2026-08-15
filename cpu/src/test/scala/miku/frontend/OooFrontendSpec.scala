package miku.frontend

import miku.core._
import miku.predict._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class OooFrontendSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit
  private val turnoverConfig = config.copy(enableFrontendTranslationTurnover = true)

  private def sample(dut: OooFrontend): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: OooFrontend): Unit = {
    dut.io.translationRequest.ready #= false
    dut.io.translationResponse.valid #= false
    dut.io.translationResponse.virtualAddress #= 0
    dut.io.translationResponse.physicalAddress #= 0
    dut.io.translationResponse.uncached #= false
    dut.io.translationResponse.cancelled #= false
    dut.io.translationResponse.exception.valid #= false
    dut.io.translationResponse.exception.ecode #= 0
    dut.io.translationResponse.exception.esubcode #= 0
    dut.io.translationResponse.exception.badVAddrValid #= false
    dut.io.translationResponse.exception.badVAddr #= 0
    dut.io.translationResponse.exception.tlbRefill #= false
    dut.io.cacheRequestReady #= false
    dut.io.cacheHitResponsePending #= false
    dut.io.cacheResponseValid #= false
    dut.io.cacheResponse.virtualAddress #= 0
    dut.io.cacheResponse.physicalAddress #= 0
    for (lane <- 0 until config.fetchWidth) {
      dut.io.cacheResponse.instructions(lane) #= 0
      dut.io.cacheResponse.predecode(lane).valid #= false
      dut.io.cacheResponse.predecode(lane).branchType #= 1
      dut.io.cacheResponse.predecode(lane).target #= 0
      dut.io.cacheResponse.predecode(lane).staticTaken #= false
      dut.io.cacheResponse.predecode(lane).indirect #= false
    }
    dut.io.cacheResponse.error #= false
    dut.io.decodeReady #= 0
    dut.io.redirectValid #= false
    dut.io.redirectTarget #= 0
    dut.io.predictorUpdateValid #= false
    dut.io.predictorUpdatePc #= 0
    dut.io.predictorUpdateTaken #= false
    dut.io.predictorUpdateTarget #= 0
    dut.io.predictorUpdateType #= 0
    dut.io.predictorUpdateMetadata #= 0
    dut.io.predictorUpdateIsCall #= false
    dut.io.predictorUpdateIsReturn #= false
    dut.io.predictorRetireValid #= 0
    dut.io.predictorRetireTaken #= 0
    dut.io.predictorRetireIsCall #= 0
    dut.io.predictorRetireIsReturn #= 0
    for (lane <- 0 until config.commitWidth) {
      dut.io.predictorRetireType(lane) #= 0
      dut.io.predictorRetireReturnAddress(lane) #= 0
    }
    dut.io.privilege #= 0
    dut.io.interruptPending #= false
  }

  private def clearPredecode(dut: OooFrontend): Unit = {
    for (lane <- 0 until config.fetchWidth) {
      dut.io.cacheResponse.predecode(lane).valid #= false
      dut.io.cacheResponse.predecode(lane).branchType #= 1
      dut.io.cacheResponse.predecode(lane).target #= 0
      dut.io.cacheResponse.predecode(lane).staticTaken #= false
      dut.io.cacheResponse.predecode(lane).indirect #= false
    }
  }

  private def setBranchPredecode(
      dut: OooFrontend,
      lane: Int,
      branchType: Int,
      target: BigInt,
      staticTaken: Boolean
  ): Unit = {
    dut.io.cacheResponse.predecode(lane).valid #= true
    dut.io.cacheResponse.predecode(lane).branchType #= branchType
    dut.io.cacheResponse.predecode(lane).target #= target
    dut.io.cacheResponse.predecode(lane).staticTaken #= staticTaken
    dut.io.cacheResponse.predecode(lane).indirect #= false
  }

  private def acceptFetch(dut: OooFrontend, expectedAddress: BigInt): Unit = {
    var translationCycles = 0
    while (!dut.io.translationRequest.valid.toBoolean && translationCycles < 8) {
      sample(dut)
      translationCycles += 1
    }
    assert(dut.io.translationRequest.valid.toBoolean)
    assert(dut.io.translationRequest.virtualAddress.toBigInt == expectedAddress)
    dut.io.translationRequest.ready #= true
    sample(dut)
    dut.io.translationRequest.ready #= false

    dut.io.translationResponse.valid #= true
    dut.io.translationResponse.virtualAddress #= expectedAddress
    dut.io.translationResponse.physicalAddress #= expectedAddress
    dut.io.translationResponse.uncached #= false
    sleep(1)
    assert(dut.io.translationResponse.ready.toBoolean)
    sample(dut)
    dut.io.translationResponse.valid #= false

    var cycles = 0
    while (!dut.io.cacheRequestValid.toBoolean && cycles < 8) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.cacheRequestValid.toBoolean)
    assert(dut.io.cacheRequest.virtualAddress.toBigInt == expectedAddress)
    assert(dut.io.cacheRequest.physicalAddress.toBigInt == expectedAddress)
    dut.io.cacheRequestReady #= true
    sample(dut)
    dut.io.cacheRequestReady #= false
  }

  private def returnGroup(dut: OooFrontend, address: BigInt, firstRd: Int): Unit = {
    clearPredecode(dut)
    dut.io.cacheResponseValid #= true
    dut.io.cacheResponse.virtualAddress #= address
    dut.io.cacheResponse.physicalAddress #= address
    for (lane <- 0 until config.fetchWidth) {
      dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (firstRd + lane))
    }
    dut.io.cacheResponse.error #= false
    sample(dut)
    dut.io.cacheResponseValid #= false
  }

  test("a cancelled translation releases the fetch owner and retries the same PC") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-translation-cancel")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-translation-cancel", 0x4c7a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val pc = config.resetVector
        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == pc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= pc
        dut.io.translationResponse.cancelled #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationResponse.cancelled #= false

        var cycles = 0
        while (!dut.io.translationRequest.valid.toBoolean && cycles < 8) {
          assert(!dut.io.cacheRequestValid.toBoolean)
          assert(!dut.io.cacheUncachedRequestValid.toBoolean)
          assert(dut.io.occupancy.toBigInt == 0)
          sample(dut)
          cycles += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == pc)
        assert(!dut.io.cacheRequestValid.toBoolean)
        assert(!dut.io.cacheUncachedRequestValid.toBoolean)
      }
  }

  test("a straight-line translation response turns over to L1I and the next translation") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-translation-response-bypass")
      .compile(new OooFrontend(turnoverConfig))
      .doSim("ooo-frontend-translation-response-bypass", 0x4c7b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val firstPc = config.resetVector
        val secondPc = firstPc + config.fetchWidth * 4
        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == firstPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.cacheRequestReady #= true
        dut.io.translationRequest.ready #= true
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= firstPc
        dut.io.translationResponse.physicalAddress #= 0x1000
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == firstPc)
        assert(dut.io.cacheRequest.physicalAddress.toBigInt == 0x1000)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == secondPc)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.cacheRequestReady #= false
        dut.io.translationRequest.ready #= false

        // The next translated group may replace the prior cache request in the same cycle that
        // the prior hit response returns.  This is the steady-state two-cycle turnover case.
        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= firstPc
        dut.io.cacheResponse.physicalAddress #= 0x1000
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= BigInt("00100000", 16) | (lane + 1)
        }
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= secondPc
        dut.io.translationResponse.physicalAddress #= 0x1010
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == secondPc)
        assert(dut.io.cacheRequest.physicalAddress.toBigInt == 0x1010)
        sample(dut)
        dut.io.cacheResponseValid #= false
        dut.io.translationResponse.valid #= false
        dut.io.cacheRequestReady #= false

        assert(dut.io.occupancy.toBigInt == config.fetchWidth)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == secondPc + config.fetchWidth * 4)
      }
  }

  test("translation turnover buffers the accepted group while L1I is blocked") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-buffered-translation-turnover")
      .compile(new OooFrontend(turnoverConfig))
      .doSim("ooo-frontend-buffered-translation-turnover", 0x4c7e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val firstPc = config.resetVector
        val secondPc = firstPc + config.fetchWidth * 4
        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == firstPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        // The current result cannot enter L1I, but accepting it is sufficient to launch the
        // younger translation because its complete payload is retained in translatedRequest.
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= firstPc
        dut.io.translationResponse.physicalAddress #= 0x1000
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(!dut.io.cacheRequestReady.toBoolean)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == secondPc)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationRequest.ready #= false

        // The younger result is held at the translation boundary until the older buffered group
        // enters L1I.  Its address and PA must remain those of the older accepted response.
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= secondPc
        dut.io.translationResponse.physicalAddress #= 0x1010
        sleep(1)
        assert(!dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == firstPc)
        assert(dut.io.cacheRequest.physicalAddress.toBigInt == 0x1000)

        dut.io.cacheRequestReady #= true
        sample(dut)
        dut.io.cacheRequestReady #= false
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false
      }
  }

  test("a trained conditional branch turns over translation with its speculative history") {
    val historyTurnoverConfig = turnoverConfig.copy(enableFrontendHistoryTurnover = true)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(historyTurnoverConfig))
      .doSim("ooo-frontend-history-turnover", 0x4c7c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(132)
        sleep(1)

        val branchPc = config.resetVector + 0x200
        val branchTarget = branchPc + 0x40
        val phtIndex = ((branchPc >> 4) & 0x7f).toInt
        dut.io.predictorUpdatePc #= branchPc
        dut.io.predictorUpdateTaken #= true
        dut.io.predictorUpdateTarget #= branchTarget
        dut.io.predictorUpdateType #= 0
        dut.io.predictorUpdateMetadata #=
          (phtIndex | (2 << PredictorMetadataLayout.PhtStateLsb))
        dut.io.predictorUpdateValid #= true
        sample(dut)
        dut.io.predictorUpdateValid #= false

        dut.io.redirectTarget #= branchPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false

        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= branchPc
        dut.io.translationResponse.physicalAddress #= branchPc
        dut.io.cacheRequestReady #= true
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == branchPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchTarget)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationRequest.ready #= false

        // Complete the branch-bearing group while the target translation enters L1I.  The
        // target lookup must carry the taken bit that was bypassed into GHR on the prior edge.
        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= branchPc
        dut.io.cacheResponse.physicalAddress #= branchPc
        dut.io.cacheResponse.instructions(0) #= encodeConditionalBranch(0x16, 0x40)
        for (lane <- 1 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | lane)
        }
        setBranchPredecode(
          dut,
          lane = 0,
          branchType = 0,
          target = branchTarget,
          staticTaken = false
        )
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= branchTarget
        dut.io.translationResponse.physicalAddress #= branchTarget
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == branchTarget)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.cacheResponseValid #= false
        dut.io.cacheRequestReady #= false

        returnGroup(dut, branchTarget, firstRd = 9)
        val targetPhtIndex = (1 << 7) | ((branchTarget >> 4) & 0x7f).toInt
        assert(dut.io.decoded(1).pc.toBigInt == branchTarget)
        assert(
          (dut.io.decoded(1).predictorMetadata.toBigInt &
            ((1 << PredictorMetadataLayout.PhtIndexWidth) - 1)) == targetPhtIndex
        )
      }
  }

  test("a trained call and return turn over translation through the speculative RAS") {
    val historyTurnoverConfig = turnoverConfig.copy(enableFrontendHistoryTurnover = true)
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(historyTurnoverConfig))
    for (callLane <- 0 until config.fetchWidth) {
      compiled.doSim(s"ooo-frontend-ras-turnover-lane-$callLane", 0x4c7d + callLane) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(132)
        sleep(1)

        val callPc = config.resetVector + 0x300 + callLane * 4
        val calleePc = callPc + 0x80
        val returnPc = callPc + 4

        dut.io.predictorUpdatePc #= callPc
        dut.io.predictorUpdateTaken #= true
        dut.io.predictorUpdateTarget #= calleePc
        dut.io.predictorUpdateType #= 4
        dut.io.predictorUpdateValid #= true
        sample(dut)

        dut.io.predictorUpdatePc #= calleePc
        dut.io.predictorUpdateTarget #= 0
        dut.io.predictorUpdateType #= 3
        sample(dut)
        dut.io.predictorUpdateValid #= false

        dut.io.redirectTarget #= callPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false

        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == callPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= callPc
        dut.io.translationResponse.physicalAddress #= callPc
        dut.io.cacheRequestReady #= true
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == callPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == calleePc)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationRequest.ready #= false

        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= callPc
        dut.io.cacheResponse.physicalAddress #= callPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #=
            (if (lane == callLane) encodeDirectBranch(0x15, 0x80)
             else (BigInt("00100000", 16) | lane))
        }
        setBranchPredecode(
          dut,
          lane = callLane,
          branchType = 4,
          target = calleePc,
          staticTaken = true
        )
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= calleePc
        dut.io.translationResponse.physicalAddress #= calleePc
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == calleePc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == returnPc)
      }
    }
  }

  test("the delayed speculative RAS preserves a lane-three return address") {
    val conservativeConfig = turnoverConfig.copy(enableFrontendHistoryTurnover = false)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-delayed-ras")
      .compile(new OooFrontend(conservativeConfig))
      .doSim("ooo-frontend-delayed-ras-lane-3", 0x4c81) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(132)
        sleep(1)

        val callPc = config.resetVector + 0x30c
        val calleePc = callPc + 0x80
        val returnPc = callPc + 4

        dut.io.predictorUpdatePc #= callPc
        dut.io.predictorUpdateTaken #= true
        dut.io.predictorUpdateTarget #= calleePc
        dut.io.predictorUpdateType #= 4
        dut.io.predictorUpdateValid #= true
        sample(dut)
        dut.io.predictorUpdatePc #= calleePc
        dut.io.predictorUpdateTarget #= 0
        dut.io.predictorUpdateType #= 3
        sample(dut)
        dut.io.predictorUpdateValid #= false

        dut.io.redirectTarget #= callPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false

        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == callPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= callPc
        dut.io.translationResponse.physicalAddress #= callPc
        dut.io.cacheRequestReady #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == callPc)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.cacheRequestReady #= false

        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == calleePc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= callPc
        dut.io.cacheResponse.physicalAddress #= callPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #=
            (if (lane == 3) encodeDirectBranch(0x15, 0x80)
             else (BigInt("00100000", 16) | lane))
        }
        setBranchPredecode(dut, lane = 3, branchType = 4, target = calleePc, staticTaken = true)
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= calleePc
        dut.io.translationResponse.physicalAddress #= calleePc
        dut.io.cacheRequestReady #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == calleePc)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.cacheResponseValid #= false
        dut.io.cacheRequestReady #= false

        while (!dut.io.translationRequest.valid.toBoolean) sample(dut)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == returnPc)
      }
  }

  private def encodeDirectBranch(opcode: Int, byteOffset: Int): BigInt = {
    require((byteOffset & 3) == 0)
    val encoded = (byteOffset >> 2) & ((1 << 26) - 1)
    val high10 = (encoded >> 16) & 0x3ff
    val low16 = encoded & 0xffff
    (BigInt(opcode) << 26) | (BigInt(low16) << 10) | high10
  }

  private def encodeConditionalBranch(opcode: Int, byteOffset: Int): BigInt = {
    require((byteOffset & 3) == 0)
    val encoded = (byteOffset >> 2) & 0xffff
    (BigInt(opcode) << 26) | (BigInt(encoded) << 10)
  }

  private def expectDecode(dut: OooFrontend, pcs: Seq[BigInt], rds: Seq[Int]): Unit = {
    assert(dut.io.decodeValid.toBigInt == ((BigInt(1) << pcs.size) - 1))
    pcs.indices.foreach { lane =>
      assert(dut.io.decoded(lane).pc.toBigInt == pcs(lane))
      assert(
        dut.io.decoded(lane).instruction.toBigInt ==
          (BigInt("00100000", 16) | rds(lane))
      )
    }
  }

  test("fetch4 groups compact across decode3 and redirects discard stale responses") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-fetch4-decode3", 0x4c62) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val resetPc = config.resetVector
        acceptFetch(dut, resetPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == resetPc + config.fetchWidth * 4)
        returnGroup(dut, resetPc, firstRd = 1)
        assert(dut.io.occupancy.toBigInt == 4)
        expectDecode(dut, Seq(resetPc, resetPc + 4, resetPc + 8), Seq(1, 2, 3))

        dut.io.decodeReady #= 7
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 1)
        expectDecode(dut, Seq(resetPc + 12), Seq(4))

        acceptFetch(dut, resetPc + 16)
        returnGroup(dut, resetPc + 16, firstRd = 5)
        assert(dut.io.occupancy.toBigInt == 5)
        expectDecode(dut, Seq(resetPc + 12, resetPc + 16, resetPc + 20), Seq(4, 5, 6))

        dut.io.decodeReady #= 7
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 2)

        acceptFetch(dut, resetPc + 32)
        dut.io.redirectTarget #= resetPc + 0x124
        dut.io.redirectValid #= true
        sleep(1)
        assert(dut.io.cacheKill.toBoolean)
        sample(dut)
        dut.io.redirectValid #= false
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.fetchPc.toBigInt == resetPc + 0x124)

        dut.io.cacheResponseValid #= true
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (9 + lane))
        }
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 0)

        acceptFetch(dut, resetPc + 0x124)
        returnGroup(dut, resetPc + 0x124, firstRd = 13)
        assert(dut.io.occupancy.toBigInt == 3)
        expectDecode(
          dut,
          Seq(resetPc + 0x124, resetPc + 0x128, resetPc + 0x12c),
          Seq(14, 15, 16)
        )
      }
  }

  test("a misaligned fetch keeps ADEF metadata ahead of placeholder decode errors") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-fetch-adef-priority", 0x4c63) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val badPc = BigInt("227f9789", 16)
        dut.io.redirectValid #= true
        dut.io.redirectTarget #= badPc
        sample(dut)
        dut.io.redirectValid #= false

        var requestWait = 0
        while (!dut.io.translationRequest.valid.toBoolean && requestWait < 8) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == badPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= badPc
        dut.io.translationResponse.exception.valid #= true
        dut.io.translationResponse.exception.ecode #= 8
        dut.io.translationResponse.exception.badVAddrValid #= true
        dut.io.translationResponse.exception.badVAddr #= badPc
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false

        assert(dut.io.decodeValid.toBigInt == 1)
        assert(dut.io.decoded(0).pc.toBigInt == badPc)
        assert(dut.io.decoded(0).exception.valid.toBoolean)
        assert(dut.io.decoded(0).exception.ecode.toBigInt == 8)
        assert(dut.io.decoded(0).exception.badVAddr.toBigInt == badPc)
      }
  }

  test("instruction read errors report ADEF and a same-cycle interrupt has priority") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-read-error")
      .compile(new OooFrontend(config))

    for ((interruptPending, expectedEcode, seed) <- Seq(
        (false, 8, 0x4c68),
        (true, 0, 0x4c69)
      )) {
      compiled.doSim(s"ooo-frontend-read-error-interrupt-$interruptPending", seed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val pc = config.resetVector
        acceptFetch(dut, pc)
        dut.io.interruptPending #= interruptPending
        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= pc
        dut.io.cacheResponse.physicalAddress #= pc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= BigInt("00100000", 16)
        }
        dut.io.cacheResponse.error #= true
        sample(dut)
        dut.io.cacheResponseValid #= false

        assert(dut.io.decodeValid.toBigInt == 7)
        for (lane <- 0 until config.decodeWidth) {
          val laneTakesInterrupt = interruptPending && lane == 0
          assert(dut.io.decoded(lane).exception.valid.toBoolean)
          assert(
            dut.io.decoded(lane).exception.ecode.toBigInt ==
              (if (laneTakesInterrupt) expectedEcode else 8)
          )
          assert(dut.io.decoded(lane).exception.esubcode.toBigInt == 0)
          assert(
            dut.io.decoded(lane).exception.badVAddrValid.toBoolean == !laneTakesInterrupt
          )
          if (!laneTakesInterrupt) {
            assert(dut.io.decoded(lane).exception.badVAddr.toBigInt == pc + lane * 4)
          }
          assert(!dut.io.decoded(lane).exception.tlbRefill.toBoolean)
        }
      }
    }
  }

  test("a pretranslated group waits for four free instruction-buffer slots") {
    val capacityConfig = config.copy(instructionBufferEntries = 8)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(capacityConfig))
      .doSim("ooo-frontend-pretranslation-capacity", 0x4c64) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        acceptFetch(dut, base)
        returnGroup(dut, base, firstRd = 1)
        assert(dut.io.occupancy.toBigInt == 4)

        acceptFetch(dut, base + 16)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == base + 32)

        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= base + 32
        dut.io.translationResponse.physicalAddress #= base + 32
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false

        returnGroup(dut, base + 16, firstRd = 5)
        assert(dut.io.occupancy.toBigInt == 8)
        assert(!dut.io.cacheRequestValid.toBoolean)

        dut.io.decodeReady #= 7
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 5)
        assert(!dut.io.cacheRequestValid.toBoolean)
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 2)
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == base + 32)
      }
  }

  test("a buffered translation exception waits for an instruction-buffer slot") {
    val capacityConfig = config.copy(instructionBufferEntries = 8)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(capacityConfig))
      .doSim("ooo-frontend-pretranslation-exception-capacity", 0x4c65) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        acceptFetch(dut, base)
        returnGroup(dut, base, firstRd = 1)
        assert(dut.io.occupancy.toBigInt == 4)

        acceptFetch(dut, base + 16)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= base + 32
        dut.io.translationResponse.physicalAddress #= 0
        dut.io.translationResponse.exception.valid #= true
        dut.io.translationResponse.exception.ecode #= 3
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationResponse.exception.valid #= false

        returnGroup(dut, base + 16, firstRd = 5)
        assert(dut.io.occupancy.toBigInt == 8)
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 8)

        dut.io.decodeReady #= 7
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 5)
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 6)
      }
  }

  test("a stale translation response is drained and the original PC is retried") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-translation-response-match", 0x4c65) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val resetPc = config.resetVector
        var requestWait = 0
        while (!dut.io.translationRequest.valid.toBoolean && requestWait < 8) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == resetPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        // Consume a response belonging to a later request without allowing its physical address
        // to be paired with resetPc.
        dut.io.translationResponse.virtualAddress #= resetPc + 16
        dut.io.translationResponse.physicalAddress #= resetPc + 16
        dut.io.translationResponse.valid #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false
        assert(!dut.io.cacheRequestValid.toBoolean)

        var retryWait = 0
        while (!dut.io.translationRequest.valid.toBoolean && retryWait < 8) {
          sample(dut)
          retryWait += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == resetPc)
      }
  }

  test("a stale cache response cannot satisfy the post-redirect request") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-cache-response-match", 0x4c66) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val oldPc = config.resetVector
        val redirectPc = config.resetVector + 0x108
        val nextGroup = (redirectPc & ~BigInt(config.fetchWidth * 4 - 1)) +
          config.fetchWidth * 4
        acceptFetch(dut, oldPc)

        dut.io.redirectTarget #= redirectPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false
        assert(dut.io.occupancy.toBigInt == 0)

        acceptFetch(dut, redirectPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == nextGroup)

        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= oldPc
        dut.io.cacheResponse.physicalAddress #= oldPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | lane + 1)
        }
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.fetchPc.toBigInt == nextGroup)

        returnGroup(dut, redirectPc, firstRd = 9)
        assert(dut.io.occupancy.toBigInt == 2)
        expectDecode(dut, Seq(redirectPc, redirectPc + 4), Seq(11, 12))
      }
  }

  test("a predicted branch drains a sequential translation accepted on the response edge") {
    for (deferred <- Seq(false, true)) {
      val testConfig = config.copy(enableDeferredFrontendCorrectionCleanup = deferred)
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-frontend-correction-$deferred"
        )
        .compile(new OooFrontend(testConfig))
        .doSim(
          s"ooo-frontend-same-cycle-prediction-translation-$deferred",
          if (deferred) 0x4c6f else 0x4c67
        ) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val sequentialPc = base + config.fetchWidth * 4
        val branchTarget = base + 4 + 0x40
        acceptFetch(dut, base)

        // The speculative sequential translation and the branch-bearing cache response are both
        // accepted on this edge.  The translation response appears later and must be drained.
        dut.io.translationRequest.ready #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        dut.io.cacheResponse.error #= false
        sleep(1)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == sequentialPc)
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.cacheResponseValid #= false
        sample(dut)
        assert(dut.io.fetchPc.toBigInt == branchTarget)

        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= sequentialPc
        dut.io.translationResponse.physicalAddress #= sequentialPc
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false

        var retryWait = 0
        while (!dut.io.translationRequest.valid.toBoolean && retryWait < 8) {
          sample(dut)
          retryWait += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchTarget)
      }
    }
  }

  test("FixBranch kills a stale cached handoff at its synchronous lookup response") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-fix-branch-poisoned-cache-handoff", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val sequentialPc = base + config.fetchWidth * 4
        val branchTarget = base + 4 + 0x40
        acceptFetch(dut, base)

        // Finish translating the sequential group while the branch-bearing group is in L1I.
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= sequentialPc
        dut.io.translationResponse.physicalAddress #= sequentialPc
        sample(dut)
        dut.io.translationResponse.valid #= false

        // Keep response predecode out of the L1I request enable: accept the already translated
        // sequential request, then cancel it when its synchronous lookup response is available.
        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == sequentialPc)
        assert(!dut.io.cacheKill.toBoolean)
        sample(dut)
        dut.io.cacheRequestReady #= false

        // A turnover hit may already have registered the younger response on the correction
        // edge.  The following-cycle kill must suppress that visible pulse as well as canceling
        // the L1I lookup state; clearing only the frontend owner at the edge is too late.
        clearPredecode(dut)
        dut.io.cacheResponse.virtualAddress #= sequentialPc
        dut.io.cacheResponse.physicalAddress #= sequentialPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (5 + lane))
        }
        assert(dut.io.cacheKill.toBoolean)
        // Predictor history/RAS restore is deliberately isolated from response predecode.  No
        // corrected lookup may start until that registered restore has completed.
        assert(!dut.io.translationRequest.valid.toBoolean)
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(!dut.io.cacheKill.toBoolean)
        assert(dut.io.fetchPc.toBigInt == branchTarget)
        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchTarget)

        // The canceled cached request has no response-drain obligation.
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= branchTarget
        dut.io.translationResponse.physicalAddress #= branchTarget
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.cacheRequestReady #= true
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == branchTarget)
        sample(dut)
        dut.io.cacheRequestReady #= false
        assert(dut.io.occupancy.toBigInt == 2)

        returnGroup(dut, branchTarget, firstRd = 9)
        assert(dut.io.occupancy.toBigInt == 5)
      }
  }

  test("a pending FixBranch discards the overlapping hit-turnover owner") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-pending-fix-branch-hit-turnover", 0x4c6e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val firstPc = config.resetVector
        val secondPc = firstPc + config.fetchWidth * 4
        val thirdPc = secondPc + config.fetchWidth * 4
        val branchTarget = firstPc + 4 + 0x40
        acceptFetch(dut, firstPc)

        // The first hit token moves firstPc into the registered-response owner while secondPc
        // starts its lookup.  Translation turnover simultaneously allocates thirdPc.
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == secondPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= secondPc
        dut.io.translationResponse.physicalAddress #= secondPc
        dut.io.cacheHitResponsePending #= true
        dut.io.cacheRequestReady #= true
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == secondPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == thirdPc)
        sample(dut)

        // firstPc now returns from the registered slot and reveals a taken branch.  At the same
        // time secondPc reports a hit and thirdPc enters L1I.  The latter two owners are wrong-path
        // state and must both be removed by the registered correction kill.
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.virtualAddress #= thirdPc
        dut.io.translationResponse.physicalAddress #= thirdPc
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= firstPc
        dut.io.cacheResponse.physicalAddress #= firstPc
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == thirdPc)
        sample(dut)
        dut.io.cacheHitResponsePending #= false
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= false
        dut.io.translationResponse.valid #= false
        clearPredecode(dut)

        assert(dut.io.cacheKill.toBoolean)
        assert(dut.io.occupancy.toBigInt == 2)
        sample(dut)
        assert(!dut.io.cacheKill.toBoolean)

        // Even if a stale secondPc response is observed after the kill, its transient pending
        // context has been cleared and no wrong-path instruction may enter the buffer.
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= secondPc
        dut.io.cacheResponse.physicalAddress #= secondPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #=
            (BigInt("00100000", 16) | (5 + lane))
        }
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.fetchPc.toBigInt == branchTarget)
      }
  }

  test("FixBranch drains a translation turned over on the correction edge") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(turnoverConfig))
      .doSim("ooo-frontend-fix-branch-translation-turnover-drain", 0x4c6b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val sequentialPc = base + config.fetchWidth * 4
        val turnedOverPc = sequentialPc + config.fetchWidth * 4
        val branchTarget = base + 4 + 0x40
        acceptFetch(dut, base)

        // Establish the sequential translation owner while the branch-bearing group is in L1I.
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == sequentialPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false

        // Four events overlap: the old L1I response corrects prediction, the sequential
        // translation responds and enters L1I, and turnover allocates one more wrong-path ATU
        // owner.  The correction must retain a drain obligation for that newest owner.
        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= sequentialPc
        dut.io.translationResponse.physicalAddress #= sequentialPc
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == sequentialPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == turnedOverPc)
        sample(dut)
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= false
        dut.io.translationResponse.valid #= false
        dut.io.translationRequest.ready #= false
        clearPredecode(dut)

        // The just-allocated wrong-path owner is not architecturally live, but its ATU response
        // still has to be consumed before corrected fetching can resume.
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= turnedOverPc
        dut.io.translationResponse.physicalAddress #= turnedOverPc
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        sample(dut)
        dut.io.translationResponse.valid #= false

        var retryWait = 0
        while (!dut.io.translationRequest.valid.toBoolean && retryWait < 8) {
          sample(dut)
          retryWait += 1
        }
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchTarget)
        assert(dut.io.occupancy.toBigInt == 2)
      }
  }

  test("FixBranch drains a same-cycle uncached handoff before issuing the corrected group") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-fix-branch-uncached-drain", 0x4c6a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val sequentialPc = base + config.fetchWidth * 4
        val branchTarget = base + 4 + 0x40
        acceptFetch(dut, base)

        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= sequentialPc
        dut.io.translationResponse.physicalAddress #= sequentialPc
        dut.io.translationResponse.uncached #= true
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationResponse.uncached #= false

        // An accepted uncached AXI request cannot be killed. FixBranch therefore allows this
        // handoff, tags its response for draining, and redirects translation to the real target.
        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        sleep(1)
        assert(!dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheUncachedRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == sequentialPc)
        sample(dut)
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= false
        clearPredecode(dut)
        assert(dut.io.fetchPc.toBigInt == branchTarget)
        assert(dut.io.occupancy.toBigInt == 2)

        // The uncached drain obligation is independent of predictor recovery.  As with a cached
        // handoff, hold the corrected lookup until the registered GHR/RAS restore has completed.
        assert(!dut.io.translationRequest.valid.toBoolean)
        sample(dut)

        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= branchTarget
        dut.io.translationResponse.physicalAddress #= branchTarget
        sample(dut)
        dut.io.translationResponse.valid #= false
        sleep(1)
        assert(!dut.io.cacheRequestValid.toBoolean)
        assert(!dut.io.cacheUncachedRequestValid.toBoolean)

        // The stale uncached response and corrected cached request may complete/handoff together,
        // but the stale instructions must never enter the frontend buffer.
        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= sequentialPc
        dut.io.cacheResponse.physicalAddress #= sequentialPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (5 + lane))
        }
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(!dut.io.cacheUncachedRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == branchTarget)
        sample(dut)
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 2)

        returnGroup(dut, branchTarget, firstRd = 9)
        assert(dut.io.occupancy.toBigInt == 5)
      }
  }

  test("FixBranch drops an uncached handoff response arriving during recovery") {
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-ooo-frontend-uncached-recovery"
      )
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-fix-branch-uncached-recovery-response", 0x4c70) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val sequentialPc = base + config.fetchWidth * 4
        val branchTarget = base + 4 + 0x40
        acceptFetch(dut, base)

        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= sequentialPc
        dut.io.translationResponse.physicalAddress #= sequentialPc
        dut.io.translationResponse.uncached #= true
        sample(dut)
        dut.io.translationResponse.valid #= false
        dut.io.translationResponse.uncached #= false

        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = branchTarget,
          staticTaken = true
        )
        sleep(1)
        assert(dut.io.cacheUncachedRequestValid.toBoolean)
        sample(dut)

        // The drain bit is still a correction-local token in this cycle.  A minimum-latency
        // external response must be classified as stale before the persistent drop state exists.
        clearPredecode(dut)
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponse.virtualAddress #= sequentialPc
        dut.io.cacheResponse.physicalAddress #= sequentialPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (5 + lane))
        }
        sleep(1)
        assert(!dut.io.translationRequest.valid.toBoolean)
        sample(dut)
        dut.io.cacheResponseValid #= false

        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.fetchPc.toBigInt == branchTarget)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == branchTarget)
      }
  }

  test("a sequential response hands the prefetched next group to cache without a bubble") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-cache-response-request-overlap", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val nextGroup = base + config.fetchWidth * 4
        acceptFetch(dut, base)

        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == nextGroup)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= nextGroup
        dut.io.translationResponse.physicalAddress #= nextGroup
        sample(dut)
        dut.io.translationResponse.valid #= false

        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (lane + 1))
        }
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == nextGroup)
        assert(dut.io.cacheRequest.physicalAddress.toBigInt == nextGroup)
        sample(dut)
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= false

        assert(dut.io.fetchPc.toBigInt == base + 2 * config.fetchWidth * 4)
        assert(dut.io.occupancy.toBigInt == config.fetchWidth)

        returnGroup(dut, nextGroup, firstRd = 5)
        assert(dut.io.occupancy.toBigInt == 2 * config.fetchWidth)
      }
  }

  test("a pending registered response reserves space before another hit handoff") {
    for ((entries, expectThirdHandoff, seed) <- Seq(
        (8, false, 0x4c6c),
        (16, true, 0x4c6d)
      )) {
      val capacityTurnoverConfig =
        turnoverConfig.copy(instructionBufferEntries = entries)
      SimConfig.withVerilator
        .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
        .compile(new OooFrontend(capacityTurnoverConfig))
        .doSim(s"ooo-frontend-registered-response-capacity-$entries", seed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val firstPc = config.resetVector
        val secondPc = firstPc + config.fetchWidth * 4
        val thirdPc = secondPc + config.fetchWidth * 4
        acceptFetch(dut, firstPc)

        // The first hit token hands the already translated second group to L1I.  Translation
        // turnover simultaneously allocates the third group.
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == secondPc)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= secondPc
        dut.io.translationResponse.physicalAddress #= secondPc
        dut.io.cacheHitResponsePending #= true
        dut.io.cacheRequestReady #= true
        dut.io.translationRequest.ready #= true
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == secondPc)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == thirdPc)
        sample(dut)

        // The registered first response, the second hit, and a proposed third request now overlap.
        // Eight entries can reserve only two groups; sixteen entries can retain all three.
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.virtualAddress #= thirdPc
        dut.io.translationResponse.physicalAddress #= thirdPc
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= firstPc
        dut.io.cacheResponse.physicalAddress #= firstPc
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (lane + 1))
        }
        clearPredecode(dut)
        sleep(1)
        assert(dut.io.translationResponse.ready.toBoolean)
        assert(dut.io.cacheRequestValid.toBoolean == expectThirdHandoff)
        if (expectThirdHandoff) {
          assert(dut.io.cacheRequest.virtualAddress.toBigInt == thirdPc)
        }
      }
    }
  }

  test("a hit handoff preserves the older prediction owner while the active owner advances") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(turnoverConfig))
      .doSim("ooo-frontend-hit-handoff-prediction-owner", 0x4c6e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        // Wait for BTB invalidation, then train a direct prediction for the older group.
        dut.clockDomain.waitSampling(134)
        sleep(1)
        val firstPc = config.resetVector + 0x200
        val predictedTarget = firstPc + 0x40
        dut.io.predictorUpdatePc #= firstPc
        dut.io.predictorUpdateTaken #= true
        dut.io.predictorUpdateTarget #= predictedTarget
        dut.io.predictorUpdateType #= 1
        dut.io.predictorUpdateValid #= true
        sample(dut)
        dut.io.predictorUpdateValid #= false

        dut.io.redirectTarget #= firstPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false
        acceptFetch(dut, firstPc)
        assert(dut.io.predictorDebugTaken.toBoolean)

        // Accept the predicted target and hand it to L1I on the older hit.  This edge advances
        // the active cache owner while reserving the registered response for firstPc.
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == predictedTarget)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= predictedTarget
        dut.io.translationResponse.physicalAddress #= predictedTarget
        dut.io.cacheHitResponsePending #= true
        dut.io.cacheRequestReady #= true
        sleep(1)
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == predictedTarget)
        sample(dut)

        // The older registered response must still observe its trained prediction even though
        // cache* now describes predictedTarget.  staticTaken=false makes stale/new-owner mixing
        // externally visible as predictedTaken=false.
        dut.io.translationResponse.valid #= false
        dut.io.cacheHitResponsePending #= false
        dut.io.cacheRequestReady #= false
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= firstPc
        dut.io.cacheResponse.physicalAddress #= firstPc
        dut.io.cacheResponse.instructions(0) #= encodeDirectBranch(0x14, 0x40)
        for (lane <- 1 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (lane + 1))
        }
        setBranchPredecode(dut, 0, 1, predictedTarget, staticTaken = false)
        sleep(1)
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.decodeValid.toBigInt != 0)
        assert(dut.io.decoded(0).pc.toBigInt == firstPc)
        assert(dut.io.decoded(0).predictedTaken.toBoolean)
        assert(dut.io.decoded(0).predictedTarget.toBigInt == predictedTarget)
      }
  }

  test("response handoff reserves buffer space for both fetch groups") {
    val capacityConfig = config.copy(instructionBufferEntries = 8)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(capacityConfig))
      .doSim("ooo-frontend-cache-response-capacity", 0x4c69) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        acceptFetch(dut, base)
        returnGroup(dut, base, firstRd = 1)
        assert(dut.io.occupancy.toBigInt == config.fetchWidth)

        acceptFetch(dut, base + 16)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == base + 32)
        dut.io.translationRequest.ready #= true
        sample(dut)
        dut.io.translationRequest.ready #= false
        dut.io.translationResponse.valid #= true
        dut.io.translationResponse.virtualAddress #= base + 32
        dut.io.translationResponse.physicalAddress #= base + 32
        sample(dut)
        dut.io.translationResponse.valid #= false

        dut.io.cacheRequestReady #= true
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base + 16
        dut.io.cacheResponse.physicalAddress #= base + 16
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (lane + 5))
        }
        sleep(1)
        assert(!dut.io.cacheRequestValid.toBoolean)
        sample(dut)
        dut.io.cacheResponseValid #= false
        dut.io.cacheRequestReady #= false
        assert(dut.io.occupancy.toBigInt == capacityConfig.instructionBufferEntries)

        dut.io.decodeReady #= 7
        sample(dut)
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.cacheRequestValid.toBoolean)
        assert(dut.io.cacheRequest.virtualAddress.toBigInt == base + 32)
      }
  }

  test("compaction preserves a nonaligned response through its taken lane") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-nonaligned-taken-compaction", 0x4c7e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        val firstTarget = base + 0x18
        val secondTarget = base + 0x100
        val firstBranch = encodeDirectBranch(0x14, 0x14)
        val secondBranch = encodeDirectBranch(0x14, 0xe4)

        acceptFetch(dut, base)
        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= firstBranch
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(dut, 1, 1, firstTarget, staticTaken = true)
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.fetchPc.toBigInt == firstTarget)

        acceptFetch(dut, firstTarget)
        clearPredecode(dut)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= firstTarget
        dut.io.cacheResponse.physicalAddress #= firstTarget
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 9)
        dut.io.cacheResponse.instructions(1) #= (BigInt("00100000", 16) | 10)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 11)
        dut.io.cacheResponse.instructions(3) #= secondBranch
        setBranchPredecode(dut, 3, 1, secondTarget, staticTaken = true)
        sample(dut)
        dut.io.cacheResponseValid #= false

        assert(dut.io.occupancy.toBigInt == 4)
        assert(dut.io.fetchPc.toBigInt == secondTarget)
        assert(dut.io.decodeValid.toBigInt == 7)
        assert(dut.io.decoded(0).pc.toBigInt == base)
        assert(dut.io.decoded(0).instruction.toBigInt == (BigInt("00100000", 16) | 1))
        assert(dut.io.decoded(1).pc.toBigInt == base + 4)
        assert(dut.io.decoded(1).instruction.toBigInt == firstBranch)
        assert(dut.io.decoded(1).predictedTaken.toBoolean)
        assert(dut.io.decoded(2).pc.toBigInt == firstTarget)
        assert(dut.io.decoded(2).instruction.toBigInt == (BigInt("00100000", 16) | 11))

        dut.io.decodeReady #= 7
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 1)
        assert(dut.io.decodeValid.toBigInt == 1)
        assert(dut.io.decoded(0).pc.toBigInt == firstTarget + 4)
        assert(dut.io.decoded(0).instruction.toBigInt == secondBranch)
        assert(dut.io.decoded(0).predictedTaken.toBoolean)
        assert(dut.io.decoded(0).predictedTarget.toBigInt == secondTarget)
      }
  }

  test("static branch prediction truncates the response and redirects fetch") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend")
      .compile(new OooFrontend(config))
      .doSim("ooo-frontend-static-branch-prediction", 0x4c67) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val base = config.resetVector
        acceptFetch(dut, base)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base
        dut.io.cacheResponse.physicalAddress #= base
        dut.io.cacheResponse.instructions(0) #= (BigInt("00100000", 16) | 1)
        dut.io.cacheResponse.instructions(1) #= encodeDirectBranch(0x14, 0x40)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 3)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 4)
        setBranchPredecode(
          dut,
          lane = 1,
          branchType = 1,
          target = base + 4 + 0x40,
          staticTaken = true
        )
        dut.io.cacheResponse.error #= false
        sample(dut)
        dut.io.cacheResponseValid #= false
        sample(dut)

        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.fetchPc.toBigInt == base + 4 + 0x40)
        assert(dut.io.decodeValid.toBigInt == 3)
        assert(dut.io.decoded(0).pc.toBigInt == base)
        assert(!dut.io.decoded(0).predictedTaken.toBoolean)
        assert(dut.io.decoded(1).pc.toBigInt == base + 4)
        assert(dut.io.decoded(1).predictedTaken.toBoolean)
        assert(dut.io.decoded(1).predictedTarget.toBigInt == base + 4 + 0x40)

        dut.io.decodeReady #= 3
        sample(dut)
        dut.io.decodeReady #= 0
        assert(dut.io.occupancy.toBigInt == 0)

        acceptFetch(dut, base + 4 + 0x40)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base + 4 + 0x40
        dut.io.cacheResponse.physicalAddress #= base + 4 + 0x40
        clearPredecode(dut)
        for (lane <- 0 until config.fetchWidth) {
          dut.io.cacheResponse.instructions(lane) #= (BigInt("00100000", 16) | (9 + lane))
        }
        dut.io.cacheResponse.error #= false
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 3)

        // A negative conditional immediate is predicted taken by BTFNT.
        dut.io.decodeReady #= 0
        dut.io.redirectTarget #= base + 0x100
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false
        acceptFetch(dut, base + 0x100)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= base + 0x100
        dut.io.cacheResponse.physicalAddress #= base + 0x100
        clearPredecode(dut)
        dut.io.cacheResponse.instructions(0) #= encodeConditionalBranch(0x16, -4)
        dut.io.cacheResponse.instructions(1) #= (BigInt("00100000", 16) | 21)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 22)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 23)
        setBranchPredecode(
          dut,
          lane = 0,
          branchType = 0,
          target = base + 0x100 - 4,
          staticTaken = true
        )
        dut.io.cacheResponse.error #= false
        sample(dut)
        dut.io.cacheResponseValid #= false
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 1)
        assert(dut.io.fetchPc.toBigInt == base + 0x100 - 4)
        assert(dut.io.decoded(0).predictedTaken.toBoolean)
        assert(dut.io.decoded(0).predictedTarget.toBigInt == base + 0x100 - 4)

        // A precise mispredict update overrides forward-not-taken on the next visit.
        val learnedPc = base + 0x200
        val learnedTarget = learnedPc + 0x10
        // The BRAM-backed predictor clears the 128 BTB rows after reset; PHT payload does not need
        // reset because an explicit trained bit guards every read.
        dut.clockDomain.waitSampling(134)
        sleep(1)
        dut.io.predictorUpdatePc #= learnedPc
        dut.io.predictorUpdateTaken #= true
        dut.io.predictorUpdateTarget #= learnedTarget
        dut.io.predictorUpdateType #= 0
        dut.io.predictorUpdateValid #= true
        var learnedHistory = 0
        for (_ <- 0 until 6) {
          val phtIndex = ((learnedHistory & 0x1f) << 7) | ((learnedPc >> 4) & 0x7f)
          dut.io.predictorUpdateMetadata #=
            (phtIndex | (2 << PredictorMetadataLayout.PhtStateLsb))
          dut.io.predictorRetireValid #= 1
          dut.io.predictorRetireTaken #= 1
          dut.io.predictorRetireType(0) #= 0
          sample(dut)
          learnedHistory = ((learnedHistory << 1) | 1) & 0x1f
        }
        dut.io.predictorUpdateValid #= false
        dut.io.predictorRetireValid #= 0
        dut.io.predictorRetireTaken #= 0

        dut.io.redirectTarget #= learnedPc
        dut.io.redirectValid #= true
        sample(dut)
        dut.io.redirectValid #= false
        acceptFetch(dut, learnedPc)
        assert(dut.io.predictorDebugHit.toBoolean)
        assert(dut.io.predictorDebugType.toBigInt == 0)
        assert((dut.io.predictorDebugPhtState.toBigInt & 2) != 0)
        assert(dut.io.predictorDebugTaken.toBoolean)
        sleep(1)
        assert(dut.io.translationRequest.valid.toBoolean)
        assert(dut.io.translationRequest.virtualAddress.toBigInt == learnedTarget)
        dut.io.cacheResponseValid #= true
        dut.io.cacheResponse.virtualAddress #= learnedPc
        dut.io.cacheResponse.physicalAddress #= learnedPc
        clearPredecode(dut)
        dut.io.cacheResponse.instructions(0) #= encodeConditionalBranch(0x16, 0x10)
        dut.io.cacheResponse.instructions(1) #= (BigInt("00100000", 16) | 25)
        dut.io.cacheResponse.instructions(2) #= (BigInt("00100000", 16) | 26)
        dut.io.cacheResponse.instructions(3) #= (BigInt("00100000", 16) | 27)
        setBranchPredecode(
          dut,
          lane = 0,
          branchType = 0,
          target = learnedTarget,
          staticTaken = false
        )
        dut.io.cacheResponse.error #= false
        sample(dut)
        dut.io.cacheResponseValid #= false
        assert(dut.io.occupancy.toBigInt == 1)
        assert(dut.io.fetchPc.toBigInt == learnedTarget)
        assert(dut.io.decoded(0).predictedTaken.toBoolean)
        assert(dut.io.decoded(0).predictedTarget.toBigInt == learnedTarget)
      }
  }
}
