package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class ReorderBufferProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocateAccept = in Bool ()
    val flush = in Bool ()
    val allocatePc = in Vec (UInt(config.xlen bits), config.renameWidth)
    val allocateSerializing = in Bits (config.renameWidth bits)
    val allocateIsLoad = in Bits (config.renameWidth bits)
    val allocateIsStore = in Bits (config.renameWidth bits)
    val allocateIsBranch = in Bits (config.renameWidth bits)
    val allocateLoadQueueIndex = in Vec (UInt(config.loadQueueIndexWidth bits), config.renameWidth)
    val allocateStoreQueueIndex = in Vec (UInt(config.storeQueueIndexWidth bits), config.renameWidth)
    val allocateSystemOperation =
      in Vec (UInt(SystemOperation.Width bits), config.renameWidth)
    val allocateReady = out Bool ()
    val allocatedPointer = out Vec (UInt(config.robPointerWidth bits), config.renameWidth)
    val completionValid = in Bits (config.writebackWidth bits)
    val completionWritesPdst = in Bits (config.writebackWidth bits)
    val completionRobPointer = in Vec (UInt(config.robPointerWidth bits), config.writebackWidth)
    val completionRecoveryEpoch =
      in Vec (UInt(config.recoveryEpochWidth bits), config.writebackWidth)
    val completionData = in Vec (Bits(config.xlen bits), config.writebackWidth)
    val completionSideEffectData = in Vec (Bits(config.xlen bits), config.writebackWidth)
    val completionExceptionValid = in Bits (config.writebackWidth bits)
    val completionExceptionEcode = in Vec (UInt(6 bits), config.writebackWidth)
    val completionExceptionEsubcode = in Vec (UInt(9 bits), config.writebackWidth)
    val completionExceptionBadVAddrValid = in Bits (config.writebackWidth bits)
    val completionExceptionBadVAddr = in Vec (UInt(config.xlen bits), config.writebackWidth)
    val completionExceptionTlbRefill = in Bits (config.writebackWidth bits)
    val completionBranchResolved = in Bits (config.writebackWidth bits)
    val completionBranchTaken = in Bits (config.writebackWidth bits)
    val completionBranchTarget = in Vec (UInt(config.xlen bits), config.writebackWidth)
    val completionBranchMispredict = in Bits (config.writebackWidth bits)
    val storeCompletionBypassValid = in Bool ()
    val storeCompletionBypassRobPointer = in UInt (config.robPointerWidth bits)
    val storeCompletionBypassRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val currentEpoch = in UInt (config.recoveryEpochWidth bits)
    val predictorUpdateCapacity = in UInt (log2Up(config.commitWidth + 1) bits)
    val completionWakeupValid = out Bits (config.writebackWidth bits)
    val completionWakeupCandidateValid = out Bits (config.writebackWidth bits)
    val commitValid = out Bits (config.commitWidth bits)
    val commitPc = out Vec (UInt(config.xlen bits), config.commitWidth)
    val commitResult = out Vec (Bits(config.xlen bits), config.commitWidth)
    val commitSideEffectData = out Vec (Bits(config.xlen bits), config.commitWidth)
    val commitExceptionValid = out Bits (config.commitWidth bits)
    val commitExceptionEcode = out Vec (UInt(6 bits), config.commitWidth)
    val commitExceptionEsubcode = out Vec (UInt(9 bits), config.commitWidth)
    val commitExceptionBadVAddrValid = out Bits (config.commitWidth bits)
    val commitExceptionBadVAddr = out Vec (UInt(config.xlen bits), config.commitWidth)
    val commitExceptionTlbRefill = out Bits (config.commitWidth bits)
    val commitIsLoad = out Bits (config.commitWidth bits)
    val commitIsStore = out Bits (config.commitWidth bits)
    val commitIsBranch = out Bits (config.commitWidth bits)
    val commitLoadQueueIndex = out Vec (UInt(config.loadQueueIndexWidth bits), config.commitWidth)
    val commitStoreQueueIndex = out Vec (UInt(config.storeQueueIndexWidth bits), config.commitWidth)
    val commitBranchTaken = out Bits (config.commitWidth bits)
    val commitBranchTarget = out Vec (UInt(config.xlen bits), config.commitWidth)
    val recoveryValid = out Bool ()
    val recoveryCause = out UInt (RecoveryCause.Width bits)
    val recoveryTaken = out Bool ()
    val recoveryTarget = out UInt (config.xlen bits)
    val occupancy = out UInt (log2Up(config.robEntries + 1) bits)
    val empty = out Bool ()
    val headPointer = out UInt (config.robPointerWidth bits)
  }
  noIoPrefix()

  val rob = new ReorderBuffer(config)
  for (lane <- 0 until config.renameWidth) {
    rob.io.allocate(lane).assignFromBits(B(0, rob.io.allocate(lane).getBitsWidth bits))
    rob.io.allocate(lane).uop.decoded.pc.allowOverride()
    rob.io.allocate(lane).uop.decoded.pc := io.allocatePc(lane)
    rob.io.allocate(lane).uop.decoded.serializing.allowOverride()
    rob.io.allocate(lane).uop.decoded.serializing := io.allocateSerializing(lane)
    rob.io.allocate(lane).uop.decoded.isBranch.allowOverride()
    rob.io.allocate(lane).uop.decoded.isLoad.allowOverride()
    rob.io.allocate(lane).uop.decoded.isStore.allowOverride()
    rob.io.allocate(lane).uop.decoded.isLoad := io.allocateIsLoad(lane)
    rob.io.allocate(lane).uop.decoded.isStore := io.allocateIsStore(lane)
    rob.io.allocate(lane).uop.decoded.isBranch := io.allocateIsBranch(lane)
    rob.io.allocate(lane).uop.decoded.systemOperation.allowOverride()
    rob.io.allocate(lane).uop.decoded.systemOperation := io.allocateSystemOperation(lane)
    rob.io.allocate(lane).uop.loadQueueIndex.allowOverride()
    rob.io.allocate(lane).uop.storeQueueIndex.allowOverride()
    rob.io.allocate(lane).uop.loadQueueIndex := io.allocateLoadQueueIndex(lane)
    rob.io.allocate(lane).uop.storeQueueIndex := io.allocateStoreQueueIndex(lane)
  }
  rob.io.currentEpoch := io.currentEpoch
  rob.io.predictorUpdateCapacity := io.predictorUpdateCapacity
  rob.io.observationRenameAdmission := 0
  rob.io.completionValid := io.completionValid
  rob.io.storeCompletionBypassValid := io.storeCompletionBypassValid
  rob.io.storeCompletionBypass.robPointer := io.storeCompletionBypassRobPointer
  rob.io.storeCompletionBypass.recoveryEpoch := io.storeCompletionBypassRecoveryEpoch
  for (lane <- 0 until config.writebackWidth) {
    val completion = rob.io.completion(lane)
    completion.robPointer := io.completionRobPointer(lane)
    completion.recoveryEpoch := io.completionRecoveryEpoch(lane)
    completion.pdst := U(lane + 1, config.physicalRegIndexWidth bits)
    completion.writesPdst := io.completionWritesPdst(lane)
    completion.data := io.completionData(lane)
    completion.sideEffectData := io.completionSideEffectData(lane)
    completion.exception.valid := io.completionExceptionValid(lane)
    completion.exception.ecode := io.completionExceptionEcode(lane)
    completion.exception.esubcode := io.completionExceptionEsubcode(lane)
    completion.exception.badVAddrValid := io.completionExceptionBadVAddrValid(lane)
    completion.exception.badVAddr := io.completionExceptionBadVAddr(lane)
    completion.exception.tlbRefill := io.completionExceptionTlbRefill(lane)
    completion.branchResolved := io.completionBranchResolved(lane)
    completion.branchTaken := io.completionBranchTaken(lane)
    completion.branchTarget := io.completionBranchTarget(lane)
    completion.branchMispredict := io.completionBranchMispredict(lane)
  }
  rob.io.allocateValid := io.allocateValid
  rob.io.allocateAccept := io.allocateAccept
  rob.io.allocateAcceptMask := Mux(io.allocateAccept, io.allocateValid, B(0, config.renameWidth bits))
  rob.io.flush := io.flush

  io.allocateReady := rob.io.allocateReady
  io.allocatedPointer := rob.io.allocatedPointer
  io.completionWakeupValid := rob.io.completionWakeupValid
  io.completionWakeupCandidateValid := rob.io.completionWakeupCandidateValid
    io.commitValid := rob.io.commitValid
    for (lane <- 0 until config.commitWidth) {
      io.commitPc(lane) := rob.io.commit(lane).pc
      io.commitResult(lane) := rob.io.commit(lane).result
      io.commitSideEffectData(lane) := rob.io.commit(lane).sideEffectData
      io.commitExceptionValid(lane) := rob.io.commit(lane).exception.valid
      io.commitExceptionEcode(lane) := rob.io.commit(lane).exception.ecode
      io.commitExceptionEsubcode(lane) := rob.io.commit(lane).exception.esubcode
      io.commitExceptionBadVAddrValid(lane) := rob.io.commit(lane).exception.badVAddrValid
      io.commitExceptionBadVAddr(lane) := rob.io.commit(lane).exception.badVAddr
      io.commitExceptionTlbRefill(lane) := rob.io.commit(lane).exception.tlbRefill
    io.commitIsLoad(lane) := rob.io.commit(lane).isLoad
    io.commitIsStore(lane) := rob.io.commit(lane).isStore
    io.commitIsBranch(lane) := rob.io.commit(lane).isBranch
    io.commitLoadQueueIndex(lane) := rob.io.commit(lane).loadQueueIndex
    io.commitStoreQueueIndex(lane) := rob.io.commit(lane).storeQueueIndex
    io.commitBranchTaken(lane) := rob.io.commit(lane).branchTaken
    io.commitBranchTarget(lane) := rob.io.commit(lane).branchTarget
  }
  io.recoveryValid := rob.io.recoveryValid
  io.recoveryCause := rob.io.recovery.cause
  io.recoveryTaken := rob.io.recovery.taken
  io.recoveryTarget := rob.io.recovery.target
  io.occupancy := rob.io.occupancy
  io.empty := rob.io.empty
  io.headPointer := rob.io.headPointer
}

