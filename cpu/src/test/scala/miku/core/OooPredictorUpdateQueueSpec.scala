package miku.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class OooPredictorUpdateQueueSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private final class QueueHarness extends Component {
    val io = new Bundle {
      val pushValid = in Bits (config.commitWidth bits)
      val pushPc = in Vec (UInt(config.xlen bits), config.commitWidth)
      val popReady = in Bool ()
      val pushCapacity = out UInt (log2Up(config.commitWidth + 1) bits)
      val popValid = out Bool ()
      val popPc = out UInt (config.xlen bits)
      val occupancy = out UInt (log2Up(9) bits)
    }

    val queue = new OooPredictorUpdateQueue(config, depth = 8)
    queue.io.pushValid := io.pushValid
    queue.io.popReady := io.popReady
    for (lane <- 0 until config.commitWidth) {
      queue.io.push(lane).pc := io.pushPc(lane)
      queue.io.push(lane).taken := False
      queue.io.push(lane).target := 0
      queue.io.push(lane).branchType := 0
      queue.io.push(lane).metadata := 0
      queue.io.push(lane).isCall := False
      queue.io.push(lane).isReturn := False
    }
    io.pushCapacity := queue.io.pushCapacity
    io.popValid := queue.io.popValid
    io.popPc := queue.io.pop.pc
    io.occupancy := queue.io.occupancy
  }

  test("three-wide enqueue, backpressure and recovery preserve program order") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-predictor-update-queue")
      .compile(new QueueHarness)
      .doSim("ooo-predictor-update-queue", 0xb03f) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.pushValid #= 0
        dut.io.popReady #= true
        for (lane <- 0 until config.commitWidth) dut.io.pushPc(lane) #= 0
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sleep(1)

        def push(valid: Int, pcs: Seq[Long]): Unit = {
          assert(Integer.bitCount(valid) <= dut.io.pushCapacity.toInt)
          dut.io.pushValid #= valid
          for (lane <- 0 until config.commitWidth) {
            dut.io.pushPc(lane) #= BigInt(pcs.lift(lane).getOrElse(0L))
          }
          dut.clockDomain.waitSampling()
          dut.io.pushValid #= 0
          sleep(1)
        }

        push(0x3, Seq(10L, 11L))
        assert(dut.io.popValid.toBoolean)
        assert(dut.io.popPc.toBigInt == 10)

        // The recovery branch (11) remains behind its older same-cycle branch while a new
        // three-branch retirement batch enters concurrently with the first pop.
        dut.io.popReady #= false
        push(0x7, Seq(20L, 21L, 22L))
        assert(dut.io.popPc.toBigInt == 10)
        assert(dut.io.occupancy.toInt == 5)
        dut.io.popReady #= true
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.popPc.toBigInt == 11)
        push(0x7, Seq(30L, 31L, 32L))
        assert(dut.io.popPc.toBigInt == 20)
        push(0x7, Seq(40L, 41L, 42L))
        assert(dut.io.occupancy.toInt == 8)
        assert(dut.io.pushCapacity.toInt == 1)

        val observed = scala.collection.mutable.ArrayBuffer.empty[Int]
        while (dut.io.popValid.toBoolean) {
          observed += dut.io.popPc.toBigInt.toInt
          dut.clockDomain.waitSampling()
          sleep(1)
        }
        assert(observed.toSeq == Seq(21, 22, 30, 31, 32, 40, 41, 42))
        assert(dut.io.occupancy.toInt == 0)
        assert(dut.io.pushCapacity.toInt == config.commitWidth)
      }
  }
}
