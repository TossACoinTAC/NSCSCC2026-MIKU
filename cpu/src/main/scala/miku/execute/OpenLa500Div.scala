package miku.execute

import spinal.core._

/** Cycle-compatible implementation of a158aa8:rtl/div.v.
  *
  * A request and its operands remain asserted through the result-capture edge. complete is the E33
  * notification pulse; quotient and remainder are jointly valid after E34. E35 is a cleanup edge,
  * so a continuously asserted request starts its next operation at E36. Dropping div before E34
  * synchronously aborts the operation. All state belongs to the explicit div_clk, active-high
  * synchronous-reset domain.
  */
final class OpenLa500Div extends Component {
  val io = new Bundle {
    val div_clk = in Bool ()
    val reset = in Bool ()
    val div = in Bool ()
    val div_signed = in Bool ()
    val x = in Bits (32 bits)
    val y = in Bits (32 bits)
    val s = out Bits (32 bits)
    val r = out Bits (32 bits)
    val complete = out Bool ()
  }

  noIoPrefix()

  val divClockDomain = ClockDomain(
    clock = io.div_clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val logic = new ClockingArea(divClockDomain) {
    val quotient = Reg(UInt(32 bits)) init (0)
    val partialRemainder = Reg(UInt(32 bits)) init (0)
    val capturedRemainder = Reg(UInt(32 bits)) init (0)
    val count = Reg(UInt(8 bits)) init (32)
    val signedBuffer = Reg(Bool()) init (False)
    val xNegativeBuffer = Reg(Bool()) init (False)
    val yNegativeBuffer = Reg(Bool()) init (False)

    val complete = count === U(0xff, 8 bits)
    val cleanup = count === U(0xf0, 8 bits)
    val useBufferedSigns = complete || cleanup

    val effectiveSigned = Mux(useBufferedSigns, signedBuffer, io.div_signed)
    val effectiveXNegative = Mux(useBufferedSigns, xNegativeBuffer, io.x(31))
    val effectiveYNegative = Mux(useBufferedSigns, yNegativeBuffer, io.y(31))

    val xMagnitude = UInt(32 bits)
    val yMagnitude = UInt(32 bits)
    xMagnitude := io.x.asUInt
    yMagnitude := io.y.asUInt
    when(effectiveSigned && io.x(31)) {
      xMagnitude := U(0, 32 bits) - io.x.asUInt
    }
    when(effectiveSigned && io.y(31)) {
      yMagnitude := U(0, 32 bits) - io.y.asUInt
    }

    val unsignedX = U(0, 1 bits) ## xMagnitude
    val dividendBit = unsignedX(count(5 downto 0))
    val shiftedRemainder = (partialRemainder ## dividendBit).asUInt
    val trialDifference = shiftedRemainder - yMagnitude.resize(33)
    val trialNegative = trialDifference(32)

    when(io.div) {
      signedBuffer := io.div_signed
      xNegativeBuffer := io.x(31)
      yNegativeBuffer := io.y(31)
    }

    when(!io.div || cleanup) {
      count := 32
      partialRemainder := 0
    }.elsewhen(!count(7)) {
      quotient := (quotient(30 downto 0) ## !trialNegative).asUInt
      partialRemainder := Mux(
        trialNegative,
        shiftedRemainder(31 downto 0),
        trialDifference(31 downto 0)
      )
      count := count - 1
    }.otherwise {
      capturedRemainder := partialRemainder
      count := U(0xf0, 8 bits)
    }

    val signedQuotient = UInt(32 bits)
    val signedRemainder = UInt(32 bits)
    signedQuotient := quotient
    signedRemainder := capturedRemainder
    when(effectiveSigned && (effectiveXNegative =/= effectiveYNegative)) {
      signedQuotient := U(0, 32 bits) - quotient
    }
    when(effectiveSigned && effectiveXNegative) {
      signedRemainder := U(0, 32 bits) - capturedRemainder
    }
  }

  io.s := logic.signedQuotient.asBits
  io.r := logic.signedRemainder.asBits
  io.complete := logic.complete
}