class ReorderBufferSpec extends AnyFunSuite {
  private def initialize(dut: ReorderBufferProbe, config: OooCoreConfig): Unit = {
    dut.io.allocateValid #= 0
    dut.io.allocateAccept #= false
    dut.io.flush #= false
    dut.io.completionValid #= 0
    dut.io.completionWritesPdst #= 0
    for (lane <- 0 until config.writebackWidth) {
      dut.io.completionData(lane) #= BigInt(0x100 + lane)
      dut.io.completionSideEffectData(lane) #= 0
      dut.io.completionExceptionEcode(lane) #= 0
      dut.io.completionExceptionEsubcode(lane) #= 0
      dut.io.completionExceptionBadVAddr(lane) #= 0
    }
    dut.io.completionExceptionValid #= 0
    dut.io.completionExceptionBadVAddrValid #= 0
    dut.io.completionExceptionTlbRefill #= 0
    dut.io.completionBranchResolved #= 0
    dut.io.completionBranchTaken #= 0
    dut.io.completionBranchMispredict #= 0
    dut.io.storeCompletionBypassValid #= false
    dut.io.storeCompletionBypassRobPointer #= 0
    dut.io.storeCompletionBypassRecoveryEpoch #= 0
    dut.io.currentEpoch #= 0
    dut.io.predictorUpdateCapacity #= config.commitWidth
    dut.io.allocateSerializing #= 0
    dut.io.allocateIsLoad #= 0
    dut.io.allocateIsStore #= 0
    dut.io.allocateIsBranch #= 0
    for (lane <- 0 until config.writebackWidth) {
      dut.io.completionRobPointer(lane) #= 0
      dut.io.completionRecoveryEpoch(lane) #= 0
      dut.io.completionBranchTarget(lane) #= 0
    }
    for (lane <- 0 until config.renameWidth) {
      dut.io.allocatePc(lane) #= 0
      dut.io.allocateLoadQueueIndex(lane) #= 0
      dut.io.allocateStoreQueueIndex(lane) #= 0
      dut.io.allocateSystemOperation(lane) #= 0
    }
  }

