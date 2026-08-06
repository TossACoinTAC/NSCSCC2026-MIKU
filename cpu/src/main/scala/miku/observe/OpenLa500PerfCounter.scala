package miku.observe

import java.nio.file.{Files, Path, Paths}
import spinal.core._
import spinal.core.sim.SimPublic

/** Performance-event levels sampled on every rising edge at the writeback boundary. */
final case class PerfCounterEvent() extends Bundle {
  val dataCacheMiss = Bool()
  val instructionCacheMiss = Bool()
  val retired = Bool()
  val branch = Bool()
  val memoryAccess = Bool()
  val predictedBranch = Bool()
  val predictionError = Bool()
}

/** Read-only verification/observation view; omitted from the production component boundary. */
final case class PerfCounterSnapshot() extends Bundle {
  val dataCacheMiss = UInt(32 bits)
  val instructionCacheMiss = UInt(32 bits)
  val retired = UInt(32 bits)
  val branch = UInt(32 bits)
  val memoryAccess = UInt(32 bits)
  val predictedBranch = UInt(32 bits)
  val predictionError = UInt(32 bits)
}

/** Shared counter implementation used by both the typed core and the exact legacy adapter. */
private[observe] final class PerfCounterLogic(
    clockDomain: ClockDomain,
    events: PerfCounterEvent
) extends ClockingArea(clockDomain) {
  val dataCacheMiss = Reg(UInt(32 bits)) init (0)
  val instructionCacheMiss = Reg(UInt(32 bits)) init (0)
  val retired = Reg(UInt(32 bits)) init (0)
  val branch = Reg(UInt(32 bits)) init (0)
  val memoryAccess = Reg(UInt(32 bits)) init (0)
  val predictedBranch = Reg(UInt(32 bits)) init (0)
  val predictionError = Reg(UInt(32 bits)) init (0)

  when(events.dataCacheMiss) {
    dataCacheMiss := dataCacheMiss + 1
  }
  when(events.instructionCacheMiss) {
    instructionCacheMiss := instructionCacheMiss + 1
  }
  when(events.retired) {
    retired := retired + 1
  }
  when(events.branch) {
    branch := branch + 1
  }
  when(events.memoryAccess) {
    memoryAccess := memoryAccess + 1
  }
  when(events.predictedBranch) {
    predictedBranch := predictedBranch + 1
  }
  when(events.predictionError) {
    predictionError := predictionError + 1
  }
}

/** Activity-compatible implementation of `a158aa8:rtl/perf_counter.v`.
  *
  * Seven independent 32-bit counters sample writeback events on the rising edge. Reset is
  * synchronous, active high, and has priority over all events. The counters naturally wrap modulo
  * 2^32. They are intentionally not architectural outputs; future observation adapters may consume
  * the named read-only state without coupling pipeline stages to the counter implementation.
  */
final class OpenLa500PerfCounter(exposeSnapshot: Boolean = false) extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val events = in(PerfCounterEvent())
    val snapshot = if (exposeSnapshot) out(PerfCounterSnapshot()) else null
  }

  private val perfClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val counters = new PerfCounterLogic(perfClockDomain, io.events)

  if (exposeSnapshot) {
    io.snapshot.dataCacheMiss := counters.dataCacheMiss
    io.snapshot.instructionCacheMiss := counters.instructionCacheMiss
    io.snapshot.retired := counters.retired
    io.snapshot.branch := counters.branch
    io.snapshot.memoryAccess := counters.memoryAccess
    io.snapshot.predictedBranch := counters.predictedBranch
    io.snapshot.predictionError := counters.predictionError
  }
}

