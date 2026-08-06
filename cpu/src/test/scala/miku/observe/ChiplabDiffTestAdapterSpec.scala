package miku.observe

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import scala.jdk.CollectionConverters._

private final class ChiplabDiffTestAdapterProbe extends Component {
  val io = new Bundle {
    val clock = in Bool ()
  }
  val adapter = new ChiplabDiffTestAdapter
  adapter.io.clock := io.clock
  adapter.io.commit.valid := False
  adapter.io.commit.payload.assignDontCare()
  adapter.io.archState.assignDontCare()
}

class ChiplabDiffTestAdapterSpec extends AnyFunSuite {
  test("generated conditional wrapper contains all seven official chiplab Difftest modules") {
    val outputDirectory = Files.createTempDirectory("miku-chiplab-difftest-")
    try {
      SpinalConfig(targetDirectory = outputDirectory.toString, removePruned = false)
        .generateVerilog(new ChiplabDiffTestAdapterProbe)

      val rtl = Files
        .walk(outputDirectory)
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".v"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")

      val officialModules = Seq(
        "DifftestInstrCommit",
        "DifftestExcpEvent",
        "DifftestTrapEvent",
        "DifftestStoreEvent",
        "DifftestLoadEvent",
        "DifftestCSRRegState",
        "DifftestGRegState"
      )
      officialModules.foreach(name => assert(rtl.contains(s"$name u_difftest_")))
      assert(rtl.contains("`ifdef DIFFTEST_EN"))
      assert(rtl.contains("`ifndef DIFFTEST_EN"))
      assert(
        rtl
          .sliding("verilator lint_off UNUSEDSIGNAL".length)
          .count(_ == "verilator lint_off UNUSEDSIGNAL") == 1
      )
      assert(
        rtl
          .sliding("verilator lint_on UNUSEDSIGNAL".length)
          .count(_ == "verilator lint_on UNUSEDSIGNAL") == 1
      )
      assert(rtl.contains(".valid(instrValid)"))
      assert(rtl.contains(".skip(1'b0 & ^commitContract)"))
      assert(rtl.contains(".TLBFILL_index(tlbFillIndex)"))
      assert(rtl.contains(".euen(64'b0 & csrState[191:128])"))
      assert(rtl.contains(".gpr_0(64'b0 & gprState[63:0])"))
      assert(rtl.contains("input  wire [2047:0] gprState"))
      assert(!rtl.contains("registeredArchState"))
      assert(rtl.contains("registeredCommit"))
      Seq(
        "assign wrapper_pc = {32'h0,registeredCommit_pc};",
        "assign wrapper_gprWriteIndex = {3'b000,registeredCommit_gprWrite_index};",
        "assign wrapper_gprWriteData = {32'h0,registeredCommit_gprWrite_data};",
        "assign wrapper_interruptNumber = {21'h0,io_archState_estat[12 : 2]};",
        "assign wrapper_exceptionCause = {26'h0,registeredCommit_exception_ecode};",
        "assign wrapper_exceptionPc = {32'h0,registeredCommit_pc};",
        "assign wrapper_storePhysicalAddress = {32'h0,registeredCommit_store_pAddr};",
        "assign wrapper_storeVirtualAddress = {32'h0,registeredCommit_store_vAddr};",
        "assign wrapper_storeData = {32'h0,registeredCommit_store_data};",
        "assign wrapper_loadPhysicalAddress = {32'h0,registeredCommit_load_pAddr};",
        "assign wrapper_loadVirtualAddress = {32'h0,registeredCommit_load_vAddr};"
      ).foreach(expected => assert(rtl.contains(expected)))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
