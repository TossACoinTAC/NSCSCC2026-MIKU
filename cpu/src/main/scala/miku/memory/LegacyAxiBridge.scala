package miku.memory

import miku.compat.Axi3Compat
import spinal.core._
import spinal.lib._

/** Cycle-compatible implementation of `a158aa8:rtl/axi_bridge.v`.
  *
  * The legacy bridge accepts at most one AR and one write transaction at a time. Data reads have
  * priority over instruction reads, reads wait for an outstanding B response, and R is never
  * backpressured. Writes deliberately serialize AW, W, and B; a cache-line request emits four
  * 32-bit W beats while scalar writes emit one. All state belongs to the explicit `clk`,
  * active-high synchronous-reset domain.
  */
final class LegacyAxiBridge extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()

    val arid = out Bits (4 bits)
    val araddr = out Bits (32 bits)
    val arlen = out Bits (8 bits)
    val arsize = out Bits (3 bits)
    val arburst = out Bits (2 bits)
    val arlock = out Bits (2 bits)
    val arcache = out Bits (4 bits)
    val arprot = out Bits (3 bits)
    val arvalid = out Bool ()
    val arready = in Bool ()

    val rid = in Bits (4 bits)
    val rdata = in Bits (32 bits)
    val rresp = in Bits (2 bits)
    val rlast = in Bool ()
    val rvalid = in Bool ()
    val rready = out Bool ()

    val awid = out Bits (4 bits)
    val awaddr = out Bits (32 bits)
    val awlen = out Bits (8 bits)
    val awsize = out Bits (3 bits)
    val awburst = out Bits (2 bits)
    val awlock = out Bits (2 bits)
    val awcache = out Bits (4 bits)
    val awprot = out Bits (3 bits)
    val awvalid = out Bool ()
    val awready = in Bool ()

    val wid = out Bits (4 bits)
    val wdata = out Bits (32 bits)
    val wstrb = out Bits (4 bits)
    val wlast = out Bool ()
    val wvalid = out Bool ()
    val wready = in Bool ()

    val bid = in Bits (4 bits)
    val bresp = in Bits (2 bits)
    val bvalid = in Bool ()
    val bready = out Bool ()

    val inst_rd_req = in Bool ()
    val inst_rd_type = in Bits (3 bits)
    val inst_rd_addr = in Bits (32 bits)
    val inst_rd_rdy = out Bool ()
    val inst_ret_valid = out Bool ()
    val inst_ret_last = out Bool ()
    val inst_ret_data = out Bits (32 bits)

    val inst_wr_req = in Bool ()
    val inst_wr_type = in Bits (3 bits)
    val inst_wr_addr = in Bits (32 bits)
    val inst_wr_wstrb = in Bits (4 bits)
    val inst_wr_data = in Bits (128 bits)
    val inst_wr_rdy = out Bool ()

    val data_rd_req = in Bool ()
    val data_rd_type = in Bits (3 bits)
    val data_rd_addr = in Bits (32 bits)
    val data_rd_rdy = out Bool ()
    val data_ret_valid = out Bool ()
    val data_ret_last = out Bool ()
    val data_ret_data = out Bits (32 bits)

    val data_wr_req = in Bool ()
    val data_wr_type = in Bits (3 bits)
    val data_wr_addr = in Bits (32 bits)
    val data_wr_wstrb = in Bits (4 bits)
    val data_wr_data = in Bits (128 bits)
    val data_wr_rdy = out Bool ()
    val write_buffer_empty = out Bool ()
  }

  noIoPrefix()

  private val bridgeClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  private val logic = new ClockingArea(bridgeClockDomain) {
    private val WriteEmpty = U(0, 3 bits)
    private val WriteDataTransform = U(4, 3 bits)
    private val WriteDataWait = U(5, 3 bits)
    private val WriteWaitResponse = U(6, 3 bits)

    val readRequestBusy = Reg(Bool()) init (False)
    val readResponseBusy = Reg(Bool()) init (False)
    val writeState = Reg(UInt(3 bits)) init (WriteEmpty)

    val arid = Reg(Bits(4 bits))
    val araddr = Reg(Bits(32 bits))
    val arlen = Reg(Bits(8 bits))
    val arsize = Reg(Bits(3 bits))
    val arvalid = Reg(Bool()) init (False)

    val rready = Reg(Bool()) init (True)

    val awaddr = Reg(Bits(32 bits))
    val awlen = Reg(Bits(8 bits))
    val awsize = Reg(Bits(3 bits))
    val awvalid = Reg(Bool()) init (False)
    val wdata = Reg(Bits(32 bits))
    val wstrb = Reg(Bits(4 bits))
    val wlast = Reg(Bool()) init (False)
    val wvalid = Reg(Bool()) init (False)
    val bready = Reg(Bool()) init (False)
    val writeBufferData = Reg(Bits(128 bits)) init (0)
    val writeBufferCount = Reg(UInt(3 bits)) init (0)

    val writeBusy = writeState =/= WriteEmpty
    val completingWrite = io.bvalid && bready

    // Golden assigns rready only in the synchronous reset branch and then holds it forever.
    rready := rready

    private def captureRead(id: Bits, address: Bits, requestType: Bits): Unit = {
      val cacheLine = requestType === B"3'b100"
      readRequestBusy := True
      arid := id
      araddr := address
      arsize := Mux(cacheLine, B"3'b010", requestType)
      arlen := Mux(cacheLine, B"8'h03", B"8'h00")
      arvalid := True
    }

    when(!readRequestBusy) {
      when(io.data_rd_req) {
        when(!writeBusy || completingWrite) {
          captureRead(B"4'h1", io.data_rd_addr, io.data_rd_type)
        }
      }.elsewhen(io.inst_rd_req) {
        when(!writeBusy || completingWrite) {
          captureRead(B"4'h0", io.inst_rd_addr, io.inst_rd_type)
        }
      }
    }.elsewhen(io.arready) {
      readRequestBusy := False
      arvalid := False
    }

    when(!readResponseBusy) {
      when(io.rvalid && rready) {
        readResponseBusy := True
      }
    }.otherwise {
      when(io.rlast && io.rvalid) {
        readResponseBusy := False
      }
    }

    switch(writeState) {
      is(WriteEmpty) {
        when(io.data_wr_req) {
          val cacheLine = io.data_wr_type === B"3'b100"
          writeState := WriteDataWait
          awaddr := io.data_wr_addr
          awsize := Mux(cacheLine, B"3'b010", io.data_wr_type)
          awlen := Mux(cacheLine, B"8'h03", B"8'h00")
          awvalid := True
          wdata := io.data_wr_data(31 downto 0)
          wstrb := io.data_wr_wstrb
          writeBufferData := B(0, 32 bits) ## io.data_wr_data(127 downto 32)
          when(cacheLine) {
            writeBufferCount := 3
          }.otherwise {
            writeBufferCount := 0
            wlast := True
          }
        }
      }
      is(WriteDataWait) {
        when(io.awready) {
          writeState := WriteDataTransform
          awvalid := False
          wvalid := True
        }
      }
      is(WriteDataTransform) {
        when(io.wready) {
          when(wlast) {
            writeState := WriteWaitResponse
            wvalid := False
            wlast := False
            bready := True
          }.otherwise {
            when(writeBufferCount === 1) {
              wlast := True
            }
            wdata := writeBufferData(31 downto 0)
            wvalid := True
            writeBufferData := B(0, 32 bits) ## writeBufferData(127 downto 32)
            writeBufferCount := writeBufferCount - 1
          }
        }
      }
      is(WriteWaitResponse) {
        when(io.bvalid && bready) {
          writeState := WriteEmpty
          bready := False
        }
      }
      default {
        writeState := WriteEmpty
      }
    }
  }

  private val readCanReceive =
    !logic.readRequestBusy && !(logic.writeBusy && !(io.bvalid && logic.bready))

  io.arid := logic.arid
  io.araddr := logic.araddr
  io.arlen := logic.arlen
  io.arsize := logic.arsize
  io.arburst := B"2'b01"
  io.arlock := B"2'b00"
  io.arcache := B"4'b0000"
  io.arprot := B"3'b000"
  io.arvalid := logic.arvalid
  io.rready := logic.rready

  io.awid := B"4'h1"
  io.awaddr := logic.awaddr
  io.awlen := logic.awlen
  io.awsize := logic.awsize
  io.awburst := B"2'b01"
  io.awlock := B"2'b00"
  io.awcache := B"4'b0000"
  io.awprot := B"3'b000"
  io.awvalid := logic.awvalid
  io.wid := B"4'h1"
  io.wdata := logic.wdata
  io.wstrb := logic.wstrb
  io.wlast := logic.wlast
  io.wvalid := logic.wvalid
  io.bready := logic.bready

  io.inst_rd_rdy := !io.data_rd_req && readCanReceive
  io.inst_ret_valid := !io.rid(0) && io.rvalid
  io.inst_ret_last := !io.rid(0) && io.rlast
  io.inst_ret_data := io.rdata
  io.inst_wr_rdy := True

  io.data_rd_rdy := readCanReceive
  io.data_ret_valid := io.rid(0) && io.rvalid
  io.data_ret_last := io.rid(0) && io.rlast
  io.data_ret_data := io.rdata
  io.data_wr_rdy := !logic.writeBusy
  io.write_buffer_empty := (logic.writeBufferCount === 0) && !logic.writeBusy
}

