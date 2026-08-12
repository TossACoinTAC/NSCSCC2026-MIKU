package miku.execute

import java.nio.file.{Files, Path, Paths}
import spinal.core._
import spinal.lib._

object LaccCommand {
  val Width = 2
  val Lmadd = 0
  val Configure = 1
}

/** Level request held by EXE until the matching response becomes valid. */
final case class LaccRequest() extends Bundle {
  val command = Bits(LaccCommand.Width bits)
  val immediate = Bits(7 bits)
  val registerJ = Bits(32 bits)
  val registerK = Bits(32 bits)
}

final case class LaccResponse() extends Bundle {
  val data = Bits(32 bits)
}

/** One data-cache transaction issued by the accelerator. */
final case class LaccMemoryRequest() extends Bundle {
  val address = UInt(32 bits)
  val read = Bool()
  val writeData = Bits(32 bits)
  val size = Bits(2 bits)
}

final case class LaccMemoryResponse() extends Bundle {
  val data = Bits(32 bits)
}

/** Cycle-compatible implementation of `a158aa8:rtl/lacc_core.v` and `rtl/lacc_demo.v`.
  *
  * Command 1 configures the seven-bit element count and destination address. Command 0 reads one
  * word from each source stream, writes their sum, advances all three addresses by four bytes, and
  * accumulates both read values into the architectural response. The request is a level contract;
  * the response is valid either immediately for configuration/zero-length work or while the final
  * write handshakes. Data-request valid and payload remain stable under backpressure.
  *
  * Reset and flush are synchronous and intentionally reset only the five registers reset by the
  * golden RTL. Address/data registers retain the original partial-reset semantics. The immediate is
  * retained for compatibility but does not affect any valid response or memory transaction.
  */
final class LaccCore extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val flush = in Bool ()
    val request = slave(Flow(LaccRequest()))
    val response = master(Flow(LaccResponse()))
    val memoryRequest = master(Stream(LaccMemoryRequest()))
    val memoryResponse = slave(Flow(LaccMemoryResponse()))
  }

  private val laccClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  private val logic = new ClockingArea(laccClockDomain) {
    val Idle = B"2'b00"
    val RequestAddress1 = B"2'b01"
    val RequestAddress2 = B"2'b10"
    val Final = B"2'b11"

    val requestAddress1 = Reg(UInt(32 bits))
    val requestAddress2 = Reg(UInt(32 bits))
    val writeAddress = Reg(UInt(32 bits))
    val requestSize = Reg(UInt(7 bits)) init (0)
    val state = Reg(Bits(2 bits)) init (Idle)
    val dataRequestValid = Reg(Bool()) init (False)
    val bufferValid = Reg(Bool()) init (False)
    val writeDataValid = Reg(Bool()) init (False)

    val bufferData = Reg(Bits(32 bits))
    val bufferWriteData = Reg(Bits(32 bits))
    val writeDataValidDelayed = Reg(Bool())
    val accumulatedData = Reg(UInt(32 bits))

    val operationLmadd = io.request.payload.command === LaccCommand.Lmadd
    val operationConfigure = io.request.payload.command === LaccCommand.Configure
    val requestSizeNonZero = requestSize.orR
    val stateIdle = state === Idle
    val stateRequestAddress1 = state === RequestAddress1
    val stateRequestAddress2 = state === RequestAddress2
    val stateFinal = state === Final

    val dataHandshake = io.memoryRequest.valid && io.memoryRequest.ready
    val idleExit = stateIdle && io.request.valid && operationLmadd && requestSizeNonZero
    val requestAddress1Exit = stateRequestAddress1 && dataHandshake
    val requestAddress2Exit = stateRequestAddress2 && dataHandshake
    val finalExit = stateFinal && dataHandshake
    val exitToIdle = finalExit && !requestSizeNonZero

    val finalNextState = Mux(requestSizeNonZero, RequestAddress1, Idle)
    val stateEnable = idleExit || requestAddress1Exit || requestAddress2Exit || finalExit
    val nextState =
      Mux(idleExit, RequestAddress1, B(0, 2 bits)) |
        Mux(requestAddress1Exit, RequestAddress2, B(0, 2 bits)) |
        Mux(requestAddress2Exit, Final, B(0, 2 bits)) |
        Mux(finalExit, finalNextState, B(0, 2 bits))

    val configureRequest = io.request.valid && operationConfigure
    val startLmadd = io.request.valid && stateIdle && operationLmadd
    val requestAddress1Enable = startLmadd || requestAddress1Exit
    val requestAddress2Enable = startLmadd || requestAddress2Exit
    val requestSizeEnable = configureRequest || requestAddress2Exit
    val writeAddressEnable = configureRequest || finalExit

    val nextRequestAddress1 =
      Mux(startLmadd, io.request.payload.registerJ.asUInt, U(0, 32 bits)) |
        Mux(requestAddress1Exit, requestAddress1 + 4, U(0, 32 bits))
    val nextRequestAddress2 =
      Mux(startLmadd, io.request.payload.registerK.asUInt, U(0, 32 bits)) |
        Mux(requestAddress2Exit, requestAddress2 + 4, U(0, 32 bits))
    val nextRequestSize =
      Mux(configureRequest, io.request.payload.registerJ(6 downto 0).asUInt, U(0, 7 bits)) |
        Mux(requestAddress2Exit, requestSize - 1, U(0, 7 bits))
    val nextWriteAddress =
      Mux(configureRequest, io.request.payload.registerK.asUInt, U(0, 32 bits)) |
        Mux(finalExit, writeAddress + 4, U(0, 32 bits))

    val dataRequestEnable = idleExit || requestAddress1Exit || requestAddress2Exit || finalExit
    val nextDataRequestValid = !exitToIdle && !requestAddress2Exit

    when(requestAddress1Enable) {
      requestAddress1 := nextRequestAddress1
    }
    when(requestAddress2Enable) {
      requestAddress2 := nextRequestAddress2
    }
    when(writeAddressEnable) {
      writeAddress := nextWriteAddress
    }
    when(!bufferValid) {
      bufferData := io.memoryResponse.payload.data
    }
    when(bufferValid && io.memoryResponse.valid) {
      bufferWriteData :=
        (io.memoryResponse.payload.data.asUInt + bufferData.asUInt).resize(32).asBits
    }
    writeDataValidDelayed := writeDataValid

    when(io.flush) {
      requestSize := 0
      state := Idle
      dataRequestValid := False
      bufferValid := False
      writeDataValid := False
    }.otherwise {
      when(requestSizeEnable) {
        requestSize := nextRequestSize
      }
      when(stateEnable) {
        state := nextState
      }
      when(dataRequestEnable) {
        dataRequestValid := nextDataRequestValid
      }

      when(finalExit) {
        bufferValid := False
      }.elsewhen(io.memoryResponse.valid && !writeDataValidDelayed) {
        bufferValid := True
      }

      when(finalExit) {
        writeDataValid := False
      }.elsewhen(bufferValid && io.memoryResponse.valid) {
        writeDataValid := True
      }
    }

    when(idleExit) {
      accumulatedData := 0
    }.elsewhen(io.memoryResponse.valid) {
      accumulatedData := accumulatedData + io.memoryResponse.payload.data.asUInt
    }
  }

  io.memoryRequest.valid := logic.dataRequestValid || logic.writeDataValid
  io.memoryRequest.payload.read := !logic.writeDataValid
  io.memoryRequest.payload.address :=
    Mux(logic.stateRequestAddress1, logic.requestAddress1, U(0, 32 bits)) |
      Mux(logic.stateRequestAddress2, logic.requestAddress2, U(0, 32 bits)) |
      Mux(logic.stateFinal, logic.writeAddress, U(0, 32 bits))
  io.memoryRequest.payload.size := B"2'b10"
  io.memoryRequest.payload.writeData := logic.bufferWriteData

  io.response.valid :=
    logic.exitToIdle ||
      (io.request.valid && logic.stateIdle &&
        ((logic.operationLmadd && !logic.requestSizeNonZero) || logic.operationConfigure))
  io.response.payload.data := Mux(
    io.response.valid,
    logic.accumulatedData.asBits,
    io.request.payload.immediate.resize(32)
  )
}

