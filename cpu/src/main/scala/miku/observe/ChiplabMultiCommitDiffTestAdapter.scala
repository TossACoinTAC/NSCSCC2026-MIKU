package miku.observe

import spinal.core._
import spinal.lib._

/** Multi-retirement boundary for chiplab's indexed DPI commit arrays.
  *
  * Commit records pass through a fixed three-cycle observation pipeline. GPR and ordinary CSR state
  * is sampled after the first cycle; CSR/TLB/ERTN operations whose architectural effects are staged
  * for an extra cycle use the live CSR view at the third observation edge. Only one copy of the
  * global exception, CSR, and GPR callbacks is emitted; instruction/load/store callbacks retain
  * their retirement-lane index.
  */
final class ChiplabMultiCommitDiffTestAdapter(commitWidth: Int) extends Component {
  require(commitWidth > 0 && commitWidth <= 6, "chiplab exposes six indexed commit slots")

  val io = new Bundle {
    val clock = in Bool ()
    val commitValid = in Bits (commitWidth bits)
    val commit = in Vec (CommitEvent(), commitWidth)
    val stateDelayed = in Bits (commitWidth bits)
    val archState = in(ArchState())
  }

  val registeredValid = RegNext(io.commitValid) init (B(0, commitWidth bits))
  val registeredStateDelayed = RegNext(io.stateDelayed) init (B(0, commitWidth bits))
  val registeredCommit = Vec.fill(commitWidth)(Reg(CommitEvent()))
  for (lane <- 0 until commitWidth) {
    registeredCommit(lane) := io.commit(lane)
  }

  // Keep every commit batch in the same fixed-latency stream. Selectively delaying only a
  // serializing event can collide with and overwrite the first batch after its refetch.
  val delayedValid = RegNext(registeredValid) init (B(0, commitWidth bits))
  val delayedStateDelayed = RegNext(registeredStateDelayed) init (B(0, commitWidth bits))
  val delayedCommit = Vec.fill(commitWidth)(Reg(CommitEvent()))
  for (lane <- 0 until commitWidth) {
    delayedCommit(lane) := registeredCommit(lane)
  }
  val observedValid = RegNext(delayedValid) init (B(0, commitWidth bits))
  val observedStateDelayed = RegNext(delayedStateDelayed) init (B(0, commitWidth bits))
  val observedCommit = Vec.fill(commitWidth)(Reg(CommitEvent()))
  for (lane <- 0 until commitWidth) {
    observedCommit(lane) := delayedCommit(lane)
  }
  val registeredArchState = Reg(ArchState())
  registeredArchState := io.archState
  val sampledArchState = Reg(ArchState())
  sampledArchState := registeredArchState

  val visibleValid = observedValid
  val visibleCommit = observedCommit
  val useLiveCsrState = (visibleValid & observedStateDelayed).orR

  val rawRetired = Bits(commitWidth bits)
  for (lane <- 0 until commitWidth) {
    rawRetired(lane) := io.commitValid(lane) && io.commit(lane).retired
  }
  val cycleCount = Reg(UInt(64 bits)) init (0)
  val instructionCount = Reg(UInt(64 bits)) init (0)
  cycleCount := cycleCount + 1
  instructionCount := instructionCount + CountOne(rawRetired).resize(64)

  val globalEvent = CommitEvent()
  val globalEventValid = Bool()
  globalEvent := visibleCommit(0)
  globalEventValid := False
  for (lane <- (0 until commitWidth).reverse) {
    when(
      visibleValid(lane) &&
        (visibleCommit(lane).exception.valid || visibleCommit(lane).ertn)
    ) {
      globalEvent := visibleCommit(lane)
      globalEventValid := True
    }
  }

  private def zeroExtend32(value: Bits): Bits = B(0, 32 bits) ## value

