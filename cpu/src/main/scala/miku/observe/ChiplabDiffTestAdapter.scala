package miku.observe

import spinal.core._
import spinal.lib._

/** Typed boundary from the architectural commit/state contracts to chiplab's DPI modules.
  *
  * Commit metadata is sampled for one cycle before it reaches the DPI wrappers. Architectural state
  * remains live: at the following edge the official DPI modules must observe the GPR/CSR updates
  * made by that commit, matching the golden nonblocking-assignment schedule. The wrapper only
  * references simulator-provided Difftest modules when `DIFFTEST_EN` is defined, so normal
  * synthesis never retains unresolved DPI blackboxes.
  */
final class ChiplabDiffTestAdapter extends Component {
  val io = new Bundle {
    val clock = in Bool ()
    val commit = slave(Flow(CommitEvent()))
    val archState = in(ArchState())
  }

  val registeredValid = RegNext(io.commit.valid) init (False)
  val registeredCommit = Reg(CommitEvent())
  registeredCommit := io.commit.payload

  val rawRetired = io.commit.valid && io.commit.payload.retired
  val cycleCount = Reg(UInt(64 bits)) init (0)
  val instructionCount = Reg(UInt(64 bits)) init (0)
  cycleCount := cycleCount + 1
  when(rawRetired) {
    instructionCount := instructionCount + 1
  }

  private def zeroExtend32(value: Bits): Bits = B(0, 32 bits) ## value

  private val wrapper = new ChiplabDiffTestBlackBox
  wrapper.io.clock := io.clock
  wrapper.io.commitContract := registeredCommit.asBits
  wrapper.io.instrValid := registeredValid && registeredCommit.retired
  wrapper.io.pc := zeroExtend32(registeredCommit.pc.asBits)
  wrapper.io.instruction := registeredCommit.instruction
  wrapper.io.isTlbFill := registeredValid && registeredCommit.tlbFill.valid
  wrapper.io.tlbFillIndex := registeredCommit.tlbFill.index.asBits
  wrapper.io.isCounterInstruction := registeredCommit.isCounterInstruction
  wrapper.io.timer := registeredCommit.timer.asBits
  wrapper.io.gprWriteValid := registeredValid && registeredCommit.gprWrite.valid
  wrapper.io.gprWriteIndex := B(0, 3 bits) ## registeredCommit.gprWrite.index.asBits
  wrapper.io.gprWriteData := zeroExtend32(registeredCommit.gprWrite.data)
  wrapper.io.csrRstat := registeredCommit.csrRstat
  wrapper.io.csrReadData := registeredCommit.csrReadData

  wrapper.io.exceptionValid := registeredValid && registeredCommit.exception.valid
  wrapper.io.ertn := registeredValid && registeredCommit.ertn
  wrapper.io.interruptNumber := B(0, 21 bits) ## io.archState.estat(12 downto 2)
  wrapper.io.exceptionCause := B(0, 26 bits) ## registeredCommit.exception.ecode.asBits
  wrapper.io.exceptionPc := zeroExtend32(registeredCommit.pc.asBits)
  wrapper.io.exceptionInstruction := registeredCommit.instruction

  wrapper.io.trapValid := False
  wrapper.io.trapCode := io.archState.gpr(10)(2 downto 0)
  wrapper.io.cycleCount := cycleCount.asBits
  wrapper.io.instructionCount := instructionCount.asBits

  wrapper.io.storeValid := Mux(
    registeredValid,
    registeredCommit.store.instructionMask,
    B(0, 8 bits)
  )
  wrapper.io.storePhysicalAddress := zeroExtend32(registeredCommit.store.pAddr.asBits)
  wrapper.io.storeVirtualAddress := zeroExtend32(registeredCommit.store.vAddr.asBits)
  wrapper.io.storeData := zeroExtend32(registeredCommit.store.data)
  wrapper.io.loadValid := Mux(
    registeredValid,
    registeredCommit.load.instructionMask,
    B(0, 8 bits)
  )
  wrapper.io.loadPhysicalAddress := zeroExtend32(registeredCommit.load.pAddr.asBits)
  wrapper.io.loadVirtualAddress := zeroExtend32(registeredCommit.load.vAddr.asBits)