/** Exact legacy-port adapter used by the standalone generator and golden lockstep harness. */
private final class LegacyLaccCore extends Component {
  setDefinitionName("lacc_core")

  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val lacc_flush = in Bool ()
    val lacc_req_valid = in Bool ()
    val lacc_req_command = in Bits (LaccCommand.Width bits)
    val lacc_req_imm = in Bits (7 bits)
    val lacc_req_rj = in Bits (32 bits)
    val lacc_req_rk = in Bits (32 bits)
    val lacc_rsp_valid = out Bool ()
    val lacc_rsp_rdat = out Bits (32 bits)
    val lacc_data_valid = out Bool ()
    val lacc_data_ready = in Bool ()
    val lacc_data_addr = out Bits (32 bits)
    val lacc_data_read = out Bool ()
    val lacc_data_wdata = out Bits (32 bits)
    val lacc_data_size = out Bits (2 bits)
    val lacc_drsp_valid = in Bool ()
    val lacc_drsp_rdata = in Bits (32 bits)
  }

  noIoPrefix()

  private val core = new LaccCore
  core.io.clk := io.clk
  core.io.reset := io.reset
  core.io.flush := io.lacc_flush
  core.io.request.valid := io.lacc_req_valid
  core.io.request.payload.command := io.lacc_req_command
  core.io.request.payload.immediate := io.lacc_req_imm
  core.io.request.payload.registerJ := io.lacc_req_rj
  core.io.request.payload.registerK := io.lacc_req_rk
  io.lacc_rsp_valid := core.io.response.valid
  io.lacc_rsp_rdat := core.io.response.payload.data
  io.lacc_data_valid := core.io.memoryRequest.valid
  core.io.memoryRequest.ready := io.lacc_data_ready
  io.lacc_data_addr := core.io.memoryRequest.payload.address.asBits
  io.lacc_data_read := core.io.memoryRequest.payload.read
  io.lacc_data_wdata := core.io.memoryRequest.payload.writeData
  io.lacc_data_size := core.io.memoryRequest.payload.size
  core.io.memoryResponse.valid := io.lacc_drsp_valid
  core.io.memoryResponse.payload.data := io.lacc_drsp_rdata
}

object GenerateLaccCore {
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
          "usage: GenerateLaccCore [--out-dir] <output-directory>"
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
    config.generateVerilog(new LegacyLaccCore)
  }
}
