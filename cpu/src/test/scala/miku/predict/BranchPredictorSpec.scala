package miku.predict

import java.nio.file.Paths
import miku.config.CoreConfig
import miku.core.OooCoreConfig
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class BranchPredictorSpec extends AnyFunSuite {
  private val WordMask = (BigInt(1) << 32) - 1

  test("large gshare initializes in the background without update backpressure") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(enableLargeGshare = true)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-banked-predictor")
      .compile(new BankedFetchPredictor(config))
      .doSim("ooo-banked-predictor-background-init", 0x47534852) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.lookupValid #= false
        dut.io.lookupPc #= 0
        dut.io.btbUpdateValid #= false
        dut.io.btbUpdatePc #= 0
        dut.io.btbUpdateTarget #= 0
        dut.io.btbUpdateType #= 0
        dut.io.btbUpdateDirectionTrained #= false
        dut.io.phtUpdateValid #= false
        dut.io.phtUpdatePc #= 0
        dut.io.phtUpdateIndex #= 0
        dut.io.phtUpdateOldState #= 0
        dut.io.phtUpdateOldValid #= false
        dut.io.phtUpdateTaken #= false
        dut.io.speculativeHistoryValid #= false
        dut.io.speculativeHistoryTaken #= false
        dut.io.speculativeRasPush #= false
        dut.io.speculativeRasPop #= false
        dut.io.speculativeReturnAddress #= 0
        dut.io.commitRasPush #= false
        dut.io.commitRasPop #= false
        dut.io.commitReturnAddress #= 0
        dut.io.architecturalHistoryValid #= 0
        dut.io.architecturalHistoryTaken #= 0
        dut.io.architecturalRasPush #= 0
        dut.io.architecturalRasPop #= 0
        for (lane <- 0 until config.commitWidth) {
          dut.io.architecturalReturnAddress(lane) #= 0
        }
        dut.io.flush #= false

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(132)
        assert(dut.io.tableUpdateReady.toBoolean)

        val branchPc = BigInt("1c001000", 16)
        val forwardTarget = branchPc + 0x100
        val pcIndex = ((branchPc >> 4) & 0xfff).toInt
        val historyZeroIndex = pcIndex
        val historyOneIndex = pcIndex ^ 1

        dut.io.btbUpdateValid #= true
        dut.io.btbUpdatePc #= branchPc
        dut.io.btbUpdateTarget #= forwardTarget
        dut.io.btbUpdateType #= 0
        dut.io.btbUpdateDirectionTrained #= true
        dut.io.phtUpdateValid #= true
        dut.io.phtUpdatePc #= branchPc
        dut.io.phtUpdateIndex #= historyZeroIndex
        dut.io.phtUpdateOldState #= 1
        dut.io.phtUpdateOldValid #= false
        dut.io.phtUpdateTaken #= true
        dut.clockDomain.waitSampling()
        dut.io.btbUpdateValid #= false
        dut.io.phtUpdateValid #= false

        def lookup(): (Boolean, Int, Int) = {
          dut.io.lookupPc #= branchPc
          dut.io.lookupValid #= true
          dut.clockDomain.waitSampling()
          dut.io.lookupValid #= false
          sleep(1)
          assert(dut.io.responseValid.toBoolean)
          assert(dut.io.prediction(0).hit.toBoolean)
          (
            dut.io.prediction(0).phtValid.toBoolean,
            dut.io.prediction(0).phtState.toInt,
            dut.io.prediction(0).phtIndex.toInt
          )
        }

        // The BTB update is accepted while the PHT sweep continues.  Its simultaneous PHT update
        // is deliberately discarded, fetch uses BTFNT, and update ready remains asserted.
        assert(lookup() == ((false, 1, historyZeroIndex)))
        for (_ <- 0 until 12) {
          assert(dut.io.tableUpdateReady.toBoolean)
          dut.clockDomain.waitSampling()
        }

        dut.clockDomain.waitSampling(4096)
        assert(dut.io.tableUpdateReady.toBoolean)
        assert(lookup() == ((true, 1, historyZeroIndex)))

        dut.io.phtUpdateValid #= true
        dut.io.phtUpdatePc #= branchPc
        dut.io.phtUpdateIndex #= historyZeroIndex
        dut.io.phtUpdateOldState #= 1
        dut.io.phtUpdateOldValid #= true
        dut.io.phtUpdateTaken #= true
        dut.clockDomain.waitSampling()
        dut.io.phtUpdateValid #= false
        assert(lookup() == ((true, 2, historyZeroIndex)))

        dut.io.speculativeHistoryValid #= true
        dut.io.speculativeHistoryTaken #= true
        dut.clockDomain.waitSampling()
        dut.io.speculativeHistoryValid #= false
        assert(lookup() == ((true, 1, historyOneIndex)))

        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.flush #= false
        assert(lookup() == ((true, 2, historyZeroIndex)))
      }
  }

  test(
    "official 32-entry BTB, saturating counters and return prediction obey the active contract"
  ) {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-contracts")
    val workspace = Paths.get(workspaceRoot, "predictor").toString

    SimConfig
      .withConfig(SpinalConfig(oneFilePerComponent = true))
      .withVerilator
      .addSimulatorFlag("-Wall")
      .addSimulatorFlag("-Wwarn-WIDTH")
      .addSimulatorFlag("-Wwarn-UNOPTFLAT")
      .addSimulatorFlag("-Wwarn-CMPCONST")
      .addSimulatorFlag("-Wwarn-UNSIGNED")
      .disableCache
      .workspacePath(workspace)
      .compile(new BranchPredictor(CoreConfig.Locked))
      .doSim("predictor-directed", 0x158aa8) { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        def clearInputs(): Unit = {
          dut.io.lookup.valid #= false
          dut.io.lookup.payload.pc #= 0
          dut.io.update.valid #= false
          dut.io.update.payload.popReturnStack #= false
          dut.io.update.payload.pushReturnStack #= false
          dut.io.update.payload.addEntry #= false
          dut.io.update.payload.predictionError #= false
          dut.io.update.payload.predictionRight #= false
          dut.io.update.payload.targetError #= false
          dut.io.update.payload.actualTaken #= false
          dut.io.update.payload.actualTarget #= 0
          dut.io.update.payload.pc #= 0
          dut.io.update.payload.legacyIndex #= 0
        }

        def resetState(): Unit = {
          clearInputs()
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        def update(
            pc: BigInt,
            target: BigInt = 0,
            pop: Boolean = false,
            push: Boolean = false,
            add: Boolean = false,
            error: Boolean = false,
            right: Boolean = false,
            targetError: Boolean = false,
            actualTaken: Boolean = false,
            legacyIndex: Int = 0
        ): Unit = {
          dut.io.update.payload.pc #= pc & WordMask
          dut.io.update.payload.actualTarget #= target & WordMask
          dut.io.update.payload.popReturnStack #= pop
          dut.io.update.payload.pushReturnStack #= push
          dut.io.update.payload.addEntry #= add
          dut.io.update.payload.predictionError #= error
          dut.io.update.payload.predictionRight #= right
          dut.io.update.payload.targetError #= targetError
          dut.io.update.payload.actualTaken #= actualTaken
          dut.io.update.payload.legacyIndex #= legacyIndex
          dut.io.update.valid #= true
          dut.clockDomain.waitSampling()
          dut.io.update.valid #= false
          clearInputs()
          sleep(1)
        }

        final case class Prediction(valid: Boolean, taken: Boolean, target: BigInt, index: Int)

        def lookup(pc: BigInt): Prediction = {
          dut.io.lookup.payload.pc #= pc & WordMask
          dut.io.lookup.valid #= true
          dut.clockDomain.waitSampling()
          dut.io.lookup.valid #= false
          sleep(1)
          val result = Prediction(
            dut.io.prediction.valid.toBoolean,
            dut.io.prediction.payload.taken.toBoolean,
            dut.io.prediction.payload.target.toBigInt,
            dut.io.prediction.payload.legacyIndex.toInt
          )
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(!dut.io.prediction.valid.toBoolean, "a lookup response lasted more than one cycle")
          assert(!dut.io.prediction.payload.taken.toBoolean)
          assert(dut.io.prediction.payload.target.toBigInt == 0)
          assert(dut.io.prediction.payload.legacyIndex.toInt == 0)
          result
        }

        def expectHit(
            pc: BigInt,
            target: BigInt,
            taken: Boolean = true,
            index: Option[Int] = None
        ): Unit = {
          val result = lookup(pc)
          assert(result.valid, f"expected hit for PC 0x$pc%08x")
          assert(result.taken == taken, f"wrong direction for PC 0x$pc%08x")
          assert(result.target == (target & WordMask), f"wrong target for PC 0x$pc%08x")
          index.foreach(expected => assert(result.index == expected))
        }

        def expectMiss(pc: BigInt): Unit = {
          val result = lookup(pc)
          assert(!result.valid, f"unexpected hit for PC 0x$pc%08x")
          assert(!result.taken)
          assert(result.target == 0)
          assert(result.index == 0)
        }

        resetState()
        expectMiss(BigInt("1c000000", 16))

        val counterPc = BigInt("1c001000", 16)
        val counterTarget = BigInt("1c002000", 16)
        update(counterPc, counterTarget, add = true, actualTaken = true)
        expectHit(counterPc, counterTarget, index = Some(0))
        update(counterPc, right = true, actualTaken = false)
        expectHit(counterPc, counterTarget, taken = false)
        update(counterPc, error = true, actualTaken = false)
        expectHit(counterPc, counterTarget, taken = false)
        update(counterPc, error = true, actualTaken = false)
        expectHit(counterPc, counterTarget, taken = false)
        update(counterPc, error = true, actualTaken = true)
        expectHit(counterPc, counterTarget, taken = false)
        update(counterPc, error = true, actualTaken = true)
        expectHit(counterPc, counterTarget)
        update(counterPc, right = true, actualTaken = true)
        update(counterPc, right = true, actualTaken = true)
        expectHit(counterPc, counterTarget)

        val correctedTarget = BigInt("1c003000", 16)
        update(BigInt("1c101000", 16), correctedTarget, targetError = true, legacyIndex = 0)
        expectHit(counterPc, correctedTarget)

        val fillBase = BigInt("1d000000", 16)
        val targetBase = BigInt("1e000000", 16)
        for (entry <- 0 until 31) {
          update(fillBase + entry * 4, targetBase + entry * 4, add = true, actualTaken = true)
        }
        expectHit(fillBase, targetBase, index = Some(1))
        expectHit(fillBase + 15 * 4, targetBase + 15 * 4, index = Some(16))
        expectHit(fillBase + 30 * 4, targetBase + 30 * 4, index = Some(31))

        val weakPc = fillBase + 11 * 4
        update(weakPc, error = true, actualTaken = false, legacyIndex = 12)
        update(weakPc, error = true, actualTaken = false, legacyIndex = 12)
        val replacementPc = BigInt("1d100000", 16)
        val replacementTarget = BigInt("1e100000", 16)
        update(replacementPc, replacementTarget, add = true, actualTaken = true)
        expectMiss(weakPc)
        expectHit(replacementPc, replacementTarget)
        expectHit(fillBase + 10 * 4, targetBase + 10 * 4)

        val randomReplacementPc = BigInt("1d200000", 16)
        val randomReplacementTarget = BigInt("1e200000", 16)
        update(randomReplacementPc, randomReplacementTarget, add = true, actualTaken = true)
        expectHit(randomReplacementPc, randomReplacementTarget)
        val oldMisses = (0 until 31).count { entry =>
          !lookup(fillBase + entry * 4).valid
        }
        val replacementWasEvicted = !lookup(replacementPc).valid
        val originalMisses = oldMisses + (if (!lookup(counterPc).valid) 1 else 0)
        assert(
          originalMisses + (if (replacementWasEvicted) 1 else 0) == 2,
          "the strongly-untaken replacement plus one LFSR replacement must be observable"
        )

        val returnPc = BigInt("1c004000", 16)
        val call0 = BigInt("1c005000", 16)
        update(returnPc, pop = true, add = true)
        expectMiss(returnPc)
        update(call0, push = true)
        expectHit(returnPc, call0 + 4)

        val call1 = BigInt("1c006000", 16)
        update(call1, push = true)
        expectHit(returnPc, call1 + 4)
        update(returnPc, pop = true)
        expectHit(returnPc, call0 + 4)
        update(returnPc, pop = true)
        expectMiss(returnPc)
        update(returnPc, pop = true)
        expectMiss(returnPc)

        for (depth <- 0 until 8) update(BigInt("1c010000", 16) + depth * 4, push = true)
        expectHit(returnPc, BigInt("1c010000", 16) + 7 * 4 + 4)
        update(BigInt("1c020000", 16), push = true)
        expectHit(returnPc, BigInt("1c010000", 16) + 7 * 4 + 4)
        update(BigInt("1c030000", 16), pop = true, push = true)
        expectHit(returnPc, BigInt("1c010000", 16) + 6 * 4 + 4)
        update(BigInt("1c040000", 16), pop = true, push = true)
        expectHit(returnPc, BigInt("1c040000", 16) + 4)

        for (_ <- 0 until 8) update(returnPc, pop = true)
        expectMiss(returnPc)
        val matcherBase = BigInt("1c100000", 16)
        for (entry <- 0 until 15) update(matcherBase + entry * 4, pop = true, add = true)
        val matcherReplacement = BigInt("1c110000", 16)
        update(matcherReplacement, pop = true, add = true)
        update(call0, push = true)
        expectHit(matcherReplacement, call0 + 4)
        val matcherMisses =
          (0 until 15).count(entry => !lookup(matcherBase + entry * 4).valid) +
            (if (!lookup(returnPc).valid) 1 else 0)
        assert(matcherMisses == 1, s"expected one replaced return-site matcher, got $matcherMisses")

        val collisionPc = BigInt("1c200000", 16)
        val branchTarget = BigInt("1c210000", 16)
        val returnTargetSource = BigInt("1c220000", 16)
        update(collisionPc, branchTarget, add = true, actualTaken = true)
        update(collisionPc, pop = true, add = true)
        update(returnTargetSource, push = true)
        expectHit(collisionPc, returnTargetSource + 4)
      }
  }

  test("banked predictor preserves an ordered retirement batch across same-cycle flush") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableLargeGshare = true,
      largeGshareHistoryWidth = 16
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-banked-predictor")
      .compile(new BankedFetchPredictor(config))
      .doSim("ooo-banked-predictor-commit-flush", 0x52415346) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.lookupValid #= false
        dut.io.lookupPc #= 0
        dut.io.btbUpdateValid #= false
        dut.io.btbUpdatePc #= 0
        dut.io.btbUpdateTarget #= 0
        dut.io.btbUpdateType #= 1
        dut.io.btbUpdateDirectionTrained #= false
        dut.io.phtUpdateValid #= false
        dut.io.phtUpdatePc #= 0
        dut.io.phtUpdateIndex #= 0
        dut.io.phtUpdateOldState #= 0
        dut.io.phtUpdateOldValid #= false
        dut.io.phtUpdateTaken #= false
        dut.io.speculativeHistoryValid #= false
        dut.io.speculativeHistoryTaken #= false
        dut.io.speculativeRasPush #= false
        dut.io.speculativeRasPop #= false
        dut.io.speculativeReturnAddress #= 0
        dut.io.commitRasPush #= false
        dut.io.commitRasPop #= false
        dut.io.commitReturnAddress #= 0
        dut.io.architecturalHistoryValid #= 0
        dut.io.architecturalHistoryTaken #= 0
        dut.io.architecturalRasPush #= 0
        dut.io.architecturalRasPop #= 0
        for (lane <- 0 until config.commitWidth) {
          dut.io.architecturalReturnAddress(lane) #= 0
        }
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(132)

        val returnPc = BigInt("1c004000", 16)
        val fallbackTarget = BigInt("1c008000", 16)
        val olderReturnAddress = BigInt("1c00a004", 16)
        val recoveryReturnAddress = BigInt("1c00c004", 16)
        dut.io.btbUpdatePc #= returnPc
        dut.io.btbUpdateTarget #= fallbackTarget
        dut.io.btbUpdateType #= 3
        dut.io.btbUpdateValid #= true
        dut.clockDomain.waitSampling()
        dut.io.btbUpdateValid #= false
        for (bank <- 1 until config.fetchWidth) {
          dut.io.btbUpdatePc #= returnPc + bank * 4
          dut.io.btbUpdateTarget #= fallbackTarget + bank * 4
          dut.io.btbUpdateType #= 3
          dut.io.btbUpdateValid #= true
          dut.clockDomain.waitSampling()
          dut.io.btbUpdateValid #= false
        }

        dut.io.architecturalRasPush #= 3
        dut.io.architecturalReturnAddress(0) #= olderReturnAddress
        dut.io.architecturalReturnAddress(1) #= recoveryReturnAddress
        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.architecturalRasPush #= 0
        dut.io.flush #= false

        dut.io.lookupPc #= returnPc
        dut.io.lookupValid #= true
        dut.clockDomain.waitSampling()
        dut.io.lookupValid #= false
        sleep(1)
        assert(dut.io.responseValid.toBoolean)
        for (bank <- 0 until config.fetchWidth) {
          assert(dut.io.prediction(bank).hit.toBoolean)
          assert(dut.io.prediction(bank).target.toBigInt == recoveryReturnAddress)
        }
        dut.io.lookupPc #= returnPc + 0x1000
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.responseValid.toBoolean)

        // Three conditional branches retire oldest-to-youngest with outcomes T,N,T. The flush
        // must restore speculative history to binary 101 in the same cycle as the batch update.
        dut.io.architecturalHistoryValid #= 7
        dut.io.architecturalHistoryTaken #= 5
        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.architecturalHistoryValid #= 0
        dut.io.architecturalHistoryTaken #= 0
        dut.io.flush #= false
        dut.io.lookupPc #= returnPc
        dut.io.lookupValid #= true
        dut.clockDomain.waitSampling()
        dut.io.lookupValid #= false
        sleep(1)
        val pcIndex = ((returnPc >> 4) & 0xfff).toInt
        assert(dut.io.prediction(0).phtIndex.toInt == (5 ^ pcIndex))

        // Shift the committed 0b101 into the upper GHR nibble.  A later flush must restore all
        // 16 bits, and the folded upper nibble must participate in the next table address.
        dut.io.architecturalHistoryValid #= 1
        dut.io.architecturalHistoryTaken #= 0
        for (_ <- 0 until 13) {
          dut.clockDomain.waitSampling()
        }
        dut.io.architecturalHistoryValid #= 0
        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.flush #= false
        dut.io.lookupValid #= true
        dut.clockDomain.waitSampling()
        dut.io.lookupValid #= false
        sleep(1)
        assert(dut.io.prediction(0).phtIndex.toInt == (0xa ^ pcIndex))
      }
  }
}
