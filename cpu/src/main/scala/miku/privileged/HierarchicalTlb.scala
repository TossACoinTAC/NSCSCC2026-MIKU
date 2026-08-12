package miku.privileged

import spinal.core._
import spinal.lib._

final case class TlbProbeRequest() extends Bundle {
  val vppn = Bits(19 bits)
  val oddPage = Bool()
  val asid = Bits(10 bits)
}

final case class TlbLookupResult() extends Bundle {
  val found = Bool()
  val index = UInt(5 bits)
  val pageSize = Bits(6 bits)
  val ppn = Bits(20 bits)
  val valid = Bool()
  val dirty = Bool()
  val memoryAttribute = Bits(2 bits)
  val privilege = Bits(2 bits)
}

final case class TlbEntryState() extends Bundle {
  val vppn = Bits(19 bits)
  val asid = Bits(10 bits)
  val global = Bool()
  val pageSize = Bits(6 bits)
  val enabled = Bool()
  val ppn0 = Bits(20 bits)
  val privilege0 = Bits(2 bits)
  val memoryAttribute0 = Bits(2 bits)
  val dirty0 = Bool()
  val valid0 = Bool()
  val ppn1 = Bits(20 bits)
  val privilege1 = Bits(2 bits)
  val memoryAttribute1 = Bits(2 bits)
  val dirty1 = Bool()
  val valid1 = Bool()
}

/** Two four-entry micro-TLBs backed by one four-entry-per-cycle main-TLB walker.
  *
  * The common translation path compares four entries instead of driving two parallel 32-entry CAMs.
  * Main-TLB storage intentionally has no reset, matching the architectural TLB. A write has
  * priority over invalidation for the selected entry, and every mutation drops cached micro-TLB
  * state before a redirected request can be accepted.
  */
