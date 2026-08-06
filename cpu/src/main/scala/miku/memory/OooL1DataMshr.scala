package miku.memory

import miku.core._
import spinal.core._

object OooL1DataMshrState extends SpinalEnum {
  val writeback, writebackWait, readRequest, refill, install, respond = newElement()
}

final case class OooL1DataMshr(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val state = OooL1DataMshrState()
  val lineAddress = UInt(config.xlen bits)
  val criticalBeat = UInt(OooCacheContract.BeatIndexWidth bits)
  val victimWay = UInt(log2Up(config.dataCache.ways) bits)
  val victimAddress = UInt(config.xlen bits)
  val refillMask = Bits(OooCacheContract.BeatsPerLine bits)
  val refillError = Bool()
  val storeByteMask = Bits(OooCacheContract.LineBytes bits)
}

final case class OooL1DataMshrWaiter(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val mshrId = UInt(log2Up(config.mshrEntries) bits)
  val physicalAddress = UInt(config.xlen bits)
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
}

final case class OooL1DataLookupRequest(config: OooCoreConfig) extends Bundle {
  val physicalAddress = UInt(config.xlen bits)
  val isWrite = Bool()
  val byteMask = Bits(config.xlen / 8 bits)
  val writeData = Bits(config.xlen bits)
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
}
