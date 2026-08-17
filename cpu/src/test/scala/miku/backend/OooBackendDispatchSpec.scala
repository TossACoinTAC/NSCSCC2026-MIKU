package miku.backend

import miku.core._
import miku.frontend._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

private final class OooBackendDispatchProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.renameWidth bits)
    val pc = in Vec (UInt(config.xlen bits), config.renameWidth)
    val instruction = in Vec (Bits(32 bits), config.renameWidth)
    val renameReady = out Bits (config.renameWidth bits)
    val issueValid = out Bits (config.executionWidth bits)
    val issuePc = out Vec (UInt(config.xlen bits), config.executionWidth)
    val issuePdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.executionWidth)
    val issueRobPointer = out Vec (UInt(config.robPointerWidth bits), config.executionWidth)
    val issueRecoveryEpoch = out Vec (UInt(config.recoveryEpochWidth bits), config.executionWidth)
    val issueSource1 = out Vec (Bits(config.xlen bits), config.executionWidth)
    val issueSource2 = out Vec (Bits(config.xlen bits), config.executionWidth)
    val issueReady = in Bits (config.executionWidth bits)
    val completionValid = in Bits (config.writebackWidth bits)
    val completionLane = in UInt (log2Up(config.writebackWidth) bits)
    val completionRobPointer = in UInt (config.robPointerWidth bits)
    val completionPdst = in UInt (config.physicalRegIndexWidth bits)
    val completionWritesPdst = in Bool ()
    val completionData = in Bits (config.xlen bits)
    val directWakeupValid = in Bool ()
    val directWakeupPdst = in UInt (config.physicalRegIndexWidth bits)
    val loadWakeupValid = in Bool ()
    val loadWakeupPdst = in UInt (config.physicalRegIndexWidth bits)
    val loadWakeupRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val loadWakeupEpochCurrent = in Bool ()
    val fixedPortWakeupValid = in Bool ()
    val fixedPortWakeupPdst = in UInt (config.physicalRegIndexWidth bits)
    val multiplyForwardValid = in Bool ()
    val multiplyForwardPdst = in UInt (config.physicalRegIndexWidth bits)
    val multiplyForwardData = in Bits (config.xlen bits)
    val storeDataReady = in Bool ()
    val storeDataValid = out Bool ()
    val storeDataRobPointer = out UInt (config.robPointerWidth bits)
    val storeDataStoreQueueIndex = out UInt (config.storeQueueIndexWidth bits)
    val storeData = out Bits (config.xlen bits)
    val loadStoreIssueOccupancy = out UInt (log2Up(config.issueQueueEntriesPerPort + 1) bits)
    val storeDataOccupancy = out UInt (log2Up(config.storeQueueEntries + 1) bits)
    val memoryAllocateValid = out Bits (config.renameWidth bits)
    val memoryAllocateEpoch = out Vec (UInt(config.memoryEpochWidth bits), config.renameWidth)
    val committedMemoryEpoch = out UInt (config.memoryEpochWidth bits)
    val speculativeMemoryEpoch = out UInt (config.memoryEpochWidth bits)
    val flush = in Bool ()
  }
  noIoPrefix()

  val backend = new OooBackend(config)
  backend.io.predictorUpdateCapacity := U(
    config.commitWidth,
    log2Up(config.commitWidth + 1) bits
  )
  val decoders = Array.tabulate(config.renameWidth)(_ => new La32rDecoder(config))

  backend.io.renameValid := io.inputValid
  for (lane <- 0 until config.renameWidth) {
    val decoder = decoders(lane)
    decoder.io.pc := io.pc(lane)
    decoder.io.instruction := io.instruction(lane)
    decoder.io.fetchSlot := U(lane, config.fetchSlotWidth bits)
    decoder.io.predictedTaken := False
    decoder.io.predictedTarget := U(0, config.xlen bits)
    decoder.io.predictorMetadata := B(0, 16 bits)
    decoder.io.fetchException.valid := False
    decoder.io.fetchException.ecode := U(0, 6 bits)
    decoder.io.fetchException.esubcode := U(0, 9 bits)
    decoder.io.fetchException.badVAddrValid := False
    decoder.io.fetchException.badVAddr := U(0, config.xlen bits)
    decoder.io.fetchException.tlbRefill := False
    decoder.io.privilege := B(0, 2 bits)
    decoder.io.interruptPending := False
    backend.io.rename(lane) := decoder.io.decoded
  }

  backend.io.issueReady := io.issueReady
  backend.io.completionValid := io.completionValid
  backend.io.storeCompletionBypassValid := False
  backend.io.storeCompletionBypass.assignFromBits(
    B(0, backend.io.storeCompletionBypass.getBitsWidth bits)
  )
  backend.io.directWakeupValid := 0
  backend.io.directWakeupValid(0) := io.directWakeupValid
  private val multiplyPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Multiply))
  backend.io.directWakeupValid(multiplyPort) := io.fixedPortWakeupValid
  for (lane <- 0 until config.executionWidth) {
    if (lane == multiplyPort) {
      backend.io.directWakeupPdst(lane) := io.fixedPortWakeupPdst
    } else if (lane == 0) {
      backend.io.directWakeupPdst(lane) := io.directWakeupPdst
    } else {
      backend.io.directWakeupPdst(lane) := 0
    }
  }
  backend.io.loadWakeupValid := io.loadWakeupValid
  backend.io.loadWakeupPdst := io.loadWakeupPdst
  backend.io.loadWakeupRecoveryEpoch := io.loadWakeupRecoveryEpoch
  backend.io.loadWakeupEpochCurrent := io.loadWakeupEpochCurrent
  backend.io.resultForwardValid := io.multiplyForwardValid
  backend.io.resultForwardPdst := io.multiplyForwardPdst
  backend.io.resultForwardData := io.multiplyForwardData
  backend.io.storeDataReady := io.storeDataReady
  for (lane <- 0 until config.writebackWidth) {
    backend.io
      .completion(lane)
      .assignFromBits(
        B(0, backend.io.completion(lane).getBitsWidth bits)
      )
  }
  val completionPayload = Completion(config)
  completionPayload.robPointer := io.completionRobPointer
  completionPayload.recoveryEpoch := 0
  completionPayload.pdst := io.completionPdst
  completionPayload.writesPdst := io.completionWritesPdst
  completionPayload.data := io.completionData
  completionPayload.sideEffectData := 0
  completionPayload.exception.valid := False
  completionPayload.exception.ecode := 0
  completionPayload.exception.esubcode := 0
  completionPayload.exception.badVAddrValid := False
  completionPayload.exception.badVAddr := 0
  completionPayload.exception.tlbRefill := False
  completionPayload.branchResolved := False
  completionPayload.branchTaken := False
  completionPayload.branchTarget := 0
  completionPayload.branchMispredict := False
  backend.io.completion(io.completionLane) := completionPayload
  backend.io.releaseLoadValid := B(0, config.commitWidth bits)
  backend.io.releaseStoreValid := B(0, config.commitWidth bits)
  backend.io.debugReadAddress := 0
  backend.io.flush := io.flush

  io.renameReady := backend.io.renameReady
  io.issueValid := backend.io.issueValid
  io.storeDataValid := backend.io.storeDataValid
  io.storeDataRobPointer := backend.io.storeDataRobPointer
  io.storeDataStoreQueueIndex := backend.io.storeDataStoreQueueIndex
  io.storeData := backend.io.storeData
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  io.loadStoreIssueOccupancy := backend.io.loadStoreIssueOccupancy
  io.storeDataOccupancy := backend.io.storeDataOccupancy
  io.memoryAllocateValid := backend.io.memoryAllocateValid
  io.committedMemoryEpoch := backend.io.committedMemoryEpoch
  io.speculativeMemoryEpoch := backend.io.speculativeMemoryEpoch
  for (lane <- 0 until config.renameWidth) {
    io.memoryAllocateEpoch(lane) := backend.io.memoryAllocate(lane).memoryEpoch
  }
  for (port <- 0 until config.executionWidth) {
    io.issuePc(port) := backend.io.issue(port).decoded.pc
    io.issuePdst(port) := backend.io.issue(port).pdst
    io.issueRobPointer(port) := backend.io.issue(port).robPointer
    io.issueRecoveryEpoch(port) := backend.io.issue(port).recoveryEpoch
    io.issueSource1(port) := backend.io.issueSource1(port)
    io.issueSource2(port) := backend.io.issueSource2(port)
  }
}

class OooBackendDispatchSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))

  private def clearControl(dut: OooBackendDispatchProbe): Unit = {
    dut.io.inputValid #= 0
    dut.io.issueReady #= 0
    dut.io.completionValid #= 0
    dut.io.completionLane #= 0
    dut.io.completionRobPointer #= 0
    dut.io.completionPdst #= 0
    dut.io.completionWritesPdst #= false
    dut.io.completionData #= 0
    dut.io.directWakeupValid #= false
    dut.io.directWakeupPdst #= 0
    dut.io.loadWakeupValid #= false
    dut.io.loadWakeupPdst #= 0
    dut.io.loadWakeupRecoveryEpoch #= 0
    dut.io.loadWakeupEpochCurrent #= true
    dut.io.fixedPortWakeupValid #= false
    dut.io.fixedPortWakeupPdst #= 0
    dut.io.multiplyForwardValid #= false
    dut.io.multiplyForwardPdst #= 0
    dut.io.multiplyForwardData #= 0
    dut.io.storeDataReady #= true
    dut.io.flush #= false
    for (lane <- 0 until config.renameWidth) {
      dut.io.pc(lane) #= 0
      dut.io.instruction(lane) #= 0
    }
  }

  private def measureStoreDataWakeLatency(enableDirectWakeup: Boolean): Int = {
    val testConfig = config.copy(enableStoreDataDirectWakeup = enableDirectWakeup)
    var observedLatency = -1
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          s"/sim-workspace-ooo-store-data-direct-$enableDirectWakeup"
      )
      .compile(new OooBackendDispatchProbe(testConfig))
      .doSim(s"ooo-store-data-direct-$enableDirectWakeup", 0x4c61) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val storePc = producerPc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("0280040d", 16) // addi.w r13,r0,1
        dut.io.pc(1) #= storePc
        dut.io.instruction(1) #= BigInt("2980000d", 16) // st.w r13,r0,0
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var producerPdst = BigInt(0)
        var storeIssued = false
        for (_ <- 0 until 16 if producerPdst == 0 || !storeIssued) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until testConfig.executionWidth) {
            if ((mask & (BigInt(1) << port)) != 0) {
              if (dut.io.issuePc(port).toBigInt == producerPc) {
                producerPdst = dut.io.issuePdst(port).toBigInt
              }
              if (dut.io.issuePc(port).toBigInt == storePc) {
                storeIssued = true
              }
            }
          }
        }
        assert(producerPdst != 0)
        assert(storeIssued)
        assert(!dut.io.storeDataValid.toBoolean)

        val result = BigInt("89abcdef", 16)
        dut.io.directWakeupValid #= true
        dut.io.directWakeupPdst #= producerPdst
        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= 0
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= result
        dut.clockDomain.waitSampling()
        dut.io.directWakeupValid #= false
        dut.io.completionValid #= 0

        for (cycle <- 1 to 5 if observedLatency < 0) {
          sleep(1)
          if (dut.io.storeDataValid.toBoolean) {
            observedLatency = cycle
            assert(dut.io.storeData.toBigInt == result)
          } else {
            dut.clockDomain.waitSampling()
          }
        }
        assert(observedLatency > 0)
      }
    observedLatency
  }

  test("unused encoded source fields do not create rename dependencies") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-unused-source-normalization", 0x4c60) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val lu12iPc = producerPc + 4
        val addiPc = producerPc + 8
        dut.io.inputValid #= 7
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("2880000d", 16) // ld.w r13,r0,0
        dut.io.pc(1) #= lu12iPc
        // LU12I.W has no register source; immediate bits [4:0] occupy rj and encode 13 here.
        dut.io.instruction(1) #= BigInt("140001ae", 16) // lu12i.w r14,0xd
        dut.io.pc(2) #= addiPc
        // ADDI.W does not use rk; immediate bits [4:0] occupy rk and encode 13 here.
        dut.io.instruction(2) #= BigInt("0280340f", 16) // addi.w r15,r0,13
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        val issuedBeforeLoadCompletion = ArrayBuffer.empty[BigInt]
        var cycles = 0
        while (
          cycles < 16 &&
          (!issuedBeforeLoadCompletion.contains(lu12iPc) ||
            !issuedBeforeLoadCompletion.contains(addiPc))
        ) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((mask & (BigInt(1) << port)) != 0) {
              issuedBeforeLoadCompletion += dut.io.issuePc(port).toBigInt
            }
          }
          cycles += 1
        }

        assert(issuedBeforeLoadCompletion.contains(producerPc))
        assert(issuedBeforeLoadCompletion.contains(lu12iPc))
        assert(issuedBeforeLoadCompletion.contains(addiPc))
      }
  }

  test("ORN and ANDN preserve both register dependencies through rename") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-orn-andn-register-dependencies", 0x4c74) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        def addi(rd: Int, rj: Int, immediate: Int): BigInt =
          BigInt("02800000", 16) | ((immediate & 0xfff) << 10) | (rj << 5) | rd
        def registerAlu(op19To15: Int, rd: Int, rj: Int, rk: Int): BigInt =
          (BigInt(1) << 20) | (BigInt(op19To15) << 15) |
            (BigInt(rk) << 10) | (BigInt(rj) << 5) | rd

        val basePc = BigInt("1c000000", 16)
        val producerPcs = Vector(basePc, basePc + 4)
        val consumerPcs = Vector(basePc + 8, basePc + 12)
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPcs(0)
        dut.io.instruction(0) #= addi(13, 0, 1)
        dut.io.pc(1) #= producerPcs(1)
        dut.io.instruction(1) #= addi(14, 0, 2)
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        val producers = scala.collection.mutable.Map.empty[BigInt, (BigInt, BigInt, Int)]
        for (_ <- 0 until 16 if producers.size < 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            val pc = dut.io.issuePc(port).toBigInt
            if (
              (mask & (BigInt(1) << port)) != 0 &&
              producerPcs.contains(pc)
            ) {
              producers(pc) = (
                dut.io.issuePdst(port).toBigInt,
                dut.io.issueRobPointer(port).toBigInt,
                port
              )
            }
          }
        }
        assert(producers.size == 2)
        assert(producers.values.forall(_._1 != 0))

        dut.io.inputValid #= 3
        dut.io.pc(0) #= consumerPcs(0)
        dut.io.instruction(0) #= registerAlu(0x0c, 15, 13, 14) // orn r15,r13,r14
        dut.io.pc(1) #= consumerPcs(1)
        dut.io.instruction(1) #= registerAlu(0x0d, 16, 13, 14) // andn r16,r13,r14
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        def assertConsumersBlocked(): Unit = {
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          assert(
            !(0 until config.executionWidth).exists { port =>
              (mask & (BigInt(1) << port)) != 0 &&
              consumerPcs.contains(dut.io.issuePc(port).toBigInt)
            }
          )
        }
        for (_ <- 0 until 3) {
          assertConsumersBlocked()
          dut.clockDomain.waitSampling()
        }

        def completeProducer(pc: BigInt, data: BigInt): Unit = {
          val (pdst, robPointer, port) = producers(pc)
          dut.io.completionValid #= BigInt(1) << port
          dut.io.completionLane #= port
          dut.io.completionRobPointer #= robPointer
          dut.io.completionPdst #= pdst
          dut.io.completionWritesPdst #= true
          dut.io.completionData #= data
          dut.clockDomain.waitSampling()
          dut.io.completionValid #= 0
        }

        val source1 = BigInt("13579bdf", 16)
        val source2 = BigInt("2468ace0", 16)
        completeProducer(producerPcs(0), source1)
        for (_ <- 0 until 2) {
          assertConsumersBlocked()
          dut.clockDomain.waitSampling()
        }
        completeProducer(producerPcs(1), source2)

        val observed = scala.collection.mutable.Map.empty[BigInt, (BigInt, BigInt)]
        for (_ <- 0 until 16 if observed.size < 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            val pc = dut.io.issuePc(port).toBigInt
            if (
              (mask & (BigInt(1) << port)) != 0 &&
              consumerPcs.contains(pc)
            ) {
              observed(pc) = (
                dut.io.issueSource1(port).toBigInt,
                dut.io.issueSource2(port).toBigInt
              )
            }
          }
        }
        assert(observed.keySet == consumerPcs.toSet)
        assert(observed.values.forall(_ == (source1, source2)))
      }
  }

  test("memory epoch follows rename lane order, rollback, and eight-bit wrap") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))

    compiled.doSim("ooo-memory-epoch-lanes-rollback", 0x4c73) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      clearControl(dut)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()

      dut.io.inputValid #= 7
      dut.io.instruction(0) #= BigInt("28800000", 16)
      dut.io.instruction(1) #= BigInt("38720000", 16)
      dut.io.instruction(2) #= BigInt("28800000", 16)
      sleep(1)
      assert(dut.io.memoryAllocateValid.toBigInt == 5)
      assert(dut.io.memoryAllocateEpoch(0).toBigInt == 0)
      assert(dut.io.memoryAllocateEpoch(2).toBigInt == 1)
      dut.clockDomain.waitSampling()
      dut.io.inputValid #= 0
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 1)
      assert(dut.io.committedMemoryEpoch.toBigInt == 0)

      dut.io.flush #= true
      dut.clockDomain.waitSampling()
      dut.io.flush #= false
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 0)

      dut.io.inputValid #= 7
      dut.io.instruction(0) #= BigInt("28800000", 16)
      dut.io.instruction(1) #= BigInt("06000001", 16)
      dut.io.instruction(2) #= BigInt("28800000", 16)
      sleep(1)
      assert(dut.io.memoryAllocateValid.toBigInt == 5)
      assert(dut.io.memoryAllocateEpoch(0).toBigInt == 0)
      assert(dut.io.memoryAllocateEpoch(2).toBigInt == 1)
      dut.clockDomain.waitSampling()
      dut.io.inputValid #= 0
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 1)

      dut.io.flush #= true
      dut.clockDomain.waitSampling()
      dut.io.flush #= false
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 0)

      dut.io.inputValid #= 7
      dut.io.instruction(0) #= BigInt("00100000", 16)
      dut.io.instruction(1) #= BigInt("28800000", 16)
      dut.io.instruction(2) #= BigInt("38720000", 16)
      sleep(1)
      assert(dut.io.memoryAllocateValid.toBigInt == 2)
      assert(dut.io.memoryAllocateEpoch(1).toBigInt == 0)
      dut.clockDomain.waitSampling()
      dut.io.inputValid #= 0
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 1)

      dut.io.flush #= true
      dut.clockDomain.waitSampling()
      dut.io.flush #= false
      sleep(1)
      assert(dut.io.speculativeMemoryEpoch.toBigInt == 0)

      dut.io.inputValid #= 7
      dut.io.instruction(0) #= BigInt("38720000", 16)
      dut.io.instruction(1) #= BigInt("38728000", 16)
      dut.io.instruction(2) #= BigInt("28800000", 16)
      sleep(1)
      assert(dut.io.memoryAllocateValid.toBigInt == 4)
      assert(dut.io.memoryAllocateEpoch(2).toBigInt == 2)
    }

    compiled.doSim("ooo-memory-epoch-wrap", 0x4c74) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      clearControl(dut)
      dut.io.issueReady #= 0xf
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.deassertReset()

      for (iteration <- 0 until 256) {
        dut.io.inputValid #= 1
        dut.io.instruction(0) #= BigInt("38720000", 16)
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= (iteration % (config.robEntries * 2))
        dut.io.completionPdst #= 0
        dut.io.completionWritesPdst #= false
        dut.clockDomain.waitSampling()
        dut.io.completionValid #= 0

        val expected = (iteration + 1) & 0xff
        var waitCycles = 0
        while (dut.io.committedMemoryEpoch.toBigInt != expected && waitCycles < 12) {
          dut.clockDomain.waitSampling()
          sleep(1)
          waitCycles += 1
        }
        assert(dut.io.committedMemoryEpoch.toBigInt == expected)
        assert(dut.io.speculativeMemoryEpoch.toBigInt == expected)
      }
    }
  }

  test("rename dispatch queue sustains three independent ALU issues without loss") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-dispatch-throughput", 0x4c42) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        val observedPc = ArrayBuffer.empty[BigInt]
        val observedRob = ArrayBuffer.empty[BigInt]
        var threeIssueCycles = 0

        def sampleAndCapture(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
          var issuedThisCycle = 0
          val issueMask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((issueMask & (BigInt(1) << port)) != 0) {
              observedPc += dut.io.issuePc(port).toBigInt
              observedRob += dut.io.issueRobPointer(port).toBigInt
              assert(dut.io.issueSource1(port).toBigInt == 0)
              assert(dut.io.issueSource2(port).toBigInt == 0)
              issuedThisCycle += 1
            }
          }
          if (issuedThisCycle == 3) threeIssueCycles += 1
        }

        sampleAndCapture()
        val basePc = BigInt("1c000000", 16)
        val expectedPc = (0 until 9).map(index => basePc + index * 4)
        for (group <- 0 until 3) {
          dut.io.inputValid #= 7
          for (lane <- 0 until config.renameWidth) {
            val index = group * config.renameWidth + lane
            dut.io.pc(lane) #= expectedPc(index)
            dut.io.instruction(lane) #= (BigInt("00100000", 16) | (index + 1))
          }
          sleep(1)
          assert(dut.io.renameReady.toBigInt == 7)
          sampleAndCapture()
        }
        dut.io.inputValid #= 0

        var drainCycles = 0
        while (observedPc.size < expectedPc.size && drainCycles < 20) {
          sampleAndCapture()
          drainCycles += 1
        }

        assert(observedPc.size == expectedPc.size)
        assert(observedPc.distinct.size == expectedPc.size)
        assert(observedPc.toSet == expectedPc.toSet)
        assert(observedRob.toSet == (0 until 9).map(BigInt(_)).toSet)
        assert(threeIssueCycles >= 2)
      }
  }

  test("rename oldest fallback admits lane zero when a complete group is blocked") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-backend-dispatch")
      .compile(
        new OooBackendDispatchProbe(
          config.copy(enableRenameOldestFallback = true, enableRenameTwoWideFallback = false)
        )
      )
      .doSim("ooo-backend-rename-oldest-fallback", 0x4cA1) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        // Keep execution stopped until the rename-side queues expose a real
        // resource boundary; the test must observe partial acceptance rather
        // than merely a decode bubble.
        dut.io.issueReady #= 0
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c100000", 16)
        var partialObserved = false
        var cycles = 0
        while (!partialObserved && cycles < 64) {
          dut.io.inputValid #= 7
          for (lane <- 0 until config.renameWidth) {
            dut.io.pc(lane) #= basePc + (cycles * config.renameWidth + lane) * 4
            dut.io.instruction(lane) #= (BigInt("02800000", 16) | (lane + 1))
          }
          sleep(1)
          val ready = dut.io.renameReady.toBigInt
          if (ready == 1) {
            partialObserved = true
          }
          dut.clockDomain.waitSampling()
          cycles += 1
        }
        assert(partialObserved, s"no oldest-lane fallback observed after $cycles cycles")
        dut.io.inputValid #= 0
      }
  }

  test("rename admits a two-uop prefix when only the youngest lane is resource-blocked") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-backend-dispatch")
      .compile(
        new OooBackendDispatchProbe(
          config.copy(enableRenameOldestFallback = true, enableRenameTwoWideFallback = true)
        )
      )
      .doSim("ooo-backend-rename-two-wide-fallback", 0x4cA2) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        // Stop execution so the dispatch queue creates the resource boundary.
        dut.io.issueReady #= 0
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c200000", 16)
        var twoWideObserved = false
        var cycles = 0
        while (!twoWideObserved && cycles < 64) {
          dut.io.inputValid #= 7
          for (lane <- 0 until config.renameWidth) {
            dut.io.pc(lane) #= basePc + (cycles * config.renameWidth + lane) * 4
            dut.io.instruction(lane) #= (BigInt("02800000", 16) | (lane + 1))
          }
          sleep(1)
          twoWideObserved = twoWideObserved || dut.io.renameReady.toBigInt == 3
          dut.clockDomain.waitSampling()
          cycles += 1
        }
        assert(twoWideObserved, s"no two-lane fallback observed after $cycles cycles")
        dut.io.inputValid #= 0
      }
  }

  test("registered LSU address buffering preserves ordered issues across backpressure") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-lsu-address-buffer", 0x4c45) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0x7
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c000000", 16)
        val expectedPc = (0 until 5).map(index => basePc + index * 4)
        for (index <- expectedPc.indices) {
          dut.io.inputValid #= 1
          dut.io.pc(0) #= expectedPc(index)
          // ld.w r(index + 1),r0,0
          dut.io.instruction(0) #= (BigInt("28800000", 16) | (index + 1))
          sleep(1)
          assert(dut.io.renameReady.toBigInt == 7)
          dut.clockDomain.waitSampling()
        }
        dut.io.inputValid #= 0
        dut.clockDomain.waitSampling(3)
        sleep(1)
        assert((dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) != 0)
        assert(dut.io.issuePc(loadStorePort).toBigInt == expectedPc.head)

        val observedPc = ArrayBuffer(expectedPc.head)
        dut.io.issueReady #= 0xf
        var cycles = 0
        while (observedPc.size < expectedPc.size && cycles < 16) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if ((dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) != 0) {
            observedPc += dut.io.issuePc(loadStorePort).toBigInt
          }
          cycles += 1
        }

        assert(observedPc == expectedPc)
    }
  }

  test("ordinary issue address buffering holds payload and sustains one issue per cycle") {
    for (tokenizedOutput <- Seq(false, true)) {
      val testConfig = config.copy(enableTokenizedOrdinaryIssueOutput = tokenizedOutput)
      val multiplyPort = testConfig.executionPorts.indexWhere(
        _.capabilities.contains(ExecutionUnitKind.Multiply)
      )
      SimConfig.withVerilator
        .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          s"/sim-workspace-ooo-backend-ordinary-output-$tokenizedOutput")
        .compile(new OooBackendDispatchProbe(testConfig))
        .doSim(s"ooo-backend-ordinary-address-buffer-$tokenizedOutput", 0x4c7c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf & ~(1 << multiplyPort)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c000000", 16)
        val expectedPc = (0 until 4).map(index => basePc + index * 4)
        for (index <- expectedPc.indices) {
          dut.io.inputValid #= 1
          dut.io.pc(0) #= expectedPc(index)
          dut.io.instruction(0) #= (BigInt("001c0000", 16) | (index + 1)) // mul.w rd,r0,r0
          sleep(1)
          assert(dut.io.renameReady.toBigInt == 7)
          dut.clockDomain.waitSampling()
        }
        dut.io.inputValid #= 0

        var waitCycles = 0
        while (
          (dut.io.issueValid.toBigInt & (BigInt(1) << multiplyPort)) == 0 && waitCycles < 12
        ) {
          dut.clockDomain.waitSampling()
          sleep(1)
          waitCycles += 1
        }
        assert((dut.io.issueValid.toBigInt & (BigInt(1) << multiplyPort)) != 0)
        assert(dut.io.issuePc(multiplyPort).toBigInt == expectedPc.head)
        val heldPdst = dut.io.issuePdst(multiplyPort).toBigInt
        for (_ <- 0 until 4) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert((dut.io.issueValid.toBigInt & (BigInt(1) << multiplyPort)) != 0)
          assert(dut.io.issuePc(multiplyPort).toBigInt == expectedPc.head)
          assert(dut.io.issuePdst(multiplyPort).toBigInt == heldPdst)
        }

        val observedPc = ArrayBuffer(expectedPc.head)
        dut.io.issueReady #= 0xf
        var cycles = 0
        while (observedPc.size < expectedPc.size && cycles < 16) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if ((dut.io.issueValid.toBigInt & (BigInt(1) << multiplyPort)) != 0) {
            observedPc += dut.io.issuePc(multiplyPort).toBigInt
          }
          cycles += 1
        }

          assert(observedPc == expectedPc)
        }
    }
  }

  test("a Store address issues before its data dependency reaches writeback") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-store-address-data-decoupling", 0x4c49) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val storePc = producerPc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("2880000d", 16) // ld.w r13,r0,0
        dut.io.pc(1) #= storePc
        dut.io.instruction(1) #= BigInt("2980000d", 16) // st.w r13,r0,0
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var producerPdst = BigInt(0)
        var storeIssued = false
        for (_ <- 0 until 16 if !storeIssued) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((mask & (BigInt(1) << port)) != 0) {
              if (dut.io.issuePc(port).toBigInt == producerPc) {
                producerPdst = dut.io.issuePdst(port).toBigInt
              }
              if (dut.io.issuePc(port).toBigInt == storePc) {
                storeIssued = true
              }
            }
          }
        }
        assert(producerPdst != 0)
        assert(storeIssued)
        assert(!dut.io.storeDataValid.toBoolean)

        val loadResult = BigInt("89abcdef", 16)
        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= 0
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= loadResult
        dut.clockDomain.waitSampling()
        dut.io.completionValid #= 0

        var dataSeen = false
        for (_ <- 0 until 8 if !dataSeen) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if (dut.io.storeDataValid.toBoolean) {
            dataSeen = true
          }
        }
        assert(dataSeen)
        assert(dut.io.storeDataRobPointer.toBigInt == 1)
        assert(dut.io.storeDataStoreQueueIndex.toBigInt == 0)
        assert(dut.io.storeData.toBigInt == loadResult)
      }
  }

  test("Store data direct wake reaches the PRF boundary one cycle earlier") {
    val legacyLatency = measureStoreDataWakeLatency(enableDirectWakeup = false)
    val directLatency = measureStoreDataWakeLatency(enableDirectWakeup = true)
    assert(legacyLatency == 2)
    assert(directLatency == 1)
  }

  test("Store dispatch remains atomic when the address IQ is full") {
    val asymmetricConfig = config.copy(loadQueueEntries = 16, storeQueueEntries = 16)
    val asymmetricLoadStorePort = asymmetricConfig.executionPorts
      .indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(asymmetricConfig))
      .doSim("ooo-backend-store-atomic-dispatch", 0x4c5c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.storeDataReady #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c001000", 16)
        val expectedPc = (0 until 12).map(index => basePc + index * 4)
        for (index <- expectedPc.indices) {
          dut.io.inputValid #= 1
          dut.io.pc(0) #= expectedPc(index)
          dut.io.instruction(0) #= BigInt("29800000", 16) // st.w r0,r0,0
          sleep(1)
          assert((dut.io.renameReady.toBigInt & 1) != 0)
          dut.clockDomain.waitSampling()
        }
        dut.io.inputValid #= 0
        dut.clockDomain.waitSampling(8)
        sleep(1)

        // Eleven unique Stores occupy the address IQ plus its operand holding
        // stage. Later Stores remain in dispatch and must not be copied into
        // the still-ready data IQ while the address side is backpressured.
        assert(dut.io.loadStoreIssueOccupancy.toBigInt == 10)
        assert(
          (dut.io.issueValid.toBigInt & (BigInt(1) << asymmetricLoadStorePort)) != 0
        )
        assert(dut.io.storeDataOccupancy.toBigInt == 11)

        val observedPc = ArrayBuffer(dut.io.issuePc(asymmetricLoadStorePort).toBigInt)
        dut.io.issueReady #= BigInt(1) << asymmetricLoadStorePort
        var cycles = 0
        while (observedPc.size < expectedPc.size && cycles < 32) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if ((dut.io.issueValid.toBigInt & (BigInt(1) << asymmetricLoadStorePort)) != 0) {
            observedPc += dut.io.issuePc(asymmetricLoadStorePort).toBigInt
          }
          cycles += 1
        }
        assert(observedPc == expectedPc)
      }
  }

  test("an accepted Store is not replayed when the next LSU read port conflicts") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-store-consume-with-read-conflict", 0x4c5d) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.storeDataReady #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val storePc = BigInt("1c0193e0", 16)
        val loadPc = storePc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= storePc
        dut.io.instruction(0) #= BigInt("299db0a5", 16) // st.w r5,r5,1900
        dut.io.pc(1) #= loadPc
        dut.io.instruction(1) #= BigInt("289db18a", 16) // ld.w r10,r12,1900
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var waitCycles = 0
        while (
          (!(dut.io.storeDataValid.toBoolean &&
            (dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) != 0 &&
            dut.io.issuePc(loadStorePort).toBigInt == storePc)) && waitCycles < 16
        ) {
          dut.clockDomain.waitSampling()
          sleep(1)
          waitCycles += 1
        }
        assert(dut.io.storeDataValid.toBoolean)
        assert((dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) != 0)
        assert(dut.io.issuePc(loadStorePort).toBigInt == storePc)

        dut.io.issueReady #= BigInt(1) << loadStorePort
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(
          (dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) == 0 ||
            dut.io.issuePc(loadStorePort).toBigInt != storePc
        )

        dut.io.storeDataReady #= true
        for (_ <- 0 until 8) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(
            (dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) == 0 ||
              dut.io.issuePc(loadStorePort).toBigInt != storePc
          )
        }
      }
  }

  test("raw ALU completion wakes a dependant into the registered PRF bypass") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-early-alu-wakeup", 0x4c47) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val consumerPc = producerPc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("0280040d", 16) // addi.w r13,r0,1
        dut.io.pc(1) #= consumerPc
        dut.io.instruction(1) #= BigInt("028005ae", 16) // addi.w r14,r13,1
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var producerPdst = BigInt(0)
        var producerSeen = false
        var cycles = 0
        while (!producerSeen && cycles < 16) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if (
              (mask & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == producerPc
            ) {
              producerSeen = true
              producerPdst = dut.io.issuePdst(port).toBigInt
            }
          }
          cycles += 1
        }
        assert(producerSeen)
        assert(producerPdst != 0)

        val result = BigInt("12345678", 16)
        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= 0
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= result
        dut.io.directWakeupValid #= true
        dut.io.directWakeupPdst #= producerPdst

        dut.clockDomain.waitSampling()
        sleep(1)
        assert(
          !(0 until config.executionWidth).exists { port =>
            (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
            dut.io.issuePc(port).toBigInt == consumerPc
          }
        )
        dut.io.completionValid #= 0
        dut.io.directWakeupValid #= false
        // The PRF write happens on the following ROB-qualified edge. Changing the unaccepted
        // completion tuple must not split the destination from the data captured on the prior edge.
        val poisonPdst =
          if (producerPdst + 1 < config.physicalRegs) producerPdst + 1 else BigInt(1)
        dut.io.completionPdst #= poisonPdst
        dut.io.completionData #= BigInt("deadbeef", 16)

        dut.clockDomain.waitSampling()
        sleep(1)
        val consumerPort = (0 until config.executionWidth).find { port =>
          (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
          dut.io.issuePc(port).toBigInt == consumerPc
        }
        assert(consumerPort.nonEmpty)
        assert(dut.io.issueSource1(consumerPort.get).toBigInt == result)
      }
  }

  test("LSU select keeps direct wake latency while deferring registered-only wake") {
    def measure(testConfig: OooCoreConfig, label: String, direct: Boolean, seed: Int): Int = {
      var consumerIssueCycle = -1
      SimConfig.withVerilator
        .workspacePath(
          s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-backend-lsu-select-$label"
        )
        .compile(new OooBackendDispatchProbe(testConfig))
        .doSim(s"ooo-backend-lsu-select-$label", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearControl(dut)
          dut.io.issueReady #= 0xf
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()

          val producerPc = BigInt("1c000000", 16)
          val consumerPc = producerPc + 4
          dut.io.inputValid #= 3
          dut.io.pc(0) #= producerPc
          dut.io.instruction(0) #= BigInt("0280040d", 16) // addi.w r13,r0,1
          dut.io.pc(1) #= consumerPc
          dut.io.instruction(1) #= BigInt("288001ae", 16) // ld.w r14,r13,0
          dut.clockDomain.waitSampling()
          dut.io.inputValid #= 0

          var producerPdst = BigInt(0)
          var producerRobPointer = BigInt(0)
          for (_ <- 0 until 16 if producerPdst == 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            val producerPort = (0 until testConfig.executionWidth).find { port =>
              (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == producerPc
            }
            producerPort.foreach { port =>
              producerPdst = dut.io.issuePdst(port).toBigInt
              producerRobPointer = dut.io.issueRobPointer(port).toBigInt
            }
          }
          assert(producerPdst != 0)
          assert(dut.io.loadStoreIssueOccupancy.toBigInt != 0)

          val result = BigInt("12345000", 16)
          dut.io.completionValid #= 1
          dut.io.completionLane #= 0
          dut.io.completionRobPointer #= producerRobPointer
          dut.io.completionPdst #= producerPdst
          dut.io.completionWritesPdst #= true
          dut.io.completionData #= result
          dut.io.directWakeupValid #= direct
          dut.io.directWakeupPdst #= producerPdst
          dut.clockDomain.waitSampling()
          dut.io.completionValid #= 0
          dut.io.directWakeupValid #= false

          for (cycle <- 1 to 6 if consumerIssueCycle < 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            if (
              (dut.io.issueValid.toBigInt & (BigInt(1) << loadStorePort)) != 0 &&
              dut.io.issuePc(loadStorePort).toBigInt == consumerPc
            ) {
              consumerIssueCycle = cycle
              assert(dut.io.issueSource1(loadStorePort).toBigInt == result)
            }
          }
          assert(consumerIssueCycle > 0)
        }
      consumerIssueCycle
    }

    val legacyConfig = config.copy(enableLsuRegisteredWakeSelectDecoupling = false)
    val decoupledConfig = config.copy(enableLsuRegisteredWakeSelectDecoupling = true)
    val legacyDirect = measure(legacyConfig, "legacy-direct", direct = true, 0x4c80)
    val decoupledDirect = measure(decoupledConfig, "decoupled-direct", direct = true, 0x4c81)
    val legacyRegistered = measure(legacyConfig, "legacy-registered", direct = false, 0x4c82)
    val decoupledRegistered =
      measure(decoupledConfig, "decoupled-registered", direct = false, 0x4c83)

    assert(decoupledDirect == legacyDirect)
    assert(decoupledRegistered == legacyRegistered + 1)
  }

  test("ordinary IQ select keeps source readiness while deferring registered-only wake") {
    def measure(testConfig: OooCoreConfig, label: String, seed: Int): Int = {
      var consumerIssueCycle = -1
      SimConfig.withVerilator
        .workspacePath(
          s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-backend-ordinary-select-$label"
        )
        .compile(new OooBackendDispatchProbe(testConfig))
        .doSim(s"ooo-backend-ordinary-select-$label", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearControl(dut)
          dut.io.issueReady #= 0xf
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()

          val producerPc = BigInt("1c000000", 16)
          val consumerPc = producerPc + 4
          dut.io.inputValid #= 3
          dut.io.pc(0) #= producerPc
          dut.io.instruction(0) #= BigInt("0280040d", 16) // addi.w r13,r0,1
          dut.io.pc(1) #= consumerPc
          dut.io.instruction(1) #= BigInt("028005ae", 16) // addi.w r14,r13,1
          dut.clockDomain.waitSampling()
          dut.io.inputValid #= 0

          var producerPdst = BigInt(0)
          var producerRobPointer = BigInt(0)
          for (_ <- 0 until 16 if producerPdst == 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            val producerPort = (0 until testConfig.executionWidth).find { port =>
              (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
                dut.io.issuePc(port).toBigInt == producerPc
            }
            producerPort.foreach { port =>
              producerPdst = dut.io.issuePdst(port).toBigInt
              producerRobPointer = dut.io.issueRobPointer(port).toBigInt
            }
          }
          assert(producerPdst != 0)

          val result = BigInt("2468ace0", 16)
          // Lane 1 models a registered-only completion (the ALU/Divide port), with no direct
          // wake.  The consumer is resident in an ordinary IQ before this event arrives.
          dut.io.completionValid #= 1 << 1
          dut.io.completionLane #= 1
          dut.io.completionRobPointer #= producerRobPointer
          dut.io.completionPdst #= producerPdst
          dut.io.completionWritesPdst #= true
          dut.io.completionData #= result
          dut.clockDomain.waitSampling()
          dut.io.completionValid #= 0

          for (cycle <- 1 to 8 if consumerIssueCycle < 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            if ((dut.io.issueValid.toBigInt & 0x7) != 0) {
              val port = (0 until testConfig.executionWidth).find { candidate =>
                (dut.io.issueValid.toBigInt & (BigInt(1) << candidate)) != 0 &&
                  dut.io.issuePc(candidate).toBigInt == consumerPc
              }
              port.foreach { selected =>
                consumerIssueCycle = cycle
                assert(dut.io.issueSource1(selected).toBigInt == result)
              }
            }
          }
          assert(consumerIssueCycle > 0)
        }
      consumerIssueCycle
    }

    val legacyConfig = config.copy(enableOrdinaryRegisteredWakeSelectDecoupling = false)
    val decoupledConfig = config.copy(enableOrdinaryRegisteredWakeSelectDecoupling = true)
    val legacy = measure(legacyConfig, "legacy", 0x4c84)
    val decoupled = measure(decoupledConfig, "decoupled", 0x4c85)
    assert(decoupled == legacy + 1)
  }

  test("a direct-only completion port keeps resident and later consumers live") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-direct-only-echo")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-direct-only-port-echo", 0x4c73) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        def addi(rd: Int, rj: Int, immediate: Int): BigInt =
          BigInt("02800000", 16) | ((immediate & 0xfff) << 10) | (rj << 5) | rd

        val basePc = BigInt("1c000000", 16)
        val producerPcs = Vector.tabulate(3)(lane => basePc + lane * 4)
        val producerRegisters = Vector(13, 15, 17)
        val residentConsumerPcs = Vector.tabulate(3)(lane => basePc + 0x10 + lane * 4)

        // Three independent ALUs occupy ports 0/1/2.  Their consumers enter behind them, so the
        // r17 consumer is resident when the direct-only port-2 producer broadcasts its tag.
        dut.io.inputValid #= 7
        for (lane <- 0 until 3) {
          dut.io.pc(lane) #= producerPcs(lane)
          dut.io.instruction(lane) #= addi(producerRegisters(lane), 0, lane + 1)
        }
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 7
        for (lane <- 0 until 3) {
          dut.io.pc(lane) #= residentConsumerPcs(lane)
          dut.io.instruction(lane) #=
            addi(producerRegisters(lane) + 1, producerRegisters(lane), 1)
        }
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        val directPort = config.executionPorts.indexWhere(port =>
          port.capabilities.contains(ExecutionUnitKind.Multiply)
        )
        var producerPdst = BigInt(0)
        var producerRobPointer = BigInt(0)
        for (_ <- 0 until 16 if producerPdst == 0) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if (
            (dut.io.issueValid.toBigInt & (BigInt(1) << directPort)) != 0 &&
            dut.io.issuePc(directPort).toBigInt == producerPcs(2)
          ) {
            producerPdst = dut.io.issuePdst(directPort).toBigInt
            producerRobPointer = dut.io.issueRobPointer(directPort).toBigInt
          }
        }
        assert(producerPdst != 0)

        val laterConsumerPc = basePc + 0x20
        val observed = scala.collection.mutable.Map.empty[BigInt, BigInt]
        def observeConsumers(): Unit = {
          for (port <- 0 until config.executionWidth) {
            if ((dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0) {
              val pc = dut.io.issuePc(port).toBigInt
              if (pc == residentConsumerPcs(2) || pc == laterConsumerPc) {
                observed(pc) = dut.io.issueSource1(port).toBigInt
              }
            }
          }
        }

        val result = BigInt("13579bdf", 16)
        dut.io.completionValid #= BigInt(1) << directPort
        dut.io.completionLane #= directPort
        dut.io.completionRobPointer #= producerRobPointer
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= result
        dut.io.fixedPortWakeupValid #= true
        dut.io.fixedPortWakeupPdst #= producerPdst
        dut.clockDomain.waitSampling()
        sleep(1)
        observeConsumers()
        dut.io.completionValid #= 0
        dut.io.fixedPortWakeupValid #= false

        // This consumer starts after the direct event.  It must use the raw registered completion
        // through RenameMap/dispatch qualification even though that echo is absent from every IQ.
        dut.io.inputValid #= 1
        dut.io.pc(0) #= laterConsumerPc
        dut.io.instruction(0) #= addi(20, producerRegisters(2), 1)
        dut.clockDomain.waitSampling()
        sleep(1)
        observeConsumers()
        dut.io.inputValid #= 0

        for (_ <- 0 until 16 if observed.size < 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          observeConsumers()
        }
        assert(observed(residentConsumerPcs(2)) == result)
        assert(observed(laterConsumerPc) == result)
      }
  }

  test("a registered writeback wake takes priority over a younger direct wake") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-writeback-wakeup-priority", 0x4c5e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c000000", 16)
        val oldProducerPc = basePc
        val oldConsumerPc = basePc + 4
        val directProducerPc = basePc + 8
        val directConsumerPc = basePc + 12

        dut.io.inputValid #= 7
        dut.io.pc(0) #= oldProducerPc
        dut.io.instruction(0) #= BigInt("2880000d", 16) // ld.w r13,r0,0
        dut.io.pc(1) #= oldConsumerPc
        dut.io.instruction(1) #= BigInt("028005ae", 16) // addi.w r14,r13,1
        dut.io.pc(2) #= directProducerPc
        dut.io.instruction(2) #= BigInt("0280080f", 16) // addi.w r15,r0,2
        dut.clockDomain.waitSampling()

        dut.io.inputValid #= 1
        dut.io.pc(0) #= directConsumerPc
        dut.io.instruction(0) #= BigInt("028005f0", 16) // addi.w r16,r15,1
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var oldPdst = BigInt(0)
        var oldRobPointer = BigInt(0)
        var directPdst = BigInt(0)
        var directRobPointer = BigInt(0)
        for (_ <- 0 until 16 if oldPdst == 0 || directPdst == 0) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((mask & (BigInt(1) << port)) != 0) {
              val pc = dut.io.issuePc(port).toBigInt
              if (pc == oldProducerPc) {
                oldPdst = dut.io.issuePdst(port).toBigInt
                oldRobPointer = dut.io.issueRobPointer(port).toBigInt
              }
              if (pc == directProducerPc) {
                directPdst = dut.io.issuePdst(port).toBigInt
                directRobPointer = dut.io.issueRobPointer(port).toBigInt
              }
            }
          }
        }
        assert(oldPdst != 0)
        assert(directPdst != 0)

        val oldResult = BigInt("11223344", 16)
        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= oldRobPointer
        dut.io.completionPdst #= oldPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= oldResult
        dut.clockDomain.waitSampling()

        val directResult = BigInt("55667788", 16)
        dut.io.completionRobPointer #= directRobPointer
        dut.io.completionPdst #= directPdst
        dut.io.completionData #= directResult
        dut.io.directWakeupValid #= true
        dut.io.directWakeupPdst #= directPdst
        dut.clockDomain.waitSampling()
        dut.io.completionValid #= 0
        dut.io.directWakeupValid #= false

        val observed = scala.collection.mutable.Map.empty[BigInt, BigInt]
        for (_ <- 0 until 16 if observed.size < 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((mask & (BigInt(1) << port)) != 0) {
              val pc = dut.io.issuePc(port).toBigInt
              if (pc == oldConsumerPc || pc == directConsumerPc) {
                observed(pc) = dut.io.issueSource1(port).toBigInt
              }
            }
          }
        }
        assert(observed(oldConsumerPc) == oldResult)
        assert(observed(directConsumerPc) == directResult)
      }
  }

  test("a successfully broadcast direct wake does not cover the next direct tag") {
    for ((suppressEcho, name, seed) <- Seq(
        (false, "legacy", 0x4c61),
        (true, "suppress-echo", 0x4c62)
      )) {
      val testConfig = config.copy(
        enableDirectWakeupEchoSuppression = suppressEcho,
        enableOrdinaryRegisteredWakeSelectDecoupling = false
      )
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-backend-direct-echo-$name")
        .compile(new OooBackendDispatchProbe(testConfig))
        .doSim(s"ooo-backend-direct-echo-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearControl(dut)
          dut.io.issueReady #= 0xf
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()

          val basePc = BigInt("1c000000", 16)
          val firstProducerPc = basePc
          val secondProducerPc = basePc + 4
          val secondConsumerPc = basePc + 8
          dut.io.inputValid #= 7
          dut.io.pc(0) #= firstProducerPc
          dut.io.instruction(0) #= BigInt("0280040d", 16) // addi.w r13,r0,1
          dut.io.pc(1) #= secondProducerPc
          dut.io.instruction(1) #= BigInt("0280080f", 16) // addi.w r15,r0,2
          dut.io.pc(2) #= secondConsumerPc
          dut.io.instruction(2) #= BigInt("028005f0", 16) // addi.w r16,r15,1
          dut.clockDomain.waitSampling()
          dut.io.inputValid #= 0

          var firstPdst = BigInt(0)
          var firstPointer = BigInt(0)
          var secondPdst = BigInt(0)
          var secondPointer = BigInt(0)
          for (_ <- 0 until 16 if firstPdst == 0 || secondPdst == 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            val mask = dut.io.issueValid.toBigInt
            for (port <- 0 until testConfig.executionWidth) {
              if ((mask & (BigInt(1) << port)) != 0) {
                val pc = dut.io.issuePc(port).toBigInt
                if (pc == firstProducerPc) {
                  firstPdst = dut.io.issuePdst(port).toBigInt
                  firstPointer = dut.io.issueRobPointer(port).toBigInt
                }
                if (pc == secondProducerPc) {
                  secondPdst = dut.io.issuePdst(port).toBigInt
                  secondPointer = dut.io.issueRobPointer(port).toBigInt
                }
              }
            }
          }
          assert(firstPdst != 0)
          assert(secondPdst != 0)

          dut.io.completionValid #= 1
          dut.io.completionRobPointer #= firstPointer
          dut.io.completionPdst #= firstPdst
          dut.io.completionWritesPdst #= true
          dut.io.completionData #= BigInt("11111111", 16)
          dut.io.directWakeupValid #= true
          dut.io.directWakeupPdst #= firstPdst
          dut.clockDomain.waitSampling()

          dut.io.completionRobPointer #= secondPointer
          dut.io.completionPdst #= secondPdst
          dut.io.completionData #= BigInt("22222222", 16)
          dut.io.directWakeupPdst #= secondPdst
          dut.clockDomain.waitSampling()
          dut.io.completionValid #= 0
          dut.io.directWakeupValid #= false

          dut.clockDomain.waitSampling()
          sleep(1)
          val issuedOnFirstOpportunity = (0 until testConfig.executionWidth).exists { port =>
            (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == secondConsumerPc
          }
          assert(issuedOnFirstOpportunity == suppressEcho)

          if (!suppressEcho) {
            dut.clockDomain.waitSampling()
            sleep(1)
            assert((0 until testConfig.executionWidth).exists { port =>
              (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
                dut.io.issuePc(port).toBigInt == secondConsumerPc
            })
          }
        }
    }
  }

  test("source-qualified LSQ load wakeup reaches a dependent through the PRF write-through") {
    for ((earlyWake, name, seed, sourceWakeupValid, wakeEpoch) <- Seq(
        (false, "registered", 0x4c70, false, 0),
        (true, "early", 0x4c71, true, 0),
        (true, "stale-source", 0x4c72, false, 1)
      )) {
      val testConfig = config.copy(
        enableLoadCompletionEarlyWakeup = earlyWake,
        enableOrdinaryRegisteredWakeSelectDecoupling = false
      )
      SimConfig.withVerilator
        .workspacePath(s"${sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target")}/sim-workspace-ooo-backend-load-wakeup-$name")
        .compile(new OooBackendDispatchProbe(testConfig))
        .doSim(s"ooo-backend-load-wakeup-$name", seed) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearControl(dut)
          dut.io.issueReady #= 0xf
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling()

          val producerPc = BigInt("1c000000", 16)
          val consumerPc = producerPc + 4
          dut.io.inputValid #= 3
          dut.io.pc(0) #= producerPc
          dut.io.instruction(0) #= BigInt("2880000d", 16) // ld.w r13,r0,0
          dut.io.pc(1) #= consumerPc
          dut.io.instruction(1) #= BigInt("028005ae", 16) // addi.w r14,r13,1
          dut.clockDomain.waitSampling()
          dut.io.inputValid #= 0

          var producerPdst = BigInt(0)
          var producerRob = BigInt(0)
          var cycles = 0
          while (producerPdst == 0 && cycles < 24) {
            dut.clockDomain.waitSampling()
            sleep(1)
            for (port <- 0 until testConfig.executionWidth) {
              if (
                (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
                  dut.io.issuePc(port).toBigInt == producerPc
              ) {
                producerPdst = dut.io.issuePdst(port).toBigInt
                producerRob = dut.io.issueRobPointer(port).toBigInt
              }
            }
            cycles += 1
          }
          assert(producerPdst != 0)

          dut.io.completionValid #= BigInt(1) << loadStorePort
          dut.io.completionLane #= loadStorePort
          dut.io.completionRobPointer #= producerRob
          dut.io.completionPdst #= producerPdst
          dut.io.completionWritesPdst #= true
          dut.io.completionData #= BigInt("12345678", 16)
          dut.io.loadWakeupValid #= sourceWakeupValid
          dut.io.loadWakeupPdst #= producerPdst
          dut.io.loadWakeupRecoveryEpoch #= wakeEpoch
          dut.io.loadWakeupEpochCurrent #= (wakeEpoch == 0)
          dut.clockDomain.waitSampling()
          dut.io.completionValid #= 0
          dut.io.loadWakeupValid #= false

          var consumerIssueCycle = -1
          for (cycle <- 1 to 5 if consumerIssueCycle < 0) {
            dut.clockDomain.waitSampling()
            sleep(1)
            if ((0 until testConfig.executionWidth).exists { port =>
                (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
                  dut.io.issuePc(port).toBigInt == consumerPc
              }) {
              consumerIssueCycle = cycle
              val consumerPort = (0 until testConfig.executionWidth).find { port =>
                (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
                  dut.io.issuePc(port).toBigInt == consumerPc
              }.get
              assert(dut.io.issueSource1(consumerPort).toBigInt == BigInt("12345678", 16))
            }
          }
          val expectedCycle = if (earlyWake && wakeEpoch == 0) 1 else 2
          assert(
            consumerIssueCycle == expectedCycle,
            s"$name expected dependent issue at $expectedCycle, got $consumerIssueCycle"
          )
        }
    }
  }

  test("multiply direct wake and raw result keep resident and later consumers live") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-early-multiply-wakeup", 0x4c48) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val consumerPc = producerPc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("001c000d", 16) // mul.w r13,r0,r0
        dut.io.pc(1) #= consumerPc
        dut.io.instruction(1) #= BigInt("028005ae", 16) // addi.w r14,r13,1
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var producerPdst = BigInt(0)
        var producerRobPointer = BigInt(0)
        var producerSeen = false
        var cycles = 0
        while (!producerSeen && cycles < 16) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val mask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if (
              (mask & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == producerPc
            ) {
              producerSeen = true
              producerPdst = dut.io.issuePdst(port).toBigInt
              producerRobPointer = dut.io.issueRobPointer(port).toBigInt
            }
          }
          cycles += 1
        }
        assert(producerSeen)
        assert(producerPdst != 0)

        // Wake on multiply issue.  The product itself becomes available from
        // the multiply pipeline during the following PRF-read cycle.
        dut.io.fixedPortWakeupValid #= true
        dut.io.fixedPortWakeupPdst #= producerPdst
        dut.clockDomain.waitSampling()
        dut.io.fixedPortWakeupValid #= false

        val product = BigInt("76543210", 16)
        dut.io.multiplyForwardValid #= true
        dut.io.multiplyForwardPdst #= producerPdst
        dut.io.multiplyForwardData #= product
        dut.io.completionValid #= BigInt(1) << config.executionWidth
        dut.io.completionLane #= config.executionWidth
        dut.io.completionRobPointer #= producerRobPointer
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= product
        dut.clockDomain.waitSampling()
        sleep(1)

        val consumerPort = (0 until config.executionWidth).find { port =>
          (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
          dut.io.issuePc(port).toBigInt == consumerPc
        }
        assert(consumerPort.nonEmpty)
        assert(dut.io.issueSource1(consumerPort.get).toBigInt == product)

        dut.io.multiplyForwardValid #= false
        dut.io.completionValid #= 0
        val laterConsumerPc = consumerPc + 4
        dut.io.inputValid #= 1
        dut.io.pc(0) #= laterConsumerPc
        dut.io.instruction(0) #= BigInt("028005af", 16) // addi.w r15,r13,1
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var laterResult = Option.empty[BigInt]
        for (_ <- 0 until 16 if laterResult.isEmpty) {
          dut.clockDomain.waitSampling()
          sleep(1)
          for (port <- 0 until config.executionWidth) {
            if (
              (dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == laterConsumerPc
            ) laterResult = Some(dut.io.issueSource1(port).toBigInt)
          }
        }
        assert(laterResult.contains(product))
      }
  }

  test("multiply direct wake cannot release stale Store data") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-store-data-multiply-wakeup", 0x4c75) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val producerPc = BigInt("1c000000", 16)
        val storePc = producerPc + 4
        dut.io.inputValid #= 3
        dut.io.pc(0) #= producerPc
        dut.io.instruction(0) #= BigInt("001c000d", 16) // mul.w r13,r0,r0
        dut.io.pc(1) #= storePc
        dut.io.instruction(1) #= BigInt("2980000d", 16) // st.w r13,r0,0
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        var producerPdst = BigInt(0)
        var producerRobPointer = BigInt(0)
        var storeIssued = false
        for (_ <- 0 until 16 if producerPdst == 0 || !storeIssued) {
          dut.clockDomain.waitSampling()
          sleep(1)
          for (port <- 0 until config.executionWidth) {
            if ((dut.io.issueValid.toBigInt & (BigInt(1) << port)) != 0) {
              if (dut.io.issuePc(port).toBigInt == producerPc) {
                producerPdst = dut.io.issuePdst(port).toBigInt
                producerRobPointer = dut.io.issueRobPointer(port).toBigInt
              }
              if (dut.io.issuePc(port).toBigInt == storePc) storeIssued = true
            }
          }
        }
        assert(producerPdst != 0)
        assert(storeIssued)
        assert(!dut.io.storeDataValid.toBoolean)

        // This is only the multiply pipe acceptance tag.  No result has been
        // written to the PRF yet, so StoreData must remain resident.
        dut.io.fixedPortWakeupValid #= true
        dut.io.fixedPortWakeupPdst #= producerPdst
        dut.clockDomain.waitSampling()
        dut.io.fixedPortWakeupValid #= false
        sleep(1)
        assert(!dut.io.storeDataValid.toBoolean)

        val product = BigInt("76543210", 16)
        dut.io.completionValid #= BigInt(1) << config.executionWidth
        dut.io.completionLane #= config.executionWidth
        dut.io.completionRobPointer #= producerRobPointer
        dut.io.completionPdst #= producerPdst
        dut.io.completionWritesPdst #= true
        dut.io.completionData #= product

        var observed = false
        for (_ <- 0 until 6 if !observed) {
          dut.clockDomain.waitSampling()
          sleep(1)
          if (dut.io.storeDataValid.toBoolean) {
            observed = true
            assert(dut.io.storeData.toBigInt == product)
          }
        }
        assert(observed, "Store data did not become available after multiply completion")
        dut.io.completionValid #= 0
      }
  }

  test("flush advances the recovery epoch carried by newly issued uops") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-recovery-epoch", 0x4c46) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        def issueEpoch(pc: BigInt): BigInt = {
          dut.io.inputValid #= 1
          dut.io.pc(0) #= pc
          dut.io.instruction(0) #= BigInt("00100000", 16)
          dut.clockDomain.waitSampling()
          dut.io.inputValid #= 0
          for (_ <- 0 until 12) {
            dut.clockDomain.waitSampling()
            sleep(1)
            val mask = dut.io.issueValid.toBigInt
            for (port <- 0 until config.executionWidth) {
              if (
                (mask & (BigInt(1) << port)) != 0 &&
                dut.io.issuePc(port).toBigInt == pc
              ) return dut.io.issueRecoveryEpoch(port).toBigInt
            }
          }
          fail(s"uop at 0x${pc.toString(16)} did not issue")
        }

        val basePc = BigInt("1c000000", 16)
        assert(issueEpoch(basePc) == 0)
        dut.io.flush #= true
        dut.clockDomain.waitSampling()
        dut.io.flush #= false
        assert(issueEpoch(basePc + 4) == 1)
      }
  }

  test("flush atomically blocks rename allocation despite available capacity") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-flush-blocks-allocation", 0x4c47) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.inputValid #= 7
        for (lane <- 0 until config.renameWidth) {
          dut.io.pc(lane) #= BigInt("1c002000", 16) + lane * 4
          dut.io.instruction(lane) #= BigInt("00100000", 16)
        }
        dut.io.flush #= true
        sleep(1)
        assert(dut.io.renameReady.toBigInt == 0)
        assert(dut.io.memoryAllocateValid.toBigInt == 0)
        dut.clockDomain.waitSampling()

        dut.io.flush #= false
        dut.io.inputValid #= 0
        for (_ <- 0 until 8) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(dut.io.issueValid.toBigInt == 0)
        }
      }
  }

  test("a completion without a physical write cannot wake a reused destination tag") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-ignore-non-writing-completion", 0x4c43) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c000000", 16)
        // ld.w r13,r0,0; addi.w r14,r13,1; b 0.  The load does not use the
        // fixed-latency direct wakeup, so only a writing completion may release r14.
        dut.io.inputValid #= 7
        dut.io.pc(0) #= basePc
        dut.io.instruction(0) #= BigInt("2880000d", 16)
        dut.io.pc(1) #= basePc + 4
        dut.io.instruction(1) #= BigInt("028005ae", 16)
        dut.io.pc(2) #= basePc + 8
        dut.io.instruction(2) #= BigInt("50000000", 16)
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        // Complete the non-writing branch while claiming the producer's physical tag p1.
        dut.io.completionValid #= 1
        dut.io.completionRobPointer #= 2
        dut.io.completionPdst #= 1
        dut.io.completionWritesPdst #= false
        dut.clockDomain.waitSampling()
        dut.io.completionValid #= 0
        dut.clockDomain.waitSampling(2)

        dut.io.issueReady #= 0xf
        var dependentIssued = false
        for (_ <- 0 until 8) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val issueMask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if (
              (issueMask & (BigInt(1) << port)) != 0 &&
              dut.io.issuePc(port).toBigInt == basePc + 4
            ) {
              dependentIssued = true
            }
          }
        }
        assert(!dependentIssued)
      }
  }

  test("an r0 write cannot borrow the next allocated physical destination") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-backend-dispatch")
      .compile(new OooBackendDispatchProbe(config))
      .doSim("ooo-backend-r0-has-no-physical-destination", 0x4c44) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearControl(dut)
        dut.io.issueReady #= 0xf
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        val basePc = BigInt("1c000000", 16)
        // add.w r0,r0,r0 must not claim p1; addi.w r13,r0,1 owns p1.
        dut.io.inputValid #= 3
        dut.io.pc(0) #= basePc
        dut.io.instruction(0) #= BigInt("00100000", 16)
        dut.io.pc(1) #= basePc + 4
        dut.io.instruction(1) #= BigInt("0280040d", 16)
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 0

        val observedPdst = scala.collection.mutable.Map.empty[BigInt, BigInt]
        var cycles = 0
        while (observedPdst.size < 2 && cycles < 12) {
          dut.clockDomain.waitSampling()
          sleep(1)
          val issueMask = dut.io.issueValid.toBigInt
          for (port <- 0 until config.executionWidth) {
            if ((issueMask & (BigInt(1) << port)) != 0) {
              observedPdst(dut.io.issuePc(port).toBigInt) = dut.io.issuePdst(port).toBigInt
            }
          }
          cycles += 1
        }

        assert(observedPdst(basePc) == 0)
        assert(observedPdst(basePc + 4) == 1)
      }
  }
}
