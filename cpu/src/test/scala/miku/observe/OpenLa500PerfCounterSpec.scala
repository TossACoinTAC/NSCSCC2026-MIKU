package miku.observe

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.jdk.CollectionConverters._

private final class OpenLa500PerfCounterSimTop extends Component {
  val io = new Bundle {
    val coreClk = in Bool ()
    val coreReset = in Bool ()
    val events = in(PerfCounterEvent())
    val dataCacheMiss = out UInt (32 bits)
    val instructionCacheMiss = out UInt (32 bits)
    val retired = out UInt (32 bits)
    val branch = out UInt (32 bits)
    val memoryAccess = out UInt (32 bits)
    val predictedBranch = out UInt (32 bits)
    val predictionError = out UInt (32 bits)
    val heartbeat = out Bool ()
  }

  private val core = new OpenLa500PerfCounter(exposeSnapshot = true)
  core.io.clk := io.coreClk
  core.io.reset := io.coreReset
  core.io.events := io.events
  io.dataCacheMiss := core.io.snapshot.dataCacheMiss
  io.instructionCacheMiss := core.io.snapshot.instructionCacheMiss
  io.retired := core.io.snapshot.retired
  io.branch := core.io.snapshot.branch
  io.memoryAccess := core.io.snapshot.memoryAccess
  io.predictedBranch := core.io.snapshot.predictedBranch
  io.predictionError := core.io.snapshot.predictionError

  private val heartbeat = Reg(Bool()) init (False)
  heartbeat := !heartbeat
  io.heartbeat := heartbeat
}

class OpenLa500PerfCounterSpec extends AnyFunSuite {
  test("synchronous reset, independent events, concurrency, and idle hold match the contract") {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-miku-perf-counter")
    val workspace = Paths.get(workspaceRoot, "miku-perf-counter-contract").toString

    SimConfig
      .withConfig(SpinalConfig(oneFilePerComponent = true))
      .withVerilator
      .addSimulatorFlag("-Wall")
      .addSimulatorFlag("-Wwarn-WIDTH")
      .addSimulatorFlag("-Wwarn-UNOPTFLAT")
      .addSimulatorFlag("-Wwarn-CMPCONST")
      .addSimulatorFlag("-Wwarn-UNSIGNED")
      .disableCache
      .workspacePath(workspace)
      .compile(new OpenLa500PerfCounterSimTop)
      .doSim("perf-counter-contract", 0x158aa8e) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.coreClk #= false
        dut.io.coreReset #= true

        def clearEvents(): Unit = {
          dut.io.events.dataCacheMiss #= false
          dut.io.events.instructionCacheMiss #= false
          dut.io.events.retired #= false
          dut.io.events.branch #= false
          dut.io.events.memoryAccess #= false
          dut.io.events.predictedBranch #= false
          dut.io.events.predictionError #= false
        }

        def risingEdge(): Unit = {
          dut.io.coreClk #= false
          sleep(2)
          dut.io.coreClk #= true
          sleep(2)
          dut.io.coreClk #= false
          sleep(2)
        }

        def values: Seq[BigInt] =
          Seq(
            dut.io.dataCacheMiss.toBigInt,
            dut.io.instructionCacheMiss.toBigInt,
            dut.io.retired.toBigInt,
            dut.io.branch.toBigInt,
            dut.io.memoryAccess.toBigInt,
            dut.io.predictedBranch.toBigInt,
            dut.io.predictionError.toBigInt
          )

        clearEvents()
        dut.io.events.retired #= true
        risingEdge()
        assert(values == Seq.fill(7)(BigInt(0)), "synchronous reset did not dominate events")

        dut.io.coreReset #= false
        clearEvents()
        dut.io.events.dataCacheMiss #= true
        risingEdge()
        assert(values == Seq(1, 0, 0, 0, 0, 0, 0), "single event incremented wrong counters")

        dut.io.events.dataCacheMiss #= false
        dut.io.events.instructionCacheMiss #= true
        dut.io.events.retired #= true
        dut.io.events.branch #= true
        dut.io.events.memoryAccess #= true
        dut.io.events.predictedBranch #= true
        dut.io.events.predictionError #= true
        risingEdge()
        assert(values == Seq(1, 1, 1, 1, 1, 1, 1), "concurrent events were not independent")

        clearEvents()
        risingEdge()
        risingEdge()
        assert(values == Seq.fill(7)(BigInt(1)), "idle cycles changed counter state")

        dut.io.coreReset #= true
        dut.io.events.dataCacheMiss #= true
        dut.io.events.predictionError #= true
        risingEdge()
        assert(values == Seq.fill(7)(BigInt(0)), "reset plus events did not clear all counters")
      }
  }

  test("generated legacy wrapper has exactly the locked nine input ports and named counters") {
    val outputDirectory = Files.createTempDirectory("miku-perf-counter-rtl-")
    try {
      GenerateOpenLa500PerfCounter.main(Array(outputDirectory.toString))
      val rtl = Files.readString(outputDirectory.resolve("perf_counter.v"), StandardCharsets.UTF_8)
      val moduleHeader = "(?s)module\\s+perf_counter\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain module perf_counter"))
      val declaredPorts =
        "(?m)^\\s*input\\s+wire(?:\\s+\\[[^]]+\\])?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*$".r
          .findAllMatchIn(moduleHeader)
          .map(_.group(1))
          .toSeq

      assert(
        declaredPorts == Seq(
          "clk",
          "reset",
          "dcache_miss",
          "icache_miss",
          "commit_inst",
          "br_inst",
          "mem_inst",
          "br_pre",
          "br_pre_error"
        )
      )
      assert(!"(?m)^\\s*output\\s+wire".r.findFirstIn(moduleHeader).isDefined)
      for (
        counter <- Seq(
          "dcache_miss_counter",
          "icache_miss_counter",
          "commit_inst_counter",
          "br_inst_counter",
          "mem_inst_counter",
          "br_pre_counter",
          "br_pre_error_counter"
        )
      ) assert(rtl.contains(counter), s"generated RTL lost named counter $counter")
    } finally {
      Files
        .walk(outputDirectory)
        .iterator()
        .asScala
        .toSeq
        .sortBy(_.getNameCount)
        .reverse
        .foreach(path => Files.deleteIfExists(path))
    }
  }
}