  private val wrapper = new ChiplabMultiCommitDiffTestBlackBox(commitWidth)
  wrapper.io.clock := io.clock
  for (lane <- 0 until commitWidth) {
    val commit = visibleCommit(lane)
    wrapper.io.instrValid(lane) := visibleValid(lane) && commit.retired
    wrapper.io.pc(lane * 64 + 63 downto lane * 64) := zeroExtend32(commit.pc.asBits)
    wrapper.io.instruction(lane * 32 + 31 downto lane * 32) := commit.instruction
    wrapper.io.isTlbFill(lane) := visibleValid(lane) && commit.tlbFill.valid
    wrapper.io.tlbFillIndex(lane * 5 + 4 downto lane * 5) := commit.tlbFill.index.asBits
    wrapper.io.isCounterInstruction(lane) := commit.isCounterInstruction
    wrapper.io.timer(lane * 64 + 63 downto lane * 64) := commit.timer.asBits
    wrapper.io.gprWriteValid(lane) := visibleValid(lane) && commit.gprWrite.valid
    wrapper.io.gprWriteIndex(lane * 8 + 7 downto lane * 8) :=
      B(0, 3 bits) ## commit.gprWrite.index.asBits
    wrapper.io.gprWriteData(lane * 64 + 63 downto lane * 64) :=
      zeroExtend32(commit.gprWrite.data)
    wrapper.io.csrRstat(lane) := commit.csrRstat
    wrapper.io.csrReadData(lane * 32 + 31 downto lane * 32) := commit.csrReadData
    wrapper.io.storeValid(lane * 8 + 7 downto lane * 8) := Mux(
      visibleValid(lane),
      commit.store.instructionMask,
      B(0, 8 bits)
    )
    wrapper.io.storePhysicalAddress(lane * 64 + 63 downto lane * 64) :=
      zeroExtend32(commit.store.pAddr.asBits)
    wrapper.io.storeVirtualAddress(lane * 64 + 63 downto lane * 64) :=
      zeroExtend32(commit.store.vAddr.asBits)
    wrapper.io.storeData(lane * 64 + 63 downto lane * 64) := zeroExtend32(commit.store.data)
    wrapper.io.loadValid(lane * 8 + 7 downto lane * 8) := Mux(
      visibleValid(lane),
      commit.load.instructionMask,
      B(0, 8 bits)
    )
    wrapper.io.loadPhysicalAddress(lane * 64 + 63 downto lane * 64) :=
      zeroExtend32(commit.load.pAddr.asBits)
    wrapper.io.loadVirtualAddress(lane * 64 + 63 downto lane * 64) :=
      zeroExtend32(commit.load.vAddr.asBits)
  }

  wrapper.io.exceptionValid := globalEventValid && globalEvent.exception.valid
  wrapper.io.ertn := globalEventValid && globalEvent.ertn
  wrapper.io.interruptNumber := B(0, 21 bits) ## sampledArchState.estat(12 downto 2)
  wrapper.io.exceptionCause := B(0, 26 bits) ## globalEvent.exception.ecode.asBits
  wrapper.io.exceptionPc := zeroExtend32(globalEvent.pc.asBits)
  wrapper.io.exceptionInstruction := globalEvent.instruction
  wrapper.io.trapValid := False
  wrapper.io.trapCode := sampledArchState.gpr(10)(2 downto 0)
  wrapper.io.trapPc := zeroExtend32(globalEvent.pc.asBits)
  wrapper.io.cycleCount := cycleCount.asBits
  wrapper.io.instructionCount := instructionCount.asBits

