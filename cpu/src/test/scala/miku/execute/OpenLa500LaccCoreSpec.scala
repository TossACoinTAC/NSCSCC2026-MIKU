package miku.execute

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.jdk.CollectionConverters._

private final class OpenLa500LaccCoreSimTop extends Component {
  val io = new Bundle {
    val coreClk = in Bool ()
    val coreReset = in Bool ()
    val flush = in Bool ()
    val requestValid = in Bool ()
    val requestCommand = in Bits (OpenLa500LaccCommand.Width bits)
    val requestImmediate = in Bits (7 bits)
    val requestRegisterJ = in Bits (32 bits)
    val requestRegisterK = in Bits (32 bits)
    val responseValid = out Bool ()
    val responseData = out Bits (32 bits)
    val memoryRequestValid = out Bool ()
    val memoryRequestReady = in Bool ()
    val memoryRequestRead = out Bool ()
    val memoryRequestAddress = out UInt (32 bits)
    val memoryRequestWriteData = out Bits (32 bits)
    val memoryRequestSize = out Bits (2 bits)
    val memoryResponseValid = in Bool ()
    val memoryResponseData = in Bits (32 bits)
    val heartbeat = out Bool ()
  }

  private val core = new OpenLa500LaccCore
  core.io.clk := io.coreClk
  core.io.reset := io.coreReset
  core.io.flush := io.flush
  core.io.request.valid := io.requestValid
  core.io.request.payload.command := io.requestCommand
  core.io.request.payload.immediate := io.requestImmediate
  core.io.request.payload.registerJ := io.requestRegisterJ
  core.io.request.payload.registerK := io.requestRegisterK
  io.responseValid := core.io.response.valid
  io.responseData := core.io.response.payload.data
  io.memoryRequestValid := core.io.memoryRequest.valid
  core.io.memoryRequest.ready := io.memoryRequestReady
  io.memoryRequestRead := core.io.memoryRequest.payload.read
  io.memoryRequestAddress := core.io.memoryRequest.payload.address
  io.memoryRequestWriteData := core.io.memoryRequest.payload.writeData
  io.memoryRequestSize := core.io.memoryRequest.payload.size
  core.io.memoryResponse.valid := io.memoryResponseValid
  core.io.memoryResponse.payload.data := io.memoryResponseData

  private val heartbeat = Reg(Bool()) init (False)
  heartbeat := !heartbeat
  io.heartbeat := heartbeat
}

class OpenLa500LaccCoreSpec extends AnyFunSuite {
  private val RandomSeed = 0x158aa8

