package miku.backend

import miku.core._
import spinal.core._
import spinal.lib._

final class LoadStoreQueueAllocator(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val countWidth = log2Up(config.loadQueueEntries + 1)
  private val loadPrefixWidth = log2Up(config.renameWidth + 1)

  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocateIsLoad = in Bits (config.renameWidth bits)
    val allocateIsStore = in Bits (config.renameWidth bits)
    val allocateLoadIndex = out Vec (UInt(config.loadQueueIndexWidth bits), config.renameWidth)
    val allocateStoreIndex = out Vec (UInt(config.storeQueueIndexWidth bits), config.renameWidth)
    val allocateReady = out Bool ()
    val allocateCapacityReady = out Bool ()
    val allocateAccept = in Bool ()

    val releaseLoadCount = in UInt (log2Up(config.commitWidth + 1) bits)
    val releaseStoreCount = in UInt (log2Up(config.commitWidth + 1) bits)
    val flush = in Bool ()
    val loadOccupancy = out UInt (countWidth bits)
    val storeOccupancy = out UInt (countWidth bits)
  }

  val loadTail = Reg(UInt(config.loadQueueIndexWidth bits)) init (0)
  val storeTail = Reg(UInt(config.storeQueueIndexWidth bits)) init (0)
  val loadOccupancy = Reg(UInt(countWidth bits)) init (0)
  val storeOccupancy = Reg(UInt(countWidth bits)) init (0)

  val loadRequests = io.allocateValid & io.allocateIsLoad
  val storeRequests = io.allocateValid & io.allocateIsStore
  val loadPrefix = Vec(UInt(loadPrefixWidth bits), config.renameWidth + 1)
  val storePrefix = Vec(UInt(loadPrefixWidth bits), config.renameWidth + 1)
  loadPrefix(0) := U(0, loadPrefixWidth bits)
  storePrefix(0) := U(0, loadPrefixWidth bits)
  for (lane <- 0 until config.renameWidth) {
    loadPrefix(lane + 1) := loadPrefix(lane) + loadRequests(lane).asUInt
    storePrefix(lane + 1) := storePrefix(lane) + storeRequests(lane).asUInt
    io.allocateLoadIndex(lane) := (loadTail + loadPrefix(lane)).resized
    io.allocateStoreIndex(lane) := (storeTail + storePrefix(lane)).resized
  }

  val loadRequested = loadPrefix(config.renameWidth)
  val storeRequested = storePrefix(config.renameWidth)
  val loadFree = U(config.loadQueueEntries, countWidth bits) - loadOccupancy
  val storeFree = U(config.storeQueueEntries, countWidth bits) - storeOccupancy
  io.allocateCapacityReady := loadFree >= loadRequested && storeFree >= storeRequested
  io.allocateReady := !io.flush && io.allocateCapacityReady

  when(io.flush) {
    loadTail := U(0, config.loadQueueIndexWidth bits)
    storeTail := U(0, config.storeQueueIndexWidth bits)
    loadOccupancy := U(0, countWidth bits)
    storeOccupancy := U(0, countWidth bits)
  }.otherwise {
    when(io.allocateAccept) {
      loadTail := loadTail + loadRequested
      storeTail := storeTail + storeRequested
    }
    loadOccupancy :=
      loadOccupancy + Mux(io.allocateAccept, loadRequested, 0) - io.releaseLoadCount
    storeOccupancy :=
      storeOccupancy + Mux(io.allocateAccept, storeRequested, 0) - io.releaseStoreCount
  }

  io.loadOccupancy := loadOccupancy
  io.storeOccupancy := storeOccupancy
}