  test("ROB occupancy follows accepted allocation, not ready speculation") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-allocation-handshake", 0x4f4f45) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()
        assert(dut.io.empty.toBoolean)
        assert(dut.io.occupancy.toBigInt == 0)

        dut.io.allocateValid #= 1
        sleep(1)
        assert(dut.io.allocateReady.toBoolean)
        dut.io.allocateAccept #= false
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.empty.toBoolean)

        dut.io.allocateAccept #= true
        sample()
        assert(dut.io.occupancy.toBigInt == 1)
        assert(!dut.io.empty.toBoolean)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.flush #= true
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB preserves retirement metadata across three-wide wrap and reuse") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-metadata-wrap-reuse", 0x4f4f60) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        // Eleven groups allocate 33 entries, forcing the 32-entry ROB pointer/index to wrap.
        for (group <- 0 until 11) {
          dut.io.allocateValid #= 7
          dut.io.allocateAccept #= true
          var loadMask = 0
          var storeMask = 0
          var branchMask = 0
          val expected = (0 until config.renameWidth).map { lane =>
            val kind = (group + lane) % 3
            (kind == 0, kind == 1, kind == 2,
              (group * 3 + lane) & 7, (group * 5 + lane) & 7)
          }.toList
          for (lane <- 0 until config.renameWidth) {
            val load = (group + lane) % 3 == 0
            val store = (group + lane) % 3 == 1
            val branch = (group + lane) % 3 == 2
            dut.io.allocatePc(lane) #= BigInt(group * 0x100 + lane * 4)
            if (load) loadMask |= 1 << lane
            if (store) storeMask |= 1 << lane
            if (branch) branchMask |= 1 << lane
            dut.io.allocateLoadQueueIndex(lane) #= BigInt((group * 3 + lane) & 7)
            dut.io.allocateStoreQueueIndex(lane) #= BigInt((group * 5 + lane) & 7)
          }
          dut.io.allocateIsLoad #= loadMask
          dut.io.allocateIsStore #= storeMask
          dut.io.allocateIsBranch #= branchMask
          sleep(1)
          val pointers = (0 until config.renameWidth)
            .map(lane => dut.io.allocatedPointer(lane).toBigInt)
          sample()

          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          dut.io.completionValid #= 7
          for (lane <- 0 until config.renameWidth) {
            dut.io.completionRobPointer(lane) #= pointers(lane)
          }

          var remaining = expected
          for (_ <- 0 until 4) {
            sample()
            for (lane <- 0 until config.commitWidth) {
              if ((dut.io.commitValid.toBigInt & (1 << lane)) != 0 && remaining.nonEmpty) {
                val (expectedLoad, expectedStore, expectedBranch, expectedLoadIndex,
                  expectedStoreIndex) = remaining.head
                remaining = remaining.tail
                assert(dut.io.commitIsLoad.toBigInt.testBit(lane) == expectedLoad)
                assert(dut.io.commitIsStore.toBigInt.testBit(lane) == expectedStore)
                assert(dut.io.commitIsBranch.toBigInt.testBit(lane) == expectedBranch)
                assert(
                  dut.io.commitLoadQueueIndex(lane).toBigInt == BigInt(expectedLoadIndex)
                )
                assert(
                  dut.io.commitStoreQueueIndex(lane).toBigInt == BigInt(expectedStoreIndex)
                )
              }
            }
          }
          assert(remaining.isEmpty)
          dut.io.completionValid #= 0
        }
        sample()
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB flush does not immediately alias stale completion pointers") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-flush-pointer-generation", 0x4f4f46) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        sleep(1)
        val stalePointer = dut.io.allocatedPointer(0).toBigInt
        sample()
        assert(dut.io.occupancy.toBigInt == 1)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.flush #= true
        sample()
        assert(dut.io.occupancy.toBigInt == 0)

        dut.io.flush #= false
        dut.io.currentEpoch #= 1
        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        sleep(1)
        val newPointer = dut.io.allocatedPointer(0).toBigInt
        assert(newPointer != stalePointer)
        sample()
        assert(dut.io.occupancy.toBigInt == 1)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= stalePointer
        dut.io.completionValid #= 1
        sleep(1)
        sample()
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.completionRobPointer(0) #= newPointer
        dut.io.completionRecoveryEpoch(0) #= 1
        sleep(1)
        sample()
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        assert((dut.io.commitValid.toBigInt & 1) == 1)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB retains three-wide commit after a head completion bypass") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-three-commit", 0x4f4f47) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocatePc(0) #= 0
        dut.io.allocatePc(1) #= 1
        dut.io.allocatePc(2) #= 2
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        sleep(1)
        assert(dut.io.allocatedPointer(0).toBigInt == 0)
        assert(dut.io.allocatedPointer(1).toBigInt == 1)
        assert(dut.io.allocatedPointer(2).toBigInt == 2)
        sample()
        assert(dut.io.occupancy.toBigInt == 3)

        dut.io.allocatePc(0) #= 3
        dut.io.allocateValid #= 1
        sleep(1)
        assert(dut.io.allocatedPointer(0).toBigInt == 3)
        sample()
        assert(dut.io.occupancy.toBigInt == 4)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= 1
        dut.io.completionRobPointer(1) #= 2
        dut.io.completionRobPointer(2) #= 3
        dut.io.completionValid #= 7
        sample()
        assert(dut.io.commitValid.toBigInt == 0)
        dut.io.completionValid #= 0
        sample()
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.completionRobPointer(0) #= 0
        dut.io.completionValid #= 1
        sample()
        assert(dut.io.commitValid.toBigInt == 7)
        assert(dut.io.commitPc(0).toBigInt == 0)
        assert(dut.io.commitPc(1).toBigInt == 1)
        assert(dut.io.commitPc(2).toBigInt == 2)
        dut.io.completionValid #= 0
        sample()
        assert(dut.io.commitValid.toBigInt == 1)
        assert(dut.io.commitPc(0).toBigInt == 3)

        sample()
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB narrows the retirement prefix to predictor FIFO capacity") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob-predictor-capacity")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-predictor-capacity", 0xb03c) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        dut.io.allocateIsBranch #= 7
        for (lane <- 0 until config.renameWidth) dut.io.allocatePc(lane) #= lane
        sleep(1)
        val pointers =
          (0 until config.renameWidth).map(lane => dut.io.allocatedPointer(lane).toBigInt)
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        for (lane <- 0 until config.writebackWidth) {
          dut.io.completionRobPointer(lane) #= pointers.lift(lane).getOrElse(BigInt(0))
        }
        dut.io.completionValid #= 7
        sample()
        dut.io.completionValid #= 0
        sample()

        dut.io.predictorUpdateCapacity #= 1
        sleep(1)
        assert(dut.io.commitValid.toBigInt == 1)
        sample()
        assert(dut.io.occupancy.toInt == 2)

        dut.io.predictorUpdateCapacity #= 2
        sleep(1)
        assert(dut.io.commitValid.toBigInt == 3)
        sample()
        assert(dut.io.occupancy.toInt == 0)
      }
  }

  test("banked ROB payload remains ordered across the physical index wrap") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = false,
      enableBranchHeadCompletionBypass = false
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-payload-bank-wrap", 0x4f4f4b) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        for (index <- 0 until config.robEntries - 1) {
          dut.io.allocateValid #= 1
          dut.io.allocateAccept #= true
          dut.io.allocatePc(0) #= index
          sleep(1)
          val pointer = dut.io.allocatedPointer(0).toBigInt
          sample()

          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          dut.io.completionRobPointer(0) #= pointer
          dut.io.completionValid #= 1
          sample()
          assert(dut.io.commitValid.toBigInt == 0)
          dut.io.completionValid #= 0
          sample()
          assert(dut.io.commitValid.toBigInt == 1)
          assert(dut.io.commitPc(0).toBigInt == index)
          sample()
          assert(dut.io.occupancy.toBigInt == 0)
        }

        dut.io.allocatePc(0) #= 0x31
        dut.io.allocatePc(1) #= 0x32
        dut.io.allocatePc(2) #= 0x33
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        sleep(1)
        val pointers = (0 until config.renameWidth).map { lane =>
          dut.io.allocatedPointer(lane).toBigInt
        }
        assert(pointers.map(_ & (config.robEntries - 1)) == Seq(31, 0, 1))
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        for (lane <- 0 until config.commitWidth) {
          dut.io.completionRobPointer(lane) #= pointers(lane)
          dut.io.completionData(lane) #= BigInt(0xc031 + lane)
          dut.io.completionSideEffectData(lane) #= BigInt(0xd031 + lane)
        }
        dut.io.completionValid #= 7
        sample()
        assert(dut.io.commitValid.toBigInt == 0)
        dut.io.completionValid #= 0
        sample()
        assert(dut.io.commitValid.toBigInt == 7)
        assert(
          (0 until 3).map(lane => dut.io.commitPc(lane).toBigInt) == Seq(0x31, 0x32, 0x33)
        )
        assert(
          (0 until 3).map(lane => dut.io.commitResult(lane).toBigInt) ==
            Seq(BigInt(0xc031), BigInt(0xc032), BigInt(0xc033))
        )
        assert(
          (0 until 3).map(lane => dut.io.commitSideEffectData(lane).toBigInt) ==
            Seq(BigInt(0xd031), BigInt(0xd032), BigInt(0xd033))
        )
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
      }
  }

  test("branch completion overwrites a reused ROB slot target before retirement") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-branch-target-reuse", 0x4f4f4d) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        def allocateAndComplete(pc: Int, isBranch: Boolean, target: BigInt): Unit = {
          dut.io.allocatePc(0) #= pc
          dut.io.allocateIsBranch #= (if (isBranch) 1 else 0)
          dut.io.allocateValid #= 1
          dut.io.allocateAccept #= true
          sleep(1)
          val pointer = dut.io.allocatedPointer(0).toBigInt
          sample()
          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          dut.io.completionRobPointer(0) #= pointer
          dut.io.completionBranchResolved #= (if (isBranch) 1 else 0)
          dut.io.completionBranchTarget(0) #= target
          dut.io.completionValid #= 1
          sample()
          var commitObservations = 0
          def observeCommit(): Unit = {
            if ((dut.io.commitValid.toBigInt & 1) != 0) {
              commitObservations += 1
              assert(dut.io.commitPc(0).toBigInt == BigInt(pc))
              if (isBranch) assert(dut.io.commitBranchTarget(0).toBigInt == target)
            }
          }
          observeCommit()
          dut.io.completionValid #= 0
          dut.io.completionBranchResolved #= 0
          for (_ <- 0 until 4) {
            sample()
            observeCommit()
          }
          assert(commitObservations == 1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        val firstTarget = BigInt("1c010004", 16)
        val reusedTarget = BigInt("1c020008", 16)
        allocateAndComplete(pc = 0, isBranch = true, target = firstTarget)
        for (pointer <- 1 until config.robEntries) {
          allocateAndComplete(pc = pointer * 4, isBranch = false, target = 0)
        }
        allocateAndComplete(pc = config.robEntries * 4, isBranch = true, target = reusedTarget)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB exposes accepted physical writes from the registered completion stage") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-registered-wakeup", 0x4f4f48) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        sleep(1)
        val pointer = dut.io.allocatedPointer(0).toBigInt
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= pointer
        dut.io.completionWritesPdst #= 1
        dut.io.completionValid #= 1
        sleep(1)
        assert(dut.io.completionWakeupValid.toBigInt == 0)

        sample()
        assert(dut.io.completionWakeupValid.toBigInt == 1)
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 1)
        assert(dut.io.commitValid.toBigInt == 1)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        assert(dut.io.occupancy.toBigInt == 0)
      }
  }

  test("ordinary head completion may commit from the staged exact-pointer match") {
    for ((bypass, name, seed) <- Seq(
        (false, "legacy", 0x4f4f51),
        (true, "bypass", 0x4f4f52)
      )) {
      val config = OooCoreConfig.FourIssueThreeCommit.copy(
        enableHeadCompletionCommitBypass = bypass
      )
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-rob-head-completion-$name")
        .compile(new ReorderBufferProbe(config))
        .doSim(s"ooo-rob-head-completion-$name", seed) { dut =>
          def sample(): Unit = {
            dut.clockDomain.waitSampling()
            sleep(1)
          }

          dut.clockDomain.forkStimulus(period = 10)
          initialize(dut, config)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample()

          dut.io.allocateValid #= 1
          dut.io.allocateAccept #= true
          dut.io.allocatePc(0) #= BigInt("1c000000", 16)
          sleep(1)
          val pointer = dut.io.allocatedPointer(0).toBigInt
          sample()

          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          dut.io.completionRobPointer(0) #= pointer
          dut.io.completionRecoveryEpoch(0) #= 0
          dut.io.completionValid #= 1
          sample()
          assert(dut.io.completionWakeupValid.toBigInt == 0)
          assert(((dut.io.commitValid.toBigInt & 1) != 0) == bypass)
          if (bypass) {
            assert(dut.io.commitResult(0).toBigInt == 0x100)
          }

          dut.io.completionValid #= 0
          sample()
          if (bypass) {
            assert(dut.io.occupancy.toBigInt == 0)
            assert(dut.io.commitValid.toBigInt == 0)
          } else {
            assert((dut.io.commitValid.toBigInt & 1) == 1)
            sample()
            assert(dut.io.occupancy.toBigInt == 0)
          }
        }
    }
  }

  test("head bypass tracks the next head while an older entry retires") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob-head-turnover")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-head-turnover", 0x4f4f55) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 3
        dut.io.allocateAccept #= true
        dut.io.allocateSerializing #= 1
        dut.io.allocatePc(0) #= 0x100
        dut.io.allocatePc(1) #= 0x104
        sleep(1)
        val older = dut.io.allocatedPointer(0).toBigInt
        val younger = dut.io.allocatedPointer(1).toBigInt
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.allocateSerializing #= 0
        dut.io.completionRobPointer(0) #= older
        dut.io.completionValid #= 1
        sample()
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.commitValid.toBigInt == 1)
        assert(dut.io.commitPc(0).toBigInt == 0x100)

        // The completion is accepted while the serializing older head retires.
        // The following cycle must bypass the newly presented head without a bubble.
        dut.io.completionRobPointer(0) #= younger
        dut.io.completionValid #= 1
        sample()
        assert(dut.io.commitValid.toBigInt == 1)
        assert(dut.io.commitPc(0).toBigInt == 0x104)
        assert(dut.io.commitResult(0).toBigInt == 0x100)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("head completion bypass preserves precise retirement boundaries") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true,
      enableBranchHeadCompletionBypass = false
    )
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob-head-completion-boundaries")
      .compile(new ReorderBufferProbe(config))
    val cases = Seq(
      ("completion-exception", false, false, 0, true, false),
      ("resolved-branch", false, false, 0, false, true),
      ("serializing-uop", true, false, 0, false, false),
      ("system-operation", false, false, 1, false, false),
      ("branch-payload", false, true, 0, false, false)
    )
    for (((name, serializing, isBranch, systemOperation, completionException,
          branchResolved), index) <- cases.zipWithIndex) {
      compiled.doSim(s"ooo-rob-head-completion-$name", 0x4f4f53 + index) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        dut.io.allocatePc(0) #= BigInt("1c000100", 16)
        dut.io.allocateSerializing #= (if (serializing) 1 else 0)
        dut.io.allocateIsBranch #= (if (isBranch) 1 else 0)
        dut.io.allocateSystemOperation(0) #= systemOperation
        sleep(1)
        val pointer = dut.io.allocatedPointer(0).toBigInt
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= pointer
        dut.io.completionExceptionValid #= (if (completionException) 1 else 0)
        dut.io.completionBranchResolved #= (if (branchResolved) 1 else 0)
        dut.io.completionValid #= 1
        sample()
        withClue(s"$name must not retire from the bypass stage: ") {
          assert(dut.io.commitValid.toBigInt == 0)
        }

        dut.io.completionValid #= 0
        dut.io.completionExceptionValid #= 0
        dut.io.completionBranchResolved #= 0
        sample()
        withClue(s"$name must retain normal registered completion: ") {
          assert((dut.io.commitValid.toBigInt & 1) == 1)
          assert(dut.io.commitPc(0).toBigInt == BigInt("1c000100", 16))
          assert(dut.io.commitResult(0).toBigInt == 0x100)
        }
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
      }
    }
  }

  test("branch head completion bypass preserves latency and branch metadata") {
    for (enabled <- Seq(false, true)) {
      val config = OooCoreConfig.FourIssueThreeCommit.copy(
        enableHeadCompletionCommitBypass = true,
        enableBranchHeadCompletionBypass = enabled
      )
      SimConfig.withVerilator
        .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          s"/sim-workspace-ooo-rob-branch-head-bypass-$enabled")
        .compile(new ReorderBufferProbe(config))
        .doSim(s"ooo-rob-branch-head-bypass-$enabled", if (enabled) 0x4f4f57 else 0x4f4f56) {
          dut =>
            def sample(): Unit = {
              dut.clockDomain.waitSampling()
              sleep(1)
            }

            dut.clockDomain.forkStimulus(period = 10)
            initialize(dut, config)
            dut.clockDomain.assertReset()
            dut.clockDomain.waitSampling(2)
            dut.clockDomain.deassertReset()
            sample()

            dut.io.allocateValid #= 1
            dut.io.allocateAccept #= true
            dut.io.allocateIsBranch #= 1
            dut.io.allocatePc(0) #= 0x1c000100
            sleep(1)
            val pointer = dut.io.allocatedPointer(0).toBigInt
            sample()

            dut.io.allocateValid #= 0
            dut.io.allocateAccept #= false
            dut.io.allocateIsBranch #= 0
            dut.io.completionRobPointer(0) #= pointer
            dut.io.completionBranchResolved #= 1
            dut.io.completionBranchTaken #= 1
            dut.io.completionBranchTarget(0) #= 0x1c001234
            dut.io.completionValid #= 1
            sample()

            withClue(s"enabled=$enabled bypass cycle: ") {
              assert((dut.io.commitValid.toBigInt & 1) == (if (enabled) 1 else 0))
            }
            if (!enabled) {
              dut.io.completionValid #= 0
              sample()
              assert((dut.io.commitValid.toBigInt & 1) == 1)
            }
            assert(dut.io.commitResult(0).toBigInt == 0x100)
            assert((dut.io.commitBranchTaken.toBigInt & 1) == 1)
            assert(dut.io.commitBranchTarget(0).toBigInt == 0x1c001234)
            assert(!dut.io.recoveryValid.toBoolean)

            dut.io.completionValid #= 0
            sample()
            assert(dut.io.occupancy.toBigInt == 0)
        }
    }
  }

  test("branch head completion bypass preserves precise qualification and recovery") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true,
      enableBranchHeadCompletionBypass = true
    )
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-rob-branch-head-qualification")
      .compile(new ReorderBufferProbe(config))

    compiled.doSim("ooo-rob-branch-head-stale-epoch", 0x4f4f58) { dut =>
      def sample(): Unit = {
        dut.clockDomain.waitSampling()
        sleep(1)
      }

      dut.clockDomain.forkStimulus(period = 10)
      initialize(dut, config)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample()

      dut.io.currentEpoch #= 2
      dut.io.allocateValid #= 1
      dut.io.allocateAccept #= true
      dut.io.allocateIsBranch #= 1
      sleep(1)
      val pointer = dut.io.allocatedPointer(0).toBigInt
      sample()

      dut.io.allocateValid #= 0
      dut.io.allocateAccept #= false
      dut.io.allocateIsBranch #= 0
      dut.io.completionRobPointer(0) #= pointer
      dut.io.completionRecoveryEpoch(0) #= 1
      dut.io.completionBranchResolved #= 1
      dut.io.completionValid #= 1
      sample()
      assert(dut.io.commitValid.toBigInt == 0)
      dut.io.completionValid #= 0
      sample()
      assert(dut.io.commitValid.toBigInt == 0)
      assert(dut.io.occupancy.toBigInt == 1)
    }

    compiled.doSim("ooo-rob-branch-head-exception", 0x4f4f59) { dut =>
      def sample(): Unit = {
        dut.clockDomain.waitSampling()
        sleep(1)
      }

      dut.clockDomain.forkStimulus(period = 10)
      initialize(dut, config)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample()

      dut.io.allocateValid #= 1
      dut.io.allocateAccept #= true
      dut.io.allocateIsBranch #= 1
      sleep(1)
      val pointer = dut.io.allocatedPointer(0).toBigInt
      sample()

      dut.io.allocateValid #= 0
      dut.io.allocateAccept #= false
      dut.io.allocateIsBranch #= 0
      dut.io.completionRobPointer(0) #= pointer
      dut.io.completionExceptionValid #= 1
      dut.io.completionBranchResolved #= 1
      dut.io.completionValid #= 1
      sample()
      assert(dut.io.commitValid.toBigInt == 0)
      dut.io.completionValid #= 0
      dut.io.completionExceptionValid #= 0
      sample()
      assert((dut.io.commitValid.toBigInt & 1) == 1)
      assert(dut.io.recoveryValid.toBoolean)
      assert(dut.io.recoveryCause.toBigInt == 2)
    }

    compiled.doSim("ooo-rob-branch-head-capacity-recovery", 0x4f4f5a) { dut =>
      def sample(): Unit = {
        dut.clockDomain.waitSampling()
        sleep(1)
      }

      dut.clockDomain.forkStimulus(period = 10)
      initialize(dut, config)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()
      sample()

      dut.io.allocateValid #= 1
      dut.io.allocateAccept #= true
      dut.io.allocateIsBranch #= 1
      sleep(1)
      val pointer = dut.io.allocatedPointer(0).toBigInt
      sample()

      dut.io.allocateValid #= 0
      dut.io.allocateAccept #= false
      dut.io.allocateIsBranch #= 0
      dut.io.predictorUpdateCapacity #= 0
      dut.io.completionRobPointer(0) #= pointer
      dut.io.completionBranchResolved #= 1
      dut.io.completionBranchTaken #= 1
      dut.io.completionBranchMispredict #= 1
      dut.io.completionBranchTarget(0) #= 0x1c002468
      dut.io.completionValid #= 1
      sample()
      assert(dut.io.commitValid.toBigInt == 0)
      assert(!dut.io.recoveryValid.toBoolean)

      dut.io.completionValid #= 0
      sample()
      assert(dut.io.commitValid.toBigInt == 0)
      dut.io.predictorUpdateCapacity #= 1
      sleep(1)
      assert((dut.io.commitValid.toBigInt & 1) == 1)
      assert(dut.io.commitResult(0).toBigInt == 0x100)
      assert((dut.io.commitBranchTaken.toBigInt & 1) == 1)
      assert(dut.io.commitBranchTarget(0).toBigInt == 0x1c002468)
      assert(dut.io.recoveryValid.toBoolean)
      assert(dut.io.recoveryCause.toBigInt == 1)
      assert(dut.io.recoveryTaken.toBoolean)
      assert(dut.io.recoveryTarget.toBigInt == 0x1c002468)
      sample()
      assert(dut.io.occupancy.toBigInt == 0)
    }
  }

  test("ROB registers epoch qualification without adding wakeup latency") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-registered-epoch-qualification", 0x4f4f4a) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.currentEpoch #= 2
        dut.io.completionWritesPdst #= 1
        dut.io.completionValid #= 1
        dut.io.completionRecoveryEpoch(0) #= 1
        sample()
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 0)

        dut.io.completionRecoveryEpoch(0) #= 2
        sample()
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 1)
        assert(dut.io.completionWakeupValid.toBigInt == 1)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 0)
      }
  }

  test("narrow Store completion obeys epoch and flush while retaining head bypass") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = true
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob-store-completion")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-narrow-store-completion", 0x4f4f54) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        def allocateOne(pc: Int): BigInt = {
          dut.io.allocateValid #= 1
          dut.io.allocateAccept #= true
          dut.io.allocatePc(0) #= pc
          sleep(1)
          val pointer = dut.io.allocatedPointer(0).toBigInt
          sample()
          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          pointer
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.currentEpoch #= 2
        val firstPointer = allocateOne(0x1c000100)
        dut.io.storeCompletionBypassRobPointer #= firstPointer
        dut.io.storeCompletionBypassRecoveryEpoch #= 1
        dut.io.storeCompletionBypassValid #= true
        sample()
        dut.io.storeCompletionBypassValid #= false
        assert(dut.io.commitValid.toBigInt == 0)
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        sample()
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.storeCompletionBypassRecoveryEpoch #= 2
        dut.io.storeCompletionBypassValid #= true
        sample()
        dut.io.storeCompletionBypassValid #= false
        assert((dut.io.commitValid.toBigInt & 1) == 1)
        assert(dut.io.commitPc(0).toBigInt == 0x1c000100)
        assert(dut.io.commitResult(0).toBigInt == 0)
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        sample()
        assert(dut.io.occupancy.toBigInt == 0)

        val flushedPointer = allocateOne(0x1c000104)
        dut.io.storeCompletionBypassRobPointer #= flushedPointer
        dut.io.storeCompletionBypassRecoveryEpoch #= 2
        dut.io.storeCompletionBypassValid #= true
        dut.io.flush #= true
        sample()
        dut.io.storeCompletionBypassValid #= false
        dut.io.flush #= false
        assert(dut.io.empty.toBoolean)
        for (_ <- 0 until 2) {
          sample()
          assert(dut.io.commitValid.toBigInt == 0)
          assert(dut.io.completionWakeupValid.toBigInt == 0)
        }
      }
  }

  test("ROB flush suppresses architectural wakeup without extending the IQ candidate path") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-rob")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-flush-wakeup-candidate", 0x4f4f49) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        sleep(1)
        val pointer = dut.io.allocatedPointer(0).toBigInt
        sample()

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= pointer
        dut.io.completionWritesPdst #= 1
        dut.io.completionValid #= 1
        sample()
        assert(dut.io.completionWakeupValid.toBigInt == 1)
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 1)

        dut.io.flush #= true
        sleep(1)
        assert(dut.io.completionWakeupValid.toBigInt == 0)
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 1)
        sample()
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 0)
        assert(dut.io.empty.toBoolean)
      }
  }

  test("ROB rejects an old-epoch completion after the full pointer identity wraps") {
    val variants = Seq(
      ("default", OooCoreConfig.FourIssueThreeCommit, 0x4f4f4c),
      ("expanded-window", OooCoreConfig.ExpandedWindow, 0x4f4f5c)
    )
    for ((variant, baseConfig, seed) <- variants) {
      val config = baseConfig.copy(enableHeadCompletionCommitBypass = true)
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-rob-full-pointer-$variant"
        )
        .compile(new ReorderBufferProbe(config))
        .doSim(s"ooo-rob-full-pointer-wrap-epoch-qualification-$variant", seed) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        def allocateOne(expectedPointer: Int, pc: Int): Unit = {
          dut.io.allocatePc(0) #= pc
          dut.io.allocateValid #= 1
          dut.io.allocateAccept #= true
          sleep(1)
          assert(dut.io.allocateReady.toBoolean)
          assert(dut.io.allocatedPointer(0).toBigInt == expectedPointer)
          sample()
          dut.io.allocateValid #= 0
          dut.io.allocateAccept #= false
          assert(dut.io.occupancy.toBigInt == 1)
        }

        def completeAndCommit(pointer: Int, epoch: Int): Unit = {
          dut.io.completionRobPointer(0) #= pointer
          dut.io.completionRecoveryEpoch(0) #= epoch
          dut.io.completionValid #= 1
          sample()
          assert((dut.io.commitValid.toBigInt & 1) == 1)
          assert(dut.io.commitPc(0).toBigInt == pointer)
          dut.io.completionValid #= 0
          sample()
          assert(dut.io.occupancy.toBigInt == 0)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        // Preserve pointer 0 as an old-epoch completion identity, then flush it.
        allocateOne(expectedPointer = 0, pc = 0)
        dut.io.flush #= true
        sample()
        dut.io.flush #= false
        assert(dut.io.empty.toBoolean)

        // Retire every remaining pointer identity so index and generation both
        // return to pointer 0.  The expanded variant exercises the new seventh bit.
        dut.io.currentEpoch #= 1
        for (pointer <- 1 until (1 << config.robPointerWidth)) {
          allocateOne(expectedPointer = pointer, pc = pointer)
          completeAndCommit(pointer = pointer, epoch = 1)
        }

        allocateOne(expectedPointer = 0, pc = 0x100)
        dut.io.completionRobPointer(0) #= 0
        dut.io.completionRecoveryEpoch(0) #= 0
        dut.io.completionWritesPdst #= 1
        dut.io.completionValid #= 1
        sample()
        dut.io.completionValid #= 0
        assert(dut.io.completionWakeupCandidateValid.toBigInt == 0)
        assert(dut.io.completionWakeupValid.toBigInt == 0)

        for (_ <- 0 until 4) {
          sample()
          assert(dut.io.completionWakeupCandidateValid.toBigInt == 0)
          assert(dut.io.completionWakeupValid.toBigInt == 0)
          assert(dut.io.commitValid.toBigInt == 0)
          assert(dut.io.occupancy.toBigInt == 1)
        }

        dut.io.completionRecoveryEpoch(0) #= 1
        dut.io.completionWritesPdst #= 0
        dut.io.completionValid #= 1
        sample()
        assert((dut.io.commitValid.toBigInt & 1) == 1)
        assert(dut.io.commitPc(0).toBigInt == 0x100)
        dut.io.completionValid #= 0
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
      }
    }
  }

  test("ROB retains three producer payloads through same-cycle three-wide retirement") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = false,
      enableBranchHeadCompletionBypass = false
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-rob-cold-payload")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-cold-payload-three-wide", 0x52543039) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocatePc(0) #= 0x100
        dut.io.allocatePc(1) #= 0x104
        dut.io.allocatePc(2) #= 0x108
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        sample()
        assert(dut.io.occupancy.toBigInt == 3)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        for (lane <- 0 until 3) {
          dut.io.completionRobPointer(lane) #= lane
          dut.io.completionData(lane) #= BigInt(0xa000 + lane)
          dut.io.completionSideEffectData(lane) #= BigInt(0xb000 + lane)
        }
        dut.io.completionValid #= 7
        sample()
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.completionValid #= 0
        sample()
        assert(dut.io.commitValid.toBigInt == 7)
        for (lane <- 0 until 3) {
          assert(dut.io.commitResult(lane).toBigInt == BigInt(0xa000 + lane))
          assert(dut.io.commitSideEffectData(lane).toBigInt == BigInt(0xb000 + lane))
        }
        sample()
        assert(dut.io.occupancy.toBigInt == 0)
      }
  }

  test("ROB preserves completion exception payload in the precise retirement record") {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableHeadCompletionCommitBypass = false,
      enableBranchHeadCompletionBypass = false
    )
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-rob-cold-exception")
      .compile(new ReorderBufferProbe(config))
      .doSim("ooo-rob-cold-exception", 0x5254303a) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        dut.clockDomain.forkStimulus(period = 10)
        initialize(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        dut.io.allocatePc(0) #= 0x200
        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        sleep(1)
        val pointer = dut.io.allocatedPointer(0).toBigInt
        sample()
        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.completionRobPointer(0) #= pointer
        dut.io.completionExceptionValid #= 1
        dut.io.completionExceptionEcode(0) #= 0x2a
        dut.io.completionExceptionEsubcode(0) #= 0x101
        dut.io.completionExceptionBadVAddrValid #= 1
        dut.io.completionExceptionBadVAddr(0) #= BigInt("cafebabe", 16)
        dut.io.completionExceptionTlbRefill #= 1
        dut.io.completionValid #= 1
        sample()
        assert(dut.io.commitValid.toBigInt == 0)

        dut.io.completionValid #= 0
        dut.io.completionExceptionValid #= 0
        dut.io.completionExceptionBadVAddrValid #= 0
        dut.io.completionExceptionTlbRefill #= 0
        sample()
        assert((dut.io.commitValid.toBigInt & 1) == 1)
        assert((dut.io.commitExceptionValid.toBigInt & 1) == 1)
        assert(dut.io.commitExceptionEcode(0).toBigInt == 0x2a)
        assert(dut.io.commitExceptionEsubcode(0).toBigInt == 0x101)
        assert((dut.io.commitExceptionBadVAddrValid.toBigInt & 1) == 1)
        assert(dut.io.commitExceptionBadVAddr(0).toBigInt == BigInt("cafebabe", 16))
        assert((dut.io.commitExceptionTlbRefill.toBigInt & 1) == 1)
        assert(dut.io.recoveryValid.toBoolean)
        assert(dut.io.recoveryCause.toBigInt == 2)
      }
  }
}
