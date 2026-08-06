package miku.memory

import spinal.core._

/** Cycle-oriented replacement for the active a158aa8 dcache boundary.
  *
  * The external contract intentionally remains the legacy 35-port interface. The request,
  * write-back and refill state machines retain the old ordering: lookup, optional dirty write-back,
  * refill, and delayed hit-store write buffer.
  */
final class OpenLa500DCache extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val valid = in Bool ()
    val op = in Bool ()
    val size = in Bits (3 bits)
    val index = in Bits (8 bits)
    val tag = in Bits (20 bits)
    val offset = in Bits (4 bits)
    val wstrb = in Bits (4 bits)
    val wdata = in Bits (32 bits)
    val addr_ok = out Bool ()
    val data_ok = out Bool ()
    val rdata = out Bits (32 bits)
    val uncache_en = in Bool ()
    val dcacop_op_en = in Bool ()
    val cacop_op_mode = in Bits (2 bits)
    val preld_hint = in Bits (5 bits)
    val preld_en = in Bool ()
    val tlb_excp_cancel_req = in Bool ()
    val sc_cancel_req = in Bool ()
    val dcache_empty = out Bool ()
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
    config = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)
  )

  private val logic = new ClockingArea(cacheClockDomain) {
    val MainIdle = B"5'b00001"
    val MainLookup = B"5'b00010"
    val MainMiss = B"5'b00100"
    val MainReplace = B"5'b01000"
    val MainRefill = B"5'b10000"
    val mainState = Reg(Bits(5 bits)) init (MainIdle)

    val requestOp = Reg(Bool()) init (False)
    val requestPreld = Reg(Bool()) init (False)
    val requestSize = Reg(Bits(3 bits)) init (0)
    val requestIndex = Reg(Bits(8 bits)) init (0)
    val requestTag = Reg(Bits(20 bits))
    val requestOffset = Reg(Bits(4 bits)) init (0)
    val requestWstrb = Reg(Bits(4 bits)) init (0)
    val requestWdata = Reg(Bits(32 bits)) init (0)
    val requestUncache = Reg(Bool()) init (False)
    val requestCacop = Reg(Bool()) init (False)
    val requestCacopMode = Reg(Bits(2 bits)) init (0)

    val missReplaceWay = Reg(Bits(2 bits)) init (0)
    val missRetNum = Reg(UInt(2 bits))
    val rdReqBuffer = Reg(Bool()) init (False)
    val lfsr = Reg(Bits(8 bits)) init (B"8'b00000001")
    val legacyWrReq = Reg(Bool()) init (False)

    val writeBufferState = Reg(Bool()) init (False)
    val writeBufferIndex = Reg(Bits(8 bits)) init (0)
    val writeBufferWstrb = Reg(Bits(4 bits)) init (0)
    val writeBufferWdata = Reg(Bits(32 bits)) init (0)
    val writeBufferWay = Reg(Bits(2 bits)) init (0)
    val writeBufferWord = Reg(UInt(2 bits)) init (0)

    val uncacheWrBuffer = Reg(Bool())
    val cacopMode2HitWrBuffer = Reg(Bool())

    val dataMem = Array.fill(2, 4)(Mem(Bits(32 bits), 256))
    val tagMem = Array.fill(2)(Mem(Bits(21 bits), 256))
    val dirtyMem = Vec.fill(256)(Reg(Bits(2 bits)))

    val isIdle = mainState === MainIdle
    val isLookup = mainState === MainLookup
    val isReplace = mainState === MainReplace
    val isRefill = mainState === MainRefill
    val writeBufferFull = writeBufferState
    val cancelReq = io.tlb_excp_cancel_req || io.sc_cancel_req

    val requestValid = io.valid || io.dcacop_op_en || io.preld_en
    val sameWord = writeBufferWord === io.offset(3 downto 2).asUInt
    val idleToLookup = !writeBufferFull || !(sameWord || io.dcacop_op_en)
    val mode0 = requestCacop && requestCacopMode === B"2'b00"
    val mode1 = requestCacop && (requestCacopMode === B"2'b01" || requestCacopMode === B"2'b11")
    val mode2 = requestCacop && requestCacopMode === B"2'b10"

    val writeIn = Bits(32 bits)
    for (byte <- 0 until 4) {
      val hi = byte * 8 + 7
      val lo = byte * 8
      writeIn(hi downto lo) := Mux(
        requestWstrb(byte),
        requestWdata(hi downto lo),
        io.ret_data(hi downto lo)
      )
    }
    val refillData = Mux(
      requestOp && requestOffset(3 downto 2).asUInt === missRetNum,
      writeIn,
      io.ret_data
    )

    val tagOutputs = Vec(Bits(21 bits), 2)
    val dataOutputs = Array.fill(2)(Vec(Bits(32 bits), 4))
    val realHit = Bits(2 bits)
    val cacheHit = Bool()
    val loadResult = Bits(32 bits)

    for (way <- 0 until 2) {
      val tagAddress = Mux(io.addr_ok, io.index, requestIndex).asUInt
      val tagWriteNow = isRefill && missReplaceWay(way) &&
        ((io.ret_valid && io.ret_last) || mode0 || mode1 || cacopMode2HitWrBuffer)
      val tagEnabled = !requestUncache || isIdle || isLookup
      val tagWriteData = Mux(
        mode0 || mode1 || cacopMode2HitWrBuffer,
        B(0, 21 bits),
        requestTag ## True
      )
      tagOutputs(way) := tagMem(way).readWriteSync(
        address = tagAddress,
        data = tagWriteData,
        enable = tagEnabled,
        write = tagWriteNow,
        duringWrite = dontRead
      )
      realHit(way) := tagOutputs(way)(0) && tagOutputs(way)(20 downto 1) === io.tag
      for (bank <- 0 until 4) {
        val dataAddress = Mux(io.addr_ok, io.index, requestIndex).asUInt
        val hitStoreNow =
          writeBufferFull && writeBufferWay(way) &&
            writeBufferWord === U(bank, 2 bits)
        val refillWriteNow =
          isRefill && missReplaceWay(way) && io.ret_valid && missRetNum === U(bank, 2 bits)
        val dataEnabled = !(requestUncache || mode0) || isIdle || isLookup
        val writeNow = hitStoreNow || refillWriteNow
        // Golden ORs the hit-store byte mask with a full refill mask.  When both
        // target this bank, hit-store data wins but all four bytes are written.
        val writeMask = Mux(refillWriteNow, B"4'b1111", writeBufferWstrb)
        val writeAddress = Mux(hitStoreNow, writeBufferIndex.asUInt, dataAddress)
        val writeData = Mux(hitStoreNow, writeBufferWdata, refillData)
        dataOutputs(way)(bank) := dataMem(way)(bank).readWriteSync(
          address = writeAddress,
          data = writeData,
          enable = dataEnabled,
          write = writeNow,
          mask = writeMask,
          duringWrite = dontRead
        )
      }
    }
    // CACOP follows the locked passing d22c13c state path: it must not be
    // treated as a normal lookup hit, even when the indexed line is valid.
    cacheHit := realHit.orR && !(io.uncache_en || mode0 || mode1 || mode2)
    loadResult :=
      (Mux(realHit(0), dataOutputs(0)(requestOffset(3 downto 2).asUInt), B(0, 32 bits)) |
        Mux(realHit(1), dataOutputs(1)(requestOffset(3 downto 2).asUInt), B(0, 32 bits)))

    val cacopChosenWay = Mux(requestOffset(0), B"2'b10", B"2'b01")
    val invalidWay = Bits(2 bits)
    invalidWay := B"2'b00"
    when(!tagOutputs(0)(0)) { invalidWay := B"2'b01" }
      .elsewhen(!tagOutputs(1)(0)) { invalidWay := B"2'b10" }
    val randomWay = Mux(lfsr(6), B"2'b10", B"2'b01")
    val replacementWay = Bits(2 bits)
    replacementWay := Mux(invalidWay.orR, invalidWay, randomWay)
    when(mode0 || mode1) { replacementWay := cacopChosenWay }
      .elsewhen(mode2) { replacementWay := realHit }

    val dirtyAtIndex = dirtyMem(requestIndex.asUInt)
    val effectiveDirty = dirtyAtIndex | Mux(
      writeBufferFull && writeBufferIndex === requestIndex,
      writeBufferWay,
      B(0, 2 bits)
    )
    val replacementDirty = (replacementWay & effectiveDirty).orR
    val validWays = Bits(2 bits)
    validWays(0) := tagOutputs(0)(0)
    validWays(1) := tagOutputs(1)(0)
    val replacementValid = (replacementWay & validWays).orR
    val lookupWriteConflict =
      writeBufferFull &&
        (writeBufferWord === io.offset(3 downto 2).asUInt || io.dcacop_op_en)
    val consecutiveStoreLoadConflict =
      requestOp && !io.op &&
        (requestOffset(3 downto 2) === io.offset(3 downto 2) || io.dcacop_op_en)
    val lookupToLookup = !lookupWriteConflict && !consecutiveStoreLoadConflict && cacheHit
    val addrOk = (isIdle && idleToLookup) || (isLookup && lookupToLookup)

    val uncacheRequest = io.uncache_en && !requestCacop
    val cacopMode2Hit = mode2 && realHit.orR
    val uncacheWrite = uncacheRequest && requestOp && !mode1 && !cacopMode2Hit
    val rdReq = isReplace && !(uncacheWrBuffer || mode0 || mode1 || mode2)
    val refillMatch = missRetNum === requestOffset(3 downto 2).asUInt
    val dataOk =
      (isLookup && (cacheHit || requestOp || cancelReq) ||
        isRefill && !requestOp && io.ret_valid && (refillMatch || requestUncache)) &&
        !(requestPreld || requestCacop)

    val replaceTag = Bits(20 bits)
    replaceTag := 0
    when(missReplaceWay(0)) { replaceTag := tagOutputs(0)(20 downto 1) }
      .elsewhen(missReplaceWay(1)) { replaceTag := tagOutputs(1)(20 downto 1) }
    val replaceData = Bits(128 bits)
    replaceData := B(0, 128 bits)
    when(missReplaceWay(0)) {
      replaceData := dataOutputs(0)(3) ## dataOutputs(0)(2) ## dataOutputs(0)(1) ## dataOutputs(0)(
        0
      )
    }
      .elsewhen(missReplaceWay(1)) {
        replaceData := dataOutputs(1)(3) ## dataOutputs(1)(2) ## dataOutputs(1)(1) ## dataOutputs(
          1
        )(0)
      }

    def captureRequest(): Unit = {
      requestOp := io.op
      requestPreld := io.preld_en
      requestSize := io.size
      requestIndex := io.index
      requestOffset := io.offset
      requestWstrb := io.wstrb
      requestWdata := io.wdata
      requestCacopMode := io.cacop_op_mode
      requestCacop := io.dcacop_op_en
    }

    switch(mainState) {
      is(MainIdle) {
        when(requestValid && idleToLookup) { mainState := MainLookup; captureRequest() }
      }
      is(MainLookup) {
        when(requestValid && lookupToLookup) {
          mainState := MainLookup
          captureRequest()
        }.elsewhen(cancelReq) {
          mainState := MainIdle
        }.elsewhen(!cacheHit) {
          when(
            uncacheWrite ||
              (replacementDirty && replacementValid && (!uncacheRequest || cacopMode2Hit) && !mode0)
          ) { mainState := MainMiss }
            .otherwise { mainState := MainReplace }
          requestTag := io.tag
          requestUncache := uncacheRequest
          uncacheWrBuffer := uncacheWrite
          missReplaceWay := replacementWay
          cacopMode2HitWrBuffer := cacopMode2Hit
        }.otherwise {
          mainState := MainIdle
        }
      }
      is(MainMiss) {
        when(io.wr_rdy) { mainState := MainReplace; legacyWrReq := True }
      }
      is(MainReplace) {
        when(io.rd_rdy) { mainState := MainRefill; missRetNum := 0 }
        legacyWrReq := False
      }
      is(MainRefill) {
        when((io.ret_valid && io.ret_last) || !rdReqBuffer) {
          mainState := MainIdle
        }.elsewhen(io.ret_valid) {
          missRetNum := missRetNum + U(1, 2 bits)
        }
      }
      default { mainState := MainIdle }
    }

    when(rdReq) { rdReqBuffer := True }
      .elsewhen(isRefill && io.ret_valid && io.ret_last) { rdReqBuffer := False }

    when(
      isRefill && ((io.ret_valid && io.ret_last) || !rdReqBuffer) && !(requestUncache || mode0)
    ) {
      when(missReplaceWay(0)) { dirtyMem(requestIndex.asUInt)(0) := requestOp }
      when(missReplaceWay(1)) { dirtyMem(requestIndex.asUInt)(1) := requestOp }
    }.elsewhen(writeBufferFull) {
      dirtyMem(writeBufferIndex.asUInt) := dirtyMem(writeBufferIndex.asUInt) | writeBufferWay
    }

    when(isLookup && cacheHit && requestOp && !cancelReq) {
      writeBufferState := True
      writeBufferIndex := requestIndex
      writeBufferWstrb := requestWstrb
      writeBufferWdata := requestWdata
      writeBufferWord := requestOffset(3 downto 2).asUInt
      writeBufferWay := realHit
    }.otherwise {
      writeBufferState := False
    }

    lfsr(0) := lfsr(7)
    lfsr(1) := lfsr(0)
    lfsr(2) := lfsr(1)
    lfsr(3) := lfsr(2)
    lfsr(4) := lfsr(3) ^ lfsr(7)
    lfsr(5) := lfsr(4) ^ lfsr(7)
    lfsr(6) := lfsr(5) ^ lfsr(7)
    lfsr(7) := lfsr(6)

    io.addr_ok := addrOk
    io.data_ok := dataOk
    io.rdata := Mux(isLookup, loadResult, Mux(isRefill, io.ret_data, B(0, 32 bits)))
    io.dcache_empty := isIdle
    io.rd_req := rdReq
    io.rd_type := Mux(requestUncache, requestSize, B"3'b100")
    io.rd_addr := Mux(
      requestUncache,
      requestTag ## requestIndex ## requestOffset,
      requestTag ## requestIndex ## B"4'b0000"
    )
    io.wr_req := legacyWrReq
    io.wr_type := Mux(uncacheWrBuffer, requestSize, B"3'b100")
    io.wr_addr := Mux(
      uncacheWrBuffer,
      requestTag ## requestIndex ## requestOffset,
      replaceTag ## requestIndex ## B"4'b0000"
    )
    io.wr_wstrb := Mux(uncacheWrBuffer, requestWstrb, B"4'hf")
    io.wr_data := Mux(uncacheWrBuffer, B(0, 96 bits) ## requestWdata, replaceData)
    io.cache_miss := isRefill && io.ret_last && !(requestUncache || requestCacop || requestPreld)
  }
}
