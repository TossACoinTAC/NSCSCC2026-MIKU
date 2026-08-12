package miku.privileged

import miku.core._
import spinal.core._

/** Cycle-compatible owner of the active a158aa8 CSR state.
  *
  * Inputs and outputs intentionally retain the legacy csr module contract. State updates occur on
  * the rising edge of clk with a synchronous active-high reset. Registers with partial reset in the
  * golden RTL also have partial reset here; this module does not manufacture values for undefined
  * legacy bits.
  */
final class CsrFile(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit,
    diffTestEnabled: Boolean = false,
    tlbNum: Int = 32
) extends Component {
  require(tlbNum == 32, "the locked MIKU contract requires TLBNUM=32")

  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val rd_addr = in Bits (14 bits)
    val rd_data = out Bits (32 bits)
    val timer_64_out = out Bits (64 bits)
    val tid_out = out Bits (32 bits)
    val csr_wr_en = in Bool ()
    val wr_addr = in Bits (14 bits)
    val wr_data = in Bits (32 bits)
    val interrupt = in Bits (8 bits)
    val has_int = out Bool ()
    val excp_flush = in Bool ()
    val ertn_flush = in Bool ()
    val era_in = in Bits (32 bits)
    val esubcode_in = in Bits (9 bits)
    val ecode_in = in Bits (6 bits)
    val va_error_in = in Bool ()
    val bad_va_in = in Bits (32 bits)
    val tlbsrch_en = in Bool ()
    val tlbsrch_found = in Bool ()
    val tlbsrch_index = in Bits (5 bits)
    val excp_tlbrefill = in Bool ()
    val excp_tlb = in Bool ()
    val excp_tlb_vppn = in Bits (19 bits)
    val llbit_in = in Bool ()
    val llbit_set_in = in Bool ()
    val lladdr_in = in Bits (config.reservationAddressWidth bits)
    val lladdr_set_in = in Bool ()
    val llbit_out = out Bool ()
    val vppn_out = out Bits (19 bits)
    val lladdr_out = out Bits (config.reservationAddressWidth bits)
    val eentry_out = out Bits (32 bits)
    val era_out = out Bits (32 bits)
    val tlbrentry_out = out Bits (32 bits)
    val disable_cache_out = out Bool ()
    val asid_out = out Bits (10 bits)
    val rand_index = out Bits (5 bits)
    val tlbehi_out = out Bits (32 bits)
    val tlbelo0_out = out Bits (32 bits)
    val tlbelo1_out = out Bits (32 bits)
    val tlbidx_out = out Bits (32 bits)
    val pg_out = out Bool ()
    val da_out = out Bool ()
    val dmw0_out = out Bits (32 bits)
    val dmw1_out = out Bits (32 bits)
    val datf_out = out Bits (2 bits)
    val datm_out = out Bits (2 bits)
    val ecode_out = out Bits (6 bits)
    val tlbrd_en = in Bool ()
    val tlbehi_in = in Bits (32 bits)
    val tlbelo0_in = in Bits (32 bits)
    val tlbelo1_in = in Bits (32 bits)
    val tlbidx_in = in Bits (32 bits)
    val asid_in = in Bits (10 bits)
    val plv_out = out Bits (2 bits)

    val csr_crmd_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_prmd_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_ectl_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_estat_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_era_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_badv_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_eentry_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tlbidx_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tlbehi_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tlbelo0_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tlbelo1_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_asid_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_save0_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_save1_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_save2_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_save3_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tid_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tcfg_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tval_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_ticlr_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_llbctl_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_tlbrentry_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_dmw0_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_dmw1_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_pgdl_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
    val csr_pgdh_diff = if (diffTestEnabled) out(Bits(32 bits)) else null
  }

  noIoPrefix()

  private object Address {
    val Crmd = B(0x0000, 14 bits)
    val Prmd = B(0x0001, 14 bits)
    val Ectl = B(0x0004, 14 bits)
    val Estat = B(0x0005, 14 bits)
    val Era = B(0x0006, 14 bits)
    val Badv = B(0x0007, 14 bits)
    val Eentry = B(0x000c, 14 bits)
    val Tlbidx = B(0x0010, 14 bits)
    val Tlbehi = B(0x0011, 14 bits)
    val Tlbelo0 = B(0x0012, 14 bits)
    val Tlbelo1 = B(0x0013, 14 bits)
    val Asid = B(0x0018, 14 bits)
    val Pgdl = B(0x0019, 14 bits)
    val Pgdh = B(0x001a, 14 bits)
    val Pgd = B(0x001b, 14 bits)
    val Cpuid = B(0x0020, 14 bits)
    val Save0 = B(0x0030, 14 bits)
    val Save1 = B(0x0031, 14 bits)
    val Save2 = B(0x0032, 14 bits)
    val Save3 = B(0x0033, 14 bits)
    val Tid = B(0x0040, 14 bits)
    val Tcfg = B(0x0041, 14 bits)
    val Tval = B(0x0042, 14 bits)
    val Cntc = B(0x0043, 14 bits)
    val Ticlr = B(0x0044, 14 bits)
    val Llbctl = B(0x0060, 14 bits)
    val Tlbrentry = B(0x0088, 14 bits)
    val Brk = B(0x0100, 14 bits)
    val DisableCache = B(0x0101, 14 bits)
    val Dmw0 = B(0x0180, 14 bits)
    val Dmw1 = B(0x0181, 14 bits)
    val Cpucfg1 = B(0x00b1, 14 bits)
    val Cpucfg2 = B(0x00b2, 14 bits)
    val Cpucfg10 = B(0x00c0, 14 bits)
    val Cpucfg11 = B(0x00c1, 14 bits)
    val Cpucfg12 = B(0x00c2, 14 bits)
    val Cpucfg13 = B(0x00c3, 14 bits)
  }

  val csrClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)
  )

  val logic = new ClockingArea(csrClockDomain) {
    val crmd = Reg(Bits(32 bits))
    val prmd = Reg(Bits(32 bits))
    val ectl = Reg(Bits(32 bits))
    val estat = Reg(Bits(32 bits))
    val era = Reg(Bits(32 bits))
    val badv = Reg(Bits(32 bits))
    val eentry = Reg(Bits(32 bits))
    val tlbidx = Reg(Bits(32 bits))
    val tlbehi = Reg(Bits(32 bits))
    val tlbelo0 = Reg(Bits(32 bits))
    val tlbelo1 = Reg(Bits(32 bits))
    val asid = Reg(Bits(32 bits))
    val cpuid = Reg(Bits(32 bits))
    val save0 = Reg(Bits(32 bits))
    val save1 = Reg(Bits(32 bits))
    val save2 = Reg(Bits(32 bits))
    val save3 = Reg(Bits(32 bits))
    val tid = Reg(Bits(32 bits))
    val tcfg = Reg(Bits(32 bits))
    val tval = Reg(Bits(32 bits))
    val cntc = Reg(Bits(32 bits))
    val ticlr = Reg(Bits(32 bits))
    val llbctl = Reg(Bits(32 bits))
    val tlbrentry = Reg(Bits(32 bits))
    val dmw0 = Reg(Bits(32 bits))
    val dmw1 = Reg(Bits(32 bits))
    val pgdl = Reg(Bits(32 bits))
    val pgdh = Reg(Bits(32 bits))
    val brk = Reg(Bits(32 bits))
    val disableCache = Reg(Bits(32 bits))
    val timerEnabled = Reg(Bool())
    val timer64 = Reg(UInt(64 bits))
    val llbit = Reg(Bool())
    val lladdr = Reg(Bits(config.reservationAddressWidth bits))

    // These reserved bits are physical legacy flops that are never written. Explicit self-hold
    // preserves that behavior without inventing reset values and documents that they are not gaps.
    tlbelo0(31 downto 28) := tlbelo0(31 downto 28)
    tlbelo1(31 downto 28) := tlbelo1(31 downto 28)
    llbctl(0) := llbctl(0)
    pgdl(11 downto 0) := pgdl(11 downto 0)
    pgdh(11 downto 0) := pgdh(11 downto 0)

    def write(address: Bits): Bool = io.csr_wr_en && io.wr_addr === address

    val crmdWrite = write(Address.Crmd)
    val tlbehiWrite = write(Address.Tlbehi)
    val tcfgWrite = write(Address.Tcfg)
    val ticlrWrite = write(Address.Ticlr)
    val llbctlWrite = write(Address.Llbctl)
    val tlbrdValid = io.tlbrd_en && !io.tlbidx_in(31)
    val tlbrdInvalid = io.tlbrd_en && io.tlbidx_in(31)
    val returningFromRefill = estat(21 downto 16) === B(0x3f, 6 bits)

    when(io.reset) {
      crmd := B(0x00000008L, 32 bits)
    }.elsewhen(io.excp_flush) {
      crmd(1 downto 0) := 0
      crmd(2) := False
      when(io.excp_tlbrefill) {
        crmd(3) := True
        crmd(4) := False
      }
    }.elsewhen(io.ertn_flush) {
      crmd(1 downto 0) := prmd(1 downto 0)
      crmd(2) := prmd(2)
      when(returningFromRefill) {
        crmd(3) := False
        crmd(4) := True
      }
    }.elsewhen(crmdWrite) {
      crmd(8 downto 0) := io.wr_data(8 downto 0)
    }

    when(io.reset) {
      prmd(31 downto 3) := 0
    }.elsewhen(io.excp_flush) {
      prmd(1 downto 0) := crmd(1 downto 0)
      prmd(2) := crmd(2)
    }.elsewhen(write(Address.Prmd)) {
      prmd(2 downto 0) := io.wr_data(2 downto 0)
    }

    when(io.reset) {
      ectl := 0
    }.elsewhen(write(Address.Ectl)) {
      ectl(9 downto 0) := io.wr_data(9 downto 0)
      ectl(12 downto 11) := io.wr_data(12 downto 11)
    }

    when(io.reset) {
      estat := 0
      timerEnabled := False
    }.otherwise {
      when(ticlrWrite && io.wr_data(0)) {
        estat(11) := False
      }.elsewhen(tcfgWrite) {
        timerEnabled := io.wr_data(0)
      }.elsewhen(timerEnabled && tval === B(0, 32 bits)) {
        estat(11) := True
        timerEnabled := tcfg(1)
      }
      estat(9 downto 2) := io.interrupt
      when(io.excp_flush) {
        estat(21 downto 16) := io.ecode_in
        estat(30 downto 22) := io.esubcode_in
      }.elsewhen(write(Address.Estat)) {
        estat(1 downto 0) := io.wr_data(1 downto 0)
      }
    }

    when(io.excp_flush) { era := io.era_in }.elsewhen(write(Address.Era)) { era := io.wr_data }
    when(write(Address.Badv)) { badv := io.wr_data }.elsewhen(io.va_error_in) {
      badv := io.bad_va_in
    }

    when(io.reset) { eentry(5 downto 0) := 0 }.elsewhen(write(Address.Eentry)) {
      eentry(31 downto 6) := io.wr_data(31 downto 6)
    }

    when(io.reset) {
      tlbidx(4 downto 0) := 0
      tlbidx(23 downto 5) := 0
      tlbidx(30) := False
    }.elsewhen(write(Address.Tlbidx)) {
      tlbidx(4 downto 0) := io.wr_data(4 downto 0)
      tlbidx(29 downto 24) := io.wr_data(29 downto 24)
      tlbidx(31) := io.wr_data(31)
    }.elsewhen(io.tlbsrch_en) {
      when(io.tlbsrch_found) {
        tlbidx(4 downto 0) := io.tlbsrch_index
        tlbidx(31) := False
      }.otherwise {
        tlbidx(31) := True
      }
    }.elsewhen(tlbrdValid) {
      tlbidx(29 downto 24) := io.tlbidx_in(29 downto 24)
      tlbidx(31) := io.tlbidx_in(31)
    }.elsewhen(tlbrdInvalid) {
      tlbidx(29 downto 24) := 0
      tlbidx(31) := io.tlbidx_in(31)
    }

    when(io.reset) { tlbehi(12 downto 0) := 0 }
      .elsewhen(tlbehiWrite) {
        tlbehi(31 downto 13) := io.wr_data(31 downto 13)
      }
      .elsewhen(tlbrdValid) {
        tlbehi(31 downto 13) := io.tlbehi_in(31 downto 13)
      }
      .elsewhen(tlbrdInvalid) {
        tlbehi(31 downto 13) := 0
      }
      .elsewhen(io.excp_tlb) {
        tlbehi(31 downto 13) := io.excp_tlb_vppn
      }

    def updateTlbelo(register: Bits, address: Bits, source: Bits): Unit = {
      when(io.reset) {
        register(7) := False
      }.elsewhen(write(address)) {
        register(6 downto 0) := io.wr_data(6 downto 0)
        register(27 downto 8) := io.wr_data(27 downto 8)
      }.elsewhen(tlbrdValid) {
        register(6 downto 0) := source(6 downto 0)
        register(27 downto 8) := source(27 downto 8)
      }.elsewhen(tlbrdInvalid) {
        register(6 downto 0) := 0
        register(27 downto 8) := 0
      }
    }
    updateTlbelo(tlbelo0, Address.Tlbelo0, io.tlbelo0_in)
    updateTlbelo(tlbelo1, Address.Tlbelo1, io.tlbelo1_in)

    when(io.reset) { asid(31 downto 10) := B(0x280, 22 bits) }
      .elsewhen(write(Address.Asid)) {
        asid(9 downto 0) := io.wr_data(9 downto 0)
      }
      .elsewhen(tlbrdValid) {
        asid(9 downto 0) := io.asid_in
      }
      .elsewhen(tlbrdInvalid) {
        asid(9 downto 0) := 0
      }

    when(io.reset) { tlbrentry(5 downto 0) := 0 }.elsewhen(write(Address.Tlbrentry)) {
      tlbrentry(31 downto 6) := io.wr_data(31 downto 6)
    }

    def updateDmw(register: Bits, address: Bits): Unit = {
      when(io.reset) {
        register(2 downto 1) := 0
        register(24 downto 6) := 0
        register(28) := False
      }.elsewhen(write(address)) {
        register(0) := io.wr_data(0)
        register(3) := io.wr_data(3)
        register(5 downto 4) := io.wr_data(5 downto 4)
        register(27 downto 25) := io.wr_data(27 downto 25)
        register(31 downto 29) := io.wr_data(31 downto 29)
      }
    }
    updateDmw(dmw0, Address.Dmw0)
    updateDmw(dmw1, Address.Dmw1)

    when(io.reset) { cpuid := 0 }
    when(write(Address.Save0)) { save0 := io.wr_data }
    when(write(Address.Save1)) { save1 := io.wr_data }
    when(write(Address.Save2)) { save2 := io.wr_data }
    when(write(Address.Save3)) { save3 := io.wr_data }
    when(io.reset) { tid := 0 }.elsewhen(write(Address.Tid)) { tid := io.wr_data }

    when(io.reset) { tcfg := 0 }.elsewhen(tcfgWrite) {
      tcfg := io.wr_data
    }
    when(io.reset) { cntc := 0 }.elsewhen(write(Address.Cntc)) { cntc := io.wr_data }
    when(io.reset) {
      tval := 0
    }.elsewhen(tcfgWrite) {
      tval := io.wr_data(31 downto 2) ## B(0, 2 bits)
    }.elsewhen(timerEnabled) {
      when(tval =/= B(0, 32 bits)) {
        tval := (tval.asUInt - 1).asBits
      }.otherwise {
        tval := Mux(tcfg(1), tcfg(31 downto 2) ## B(0, 2 bits), B(0xffffffffL, 32 bits))
      }
    }
    when(io.reset) { ticlr := 0 }

    when(io.reset) {
      llbctl(31 downto 3) := 0
      llbctl(2) := False
      llbctl(1) := False
      llbit := False
    }.elsewhen(io.ertn_flush) {
      when(llbctl(2)) { llbctl(2) := False }.otherwise { llbit := False }
    }.elsewhen(llbctlWrite) {
      llbctl(2) := io.wr_data(2)
      when(io.wr_data(1)) { llbit := False }
    }.elsewhen(io.llbit_set_in) {
      llbit := io.llbit_in
    }
    when(io.reset) { lladdr := 0 }.elsewhen(io.lladdr_set_in) { lladdr := io.lladdr_in }

    when(io.reset) { timer64 := 0 }.otherwise { timer64 := timer64 + 1 }
    when(write(Address.Pgdl)) { pgdl(31 downto 12) := io.wr_data(31 downto 12) }
    when(write(Address.Pgdh)) { pgdh(31 downto 12) := io.wr_data(31 downto 12) }

    when(io.reset) { brk := 0 }
    when(write(Address.Brk)) { brk := io.wr_data }
    when(io.reset) { disableCache := 0 }
    when(write(Address.DisableCache)) { disableCache := io.wr_data }
  }

  import Address._
  io.rd_data := 0
  switch(io.rd_addr) {
    is(Crmd) { io.rd_data := logic.crmd }
    is(Prmd) { io.rd_data := logic.prmd }
    is(Ectl) { io.rd_data := logic.ectl }
    is(Estat) { io.rd_data := logic.estat }
    is(Era) { io.rd_data := logic.era }
    is(Badv) { io.rd_data := logic.badv }
    is(Eentry) { io.rd_data := logic.eentry }
    is(Tlbidx) { io.rd_data := logic.tlbidx }
    is(Tlbehi) { io.rd_data := logic.tlbehi }
    is(Tlbelo0) { io.rd_data := logic.tlbelo0 }
    is(Tlbelo1) { io.rd_data := logic.tlbelo1 }
    is(Asid) { io.rd_data := logic.asid }
    is(Pgdl) { io.rd_data := logic.pgdl }
    is(Pgdh) { io.rd_data := logic.pgdh }
    is(Pgd) { io.rd_data := Mux(logic.badv(31), logic.pgdh, logic.pgdl) }
    is(Cpuid) { io.rd_data := logic.cpuid }
    is(Save0) { io.rd_data := logic.save0 }
    is(Save1) { io.rd_data := logic.save1 }
    is(Save2) { io.rd_data := logic.save2 }
    is(Save3) { io.rd_data := logic.save3 }
    is(Tid) { io.rd_data := logic.tid }
    is(Tcfg) { io.rd_data := logic.tcfg }
    is(Tval) { io.rd_data := logic.tval }
    is(Cntc) { io.rd_data := logic.cntc }
    is(Ticlr) { io.rd_data := logic.ticlr }
    is(Llbctl) { io.rd_data := logic.llbctl(31 downto 1) ## logic.llbit }
    is(Tlbrentry) { io.rd_data := logic.tlbrentry }
    is(Dmw0) { io.rd_data := logic.dmw0 }
    is(Dmw1) { io.rd_data := logic.dmw1 }
    is(Cpucfg1) { io.rd_data := B(CpuConfigEncoding.value(config, 1), 32 bits) }
    is(Cpucfg2) { io.rd_data := B(CpuConfigEncoding.value(config, 2), 32 bits) }
    is(Cpucfg10) { io.rd_data := B(CpuConfigEncoding.value(config, 16), 32 bits) }
    is(Cpucfg11) { io.rd_data := B(CpuConfigEncoding.value(config, 17), 32 bits) }
    is(Cpucfg12) { io.rd_data := B(CpuConfigEncoding.value(config, 18), 32 bits) }
    is(Cpucfg13) { io.rd_data := B(CpuConfigEncoding.value(config, 19), 32 bits) }
  }

  io.has_int := ((logic.ectl(12 downto 0) & logic.estat(12 downto 0)).orR) && logic.crmd(2)
  io.eentry_out := logic.eentry
  io.era_out := logic.era
  io.timer_64_out := (logic.timer64 + logic.cntc.asSInt.resize(64).asUInt).asBits
  io.tid_out := logic.tid
  io.llbit_out := logic.llbit
  io.lladdr_out := logic.lladdr
  io.asid_out := logic.asid(9 downto 0)
  io.vppn_out := Mux(logic.tlbehiWrite, io.wr_data(31 downto 13), logic.tlbehi(31 downto 13))
  io.tlbehi_out := logic.tlbehi
  io.tlbelo0_out := logic.tlbelo0
  io.tlbelo1_out := logic.tlbelo1
  io.tlbidx_out := logic.tlbidx
  io.rand_index := logic.timer64(4 downto 0).asBits
  io.disable_cache_out := logic.disableCache(0)

  val refillReturn = logic.returningFromRefill && io.ertn_flush
  val noForward = !io.excp_tlbrefill && !refillReturn && !logic.crmdWrite
  io.pg_out :=
    refillReturn || (logic.crmdWrite && io.wr_data(4)) || (noForward && logic.crmd(4))
  io.da_out :=
    io.excp_tlbrefill || (logic.crmdWrite && io.wr_data(3)) || (noForward && logic.crmd(3))
  io.dmw0_out := Mux(logic.write(Dmw0), io.wr_data, logic.dmw0)
  io.dmw1_out := Mux(logic.write(Dmw1), io.wr_data, logic.dmw1)
  io.plv_out :=
    (B(2 bits, default -> io.ertn_flush) & logic.prmd(1 downto 0)) |
      (B(2 bits, default -> logic.crmdWrite) & io.wr_data(1 downto 0)) |
      (B(2 bits, default -> (!io.excp_flush && !io.ertn_flush && !logic.crmdWrite)) & logic
        .crmd(1 downto 0))
  io.tlbrentry_out := logic.tlbrentry
  io.datf_out := logic.crmd(6 downto 5)
  io.datm_out := logic.crmd(8 downto 7)
  io.ecode_out := logic.estat(21 downto 16)

  if (diffTestEnabled) {
    io.csr_crmd_diff := logic.crmd
    io.csr_prmd_diff := logic.prmd
    io.csr_ectl_diff := logic.ectl
    io.csr_estat_diff := logic.estat
    io.csr_era_diff := logic.era
    io.csr_badv_diff := logic.badv
    io.csr_eentry_diff := logic.eentry
    io.csr_tlbidx_diff := logic.tlbidx
    io.csr_tlbehi_diff := logic.tlbehi
    io.csr_tlbelo0_diff := logic.tlbelo0
    io.csr_tlbelo1_diff := logic.tlbelo1
    io.csr_asid_diff := logic.asid
    io.csr_save0_diff := logic.save0
    io.csr_save1_diff := logic.save1
    io.csr_save2_diff := logic.save2
    io.csr_save3_diff := logic.save3
    io.csr_tid_diff := logic.tid
    io.csr_tcfg_diff := logic.tcfg
    io.csr_tval_diff := logic.tval
    io.csr_ticlr_diff := logic.ticlr
    io.csr_llbctl_diff := logic.llbctl(31 downto 1) ## logic.llbit
    io.csr_tlbrentry_diff := logic.tlbrentry
    io.csr_dmw0_diff := logic.dmw0
    io.csr_dmw1_diff := logic.dmw1
    io.csr_pgdl_diff := logic.pgdl
    io.csr_pgdh_diff := logic.pgdh
  }
}
