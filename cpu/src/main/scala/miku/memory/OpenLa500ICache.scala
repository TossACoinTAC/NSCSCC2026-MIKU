package miku.memory

import spinal.core._

/** Cycle-compatible implementation of `a158aa8:rtl/icache.v`.
  *
  * Inputs are the exact legacy request/refill contract. A request spends one cycle in lookup; cache
  * hits may accept the next request in that cycle, while misses serialize one AXI-side read. The
  * two-way tag and four-bank data memories are synchronous-read, unreset memories. Lookup, refill,
  * backpressure, cancellation and the historically ineffective CACOP path are preserved.
  */
final class OpenLa500ICache extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()

    val valid = in Bool ()
    val op = in Bool ()
    val index = in Bits (8 bits)
    val tag = in Bits (20 bits)
    val offset = in Bits (4 bits)
    val wstrb = in Bits (4 bits)
    val wdata = in Bits (32 bits)
    val addr_ok = out Bool ()
    val data_ok = out Bool ()
    val rdata = out Bits (32 bits)
    val uncache_en = in Bool ()
    val icacop_op_en = in Bool ()
    val cacop_op_mode = in Bits (2 bits)
    val cacop_op_addr_index = in Bits (8 bits)
    val cacop_op_addr_tag = in Bits (20 bits)
    val cacop_op_addr_offset = in Bits (4 bits)
    val icache_unbusy = out Bool ()
    val tlb_excp_cancel_req = in Bool ()

    val rd_req = out Bool ()
    val rd_type = out Bits (3 bits)
    val rd_addr = out Bits (32 bits)
    val rd_rdy = in Bool ()
    val ret_valid = in Bool ()
    val ret_last = in Bool ()
    val ret_data = in Bits (32 bits)
    val wr_req = out Bool ()
    val wr_type = out Bits (3 bits)
    val wr_addr = out Bits (32 bits)
    val wr_wstrb = out Bits (4 bits)
    val wr_data = out Bits (128 bits)
    val wr_rdy = in Bool ()

    val cache_miss = out Bool ()
  }

  noIoPrefix()

  private val cacheClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  private val logic = new ClockingArea(cacheClockDomain) {
    val MainIdle = B"5'b00001"
    val MainLookup = B"5'b00010"
    val MainReplace = B"5'b01000"
    val MainRefill = B"5'b10000"

    val mainState = Reg(Bits(5 bits)) init (MainIdle)
    val requestIndex = Reg(Bits(8 bits)) init (0)
    val requestTag = Reg(Bits(20 bits)) init (0)
    val requestOffset = Reg(Bits(4 bits)) init (0)
    val requestUncache = Reg(Bool()) init (False)
    val requestCacop = Reg(Bool()) init (False)
    val requestCacopMode = Reg(Bits(2 bits)) init (0)
    val missReplaceWay = Reg(Bits(2 bits)) init (0)
    val missRetNum = Reg(UInt(2 bits))
    val lookupWayHitBuffer = Reg(Bits(2 bits)) init (0)
    val rdReqBuffer = Reg(Bool()) init (False)
    val lfsr = Reg(Bits(8 bits)) init (B"8'b00000001")
    val legacyWrReq = Reg(Bool()) init (False)

    val dataMem = Array.fill(2, 4)(Mem(Bits(32 bits), 256))
    val tagMem = Array.fill(2)(Mem(Bits(21 bits), 256))

    val isIdle = mainState === MainIdle
    val isLookup = mainState === MainLookup
    val isReplace = mainState === MainReplace
    val isRefill = mainState === MainRefill

    val realOffset = Mux(io.icacop_op_en, io.cacop_op_addr_offset, io.offset)
    val realIndex = Mux(io.icacop_op_en, io.cacop_op_addr_index, io.index)
    val realTag = Mux(requestCacop, io.cacop_op_addr_tag, io.tag)
    val requestValid = io.valid || io.icacop_op_en

    val mode0 = requestCacop && requestCacopMode === B"2'b00"
    val mode1 = requestCacop && (requestCacopMode === B"2'b01" || requestCacopMode === B"2'b11")
    val mode2 = requestCacop && requestCacopMode === B"2'b10"
    val mode2HitWrite = mode2 && lookupWayHitBuffer.orR

    val tagOutputs = Vec(Bits(21 bits), 2)
    val dataOutputs = Array.fill(2)(Vec(Bits(32 bits), 4))

    val wayHit = Bits(2 bits)
    for (way <- 0 until 2) {
      wayHit(way) := tagOutputs(way)(0) && tagOutputs(way)(20 downto 1) === realTag
    }
    // CACOP follows the locked passing d22c13c state path: it must not be
    // treated as a normal lookup hit, even when the indexed line is valid.
    val cacheHit = wayHit.orR && !(io.uncache_en || mode0 || mode1 || mode2)
    val addrOk = (isIdle || (isLookup && cacheHit)) && !io.icacop_op_en

    val wayWords = Vec(Bits(32 bits), 2)
    for (way <- 0 until 2) {
      wayWords(way) := dataOutputs(way)(requestOffset(3 downto 2).asUInt)
    }
    val loadResult =
      Mux(wayHit(0), wayWords(0), B(0, 32 bits)) |
        Mux(wayHit(1), wayWords(1), B(0, 32 bits))

    val invalidWay = Bits(2 bits)
    invalidWay := B"2'b00"
    when(!tagOutputs(0)(0)) {
      invalidWay := B"2'b01"
    }.elsewhen(!tagOutputs(1)(0)) {
      invalidWay := B"2'b10"
    }
    val hasInvalidWay = invalidWay.orR
    val randomWay = Mux(lfsr(6), B"2'b10", B"2'b01")
    val randomReplacement = Mux(hasInvalidWay, invalidWay, randomWay)
    val cacopChosenWay = Mux(requestOffset(0), B"2'b10", B"2'b01")
    val replaceWay = Bits(2 bits)
    replaceWay := B"2'b00"
    when(mode0 || mode1) {
      replaceWay := cacopChosenWay
    }.elsewhen(mode2) {
      replaceWay := wayHit
    }.elsewhen(!requestCacop) {
      replaceWay := randomReplacement
    }

    val rdReq = isReplace && !(mode0 || mode1 || mode2)
    val refillMatch = missRetNum === requestOffset(3 downto 2).asUInt
    val dataOk =
      (isLookup && (cacheHit || io.tlb_excp_cancel_req)) ||
        (isRefill && io.ret_valid && (refillMatch || requestUncache) && !requestCacop)

    // The legacy source increments this exact two-bit binary counter.
    val nextRetNum = (missRetNum + U(1, 2 bits)).resized

    for (way <- 0 until 2; bank <- 0 until 4) {
      val address = Mux(addrOk, realIndex, requestIndex)
      val writeMask = Bits(4 bits)
      writeMask := B"4'b0000"
      when(isRefill && missReplaceWay(way) && io.ret_valid && missRetNum === bank) {
        writeMask := B"4'b1111"
      }
      val enabled = !(requestUncache || mode0) || isIdle || isLookup
      val writeEnabled = enabled && writeMask.orR
      val readEnabled = enabled && !writeMask.orR
      dataMem(way)(bank).write(
        address = address.asUInt,
        data = io.ret_data,
        enable = writeEnabled,
        mask = writeMask
      )
      dataOutputs(way)(bank) := dataMem(way)(bank).readSync(
        address = address.asUInt,
        enable = readEnabled
      )
    }

    for (way <- 0 until 2) {
      val address = Bits(8 bits)
      address := B"8'h00"
      when(addrOk || (io.icacop_op_en && (isIdle || isLookup))) {
        address := realIndex
      }.elsewhen(isReplace || isRefill) {
        address := requestIndex
      }
      val enabled = !requestUncache || isIdle || isLookup
      val writeEnabled =
        enabled && missReplaceWay(way) && isRefill &&
          ((io.ret_valid && io.ret_last) || mode0 || mode1 || mode2HitWrite)
      val writeData = Mux(mode0 || mode1 || mode2HitWrite, B(0, 21 bits), requestTag ## True)
      tagMem(way).write(
        address = address.asUInt,
        data = writeData,
        enable = writeEnabled
      )
      tagOutputs(way) := tagMem(way).readSync(
        address = address.asUInt,
        enable = enabled && !writeEnabled
      )
    }

    private def captureRequest(): Unit = {
      requestIndex := realIndex
      requestOffset := realOffset
      requestCacopMode := io.cacop_op_mode
      requestCacop := io.icacop_op_en
    }

    switch(mainState) {
      is(MainIdle) {
        when(requestValid) {
          mainState := MainLookup
          captureRequest()
        }
      }
      is(MainLookup) {
        when(requestValid && cacheHit) {
          mainState := MainLookup
          captureRequest()
        }.elsewhen(io.tlb_excp_cancel_req) {
          mainState := MainIdle
        }.elsewhen(!cacheHit) {
          mainState := MainReplace
          requestTag := realTag
          requestUncache := io.uncache_en && !requestCacop
          missReplaceWay := replaceWay
        }.otherwise {
          mainState := MainIdle
        }
      }
      is(MainReplace) {
        when(io.rd_rdy) {
          mainState := MainRefill
          missRetNum := 0
        }
      }
      is(MainRefill) {
        when((io.ret_valid && io.ret_last) || !rdReqBuffer) {
          mainState := MainIdle
        }.elsewhen(io.ret_valid) {
          missRetNum := nextRetNum
        }
      }
      default {
        mainState := MainIdle
      }
    }

    when(mode2 && isLookup) {
      lookupWayHitBuffer := wayHit
    }

    when(rdReq) {
      rdReqBuffer := True
    }.elsewhen(isRefill && io.ret_valid && io.ret_last) {
      rdReqBuffer := False
    }

    lfsr(0) := lfsr(7)
    lfsr(1) := lfsr(0)
    lfsr(2) := lfsr(1)
    lfsr(3) := lfsr(2)
    lfsr(4) := lfsr(3) ^ lfsr(7)
    lfsr(5) := lfsr(4) ^ lfsr(7)
    lfsr(6) := lfsr(5) ^ lfsr(7)
    lfsr(7) := lfsr(6)

    // Golden only assigns this register in reset; the other write outputs are undriven.
    legacyWrReq := legacyWrReq
  }

  io.addr_ok := logic.addrOk
  io.data_ok := logic.dataOk
  io.rdata := Mux(
    logic.isLookup,
    logic.loadResult,
    Mux(logic.isRefill, io.ret_data, B(0, 32 bits))
  )
  io.icache_unbusy := logic.isIdle
  io.rd_req := logic.rdReq
  io.rd_type := Mux(logic.requestUncache, B"3'b010", B"3'b100")
  io.rd_addr := Mux(
    logic.requestUncache,
    logic.requestTag ## logic.requestIndex ## logic.requestOffset,
    logic.requestTag ## logic.requestIndex ## B"4'b0000"
  )
  io.wr_req := logic.legacyWrReq
  io.wr_type := B"3'b000"
  io.wr_addr := B(0, 32 bits)
  io.wr_wstrb := B"4'b0000"
  io.wr_data := B(0, 128 bits)
  io.cache_miss :=
    logic.isRefill && io.ret_last && !(logic.requestUncache || logic.requestCacop)
}