  test("configuration, lmadd, backpressure, flush, and reset match the golden cycle contract") {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-miku-lacc")
    val workspace = Paths.get(workspaceRoot, "miku-lacc-cycle-contract").toString

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
      .compile(new OpenLa500LaccCoreSimTop)
      .doSim("lacc-cycle-contract", RandomSeed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.coreClk #= false
        dut.io.coreReset #= true
        dut.io.flush #= false
        dut.io.requestValid #= false
        dut.io.requestCommand #= OpenLa500LaccCommand.Lmadd
        dut.io.requestImmediate #= 0
        dut.io.requestRegisterJ #= 0
        dut.io.requestRegisterK #= 0
        dut.io.memoryRequestReady #= false
        dut.io.memoryResponseValid #= false
        dut.io.memoryResponseData #= 0

        def coreRisingEdge(): Unit = {
          dut.io.coreClk #= false
          sleep(2)
          dut.io.coreClk #= true
          sleep(2)
          dut.io.coreClk #= false
          sleep(2)
        }

        def coreRisingEdges(count: Int): Unit =
          for (_ <- 0 until count) coreRisingEdge()

        coreRisingEdges(2)
        dut.io.coreReset #= false
        coreRisingEdge()

        def driveRequest(command: Int, registerJ: BigInt, registerK: BigInt): Unit = {
          dut.io.requestCommand #= command
          dut.io.requestRegisterJ #= registerJ
          dut.io.requestRegisterK #= registerK
          dut.io.requestValid #= true
          sleep(1)
        }

        def stopRequest(): Unit = {
          dut.io.requestValid #= false
          sleep(1)
        }

        def configure(count: Int, destination: BigInt): Unit = {
          driveRequest(OpenLa500LaccCommand.Configure, count, destination)
          assert(dut.io.responseValid.toBoolean, "configure did not respond immediately")
          assert(!dut.io.memoryRequestValid.toBoolean, "configure issued a memory request")
          coreRisingEdge()
          stopRequest()
        }

        def expectMemoryRequest(read: Boolean, address: BigInt, writeData: Option[BigInt] = None)
            : Unit = {
          sleep(1)
          assert(dut.io.memoryRequestValid.toBoolean, f"missing request at 0x$address%08x")
          assert(
            dut.io.memoryRequestRead.toBoolean == read,
            s"unexpected read flag at 0x${address.toString(16)}"
          )
          assert(
            dut.io.memoryRequestAddress.toBigInt == address,
            f"unexpected request address, expected 0x$address%08x"
          )
          assert(dut.io.memoryRequestSize.toBigInt == 2, "LACC request is not a word access")
          writeData.foreach { expected =>
            assert(
              dut.io.memoryRequestWriteData.toBigInt == expected,
              f"unexpected write data, expected 0x$expected%08x"
            )
          }
        }

        def acceptMemoryRequest(): Unit = {
          dut.io.memoryRequestReady #= true
          coreRisingEdge()
          dut.io.memoryRequestReady #= false
        }

        def sendMemoryResponse(data: BigInt): Unit = {
          dut.io.memoryResponseData #= data
          dut.io.memoryResponseValid #= true
          coreRisingEdge()
          dut.io.memoryResponseValid #= false
        }

        configure(count = 0, destination = BigInt("3000", 16))
        driveRequest(OpenLa500LaccCommand.Lmadd, BigInt("1000", 16), BigInt("2000", 16))
        assert(dut.io.responseValid.toBoolean, "zero-count lmadd did not respond immediately")
        assert(!dut.io.memoryRequestValid.toBoolean, "zero-count lmadd issued a memory request")
        coreRisingEdge()
        stopRequest()

        configure(count = 1, destination = BigInt("3000", 16))
        driveRequest(OpenLa500LaccCommand.Lmadd, BigInt("1000", 16), BigInt("2000", 16))
        assert(!dut.io.responseValid.toBoolean, "nonzero lmadd responded before executing")
        coreRisingEdge()

        expectMemoryRequest(read = true, BigInt("1000", 16))
        val heldReadAddress = dut.io.memoryRequestAddress.toBigInt
        coreRisingEdges(2)
        expectMemoryRequest(read = true, heldReadAddress)
        acceptMemoryRequest()

        expectMemoryRequest(read = true, BigInt("2000", 16))
        coreRisingEdge()
        expectMemoryRequest(read = true, BigInt("2000", 16))
        acceptMemoryRequest()
        assert(
          !dut.io.memoryRequestValid.toBoolean,
          "read request remained asserted after acceptance"
        )

        sendMemoryResponse(5)
        assert(
          !dut.io.memoryRequestValid.toBoolean,
          "write became valid after only one read response"
        )
        sendMemoryResponse(7)
        expectMemoryRequest(read = false, BigInt("3000", 16), Some(12))
        assert(!dut.io.responseValid.toBoolean, "response preceded the final write handshake")

        val heldWriteAddress = dut.io.memoryRequestAddress.toBigInt
        val heldWriteData = dut.io.memoryRequestWriteData.toBigInt
        coreRisingEdges(2)
        expectMemoryRequest(read = false, heldWriteAddress, Some(heldWriteData))
        dut.io.memoryRequestReady #= true
        sleep(1)
        assert(dut.io.responseValid.toBoolean, "final write handshake did not assert response")
        assert(dut.io.responseData.toBigInt == 12, "response did not accumulate both read values")
        coreRisingEdge()
        dut.io.memoryRequestReady #= false
        stopRequest()
        assert(!dut.io.memoryRequestValid.toBoolean, "completed lmadd retained a memory request")

        configure(count = 2, destination = BigInt("7000", 16))
        driveRequest(OpenLa500LaccCommand.Lmadd, BigInt("4000", 16), BigInt("5000", 16))
        coreRisingEdge()

        val readAddresses = Seq(
          BigInt("4000", 16),
          BigInt("5000", 16),
          BigInt("4004", 16),
          BigInt("5004", 16)
        )
        val writeAddresses = Seq(BigInt("7000", 16), BigInt("7004", 16))
        val responseData = Seq((1, 2), (3, 4))

        for (iteration <- 0 until 2) {
          expectMemoryRequest(read = true, readAddresses(iteration * 2))
          acceptMemoryRequest()
          expectMemoryRequest(read = true, readAddresses(iteration * 2 + 1))
          acceptMemoryRequest()
          sendMemoryResponse(responseData(iteration)._1)
          sendMemoryResponse(responseData(iteration)._2)
          expectMemoryRequest(
            read = false,
            writeAddresses(iteration),
            Some(responseData(iteration)._1 + responseData(iteration)._2)
          )
          dut.io.memoryRequestReady #= true
          sleep(1)
          assert(
            dut.io.responseValid.toBoolean == (iteration == 1),
            s"response-valid mismatch on write iteration $iteration"
          )
          if (iteration == 1) {
            assert(dut.io.responseData.toBigInt == 10, "two-iteration response sum is incorrect")
          }
          coreRisingEdge()
          dut.io.memoryRequestReady #= false
        }
        stopRequest()

        configure(count = 2, destination = BigInt("a000", 16))
        driveRequest(OpenLa500LaccCommand.Lmadd, BigInt("8000", 16), BigInt("9000", 16))
        coreRisingEdge()
        expectMemoryRequest(read = true, BigInt("8000", 16))
        stopRequest()
        dut.io.flush #= true
        coreRisingEdge()
        dut.io.flush #= false
        sleep(1)
        assert(!dut.io.memoryRequestValid.toBoolean, "flush did not cancel the active request")
        assert(!dut.io.responseValid.toBoolean, "flush created an architectural response")

        driveRequest(command = 2, registerJ = 0, registerK = 0)
        assert(!dut.io.responseValid.toBoolean, "unsupported command produced a response")
        assert(
          !dut.io.memoryRequestValid.toBoolean,
          "unsupported command produced a memory request"
        )
        coreRisingEdge()
        stopRequest()

        configure(count = 1, destination = BigInt("d000", 16))
        driveRequest(OpenLa500LaccCommand.Lmadd, BigInt("b000", 16), BigInt("c000", 16))
        coreRisingEdge()
        expectMemoryRequest(read = true, BigInt("b000", 16))
        stopRequest()
        dut.io.coreReset #= true
        coreRisingEdge()
        dut.io.coreReset #= false
        sleep(1)
        assert(!dut.io.memoryRequestValid.toBoolean, "reset did not cancel the active request")
        assert(!dut.io.responseValid.toBoolean, "reset created an architectural response")
      }
  }

