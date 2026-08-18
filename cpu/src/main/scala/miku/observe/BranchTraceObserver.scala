package miku.observe

import miku.core.OooCoreConfig
import spinal.core._

/** Simulation-only retirement sideband for predictor metadata analysis.
  *
  * The observer has no ready/feedback path and is instantiated only when the generator is invoked
  * with the branch-trace option. Its DPI shell is disabled for synthesis by the existing
  * DIFFTEST_EN simulation guard.
  */
final class BranchTraceObserver(config: OooCoreConfig) extends BlackBox {
  private val moduleName = s"MikuBranchTraceObserver_${config.commitWidth}"
  private val commitWidth = config.commitWidth
  private val xlen = config.xlen
  private val robPointerWidth = config.robPointerWidth
  private val phtIndexWidth = config.predictorPhtIndexWidth
  private val metadataValidBit = config.predictorMetadataValidBit

  val io = new Bundle {
    val clock = in Bool ()
    val cycle = in Bits (64 bits)
    val valid = in Bits (commitWidth bits)
    val robPointer = in Bits (commitWidth * robPointerWidth bits)
    val pc = in Bits (commitWidth * xlen bits)
    val instruction = in Bits (commitWidth * 32 bits)
    val predictorType = in Bits (commitWidth * 3 bits)
    val actualTaken = in Bits (commitWidth bits)
    val actualTarget = in Bits (commitWidth * xlen bits)
    val predictorMetadata = in Bits (commitWidth * 16 bits)
  }

  noIoPrefix()
  setDefinitionName(moduleName)

  private def slice(signal: String, lane: Int, width: Int): String = {
    val low = lane * width
    val high = low + width - 1
    s"$signal[$high:$low]"
  }

  private val eventCalls = (0 until commitWidth)
    .map { lane =>
      s"""
    if (valid[$lane]) begin
      miku_branch_trace_event(
        cycle,
        8'd$lane,
        ${slice("robPointer", lane, robPointerWidth)},
        ${slice("pc", lane, xlen)},
        ${slice("instruction", lane, 32)},
        ${slice("predictorType", lane, 3)},
        ${slice("actualTaken", lane, 1)},
        ${slice("actualTarget", lane, xlen)},
        ${slice("predictorMetadata", lane, 16)},
        8'd$phtIndexWidth,
        8'd$metadataValidBit
      );
    end"""
    }
    .mkString("\n")

  setInlineVerilog(s"""
module $moduleName (
    input wire clock,
    input wire [63:0] cycle,
    input wire [${commitWidth - 1}:0] valid,
    input wire [${commitWidth * robPointerWidth - 1}:0] robPointer,
    input wire [${commitWidth * xlen - 1}:0] pc,
    input wire [${commitWidth * 32 - 1}:0] instruction,
    input wire [${commitWidth * 3 - 1}:0] predictorType,
    input wire [${commitWidth - 1}:0] actualTaken,
    input wire [${commitWidth * xlen - 1}:0] actualTarget,
    input wire [${commitWidth * 16 - 1}:0] predictorMetadata
);
`ifdef DIFFTEST_EN
  import "DPI-C" function void miku_branch_trace_init(
    input byte unsigned pht_index_width,
    input byte unsigned metadata_valid_bit
  );
  import "DPI-C" function void miku_branch_trace_event(
    input longint unsigned cycle,
    input byte unsigned lane,
    input int unsigned rob_pointer,
    input int unsigned pc,
    input int unsigned instruction,
    input byte unsigned predictor_type,
    input byte unsigned actual_taken,
    input int unsigned actual_target,
    input int unsigned predictor_metadata,
    input byte unsigned pht_index_width,
    input byte unsigned metadata_valid_bit
  );

  initial begin
    miku_branch_trace_init(8'd$phtIndexWidth, 8'd$metadataValidBit);
  end

  always @(posedge clock) begin
$eventCalls
  end
`endif
endmodule
""")
}