/** Typed cache/AXI boundary around the cycle-compatible legacy bridge.
  *
  * The adapter owns no state. It only maps the existing cache transaction pins to the directioned
  * line contracts and maps the legacy AXI3/WID pins to [[Axi3Compat]]. Keeping the legacy bridge as
  * a child preserves its verified arbitration, burst, and reset behavior while allowing the active
  * backend to depend on typed contracts.
  */
final class TypedAxiBridge extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val inst = slave(LineReadWritePort())
    val data = slave(LineReadWritePort())
    val axi = master(Axi3Compat())
    val writeBufferEmpty = out Bool ()
  }

  noIoPrefix()

  private val legacy = new LegacyAxiBridge
  legacy.io.clk := io.clk
  legacy.io.reset := io.reset

  legacy.io.inst_rd_req := io.inst.read.valid
  legacy.io.inst_rd_type := io.inst.read.payload.requestType
  legacy.io.inst_rd_addr := io.inst.read.payload.address.asBits
  io.inst.read.ready := legacy.io.inst_rd_rdy
  io.inst.readResponse.valid := legacy.io.inst_ret_valid
  io.inst.readResponse.payload.last := legacy.io.inst_ret_last
  io.inst.readResponse.payload.data := legacy.io.inst_ret_data

  legacy.io.inst_wr_req := io.inst.write.valid
  legacy.io.inst_wr_type := io.inst.write.payload.requestType
  legacy.io.inst_wr_addr := io.inst.write.payload.address.asBits
  legacy.io.inst_wr_wstrb := io.inst.write.payload.byteMask
  legacy.io.inst_wr_data := io.inst.write.payload.data
  io.inst.write.ready := legacy.io.inst_wr_rdy

  legacy.io.data_rd_req := io.data.read.valid
  legacy.io.data_rd_type := io.data.read.payload.requestType
  legacy.io.data_rd_addr := io.data.read.payload.address.asBits
  io.data.read.ready := legacy.io.data_rd_rdy
  io.data.readResponse.valid := legacy.io.data_ret_valid
  io.data.readResponse.payload.last := legacy.io.data_ret_last
  io.data.readResponse.payload.data := legacy.io.data_ret_data

  legacy.io.data_wr_req := io.data.write.valid
  legacy.io.data_wr_type := io.data.write.payload.requestType
  legacy.io.data_wr_addr := io.data.write.payload.address.asBits
  legacy.io.data_wr_wstrb := io.data.write.payload.byteMask
  legacy.io.data_wr_data := io.data.write.payload.data
  io.data.write.ready := legacy.io.data_wr_rdy
  io.writeBufferEmpty := legacy.io.write_buffer_empty

  legacy.io.arready := io.axi.ar.ready
  legacy.io.rid := io.axi.r.payload.id
  legacy.io.rdata := io.axi.r.payload.data
  legacy.io.rresp := io.axi.r.payload.response
  legacy.io.rlast := io.axi.r.payload.last
  legacy.io.rvalid := io.axi.r.valid
  legacy.io.awready := io.axi.aw.ready
  legacy.io.wready := io.axi.w.ready
  legacy.io.bid := io.axi.b.payload.id
  legacy.io.bresp := io.axi.b.payload.response
  legacy.io.bvalid := io.axi.b.valid

  io.axi.ar.payload.id := legacy.io.arid
  io.axi.ar.payload.address := legacy.io.araddr
  io.axi.ar.payload.len := legacy.io.arlen
  io.axi.ar.payload.size := legacy.io.arsize
  io.axi.ar.payload.burst := legacy.io.arburst
  io.axi.ar.payload.lock := legacy.io.arlock
  io.axi.ar.payload.cache := legacy.io.arcache
  io.axi.ar.payload.prot := legacy.io.arprot
  io.axi.ar.valid := legacy.io.arvalid
  io.axi.r.ready := legacy.io.rready
  io.axi.aw.payload.id := legacy.io.awid
  io.axi.aw.payload.address := legacy.io.awaddr
  io.axi.aw.payload.len := legacy.io.awlen
  io.axi.aw.payload.size := legacy.io.awsize
  io.axi.aw.payload.burst := legacy.io.awburst
  io.axi.aw.payload.lock := legacy.io.awlock
  io.axi.aw.payload.cache := legacy.io.awcache
  io.axi.aw.payload.prot := legacy.io.awprot
  io.axi.aw.valid := legacy.io.awvalid
  io.axi.w.payload.id := legacy.io.wid
  io.axi.w.payload.data := legacy.io.wdata
  io.axi.w.payload.byteMask := legacy.io.wstrb
  io.axi.w.payload.last := legacy.io.wlast
  io.axi.w.valid := legacy.io.wvalid
  io.axi.b.ready := legacy.io.bready
}
