package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class RenameMapProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val renameSource1 = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renameSource2 = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renameDestination = in Vec (UInt(config.archRegIndexWidth bits), config.renameWidth)
    val renamePdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renamePsrc1 = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renamePsrc2 = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val renameSource1Ready = out Bits (config.renameWidth bits)
    val renameSource2Ready = out Bits (config.renameWidth bits)
    val renameOldPdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.renameWidth)
    val writebackValid = in Bits (config.writebackWidth bits)
    val writebackPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
    val commitValid = in Bits (config.commitWidth bits)
    val commitArch = in Vec (UInt(config.archRegIndexWidth bits), config.commitWidth)
    val commitPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.commitWidth)
    val commitPreviousPdst = out Vec (UInt(config.physicalRegIndexWidth bits), config.commitWidth)
    val physicalReady = out Bits (config.physicalRegs bits)
    val flush = in Bool ()
  }
  noIoPrefix()

  val registerMap = new RenameMap(config)
  registerMap.io.renameValid := io.renameValid
  registerMap.io.renameSource1 := io.renameSource1
  registerMap.io.renameSource2 := io.renameSource2
  registerMap.io.renameDestination := io.renameDestination
  registerMap.io.renamePdst := io.renamePdst
  registerMap.io.writebackValid := io.writebackValid
  registerMap.io.writebackPdst := io.writebackPdst
  registerMap.io.commitValid := io.commitValid
  registerMap.io.commitArch := io.commitArch
  registerMap.io.commitPdst := io.commitPdst
  registerMap.io.flush := io.flush

  io.renamePsrc1 := registerMap.io.renamePsrc1
  io.renamePsrc2 := registerMap.io.renamePsrc2
  io.renameSource1Ready := registerMap.io.renameSource1Ready
  io.renameSource2Ready := registerMap.io.renameSource2Ready
  io.renameOldPdst := registerMap.io.renameOldPdst
  io.commitPreviousPdst := registerMap.io.commitPreviousPdst
  io.physicalReady := registerMap.io.physicalReady
}

class RegisterStructuresSpec extends AnyFunSuite {
  test("physical register zero ignores same-cycle writeback bypass") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-registers")
      .compile(new PhysicalRegisterFile(config))
      .doSim("ooo-prf-zero-bypass", 0x50524630) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.writeValid #= 0
        dut.io.flush #= false
        dut.io.debugReadAddress #= 0
        for (port <- 0 until config.executionWidth * 2) {
          dut.io.readAddress(port) #= 0
        }
        for (port <- 0 until config.writebackWidth) {
          dut.io.write(port).pdst #= 0
          dut.io.write(port).data #= 0
        }
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        dut.io.writeValid #= 1
        dut.io.write(0).pdst #= 0
        dut.io.write(0).data #= BigInt("deadbeef", 16)
        sleep(1)
        assert(dut.io.readData(0).toBigInt == 0)
        assert(dut.io.debugReadData.toBigInt == 0)

