package miku.core

import miku.backend._
import miku.compat.Axi3Compat
import miku.memory._
import miku.observe.{
  ArchState,
  ChiplabMultiCommitDiffTestAdapter,
  CommitEvent,
  PerfObservationV1
}
import miku.privileged._
import spinal.core._
import spinal.lib._

/** OoO core with architectural CSR/TLB state and the 64-byte AXI3 line bridge. */
final class OooCoreSystem(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val aclk = in Bool ()
    val reset = in Bool ()
    val intrpt = in Bits (8 bits)
    val axi = master(Axi3Compat())

    val breakPoint = in Bool ()
    val informationSelect = in Bool ()
    val registerNumber = in Bits (5 bits)
    val writebackValid = out Bool ()
    val registerReadData = out Bits (config.xlen bits)
    val debugPc = out Bits (config.xlen bits)
    val debugGprWriteMask = out Bits (4 bits)
    val debugGprIndex = out Bits (5 bits)
    val debugGprData = out Bits (config.xlen bits)
    val debugInstruction = out Bits (32 bits)
  }

  noIoPrefix()

  val systemClockDomain = ClockDomain(
    clock = io.aclk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val systemArea = new ClockingArea(systemClockDomain) {
    val core = new OooCore(config)
    // Keep the CSR diff views in the generated design.  The view is wrapped by a conditional
    // chiplab DPI shell, so it is inert in synthesis and available to the official simulator.
    val csr = new CsrFile(config = config, diffTestEnabled = true, tlbNum = 32)
    val addressTranslation = new AddressTranslationUnit(config)
    val axiBridge = new AxiLineBridge(config)
    val idleController = new IdleController(config)

    // Serializing commits already generate a registered redirect in the core.
    // Keep that redirect timing, but register the state-changing payloads at
    // this boundary.  CSR/TLB/cache state therefore updates on the flush edge
    // instead of carrying the ROB commit decode cone into a wide CE network.
    val committedCsrWriteValid = RegNext(core.io.csrWriteValid) init (False)
    val committedCsrWriteAddress = RegNext(core.io.csrWriteAddress) init (U(0, 14 bits))
    val committedCsrWriteData = RegNext(core.io.csrWriteData) init (B(0, config.xlen bits))
    val committedCsrWriteMask = RegNext(core.io.csrWriteMask) init (False)
    val committedErtnValid = RegNext(core.io.ertnValid) init (False)
    val committedTlbSearchValid = RegNext(core.io.tlbSearchValid) init (False)
    val committedTlbSearchVppn = RegNext(csr.io.vppn_out) init (B(0, 19 bits))
    val committedTlbReadValid = RegNext(core.io.tlbReadValid) init (False)
    val committedTlbWriteValid = RegNext(core.io.tlbWriteValid) init (False)
    val committedTlbFillValid = RegNext(core.io.tlbFillValid) init (False)
    val committedTlbRandomIndex = RegNext(csr.io.rand_index.asUInt) init (U(0, 5 bits))
    val committedTlbInvalidateValid = RegNext(core.io.tlbInvalidateValid) init (False)
    val committedTlbInvalidateAsid = RegNext(core.io.tlbInvalidateAsid) init (B(0, 10 bits))
    val committedTlbInvalidateVpn = RegNext(core.io.tlbInvalidateVpn) init (B(0, 19 bits))
    val committedTlbInvalidateOperation =
      RegNext(core.io.tlbInvalidateOperation) init (B(0, 5 bits))
    val committedReservationBitSet = RegNext(core.io.reservationBitSet) init (False)
    val committedReservationBitValue = RegNext(core.io.reservationBitValue) init (False)
    val committedReservationAddressSet = RegNext(core.io.reservationAddressSet) init (False)
    val committedReservationLineAddress =
      RegNext(core.io.reservationLineAddressUpdate) init (
        B(0, config.reservationAddressWidth bits)
      )
    val committedCacheInvalidateValid = RegNext(core.io.cacheInvalidateValid) init (False)
    val committedDataCacheInvalidateValid = RegNext(core.io.dataCacheInvalidateValid) init (False)
    val committedDataCacheWritebackInvalidateValid =
      RegNext(core.io.dataCacheWritebackInvalidateValid) init (False)
    val committedLevel2CacheInvalidateValid =
      RegNext(core.io.level2CacheInvalidateValid) init (False)

    csr.io.clk := io.aclk
    csr.io.reset := io.reset
    addressTranslation.io.clk := io.aclk
    addressTranslation.io.reset := io.reset

    core.io.systemReadData := csr.io.rd_data
    core.io.timer := csr.io.timer_64_out
    core.io.timerId := csr.io.tid_out
    // CSR writes, exception entry, and ERTN all refetch before the next instruction is accepted.
    // Snapshot the forwarded CSR context at that boundary so translation does not inherit the long
    // ROB-commit -> CSR-forwarding -> TLB response combinational path.
    val translationAsid = RegNext(csr.io.asid_out) init (B(0, 10 bits))
    val translationDa = RegNext(csr.io.da_out) init (True)
    val translationPg = RegNext(csr.io.pg_out) init (False)
    val translationDmw0 = RegNext(csr.io.dmw0_out) init (B(0, config.xlen bits))
    val translationDmw1 = RegNext(csr.io.dmw1_out) init (B(0, config.xlen bits))
    val translationPrivilege = RegNext(csr.io.plv_out) init (B(0, 2 bits))
    val translationInstructionMat = RegNext(csr.io.datf_out) init (B(0, 2 bits))
    val translationDataMat = RegNext(csr.io.datm_out) init (B(0, 2 bits))
    val translationDisableCache = RegNext(csr.io.disable_cache_out) init (False)

    core.io.privilege := translationPrivilege
    core.io.interruptPending := csr.io.has_int
    core.io.debugReadAddress := io.registerNumber.asUInt
    csr.io.rd_addr := core.io.systemReadAddress.asBits
    csr.io.csr_wr_en := committedCsrWriteValid
    csr.io.wr_addr := committedCsrWriteAddress.asBits
    csr.io.wr_data := committedCsrWriteData
    csr.io.interrupt := io.intrpt

    csr.io.excp_flush := core.io.exceptionValid
    csr.io.era_in := core.io.exceptionPc.asBits
    csr.io.esubcode_in := core.io.exception.esubcode.asBits
    csr.io.ecode_in := core.io.exception.ecode.asBits
    csr.io.va_error_in := core.io.exceptionValid && core.io.exception.badVAddrValid
    csr.io.bad_va_in := core.io.exception.badVAddr.asBits
    csr.io.excp_tlbrefill := core.io.exceptionValid && core.io.exception.tlbRefill
    val tlbExceptionCode = core.io.exception.ecode === 1 || core.io.exception.ecode === 2 ||
      core.io.exception.ecode === 3 || core.io.exception.ecode === 4 ||
      core.io.exception.ecode === 7
    csr.io.excp_tlb := core.io.exceptionValid &&
      (core.io.exception.tlbRefill || tlbExceptionCode)
    csr.io.excp_tlb_vppn := Mux(
      core.io.exception.badVAddrValid,
      core.io.exception.badVAddr(31 downto 13).asBits,
      core.io.exceptionPc(31 downto 13).asBits
    )

    val privilegedRedirectRequest = core.io.ertnValid || core.io.refetchValid ||
      core.io.tlbSearchValid || core.io.tlbReadValid || core.io.tlbWriteValid ||
      core.io.tlbFillValid || core.io.tlbInvalidateValid
    val privilegedRedirectPending = RegNext(privilegedRedirectRequest) init (False)
    val privilegedRedirectTarget = Reg(UInt(config.xlen bits))
    if (config.enableUnconditionalPrivilegedRedirectTargetCapture) {
      // The target is observed only with the following registered pending bit.
      // Sampling it unconditionally preserves that edge while removing the
      // wide target register's request-qualified clock enable.
      privilegedRedirectTarget := Mux(
        core.io.ertnValid,
        csr.io.era_out.asUInt,
        core.io.serialCommitPc + 4
      )
    } else {
      when(privilegedRedirectRequest) {
        privilegedRedirectTarget := Mux(
          core.io.ertnValid,
          csr.io.era_out.asUInt,
          core.io.serialCommitPc + 4
        )
      }
    }
    idleController.io.enterValid := core.io.idleValid
    idleController.io.enterPc := core.io.serialCommitPc
    idleController.io.interruptPending := csr.io.has_int
    core.io.externalRedirectValid := privilegedRedirectPending || idleController.io.redirectValid
    core.io.externalRedirectTarget := Mux(
      privilegedRedirectPending,
      privilegedRedirectTarget,
      idleController.io.redirectTarget
    )
    core.io.exceptionEntryTarget := csr.io.eentry_out.asUInt
    core.io.tlbRefillTarget := csr.io.tlbrentry_out.asUInt
    // Align the ERTN CSR restore with the registered redirect/flush edge.  The
    // retirement cycle still observes the pre-ERTN mode; redirected fetch sees
    // the restored CRMD/PRMD state.
    csr.io.ertn_flush := committedErtnValid
    core.io.cacheInvalidate := committedCacheInvalidateValid
    core.io.dataCacheInvalidate := committedDataCacheInvalidateValid
    core.io.dataCacheWritebackInvalidate := committedDataCacheWritebackInvalidateValid
    core.io.level2CacheInvalidate := committedLevel2CacheInvalidateValid

    addressTranslation.io.instructionRequest.valid := core.io.instructionTranslationRequest.valid
    addressTranslation.io.instructionRequest.payload :=
      core.io.instructionTranslationRequest.payload
    core.io.instructionTranslationRequest.ready :=
      addressTranslation.io.instructionRequest.ready
    core.io.instructionTranslationResponse.valid :=
      addressTranslation.io.instructionResponse.valid
    core.io.instructionTranslationResponse.payload :=
      addressTranslation.io.instructionResponse.payload
    addressTranslation.io.instructionResponse.ready :=
      core.io.instructionTranslationResponse.ready
    addressTranslation.io.dataRequest.valid := core.io.dataTranslationRequest.valid
    addressTranslation.io.dataRequest.payload := core.io.dataTranslationRequest.payload
    core.io.dataTranslationRequest.ready := addressTranslation.io.dataRequest.ready
    core.io.dataTranslationResponse.valid := addressTranslation.io.dataResponse.valid
    core.io.dataTranslationResponse.payload := addressTranslation.io.dataResponse.payload
    addressTranslation.io.dataResponse.ready := core.io.dataTranslationResponse.ready
    addressTranslation.io.dataBypassAddress := core.io.dataTranslationBypassAddress
    core.io.dataTranslationBypass := addressTranslation.io.dataBypass
    core.io.reservationValid := csr.io.llbit_out
    core.io.reservationLineAddress := csr.io.lladdr_out

    // Maintain an architectural GPR mirror at retirement.  The speculative PRF is intentionally
    // not exposed to the board debug port: the mirror is both deterministic after reset and the
    // exact state consumed by chiplab's GRegState DPI callback.
    val architecturalGpr =
      Vec.fill(config.archRegs)(Reg(Bits(config.xlen bits)) init (B(0, config.xlen bits)))
    architecturalGpr(0) := B(0, config.xlen bits)
    for (lane <- 0 until config.commitWidth) {
      when(
        core.io.commitValid(lane) && core.io.commit(lane).retired &&
          core.io.commit(lane).writesGpr && core.io.commit(lane).rd =/= 0
      ) {
        architecturalGpr(core.io.commit(lane).rd) := core.io.commit(lane).result
      }
    }

    addressTranslation.io.csrAsid := translationAsid
    addressTranslation.io.csrDa := translationDa
    addressTranslation.io.csrPg := translationPg
    addressTranslation.io.csrDmw0 := translationDmw0
    addressTranslation.io.csrDmw1 := translationDmw1
    addressTranslation.io.csrPrivilege := translationPrivilege
    addressTranslation.io.instructionMat := translationInstructionMat
    addressTranslation.io.dataMat := translationDataMat
    addressTranslation.io.disableCache := translationDisableCache
    addressTranslation.io.tlbFillValid := committedTlbFillValid
    addressTranslation.io.tlbWriteValid := committedTlbWriteValid
    addressTranslation.io.tlbRandomIndex := committedTlbRandomIndex
    addressTranslation.io.csrTlbEntryHigh := csr.io.tlbehi_out
    addressTranslation.io.csrTlbEntryLow0 := csr.io.tlbelo0_out
    addressTranslation.io.csrTlbEntryLow1 := csr.io.tlbelo1_out
    addressTranslation.io.csrTlbIndex := csr.io.tlbidx_out
    addressTranslation.io.csrExceptionCode := csr.io.ecode_out
    addressTranslation.io.tlbInvalidateValid := committedTlbInvalidateValid
    addressTranslation.io.tlbInvalidateAsid := committedTlbInvalidateAsid
    addressTranslation.io.tlbInvalidateVpn := committedTlbInvalidateVpn
    addressTranslation.io.tlbInvalidateOperation := committedTlbInvalidateOperation
    addressTranslation.io.tlbSearchValid := committedTlbSearchValid
    addressTranslation.io.tlbSearchVppn := committedTlbSearchVppn

    csr.io.tlbsrch_en := addressTranslation.io.tlbSearchResponseValid
    csr.io.tlbsrch_found := addressTranslation.io.tlbSearchFound
    csr.io.tlbsrch_index := addressTranslation.io.tlbSearchIndex
    csr.io.tlbrd_en := committedTlbReadValid
    csr.io.tlbehi_in := addressTranslation.io.tlbReadEntryHigh
    csr.io.tlbelo0_in := addressTranslation.io.tlbReadEntryLow0
    csr.io.tlbelo1_in := addressTranslation.io.tlbReadEntryLow1
    csr.io.tlbidx_in := addressTranslation.io.tlbReadIndex
    csr.io.asid_in := addressTranslation.io.tlbReadAsid
    csr.io.llbit_in := committedReservationBitValue
    csr.io.llbit_set_in := committedReservationBitSet
    csr.io.lladdr_in := committedReservationLineAddress
    csr.io.lladdr_set_in := committedReservationAddressSet

    axiBridge.io.memoryReadValid := core.io.memoryReadValid
    axiBridge.io.memoryRead := core.io.memoryRead
    core.io.memoryReadReady := axiBridge.io.memoryReadReady
    core.io.memoryReadBeatValid := axiBridge.io.memoryReadBeatValid
    core.io.memoryReadBeat := axiBridge.io.memoryReadBeat
    axiBridge.io.memoryReadBeatReady := core.io.memoryReadBeatReady
    axiBridge.io.memoryWriteValid := core.io.memoryWriteValid
    axiBridge.io.memoryWrite := core.io.memoryWrite
    core.io.memoryWriteReady := axiBridge.io.memoryWriteReady
    core.io.memoryWriteResponseValid := axiBridge.io.memoryWriteResponseValid
    core.io.memoryWriteResponse := axiBridge.io.memoryWriteResponse
    core.io.memoryBusIdle := axiBridge.io.idle
    axiBridge.io.uncachedInstructionRequestValid :=
      core.io.uncachedInstructionRequestValid
    axiBridge.io.uncachedInstructionRequest := core.io.uncachedInstructionRequest
    core.io.uncachedInstructionRequestReady :=
      axiBridge.io.uncachedInstructionRequestReady
    core.io.uncachedInstructionResponseValid :=
      axiBridge.io.uncachedInstructionResponseValid
    core.io.uncachedInstructionResponse := axiBridge.io.uncachedInstructionResponse
    axiBridge.io.uncachedDataRequestValid := core.io.uncachedDataRequestValid
    axiBridge.io.uncachedDataRequest := core.io.uncachedDataRequest
    core.io.uncachedDataRequestReady := axiBridge.io.uncachedDataRequestReady
    core.io.uncachedDataResponseValid := axiBridge.io.uncachedDataResponseValid
    core.io.uncachedDataResponse := axiBridge.io.uncachedDataResponse

    axiBridge.io.axi.ar.ready := io.axi.ar.ready
    axiBridge.io.axi.r.payload := io.axi.r.payload
    axiBridge.io.axi.r.valid := io.axi.r.valid
    axiBridge.io.axi.aw.ready := io.axi.aw.ready
    axiBridge.io.axi.w.ready := io.axi.w.ready
    axiBridge.io.axi.b.payload := io.axi.b.payload
    axiBridge.io.axi.b.valid := io.axi.b.valid
    io.axi.ar.payload := axiBridge.io.axi.ar.payload
    io.axi.ar.valid := axiBridge.io.axi.ar.valid
    io.axi.r.ready := axiBridge.io.axi.r.ready
    io.axi.aw.payload := axiBridge.io.axi.aw.payload
    io.axi.aw.valid := axiBridge.io.axi.aw.valid
    io.axi.w.payload := axiBridge.io.axi.w.payload
    io.axi.w.valid := axiBridge.io.axi.w.valid
    io.axi.b.ready := axiBridge.io.axi.b.ready

    // Stable simulation observation ABI. Each owning module publishes one
    // word locally; this system scope owns only the header and AXI activity.
    val perfObservationV1Word0 = Bits(PerfObservationV1.WordWidth bits)
    perfObservationV1Word0 := 0
    perfObservationV1Word0(31 downto 0) := B(PerfObservationV1.Magic, 32 bits)
    perfObservationV1Word0(39 downto 32) := B(PerfObservationV1.Version, 8 bits)
    perfObservationV1Word0(47 downto 40) := B(PerfObservationV1.WordCount, 8 bits)
    perfObservationV1Word0(55 downto 48) := B(config.commitWidth, 8 bits)
    perfObservationV1Word0(63 downto 56) := B(config.executionWidth, 8 bits)
    PerfObservationV1.expose(perfObservationV1Word0, 0)

    val perfObservationV1Word7 = Bits(PerfObservationV1.WordWidth bits)
    perfObservationV1Word7 := 0
    perfObservationV1Word7(0) := io.axi.ar.valid
    perfObservationV1Word7(1) := io.axi.ar.ready
    perfObservationV1Word7(2) := io.axi.ar.valid && io.axi.ar.ready
    perfObservationV1Word7(3) := io.axi.r.valid
    perfObservationV1Word7(4) := io.axi.r.ready
    perfObservationV1Word7(5) := io.axi.r.valid && io.axi.r.ready
    perfObservationV1Word7(6) :=
      io.axi.r.valid && io.axi.r.payload.response =/= B(0, 2 bits)
    perfObservationV1Word7(7) := io.axi.aw.valid
    perfObservationV1Word7(8) := io.axi.aw.ready
    perfObservationV1Word7(9) := io.axi.aw.valid && io.axi.aw.ready
    perfObservationV1Word7(10) := io.axi.w.valid
    perfObservationV1Word7(11) := io.axi.w.ready
    perfObservationV1Word7(12) := io.axi.w.valid && io.axi.w.ready
    perfObservationV1Word7(13) := io.axi.b.valid
    perfObservationV1Word7(14) := io.axi.b.ready
    perfObservationV1Word7(15) := io.axi.b.valid && io.axi.b.ready
    perfObservationV1Word7(16) :=
      io.axi.b.valid && io.axi.b.payload.response =/= B(0, 2 bits)
    PerfObservationV1.expose(perfObservationV1Word7, 7)

    io.writebackValid := core.io.debugCommitValid
    io.registerReadData := architecturalGpr(io.registerNumber.asUInt)
    io.debugPc := core.io.debugCommit.pc.asBits
    io.debugGprWriteMask := B(
      4 bits,
      default ->
        (core.io.debugCommitValid && core.io.debugCommit.retired &&
          core.io.debugCommit.writesGpr && core.io.debugCommit.rd =/= 0)
    )
    io.debugGprIndex := core.io.debugCommit.rd.asBits
    io.debugGprData := core.io.debugCommit.result
    io.debugInstruction := core.io.debugCommit.instruction

    val diffCommit = Vec(CommitEvent(), config.commitWidth)
    for (lane <- 0 until config.commitWidth) {
      val record = core.io.commit(lane)
      val memory = core.io.commitMemory(lane)
      val event = diffCommit(lane)
      event.pc := record.pc
      event.instruction := record.instruction
      event.retired := record.retired
      event.ertn := record.retired && record.systemOperation === SystemOperation.ertn
      event.isCounterInstruction := record.systemOperation === SystemOperation.counterId ||
        record.systemOperation === SystemOperation.counterLow ||
        record.systemOperation === SystemOperation.counterHigh
      event.csrRstat := record.systemOperation === SystemOperation.csrRead &&
        record.csrAddress === U(5, 14 bits)
      event.csrReadData := record.result
      event.gprWrite.valid := record.retired && record.writesGpr && record.rd =/= 0
      event.gprWrite.index := record.rd
      event.gprWrite.data := record.result
      event.csrWrite.valid := record.retired && record.csrWrite
      event.csrWrite.address := record.csrAddress
      event.csrWrite.data := record.sideEffectData
      event.exception.valid := record.exception.valid
      event.exception.ecode := record.exception.ecode
      event.exception.esubcode := record.exception.esubcode
      event.exception.badVAddrValid := record.exception.badVAddrValid
      event.exception.badVAddr := record.exception.badVAddr
      event.exception.tlbRefill := record.exception.tlbRefill
      event.exception.tlbException := record.exception.tlbRefill
      event.exception.tlbVppn := record.exception.badVAddr(31 downto 13)
      event.timer := core.io.timer.asUInt
      // Chiplab seeds NEMU's stable counter from this field immediately before
      // executing RDCNT*. An OoO counter read may have sampled earlier than its
      // retirement, so forward the value actually stored in the ROB for the
      // half selected by the instruction.
      when(record.systemOperation === SystemOperation.counterLow) {
        event.timer(31 downto 0) := record.result.asUInt
      }
      when(record.systemOperation === SystemOperation.counterHigh) {
        event.timer(63 downto 32) := record.result.asUInt
      }
      event.load.instructionMask := memory.loadInstructionMask
      event.load.pAddr := memory.physicalAddress
      event.load.vAddr := memory.virtualAddress
      event.store.instructionMask := memory.storeInstructionMask
      event.store.pAddr := memory.physicalAddress
      event.store.vAddr := memory.virtualAddress
      event.store.data := memory.storeData
      event.store.byteMask := memory.storeByteMask
      event.tlbFill.valid := record.retired && record.systemOperation === SystemOperation.tlbFill
      event.tlbFill.index := csr.io.rand_index.asUInt
    }

    val diffTest = new ChiplabMultiCommitDiffTestAdapter(config.commitWidth)
    diffTest.io.clock := io.aclk
    diffTest.io.commitValid := core.io.commitValid
    diffTest.io.commit := diffCommit
    diffTest.io.stateDelayed := core.io.commit.map(_.serializing).asBits()
    val diffArchState = ArchState()
    for (index <- 0 until config.archRegs) {
      diffArchState.gpr(index) := architecturalGpr(index)
    }
    diffArchState.crmd := csr.io.csr_crmd_diff
    diffArchState.prmd := csr.io.csr_prmd_diff
    diffArchState.euen := B(0, 32 bits)
    diffArchState.ecfg := csr.io.csr_ectl_diff
    diffArchState.estat := csr.io.csr_estat_diff
    diffArchState.era := csr.io.csr_era_diff
    diffArchState.badv := csr.io.csr_badv_diff
    diffArchState.eentry := csr.io.csr_eentry_diff
    diffArchState.tlbidx := csr.io.csr_tlbidx_diff
    diffArchState.tlbehi := csr.io.csr_tlbehi_diff
    diffArchState.tlbelo0 := csr.io.csr_tlbelo0_diff
    diffArchState.tlbelo1 := csr.io.csr_tlbelo1_diff
    diffArchState.asid := csr.io.csr_asid_diff
    diffArchState.pgdl := csr.io.csr_pgdl_diff
    diffArchState.pgdh := csr.io.csr_pgdh_diff
    diffArchState.save0 := csr.io.csr_save0_diff
    diffArchState.save1 := csr.io.csr_save1_diff
    diffArchState.save2 := csr.io.csr_save2_diff
    diffArchState.save3 := csr.io.csr_save3_diff
    diffArchState.tid := csr.io.csr_tid_diff
    diffArchState.tcfg := csr.io.csr_tcfg_diff
    diffArchState.tval := csr.io.csr_tval_diff
    diffArchState.ticlr := csr.io.csr_ticlr_diff
    diffArchState.llbctl := csr.io.csr_llbctl_diff
    diffArchState.tlbrentry := csr.io.csr_tlbrentry_diff
    diffArchState.dmw0 := csr.io.csr_dmw0_diff
    diffArchState.dmw1 := csr.io.csr_dmw1_diff
    diffTest.io.archState := diffArchState
  }
}
