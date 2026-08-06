package miku.observe

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import scala.jdk.CollectionConverters._

private final class ChiplabMultiCommitDiffTestAdapterProbe extends Component {
  val io = new Bundle {
    val clock = in Bool ()
  }
  val adapter = new ChiplabMultiCommitDiffTestAdapter(commitWidth = 3)
  adapter.io.clock := io.clock
  adapter.io.commitValid := 0
  adapter.io.stateDelayed := 0
  adapter.io.commit.assignDontCare()
  adapter.io.archState.assignDontCare()
}

class ChiplabMultiCommitDiffTestAdapterSpec extends AnyFunSuite {
  test("generated wrapper preserves three indexed commits and one global state stream") {
    val outputDirectory = Files.createTempDirectory("miku-chiplab-multi-difftest-")
    try {
      SpinalConfig(targetDirectory = outputDirectory.toString, removePruned = false)
        .generateVerilog(new ChiplabMultiCommitDiffTestAdapterProbe)

      val rtl = Files
        .walk(outputDirectory)
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".v"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")

      for (lane <- 0 until 3) {
        assert(rtl.contains(s"DifftestInstrCommit u_difftest_instr_commit_$lane"))
        assert(rtl.contains(s"DifftestStoreEvent u_difftest_store_$lane"))
        assert(rtl.contains(s"DifftestLoadEvent u_difftest_load_$lane"))
        assert(rtl.contains(s".index(8'd$lane)"))
      }
      assert(
        rtl
          .sliding("DifftestExcpEvent u_difftest_exception".length)
          .count(
            _ == "DifftestExcpEvent u_difftest_exception"
          ) == 1
      )
      assert(
        rtl
          .sliding("DifftestCSRRegState u_difftest_csr_state".length)
          .count(
            _ == "DifftestCSRRegState u_difftest_csr_state"
          ) == 1
      )
      assert(
        rtl
          .sliding("DifftestGRegState u_difftest_gpr_state".length)
          .count(
            _ == "DifftestGRegState u_difftest_gpr_state"
          ) == 1
      )
      assert(rtl.contains("module ChiplabMultiCommitDiffTestBlackBox_3"))
      assert(rtl.contains("`ifdef DIFFTEST_EN"))
      assert(rtl.contains("input wire [2:0] instrValid"))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