        dut.clockDomain.waitSampling()
        dut.io.writeValid #= 0
        sleep(1)
        assert(dut.io.readData(0).toBigInt == 0)
        assert(dut.io.debugReadData.toBigInt == 0)
      }
  }

  test("physical register banks retain simultaneous same-bank writes") {
    val config = OooCoreConfig.ExpandedWindow
    val destinations = Seq(4, 36, 68, 100, 124)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-registers-banked-write")
      .compile(new PhysicalRegisterFile(config))
      .doSim("ooo-prf-banked-write", 0x50524642) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.writeValid #= 0
        dut.io.flush #= false
        dut.io.debugReadAddress #= 0
        for (port <- 0 until config.executionWidth * 2) {
          dut.io.readAddress(port) #= 0
        }
        for (port <- 0 until config.writebackWidth) {
          dut.io.write(port).pdst #= 0
          dut.io.write(port).data #= 0
        }
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        for (port <- destinations.indices) {
          dut.io.write(port).pdst #= destinations(port)
          dut.io.write(port).data #= BigInt(0x5100 + port)
          dut.io.readAddress(port) #= destinations(port)
        }
        dut.io.writeValid #= (BigInt(1) << config.writebackWidth) - 1
        sleep(1)
        for (port <- destinations.indices) {
          assert(dut.io.readData(port).toBigInt == BigInt(0x5100 + port))
        }

        dut.clockDomain.waitSampling()
        dut.io.writeValid #= 0
        sleep(1)
        for (port <- destinations.indices) {
          assert(dut.io.readData(port).toBigInt == BigInt(0x5100 + port))
        }
      }
  }

  test("physical register bypass selects the matching bank and row") {
    val config = OooCoreConfig.ExpandedWindow
    val destinations = Seq(1, 34, 67, 100, 5)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-registers-banked-bypass")
      .compile(new PhysicalRegisterFile(config))
      .doSim("ooo-prf-banked-bypass", 0x50524643) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.writeValid #= 0
        dut.io.flush #= false
        dut.io.debugReadAddress #= destinations(3)
        for (port <- 0 until config.executionWidth * 2) {
          dut.io.readAddress(port) #= destinations(port % destinations.size)
        }
        for (port <- 0 until config.writebackWidth) {
          dut.io.write(port).pdst #= destinations(port)
          dut.io.write(port).data #= BigInt(0x6200 + port)
        }
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        dut.io.writeValid #= (BigInt(1) << config.writebackWidth) - 1
        sleep(1)
        for (port <- 0 until config.executionWidth * 2) {
          assert(dut.io.readData(port).toBigInt == BigInt(0x6200 + port % destinations.size))
        }
        assert(dut.io.debugReadData.toBigInt == BigInt(0x6203))

        dut.clockDomain.waitSampling()
        dut.io.writeValid #= 0
        sleep(1)
        for (port <- 0 until config.executionWidth * 2) {
          assert(dut.io.readData(port).toBigInt == BigInt(0x6200 + port % destinations.size))
        }
        assert(dut.io.debugReadData.toBigInt == BigInt(0x6203))
      }
  }

  private def clearFreeListInputs(dut: PhysicalRegisterFreeList, config: OooCoreConfig): Unit = {
    dut.io.allocateValid #= 0
    dut.io.allocateAccept #= false
    dut.io.allocateAcceptMask #= 0
    dut.io.commitFreeValid #= 0
    dut.io.flush #= false
    for (lane <- 0 until config.commitWidth) {
      dut.io.commitFreePdst(lane) #= 0
    }
  }

  private def freeListSample(dut: PhysicalRegisterFreeList): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def checkAllocation(
      dut: PhysicalRegisterFreeList,
      expected: Seq[Int],
      ready: Boolean = true
  ): Unit = {
    sleep(1)
    assert(dut.io.allocateReady.toBoolean == ready)
    for ((pdst, lane) <- expected.zipWithIndex) {
      assert(dut.io.allocatePdst(lane).toBigInt == pdst)
    }
  }

  private def clearInputs(dut: RenameMapProbe, config: OooCoreConfig): Unit = {
    dut.io.renameValid #= 0
    dut.io.writebackValid #= 0
    dut.io.commitValid #= 0
    dut.io.flush #= false
    for (lane <- 0 until config.renameWidth) {
      dut.io.renameSource1(lane) #= 0
      dut.io.renameSource2(lane) #= 0
      dut.io.renameDestination(lane) #= 0
      dut.io.renamePdst(lane) #= 0
    }
    for (lane <- 0 until config.writebackWidth) {
      dut.io.writebackPdst(lane) #= 0
    }
    for (lane <- 0 until config.commitWidth) {
      dut.io.commitArch(lane) #= 0
      dut.io.commitPdst(lane) #= 0
    }
  }

  private def sample(dut: RenameMapProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def physicalReady(dut: RenameMapProbe, index: Int): Boolean =
    ((dut.io.physicalReady.toBigInt >> index) & 1) == 1

  test("rename handles same-cycle RAW and WAW in lane order") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-registers")
      .compile(new RenameMapProbe(config))
      .doSim("ooo-register-same-cycle-dependencies", 0x5241) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.renameValid #= 3
        dut.io.renameDestination(0) #= 5
        dut.io.renameDestination(1) #= 5
        dut.io.renameSource1(1) #= 5
        dut.io.renamePdst(0) #= 10
        dut.io.renamePdst(1) #= 11
        sleep(1)

        assert(dut.io.renamePsrc1(0).toBigInt == 0)
        assert((dut.io.renameSource1Ready.toBigInt & 1) == 1)
        assert(dut.io.renamePsrc1(1).toBigInt == 10)
        assert((dut.io.renameSource1Ready.toBigInt & 2) == 0)
        assert(dut.io.renameOldPdst(0).toBigInt == 0)
        assert(dut.io.renameOldPdst(1).toBigInt == 10)
      }
  }

  test("writeback wakes the current physical source and commit history is ordered") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-registers")
      .compile(new RenameMapProbe(config))
      .doSim("ooo-register-wakeup-and-commit-history", 0x5242) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.renameValid #= 1
        dut.io.renameDestination(0) #= 7
        dut.io.renamePdst(0) #= 12
        sample(dut)

        dut.io.renameValid #= 0
        dut.io.renameSource1(0) #= 7
        dut.io.writebackValid #= 1
        dut.io.writebackPdst(0) #= 12
        sleep(1)
        assert(dut.io.renamePsrc1(0).toBigInt == 12)
        assert((dut.io.renameSource1Ready.toBigInt & 1) == 1)

        dut.io.writebackValid #= 0
        dut.io.commitValid #= 3
        dut.io.commitArch(0) #= 7
        dut.io.commitArch(1) #= 7
        dut.io.commitPdst(0) #= 12
        dut.io.commitPdst(1) #= 13
        sleep(1)
        assert(dut.io.commitPreviousPdst(0).toBigInt == 0)
        assert(dut.io.commitPreviousPdst(1).toBigInt == 12)
        sample(dut)

        dut.io.commitValid #= 0
        dut.io.flush #= true
        sample(dut)
        assert(dut.io.renamePsrc1(0).toBigInt == 13)
      }
  }

  for ((name, config, highPdst) <- Seq(
      ("default", OooCoreConfig.FourIssueThreeCommit, 63),
      ("expanded-window", OooCoreConfig.ExpandedWindow, 127)
    )) {
    test(s"rename ready mask preserves allocation priority and flush for $name") {
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-register-ready-mask-$name"
        )
        .compile(new RenameMapProbe(config))
        .doSim(s"ooo-register-ready-mask-$name", 0x5243 + highPdst) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut, config)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          sample(dut)

          dut.io.renameValid #= 7
          dut.io.renameDestination(0) #= 1
          dut.io.renameDestination(1) #= 2
          dut.io.renameDestination(2) #= 3
          dut.io.renamePdst(0) #= 7
          dut.io.renamePdst(1) #= 12
          dut.io.renamePdst(2) #= highPdst
          sample(dut)
          assert(!physicalReady(dut, 7))
          assert(!physicalReady(dut, 12))
          assert(!physicalReady(dut, highPdst))

          clearInputs(dut, config)
          dut.io.renameValid #= 1
          dut.io.renameDestination(0) #= 4
          dut.io.renamePdst(0) #= 12
          dut.io.writebackValid #= 3
          dut.io.writebackPdst(0) #= 7
          dut.io.writebackPdst(1) #= 12
          sample(dut)
          assert(physicalReady(dut, 7))
          assert(!physicalReady(dut, 12))

          clearInputs(dut, config)
          dut.io.writebackValid #= 3
          dut.io.writebackPdst(0) #= 0
          dut.io.writebackPdst(1) #= 12
          sample(dut)
          assert(physicalReady(dut, 0))
          assert(physicalReady(dut, 12))

          clearInputs(dut, config)
          dut.io.flush #= true
          sample(dut)
          assert(dut.io.physicalReady.toBigInt == (BigInt(1) << config.physicalRegs) - 1)
        }
    }
  }

  test("free list flush restores the committed allocation head") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-free-list")
      .compile(new PhysicalRegisterFreeList(config))
      .doSim("ooo-free-list-flush-head", 0x4651) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearFreeListInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        freeListSample(dut)

        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(1, 2, 3))
        freeListSample(dut)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.commitFreeValid #= 7
        freeListSample(dut)

        dut.io.commitFreeValid #= 0
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(4, 5, 6))
        freeListSample(dut)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.flush #= true
        freeListSample(dut)

        dut.io.flush #= false
        dut.io.allocateValid #= 7
        checkAllocation(dut, Seq(4, 5, 6))
      }
  }

  test("free list applies a delayed retirement batch during recovery flush") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-free-list")
      .compile(new PhysicalRegisterFreeList(config))
      .doSim("ooo-free-list-delayed-commit-flush", 0x4653) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearFreeListInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        freeListSample(dut)

        // p1..p3 become architectural mappings.
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(1, 2, 3))
        freeListSample(dut)
        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.commitFreeValid #= 7
        freeListSample(dut)

        // p4 commits as the replacement for p1 while p5/p6 remain speculative.
        // Its registered retirement batch arrives together with recovery.
        dut.io.commitFreeValid #= 0
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(4, 5, 6))
        freeListSample(dut)
        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.commitFreeValid #= 1
        dut.io.commitFreePdst(0) #= 1
        dut.io.flush #= true
        freeListSample(dut)

        dut.io.commitFreeValid #= 0
        dut.io.commitFreePdst(0) #= 0
        dut.io.flush #= false
        dut.io.allocateValid #= 7
        checkAllocation(dut, Seq(5, 6, 7))
      }
  }

  test("free list recycles committed registers across pointer wrap") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-free-list")
      .compile(new PhysicalRegisterFreeList(config))
      .doSim("ooo-free-list-wrap-recycle", 0x4652) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearFreeListInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        freeListSample(dut)

        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(1, 2, 3))
        freeListSample(dut)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.commitFreeValid #= 7
        freeListSample(dut)

        dut.io.commitFreeValid #= 0
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        checkAllocation(dut, Seq(4, 5, 6))
        freeListSample(dut)

        dut.io.allocateValid #= 0
        dut.io.allocateAccept #= false
        dut.io.commitFreeValid #= 7
        for (lane <- 0 until config.commitWidth) {
          dut.io.commitFreePdst(lane) #= lane + 1
        }
        freeListSample(dut)

        dut.io.commitFreeValid #= 0
        for (lane <- 0 until config.commitWidth) {
          dut.io.commitFreePdst(lane) #= 0
        }
        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        for (_ <- 0 until 19) {
          freeListSample(dut)
        }

        dut.io.allocateAccept #= false
        checkAllocation(dut, Seq(1, 2, 3))
        dut.io.allocateAccept #= true
        freeListSample(dut)

        dut.io.allocateAccept #= false
        dut.io.allocateValid #= 1
        sleep(1)
        assert(!dut.io.allocateReady.toBoolean)

        dut.io.allocateValid #= 0
        dut.io.commitFreeValid #= 1
        dut.io.commitFreePdst(0) #= 4
        freeListSample(dut)

        dut.io.commitFreeValid #= 0
        dut.io.commitFreePdst(0) #= 0
        dut.io.allocateValid #= 1
        checkAllocation(dut, Seq(4))
      }
  }

  test("free list exposes conservative rename-group capacity") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-free-list")
      .compile(new PhysicalRegisterFreeList(config))
      .doSim("ooo-free-list-group-capacity", 0x4654) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearFreeListInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        freeListSample(dut)

        dut.io.allocateValid #= 7
        dut.io.allocateAccept #= true
        for (_ <- 0 until 20) freeListSample(dut)
        assert(dut.io.allocateCapacityReady.toBoolean)

        // Consume one of the last three entries directly. One exact request
        // still fits, while an arbitrary three-wide rename group does not.
        dut.io.allocateValid #= 1
        freeListSample(dut)
        dut.io.allocateAccept #= false
        sleep(1)
        assert(dut.io.allocateReady.toBoolean)
        assert(!dut.io.allocateCapacityReady.toBoolean)
      }
  }

  test("free list accept mask cannot consume tags for non-writing uops") {
    val config = OooCoreConfig.FourIssueThreeCommit
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-free-list")
      .compile(new PhysicalRegisterFreeList(config))
      .doSim("ooo-free-list-qualified-accept-mask", 0x4655) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearFreeListInputs(dut, config)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        freeListSample(dut)

        // The backend may accept three uops while only lane zero writes a GPR.
        // Even a defensively over-broad accept mask must consume exactly p1.
        dut.io.allocateValid #= 1
        dut.io.allocateAccept #= true
        dut.io.allocateAcceptMask #= 7
        checkAllocation(dut, Seq(1))
        freeListSample(dut)

        dut.io.allocateAccept #= false
        dut.io.allocateAcceptMask #= 0
        dut.io.allocateValid #= 7
        checkAllocation(dut, Seq(2, 3, 4))
      }
  }

  for ((name, config) <- Seq(
      ("default", OooCoreConfig.FourIssueThreeCommit),
      ("expanded-window", OooCoreConfig.ExpandedWindow)
    )) {
    test(s"free list banks preserve sparse allocation and simultaneous recycle for $name") {
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-free-list-banked-$name"
        )
        .compile(new PhysicalRegisterFreeList(config))
        .doSim(s"ooo-free-list-banked-$name", 0x4660 + config.physicalRegs) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearFreeListInputs(dut, config)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          freeListSample(dut)

          val free = scala.collection.mutable.Queue((1 until config.physicalRegs): _*)
          val inFlight = scala.collection.mutable.Queue.empty[Int]
          val masks = Seq(7, 5, 3, 1, 6, 2, 4)

          for (cycle <- 0 until config.physicalRegs * 2) {
            val mask = masks(cycle % masks.size)
            val allocateCount = Integer.bitCount(mask)
            assert(free.size >= allocateCount)
            val expected = free.take(allocateCount).toVector
            val releaseCount = math.min(config.commitWidth, inFlight.size)
            val released = Vector.fill(releaseCount)(inFlight.dequeue())

            dut.io.allocateValid #= mask
            dut.io.allocateAccept #= false
            dut.io.allocateAcceptMask #= mask
            dut.io.commitFreeValid #= (1 << releaseCount) - 1
            for (lane <- 0 until config.commitWidth) {
              dut.io.commitFreePdst(lane) #=
                (if (lane < releaseCount) released(lane).toLong else 0L)
            }

            sleep(1)
            assert(dut.io.allocateReady.toBoolean)
            var offset = 0
            for (lane <- 0 until config.renameWidth) {
              if (((mask >> lane) & 1) != 0) {
                assert(dut.io.allocatePdst(lane).toBigInt == expected(offset))
                offset += 1
              }
            }
            freeListSample(dut)

            for (_ <- 0 until allocateCount) free.dequeue()
            expected.foreach(inFlight.enqueue(_))
            released.foreach(free.enqueue(_))
          }
        }
    }
  }
}
