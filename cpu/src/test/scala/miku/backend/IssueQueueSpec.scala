package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.util.Random

private final class IssueQueueProbe(
    config: OooCoreConfig,
    portIndex: Int = 0,
    separateSelectWakeup: Boolean = false,
    tokenizedIssueOutput: Boolean = false
)
    extends Component {
  val io = new Bundle {
    val enqueueValid = in Bool ()
    val enqueue = in(RenamedMicroOp(config))
    val enqueueReady = out Bool ()
    val wakeupValid = in Bits (config.writebackWidth bits)
    val wakeupPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
    val selectWakeupValid = in Bits (config.writebackWidth bits)
    val selectWakeupPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
    val issueValid = out Bool ()
    val issue = out(RenamedMicroOp(config))
    val issueReady = in Bool ()
    val robHeadPointer = in UInt (config.robPointerWidth bits)
    val flush = in Bool ()
    val occupancy = out UInt (log2Up(config.issueQueueEntriesPerPort + 1) bits)
  }
  noIoPrefix()

  val queue = new IssueQueue(config, portIndex, tokenizedIssueOutput = tokenizedIssueOutput)
  queue.io.enqueueValid := io.enqueueValid
  queue.io.enqueue := io.enqueue
  queue.io.wakeupValid := io.wakeupValid
  queue.io.wakeupPdst := io.wakeupPdst
  if (separateSelectWakeup) {
    queue.io.selectWakeupValid := io.selectWakeupValid
    queue.io.selectWakeupPdst := io.selectWakeupPdst
  } else {
    queue.io.selectWakeupValid := io.wakeupValid
    queue.io.selectWakeupPdst := io.wakeupPdst
  }
  queue.io.issueReady := io.issueReady
  queue.io.robHeadPointer := io.robHeadPointer
  queue.io.flush := io.flush

  io.enqueueReady := queue.io.enqueueReady
  io.issueValid := queue.io.issueValid
  io.issue := queue.io.issue
  io.occupancy := queue.io.occupancy
}

class IssueQueueSpec extends AnyFunSuite {
  private def clearInputs(dut: IssueQueueProbe, config: OooCoreConfig): Unit = {
    dut.io.enqueueValid #= false
    dut.io.enqueue.decoded.serializing #= false
    dut.io.enqueue.decoded.isStore #= false
    dut.io.enqueue.pdst #= 0
    dut.io.enqueue.oldPdst #= 0
    dut.io.enqueue.psrc1 #= 0
    dut.io.enqueue.psrc2 #= 0
    dut.io.enqueue.source1Ready #= false
    dut.io.enqueue.source2Ready #= false
    dut.io.enqueue.robPointer #= 0
    dut.io.enqueue.recoveryEpoch #= 0
    dut.io.enqueue.loadQueueIndex #= 0
    dut.io.enqueue.storeQueueIndex #= 0
    dut.io.wakeupValid #= 0
    dut.io.selectWakeupValid #= 0
    for (lane <- 0 until config.writebackWidth) {
      dut.io.wakeupPdst(lane) #= 0
      dut.io.selectWakeupPdst(lane) #= 0
    }
    dut.io.issueReady #= false
    dut.io.robHeadPointer #= 0
    dut.io.flush #= false
  }

