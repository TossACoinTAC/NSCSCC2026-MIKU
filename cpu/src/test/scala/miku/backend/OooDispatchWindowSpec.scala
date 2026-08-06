package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class OooDispatchWindowProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.dispatchWidth bits)
    val input = in Vec (OooRenamedUop(config), config.dispatchWidth)
    val inputReady = out Bits (config.dispatchWidth bits)
    val outputValid = out Bits (config.dispatchWidth bits)
    val output = out Vec (OooRenamedUop(config), config.dispatchWidth)
    val outputReady = in Bits (config.dispatchWidth bits)
    val flush = in Bool ()
  }
  noIoPrefix()

  val window = new OooDispatchWindow(config)
  window.io.inputValid := io.inputValid
  window.io.input := io.input
  window.io.outputReady := io.outputReady
  window.io.flush := io.flush
  io.inputReady := window.io.inputReady
  io.outputValid := window.io.outputValid
  io.output := window.io.output
}

class OooDispatchWindowSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def clearInputs(dut: OooDispatchWindowProbe): Unit = {
    dut.io.inputValid #= 0
    dut.io.outputReady #= 0
    dut.io.flush #= false
    for (lane <- 0 until config.dispatchWidth) {
      dut.io.input(lane).pdst #= 0
      dut.io.input(lane).oldPdst #= 0
      dut.io.input(lane).psrc1 #= 0
      dut.io.input(lane).psrc2 #= 0
      dut.io.input(lane).source1Ready #= false
      dut.io.input(lane).source2Ready #= false
      dut.io.input(lane).robPointer #= 0
      dut.io.input(lane).recoveryEpoch #= 0
      dut.io.input(lane).loadQueueIndex #= 0
      dut.io.input(lane).storeQueueIndex #= 0
      dut.io.input(lane).decoded.fuType #= 0
      dut.io.input(lane).decoded.serializing #= false
      dut.io.input(lane).decoded.isLoad #= false
      dut.io.input(lane).decoded.isStore #= false
      dut.io.input(lane).decoded.writesGpr #= false
    }
  }

  private def driveInputs(dut: OooDispatchWindowProbe, pointers: Seq[Int]): Unit = {
    dut.io.inputValid #= ((1 << pointers.size) - 1)
    pointers.zipWithIndex.foreach { case (pointer, lane) =>
      dut.io.input(lane).robPointer #= pointer
    }
  }

  private def sample(dut: OooDispatchWindowProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def expectOutputs(dut: OooDispatchWindowProbe, pointers: Seq[Int]): Unit = {
    assert(dut.io.outputValid.toBigInt == (1 << pointers.size) - 1)
    pointers.zipWithIndex.foreach { case (pointer, lane) =>
      assert(dut.io.output(lane).robPointer.toBigInt == pointer)
    }
  }

  test("dispatch window compacts partial dispatch and refills every vacated slot") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-dispatch-window")
      .compile(new OooDispatchWindowProbe(config))
      .doSim("ooo-dispatch-window-refill", 0x4457) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sleep(1)

        driveInputs(dut, Seq(10, 11, 12))
        assert(dut.io.inputReady.toBigInt == 7)
        sample(dut)
        driveInputs(dut, Seq.empty)
        expectOutputs(dut, Seq(10, 11, 12))

        // Dispatch two and append two queue entries behind the survivor.
        dut.io.outputReady #= 3
        driveInputs(dut, Seq(20, 21, 22))
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 3)
        sample(dut)
        dut.io.outputReady #= 0
        driveInputs(dut, Seq.empty)
        expectOutputs(dut, Seq(12, 20, 21))

        // Dispatch one and fill the one newly available slot.
        dut.io.outputReady #= 1
        driveInputs(dut, Seq(30, 31))
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 1)
        sample(dut)
        dut.io.outputReady #= 0
        driveInputs(dut, Seq.empty)
        expectOutputs(dut, Seq(20, 21, 30))

        // A full drain and refill has no empty cycle at the router output.
        dut.io.outputReady #= 7
        driveInputs(dut, Seq(40, 41, 42))
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 7)
        sample(dut)
        dut.io.outputReady #= 0
        driveInputs(dut, Seq.empty)
        expectOutputs(dut, Seq(40, 41, 42))

        dut.io.flush #= true
        sample(dut)
        dut.io.flush #= false
        assert(dut.io.outputValid.toBigInt == 0)

        // Stress every occupancy/consume/refill combination with a software scoreboard. The
        // fixed table above is timing-driven, so keep its ordering and handshake behavior under
        // randomized regression instead of relying only on the directed examples.
        val random = new scala.util.Random(0x4457)
        var expected = Vector.empty[Int]
        var nextPointer = 0
        for (_ <- 0 until 500) {
          val doFlush = random.nextInt(40) == 0
          val readyCount = random.nextInt(config.dispatchWidth + 1)
          val offeredCount = random.nextInt(config.dispatchWidth + 1)
          val offered = Vector.tabulate(offeredCount) { _ =>
            val pointer = nextPointer
            nextPointer = (nextPointer + 1) & ((1 << config.robPointerWidth) - 1)
            pointer
          }

          dut.io.flush #= doFlush
          dut.io.outputReady #= ((1 << readyCount) - 1)
          driveInputs(dut, offered)
          sleep(1)

          if (doFlush) {
            // Candidate handshakes may remain visible during the flush cycle;
            // the sequential flush branch must discard all of them at the edge.
            sample(dut)
            expected = Vector.empty
          } else {
            expectOutputs(dut, expected)
            val consumedCount = math.min(readyCount, expected.size)
            val remaining = expected.drop(consumedCount)
            val available = config.dispatchWidth - remaining.size
            assert(dut.io.inputReady.toBigInt == (1 << available) - 1)
            val accepted = offered.take(available)
            sample(dut)
            expected = remaining ++ accepted
          }
        }
      }
  }
}