  private val csrWords = Seq(
    (io.archState.crmd, sampledArchState.crmd),
    (io.archState.prmd, sampledArchState.prmd),
    (io.archState.euen, sampledArchState.euen),
    (io.archState.ecfg, sampledArchState.ecfg),
    (io.archState.estat, sampledArchState.estat),
    (io.archState.era, sampledArchState.era),
    (io.archState.badv, sampledArchState.badv),
    (io.archState.eentry, sampledArchState.eentry),
    (io.archState.tlbidx, sampledArchState.tlbidx),
    (io.archState.tlbehi, sampledArchState.tlbehi),
    (io.archState.tlbelo0, sampledArchState.tlbelo0),
    (io.archState.tlbelo1, sampledArchState.tlbelo1),
    (io.archState.asid, sampledArchState.asid),
    (io.archState.pgdl, sampledArchState.pgdl),
    (io.archState.pgdh, sampledArchState.pgdh),
    (io.archState.save0, sampledArchState.save0),
    (io.archState.save1, sampledArchState.save1),
    (io.archState.save2, sampledArchState.save2),
    (io.archState.save3, sampledArchState.save3),
    (io.archState.tid, sampledArchState.tid),
    (io.archState.tcfg, sampledArchState.tcfg),
    (io.archState.tval, sampledArchState.tval),
    (io.archState.ticlr, sampledArchState.ticlr),
    (io.archState.llbctl, sampledArchState.llbctl),
    (io.archState.tlbrentry, sampledArchState.tlbrentry),
    (io.archState.dmw0, sampledArchState.dmw0),
    (io.archState.dmw1, sampledArchState.dmw1)
  )
  wrapper.io.csrState := csrWords
    .map { case (live, sampled) => zeroExtend32(Mux(useLiveCsrState, live, sampled)) }
    .reverse
    .reduce(_ ## _)
  wrapper.io.gprState := sampledArchState.gpr.map(zeroExtend32).reverse.reduce(_ ## _)
}

/** Conditional Verilog shell around the simulator-owned indexed commit and global state modules. */
private final class ChiplabMultiCommitDiffTestBlackBox(commitWidth: Int) extends BlackBox {
  private val moduleName = s"ChiplabMultiCommitDiffTestBlackBox_$commitWidth"
  setDefinitionName(moduleName)

  val io = new Bundle {
    val clock = in Bool ()
    val instrValid = in Bits (commitWidth bits)
    val pc = in Bits (commitWidth * 64 bits)
    val instruction = in Bits (commitWidth * 32 bits)
    val isTlbFill = in Bits (commitWidth bits)
    val tlbFillIndex = in Bits (commitWidth * 5 bits)
    val isCounterInstruction = in Bits (commitWidth bits)
    val timer = in Bits (commitWidth * 64 bits)
    val gprWriteValid = in Bits (commitWidth bits)
    val gprWriteIndex = in Bits (commitWidth * 8 bits)
    val gprWriteData = in Bits (commitWidth * 64 bits)
    val csrRstat = in Bits (commitWidth bits)
    val csrReadData = in Bits (commitWidth * 32 bits)
    val storeValid = in Bits (commitWidth * 8 bits)
    val storePhysicalAddress = in Bits (commitWidth * 64 bits)
    val storeVirtualAddress = in Bits (commitWidth * 64 bits)
    val storeData = in Bits (commitWidth * 64 bits)
    val loadValid = in Bits (commitWidth * 8 bits)
    val loadPhysicalAddress = in Bits (commitWidth * 64 bits)
    val loadVirtualAddress = in Bits (commitWidth * 64 bits)
    val exceptionValid = in Bool ()
    val ertn = in Bool ()
    val interruptNumber = in Bits (32 bits)
    val exceptionCause = in Bits (32 bits)
    val exceptionPc = in Bits (64 bits)
    val exceptionInstruction = in Bits (32 bits)
    val trapValid = in Bool ()
    val trapCode = in Bits (3 bits)
    val trapPc = in Bits (64 bits)
    val cycleCount = in Bits (64 bits)
    val instructionCount = in Bits (64 bits)
    val csrState = in Bits (27 * 64 bits)
    val gprState = in Bits (32 * 64 bits)
  }
  noIoPrefix()

  private def slice(signal: String, lane: Int, width: Int): String = {
    val low = lane * width
    val high = low + width - 1
    s"$signal[$high:$low]"
  }

  private val laneInstances = (0 until commitWidth)
    .map { lane =>
      s"""
  DifftestInstrCommit u_difftest_instr_commit_$lane (
    .clock(clock), .coreid(8'b0), .index(8'd$lane), .valid(instrValid[$lane]),
    .pc(${slice("pc", lane, 64)}), .instr(${slice("instruction", lane, 32)}),
    .skip(1'b0), .is_TLBFILL(isTlbFill[$lane]),
    .TLBFILL_index(${slice("tlbFillIndex", lane, 5)}),
    .is_CNTinst(isCounterInstruction[$lane]), .timer_64_value(${slice("timer", lane, 64)}),
    .wen(gprWriteValid[$lane]), .wdest(${slice("gprWriteIndex", lane, 8)}),
    .wdata(${slice("gprWriteData", lane, 64)}), .csr_rstat(csrRstat[$lane]),
    .csr_data(${slice("csrReadData", lane, 32)})
  );

  DifftestStoreEvent u_difftest_store_$lane (
    .clock(clock), .coreid(8'b0), .index(8'd$lane),
    .valid(${slice("storeValid", lane, 8)}),
    .storePAddr(${slice("storePhysicalAddress", lane, 64)}),
    .storeVAddr(${slice("storeVirtualAddress", lane, 64)}),
    .storeData(${slice("storeData", lane, 64)})
  );

  DifftestLoadEvent u_difftest_load_$lane (
    .clock(clock), .coreid(8'b0), .index(8'd$lane),
    .valid(${slice("loadValid", lane, 8)}),
    .paddr(${slice("loadPhysicalAddress", lane, 64)}),
    .vaddr(${slice("loadVirtualAddress", lane, 64)})
  );"""
    }
    .mkString("\n")

  private val csrNames = Seq(
    "crmd",
    "prmd",
    "euen",
    "ecfg",
    "estat",
    "era",
    "badv",
    "eentry",
    "tlbidx",
    "tlbehi",
    "tlbelo0",
    "tlbelo1",
    "asid",
    "pgdl",
    "pgdh",
    "save0",
    "save1",
    "save2",
    "save3",
    "tid",
    "tcfg",
    "tval",
    "ticlr",
    "llbctl",
    "tlbrentry",
    "dmw0",
    "dmw1"
  )
  private val csrConnections = csrNames.zipWithIndex
    .map { case (name, index) =>
      val high = index * 64 + 63
      val low = index * 64
      val source = if (name == "euen") s"64'b0 & csrState[$high:$low]" else s"csrState[$high:$low]"
      s"    .$name($source)"
    }
    .mkString(",\n")
  private val gprConnections = (0 until 32)
    .map { index =>
      val source =
        if (index == 0) "64'b0 & gprState[63:0]"
        else s"gprState[${index * 64 + 63}:${index * 64}]"
      s"    .gpr_$index($source)"
    }
    .mkString(",\n")

  setInlineVerilog(s"""
`ifndef DIFFTEST_EN
/* verilator lint_off UNUSEDSIGNAL */
`endif
module $moduleName (
    input wire clock,
    input wire [${commitWidth - 1}:0] instrValid,
    input wire [${commitWidth * 64 - 1}:0] pc,
    input wire [${commitWidth * 32 - 1}:0] instruction,
    input wire [${commitWidth - 1}:0] isTlbFill,
    input wire [${commitWidth * 5 - 1}:0] tlbFillIndex,
    input wire [${commitWidth - 1}:0] isCounterInstruction,
    input wire [${commitWidth * 64 - 1}:0] timer,
    input wire [${commitWidth - 1}:0] gprWriteValid,
    input wire [${commitWidth * 8 - 1}:0] gprWriteIndex,
    input wire [${commitWidth * 64 - 1}:0] gprWriteData,
    input wire [${commitWidth - 1}:0] csrRstat,
    input wire [${commitWidth * 32 - 1}:0] csrReadData,
    input wire [${commitWidth * 8 - 1}:0] storeValid,
    input wire [${commitWidth * 64 - 1}:0] storePhysicalAddress,
    input wire [${commitWidth * 64 - 1}:0] storeVirtualAddress,
    input wire [${commitWidth * 64 - 1}:0] storeData,
    input wire [${commitWidth * 8 - 1}:0] loadValid,
    input wire [${commitWidth * 64 - 1}:0] loadPhysicalAddress,
    input wire [${commitWidth * 64 - 1}:0] loadVirtualAddress,
    input wire exceptionValid,
    input wire ertn,
    input wire [31:0] interruptNumber,
    input wire [31:0] exceptionCause,
    input wire [63:0] exceptionPc,
    input wire [31:0] exceptionInstruction,
    input wire trapValid,
    input wire [2:0] trapCode,
    input wire [63:0] trapPc,
    input wire [63:0] cycleCount,
    input wire [63:0] instructionCount,
    input wire [1727:0] csrState,
    input wire [2047:0] gprState
);
`ifdef DIFFTEST_EN
$laneInstances

  DifftestExcpEvent u_difftest_exception (
    .clock(clock), .coreid(8'b0), .excp_valid(exceptionValid), .eret(ertn),
    .intrNo(interruptNumber), .cause(exceptionCause), .exceptionPC(exceptionPc),
    .exceptionInst(exceptionInstruction)
  );

  DifftestTrapEvent u_difftest_trap (
    .clock(clock), .coreid(8'b0), .valid(trapValid), .code(trapCode), .pc(trapPc),
    .cycleCnt(cycleCount), .instrCnt(instructionCount)
  );

  DifftestCSRRegState u_difftest_csr_state (
    .clock(clock), .coreid(8'b0),
$csrConnections
  );

  DifftestGRegState u_difftest_gpr_state (
    .clock(clock), .coreid(8'b0),
$gprConnections
  );
`endif
endmodule
`ifndef DIFFTEST_EN
/* verilator lint_on UNUSEDSIGNAL */
`endif""")
}