  private def sample(dut: IssueQueueProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  test("IQ balanced selection preserves oldest-ready priority") {
    for (balanced <- Seq(false, true)) {
      val config = OooCoreConfig.FourIssueThreeCommit.copy(
        enableBalancedIssueSelection = balanced
      )
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-iq-balanced-$balanced"
        )
        .compile(new IssueQueueProbe(config))
        .doSim(s"ooo-iq-balanced-$balanced", if (balanced) 0x4971 else 0x4970) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut, config)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          val readyByAge = Seq(false, true, false, true, true, false, true, false)
          dut.io.enqueueValid #= true
          for ((ready, age) <- readyByAge.zipWithIndex) {
            dut.io.enqueue.robPointer #= age
            dut.io.enqueue.psrc1 #= (age + 1)
            dut.io.enqueue.source1Ready #= ready
            dut.io.enqueue.source2Ready #= true
            sample(dut)
          }
          dut.io.enqueueValid #= false
          dut.io.issueReady #= true

          for (expected <- Seq(1, 3, 4, 6)) {
            sleep(1)
            assert(dut.io.issueValid.toBoolean)
            assert(
              dut.io.issue.robPointer.toInt == expected,
              s"balanced=$balanced selected ${dut.io.issue.robPointer.toInt}, expected age $expected"
            )
            sample(dut)
          }

          dut.io.issueReady #= false
          assert(!dut.io.issueValid.toBoolean)
          assert(dut.io.occupancy.toInt == 4)
        }
    }
  }

  test("IQ randomized compaction preserves payload and wakeup state") {
    val config = OooCoreConfig.FourIssueThreeCommit
    final case class ModelEntry(
        id: Long,
        pdst: Int,
        psrc1: Int,
        psrc2: Int,
        source1Ready: Boolean,
        source2Ready: Boolean
    )

    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config, portIndex = 0))
      .doSim("ooo-iq-randomized-scoreboard", 0x4957) { dut =>
        val random = new Random(0x4957)
        var entries = Vector.empty[ModelEntry]
        var nextId = 1L

        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.io.enqueue.decoded.fuType #= 0
        dut.io.enqueue.decoded.source1IsPc #= false
        dut.io.enqueue.decoded.source2IsImmediate #= false
        dut.io.enqueue.decoded.source2IsFour #= false
        dut.io.enqueue.decoded.operation #= 1
        dut.io.enqueue.decoded.exception.valid #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        for (cycle <- 0 until 20000) {
          val wakeTags = Vector.fill(config.writebackWidth) {
            if (random.nextInt(4) == 0) 1 + random.nextInt(config.physicalRegs - 1)
            else 0
          }
          var wakeMask = BigInt(0)
          for (lane <- 0 until config.writebackWidth) {
            if (wakeTags(lane) != 0) wakeMask = wakeMask.setBit(lane)
            dut.io.wakeupPdst(lane) #= wakeTags(lane)
          }
          dut.io.wakeupValid #= wakeMask

          val issueReady = random.nextBoolean()
          val enqueueValid = random.nextInt(4) != 0
          val psrc1 = 1 + random.nextInt(config.physicalRegs - 1)
          val psrc2 = 1 + random.nextInt(config.physicalRegs - 1)
          val pdst = 1 + random.nextInt(config.physicalRegs - 1)
          val source1Ready = random.nextInt(3) == 0
          val source2Ready = random.nextInt(3) == 0
          val enqueueId = 0x1c000000L + nextId * 4

          dut.io.issueReady #= issueReady
          dut.io.enqueueValid #= enqueueValid
          dut.io.enqueue.decoded.pc #= enqueueId
          dut.io.enqueue.pdst #= pdst
          dut.io.enqueue.psrc1 #= psrc1
          dut.io.enqueue.psrc2 #= psrc2
          dut.io.enqueue.source1Ready #= source1Ready
          dut.io.enqueue.source2Ready #= source2Ready
          dut.io.enqueue.robPointer #= (nextId % (1 << config.robPointerWidth))
          sleep(1)

          assert(
            dut.io.occupancy.toInt == entries.size,
            s"cycle $cycle occupancy ${dut.io.occupancy.toInt} != ${entries.size}"
          )

          def awakened(tag: Int): Boolean = wakeTags.contains(tag)
          val effectiveEntries = entries.map { entry =>
            entry.copy(
              source1Ready = entry.source1Ready || awakened(entry.psrc1),
              source2Ready = entry.source2Ready || awakened(entry.psrc2)
            )
          }
          val selectedIndex = effectiveEntries.indexWhere(entry =>
            entry.source1Ready && entry.source2Ready
          )
          val expectedIssueValid = selectedIndex >= 0
          assert(
            dut.io.issueValid.toBoolean == expectedIssueValid,
            s"cycle $cycle issueValid mismatch with ${entries.size} resident entries"
          )
          if (expectedIssueValid) {
            val expected = effectiveEntries(selectedIndex)
            assert(
              dut.io.issue.decoded.pc.toLong == expected.id,
              f"cycle $cycle selected PC 0x${dut.io.issue.decoded.pc.toLong}%08x != 0x${expected.id}%08x"
            )
            assert(dut.io.issue.pdst.toInt == expected.pdst, s"cycle $cycle pdst mismatch")
            assert(dut.io.issue.psrc1.toInt == expected.psrc1, s"cycle $cycle psrc1 mismatch")
            assert(dut.io.issue.psrc2.toInt == expected.psrc2, s"cycle $cycle psrc2 mismatch")
          }

          val enqueueFire = enqueueValid && dut.io.enqueueReady.toBoolean
          val dequeueFire = expectedIssueValid && issueReady
          val enqueued = ModelEntry(
            enqueueId,
            pdst,
            psrc1,
            psrc2,
            source1Ready || awakened(psrc1),
            source2Ready || awakened(psrc2)
          )

          dut.clockDomain.waitSampling()
          entries =
            if (dequeueFire) effectiveEntries.patch(selectedIndex, Nil, 1)
            else effectiveEntries
          if (enqueueFire) {
            entries :+= enqueued
            nextId += 1
          }
        }
      }
  }

  test("IQ retains only the decoded payload required by each fixed execution port") {
    val config = OooCoreConfig.FourIssueThreeCommit
    for (port <- 0 until config.executionWidth) {
      val capabilities = config.executionPorts(port).capabilities
      val hasBranch = capabilities.contains(ExecutionUnitKind.Branch)
      val hasDivide = capabilities.contains(ExecutionUnitKind.Divide)
      val hasSystem = capabilities.contains(ExecutionUnitKind.Csr)
      val hasMemory = capabilities.contains(ExecutionUnitKind.LoadStore)
      val fuType =
        if (hasMemory) 5
        else if (hasBranch) 1
        else if (hasDivide) 3
        else 4

      SimConfig.withVerilator
        .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq-port-payload")
        .compile(new IssueQueueProbe(config, port))
        .doSim(s"ooo-iq-port-$port-payload", 0x5100 + port) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut, config)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          dut.io.enqueueValid #= true
          dut.io.enqueue.decoded.fuType #= fuType
          dut.io.enqueue.decoded.pc #= 0x1c001234L
          dut.io.enqueue.decoded.immediate #= 0x12345678L
          dut.io.enqueue.decoded.source1IsPc #= true
          dut.io.enqueue.decoded.source2IsImmediate #= true
          dut.io.enqueue.decoded.source2IsFour #= true
          dut.io.enqueue.decoded.operation #= 0x123
          dut.io.enqueue.decoded.isBranch #= true
          dut.io.enqueue.decoded.branchKind #= 5
          dut.io.enqueue.decoded.predictedTaken #= true
          dut.io.enqueue.decoded.predictedTarget #= 0x1c00abcdL
          dut.io.enqueue.decoded.mulDivOperation #= 9
          dut.io.enqueue.decoded.mulDivSigned #= true
          dut.io.enqueue.decoded.rd #= 23
          dut.io.enqueue.decoded.csrAddress #= 0x456
          dut.io.enqueue.decoded.csrMask #= true
          dut.io.enqueue.decoded.resultFromCsr #= true
          dut.io.enqueue.decoded.systemOperation #= 17
          dut.io.enqueue.decoded.serializing #= true
          dut.io.enqueue.decoded.isLoad #= true
          dut.io.enqueue.decoded.isStore #= false
          dut.io.enqueue.decoded.memorySize #= 2
          dut.io.enqueue.decoded.memorySignExtend #= true
          dut.io.enqueue.decoded.isLl #= true
          dut.io.enqueue.decoded.isSc #= false
          dut.io.enqueue.decoded.exception.valid #= true
          dut.io.enqueue.decoded.exception.ecode #= 0x0d
          dut.io.enqueue.decoded.exception.esubcode #= 3
          dut.io.enqueue.decoded.exception.badVAddrValid #= true
          dut.io.enqueue.decoded.exception.badVAddr #= 0x87654321L
          dut.io.enqueue.decoded.exception.tlbRefill #= true
          dut.io.enqueue.pdst #= 41
          dut.io.enqueue.psrc1 #= 17
          dut.io.enqueue.psrc2 #= 19
          dut.io.enqueue.source1Ready #= true
          dut.io.enqueue.source2Ready #= true
          dut.io.enqueue.robPointer #= 9
          dut.io.enqueue.recoveryEpoch #= 0x5a
          dut.io.enqueue.loadQueueIndex #= 3
          dut.io.enqueue.storeQueueIndex #= 5
          dut.io.robHeadPointer #= 9
          sample(dut)
          dut.io.enqueueValid #= false
          // The LSU IQ has one registered output boundary.
          if (hasMemory) sample(dut)

          assert(dut.io.issueValid.toBoolean, s"port $port did not expose its resident uop")
          assert(dut.io.issue.pdst.toBigInt == 41)
          assert(dut.io.issue.psrc1.toBigInt == 17)
          assert(dut.io.issue.psrc2.toBigInt == 19)
          assert(dut.io.issue.robPointer.toBigInt == 9)
          assert(dut.io.issue.recoveryEpoch.toBigInt == 0x5a)

          if (hasMemory) {
            assert(dut.io.issue.decoded.immediate.toBigInt == 0x12345678L)
            assert(dut.io.issue.decoded.isLoad.toBoolean)
            assert(dut.io.issue.decoded.memorySize.toBigInt == 2)
            assert(dut.io.issue.decoded.memorySignExtend.toBoolean)
            assert(dut.io.issue.decoded.isLl.toBoolean)
            assert(dut.io.issue.decoded.pc.toBigInt == 0x1c001234L)
            assert(!dut.io.issue.decoded.exception.valid.toBoolean)
          } else {
            assert(dut.io.issue.decoded.pc.toBigInt == 0x1c001234L)
            assert(dut.io.issue.decoded.immediate.toBigInt == 0x12345678L)
            assert(dut.io.issue.decoded.operation.toBigInt == 0x123)
            assert(dut.io.issue.decoded.exception.valid.toBoolean)
            assert(dut.io.issue.decoded.exception.badVAddr.toBigInt == 0x87654321L)
            assert(!dut.io.issue.decoded.isLoad.toBoolean)
          }
          if (hasBranch) {
            assert(dut.io.issue.decoded.isBranch.toBoolean)
            assert(dut.io.issue.decoded.branchKind.toBigInt == 5)
            assert(dut.io.issue.decoded.predictedTarget.toBigInt == 0x1c00abcdL)
          } else {
            assert(!dut.io.issue.decoded.isBranch.toBoolean)
            assert(dut.io.issue.decoded.predictedTarget.toBigInt == 0)
          }
          if (hasDivide || capabilities.contains(ExecutionUnitKind.Multiply)) {
            assert(dut.io.issue.decoded.mulDivOperation.toBigInt == 9)
            assert(dut.io.issue.decoded.mulDivSigned.toBoolean)
          }
          if (hasSystem) {
            assert(dut.io.issue.decoded.rd.toBigInt == 23)
            assert(dut.io.issue.decoded.csrAddress.toBigInt == 0x456)
            assert(dut.io.issue.decoded.serializing.toBoolean)
          } else {
            assert(dut.io.issue.decoded.rd.toBigInt == 0)
            assert(!dut.io.issue.decoded.serializing.toBoolean)
          }
        }
    }
  }

  test("IQ removes the selected younger ready entry without duplicating it") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config))
      .doSim("ooo-iq-selective-compaction", 0x4951) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 0
        dut.io.enqueue.psrc1 #= 5
        dut.io.enqueue.psrc2 #= 0
        dut.io.enqueue.source1Ready #= false
        dut.io.enqueue.source2Ready #= true
        assert(dut.io.enqueueReady.toBoolean)
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 1)

        dut.io.enqueue.robPointer #= 1
        dut.io.enqueue.psrc1 #= 0
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 2)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 1)

        dut.io.enqueueValid #= false
        dut.io.issueReady #= true
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 1)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 5
        dut.io.issueReady #= false
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 0)
      }
  }

  test("IQ wakeup follows compacted age order after an older dequeue") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config))
      .doSim("ooo-iq-compacted-wakeup", 0x4956) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        // Put a ready oldest entry at the queue head.
        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 0
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        sample(dut)

        // Issue the head while enqueuing a blocked entry behind it.
        dut.io.issueReady #= true
        dut.io.enqueue.robPointer #= 1
        dut.io.enqueue.psrc1 #= 5
        dut.io.enqueue.source1Ready #= false
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 1)
        assert(!dut.io.issueValid.toBoolean)

        // Enqueue a younger blocked entry after the survivor was compacted to
        // the head. Waking the younger entry must not corrupt the older tag.
        dut.io.issueReady #= false
        dut.io.enqueue.robPointer #= 2
        dut.io.enqueue.psrc1 #= 6
        sample(dut)
        dut.io.enqueueValid #= false
        assert(dut.io.occupancy.toBigInt == 2)

        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 6
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 2)

        // Capture the wake pulse, then prove that the stored ready bit holds
        // after the tag disappears.
        sample(dut)
        dut.io.wakeupValid #= 0
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 2)

        dut.io.issueReady #= true
        sample(dut)
        dut.io.issueReady #= false
        assert(dut.io.occupancy.toBigInt == 1)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 5
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 1)
      }
  }

  test("serial IQ entries wait for the matching ROB head") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config))
      .doSim("ooo-iq-serial-head", 0x4952) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 3
        dut.io.enqueue.decoded.serializing #= true
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        dut.io.robHeadPointer #= 2
        sample(dut)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.enqueueValid #= false
        dut.io.robHeadPointer #= 3
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 3)
      }
  }

  test("registered IQ backpressure uses the reserved slot without overflowing") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config))
      .doSim("ooo-iq-registered-full-boundary", 0x4953) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.psrc1 #= 5
        dut.io.enqueue.psrc2 #= 6
        dut.io.enqueue.source1Ready #= false
        dut.io.enqueue.source2Ready #= false
        for (entry <- 0 until config.issueQueueEntriesPerPort) {
          dut.io.enqueue.robPointer #= entry
          sleep(1)
          assert(dut.io.enqueueReady.toBoolean)
          sample(dut)
        }

        assert(dut.io.occupancy.toBigInt == config.issueQueueEntriesPerPort)
        assert(!dut.io.enqueueReady.toBoolean)
        dut.io.enqueue.robPointer #= config.issueQueueEntriesPerPort
        sample(dut)
        assert(dut.io.occupancy.toBigInt == config.issueQueueEntriesPerPort)

        dut.io.enqueueValid #= false
        dut.io.flush #= true
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 0)
        dut.io.flush #= false
        sample(dut)
        assert(dut.io.enqueueReady.toBoolean)
      }
  }

  test("tokenized IQ holds captured source tags across backpressure and flush") {
    val config = OooCoreConfig.FourIssueThreeCommit
    val multiplyPort =
      config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Multiply))
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-ooo-iq-token-source-tags"
      )
      .compile(new IssueQueueProbe(config, multiplyPort, tokenizedIssueOutput = true))
      .doSim("ooo-iq-token-source-tags", 0x4972) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 3
        dut.io.enqueue.psrc1 #= 17
        dut.io.enqueue.psrc2 #= 29
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)

        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 3)
        assert(dut.io.issue.psrc1.toBigInt == 17)
        assert(dut.io.issue.psrc2.toBigInt == 29)

        // Exercise unrelated payload writes while the selected token is held.
        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 4
        dut.io.enqueue.psrc1 #= 41
        dut.io.enqueue.psrc2 #= 43
        for (_ <- 0 until 3) {
          sample(dut)
          assert(dut.io.issueValid.toBoolean)
          assert(dut.io.issue.robPointer.toBigInt == 3)
          assert(dut.io.issue.psrc1.toBigInt == 17)
          assert(dut.io.issue.psrc2.toBigInt == 29)
        }

        dut.io.flush #= true
        dut.io.issueReady #= true
        sample(dut)
        assert(!dut.io.issueValid.toBoolean)
        assert(dut.io.occupancy.toBigInt == 0)

        dut.io.flush #= false
        dut.io.issueReady #= false
        dut.io.enqueue.robPointer #= 7
        dut.io.enqueue.psrc1 #= 47
        dut.io.enqueue.psrc2 #= 53
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 7)
        assert(dut.io.issue.psrc1.toBigInt == 47)
        assert(dut.io.issue.psrc2.toBigInt == 53)
      }
  }

  test("LSU IQ registered output holds backpressure and sustains one issue per cycle") {
    val config = OooCoreConfig.FourIssueThreeCommit
    val loadStorePort =
      config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config, loadStorePort))
      .doSim("ooo-iq-lsu-registered-output", 0x4954) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        for (entry <- 0 until 3) {
          dut.io.enqueue.robPointer #= entry
          sample(dut)
        }
        dut.io.enqueueValid #= false

        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 0)
        assert(dut.io.occupancy.toBigInt == 3)

        dut.io.issueReady #= true
        for (entry <- 0 until 3) {
          sleep(1)
          assert(dut.io.issueValid.toBoolean)
          assert(dut.io.issue.robPointer.toBigInt == entry)
          sample(dut)
        }
        assert(!dut.io.issueValid.toBoolean)
        assert(dut.io.occupancy.toBigInt == 0)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 4
        dut.io.issueReady #= false
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(dut.io.issueValid.toBoolean)
        dut.io.flush #= true
        sample(dut)
        assert(!dut.io.issueValid.toBoolean)
        assert(dut.io.occupancy.toBigInt == 0)
      }
  }

  test("LSU IQ schedules a Store address without waiting for Store data") {
    val config = OooCoreConfig.FourIssueThreeCommit
    val loadStorePort =
      config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config, loadStorePort))
      .doSim("ooo-iq-store-address-data-decoupling", 0x4955) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.decoded.isStore #= true
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= false
        dut.io.enqueue.psrc2 #= 7
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(dut.io.issueValid.toBoolean)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        dut.io.enqueueValid #= true
        dut.io.enqueue.decoded.isStore #= false
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= false
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(!dut.io.issueValid.toBoolean)
      }
  }

  test("LSU IQ persists registered wake while only fast wake bypasses select") {
    val config = OooCoreConfig.FourIssueThreeCommit
    val loadStorePort =
      config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq-lsu-select-wake")
      .compile(new IssueQueueProbe(config, loadStorePort, separateSelectWakeup = true))
      .doSim("ooo-iq-lsu-select-wake", 0x4960) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        def enqueueBlocked(robPointer: Int, source: Int): Unit = {
          dut.io.enqueueValid #= true
          dut.io.enqueue.robPointer #= robPointer
          dut.io.enqueue.psrc1 #= source
          dut.io.enqueue.source1Ready #= false
          dut.io.enqueue.source2Ready #= true
          sample(dut)
          dut.io.enqueueValid #= false
          assert(!dut.io.issueValid.toBoolean)
        }

        enqueueBlocked(1, 5)
        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 5
        dut.io.selectWakeupValid #= 0
        sample(dut)
        dut.io.wakeupValid #= 0
        assert(!dut.io.issueValid.toBoolean)

        // The persistent wake is now stored. The registered LSU output sees it
        // on the following edge even though the completion tag has disappeared.
        sample(dut)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 1)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        enqueueBlocked(2, 6)

        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 6
        dut.io.selectWakeupValid #= 1
        dut.io.selectWakeupPdst(0) #= 6
        sample(dut)
        dut.io.wakeupValid #= 0
        dut.io.selectWakeupValid #= 0
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 2)
      }
  }

  test("flush hides simultaneous compact-queue payload mutations") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config))
      .doSim("ooo-iq-flush-payload-collision", 0x4957) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 1
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        sample(dut)
        assert(dut.io.issueValid.toBoolean)

        dut.io.flush #= true
        dut.io.issueReady #= true
        dut.io.enqueue.robPointer #= 2
        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 0
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 0)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.flush #= false
        dut.io.issueReady #= false
        dut.io.enqueueValid #= false
        dut.io.wakeupValid #= 0
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 0)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 3
        sample(dut)
        dut.io.enqueueValid #= false
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 3)
      }
  }

  test("flush hides simultaneous registered LSU output mutations") {
    val config = OooCoreConfig.FourIssueThreeCommit
    val loadStorePort =
      config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-iq")
      .compile(new IssueQueueProbe(config, loadStorePort))
      .doSim("ooo-iq-lsu-flush-output-collision", 0x4958) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 4
        dut.io.enqueue.source1Ready #= true
        dut.io.enqueue.source2Ready #= true
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(dut.io.issueValid.toBoolean)

        dut.io.flush #= true
        dut.io.issueReady #= true
        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 5
        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 0
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 0)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.flush #= false
        dut.io.issueReady #= false
        dut.io.enqueueValid #= false
        dut.io.wakeupValid #= 0
        sample(dut)
        assert(dut.io.occupancy.toBigInt == 0)
        assert(!dut.io.issueValid.toBoolean)

        dut.io.enqueueValid #= true
        dut.io.enqueue.robPointer #= 6
        sample(dut)
        dut.io.enqueueValid #= false
        sample(dut)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issue.robPointer.toBigInt == 6)
      }
  }
}
