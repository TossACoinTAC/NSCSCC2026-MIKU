package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class DecodeRenameBufferProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.decodeWidth bits)
    val inputTag = in Vec (Bits(DecodedMicroOp(config).getBitsWidth bits), config.decodeWidth)
    val inputReady = out Bits (config.decodeWidth bits)
    val outputValid = out Bits (config.renameWidth bits)
    val outputTag = out Vec (Bits(DecodedMicroOp(config).getBitsWidth bits), config.renameWidth)
    val outputReady = in Bits (config.renameWidth bits)
    val flush = in Bool ()
  }
  noIoPrefix()

  val buffer = new DecodeRenameBuffer(config)
  buffer.io.inputValid := io.inputValid
  for (lane <- 0 until config.decodeWidth) {
    buffer.io.input(lane).assignFromBits(io.inputTag(lane))
  }
  buffer.io.outputReady := io.outputReady
  buffer.io.flush := io.flush
  io.inputReady := buffer.io.inputReady
  io.outputValid := buffer.io.outputValid
  for (lane <- 0 until config.renameWidth) {
    io.outputTag(lane) := buffer.io.output(lane).asBits
  }
}

class DecodeRenameBufferSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("partial oldest acceptance compacts survivors and appends new decode lanes") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-decode-rename-buffer")
      .compile(new DecodeRenameBufferProbe(config))
      .doSim("ooo-decode-rename-partial-compaction", 0xD301) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.inputValid #= 0
        dut.io.inputReady #= 0
        dut.io.outputReady #= 0
        dut.io.flush #= false
        for (lane <- 0 until config.decodeWidth) dut.io.inputTag(lane) #= 0
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.inputValid #= 7
        dut.io.inputTag(0) #= 10
        dut.io.inputTag(1) #= 11
        dut.io.inputTag(2) #= 12
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 7)
        dut.clockDomain.waitSampling()
        dut.io.inputValid #= 1
        dut.io.inputTag(0) #= 13
        dut.io.outputReady #= 1
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 1)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.outputValid.toBigInt == 7)
        assert(dut.io.outputTag.map(_.toBigInt).toSeq == Seq(11, 12, 13))
      }
  }

  test("partial acceptance with two survivors can refill two tail slots") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
        "/sim-workspace-ooo-decode-rename-buffer")
      .compile(new DecodeRenameBufferProbe(config))
      .doSim("ooo-decode-rename-two-slot-compaction", 0xD302) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.inputValid #= 3
        dut.io.inputTag(0) #= 20
        dut.io.inputTag(1) #= 21
        dut.io.outputReady #= 0
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.inputValid #= 3
        dut.io.inputTag(0) #= 22
        dut.io.inputTag(1) #= 23
        dut.io.outputReady #= 1
        sleep(1)
        assert(dut.io.inputReady.toBigInt == 3)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.outputValid.toBigInt == 7)
        assert(dut.io.outputTag.map(_.toBigInt).toSeq == Seq(21, 22, 23))
      }
  }
}
