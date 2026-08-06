package miku.compat

import spinal.core._
import spinal.lib._

/** AXI3 read-address payload for the locked chiplab boundary. */
final case class Axi3ReadAddress() extends Bundle {
  val id = Bits(4 bits)
  val address = Bits(32 bits)
  val len = Bits(8 bits)
  val size = Bits(3 bits)
  val burst = Bits(2 bits)
  val lock = Bits(2 bits)
  val cache = Bits(4 bits)
  val prot = Bits(3 bits)
}

/** AXI3 read-data payload. */
final case class Axi3ReadData() extends Bundle {
  val id = Bits(4 bits)
  val data = Bits(32 bits)
  val response = Bits(2 bits)
  val last = Bool()
}

/** AXI3 write-address payload for the locked chiplab boundary. */
final case class Axi3WriteAddress() extends Bundle {
  val id = Bits(4 bits)
  val address = Bits(32 bits)
  val len = Bits(8 bits)
  val size = Bits(3 bits)
  val burst = Bits(2 bits)
  val lock = Bits(2 bits)
  val cache = Bits(4 bits)
  val prot = Bits(3 bits)
}

/** AXI3 write-data payload. WID is intentionally retained; stock AXI4 omits it. */
final case class Axi3WriteData() extends Bundle {
  val id = Bits(4 bits)
  val data = Bits(32 bits)
  val byteMask = Bits(4 bits)
  val last = Bool()
}

/** AXI3 write-response payload. */
final case class Axi3WriteResponse() extends Bundle {
  val id = Bits(4 bits)
  val response = Bits(2 bits)
}

/** Typed AXI3/WID interface. Payloads stay directionless; only this boundary assigns ownership. */
final case class Axi3Compat() extends Bundle with IMasterSlave {
  val ar = Stream(Axi3ReadAddress())
  val r = Stream(Axi3ReadData())
  val aw = Stream(Axi3WriteAddress())
  val w = Stream(Axi3WriteData())
  val b = Stream(Axi3WriteResponse())

  override def asMaster(): Unit = {
    master(ar)
    slave(r)
    master(aw)
    master(w)
    slave(b)
  }
}
