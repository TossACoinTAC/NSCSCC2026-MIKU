package miku.privileged

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.language.reflectiveCalls

private final class CsrFileSimTop extends Component {
  val io = new Bundle {
    val coreClk = in Bool ()
    val coreReset = in Bool ()
    val readAddress = in Bits (14 bits)
    val readData = out Bits (32 bits)
    val writeValid = in Bool ()
    val writeAddress = in Bits (14 bits)
    val writeData = in Bits (32 bits)
    val externalInterrupt = in Bits (8 bits)
    val hasInterrupt = out Bool ()
  }

  private val csr = new CsrFile()
  csr.io.clk := io.coreClk
  csr.io.reset := io.coreReset
  csr.io.rd_addr := io.readAddress
  io.readData := csr.io.rd_data
  csr.io.csr_wr_en := io.writeValid
  csr.io.wr_addr := io.writeAddress
  csr.io.wr_data := io.writeData
  csr.io.interrupt := io.externalInterrupt
  io.hasInterrupt := csr.io.has_int
  csr.io.excp_flush := False
  csr.io.ertn_flush := False
  csr.io.era_in := 0
  csr.io.esubcode_in := 0
  csr.io.ecode_in := 0
  csr.io.va_error_in := False
  csr.io.bad_va_in := 0
  csr.io.tlbsrch_en := False
  csr.io.tlbsrch_found := False
  csr.io.tlbsrch_index := 0
  csr.io.excp_tlbrefill := False
  csr.io.excp_tlb := False
  csr.io.excp_tlb_vppn := 0
  csr.io.llbit_in := False
  csr.io.llbit_set_in := False
  csr.io.lladdr_in := 0
  csr.io.lladdr_set_in := False
  csr.io.tlbrd_en := False
  csr.io.tlbehi_in := 0
  csr.io.tlbelo0_in := 0
  csr.io.tlbelo1_in := 0
  csr.io.tlbidx_in := 0
  csr.io.asid_in := 0
}

class CsrFileSpec extends AnyFunSuite {
  test("n49 timer interrupt is level-pending and reset clears its state") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-miku-csr")
      .compile(new CsrFileSimTop)
      .doSim("miku-csr-timer-reset", 0x49) { dut =>
        dut.io.coreClk #= false
        dut.io.coreReset #= true
        dut.io.readAddress #= 0
        dut.io.writeValid #= false
        dut.io.writeAddress #= 0
        dut.io.writeData #= 0
        dut.io.externalInterrupt #= 0

        def risingEdge(): Unit = {
          dut.io.coreClk #= false
          sleep(2)
          dut.io.coreClk #= true
          sleep(2)
          dut.io.coreClk #= false
          sleep(2)
        }

        def sample(cycles: Int = 1): Unit = {
          for (_ <- 0 until cycles) risingEdge()
        }

        def write(address: Int, data: BigInt): Unit = {
          dut.io.writeValid #= true
          dut.io.writeAddress #= address
          dut.io.writeData #= data
          sample()
          dut.io.writeValid #= false
        }

        def read(address: Int): BigInt = {
          dut.io.readAddress #= address
          sleep(1)
          dut.io.readData.toBigInt
        }

        def armTimer(): Unit = {
          write(0x04, 0x1fff)
          write(0x00, 0x00000004)
          write(0x41, 0x00000121)
          assert(read(0x41) == 0x121)
          assert(read(0x42) == 0x120)
        }

        def waitForInterrupt(): Unit = {
          var cycles = 0
          while (!dut.io.hasInterrupt.toBoolean && cycles < 320) {
            sample()
            cycles += 1
          }
          assert(dut.io.hasInterrupt.toBoolean)
          assert((read(0x05) & 0x800) != 0)
        }

        sample(2)
        dut.io.coreReset #= false
        sample()

        assert(read(0xb1) == BigInt("0001f1f5", 16))
        assert(read(0xb2) == 0)
        assert(read(0xc0) == BigInt("0000001d", 16))
        assert(read(0xc1) == BigInt("06070001", 16))
        assert(read(0xc2) == BigInt("06070001", 16))
        assert(read(0xc3) == BigInt("06090001", 16))
        assert(read(0xb3) == 0)

        armTimer()
        waitForInterrupt()

        dut.io.coreReset #= true
        sample(2)
        dut.io.coreReset #= false
        sample()
        assert(!dut.io.hasInterrupt.toBoolean)
        assert((read(0x05) & 0x800) == 0)
        assert(read(0x41) == 0)
        assert(read(0x42) == 0)

        armTimer()
        waitForInterrupt()
        write(0x44, 1)
        assert(!dut.io.hasInterrupt.toBoolean)
        assert((read(0x05) & 0x800) == 0)
      }
  }
}
