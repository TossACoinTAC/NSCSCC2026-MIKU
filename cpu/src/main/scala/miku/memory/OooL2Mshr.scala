package miku.memory

import miku.core._
import spinal.core._

object OooL2MshrState extends SpinalEnum {
  val writeback, writebackWait, readRequest, refill, install, respond = newElement()
}

object OooL2WriteState extends SpinalEnum {
  val idle, lookup, victimWriteback, victimWritebackWait, writeThrough,
    writeThroughWait, install = newElement()
}

final case class OooL2Mshr(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val state = OooL2MshrState()
  val lineAddress = UInt(config.xlen bits)
  val criticalBeat = UInt(OooCacheContract.BeatIndexWidth bits)
  val victimWay = UInt(log2Up(config.level2Cache.ways) bits)
  val victimAddress = UInt(config.xlen bits)
  val refillMask = Bits(OooCacheContract.BeatsPerLine bits)
  val error = Bool()
  val returnBeat = UInt(OooCacheContract.BeatIndexWidth bits)
  val returnCount = UInt(OooCacheContract.BeatIndexWidth bits)
}
