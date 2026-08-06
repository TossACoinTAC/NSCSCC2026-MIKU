package miku.core

import miku.memory._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable
import scala.language.reflectiveCalls

class OooCoreIntegrationSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: OooCore): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def addiW(rd: Int, immediate: Int): BigInt =
    BigInt("02800000", 16) | (BigInt(immediate & 0xfff) << 10) | rd

  private def addiW(rd: Int, rj: Int, immediate: Int): BigInt =
    BigInt("02800000", 16) | (BigInt(immediate & 0xfff) << 10) |
      (BigInt(rj) << 5) | rd

  private def branchToSelf: BigInt = BigInt("50000000", 16)

  private def clearInputs(dut: OooCore): Unit = {
    dut.io.instructionTranslationRequest.ready #= false
    dut.io.instructionTranslationResponse.valid #= false
    dut.io.instructionTranslationResponse.virtualAddress #= 0
    dut.io.instructionTranslationResponse.physicalAddress #= 0
    dut.io.instructionTranslationResponse.uncached #= false
    dut.io.instructionTranslationResponse.cancelled #= false
    dut.io.instructionTranslationResponse.exception.valid #= false
    dut.io.instructionTranslationResponse.exception.ecode #= 0
    dut.io.instructionTranslationResponse.exception.esubcode #= 0
    dut.io.instructionTranslationResponse.exception.badVAddrValid #= false
    dut.io.instructionTranslationResponse.exception.badVAddr #= 0
    dut.io.instructionTranslationResponse.exception.tlbRefill #= false
    dut.io.dataTranslationRequest.ready #= true
    dut.io.dataTranslationResponse.valid #= false
    dut.io.dataTranslationResponse.virtualAddress #= 0
    dut.io.dataTranslationResponse.physicalAddress #= 0
    dut.io.dataTranslationResponse.uncached #= false
    dut.io.dataTranslationResponse.cancelled #= false
    dut.io.dataTranslationResponse.exception.valid #= false
    dut.io.dataTranslationResponse.exception.ecode #= 0
    dut.io.dataTranslationResponse.exception.esubcode #= 0
    dut.io.dataTranslationResponse.exception.badVAddrValid #= false
    dut.io.dataTranslationResponse.exception.badVAddr #= 0
    dut.io.dataTranslationResponse.exception.tlbRefill #= false
    dut.io.dataTranslationBypass.eligible #= false
    dut.io.dataTranslationBypass.physicalAddress #= 0
    dut.io.dataTranslationBypass.uncached #= false
    dut.io.reservationValid #= false
    dut.io.reservationLineAddress #= 0
    dut.io.uncachedInstructionRequestReady #= false
    dut.io.uncachedInstructionResponseValid #= false
    dut.io.uncachedInstructionResponse.virtualAddress #= 0
    dut.io.uncachedInstructionResponse.physicalAddress #= 0
    dut.io.uncachedInstructionResponse.error #= false
    for (lane <- 0 until config.fetchWidth) {
      dut.io.uncachedInstructionResponse.instructions(lane) #= 0
      dut.io.uncachedInstructionResponse.predecode(lane).valid #= false
      dut.io.uncachedInstructionResponse.predecode(lane).branchType #= 1
      dut.io.uncachedInstructionResponse.predecode(lane).target #= 0
      dut.io.uncachedInstructionResponse.predecode(lane).staticTaken #= false
      dut.io.uncachedInstructionResponse.predecode(lane).indirect #= false
    }
    dut.io.uncachedDataRequestReady #= false
    dut.io.uncachedDataResponseValid #= false
    dut.io.uncachedDataResponse.robPointer #= 0
    dut.io.uncachedDataResponse.recoveryEpoch #= 0
    dut.io.uncachedDataResponse.pdst #= 0
    dut.io.uncachedDataResponse.loadQueueIndex #= 0
    dut.io.uncachedDataResponse.data #= 0
    dut.io.uncachedDataResponse.error #= false
    dut.io.memoryReadReady #= false
    dut.io.memoryReadBeatValid #= false
    dut.io.memoryReadBeat.mshrId #= 0
    dut.io.memoryReadBeat.beat #= 0
    dut.io.memoryReadBeat.data #= 0
    dut.io.memoryReadBeat.last #= false
    dut.io.memoryReadBeat.error #= false
    dut.io.memoryWriteReady #= true
    dut.io.memoryBusIdle #= true
    dut.io.systemReadData #= 0
    dut.io.timer #= 0
    dut.io.timerId #= 0
    dut.io.debugReadAddress #= 0
    dut.io.privilege #= 0
    dut.io.interruptPending #= false
    dut.io.exceptionEntryTarget #= BigInt("1c001000", 16)
    dut.io.tlbRefillTarget #= BigInt("1c002000", 16)
    dut.io.externalRedirectValid #= false
    dut.io.externalRedirectTarget #= 0
    dut.io.cacheInvalidate #= false
    dut.io.dataCacheInvalidate #= false
    dut.io.dataCacheWritebackInvalidate #= false
    dut.io.level2CacheInvalidate #= false
  }

  private def refillInstructionLine(
      dut: OooCore,
      expectedAddress: BigInt,
      instructions: IndexedSeq[BigInt],
      waitForInitialization: Boolean
  ): Unit = {
    if (waitForInitialization) {
      var initializationCycles = 0
      while (
        dut.io.cacheInvalidateBusy.toBoolean && initializationCycles <
          config.level2Cache.sets + 32
      ) {
        sample(dut)
        initializationCycles += 1
      }
      assert(!dut.io.cacheInvalidateBusy.toBoolean)
    }

    var waitCycles = 0
    while (!dut.io.memoryReadValid.toBoolean && waitCycles < 80) {
      sample(dut)
      waitCycles += 1
    }
    assert(dut.io.memoryReadValid.toBoolean)
    assert(dut.io.memoryRead.lineAddress.toBigInt == expectedAddress)
    assert(dut.io.memoryRead.mshrId.toBigInt == 0)

    dut.io.memoryReadReady #= true
    sample(dut)
    dut.io.memoryReadReady #= false

    for (beat <- 0 until OooCacheContract.BeatsPerLine) {
      val data = instructions(beat * 2) | (instructions(beat * 2 + 1) << 32)
      dut.io.memoryReadBeatValid #= true
      dut.io.memoryReadBeat.mshrId #= 0
      dut.io.memoryReadBeat.beat #= beat
      dut.io.memoryReadBeat.data #= data
      dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
      dut.io.memoryReadBeat.error #= false
      sleep(1)
      assert(dut.io.memoryReadBeatReady.toBoolean)
      sample(dut)
    }
    dut.io.memoryReadBeatValid #= false
  }

  test("self-fetching core retires multi-wide and predicts a direct self branch") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-core-integration")
      .compile(new OooCore(config))
      .doSim("ooo-core-fetch-execute-commit", 0x4c63) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        fork {
          while (true) {
            while (!dut.io.instructionTranslationRequest.valid.toBoolean) {
              sample(dut)
            }
            val address = dut.io.instructionTranslationRequest.virtualAddress.toBigInt
            dut.io.instructionTranslationRequest.ready #= true
            sample(dut)
            dut.io.instructionTranslationRequest.ready #= false
            dut.io.instructionTranslationResponse.virtualAddress #= address
            dut.io.instructionTranslationResponse.physicalAddress #= address
            dut.io.instructionTranslationResponse.valid #= true
            while (!dut.io.instructionTranslationResponse.ready.toBoolean) {
              sample(dut)
            }
            sample(dut)
            dut.io.instructionTranslationResponse.valid #= false
          }
        }

        val instructions = IndexedSeq.tabulate(16) { index =>
          if (index < 12) addiW(index + 1, index + 1) else branchToSelf
        }
        refillInstructionLine(
          dut,
          config.resetVector,
          instructions,
          waitForInitialization = true
        )

        val committed = mutable.LinkedHashMap.empty[BigInt, BigInt]
        var maximumCommitWidth = 0
        var cycles = 0
        while (committed.size < 12 && cycles < 160) {
          sample(dut)
          val mask = dut.io.commitValid.toBigInt
          maximumCommitWidth = maximumCommitWidth.max(mask.bitCount)
          for (lane <- 0 until config.commitWidth if (mask & (BigInt(1) << lane)) != 0) {
            val pc = dut.io.commit(lane).pc.toBigInt
            val index = ((pc - config.resetVector) / 4).toInt
            if (index >= 0 && index < 12) {
              assert(dut.io.commit(lane).instruction.toBigInt == instructions(index))
              assert(dut.io.commit(lane).result.toBigInt == index + 1)
              committed(pc) = dut.io.commit(lane).result.toBigInt
            }
          }
          if (dut.io.recoveryValid.toBoolean) {
            fail(s"direct branch prediction unexpectedly recovered at cycle $cycles")
          }
          cycles += 1
        }

        assert(committed.keys.toSeq == (0 until 12).map(config.resetVector + _ * 4))
        assert(committed.values.toSeq == (1 to 12).map(BigInt(_)))
        assert(maximumCommitWidth >= 2)

        val branchPc = config.resetVector + 12 * 4
        var branchCommits = 0
        var branchWait = 0
        while (branchCommits < 2 && branchWait < 80) {
          sample(dut)
          assert(!dut.io.recoveryValid.toBoolean)
          val mask = dut.io.commitValid.toBigInt
          for (lane <- 0 until config.commitWidth if (mask & (BigInt(1) << lane)) != 0) {
            if (dut.io.commit(lane).pc.toBigInt == branchPc) {
              assert(dut.io.commit(lane).instruction.toBigInt == branchToSelf)
              branchCommits += 1
            }
          }
          branchWait += 1
        }
        assert(branchCommits >= 2)
      }
  }

  test("CPUCFG consumes a just-produced index and retires its configured value") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-core-cpucfg")
      .compile(new OooCore(config))
      .doSim("ooo-core-cpucfg-dependency", 0x4351) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.systemReadData #= BigInt("0000001d", 16)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        fork {
          while (true) {
            while (!dut.io.instructionTranslationRequest.valid.toBoolean) {
              sample(dut)
            }
            val address = dut.io.instructionTranslationRequest.virtualAddress.toBigInt
            dut.io.instructionTranslationRequest.ready #= true
            sample(dut)
            dut.io.instructionTranslationRequest.ready #= false
            dut.io.instructionTranslationResponse.virtualAddress #= address
            dut.io.instructionTranslationResponse.physicalAddress #= address
            dut.io.instructionTranslationResponse.valid #= true
            while (!dut.io.instructionTranslationResponse.ready.toBoolean) {
              sample(dut)
            }
            sample(dut)
            dut.io.instructionTranslationResponse.valid #= false
          }
        }

        val program = IndexedSeq(
          BigInt("0380400c", 16),
          BigInt("00006d91", 16),
          addiW(rd = 18, rj = 17, immediate = 1)
        )
        val instructions = program ++ IndexedSeq.fill(13)(branchToSelf)
        refillInstructionLine(
          dut,
          config.resetVector,
          instructions,
          waitForInitialization = true
        )

        val committed = mutable.LinkedHashMap.empty[BigInt, BigInt]
        var sawCpuCfgRead = false
        var cycles = 0
        while (committed.size < program.size && cycles < 160) {
          sleep(1)
          if (dut.io.systemReadValid.toBoolean) {
            sawCpuCfgRead = true
            assert(dut.io.systemReadAddress.toBigInt == 0xc0)
          }
          val mask = dut.io.commitValid.toBigInt
          for (lane <- 0 until config.commitWidth if (mask & (BigInt(1) << lane)) != 0) {
            val pc = dut.io.commit(lane).pc.toBigInt
            val index = ((pc - config.resetVector) / 4).toInt
            if (index >= 0 && index < program.size) {
              assert(dut.io.commit(lane).instruction.toBigInt == program(index))
              committed(pc) = dut.io.commit(lane).result.toBigInt
            }
          }
          dut.clockDomain.waitSampling()
          cycles += 1
        }

        assert(sawCpuCfgRead)
        assert(committed.keys.toSeq == (0 until program.size).map(config.resetVector + _ * 4))
        assert(committed.values.toSeq == Seq(BigInt(16), BigInt(0x1d), BigInt(0x1e)))
      }
  }

  test("registered TLB refill recovery redirects to the refill entry") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-core-tlb-refill")
      .compile(new OooCore(config))
      .doSim("ooo-core-tlb-refill-target", 0x4c64) { dut =>
        val exceptionEntry = BigInt("1c008000", 16)
        val tlbRefillEntry = BigInt("1c00f000", 16)

        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.exceptionEntryTarget #= exceptionEntry
        dut.io.tlbRefillTarget #= tlbRefillEntry
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        var requestWait = 0
        while (!dut.io.instructionTranslationRequest.valid.toBoolean && requestWait < 40) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.instructionTranslationRequest.valid.toBoolean)
        val faultPc = dut.io.instructionTranslationRequest.virtualAddress.toBigInt
        assert(faultPc == config.resetVector)

        dut.io.instructionTranslationRequest.ready #= true
        sample(dut)
        dut.io.instructionTranslationRequest.ready #= false

        dut.io.instructionTranslationResponse.virtualAddress #= faultPc
        dut.io.instructionTranslationResponse.physicalAddress #= 0
        dut.io.instructionTranslationResponse.exception.valid #= true
        dut.io.instructionTranslationResponse.exception.ecode #= 0x3f
        dut.io.instructionTranslationResponse.exception.esubcode #= 0
        dut.io.instructionTranslationResponse.exception.badVAddrValid #= true
        dut.io.instructionTranslationResponse.exception.badVAddr #= faultPc
        dut.io.instructionTranslationResponse.exception.tlbRefill #= true
        dut.io.instructionTranslationResponse.valid #= true
        var responseWait = 0
        while (!dut.io.instructionTranslationResponse.ready.toBoolean && responseWait < 20) {
          sample(dut)
          responseWait += 1
        }
        assert(dut.io.instructionTranslationResponse.ready.toBoolean)
        sample(dut)
        dut.io.instructionTranslationResponse.valid #= false
        dut.io.instructionTranslationResponse.exception.valid #= false

        var recoveryWait = 0
        while (!dut.io.recoveryValid.toBoolean && recoveryWait < 80) {
          sample(dut)
          recoveryWait += 1
        }
        assert(dut.io.recoveryValid.toBoolean)
        assert(dut.io.recovery.cause.toBigInt == 2)
        assert(dut.io.recovery.exception.tlbRefill.toBoolean)

        // Recovery is captured on the retirement pulse and applied one cycle later.
        sample(dut)
        var redirectRequestWait = 0
        while (
          !dut.io.instructionTranslationRequest.valid.toBoolean &&
          redirectRequestWait < 40
        ) {
          sample(dut)
          redirectRequestWait += 1
        }
        assert(dut.io.instructionTranslationRequest.valid.toBoolean)
        assert(dut.io.instructionTranslationRequest.virtualAddress.toBigInt == tlbRefillEntry)
        assert(dut.io.instructionTranslationRequest.virtualAddress.toBigInt != exceptionEntry)
      }
  }

  test("a level timer interrupt enters the ROB from a predicted self loop") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-core-interrupt")
      .compile(new OooCore(config))
      .doSim("ooo-core-timer-interrupt", 0x49) { dut =>
        val exceptionEntry = BigInt("1c008000", 16)
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.exceptionEntryTarget #= exceptionEntry
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        fork {
          while (true) {
            while (!dut.io.instructionTranslationRequest.valid.toBoolean) {
              sample(dut)
            }
            val address = dut.io.instructionTranslationRequest.virtualAddress.toBigInt
            dut.io.instructionTranslationRequest.ready #= true
            sample(dut)
            dut.io.instructionTranslationRequest.ready #= false
            dut.io.instructionTranslationResponse.virtualAddress #= address
            dut.io.instructionTranslationResponse.physicalAddress #= address
            dut.io.instructionTranslationResponse.valid #= true
            while (!dut.io.instructionTranslationResponse.ready.toBoolean) {
              sample(dut)
            }
            sample(dut)
            dut.io.instructionTranslationResponse.valid #= false
          }
        }

        val instructions = IndexedSeq.fill(16)(branchToSelf)
        refillInstructionLine(
          dut,
          config.resetVector,
          instructions,
          waitForInitialization = true
        )

        var branchCommitted = false
        var warmupCycles = 0
        while (!branchCommitted && warmupCycles < 120) {
          sample(dut)
          for (
            lane <- 0 until config.commitWidth
            if (dut.io.commitValid.toBigInt & (BigInt(1) << lane)) != 0
          ) {
            branchCommitted ||= dut.io.commit(lane).pc.toBigInt == config.resetVector
          }
          warmupCycles += 1
        }
        assert(branchCommitted)

        dut.io.interruptPending #= true
        var recoveryCycles = 0
        while (!dut.io.recoveryValid.toBoolean && recoveryCycles < 120) {
          sample(dut)
          recoveryCycles += 1
        }
        assert(dut.io.recoveryValid.toBoolean)
        assert(dut.io.recovery.cause.toBigInt == 2)
        assert(dut.io.recovery.exception.valid.toBoolean)
        assert(dut.io.recovery.exception.ecode.toBigInt == 0)
        assert(dut.io.exceptionValid.toBoolean)
        assert(dut.io.exception.ecode.toBigInt == 0)
        assert(dut.io.exceptionPc.toBigInt == config.resetVector)

        dut.io.interruptPending #= false
        sample(dut)
        var redirectCycles = 0
        while (
          !dut.io.instructionTranslationRequest.valid.toBoolean && redirectCycles < 40
        ) {
          sample(dut)
          redirectCycles += 1
        }
        assert(dut.io.instructionTranslationRequest.valid.toBoolean)
        assert(dut.io.instructionTranslationRequest.virtualAddress.toBigInt == exceptionEntry)
      }
  }
}