/** Exact nine-port adapter for the locked golden contract and the cycle-level harness. */
private[miku] final class LegacyOpenLa500PerfCounter extends Component {
  setDefinitionName("perf_counter")

  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val dcache_miss = in Bool ()
    val icache_miss = in Bool ()
    val commit_inst = in Bool ()
    val br_inst = in Bool ()
    val mem_inst = in Bool ()
    val br_pre = in Bool ()
    val br_pre_error = in Bool ()
  }
  noIoPrefix()

  private val events = PerfCounterEvent()
  events.flatten.foreach(_.allowPruning())
  events.dataCacheMiss := io.dcache_miss
  events.instructionCacheMiss := io.icache_miss
  events.retired := io.commit_inst
  events.branch := io.br_inst
  events.memoryAccess := io.mem_inst
  events.predictedBranch := io.br_pre
  events.predictionError := io.br_pre_error

  private val perfClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  private val counters = new PerfCounterLogic(perfClockDomain, events)

  counters.dataCacheMiss.setName("dcache_miss_counter")
  counters.instructionCacheMiss.setName("icache_miss_counter")
  counters.retired.setName("commit_inst_counter")
  counters.branch.setName("br_inst_counter")
  counters.memoryAccess.setName("mem_inst_counter")
  counters.predictedBranch.setName("br_pre_counter")
  counters.predictionError.setName("br_pre_error_counter")
  SimPublic(counters.dataCacheMiss)
  SimPublic(counters.instructionCacheMiss)
  SimPublic(counters.retired)
  SimPublic(counters.branch)
  SimPublic(counters.memoryAccess)
  SimPublic(counters.predictedBranch)
  SimPublic(counters.predictionError)
  Seq(
    counters.dataCacheMiss,
    counters.instructionCacheMiss,
    counters.retired,
    counters.branch,
    counters.memoryAccess,
    counters.predictedBranch,
    counters.predictionError
  ).foreach(_.allowPruning())
}

object GenerateOpenLa500PerfCounter {
  private def outputArgument(args: Array[String]): String =
    args match {
      case Array(path) if path.nonEmpty              => path
      case Array("--out-dir", path) if path.nonEmpty => path
      case Array() =>
        sys.env
          .get("OUT_DIR")
          .filter(_.nonEmpty)
          .getOrElse(throw new IllegalArgumentException("output directory is required"))
      case _ =>
        throw new IllegalArgumentException(
          "usage: GenerateOpenLa500PerfCounter [--out-dir] <output-directory>"
        )
    }

  private def findRepositoryRoot(path: Path): Option[Path] =
    if (path == null) None
    else if (Files.exists(path.resolve(".git"))) Some(path)
    else findRepositoryRoot(path.getParent)

  private def prospectiveRealPath(path: Path): Path =
    if (Files.exists(path)) path.toRealPath()
    else {
      val parent = Option(path.getParent).getOrElse {
        throw new IllegalArgumentException(s"output path has no existing ancestor: $path")
      }
      prospectiveRealPath(parent).resolve(path.getFileName).normalize()
    }

  def main(args: Array[String]): Unit = {
    val outputDirectory = Paths.get(outputArgument(args)).toAbsolutePath.normalize()
    val workingDirectory = Paths.get("").toAbsolutePath.normalize()
    val classDirectory = Paths
      .get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
      .toAbsolutePath
      .normalize()
    val repositoryRoots = Seq(workingDirectory, classDirectory).flatMap(findRepositoryRoot).distinct
    val prospectiveOutput = prospectiveRealPath(outputDirectory)

    repositoryRoots.foreach { repositoryRoot =>
      val protectedRtl = prospectiveRealPath(repositoryRoot.resolve("rtl"))
      require(
        prospectiveOutput != protectedRtl && !prospectiveOutput.startsWith(protectedRtl),
        s"refusing to write generated RTL under the repository RTL directory: $protectedRtl"
      )
    }

    Files.createDirectories(outputDirectory)
    require(Files.isDirectory(outputDirectory), s"output path is not a directory: $outputDirectory")
    require(
      outputDirectory.toRealPath() == prospectiveOutput,
      s"output directory changed while it was being created: $outputDirectory"
    )

    val config = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    config.withTimescale = false
    config.generateVerilog(new LegacyOpenLa500PerfCounter)
  }
}
