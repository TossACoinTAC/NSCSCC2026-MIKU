package miku.execute

import java.nio.file.Paths
import miku.memory.LegacyDataCache
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** The active Backend connects LACC request acceptance to dcache.addr_ok, while its read response
  * is qualified by dcache.data_ok. This harness keeps that exact distinction and drives the locked
  * cache refill channels from the test, so a request cannot accidentally advance on data_ok or
  * return data on addr_ok.
  */
private final class LaccDCacheIntegrationTop extends Component {
  val io = new Bundle {
    val coreClk = in Bool ()
    val coreReset = in Bool ()
    val flush = in Bool ()
    val requestValid = in Bool ()
    val requestCommand = in Bits (LaccCommand.Width bits)
    val requestImmediate = in Bits (7 bits)
    val requestRegisterJ = in Bits (32 bits)
    val requestRegisterK = in Bits (32 bits)

    val responseValid = out Bool ()
    val responseData = out Bits (32 bits)
    val memoryRequestValid = out Bool ()
    val memoryRequestReady = out Bool ()
    val memoryRequestRead = out Bool ()
    val memoryRequestAddress = out UInt (32 bits)
    val memoryRequestWriteData = out Bits (32 bits)
    val memoryRequestSize = out Bits (2 bits)
    val memoryResponseValid = out Bool ()
    val memoryResponseData = out Bits (32 bits)

    val dcacheAddrOk = out Bool ()
    val dcacheDataOk = out Bool ()
    val dcacheRdata = out Bits (32 bits)
    val dcacheSize = in Bits (3 bits)
    val dcachePreloadHint = in Bits (5 bits)
    val dcacheEmpty = out Bool ()
    val dcacheMiss = out Bool ()
    val rdReq = out Bool ()
    val rdType = out Bits (3 bits)
    val rdAddr = out Bits (32 bits)
    val rdReady = in Bool ()
    val retValid = in Bool ()
    val retLast = in Bool ()
    val retData = in Bits (32 bits)
    val wrReq = out Bool ()
    val wrType = out Bits (3 bits)
    val wrAddr = out Bits (32 bits)
    val wrStrb = out Bits (4 bits)
    val wrData = out Bits (128 bits)
    val wrReady = in Bool ()
    val heartbeat = out Bool ()
  }

  private val lacc = new LaccCore
  private val dcache = new LegacyDataCache

  lacc.io.clk := io.coreClk
  lacc.io.reset := io.coreReset
  lacc.io.flush := io.flush
  lacc.io.request.valid := io.requestValid
  lacc.io.request.payload.command := io.requestCommand
  lacc.io.request.payload.immediate := io.requestImmediate
  lacc.io.request.payload.registerJ := io.requestRegisterJ
  lacc.io.request.payload.registerK := io.requestRegisterK

  dcache.io.clk := io.coreClk
  dcache.io.reset := io.coreReset
  dcache.io.valid := lacc.io.memoryRequest.valid
  dcache.io.op := !lacc.io.memoryRequest.payload.read
  dcache.io.size := io.dcacheSize
  dcache.io.index := lacc.io.memoryRequest.payload.address(11 downto 4).asBits
  dcache.io.tag := lacc.io.memoryRequest.payload.address(31 downto 12).asBits
  dcache.io.offset := lacc.io.memoryRequest.payload.address(3 downto 0).asBits
  // LACC emits only aligned word operations; this is the same mask selected by ExecuteStage.
  dcache.io.wstrb := B"4'b1111"
  dcache.io.wdata := lacc.io.memoryRequest.payload.writeData
  dcache.io.uncache_en := False
  dcache.io.dcacop_op_en := False
  dcache.io.cacop_op_mode := 0
  dcache.io.preld_hint := io.dcachePreloadHint
  dcache.io.preld_hint.allowPruning()
  dcache.io.preld_en := False
  dcache.io.tlb_excp_cancel_req := False
  dcache.io.sc_cancel_req := False
  dcache.io.rd_rdy := io.rdReady
  dcache.io.ret_valid := io.retValid
  dcache.io.ret_last := io.retLast
  dcache.io.ret_data := io.retData
  dcache.io.wr_rdy := io.wrReady

  // These assignments intentionally mirror the legacy LACC/cache integration contract.
  lacc.io.memoryRequest.ready := dcache.io.addr_ok
  lacc.io.memoryResponse.valid := lacc.io.request.valid && dcache.io.data_ok
  lacc.io.memoryResponse.payload.data := dcache.io.rdata

