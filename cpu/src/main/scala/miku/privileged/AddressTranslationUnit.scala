package miku.privileged

import miku.backend.ExceptionMetadata
import miku.core._
import spinal.core._
import spinal.lib._

final case class TranslationRequest(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val isWrite = Bool()
}

final case class TranslationResponse(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val physicalAddress = UInt(config.xlen bits)
  val uncached = Bool()
  val cancelled = Bool()
  val exception = ExceptionMetadata()
}

final case class TranslationBypass(config: OooCoreConfig) extends Bundle {
  val eligible = Bool()
  val physicalAddress = UInt(config.xlen bits)
  val uncached = Bool()
}

final case class TranslationContext(config: OooCoreConfig) extends Bundle {
  val virtualAddress = UInt(config.xlen bits)
  val isWrite = Bool()
  val translationEnabled = Bool()
  val dmw0Enabled = Bool()
  val dmw1Enabled = Bool()
  val memoryAttribute = Bits(2 bits)
  val privilege = Bits(2 bits)
  val disableCache = Bool()
}

/** Shared two-port LA32R translator with per-port micro-TLBs and a serialized main TLB. */
final class AddressTranslationUnit(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val instructionRequest = slave(Stream(TranslationRequest(config)))
    val instructionResponse = master(Stream(TranslationResponse(config)))
    val dataRequest = slave(Stream(TranslationRequest(config)))
    val dataResponse = master(Stream(TranslationResponse(config)))
    val dataBypassAddress = in UInt (config.xlen bits)
    val dataBypass = out(TranslationBypass(config))

    val csrAsid = in Bits (10 bits)
    val csrDa = in Bool ()
    val csrPg = in Bool ()
    val csrDmw0 = in Bits (32 bits)
    val csrDmw1 = in Bits (32 bits)
    val csrPrivilege = in Bits (2 bits)
    val instructionMat = in Bits (2 bits)
    val dataMat = in Bits (2 bits)
    val disableCache = in Bool ()

    val tlbFillValid = in Bool ()
    val tlbWriteValid = in Bool ()
    val tlbRandomIndex = in UInt (5 bits)
    val csrTlbEntryHigh = in Bits (32 bits)
    val csrTlbEntryLow0 = in Bits (32 bits)
    val csrTlbEntryLow1 = in Bits (32 bits)
    val csrTlbIndex = in Bits (32 bits)
    val csrExceptionCode = in Bits (6 bits)
    val tlbReadEntryHigh = out Bits (32 bits)
    val tlbReadEntryLow0 = out Bits (32 bits)
    val tlbReadEntryLow1 = out Bits (32 bits)
    val tlbReadIndex = out Bits (32 bits)
    val tlbReadAsid = out Bits (10 bits)

    val tlbInvalidateValid = in Bool ()
    val tlbInvalidateAsid = in Bits (10 bits)
    val tlbInvalidateVpn = in Bits (19 bits)
    val tlbInvalidateOperation = in Bits (5 bits)

    val tlbSearchValid = in Bool ()
    val tlbSearchVppn = in Bits (19 bits)
    val tlbSearchReady = out Bool ()
    val tlbSearchResponseValid = out Bool ()
    val tlbSearchFound = out Bool ()
    val tlbSearchIndex = out Bits (5 bits)
  }
  noIoPrefix()

  val domain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)
  )

  val area = new ClockingArea(domain) {
    val tlb = new HierarchicalTlb()
    tlb.io.clk := io.clk
    tlb.io.reset := io.reset
    tlb.io.writeValid := io.tlbFillValid || io.tlbWriteValid
    tlb.io.writeIndex := Mux(
      io.tlbFillValid,
      io.tlbRandomIndex,
      io.csrTlbIndex(4 downto 0).asUInt
    )
    tlb.io.writeEntry.vppn := io.csrTlbEntryHigh(31 downto 13)
    tlb.io.writeEntry.asid := io.csrAsid
    tlb.io.writeEntry.global := io.csrTlbEntryLow0(6) && io.csrTlbEntryLow1(6)
    tlb.io.writeEntry.pageSize := io.csrTlbIndex(29 downto 24)
    tlb.io.writeEntry.enabled :=
      Mux(io.csrExceptionCode === B(0x3f, 6 bits), True, !io.csrTlbIndex(31))
    tlb.io.writeEntry.ppn0 := io.csrTlbEntryLow0(27 downto 8)
    tlb.io.writeEntry.privilege0 := io.csrTlbEntryLow0(3 downto 2)
    tlb.io.writeEntry.memoryAttribute0 := io.csrTlbEntryLow0(5 downto 4)
    tlb.io.writeEntry.dirty0 := io.csrTlbEntryLow0(1)
    tlb.io.writeEntry.valid0 := io.csrTlbEntryLow0(0)
    tlb.io.writeEntry.ppn1 := io.csrTlbEntryLow1(27 downto 8)
    tlb.io.writeEntry.privilege1 := io.csrTlbEntryLow1(3 downto 2)
    tlb.io.writeEntry.memoryAttribute1 := io.csrTlbEntryLow1(5 downto 4)
    tlb.io.writeEntry.dirty1 := io.csrTlbEntryLow1(1)
    tlb.io.writeEntry.valid1 := io.csrTlbEntryLow1(0)
    tlb.io.invalidateValid := io.tlbInvalidateValid
    tlb.io.invalidateOperation := io.tlbInvalidateOperation
    tlb.io.invalidateAsid := io.tlbInvalidateAsid
    tlb.io.invalidateVpn := io.tlbInvalidateVpn
    tlb.io.readIndex := io.csrTlbIndex(4 downto 0).asUInt
    tlb.io.managementVppn := io.tlbSearchVppn
    tlb.io.managementAsid := io.csrAsid

    io.tlbReadEntryHigh := tlb.io.readEntry.vppn ## B(0, 13 bits)
    io.tlbReadEntryLow0 := B(0, 4 bits) ## tlb.io.readEntry.ppn0 ## B(0, 1 bits) ##
      tlb.io.readEntry.global ## tlb.io.readEntry.memoryAttribute0 ##
      tlb.io.readEntry.privilege0 ## tlb.io.readEntry.dirty0 ## tlb.io.readEntry.valid0
    io.tlbReadEntryLow1 := B(0, 4 bits) ## tlb.io.readEntry.ppn1 ## B(0, 1 bits) ##
      tlb.io.readEntry.global ## tlb.io.readEntry.memoryAttribute1 ##
      tlb.io.readEntry.privilege1 ## tlb.io.readEntry.dirty1 ## tlb.io.readEntry.valid1
    io.tlbReadIndex := (!tlb.io.readEntry.enabled).asBits ## B(0, 1 bits) ##
      tlb.io.readEntry.pageSize ## B(0, 24 bits)
    io.tlbReadAsid := tlb.io.readEntry.asid

    val pagingMode = !io.csrDa && io.csrPg
    val tlbMutation = io.tlbFillValid || io.tlbWriteValid || io.tlbInvalidateValid
    def dmwEnabled(address: UInt, dmw: Bits): Bool =
      pagingMode && address(31 downto 29) === dmw(31 downto 29).asUInt &&
        ((io.csrPrivilege === 0 && dmw(0)) || (io.csrPrivilege === 3 && dmw(3)))

    def bypassPhysicalAddress(address: UInt, dmw0Enabled: Bool, dmw1Enabled: Bool): UInt = {
      val physicalAddress = UInt(config.xlen bits)
      physicalAddress := address
      when(dmw0Enabled) {
        physicalAddress := (io.csrDmw0(27 downto 25) ## address(28 downto 0)).asUInt
      }.elsewhen(dmw1Enabled) {
        physicalAddress := (io.csrDmw1(27 downto 25) ## address(28 downto 0)).asUInt
      }
      physicalAddress
    }

    def translatedPhysicalAddress(
        address: UInt,
        lookup: TlbLookupResult
    ): UInt = {
      val physicalAddress = UInt(config.xlen bits)
      physicalAddress := (lookup.ppn ## address(11 downto 0).asBits).asUInt
      when(lookup.pageSize =/= B(12, 6 bits)) {
        physicalAddress := (lookup.ppn(19 downto 9) ## address(20 downto 0).asBits).asUInt
      }
      physicalAddress
    }

    val dataBypassDmw0 = dmwEnabled(io.dataBypassAddress, io.csrDmw0)
    val dataBypassDmw1 = dmwEnabled(io.dataBypassAddress, io.csrDmw1)
    val dataBypassTranslate = pagingMode && !dataBypassDmw0 && !dataBypassDmw1
    val dataBypassMemoryAttribute = Mux(
      dataBypassDmw0,
      io.csrDmw0(5 downto 4),
      Mux(dataBypassDmw1, io.csrDmw1(5 downto 4), io.dataMat)
    )
    io.dataBypass.eligible := !dataBypassTranslate
    io.dataBypass.physicalAddress := bypassPhysicalAddress(
      io.dataBypassAddress,
      dataBypassDmw0,
      dataBypassDmw1
    )
    io.dataBypass.uncached := io.disableCache || dataBypassMemoryAttribute === 0

    val instructionContext = Reg(TranslationContext(config))
    val instructionSearchPending = RegInit(False)
    val instructionResponseValid = RegInit(False)
    val instructionResponse = Reg(TranslationResponse(config))
    val instructionDmw0 = dmwEnabled(io.instructionRequest.virtualAddress, io.csrDmw0)
    val instructionDmw1 = dmwEnabled(io.instructionRequest.virtualAddress, io.csrDmw1)
    val instructionTranslate = pagingMode && !instructionDmw0 && !instructionDmw1
    val instructionResponseFire = io.instructionResponse.valid && io.instructionResponse.ready
    val instructionOwnerSlotAvailable = !instructionSearchPending &&
      (!instructionResponseValid || instructionResponseFire)
    // The iTLB port is idle whenever the ATU owner slot is available; both layers are blocked by
    // the same TLB mutation.  Qualify ready with capacity directly so the request VA's DMW/paging
    // decode cannot feed back through ready into the frontend turnover handshake.
    val instructionRequestReady = !tlbMutation && instructionOwnerSlotAvailable &&
      tlb.io.instructionRequest.ready
    io.instructionRequest.ready := instructionRequestReady
    val instructionRequestFire = io.instructionRequest.valid && io.instructionRequest.ready
    tlb.io.instructionRequest.valid := instructionRequestFire && instructionTranslate
    tlb.io.instructionRequest.vppn := io.instructionRequest.virtualAddress(31 downto 13).asBits
    tlb.io.instructionRequest.oddPage := io.instructionRequest.virtualAddress(12)
    tlb.io.instructionRequest.asid := io.csrAsid
    // Preload the owner payload whenever its slot is available.  Valid/search-pending remain
    // acceptance-qualified below, while the wide context and direct-response registers no longer
    // inherit the cache-hit-to-translation request valid cone on their clock enables.
    when(instructionOwnerSlotAvailable) {
      instructionContext.virtualAddress := io.instructionRequest.virtualAddress
      instructionContext.isWrite := False
      instructionContext.translationEnabled := instructionTranslate
      instructionContext.dmw0Enabled := instructionDmw0
      instructionContext.dmw1Enabled := instructionDmw1
      instructionContext.memoryAttribute := Mux(
        instructionDmw0,
        io.csrDmw0(5 downto 4),
        Mux(instructionDmw1, io.csrDmw1(5 downto 4), io.instructionMat)
      )
      instructionContext.privilege := io.csrPrivilege
      instructionContext.disableCache := io.disableCache
      val misaligned = io.instructionRequest.virtualAddress(1 downto 0) =/= 0
      instructionResponse.virtualAddress := io.instructionRequest.virtualAddress
      instructionResponse.physicalAddress := bypassPhysicalAddress(
        io.instructionRequest.virtualAddress,
        instructionDmw0,
        instructionDmw1
      )
      instructionResponse.cancelled := False
      instructionResponse.exception.valid := misaligned
      instructionResponse.exception.ecode := Mux(misaligned, U(8, 6 bits), U(0, 6 bits))
      instructionResponse.exception.esubcode := 0
      instructionResponse.exception.badVAddrValid := misaligned
      instructionResponse.exception.badVAddr := io.instructionRequest.virtualAddress
      instructionResponse.exception.tlbRefill := False
    }
    when(instructionRequestFire) {
      instructionSearchPending := instructionTranslate
      // Reserve the response owner for either route.  A paged request keeps this valid hidden
      // while its TLB search is pending; a direct request exposes it on the next cycle.  This
      // keeps the response-valid D input independent of the accepted VA's DMW/paging decode.
      instructionResponseValid := True
    }
    // Any accepted replacement retains the owner reservation.  Its context/search-pending state
    // determines whether the replacement response is visible.
    when(instructionResponseFire && !instructionRequestFire) {
      instructionResponseValid := False
    }

    when(instructionSearchPending && tlb.io.instructionResponse.valid) {
      val misaligned = instructionContext.virtualAddress(1 downto 0) =/= 0
      val refill = instructionContext.translationEnabled && !tlb.io.instructionResponse.found
      val invalid = instructionContext.translationEnabled && tlb.io.instructionResponse.found &&
        !tlb.io.instructionResponse.payload.valid
      val privilege = instructionContext.translationEnabled && tlb.io.instructionResponse.found &&
        tlb.io.instructionResponse.payload.valid &&
        instructionContext.privilege.asUInt > tlb.io.instructionResponse.privilege.asUInt
      instructionSearchPending := False
      instructionResponseValid := True
      instructionResponse.virtualAddress := instructionContext.virtualAddress
      instructionResponse.physicalAddress := translatedPhysicalAddress(
        instructionContext.virtualAddress,
        tlb.io.instructionResponse.payload
      )
      instructionResponse.uncached := instructionContext.disableCache ||
        Mux(
          instructionContext.translationEnabled,
          tlb.io.instructionResponse.memoryAttribute,
          instructionContext.memoryAttribute
        ) === 0
      instructionResponse.cancelled := False
      instructionResponse.exception.valid := misaligned || refill || invalid || privilege
      instructionResponse.exception.ecode := Mux(
        misaligned,
        U(8, 6 bits),
        Mux(
          refill,
          U(0x3f, 6 bits),
          Mux(invalid, U(3, 6 bits), Mux(privilege, U(7, 6 bits), U(0, 6 bits)))
        )
      )
      instructionResponse.exception.esubcode := 0
      instructionResponse.exception.badVAddrValid := misaligned || refill || invalid || privilege
      instructionResponse.exception.badVAddr := instructionContext.virtualAddress
      instructionResponse.exception.tlbRefill := !misaligned && refill
    }
    io.instructionResponse.valid := instructionResponseValid &&
      (!instructionContext.translationEnabled || !instructionSearchPending) && !tlbMutation
    val visibleInstructionResponse = TranslationResponse(config)
    // Every visible response belongs to the registered instruction owner.  Source the identity
    // from that owner so direct, paged and cancel completions do not each recreate a live
    // response-payload path for the same virtual address.
    visibleInstructionResponse.virtualAddress := instructionContext.virtualAddress
    visibleInstructionResponse.physicalAddress := instructionResponse.physicalAddress
    visibleInstructionResponse.uncached := instructionResponse.uncached
    visibleInstructionResponse.cancelled := instructionResponse.cancelled
    visibleInstructionResponse.exception := instructionResponse.exception
    // Direct and DMW requests expose the accepted owner's registered MAT snapshot.  TLB
    // completions retain their registered response payload, as do explicit cancel tokens.
    when(!instructionContext.translationEnabled && !instructionResponse.cancelled) {
      visibleInstructionResponse.uncached := instructionContext.disableCache ||
        instructionContext.memoryAttribute === 0
    }
    io.instructionResponse.payload := visibleInstructionResponse

    val dataContext = Reg(TranslationContext(config))
    val dataSearchPending = RegInit(False)
    val dataResponseValid = RegInit(False)
    val dataResponse = Reg(TranslationResponse(config))
    val dataDmw0 = dmwEnabled(io.dataRequest.virtualAddress, io.csrDmw0)
    val dataDmw1 = dmwEnabled(io.dataRequest.virtualAddress, io.csrDmw1)
    val dataTranslate = pagingMode && !dataDmw0 && !dataDmw1
    val dataRequestMemoryAttribute = Mux(
      dataDmw0,
      io.csrDmw0(5 downto 4),
      Mux(dataDmw1, io.csrDmw1(5 downto 4), io.dataMat)
    )
    // As on the instruction side, an available ATU owner implies that the data TLB port has no
    // accepted probe or walk.  Keep ready dependent on capacity and mutation only; routing the
    // accepted VA through DMW or the TLB remains acceptance-qualified below.
    val dataRequestReady = !tlbMutation && !dataSearchPending && !dataResponseValid &&
      tlb.io.dataRequest.ready
    io.dataRequest.ready := dataRequestReady
    val dataRequestFire = io.dataRequest.valid && io.dataRequest.ready
    tlb.io.dataRequest.valid := dataRequestFire && dataTranslate
    tlb.io.dataRequest.vppn := io.dataRequest.virtualAddress(31 downto 13).asBits
    tlb.io.dataRequest.oddPage := io.dataRequest.virtualAddress(12)
    tlb.io.dataRequest.asid := io.csrAsid
    io.tlbSearchReady := True
    // Prefill the bypass payload whenever the response slot is free. Request
    // acceptance then only qualifies visibility; a later TLB completion or
    // mutation overrides these fields with higher assignment priority.
    when(!dataResponseValid) {
      dataResponse.virtualAddress := io.dataRequest.virtualAddress
      dataResponse.physicalAddress := bypassPhysicalAddress(
        io.dataRequest.virtualAddress,
        dataDmw0,
        dataDmw1
      )
      dataResponse.uncached := io.disableCache || dataRequestMemoryAttribute === 0
      dataResponse.cancelled := False
      dataResponse.exception.valid := False
      dataResponse.exception.ecode := 0
      dataResponse.exception.esubcode := 0
      dataResponse.exception.badVAddrValid := False
      dataResponse.exception.badVAddr := io.dataRequest.virtualAddress
      dataResponse.exception.tlbRefill := False
    }
    when(dataRequestFire) {
      dataContext.virtualAddress := io.dataRequest.virtualAddress
      dataContext.isWrite := io.dataRequest.isWrite
      dataContext.translationEnabled := dataTranslate
      dataContext.dmw0Enabled := dataDmw0
      dataContext.dmw1Enabled := dataDmw1
      dataContext.memoryAttribute := dataRequestMemoryAttribute
      dataContext.privilege := io.csrPrivilege
      dataContext.disableCache := io.disableCache
      dataSearchPending := dataTranslate
      when(!dataTranslate) {
        dataResponseValid := True
      }
    }
    when(io.dataResponse.valid && io.dataResponse.ready) { dataResponseValid := False }

    when(dataSearchPending && tlb.io.dataResponse.valid) {
      val refill = dataContext.translationEnabled && !tlb.io.dataResponse.found
      val invalid = dataContext.translationEnabled && tlb.io.dataResponse.found &&
        !tlb.io.dataResponse.payload.valid
      val privilege = dataContext.translationEnabled && tlb.io.dataResponse.found &&
        tlb.io.dataResponse.payload.valid &&
        dataContext.privilege.asUInt > tlb.io.dataResponse.privilege.asUInt
      val modify = dataContext.translationEnabled && dataContext.isWrite &&
        tlb.io.dataResponse.found && tlb.io.dataResponse.payload.valid && !privilege &&
        !tlb.io.dataResponse.dirty
      dataSearchPending := False
      dataResponseValid := True
      dataResponse.virtualAddress := dataContext.virtualAddress
      dataResponse.physicalAddress := translatedPhysicalAddress(
        dataContext.virtualAddress,
        tlb.io.dataResponse.payload
      )
      dataResponse.uncached := dataContext.disableCache ||
        Mux(
          dataContext.translationEnabled,
          tlb.io.dataResponse.memoryAttribute,
          dataContext.memoryAttribute
        ) === 0
      dataResponse.cancelled := False
      dataResponse.exception.valid := refill || invalid || privilege || modify
      dataResponse.exception.ecode := Mux(
        refill,
        U(0x3f, 6 bits),
        Mux(
          invalid,
          Mux(dataContext.isWrite, U(2, 6 bits), U(1, 6 bits)),
          Mux(privilege, U(7, 6 bits), Mux(modify, U(4, 6 bits), U(0, 6 bits)))
        )
      )
      dataResponse.exception.esubcode := 0
      dataResponse.exception.badVAddrValid := refill || invalid || privilege || modify
      dataResponse.exception.badVAddr := dataContext.virtualAddress
      dataResponse.exception.tlbRefill := refill
    }
    io.dataResponse.valid := dataResponseValid && !tlbMutation
    io.dataResponse.payload := dataResponse
    // Management search is combinational, matching the architectural TLBSRCH
    // boundary: TLBIDX is updated on the same edge that retires the instruction.
    io.tlbSearchResponseValid := io.tlbSearchValid
    io.tlbSearchFound := tlb.io.managementFound
    io.tlbSearchIndex := tlb.io.managementIndex.asBits

    when(tlbMutation) {
      when(instructionSearchPending || instructionResponseValid) {
        instructionResponseValid := True
        instructionResponse.virtualAddress := instructionContext.virtualAddress
        instructionResponse.physicalAddress := 0
        instructionResponse.uncached := False
        instructionResponse.cancelled := True
        instructionResponse.exception.assignFromBits(
          B(0, instructionResponse.exception.getBitsWidth bits)
        )
      }.otherwise {
        instructionResponseValid := False
      }
      instructionSearchPending := False
      when(dataSearchPending || dataResponseValid) {
        dataResponseValid := True
        dataResponse.virtualAddress := dataContext.virtualAddress
        dataResponse.physicalAddress := 0
        dataResponse.uncached := False
        dataResponse.cancelled := True
        dataResponse.exception.assignFromBits(B(0, dataResponse.exception.getBitsWidth bits))
      }.otherwise {
        dataResponseValid := False
      }
      dataSearchPending := False
    }
  }
}