  test("generated legacy wrapper has exactly the golden LACC ports") {
    val outputDirectory = Files.createTempDirectory("miku-lacc-rtl-")
    try {
      GenerateOpenLa500LaccCore.main(Array(outputDirectory.toString))

      val rtl = Files.readString(outputDirectory.resolve("lacc_core.v"), StandardCharsets.UTF_8)
      val moduleHeader = "(?s)module\\s+lacc_core\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain module lacc_core"))
      val declaredPorts =
        "(?m)^\\s*(?:input|output)\\s+wire(?:\\s+\\[[^]]+\\])?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*$".r
          .findAllMatchIn(moduleHeader)
          .map(_.group(1))
          .toSeq

      assert(
        declaredPorts == Seq(
          "clk",
          "reset",
          "lacc_flush",
          "lacc_req_valid",
          "lacc_req_command",
          "lacc_req_imm",
          "lacc_req_rj",
          "lacc_req_rk",
          "lacc_rsp_valid",
          "lacc_rsp_rdat",
          "lacc_data_valid",
          "lacc_data_ready",
          "lacc_data_addr",
          "lacc_data_read",
          "lacc_data_wdata",
          "lacc_data_size",
          "lacc_drsp_valid",
          "lacc_drsp_rdata"
        )
      )
      assert(!moduleHeader.contains("io_"))
      assert(!rtl.contains("`timescale"))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
