package miku.memory

import miku.core._
import spinal.core._

object L2MshrState extends SpinalEnum {
  val writeback, writebackWait, readRequest, refill, install, respond = newElement()
}

object L2WriteState extends SpinalEnum {
  val idle, lookup, victimWriteback, victimWritebackWait, writeThrough,
    writeThroughWait, install = newElement()
}

final case class L2Mshr(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val state = L2MshrState()
  val lineAddress = UInt(config.xlen bits)
  val criticalBeat = UInt(CacheContract.BeatIndexWidth bits)
  val victimWay = UInt(log2Up(config.level2Cache.ways) bits)
  val victimAddress = UInt(config.xlen bits)
  val refillMask = Bits(CacheContract.BeatsPerLine bits)
  val error = Bool()
  val returnBeat = UInt(CacheContract.BeatIndexWidth bits)
  val returnCount = UInt(CacheContract.BeatIndexWidth bits)
}