  private val csrWords = Seq(
    io.archState.crmd,
    io.archState.prmd,
    io.archState.euen,
    io.archState.ecfg,
    io.archState.estat,
    io.archState.era,
    io.archState.badv,
    io.archState.eentry,
    io.archState.tlbidx,
    io.archState.tlbehi,
    io.archState.tlbelo0,
    io.archState.tlbelo1,
    io.archState.asid,
    io.archState.pgdl,
    io.archState.pgdh,
    io.archState.save0,
    io.archState.save1,
    io.archState.save2,
    io.archState.save3,
    io.archState.tid,
    io.archState.tcfg,
    io.archState.tval,
    io.archState.ticlr,
    io.archState.llbctl,
    io.archState.tlbrentry,
    io.archState.dmw0,
    io.archState.dmw1
  )
  wrapper.io.csrState := csrWords.map(zeroExtend32).reverse.reduce(_ ## _)

  private val gprWords = io.archState.gpr.map(zeroExtend32)
  wrapper.io.gprState := gprWords.reverse.reduce(_ ## _)
}

/** Conditional Verilog shell around chiplab's seven simulator-owned Difftest modules. */
private final class ChiplabDiffTestBlackBox extends BlackBox {
  setDefinitionName("ChiplabDiffTestBlackBox")

  val io = new Bundle {
    val clock = in Bool ()
    val commitContract = in Bits (505 bits)
    val instrValid = in Bool ()
    val pc = in Bits (64 bits)
    val instruction = in Bits (32 bits)
    val isTlbFill = in Bool ()
    val tlbFillIndex = in Bits (5 bits)
    val isCounterInstruction = in Bool ()
    val timer = in Bits (64 bits)
    val gprWriteValid = in Bool ()
    val gprWriteIndex = in Bits (8 bits)
    val gprWriteData = in Bits (64 bits)
    val csrRstat = in Bool ()
    val csrReadData = in Bits (32 bits)

    val exceptionValid = in Bool ()
    val ertn = in Bool ()
    val interruptNumber = in Bits (32 bits)
    val exceptionCause = in Bits (32 bits)
    val exceptionPc = in Bits (64 bits)
    val exceptionInstruction = in Bits (32 bits)

    val trapValid = in Bool ()
    val trapCode = in Bits (3 bits)
    val cycleCount = in Bits (64 bits)
    val instructionCount = in Bits (64 bits)

    val storeValid = in Bits (8 bits)
    val storePhysicalAddress = in Bits (64 bits)
    val storeVirtualAddress = in Bits (64 bits)
    val storeData = in Bits (64 bits)
    val loadValid = in Bits (8 bits)
    val loadPhysicalAddress = in Bits (64 bits)
    val loadVirtualAddress = in Bits (64 bits)

    val csrState = in Bits (27 * 64 bits)
    val gprState = in Bits (32 * 64 bits)
  }
  noIoPrefix()

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
      val source =
        if (name == "euen") s"64'b0 & csrState[$high:$low]"
        else s"csrState[$high:$low]"
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
module ChiplabDiffTestBlackBox (
    input  wire          clock,
    input  wire [504:0]  commitContract,
    input  wire          instrValid,
    input  wire [63:0]   pc,
    input  wire [31:0]   instruction,
    input  wire          isTlbFill,
    input  wire [4:0]    tlbFillIndex,
    input  wire          isCounterInstruction,
    input  wire [63:0]   timer,
    input  wire          gprWriteValid,
    input  wire [7:0]    gprWriteIndex,
    input  wire [63:0]   gprWriteData,
    input  wire          csrRstat,
    input  wire [31:0]   csrReadData,
    input  wire          exceptionValid,
    input  wire          ertn,
    input  wire [31:0]   interruptNumber,
    input  wire [31:0]   exceptionCause,
    input  wire [63:0]   exceptionPc,
    input  wire [31:0]   exceptionInstruction,
    input  wire          trapValid,
    input  wire [2:0]    trapCode,
    input  wire [63:0]   cycleCount,
    input  wire [63:0]   instructionCount,
    input  wire [7:0]    storeValid,
    input  wire [63:0]   storePhysicalAddress,
    input  wire [63:0]   storeVirtualAddress,
    input  wire [63:0]   storeData,
    input  wire [7:0]    loadValid,
    input  wire [63:0]   loadPhysicalAddress,
    input  wire [63:0]   loadVirtualAddress,
    input  wire [1727:0] csrState,
    input  wire [2047:0] gprState
);
`ifdef DIFFTEST_EN
  DifftestInstrCommit u_difftest_instr_commit (
    .clock(clock), .coreid(8'b0), .index(8'b0), .valid(instrValid),
    .pc(pc), .instr(instruction), .skip(1'b0 & ^commitContract), .is_TLBFILL(isTlbFill),
    .TLBFILL_index(tlbFillIndex), .is_CNTinst(isCounterInstruction),
    .timer_64_value(timer), .wen(gprWriteValid), .wdest(gprWriteIndex),
    .wdata(gprWriteData), .csr_rstat(csrRstat), .csr_data(csrReadData)
  );

  DifftestExcpEvent u_difftest_exception (
    .clock(clock), .coreid(8'b0), .excp_valid(exceptionValid), .eret(ertn),
    .intrNo(interruptNumber), .cause(exceptionCause), .exceptionPC(exceptionPc),
    .exceptionInst(exceptionInstruction)
  );

  DifftestTrapEvent u_difftest_trap (
    .clock(clock), .coreid(8'b0), .valid(trapValid), .code(trapCode), .pc(pc),
    .cycleCnt(cycleCount), .instrCnt(instructionCount)
  );

  DifftestStoreEvent u_difftest_store (
    .clock(clock), .coreid(8'b0), .index(8'b0), .valid(storeValid),
    .storePAddr(storePhysicalAddress), .storeVAddr(storeVirtualAddress),
    .storeData(storeData)
  );

  DifftestLoadEvent u_difftest_load (
    .clock(clock), .coreid(8'b0), .index(8'b0), .valid(loadValid),
    .paddr(loadPhysicalAddress), .vaddr(loadVirtualAddress)
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
