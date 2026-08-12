package miku.memory

import miku.compat.Axi3Compat
import miku.core._
import miku.predict._
import spinal.core._
import spinal.lib._

/** Converts cached line traffic and uncached narrow traffic to one AXI3 master.
  *
  * Uncached instruction fetches use a four-word burst. Uncached data reads use the architectural
  * transfer size, while uncached writes are acknowledged upstream only after the AXI B response.
  */
final class AxiLineBridge(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val axiWordsPerLine = CacheContract.LineBytes / 4
  private val axiWordIndexWidth = log2Up(axiWordsPerLine)

  require(axiWordsPerLine == 16)

  val io = new Bundle {
    val memoryReadValid = in Bool ()
    val memoryRead = in(LineReadRequest(config))
    val memoryReadReady = out Bool ()
    val memoryReadBeatValid = out Bool ()
    val memoryReadBeat = out(LineReadBeat(config))
    val memoryReadBeatReady = in Bool ()

    val memoryWriteValid = in Bool ()
    val memoryWrite = in(LineWriteRequest(config))
    val memoryWriteReady = out Bool ()
    val memoryWriteResponseValid = out Bool ()
    val memoryWriteResponse = out(LineWriteResponse(config))

    val uncachedInstructionRequestValid = in Bool ()
    val uncachedInstructionRequest = in(InstructionCacheRequest(config))
    val uncachedInstructionRequestReady = out Bool ()
    val uncachedInstructionResponseValid = out Bool ()
    val uncachedInstructionResponse = out(InstructionCacheResponse(config))

    val uncachedDataRequestValid = in Bool ()
    val uncachedDataRequest = in(CacheRequest(config))
    val uncachedDataRequestReady = out Bool ()
    val uncachedDataResponseValid = out Bool ()
    val uncachedDataResponse = out(CacheResponse(config))

    val axi = master(Axi3Compat())
    val idle = out Bool ()
  }

  val instructionReadKind = U(1, 2 bits)
  val dataReadKind = U(2, 2 bits)

  val readActive = RegInit(False)
  val readKind = Reg(UInt(2 bits)) init (instructionReadKind)
  val readAddress = Reg(UInt(config.xlen bits))
  val readSize = Reg(Bits(3 bits)) init (B(2, 3 bits))
  val readAddressValid = RegInit(False)
  val lineActive = Vec.fill(config.mshrEntries)(Reg(Bool()) init (False))
  val lineHalf = Vec.fill(config.mshrEntries)(Reg(Bool()) init (False))
  val lineLowWord = Vec.fill(config.mshrEntries)(Reg(Bits(32 bits)))
  val lineLowError = Vec.fill(config.mshrEntries)(Reg(Bool()) init (False))
  val lineBeatIndex = Vec.fill(config.mshrEntries)(
    Reg(UInt(CacheContract.BeatIndexWidth bits)) init (0)
  )
  val lineBeatCount = Vec.fill(config.mshrEntries)(
    Reg(UInt(CacheContract.BeatIndexWidth bits)) init (0)
  )
  val lineArValid = RegInit(False)
  val lineArMshrId = Reg(UInt(log2Up(config.mshrEntries) bits))
  val lineArAddress = Reg(UInt(config.xlen bits))
  val readOutputValid = RegInit(False)
  val readOutput = Reg(LineReadBeat(config))
  val instructionReadWordIndex = Reg(UInt(2 bits)) init (0)
  val instructionReadError = RegInit(False)
  val instructionResponseValid = RegInit(False)
  val instructionResponse = Reg(InstructionCacheResponse(config))
  val dataReadContext = Reg(CacheRequest(config))
  val dataWriteContext = Reg(CacheRequest(config))
  val dataResponseValid = RegInit(False)
  val dataResponse = Reg(CacheResponse(config))

  val writeActive = RegInit(False)
  val writeIsUncachedData = RegInit(False)
  val writeAddress = Reg(UInt(config.xlen bits))
  val writeSize = Reg(Bits(3 bits)) init (B(2, 3 bits))
  val writeData = Reg(Bits(CacheContract.LineBits bits))
  val writeMask = Reg(Bits(CacheContract.LineBytes bits))
  val writeAddressValid = RegInit(False)
  val writeBeatIndex = Reg(UInt(axiWordIndexWidth bits)) init (0)
  val writeResponsePending = RegInit(False)
  val writeMshrId = Reg(UInt(log2Up(config.mshrEntries) bits))
  val memoryWriteResponseValid = RegInit(False)
  val memoryWriteResponse = Reg(LineWriteResponse(config))

  instructionResponseValid := False
  dataResponseValid := False
  memoryWriteResponseValid := False
  io.uncachedInstructionResponseValid := instructionResponseValid
  io.uncachedInstructionResponse := instructionResponse
  io.uncachedDataResponseValid := dataResponseValid
  io.uncachedDataResponse := dataResponse
  io.memoryWriteResponseValid := memoryWriteResponseValid
  io.memoryWriteResponse := memoryWriteResponse

  val lineBusy = lineArValid || lineActive.asBits.orR
  val busIdle = !readActive && !writeActive && !readOutputValid && !lineBusy
  val idleNow = busIdle && !instructionResponseValid && !dataResponseValid &&
    !memoryWriteResponseValid &&
    !io.memoryReadValid && !io.memoryWriteValid &&
    !io.uncachedInstructionRequestValid && !io.uncachedDataRequestValid &&
    !io.axi.r.valid && !io.axi.b.valid
  io.idle := RegNext(idleNow) init (False)
  // A newly contending cached request wins once; a registered wait bit then gives uncached
  // traffic the next idle slot.  Cached ready therefore depends only on registered bridge state,
  // keeping frontend/LSQ request-valid logic out of the L2 MSHR state-update cone without
  // allowing either traffic class to starve.
  val cachedTrafficPending = io.memoryReadValid || io.memoryWriteValid
  val uncachedTrafficPending = io.uncachedDataRequestValid ||
    io.uncachedInstructionRequestValid
  val uncachedWait = RegInit(False)
  val uncachedOwnsIdleSlot = uncachedWait || !cachedTrafficPending
  val startUncachedData = busIdle && uncachedOwnsIdleSlot &&
    io.uncachedDataRequestValid
  val startUncachedDataRead = startUncachedData && !io.uncachedDataRequest.isWrite
  val startUncachedDataWrite = startUncachedData && io.uncachedDataRequest.isWrite
  val startUncachedInstruction = busIdle && uncachedOwnsIdleSlot &&
    !io.uncachedDataRequestValid &&
    io.uncachedInstructionRequestValid
  io.uncachedDataRequestReady := startUncachedDataRead || startUncachedDataWrite
  io.uncachedInstructionRequestReady := startUncachedInstruction
  io.memoryReadReady := !readActive && !writeActive && !lineArValid &&
    !lineActive(io.memoryRead.mshrId) && !uncachedWait
  io.memoryWriteReady := busIdle && !io.memoryReadValid && !uncachedWait
  val uncachedStart = startUncachedData || startUncachedInstruction
  when(busIdle) {
    when(uncachedStart || !uncachedTrafficPending) {
      uncachedWait := False
    }.elsewhen(cachedTrafficPending) {
      uncachedWait := True
    }
  }
  val readRequestFire = io.memoryReadValid && io.memoryReadReady
  val writeRequestFire = io.memoryWriteValid && io.memoryWriteReady

  when(readRequestFire) {
    val id = io.memoryRead.mshrId
    lineActive(id) := True
    lineHalf(id) := False
    lineLowError(id) := False
    lineBeatIndex(id) := io.memoryRead.criticalBeat
    lineBeatCount(id) := U(0, CacheContract.BeatIndexWidth bits)
    lineArValid := True
    lineArMshrId := id
    lineArAddress := io.memoryRead.lineAddress +
      (io.memoryRead.criticalBeat.resize(config.xlen) |<< 3)
  }
  when(startUncachedInstruction) {
    readActive := True
    readKind := instructionReadKind
    readAddress := io.uncachedInstructionRequest.physicalAddress &
      U(((BigInt(1) << config.xlen) - 1) ^ 0xf, config.xlen bits)
    readSize := B(2, 3 bits)
    readAddressValid := True
    instructionReadWordIndex := 0
    instructionReadError := False
    instructionResponse.virtualAddress := io.uncachedInstructionRequest.virtualAddress
    instructionResponse.physicalAddress := io.uncachedInstructionRequest.physicalAddress
    instructionResponse.error := False
    for (word <- 0 until config.fetchWidth) {
      instructionResponse.instructions(word) := 0
      instructionResponse.predecode(word).valid := False
      instructionResponse.predecode(word).branchType := PredictedBranchType.direct
      instructionResponse.predecode(word).target := 0
      instructionResponse.predecode(word).staticTaken := False
      instructionResponse.predecode(word).indirect := False
    }
  }
  when(startUncachedDataRead) {
    readActive := True
    readKind := dataReadKind
    readAddress := io.uncachedDataRequest.physicalAddress
    readSize := io.uncachedDataRequest.size
    readAddressValid := True
    dataReadContext := io.uncachedDataRequest
  }

  val uncachedAr = readAddressValid
  io.axi.ar.valid := uncachedAr || lineArValid
  io.axi.ar.payload.id := B"2'b01" ## lineArMshrId.asBits
  io.axi.ar.payload.address := lineArAddress.asBits
  io.axi.ar.payload.len := B(axiWordsPerLine - 1, 8 bits)
  io.axi.ar.payload.size := B(2, 3 bits)
  io.axi.ar.payload.burst := B"2'b10"
  when(uncachedAr) {
    io.axi.ar.payload.id := Mux(
      readKind === instructionReadKind,
      B(2, 4 bits),
      B(3, 4 bits)
    )
    io.axi.ar.payload.address := readAddress.asBits
    io.axi.ar.payload.len := Mux(
      readKind === instructionReadKind,
      B(config.fetchWidth - 1, 8 bits),
      B(0, 8 bits)
    )
    io.axi.ar.payload.size := readSize
    io.axi.ar.payload.burst := B"2'b01"
  }
  io.axi.ar.payload.lock := B"2'b00"
  io.axi.ar.payload.cache := B"4'b0000"
  io.axi.ar.payload.prot := B"3'b000"
  when(io.axi.ar.valid && io.axi.ar.ready) {
    when(uncachedAr) {
      readAddressValid := False
    }.otherwise {
      lineArValid := False
    }
  }

  val readOutputFire = readOutputValid && io.memoryReadBeatReady
  when(readOutputFire) { readOutputValid := False }
  val lineResponse = io.axi.r.payload.id(3 downto 2) === B"2'b01"
  val lineResponseId = io.axi.r.payload.id(log2Up(config.mshrEntries) - 1 downto 0).asUInt
  // The low half can arrive while the previous beat drains, but a high half only enters an empty
  // output register. This cuts cache-hierarchy backpressure out of the AXI R-ready timing path
  // without adding a bubble to the normal low/high/low/high 32-bit burst sequence.
  val secondWordReady = !readOutputValid
  val lineResponseReady = lineResponse && lineActive(lineResponseId) &&
    (!lineHalf(lineResponseId) || secondWordReady)
  val uncachedResponseReady = !lineResponse && readActive && !readAddressValid
  io.axi.r.ready := lineResponseReady || uncachedResponseReady
  val readWordFire = io.axi.r.valid && io.axi.r.ready
  when(readWordFire) {
    when(lineResponse) {
      val responseError = io.axi.r.payload.response.orR
      when(!lineHalf(lineResponseId)) {
        lineLowWord(lineResponseId) := io.axi.r.payload.data
        lineLowError(lineResponseId) := responseError || io.axi.r.payload.last
        lineHalf(lineResponseId) := True
      }.otherwise {
        val expectedLast =
          lineBeatCount(lineResponseId) === CacheContract.BeatsPerLine - 1
        readOutputValid := True
        readOutput.mshrId := lineResponseId
        readOutput.beat := lineBeatIndex(lineResponseId)
        readOutput.data := io.axi.r.payload.data ## lineLowWord(lineResponseId)
        readOutput.last := expectedLast
        readOutput.error := lineLowError(lineResponseId) || responseError ||
          (io.axi.r.payload.last =/= expectedLast)
        lineHalf(lineResponseId) := False
        when(expectedLast) {
          lineActive(lineResponseId) := False
        }.otherwise {
          lineBeatIndex(lineResponseId) := lineBeatIndex(lineResponseId) + 1
          lineBeatCount(lineResponseId) := lineBeatCount(lineResponseId) + 1
        }
      }
    }.elsewhen(readKind === instructionReadKind) {
      val expectedLast = instructionReadWordIndex === config.fetchWidth - 1
      val responseError = io.axi.r.payload.response.orR ||
        io.axi.r.payload.id =/= B(2, 4 bits)
      instructionResponse.instructions(instructionReadWordIndex) := io.axi.r.payload.data
      val instructionGroupBase = instructionResponse.virtualAddress &
        U(((BigInt(1) << config.xlen) - 1) ^ (config.fetchWidth * 4 - 1), config.xlen bits)
      for (word <- 0 until config.fetchWidth) {
        when(instructionReadWordIndex === word) {
          FetchPredecoder.drive(
            instructionResponse.predecode(word),
            config,
            instructionGroupBase + U(word * 4, config.xlen bits),
            io.axi.r.payload.data
          )
        }
      }
      instructionReadError := instructionReadError || responseError
      when(expectedLast || io.axi.r.payload.last) {
        instructionResponseValid := True
        instructionResponse.error := instructionReadError || responseError ||
          (io.axi.r.payload.last =/= expectedLast)
        readActive := False
      }.otherwise {
        instructionReadWordIndex := instructionReadWordIndex + 1
      }
    }.otherwise {
      dataResponseValid := True
      dataResponse.robPointer := dataReadContext.robPointer
      dataResponse.recoveryEpoch := dataReadContext.recoveryEpoch
      dataResponse.pdst := dataReadContext.pdst
      dataResponse.loadQueueIndex := dataReadContext.loadQueueIndex
      dataResponse.data := io.axi.r.payload.data
      dataResponse.error := io.axi.r.payload.response.orR ||
        io.axi.r.payload.id =/= B(3, 4 bits) || !io.axi.r.payload.last
      readActive := False
    }
  }

  io.memoryReadBeatValid := readOutputValid
  io.memoryReadBeat := readOutput

  when(writeRequestFire) {
    writeActive := True
    writeIsUncachedData := False
    writeAddress := io.memoryWrite.lineAddress
    writeSize := B(2, 3 bits)
    writeData := io.memoryWrite.data
    writeMask := io.memoryWrite.byteMask
    writeAddressValid := True
    writeBeatIndex := 0
    writeResponsePending := False
    writeMshrId := io.memoryWrite.mshrId
  }
  when(startUncachedDataWrite) {
    writeActive := True
    writeIsUncachedData := True
    writeAddress := io.uncachedDataRequest.physicalAddress
    writeSize := io.uncachedDataRequest.size
    writeData := io.uncachedDataRequest.writeData.resize(CacheContract.LineBits)
    writeMask := io.uncachedDataRequest.byteMask.resize(CacheContract.LineBytes)
    writeAddressValid := True
    writeBeatIndex := 0
    writeResponsePending := False
    dataWriteContext := io.uncachedDataRequest
  }

  io.axi.aw.valid := writeAddressValid
  io.axi.aw.payload.id := Mux(writeIsUncachedData, B(3, 4 bits), B(1, 4 bits))
  io.axi.aw.payload.address := writeAddress.asBits
  io.axi.aw.payload.len := Mux(
    writeIsUncachedData,
    B(0, 8 bits),
    B(axiWordsPerLine - 1, 8 bits)
  )
  io.axi.aw.payload.size := writeSize
  io.axi.aw.payload.burst := B"2'b01"
  io.axi.aw.payload.lock := B"2'b00"
  io.axi.aw.payload.cache := B"4'b0000"
  io.axi.aw.payload.prot := B"3'b000"
  when(io.axi.aw.valid && io.axi.aw.ready) { writeAddressValid := False }

  val writeDataShift = (writeBeatIndex ## U(0, 5 bits)).asUInt
  val writeMaskShift = (writeBeatIndex ## U(0, 2 bits)).asUInt
  io.axi.w.valid := writeActive && !writeAddressValid && !writeResponsePending
  io.axi.w.payload.id := Mux(writeIsUncachedData, B(3, 4 bits), B(1, 4 bits))
  io.axi.w.payload.data := (writeData |>> writeDataShift)(31 downto 0)
  io.axi.w.payload.byteMask := (writeMask |>> writeMaskShift)(3 downto 0)
  io.axi.w.payload.last := writeIsUncachedData || writeBeatIndex === axiWordsPerLine - 1
  when(io.axi.w.valid && io.axi.w.ready) {
    when(io.axi.w.payload.last) {
      writeResponsePending := True
    }.otherwise {
      writeBeatIndex := writeBeatIndex + 1
    }
  }

  io.axi.b.ready := writeResponsePending
  when(io.axi.b.valid && io.axi.b.ready) {
    when(writeIsUncachedData) {
      dataResponseValid := True
      dataResponse.robPointer := dataWriteContext.robPointer
      dataResponse.recoveryEpoch := dataWriteContext.recoveryEpoch
      dataResponse.pdst := dataWriteContext.pdst
      dataResponse.loadQueueIndex := dataWriteContext.loadQueueIndex
      dataResponse.data := B(0, config.xlen bits)
      dataResponse.error := io.axi.b.payload.response.orR ||
        io.axi.b.payload.id =/= B(3, 4 bits)
    }.otherwise {
      memoryWriteResponseValid := True
      memoryWriteResponse.mshrId := writeMshrId
      memoryWriteResponse.error := io.axi.b.payload.response.orR ||
        io.axi.b.payload.id =/= B(1, 4 bits)
    }
    writeResponsePending := False
    writeActive := False
  }
}
