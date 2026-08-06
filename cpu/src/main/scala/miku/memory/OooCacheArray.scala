package miku.memory

import miku.core._
import spinal.core._

final class OooCacheArray(
    geometry: OooCacheGeometry,
    addressWidth: Int = 32
) extends Component {
  require(geometry.lineBytes == OooCacheContract.LineBytes)

  private val wayWidth = log2Up(geometry.ways)
  private val indexWidth = geometry.indexWidth
  private val offsetWidth = geometry.offsetWidth
  private val tagWidth = addressWidth - indexWidth - offsetWidth
  private val tagEntryWidth = tagWidth + 2

  val io = new Bundle {
    val lookupValid = in Bool ()
    val lookupAddress = in UInt (addressWidth bits)
    val lookupReady = out Bool ()
    val responseValid = out Bool ()
    val hit = out Bool ()
    val hitWay = out UInt (wayWidth bits)
    val hitData = out Bits (OooCacheContract.LineBits bits)
    val wayData = out Vec (Bits(OooCacheContract.LineBits bits), geometry.ways)
    val victimWay = out UInt (wayWidth bits)
    val victimValid = out Bool ()
    val victimDirty = out Bool ()
    val victimAddress = out UInt (addressWidth bits)
    val victimData = out Bits (OooCacheContract.LineBits bits)

    val writeValid = in Bool ()
    val writeIndex = in UInt (indexWidth bits)
    val writeWay = in UInt (wayWidth bits)
    val writeTag = in UInt (tagWidth bits)
    val writeData = in Bits (OooCacheContract.LineBits bits)
    val writeEntryValid = in Bool ()
    val writeDirty = in Bool ()

    val maintenanceReadValid = in Bool ()
    val maintenanceReadIndex = in UInt (indexWidth bits)
    val maintenanceReadWay = in UInt (wayWidth bits)
    val maintenanceReadReady = out Bool ()
    val maintenanceResponseValid = out Bool ()
    val maintenanceEntryValid = out Bool ()
    val maintenanceEntryDirty = out Bool ()
    val maintenanceEntryAddress = out UInt (addressWidth bits)
    val maintenanceEntryData = out Bits (OooCacheContract.LineBits bits)

    val invalidate = in Bool ()
    val invalidateBusy = out Bool ()
  }

  val tagMemories = Array.fill(geometry.ways)(Mem(Bits(tagEntryWidth bits), geometry.sets))
  val dataMemories = Array.fill(geometry.ways)(
    Mem(Bits(OooCacheContract.LineBits bits), geometry.sets)
  )
  val replacement = Vec.fill(geometry.sets)(Reg(UInt(wayWidth bits)) init (0))

  val invalidating = RegInit(True)
  val invalidateIndex = Reg(UInt(indexWidth bits)) init (0)
  when(io.invalidate) {
    invalidating := True
    invalidateIndex := U(0, indexWidth bits)
  }.elsewhen(invalidating) {
    when(invalidateIndex === U(geometry.sets - 1, indexWidth bits)) {
      invalidating := False
    }.otherwise {
      invalidateIndex := invalidateIndex + 1
    }
  }

  val requestIndex = io.lookupAddress(offsetWidth + indexWidth - 1 downto offsetWidth)
  val requestTag = io.lookupAddress(addressWidth - 1 downto offsetWidth + indexWidth)
  val maintenanceFire = io.maintenanceReadValid && !invalidating
  val lookupFire = io.lookupValid && !invalidating && !io.maintenanceReadValid
  val capturedIndex = Reg(UInt(indexWidth bits))
  val capturedTag = Reg(UInt(tagWidth bits))
  val capturedMaintenanceIndex = Reg(UInt(indexWidth bits))
  val capturedMaintenanceWay = Reg(UInt(wayWidth bits))
  when(lookupFire) {
    capturedIndex := requestIndex
    capturedTag := requestTag
  }
  when(maintenanceFire) {
    capturedMaintenanceIndex := io.maintenanceReadIndex
    capturedMaintenanceWay := io.maintenanceReadWay
  }
  val responseValid = RegNext(lookupFire) init (False)
  val maintenanceResponseValid = RegNext(maintenanceFire) init (False)

  val tagRead = Vec(Bits(tagEntryWidth bits), geometry.ways)
  val dataRead = Vec(Bits(OooCacheContract.LineBits bits), geometry.ways)
  for (way <- 0 until geometry.ways) {
    val externalWrite = io.writeValid && io.writeWay === U(way, wayWidth bits) && !invalidating
    val tagWriteEnable = invalidating || externalWrite
    val tagWriteAddress = Mux(invalidating, invalidateIndex, io.writeIndex)
    val tagWriteData = Mux(
      invalidating,
      B(0, tagEntryWidth bits),
      io.writeTag.asBits ## io.writeEntryValid.asBits ## io.writeDirty.asBits
    )
    tagMemories(way).write(
      address = tagWriteAddress,
      data = tagWriteData,
      enable = tagWriteEnable
    )
    dataMemories(way).write(
      address = io.writeIndex,
      data = io.writeData,
      enable = externalWrite
    )
    tagRead(way) := tagMemories(way).readSync(
      address = Mux(io.maintenanceReadValid, io.maintenanceReadIndex, requestIndex),
      enable = (lookupFire || maintenanceFire) && !tagWriteEnable
    )
    dataRead(way) := dataMemories(way).readSync(
      address = Mux(io.maintenanceReadValid, io.maintenanceReadIndex, requestIndex),
      enable = (lookupFire || maintenanceFire) && !externalWrite
    )
  }

  when(io.writeValid && !invalidating) {
    replacement(io.writeIndex) := (io.writeWay + 1).resized
  }

  val hitMask = Bits(geometry.ways bits)
  val invalidMask = Bits(geometry.ways bits)
  for (way <- 0 until geometry.ways) {
    val valid = tagRead(way)(1)
    val tag = tagRead(way)(tagEntryWidth - 1 downto 2).asUInt
    hitMask(way) := responseValid && valid && tag === capturedTag
    invalidMask(way) := responseValid && !valid
  }

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(wayWidth bits)
    selected := U(0, wayWidth bits)
    for (way <- (0 until geometry.ways).reverse) {
      when(mask(way)) { selected := U(way, wayWidth bits) }
    }
    selected
  }

  val hitWay = selectLowest(hitMask)
  val invalidWay = selectLowest(invalidMask)
  val victimWay = Mux(invalidMask.orR, invalidWay, replacement(capturedIndex))
  val victimTag = tagRead(victimWay)(tagEntryWidth - 1 downto 2).asUInt

  io.lookupReady := !invalidating && !io.maintenanceReadValid
  io.responseValid := responseValid
  io.hit := hitMask.orR
  io.hitWay := hitWay
  io.hitData := dataRead(hitWay)
  io.wayData := dataRead
  io.victimWay := victimWay
  io.victimValid := tagRead(victimWay)(1)
  io.victimDirty := tagRead(victimWay)(0)
  io.victimAddress := (victimTag ## capturedIndex ## U(0, offsetWidth bits)).asUInt
  io.victimData := dataRead(victimWay)
  io.maintenanceReadReady := !invalidating
  io.maintenanceResponseValid := maintenanceResponseValid
  io.maintenanceEntryValid := tagRead(capturedMaintenanceWay)(1)
  io.maintenanceEntryDirty := tagRead(capturedMaintenanceWay)(0)
  io.maintenanceEntryAddress :=
    (tagRead(capturedMaintenanceWay)(tagEntryWidth - 1 downto 2).asUInt ##
      capturedMaintenanceIndex ## U(0, offsetWidth bits)).asUInt
  io.maintenanceEntryData := dataRead(capturedMaintenanceWay)
  io.invalidateBusy := invalidating
}
