package miku.execute

import spinal.core._

/** One-stage implementation of `a158aa8:rtl/mul.v`.
  *
  * Inputs are sampled on every rising edge of `mul_clk` for which `reset` is low. The sampled
  * product is visible after that edge and remains stable while `reset` is high or until the next
  * active edge. `reset` is therefore a synchronous hold input, not an initialization reset; the
  * result is intentionally undefined before the first active capture. There is no backpressure or
  * flush behavior.
  */
final class RegisteredMultiplier extends Component {
  val io = new Bundle {
    val mul_clk = in Bool ()
    val reset = in Bool ()
    val mul_signed = in Bool ()
    val x = in Bits (32 bits)
    val y = in Bits (32 bits)
    val result = out Bits (64 bits)
  }

  noIoPrefix()

  val unsignedProduct = (io.x.asUInt * io.y.asUInt).resize(64).asBits
  val signedProduct = (io.x.asSInt * io.y.asSInt).resize(64).asBits
  val selectedProduct = Mux(io.mul_signed, signedProduct, unsignedProduct)

  val mulClockDomain = ClockDomain(
    clock = io.mul_clk,
    config = ClockDomainConfig(clockEdge = RISING)
  )

  val capture = new ClockingArea(mulClockDomain) {
    val product = Reg(Bits(64 bits))
    when(!io.reset) {
      product := selectedProduct
    }
  }

  io.result := capture.product
}
