package miku.memory

import miku.core._
import spinal.core._
import spinal.lib._

/** Four-entry identity router between private L1 line readers and the shared L2.
  *
  * Each accepted request receives a hierarchy-global MSHR id. Return beats may be interleaved by
  * global id; the router restores the requesting L1's local id and applies only that client's
  * backpressure. A two-entry registered request queue breaks the L1-to-L2 ready path while
  * retaining enough elasticity for consecutive instruction and data misses. Entries are released
  * on an accepted final beat.
  */
final class SharedReadMshrRouter(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  private val idWidth = log2Up(config.mshrEntries)
  private val countWidth = log2Up(config.mshrEntries + 1)

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(idWidth bits)
    selected := 0
    for (entry <- (0 until config.mshrEntries).reverse) {
      when(mask(entry)) { selected := U(entry, idWidth bits) }
    }
    selected
  }

  val io = new Bundle {
    val instructionReadValid = in Bool ()
    val instructionRead = in(LineReadRequest(config))
    val instructionReadReady = out Bool ()
    val instructionReadBeatValid = out Bool ()
    val instructionReadBeat = out(LineReadBeat(config))
    val instructionReadBeatReady = in Bool ()

    val dataReadValid = in Bool ()
    val dataRead = in(LineReadRequest(config))
    val dataReadReady = out Bool ()
    val dataReadBeatValid = out Bool ()
    val dataReadBeat = out(LineReadBeat(config))
    val dataReadBeatReady = in Bool ()

    val lowerReadValid = out Bool ()
    val lowerRead = out(LineReadRequest(config))
    val lowerReadReady = in Bool ()
    val lowerReadBeatValid = in Bool ()
    val lowerReadBeat = in(LineReadBeat(config))
    val lowerReadBeatReady = out Bool ()

    val activeCount = out UInt (countWidth bits)
    val idle = out Bool ()
  }

  val valid = Vec.fill(config.mshrEntries)(Reg(Bool()) init (False))
  val ownerData = Vec.fill(config.mshrEntries)(Reg(Bool()))
  val localId = Vec.fill(config.mshrEntries)(Reg(UInt(idWidth bits)))
  val preferData = RegInit(True)

  private val requestQueueDepth = 2
  private val requestQueueCountWidth = log2Up(requestQueueDepth + 1)
  val requestQueue = Vec.fill(requestQueueDepth)(Reg(LineReadRequest(config)))
  val requestHead = Reg(UInt(log2Up(requestQueueDepth) bits)) init (0)
  val requestTail = Reg(UInt(log2Up(requestQueueDepth) bits)) init (0)
  val requestCount = Reg(UInt(requestQueueCountWidth bits)) init (0)

  val freeMask = Bits(config.mshrEntries bits)
  for (entry <- 0 until config.mshrEntries) { freeMask(entry) := !valid(entry) }
  val hasFree = freeMask.orR
  val allocateId = selectLowest(freeMask)

  val bothPending = io.instructionReadValid && io.dataReadValid
  val selectData = io.dataReadValid && (!io.instructionReadValid || preferData)
  val selectInstruction = io.instructionReadValid && (!io.dataReadValid || !preferData)
  val requestQueueHasSpace = requestCount =/= requestQueueDepth
  io.dataReadReady := hasFree && requestQueueHasSpace && selectData
  io.instructionReadReady := hasFree && requestQueueHasSpace && selectInstruction

  val allocateFire = (io.dataReadValid && io.dataReadReady) ||
    (io.instructionReadValid && io.instructionReadReady)
  when(allocateFire) {
    valid(allocateId) := True
    ownerData(allocateId) := selectData
    localId(allocateId) := Mux(selectData, io.dataRead.mshrId, io.instructionRead.mshrId)
    requestQueue(requestTail).lineAddress := Mux(
      selectData,
      io.dataRead.lineAddress,
      io.instructionRead.lineAddress
    )
    requestQueue(requestTail).mshrId := allocateId
    requestQueue(requestTail).criticalBeat := Mux(
      selectData,
      io.dataRead.criticalBeat,
      io.instructionRead.criticalBeat
    )
    requestTail := requestTail + 1
    when(bothPending) { preferData := !selectData }
  }

  io.lowerReadValid := requestCount =/= 0
  io.lowerRead := requestQueue(requestHead)
  val requestFire = io.lowerReadValid && io.lowerReadReady
  when(requestFire) { requestHead := requestHead + 1 }

  switch(allocateFire ## requestFire) {
    is(B"10") { requestCount := requestCount + 1 }
    is(B"01") { requestCount := requestCount - 1 }
  }

  val responseId = io.lowerReadBeat.mshrId
  val responseKnown = valid(responseId)
  val responseOwnerData = ownerData(responseId)
  io.dataReadBeatValid := io.lowerReadBeatValid && responseKnown && responseOwnerData
  io.instructionReadBeatValid := io.lowerReadBeatValid && responseKnown && !responseOwnerData
  for (beat <- Seq(io.dataReadBeat, io.instructionReadBeat)) {
    beat.mshrId := localId(responseId)
    beat.beat := io.lowerReadBeat.beat
    beat.data := io.lowerReadBeat.data
    beat.last := io.lowerReadBeat.last
    beat.error := io.lowerReadBeat.error
  }
  io.lowerReadBeatReady := responseKnown && Mux(
    responseOwnerData,
    io.dataReadBeatReady,
    io.instructionReadBeatReady
  )

  val responseFire = io.lowerReadBeatValid && io.lowerReadBeatReady
  when(responseFire && io.lowerReadBeat.last) { valid(responseId) := False }

  io.activeCount := CountOne(valid.asBits)
  val idleNow = !valid.asBits.orR && requestCount === 0 &&
    !io.lowerReadBeatValid && !io.instructionReadValid && !io.dataReadValid
  io.idle := RegNext(idleNow) init (False)
}
