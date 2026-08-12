package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class DispatchQueueProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val enqueueValid = in Bits (config.renameWidth bits)
    val enqueue = in Vec (RenamedMicroOp(config), config.renameWidth)
    val enqueueReady = out Bool ()
    val enqueueAccept = in Bool ()
    val dequeueValid = out Bits (config.dispatchWidth bits)
    val dequeue = out Vec (RenamedMicroOp(config), config.dispatchWidth)
    val dequeueReady = in Bits (config.dispatchWidth bits)
    val flush = in Bool ()
    val occupancy = out UInt (log2Up(config.dispatchQueueEntries + 1) bits)
  }
  noIoPrefix()

  val queue = new DispatchQueue(config)
  queue.io.enqueueValid := io.enqueueValid
  queue.io.enqueue := io.enqueue
  queue.io.enqueueAccept := io.enqueueAccept
  queue.io.dequeueReady := io.dequeueReady
  queue.io.flush := io.flush
  io.enqueueReady := queue.io.enqueueReady
  io.dequeueValid := queue.io.dequeueValid
  io.dequeue := queue.io.dequeue
  io.occupancy := queue.io.occupancy
}

class DispatchQueueSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def clearInputs(dut: DispatchQueueProbe): Unit = {
    dut.io.enqueueValid #= 0
    for (lane <- 0 until config.renameWidth) {
      dut.io.enqueue(lane).pdst #= 0
      dut.io.enqueue(lane).oldPdst #= 0
      dut.io.enqueue(lane).psrc1 #= 0
      dut.io.enqueue(lane).psrc2 #= 0
      dut.io.enqueue(lane).source1Ready #= true
      dut.io.enqueue(lane).source2Ready #= true
      dut.io.enqueue(lane).robPointer #= 0
      dut.io.enqueue(lane).recoveryEpoch #= 0
      dut.io.enqueue(lane).loadQueueIndex #= 0
      dut.io.enqueue(lane).storeQueueIndex #= 0
      dut.io.enqueue(lane).decoded.fuType #= 0
      dut.io.enqueue(lane).decoded.serializing #= false
      dut.io.enqueue(lane).decoded.isLoad #= false
      dut.io.enqueue(lane).decoded.isStore #= false
      dut.io.enqueue(lane).decoded.writesGpr #= false
    }
    dut.io.enqueueAccept #= false
    dut.io.dequeueReady #= 0
    dut.io.flush #= false
  }

  private def sample(dut: DispatchQueueProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def driveEnqueue(dut: DispatchQueueProbe, pointers: Seq[Int]): Unit = {
    dut.io.enqueueValid #= ((1 << pointers.size) - 1)
    pointers.zipWithIndex.foreach { case (pointer, lane) =>
      dut.io.enqueue(lane).robPointer #= pointer
    }
    dut.io.enqueueAccept #= true
    sleep(1)
    assert(dut.io.enqueueReady.toBoolean)
    sample(dut)
    dut.io.enqueueValid #= 0
    dut.io.enqueueAccept #= false
  }

  test("rename dispatch FIFO preserves order across prefix dequeue and ring wrap") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-dispatch-queue")
      .compile(new DispatchQueueProbe(config))
      .doSim("ooo-dispatch-queue-ring", 0x4c41) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        driveEnqueue(dut, Seq(10, 11, 12))
        assert(dut.io.occupancy.toBigInt == 3)
        assert(dut.io.dequeueValid.toBigInt == 7)
        assert(dut.io.dequeue(0).robPointer.toBigInt == 10)
        assert(dut.io.dequeue(1).robPointer.toBigInt == 11)
        assert(dut.io.dequeue(2).robPointer.toBigInt == 12)

        dut.io.dequeueReady #= 3
        sample(dut)
        dut.io.dequeueReady #= 0
        assert(dut.io.occupancy.toBigInt == 1)
        assert(dut.io.dequeue(0).robPointer.toBigInt == 12)

        driveEnqueue(dut, Seq(20, 21, 22))
        assert(dut.io.occupancy.toBigInt == 4)
        assert(dut.io.dequeue(0).robPointer.toBigInt == 12)
        assert(dut.io.dequeue(1).robPointer.toBigInt == 20)
        assert(dut.io.dequeue(2).robPointer.toBigInt == 21)

        dut.io.dequeueReady #= 7
        sample(dut)
        dut.io.dequeueReady #= 0
        assert(dut.io.occupancy.toBigInt == 1)
        assert(dut.io.dequeue(0).robPointer.toBigInt == 22)

        driveEnqueue(dut, Seq(30, 31, 32))
        driveEnqueue(dut, Seq(40, 41, 42))
        assert(dut.io.occupancy.toBigInt == 7)
        dut.io.dequeueReady #= 1
        sample(dut)
        dut.io.dequeueReady #= 0
        assert(dut.io.occupancy.toBigInt == 6)
        driveEnqueue(dut, Seq(50, 51))
        assert(dut.io.occupancy.toBigInt == 8)
        assert(!dut.io.enqueueReady.toBoolean)

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        assert(dut.io.occupancy.toBigInt == 0)
        assert(dut.io.dequeueValid.toBigInt == 0)
      }
  }
}