  io.responseValid := lacc.io.response.valid
  io.responseData := lacc.io.response.payload.data
  io.memoryRequestValid := lacc.io.memoryRequest.valid
  io.memoryRequestReady := dcache.io.addr_ok
  io.memoryRequestRead := lacc.io.memoryRequest.payload.read
  io.memoryRequestAddress := lacc.io.memoryRequest.payload.address
  io.memoryRequestWriteData := lacc.io.memoryRequest.payload.writeData
  io.memoryRequestSize := lacc.io.memoryRequest.payload.size
  io.memoryResponseValid := lacc.io.memoryResponse.valid
  io.memoryResponseData := lacc.io.memoryResponse.payload.data
  io.dcacheAddrOk := dcache.io.addr_ok
  io.dcacheDataOk := dcache.io.data_ok
  io.dcacheRdata := dcache.io.rdata
  io.dcacheEmpty := dcache.io.dcache_empty
  io.dcacheMiss := dcache.io.cache_miss
  io.rdReq := dcache.io.rd_req
  io.rdType := dcache.io.rd_type
  io.rdAddr := dcache.io.rd_addr
  io.wrReq := dcache.io.wr_req
  io.wrType := dcache.io.wr_type
  io.wrAddr := dcache.io.wr_addr
  io.wrStrb := dcache.io.wr_wstrb
  io.wrData := dcache.io.wr_data

  private val heartbeatReg = Reg(Bool()) init (False)
  heartbeatReg := !heartbeatReg
  io.heartbeat := heartbeatReg
}

class LaccDCacheIntegrationSpec extends AnyFunSuite {
  private val RandomSeed = 0x158aa8

