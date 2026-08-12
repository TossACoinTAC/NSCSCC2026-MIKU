package miku.execute

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.jdk.CollectionConverters._
import scala.util.Random

private final class AluSimTop extends Component {
  val io = new Bundle {
    val aluOp = in Bits (AluOperation.Width bits)
    val src1 = in Bits (32 bits)
    val src2 = in Bits (32 bits)
    val result = out Bits (32 bits)
    val heartbeat = out Bool ()
  }

  val alu = new Alu
  alu.io.alu_op := io.aluOp
  alu.io.alu_src1 := io.src1
  alu.io.alu_src2 := io.src2
  io.result := alu.io.alu_result

  val heartbeat = Reg(Bool()) init (False)
  heartbeat := !heartbeat
  io.heartbeat := heartbeat
}

class AluSpec extends AnyFunSuite {
  private object GoldenOp {
    val Width = 14
    val Add = 0
    val Sub = 1
    val Slt = 2
    val Sltu = 3
    val And = 4
    val Nor = 5
    val Or = 6
    val Xor = 7
    val Sll = 8
    val Srl = 9
    val Sra = 10
    val Lui = 11
    val Andn = 12
    val Orn = 13
  }

  private val WordWidth = 32
  private val WordModulus = BigInt(1) << WordWidth
  private val WordMask = WordModulus - 1
  private val OpMask = (1 << GoldenOp.Width) - 1
  private val RandomSeed = 0x158aa8

  private def u32(value: BigInt): BigInt = value & WordMask

  private def selected(op: Int, index: Int, value: BigInt): BigInt =
    if ((op & (1 << index)) != 0) u32(value) else BigInt(0)

  // This models the shared adder and shared right shifter in the golden Verilog,
  // including their deliberately observable behavior for multi-hot operation masks.
  private def goldenOracle(rawOp: Int, rawSrc1: BigInt, rawSrc2: BigInt): BigInt = {
    import GoldenOp._

    val op = rawOp & OpMask
    val src1 = u32(rawSrc1)
    val src2 = u32(rawSrc2)
    val subtract = Seq(Sub, Slt, Sltu).exists(index => (op & (1 << index)) != 0)
    val adderB = if (subtract) u32(~src2) else src2
    val adderWide = src1 + adderB + (if (subtract) 1 else 0)
    val addSub = u32(adderWide)
    val adderCarry = (adderWide >> WordWidth) & 1

    val src1Sign = (src1 >> 31) & 1
    val src2Sign = (src2 >> 31) & 1
    val signedLess =
      (src1Sign == 1 && src2Sign == 0) ||
        (src1Sign == src2Sign && ((addSub >> 31) & 1) == 1)
    val slt = if (signedLess) BigInt(1) else BigInt(0)
    val sltu = if (adderCarry == 0) BigInt(1) else BigInt(0)

    val shiftAmount = (src2 & 0x1f).toInt
    val sll = u32(src1 << shiftAmount)
    val arithmeticRight = (op & (1 << Sra)) != 0 && src1Sign == 1
    val shiftSource = if (arithmeticRight) (WordMask << WordWidth) | src1 else src1
    val shiftRight = u32(shiftSource >> shiftAmount)

    val terms = Seq(
      if ((op & ((1 << Add) | (1 << Sub))) != 0) addSub else BigInt(0),
      selected(op, Slt, slt),
      selected(op, Sltu, sltu),
      selected(op, And, src1 & src2),
      selected(op, Andn, src1 & ~src2),
      selected(op, Nor, ~(src1 | src2)),
      selected(op, Or, src1 | src2),
      selected(op, Orn, src1 | ~src2),
      selected(op, Xor, src1 ^ src2),
      selected(op, Lui, src2),
      selected(op, Sll, sll),
      if ((op & ((1 << Srl) | (1 << Sra))) != 0) shiftRight else BigInt(0)
    )

    u32(terms.foldLeft(BigInt(0))(_ | _))
  }