final class HierarchicalTlb(microEntries: Int = 4, walkEntriesPerCycle: Int = 4)
    extends Component {
  require(microEntries == 4, "the timing-oriented micro-TLB currently has four entries")
  require(walkEntriesPerCycle == 4, "the main TLB currently walks four entries per cycle")

  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()

    val instructionRequest = slave(Stream(TlbProbeRequest()))
    val instructionResponse = master(Flow(TlbLookupResult()))
    val dataRequest = slave(Stream(TlbProbeRequest()))
    val dataResponse = master(Flow(TlbLookupResult()))

    val writeValid = in Bool ()
    val writeIndex = in UInt (5 bits)
    val writeEntry = in(TlbEntryState())
    val invalidateValid = in Bool ()
    val invalidateOperation = in Bits (5 bits)
    val invalidateAsid = in Bits (10 bits)
    val invalidateVpn = in Bits (19 bits)

    val readIndex = in UInt (5 bits)
    val readEntry = out(TlbEntryState())
    val managementVppn = in Bits (19 bits)
    val managementAsid = in Bits (10 bits)
    val managementFound = out Bool ()
    val managementIndex = out UInt (5 bits)
  }
  noIoPrefix()

  private val domain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)
  )

  val area = new ClockingArea(domain) {
    // The low two TLB index bits select a bank and the upper three select a row. Keeping the
    // physical layout explicit prevents the four-lane walker from becoming four independent
    // 32:1 bundle muxes after RTL generation.
    val entryBanks = Vec.fill(walkEntriesPerCycle)(Vec.fill(8)(Reg(TlbEntryState())))

    private def entryAt(index: Int): TlbEntryState =
      entryBanks(index % walkEntriesPerCycle)(index / walkEntriesPerCycle)

    private def pageMatches(entry: TlbEntryState, vppn: Bits): Bool =
      Mux(
        entry.pageSize === B(12, 6 bits),
        entry.vppn === vppn,
        entry.vppn(18 downto 9) === vppn(18 downto 9)
      )

    private def entryMatches(entry: TlbEntryState, vppn: Bits, asid: Bits): Bool =
      entry.enabled && pageMatches(entry, vppn) && (entry.global || entry.asid === asid)

    private def driveResult(
        result: TlbLookupResult,
        found: Bool,
        index: UInt,
        entry: TlbEntryState,
        vppn: Bits,
        oddPage: Bool
    ): Unit = {
      val odd = Mux(entry.pageSize === B(12, 6 bits), oddPage, vppn(8))
      result.found := found
      result.index := Mux(found, index, U(0, 5 bits))
      result.pageSize := Mux(found, entry.pageSize, B(0, 6 bits))
      result.ppn := Mux(found, Mux(odd, entry.ppn1, entry.ppn0), B(0, 20 bits))
      result.valid := found && Mux(odd, entry.valid1, entry.valid0)
      result.dirty := found && Mux(odd, entry.dirty1, entry.dirty0)
      result.memoryAttribute :=
        Mux(found, Mux(odd, entry.memoryAttribute1, entry.memoryAttribute0), B(0, 2 bits))
      result.privilege :=
        Mux(found, Mux(odd, entry.privilege1, entry.privilege0), B(0, 2 bits))
    }

    private def driveMicroResult(
        result: TlbLookupResult,
        matches: Bits,
        entries: Vec[TlbEntryState],
        vppn: Bits,
        oddPage: Bool
    ): Unit = {
      val odd = Vec(Bool(), microEntries)
      for (index <- 0 until microEntries) {
        odd(index) := Mux(entries(index).pageSize === B(12, 6 bits), oddPage, vppn(8))
      }

      result.found := matches.orR
      result.index := (0 until microEntries)
        .map(index => B(index, 5 bits).andMask(matches(index)))
        .reduce(_ | _)
        .asUInt
      result.pageSize := (0 until microEntries)
        .map(index => entries(index).pageSize.andMask(matches(index)))
        .reduce(_ | _)
      result.ppn := (0 until microEntries)
        .map(index => Mux(odd(index), entries(index).ppn1, entries(index).ppn0)
          .andMask(matches(index)))
        .reduce(_ | _)
      result.valid := (0 until microEntries)
        .map(index => matches(index) && Mux(odd(index), entries(index).valid1, entries(index).valid0))
        .reduce(_ || _)
      result.dirty := (0 until microEntries)
        .map(index => matches(index) && Mux(odd(index), entries(index).dirty1, entries(index).dirty0))
        .reduce(_ || _)
      result.memoryAttribute := (0 until microEntries)
        .map(index => Mux(
          odd(index),
          entries(index).memoryAttribute1,
          entries(index).memoryAttribute0
        ).andMask(matches(index)))
        .reduce(_ | _)
      result.privilege := (0 until microEntries)
        .map(index => Mux(odd(index), entries(index).privilege1, entries(index).privilege0)
          .andMask(matches(index)))
        .reduce(_ | _)
    }

    val mutation = io.writeValid || io.invalidateValid
    for (index <- 0 until 32) {
      val entry = entryAt(index)
      val sameSmallPage = entry.vppn === io.invalidateVpn
      val sameLargePage = entry.vppn(18 downto 9) === io.invalidateVpn(18 downto 9)
      val samePage = Mux(entry.pageSize === B(12, 6 bits), sameSmallPage, sameLargePage)
      when(io.writeValid && io.writeIndex === index) {
        entry := io.writeEntry
      }.elsewhen(io.invalidateValid) {
        switch(io.invalidateOperation) {
          is(B(0, 5 bits), B(1, 5 bits)) { entry.enabled := False }
          is(B(2, 5 bits)) {
            when(entry.global) { entry.enabled := False }
          }
          is(B(3, 5 bits)) {
            when(!entry.global) { entry.enabled := False }
          }
          is(B(4, 5 bits)) {
            when(!entry.global && entry.asid === io.invalidateAsid) {
              entry.enabled := False
            }
          }
          is(B(5, 5 bits)) {
            when(!entry.global && entry.asid === io.invalidateAsid && samePage) {
              entry.enabled := False
            }
          }
          is(B(6, 5 bits)) {
            when((entry.global || entry.asid === io.invalidateAsid) && samePage) {
              entry.enabled := False
            }
          }
        }
      }
    }

    io.readEntry := entryBanks(io.readIndex(1 downto 0))(io.readIndex(4 downto 2))
    val managementMatch = Bits(32 bits)
    for (index <- 0 until 32) {
      managementMatch(index) := entryMatches(
        entryAt(index),
        io.managementVppn,
        io.managementAsid
      )
    }
    io.managementFound := managementMatch.orR
    io.managementIndex := (0 until 32)
      .map(index => Mux(managementMatch(index), U(index, 5 bits), U(0, 5 bits)))
      .reduce(_ | _)

    val instructionMicro = Vec.fill(microEntries)(Reg(TlbEntryState()))
    val instructionMicroValid = RegInit(B(0, microEntries bits))
    val instructionFillPointer = RegInit(U(0, log2Up(microEntries) bits))
    val instructionNegativeValid = RegInit(False)
    val instructionNegativeVppn = Reg(Bits(19 bits))
    val instructionNegativeAsid = Reg(Bits(10 bits))

    val dataMicro = Vec.fill(microEntries)(Reg(TlbEntryState()))
    val dataMicroValid = RegInit(B(0, microEntries bits))
    val dataFillPointer = RegInit(U(0, log2Up(microEntries) bits))
    val dataNegativeValid = RegInit(False)
    val dataNegativeVppn = Reg(Bits(19 bits))
    val dataNegativeAsid = Reg(Bits(10 bits))

    val instructionProbePending = RegInit(False)
    val instructionWalkPending = RegInit(False)
    val instructionVppn = Reg(Bits(19 bits))
    val instructionOddPage = Reg(Bool())
    val instructionAsid = Reg(Bits(10 bits))

    val dataProbePending = RegInit(False)
    val dataWalkPending = RegInit(False)
    val dataVppn = Reg(Bits(19 bits))
    val dataOddPage = Reg(Bool())
    val dataAsid = Reg(Bits(10 bits))

    io.instructionRequest.ready :=
      !instructionProbePending && !instructionWalkPending && !mutation
    io.dataRequest.ready := !dataProbePending && !dataWalkPending && !mutation

    // Preload the probe key while the instruction port is idle.  The request fire still owns the
    // valid state, and ready drops for the complete probe/walk lifetime, so invalid input changes
    // cannot overwrite an accepted owner.  This keeps the upstream cache-hit turnover cone off
    // the wide VPPN/ASID/odd-page register enables.
    when(io.instructionRequest.ready) {
      instructionVppn := io.instructionRequest.vppn
      instructionOddPage := io.instructionRequest.oddPage
      instructionAsid := io.instructionRequest.asid
    }
    when(io.instructionRequest.fire) {
      instructionProbePending := True
    }
    when(io.dataRequest.fire) {
      dataProbePending := True
      dataVppn := io.dataRequest.vppn
      dataOddPage := io.dataRequest.oddPage
      dataAsid := io.dataRequest.asid
    }

    val instructionMicroMatch = Bits(microEntries bits)
    val dataMicroMatch = Bits(microEntries bits)
    for (index <- 0 until microEntries) {
      instructionMicroMatch(index) := instructionMicroValid(index) &&
        entryMatches(instructionMicro(index), instructionVppn, instructionAsid)
      dataMicroMatch(index) := dataMicroValid(index) &&
        entryMatches(dataMicro(index), dataVppn, dataAsid)
    }
    val instructionNegativeHit = instructionNegativeValid &&
      instructionNegativeVppn === instructionVppn && instructionNegativeAsid === instructionAsid
    val dataNegativeHit = dataNegativeValid && dataNegativeVppn === dataVppn &&
      dataNegativeAsid === dataAsid
    val instructionQuickResponse =
      instructionProbePending && (instructionMicroMatch.orR || instructionNegativeHit)
    val dataQuickResponse = dataProbePending && (dataMicroMatch.orR || dataNegativeHit)
    val instructionMicroMiss = instructionProbePending && !instructionMicroMatch.orR &&
      !instructionNegativeHit
    val dataMicroMiss = dataProbePending && !dataMicroMatch.orR && !dataNegativeHit

    when(instructionQuickResponse) { instructionProbePending := False }
    when(dataQuickResponse) { dataProbePending := False }
    when(instructionMicroMiss) {
      instructionProbePending := False
      instructionWalkPending := True
    }
    when(dataMicroMiss) {
      dataProbePending := False
      dataWalkPending := True
    }

    val walkActive = RegInit(False)
    val walkOwnerData = RegInit(False)
    val walkVppn = Reg(Bits(19 bits))
    val walkOddPage = Reg(Bool())
    val walkAsid = Reg(Bits(10 bits))
    val walkSlice = RegInit(U(0, 3 bits))
    val preferData = RegInit(False)

    // Register the main-TLB slice result before it reaches translation and exception logic.
    // This mirrors the MainTLB sWalk/sEnd boundary in ysyx: a micro-TLB miss pays one rare
    // extra cycle, while the common micro-TLB-hit path remains unchanged.
    val walkResponsePending = RegInit(False)
    val walkResponseOwnerData = Reg(Bool())
    val walkResponseFound = Reg(Bool())
    val walkResponseIndex = Reg(UInt(5 bits))
    val walkResponseEntry = Reg(TlbEntryState())
    val walkResponseVppn = Reg(Bits(19 bits))
    val walkResponseOddPage = Reg(Bool())

    val instructionNeedsWalk = instructionWalkPending || instructionMicroMiss
    val dataNeedsWalk = dataWalkPending || dataMicroMiss
    val startWalk = !walkActive && !walkResponsePending && !mutation &&
      (instructionNeedsWalk || dataNeedsWalk)
    val startOwnerData = dataNeedsWalk && (!instructionNeedsWalk || preferData)
    when(startWalk) {
      walkActive := True
      walkOwnerData := startOwnerData
      walkSlice := 0
      when(startOwnerData) {
        walkVppn := dataVppn
        walkOddPage := dataOddPage
        walkAsid := dataAsid
      }.otherwise {
        walkVppn := instructionVppn
        walkOddPage := instructionOddPage
        walkAsid := instructionAsid
      }
      when(instructionNeedsWalk && dataNeedsWalk) { preferData := !preferData }.otherwise {
        preferData := !startOwnerData
      }
    }

    val walkEntries = Vec(TlbEntryState(), walkEntriesPerCycle)
    val walkMatch = Bits(walkEntriesPerCycle bits)
    for (lane <- 0 until walkEntriesPerCycle) {
      walkEntries(lane) := entryBanks(lane)(walkSlice)
      walkMatch(lane) := entryMatches(walkEntries(lane), walkVppn, walkAsid)
    }
    val walkLane = (0 until walkEntriesPerCycle)
      .map(lane => Mux(walkMatch(lane), U(lane, 2 bits), U(0, 2 bits)))
      .reduce(_ | _)
    val walkIndex = (walkSlice.asBits ## walkLane.asBits).asUInt
    val walkEntry = walkEntries(walkLane)
    val walkDone = walkActive && (walkMatch.orR || walkSlice === U(7, 3 bits))

    walkResponsePending := False
    when(walkActive && !walkDone) { walkSlice := walkSlice + 1 }
    when(walkDone) {
      walkActive := False
      walkResponsePending := True
      walkResponseOwnerData := walkOwnerData
      walkResponseFound := walkMatch.orR
      walkResponseIndex := walkIndex
      walkResponseEntry := walkEntry
      walkResponseVppn := walkVppn
      walkResponseOddPage := walkOddPage
    }

    when(walkResponsePending) {
      when(walkResponseOwnerData) {
        dataWalkPending := False
        when(walkResponseFound) {
          dataMicro(dataFillPointer) := walkResponseEntry
          dataMicroValid(dataFillPointer) := True
          dataFillPointer := dataFillPointer + 1
          dataNegativeValid := False
        }.otherwise {
          dataNegativeValid := True
          dataNegativeVppn := walkVppn
          dataNegativeAsid := walkAsid
        }
      }.otherwise {
        instructionWalkPending := False
        when(walkResponseFound) {
          instructionMicro(instructionFillPointer) := walkResponseEntry
          instructionMicroValid(instructionFillPointer) := True
          instructionFillPointer := instructionFillPointer + 1
          instructionNegativeValid := False
        }.otherwise {
          instructionNegativeValid := True
          instructionNegativeVppn := walkVppn
          instructionNegativeAsid := walkAsid
        }
      }
    }

    val instructionMicroResult = TlbLookupResult()
    driveMicroResult(
      instructionMicroResult,
      instructionMicroMatch,
      instructionMicro,
      instructionVppn,
      instructionOddPage
    )
    val instructionWalkResult = TlbLookupResult()
    driveResult(
      instructionWalkResult,
      walkResponseFound,
      walkResponseIndex,
      walkResponseEntry,
      walkResponseVppn,
      walkResponseOddPage
    )
    io.instructionResponse.valid :=
      instructionQuickResponse || (walkResponsePending && !walkResponseOwnerData)
    io.instructionResponse.payload :=
      Mux(instructionProbePending, instructionMicroResult, instructionWalkResult)

    val dataMicroResult = TlbLookupResult()
    driveMicroResult(dataMicroResult, dataMicroMatch, dataMicro, dataVppn, dataOddPage)
    val dataWalkResult = TlbLookupResult()
    driveResult(
      dataWalkResult,
      walkResponseFound,
      walkResponseIndex,
      walkResponseEntry,
      walkResponseVppn,
      walkResponseOddPage
    )
    io.dataResponse.valid := dataQuickResponse || (walkResponsePending && walkResponseOwnerData)
    io.dataResponse.payload := Mux(dataProbePending, dataMicroResult, dataWalkResult)

    when(mutation) {
      instructionMicroValid := 0
      dataMicroValid := 0
      instructionNegativeValid := False
      dataNegativeValid := False
      instructionProbePending := False
      instructionWalkPending := False
      dataProbePending := False
      dataWalkPending := False
      walkActive := False
      walkResponsePending := False
    }
  }
}
