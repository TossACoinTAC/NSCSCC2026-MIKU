package miku.predict

import miku.core._
import spinal.core._

object OooPredictedBranchType {
  val Width = 3
  def conditional: UInt = U(0, Width bits)
  def direct: UInt = U(1, Width bits)
  def indirect: UInt = U(2, Width bits)
  def ret: UInt = U(3, Width bits)
  def call: UInt = U(4, Width bits)
}

final case class OooBankedFetchPrediction(config: OooCoreConfig) extends Bundle {
  val hit = Bool()
  val phtValid = Bool()
  val branchType = UInt(OooPredictedBranchType.Width bits)
  val phtState = UInt(2 bits)
  val phtIndex = UInt(10 bits)
  val target = UInt(config.xlen bits)
}

/** Four-bank synchronous BTB/PHT with speculative and architectural history state.
  *
  * A 16-byte group reads all lane banks in parallel. BTB and PHT arrays are cleared through their
  * write ports after reset, preserving block-RAM inference. Speculative GHR/RAS state advances when
  * a predicted group enters L1I and is restored from architectural state on a precise redirect.
  */
final class OooBankedFetchPredictor(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit,
    btbEntriesPerBank: Int = 128,
    phtEntriesPerBank: Int = 1024,
    historyWidth: Int = 8,
    rasDepth: Int = 8
) extends Component {
  private val fetchGroupOffsetWidth = log2Up(config.fetchWidth * 4)
  private val bankWidth = log2Up(config.fetchWidth)
  private val btbRowWidth = log2Up(btbEntriesPerBank)
  private val phtRowWidth = log2Up(phtEntriesPerBank)
  private val btbTagWidth = config.xlen - fetchGroupOffsetWidth - btbRowWidth
  private val rasIndexWidth = log2Up(rasDepth)
  private val rasCountWidth = log2Up(rasDepth + 1)

  private val btbTargetLsb = 0
  private val btbTypeLsb = config.xlen
  private val btbTagLsb = btbTypeLsb + OooPredictedBranchType.Width
  private val btbValidBit = btbTagLsb + btbTagWidth
  private val btbDirectionTrainedBit = btbValidBit + 1
  private val btbStaticTakenBit = btbDirectionTrainedBit + 1
  private val btbEntryWidth = btbStaticTakenBit + 1

  require(config.fetchWidth == 4)
  require(btbEntriesPerBank == 128)
  require(phtEntriesPerBank == 1024)
  require(historyWidth == 8)
  require(rasDepth == 8)

  val io = new Bundle {
    val lookupValid = in Bool ()
    val lookupPc = in UInt (config.xlen bits)
    val responseValid = out Bool ()
    val prediction = out Vec (OooBankedFetchPrediction(config), config.fetchWidth)
    val tableUpdateReady = out Bool ()

    val btbUpdateValid = in Bool ()
    val btbUpdatePc = in UInt (config.xlen bits)
    val btbUpdateTarget = in UInt (config.xlen bits)
    val btbUpdateType = in UInt (OooPredictedBranchType.Width bits)
    val btbUpdateDirectionTrained = in Bool ()

    val phtUpdateValid = in Bool ()
    val phtUpdatePc = in UInt (config.xlen bits)
    val phtUpdateIndex = in UInt (phtRowWidth bits)
    val phtUpdateOldState = in UInt (2 bits)
    val phtUpdateOldValid = in Bool ()
    val phtUpdateTaken = in Bool ()

    val speculativeHistoryValid = in Bool ()
    val speculativeHistoryTaken = in Bool ()
    val speculativeRasPush = in Bool ()
    val speculativeRasPop = in Bool ()
    val speculativeReturnAddress = in UInt (config.xlen bits)

    val commitRasPush = in Bool ()
    val commitRasPop = in Bool ()
    val commitReturnAddress = in UInt (config.xlen bits)
    val architecturalHistoryValid = in Bits (config.commitWidth bits)
    val architecturalHistoryTaken = in Bits (config.commitWidth bits)
    val architecturalRasPush = in Bits (config.commitWidth bits)
    val architecturalRasPop = in Bits (config.commitWidth bits)
    val architecturalReturnAddress = in Vec (UInt(config.xlen bits), config.commitWidth)
    val flush = in Bool ()
  }

  val btbBanks = Array.fill(config.fetchWidth)(
    Mem(Bits(btbEntryWidth bits), btbEntriesPerBank)
  )
  val phtBanks = Array.fill(config.fetchWidth)(
    Mem(Bits(2 bits), phtEntriesPerBank)
  )

  val invalidating = RegInit(True)
  val invalidateRow = Reg(UInt(btbRowWidth bits)) init (0)
  when(invalidating) {
    when(invalidateRow === U(btbEntriesPerBank - 1, btbRowWidth bits)) {
      invalidating := False
    }.otherwise {
      invalidateRow := invalidateRow + 1
    }
  }
  io.tableUpdateReady := !invalidating

  val speculativeGhr = Reg(Bits(historyWidth bits)) init (0)
  val architecturalGhr = Reg(Bits(historyWidth bits)) init (0)

  val speculativeRas = Vec.fill(rasDepth)(Reg(UInt(config.xlen bits)) init (0))
  val architecturalRas = Vec.fill(rasDepth)(Reg(UInt(config.xlen bits)) init (0))
  val speculativeRasCount = Reg(UInt(rasCountWidth bits)) init (0)
  val architecturalRasCount = Reg(UInt(rasCountWidth bits)) init (0)

  val architecturalGhrStage = Vec(Bits(historyWidth bits), config.commitWidth + 1)
  architecturalGhrStage(0) := architecturalGhr
  for (lane <- 0 until config.commitWidth) {
    architecturalGhrStage(lane + 1) := architecturalGhrStage(lane)
    when(io.architecturalHistoryValid(lane)) {
      architecturalGhrStage(lane + 1) :=
        architecturalGhrStage(lane)(historyWidth - 2 downto 0) ##
          io.architecturalHistoryTaken(lane).asBits
    }
  }
  when(io.architecturalHistoryValid.orR) {
    architecturalGhr := architecturalGhrStage(config.commitWidth)
  }
  when(io.speculativeHistoryValid) {
    speculativeGhr := speculativeGhr(historyWidth - 2 downto 0) ##
      io.speculativeHistoryTaken.asBits
  }

  val architecturalRasCountStage = Vec(UInt(rasCountWidth bits), config.commitWidth + 1)
  val architecturalRasStage = Array.fill(config.commitWidth + 1)(
    Vec(UInt(config.xlen bits), rasDepth)
  )
  architecturalRasCountStage(0) := architecturalRasCount
  for (entry <- 0 until rasDepth) {
    architecturalRasStage(0)(entry) := architecturalRas(entry)
  }
  for (lane <- 0 until config.commitWidth) {
    architecturalRasCountStage(lane + 1) := architecturalRasCountStage(lane)
    for (entry <- 0 until rasDepth) {
      architecturalRasStage(lane + 1)(entry) := architecturalRasStage(lane)(entry)
    }
    when(io.architecturalRasPush(lane) && !io.architecturalRasPop(lane)) {
      when(architecturalRasCountStage(lane) =/= U(rasDepth, rasCountWidth bits)) {
        architecturalRasStage(lane + 1)(
          architecturalRasCountStage(lane)(rasIndexWidth - 1 downto 0)
        ) := io.architecturalReturnAddress(lane)
        architecturalRasCountStage(lane + 1) := architecturalRasCountStage(lane) + 1
      }
    }.elsewhen(io.architecturalRasPop(lane) && !io.architecturalRasPush(lane)) {
      when(architecturalRasCountStage(lane) =/= 0) {
        architecturalRasCountStage(lane + 1) := architecturalRasCountStage(lane) - 1
      }
    }
  }
  when(io.architecturalRasPush.orR || io.architecturalRasPop.orR) {
    architecturalRasCount := architecturalRasCountStage(config.commitWidth)
    for (entry <- 0 until rasDepth) {
      architecturalRas(entry) := architecturalRasStage(config.commitWidth)(entry)
    }
  }
  when(io.speculativeRasPush && !io.speculativeRasPop) {
    when(speculativeRasCount =/= U(rasDepth, rasCountWidth bits)) {
      speculativeRas(speculativeRasCount(rasIndexWidth - 1 downto 0)) :=
        io.speculativeReturnAddress
      speculativeRasCount := speculativeRasCount + 1
    }
  }.elsewhen(io.speculativeRasPop && !io.speculativeRasPush) {
    when(speculativeRasCount =/= 0) {
      speculativeRasCount := speculativeRasCount - 1
    }
  }
  when(io.flush) {
    speculativeGhr := architecturalGhrStage(config.commitWidth)
    speculativeRasCount := architecturalRasCountStage(config.commitWidth)
    for (entry <- 0 until rasDepth) {
      speculativeRas(entry) := architecturalRasStage(config.commitWidth)(entry)
    }
  }

  val lookupFire = io.lookupValid && !invalidating
  val lookupBtbRow = io.lookupPc(
    fetchGroupOffsetWidth + btbRowWidth - 1 downto fetchGroupOffsetWidth
  )
  val lookupTag = io
    .lookupPc(
      config.xlen - 1 downto fetchGroupOffsetWidth + btbRowWidth
    )
    .asBits
  val lookupGhr = Bits(historyWidth bits)
  lookupGhr := speculativeGhr
  when(io.speculativeHistoryValid) {
    lookupGhr := speculativeGhr(historyWidth - 2 downto 0) ##
      io.speculativeHistoryTaken.asBits
  }
  val lookupPhtIndex = lookupGhr(4 downto 0) ##
    io.lookupPc(fetchGroupOffsetWidth + 4 downto fetchGroupOffsetWidth)
  val capturedTag = Reg(Bits(btbTagWidth bits)) init (0)
  val capturedPhtIndex = Reg(UInt(phtRowWidth bits)) init (0)
  // The synchronous table ports read continuously after initialization.  lookupFire qualifies
  // the response separately, so speculative/invalid addresses remain architecturally invisible
  // without placing the full frontend acceptance cone on every BRAM enable and metadata CE.
  capturedTag := lookupTag
  capturedPhtIndex := lookupPhtIndex.asUInt
  io.responseValid := RegNext(lookupFire) init (False)

  val btbUpdateBank = io.btbUpdatePc(fetchGroupOffsetWidth - 1 downto 2)
  val btbUpdateRow = io.btbUpdatePc(
    fetchGroupOffsetWidth + btbRowWidth - 1 downto fetchGroupOffsetWidth
  )
  val btbUpdateTag = io
    .btbUpdatePc(
      config.xlen - 1 downto fetchGroupOffsetWidth + btbRowWidth
    )
    .asBits
  val btbUpdateEntry = B(0, btbEntryWidth bits)
  btbUpdateEntry(btbTargetLsb + config.xlen - 1 downto btbTargetLsb) :=
    io.btbUpdateTarget.asBits
  btbUpdateEntry(
    btbTypeLsb + OooPredictedBranchType.Width - 1 downto btbTypeLsb
  ) := io.btbUpdateType.asBits
  btbUpdateEntry(btbTagLsb + btbTagWidth - 1 downto btbTagLsb) := btbUpdateTag
  btbUpdateEntry(btbValidBit) := True
  btbUpdateEntry(btbDirectionTrainedBit) := io.btbUpdateDirectionTrained
  // BTFNT is invariant for a learned PC/target pair. Compute it on the rare
  // update path instead of rebuilding a 32-bit comparator on every BTB hit.
  btbUpdateEntry(btbStaticTakenBit) := io.btbUpdateTarget < io.btbUpdatePc

  val phtUpdateBank = io.phtUpdatePc(fetchGroupOffsetWidth - 1 downto 2)
  val phtNextState = UInt(2 bits)
  // An untrained BTB entry uses BTFNT, so stale power-up PHT data is never observed. Its first
  // precise update installs a weak state directly and removes the 1024-cycle PHT reset sweep.
  phtNextState := Mux(io.phtUpdateTaken, U(2, 2 bits), U(1, 2 bits))
  when(io.phtUpdateOldValid && io.phtUpdateTaken && io.phtUpdateOldState =/= 3) {
    phtNextState := io.phtUpdateOldState + 1
  }.elsewhen(io.phtUpdateOldValid && !io.phtUpdateTaken && io.phtUpdateOldState =/= 0) {
    phtNextState := io.phtUpdateOldState - 1
  }.elsewhen(io.phtUpdateOldValid) {
    phtNextState := io.phtUpdateOldState
  }

  val btbRead = Vec(Bits(btbEntryWidth bits), config.fetchWidth)
  val phtRead = Vec(Bits(2 bits), config.fetchWidth)
  for (bank <- 0 until config.fetchWidth) {
    val btbWrite = io.btbUpdateValid &&
      btbUpdateBank === U(bank, bankWidth bits) && !invalidating
    btbBanks(bank).write(
      address = Mux(invalidating, invalidateRow, btbUpdateRow),
      data = Mux(invalidating, B(0, btbEntryWidth bits), btbUpdateEntry),
      enable = invalidating || btbWrite
    )
    btbRead(bank) := btbBanks(bank).readSync(
      address = lookupBtbRow,
      enable = !invalidating
    )

    val phtWrite = io.phtUpdateValid &&
      phtUpdateBank === U(bank, bankWidth bits)
    phtBanks(bank).write(
      address = io.phtUpdateIndex,
      data = phtNextState.asBits,
      enable = phtWrite
    )
    phtRead(bank) := phtBanks(bank).readSync(
      address = lookupPhtIndex.asUInt,
      enable = !invalidating
    )

    val entryTag = btbRead(bank)(btbTagLsb + btbTagWidth - 1 downto btbTagLsb)
    io.prediction(bank).hit := io.responseValid && btbRead(bank)(btbValidBit) &&
      entryTag === capturedTag
    io.prediction(bank).phtValid := io.responseValid &&
      btbRead(bank)(btbDirectionTrainedBit)
    io.prediction(bank).branchType :=
      btbRead(bank)(btbTypeLsb + OooPredictedBranchType.Width - 1 downto btbTypeLsb).asUInt
    io.prediction(bank).phtState := Mux(
      btbRead(bank)(btbDirectionTrainedBit),
      phtRead(bank),
      Mux(btbRead(bank)(btbStaticTakenBit), B"10", B"01")
    ).asUInt
    io.prediction(bank).phtIndex := capturedPhtIndex
    io.prediction(bank).target :=
      btbRead(bank)(btbTargetLsb + config.xlen - 1 downto btbTargetLsb).asUInt
    when(
      io.prediction(bank).branchType === OooPredictedBranchType.ret &&
        speculativeRasCount =/= 0
    ) {
      io.prediction(bank).target :=
        speculativeRas(speculativeRasCount(rasIndexWidth - 1 downto 0) - 1)
    }
  }
}