  test("cross-line reads use addr_ok for acceptance and data_ok for response") {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-miku-lacc")
    val workspace = Paths.get(workspaceRoot, "miku-lacc-dcache-integration").toString

    SimConfig
      .withConfig(SpinalConfig(oneFilePerComponent = true))
      .withVerilator
      .addSimulatorFlag("-Wall")
      .addSimulatorFlag("-Wwarn-WIDTH")
      .addSimulatorFlag("-Wwarn-UNOPTFLAT")
      .addSimulatorFlag("-Wwarn-CMPCONST")
      .addSimulatorFlag("-Wwarn-UNSIGNED")
      .addSimulatorFlag("-Wno-UNUSEDSIGNAL")
      .disableCache
      .workspacePath(workspace)
      .compile(new LaccDCacheIntegrationTop)
      .doSim("cross-line-lacc-dcache", RandomSeed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.coreClk #= false
        dut.io.coreReset #= true
        dut.io.flush #= false
        dut.io.requestValid #= false
        dut.io.requestCommand #= LaccCommand.Lmadd
        dut.io.requestImmediate #= 0
        dut.io.requestRegisterJ #= 0
        dut.io.requestRegisterK #= 0
        dut.io.dcacheSize #= 2
        dut.io.dcachePreloadHint #= 0
        dut.io.rdReady #= false
        dut.io.retValid #= false
        dut.io.retLast #= false
        dut.io.retData #= 0
        dut.io.wrReady #= true

        def coreRisingEdge(): Unit = {
          dut.io.coreClk #= false
          sleep(2)
          dut.io.coreClk #= true
          sleep(2)
          dut.io.coreClk #= false
          sleep(2)
        }

        def configure(count: Int, destination: BigInt): Unit = {
          dut.io.requestCommand #= LaccCommand.Configure
          dut.io.requestRegisterJ #= count
          dut.io.requestRegisterK #= destination
          dut.io.requestValid #= true
          sleep(1)
          assert(dut.io.responseValid.toBoolean, "LACC configure must respond immediately")
          assert(!dut.io.memoryRequestValid.toBoolean, "configure must not touch DCache")
          coreRisingEdge()
          dut.io.requestValid #= false
          sleep(1)
        }

        def startLmadd(source1: BigInt, source2: BigInt): Unit = {
          dut.io.requestCommand #= LaccCommand.Lmadd
          dut.io.requestRegisterJ #= source1
          dut.io.requestRegisterK #= source2
          dut.io.requestValid #= true
          sleep(1)
          assert(!dut.io.responseValid.toBoolean, "non-zero lmadd responded before memory work")
          coreRisingEdge()
        }

        def assertRequest(expectedAddress: BigInt, expectedRead: Boolean): Unit = {
          sleep(1)
          assert(dut.io.memoryRequestValid.toBoolean, "LACC memory request disappeared")
          assert(
            dut.io.memoryRequestAddress.toBigInt == expectedAddress,
            f"unexpected LACC address 0x${dut.io.memoryRequestAddress.toBigInt}%08x"
          )
          assert(
            dut.io.memoryRequestRead.toBoolean == expectedRead,
            s"unexpected LACC read/write direction at 0x${expectedAddress.toString(16)}"
          )
          assert(dut.io.memoryRequestSize.toBigInt == 2, "LACC transaction is not a word")
        }

        def assertNoRequest(): Unit = {
          sleep(1)
          assert(!dut.io.memoryRequestValid.toBoolean, "unexpected LACC memory request")
        }

        var acceptedReads = 0
        var readResponses = 0

        def acceptRequest(): Unit = {
          assert(dut.io.memoryRequestReady.toBoolean, "DCache addr_ok did not accept request")
          if (dut.io.memoryRequestRead.toBoolean) acceptedReads += 1
          coreRisingEdge()
        }

        def checkPendingRequest(pending: Option[(BigInt, Boolean)]): Unit =
          pending match {
            case Some((address, read)) => assertRequest(address, read)
            case None                  => assertNoRequest()
          }

        def waitForRefillRequest(
            expectedLine: BigInt,
            pending: Option[(BigInt, Boolean)]
        ): Unit = {
          var observed = false
          var cycles = 0
          dut.io.rdReady #= true
          while (!observed && cycles < 64) {
            sleep(1)
            checkPendingRequest(pending)
            pending.foreach { _ =>
              assert(
                !dut.io.memoryRequestReady.toBoolean,
                "DCache exposed addr_ok while a read miss was still refilling"
              )
            }
            if (dut.io.rdReq.toBoolean) {
              observed = true
              assert(
                dut.io.rdAddr.toBigInt == expectedLine,
                f"unexpected refill line 0x${dut.io.rdAddr.toBigInt}%08x"
              )
            }
            coreRisingEdge()
            cycles += 1
          }
          dut.io.rdReady #= false
          assert(observed, s"DCache did not issue refill request for 0x$expectedLine%08x")
        }

        def sendRefill(
            lineData: Seq[Int],
            targetWord: Int,
            pendingBeforeResponse: Option[(BigInt, Boolean)],
            pendingAfterResponse: Option[(BigInt, Boolean)]
        ): Unit = {
          require(lineData.size == 4)
          lineData.zipWithIndex.foreach { case (data, word) =>
            dut.io.retValid #= true
            dut.io.retLast #= word == lineData.size - 1
            dut.io.retData #= data
            sleep(1)
            val pending =
              if (word <= targetWord) pendingBeforeResponse else pendingAfterResponse
            checkPendingRequest(pending)
            val dataOk = dut.io.dcacheDataOk.toBoolean
            assert(
              dataOk == (word == targetWord),
              s"DCache data_ok mismatch on refill word $word (target $targetWord)"
            )
            if (dataOk) {
              readResponses += 1
              assert(
                readResponses <= acceptedReads,
                "DCache returned a read response without an accepted read"
              )
              assert(
                dut.io.memoryResponseValid.toBoolean,
                "data_ok was not forwarded as LACC response"
              )
              assert(dut.io.memoryResponseData.toBigInt == data, "wrong read response data")
            }
            coreRisingEdge()
            dut.io.retValid #= false
            dut.io.retLast #= false
          }
        }

        coreRisingEdge()
        coreRisingEdge()
        dut.io.coreReset #= false
        coreRisingEdge()

        configure(count = 1, destination = BigInt("1008", 16))
        startLmadd(source1 = BigInt("100c", 16), source2 = BigInt("1010", 16))

        // First source is the last word of line 0x1000, so data_ok must wait for beat 3.
        assertRequest(BigInt("100c", 16), expectedRead = true)
        acceptRequest()
        waitForRefillRequest(
          expectedLine = BigInt("1000", 16),
          pending = Some(BigInt("1010", 16) -> true)
        )
        sendRefill(
          Seq(10, 11, 12, 13),
          targetWord = 3,
          pendingBeforeResponse = Some(BigInt("1010", 16) -> true),
          pendingAfterResponse = Some(BigInt("1010", 16) -> true)
        )

        // The second source is a different line.  It must not be consumed until the first
        // refill reaches idle, and its target word is returned on the first refill beat.
        assertRequest(BigInt("1010", 16), expectedRead = true)
        acceptRequest()
        waitForRefillRequest(expectedLine = BigInt("1010", 16), pending = None)
        sendRefill(
          Seq(20, 21, 22, 23),
          targetWord = 0,
          pendingBeforeResponse = None,
          pendingAfterResponse = Some(BigInt("1008", 16) -> false)
        )

        assert(acceptedReads == 2, "the integration did not accept exactly two reads")
        assert(readResponses == 2, "accepted reads and data_ok responses are not one-to-one")

        // The destination is in the first filled line.  The final response is tied to
        // addr_ok (write request acceptance), while dcache.data_ok may arrive later.
        assertRequest(BigInt("1008", 16), expectedRead = false)
        assert(dut.io.memoryRequestWriteData.toBigInt == 33, "LACC write sum is incorrect")
        assert(dut.io.memoryRequestReady.toBoolean, "final write was not accepted by addr_ok")
        assert(dut.io.responseValid.toBoolean, "LACC response did not align with final write")
        assert(dut.io.responseData.toBigInt == 33, "LACC accumulated response is incorrect")
        coreRisingEdge()
        dut.io.requestValid #= false
        sleep(1)

        assert(!dut.io.memoryRequestValid.toBoolean, "completed LACC retained a memory request")
        assert(!dut.io.responseValid.toBoolean, "completed LACC retained a response")
      }
  }
}
