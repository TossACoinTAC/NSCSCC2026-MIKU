package miku.execute

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.jdk.CollectionConverters._
import scala.util.Random

private final class RegisteredMultiplierSimTop extends Component {
  val io = new Bundle {
    val mulClk = in Bool ()
    val hold = in Bool ()
    val signed = in Bool ()
    val x = in Bits (32 bits)
    val y = in Bits (32 bits)
    val result = out Bits (64 bits)
    val heartbeat = out Bool ()
  }

  val mul = new RegisteredMultiplier
  mul.io.mul_clk := io.mulClk
  mul.io.reset := io.hold
  mul.io.mul_signed := io.signed
  mul.io.x := io.x
  mul.io.y := io.y
  io.result := mul.io.result

  val heartbeat = Reg(Bool()) init (False)
  heartbeat := !heartbeat
  io.heartbeat := heartbeat
}

class RegisteredMultiplierSpec extends AnyFunSuite {
  private val WordWidth = 32
  private val ResultWidth = 64
  private val WordModulus = BigInt(1) << WordWidth
  private val WordMask = WordModulus - 1
  private val ResultMask = (BigInt(1) << ResultWidth) - 1
  private val RandomSeed = 0x158aa8

  private def u32(value: BigInt): BigInt = value & WordMask

  private def s32(value: BigInt): BigInt = {
    val normalized = u32(value)
    if (normalized.testBit(WordWidth - 1)) normalized - WordModulus else normalized
  }

  private def goldenProduct(x: BigInt, y: BigInt, signed: Boolean): BigInt = {
    val product = if (signed) s32(x) * s32(y) else u32(x) * u32(y)
    product & ResultMask
  }

  test("each active edge captures a full signed or unsigned product and reset holds it") {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-miku-mul")
    val workspace = Paths.get(workspaceRoot, "miku-mul-cycle-contract").toString

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
      .compile(new RegisteredMultiplierSimTop)
      .doSim("mul-cycle-contract", RandomSeed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.mulClk #= false
        dut.io.hold #= true
        dut.io.signed #= false
        dut.io.x #= 0
        dut.io.y #= 0
        sleep(2)

        def drive(x: BigInt, y: BigInt, signed: Boolean, reset: Boolean): Unit = {
          dut.io.x #= u32(x)
          dut.io.y #= u32(y)
          dut.io.signed #= signed
          dut.io.hold #= reset
        }

        def risingEdge(): Unit = {
          dut.io.mulClk #= false
          sleep(2)
          dut.io.mulClk #= true
          sleep(1)
        }

        def captureAndCheck(x: BigInt, y: BigInt, signed: Boolean): BigInt = {
          drive(x, y, signed, reset = false)
          risingEdge()
          val expected = goldenProduct(x, y, signed)
          val actual = dut.io.result.toBigInt
          assert(
            actual == expected,
            f"signed=$signed x=0x${u32(x)}%08x y=0x${u32(y)}%08x " +
              f"expected=0x$expected%016x actual=0x$actual%016x"
          )
          expected
        }

        val directed = Seq(
          (BigInt(0), BigInt(0), false),
          (BigInt(1), BigInt(1), false),
          (WordMask, WordMask, false),
          (BigInt("80000000", 16), BigInt(2), false),
          (BigInt("80000000", 16), BigInt(2), true),
          (WordMask, BigInt(2), true),
          (BigInt("7fffffff", 16), WordMask, true),
          (BigInt("80000000", 16), WordMask, true),
          (BigInt("80000000", 16), BigInt("80000000", 16), true)
        )
        directed.foreach { case (x, y, signed) => captureAndCheck(x, y, signed) }

        val held = captureAndCheck(BigInt("89abcdef", 16), BigInt("76543210", 16), signed = false)
        dut.io.mulClk #= false
        drive(BigInt("11111111", 16), BigInt("22222222", 16), signed = true, reset = false)
        sleep(3)
        assert(dut.io.result.toBigInt == held, "input changes without a rising edge changed result")

        val random = new Random(RandomSeed)
        for (_ <- 0 until 4096) {
          val x = BigInt(java.lang.Integer.toUnsignedLong(random.nextInt()))
          val y = BigInt(java.lang.Integer.toUnsignedLong(random.nextInt()))
          captureAndCheck(x, y, random.nextBoolean())
        }

        val beforeReset = dut.io.result.toBigInt
        for (_ <- 0 until 4) {
          drive(
            BigInt(java.lang.Integer.toUnsignedLong(random.nextInt())),
            BigInt(java.lang.Integer.toUnsignedLong(random.nextInt())),
            random.nextBoolean(),
            reset = true
          )
          risingEdge()
          assert(dut.io.result.toBigInt == beforeReset, "reset-high edge did not hold result")
        }

        captureAndCheck(WordMask, BigInt(3), signed = true)
      }
  }

  test("generated module has exactly the six native golden ports") {
    val outputDirectory = Files.createTempDirectory("miku-mul-rtl-")
    try {
      GenerateRegisteredMultiplier.main(Array(outputDirectory.toString))

      val rtl = Files.readString(outputDirectory.resolve("mul.v"), StandardCharsets.UTF_8)
      val moduleHeader = "(?s)module\\s+mul\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain module mul"))

      val declaredPorts =
        "(?m)^\\s*(?:input|output)\\s+wire(?:\\s+\\[[^]]+\\])?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*$".r
          .findAllMatchIn(moduleHeader)
          .map(_.group(1))
          .toSeq

      assert(declaredPorts == Seq("mul_clk", "reset", "mul_signed", "x", "y", "result"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+mul_clk.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+reset.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+mul_signed.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+\\[31:0\\]\\s+x.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+\\[31:0\\]\\s+y.*"))
      assert(moduleHeader.matches("(?s).*output\\s+wire\\s+\\[63:0\\]\\s+result.*"))
      assert(!moduleHeader.contains("io_"))
      assert(!rtl.contains("`timescale"))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