  private def withSimulation(
      simulationName: String
  )(
      testBody: (AluSimTop, (Int, BigInt, BigInt) => Unit) => Unit
  ): Unit = {
    val workspaceRoot =
      sys.env.getOrElse("SPINAL_SIM_WORKSPACE", "target/sim-workspace-miku-alu")
    val workspace = Paths.get(workspaceRoot, s"miku-alu-$simulationName").toString
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
      .compile(new AluSimTop)
      .doSim(simulationName, RandomSeed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        def check(op: Int, src1: BigInt, src2: BigInt): Unit = {
          dut.io.aluOp #= op & OpMask
          dut.io.src1 #= u32(src1)
          dut.io.src2 #= u32(src2)
          sleep(1)
          val actual = dut.io.result.toBigInt
          val expected = goldenOracle(op, src1, src2)
          assert(
            actual == expected,
            f"op=0x${op & OpMask}%04x src1=0x${u32(src1)}%08x src2=0x${u32(src2)}%08x " +
              f"expected=0x$expected%08x actual=0x$actual%08x"
          )
        }

        testBody(dut, check)
      }
  }

  test("directed vectors cover zero, every operation, multi-hot, signs, and shift boundaries") {
    import GoldenOp._

    withSimulation("directed") { (_, check) =>
      check(0, BigInt("89abcdef", 16), BigInt("76543210", 16))

      val genericSrc1 = BigInt("87654321", 16)
      val genericSrc2 = BigInt("12345678", 16)
      (0 until Width).foreach(index => check(1 << index, genericSrc1, genericSrc2))

      check(1 << Add, BigInt("ffffffff", 16), 1)
      check(1 << Sub, BigInt("80000000", 16), 1)
      check(1 << Slt, BigInt("80000000", 16), BigInt("7fffffff", 16))
      check(1 << Slt, BigInt("7fffffff", 16), BigInt("80000000", 16))
      check(1 << Sltu, BigInt("ffffffff", 16), 0)
      check(1 << Sltu, 0, BigInt("ffffffff", 16))

      Seq(0, 1, 31).foreach { amount =>
        check(1 << Sll, BigInt("80000001", 16), amount)
        check(1 << Srl, BigInt("80000001", 16), amount)
        check(1 << Sra, BigInt("80000001", 16), amount)
        check(1 << Sra, BigInt("7fffffff", 16), amount)
      }

      check((1 << Add) | (1 << Sub), BigInt("01020304", 16), BigInt("00102030", 16))
      check((1 << Add) | (1 << And), BigInt("f0f00f0f", 16), BigInt("55aa55aa", 16))
      check((1 << Srl) | (1 << Sra), BigInt("80000000", 16), 31)
      check(OpMask, BigInt("deadbeef", 16), BigInt("89abcdef", 16))
    }
  }

  test("deterministic random vectors match the independent golden oracle") {
    withSimulation("random") { (_, check) =>
      val random = new Random(RandomSeed)
      for (_ <- 0 until 4096) {
        val op = random.nextInt(OpMask + 1)
        val src1 = BigInt(java.lang.Integer.toUnsignedLong(random.nextInt()))
        val src2 = BigInt(java.lang.Integer.toUnsignedLong(random.nextInt()))
        check(op, src1, src2)
      }
    }
  }

  test("generated module has only the native golden ports") {
    val outputDirectory = Files.createTempDirectory("miku-alu-rtl-")
    try {
      GenerateAlu.main(Array(outputDirectory.toString))

      val rtl = Files.readString(outputDirectory.resolve("alu.v"), StandardCharsets.UTF_8)
      val moduleHeader = "(?s)module\\s+alu\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain module alu"))

      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+\\[13:0\\]\\s+alu_op.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+\\[31:0\\]\\s+alu_src1.*"))
      assert(moduleHeader.matches("(?s).*input\\s+wire\\s+\\[31:0\\]\\s+alu_src2.*"))
      assert(moduleHeader.matches("(?s).*output\\s+wire\\s+\\[31:0\\]\\s+alu_result.*"))
      assert(!moduleHeader.contains("io_"))
      assert(!moduleHeader.toLowerCase.contains("clk"))
      assert(!moduleHeader.toLowerCase.contains("reset"))
      assert(!rtl.contains("`timescale"))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
