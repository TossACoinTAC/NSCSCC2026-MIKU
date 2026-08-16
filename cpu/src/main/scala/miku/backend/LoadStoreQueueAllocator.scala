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
    val allocateOldestReady = out Bool ()
    val allocateAccept = in Bool ()
    val allocateAcceptMask = in Bits (config.renameWidth bits)

    val releaseLoadValid = in Bits (config.commitWidth bits)
    val releaseStoreValid = in Bits (config.commitWidth bits)
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
  io.allocateOldestReady := !io.flush &&
    loadFree >= io.allocateIsLoad(0).asUInt && storeFree >= io.allocateIsStore(0).asUInt

  val loadReleased = CountOne(io.releaseLoadValid)
  val storeReleased = CountOne(io.releaseStoreValid)
  val acceptedMask = Bits(config.renameWidth bits)
  acceptedMask := Mux(
    io.allocateAcceptMask.orR,
    io.allocateAcceptMask,
    Mux(io.allocateAccept, io.allocateValid, B(0, config.renameWidth bits))
  )
  when(io.flush) {
    loadTail := U(0, config.loadQueueIndexWidth bits)
    storeTail := U(0, config.storeQueueIndexWidth bits)
    loadOccupancy := U(0, countWidth bits)
    storeOccupancy := U(0, countWidth bits)
  }.otherwise {
    val acceptedLoadCount = CountOne(acceptedMask & io.allocateIsLoad)
    val acceptedStoreCount = CountOne(acceptedMask & io.allocateIsStore)
    when(acceptedMask.orR) {
      loadTail := loadTail + acceptedLoadCount
      storeTail := storeTail + acceptedStoreCount
    }
    loadOccupancy := loadOccupancy + acceptedLoadCount - loadReleased
    storeOccupancy := storeOccupancy + acceptedStoreCount - storeReleased
  }

  io.loadOccupancy := loadOccupancy
  io.storeOccupancy := storeOccupancy
}
