package miku.execute

import spinal.core._

object AluOperation {
  final val Width = 14

  final val Add = 0
  final val Sub = 1
  final val Slt = 2
  final val Sltu = 3
  final val And = 4
  final val Nor = 5
  final val Or = 6
  final val Xor = 7
  final val Sll = 8
  final val Srl = 9
  final val Sra = 10
  final val Lui = 11
  final val Andn = 12
  final val Orn = 13
}

/** Combinational implementation of a158aa8:rtl/alu.v.
  *
  * Input contract: `alu_op` is a 14-bit operation mask; zero and multi-hot masks are legal and
  * retain the golden masked-OR behavior. Output latency is zero and there is no clock, reset,
  * state, backpressure, or flush behavior.
  */
final class Alu extends Component {
  val io = new Bundle {
    val alu_op = in Bits (AluOperation.Width bits)
    val alu_src1 = in Bits (32 bits)
    val alu_src2 = in Bits (32 bits)
    val alu_result = out Bits (32 bits)
  }

  noIoPrefix()

  import AluOperation._

  val subtract = io.alu_op(Sub) || io.alu_op(Slt) || io.alu_op(Sltu)
  val adderB = Mux(subtract, ~io.alu_src2, io.alu_src2)
  val adder = UInt(33 bits)
  adder := io.alu_src1.asUInt.resize(33) + adderB.asUInt.resize(33) + subtract.asUInt.resize(33)

  val addSubResult = adder(31 downto 0).asBits
  val signedLess =
    (io.alu_src1(31) && !io.alu_src2(31)) ||
      ((io.alu_src1(31) === io.alu_src2(31)) && addSubResult(31))
  val sltResult = signedLess.asBits.resize(32)
  val sltuResult = (!adder(32)).asBits.resize(32)

  val andResult = io.alu_src1 & io.alu_src2
  val andnResult = io.alu_src1 & ~io.alu_src2
  val orResult = io.alu_src1 | io.alu_src2
  val ornResult = io.alu_src1 | ~io.alu_src2
  val norResult = ~orResult
  val xorResult = io.alu_src1 ^ io.alu_src2
  val luiResult = io.alu_src2

  val shiftAmount = io.alu_src2(4 downto 0).asUInt
  val sllResult = (io.alu_src1.asUInt |<< shiftAmount).asBits
  val logicalRightResult = (io.alu_src1.asUInt |>> shiftAmount).asBits
  val arithmeticRightResult = (io.alu_src1.asSInt |>> shiftAmount).asBits
  val shiftRightResult = Mux(io.alu_op(Sra), arithmeticRightResult, logicalRightResult)

  private def selected(enabled: Bool, value: Bits): Bits = {
    val result = Bits(32 bits)
    result := B(0, 32 bits)
    when(enabled) {
      result := value
    }
    result
  }

  val resultTerms = Seq(
    selected(io.alu_op(Add) || io.alu_op(Sub), addSubResult),
    selected(io.alu_op(Slt), sltResult),
    selected(io.alu_op(Sltu), sltuResult),
    selected(io.alu_op(And), andResult),
    selected(io.alu_op(Andn), andnResult),
    selected(io.alu_op(Nor), norResult),
    selected(io.alu_op(Or), orResult),
    selected(io.alu_op(Orn), ornResult),
    selected(io.alu_op(Xor), xorResult),
    selected(io.alu_op(Lui), luiResult),
    selected(io.alu_op(Sll), sllResult),
    selected(io.alu_op(Srl) || io.alu_op(Sra), shiftRightResult)
  )

  io.alu_result := resultTerms.reduce(_ | _)
}
