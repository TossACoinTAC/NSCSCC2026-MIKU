package miku.predict

import miku.core._
import spinal.core._
import spinal.lib._

object PredictedBranchType {
  val Width = 3
  def conditional: UInt = U(0, Width bits)
  def direct: UInt = U(1, Width bits)
  def indirect: UInt = U(2, Width bits)
  def ret: UInt = U(3, Width bits)
  def call: UInt = U(4, Width bits)
}

final case class BankedFetchPrediction(config: OooCoreConfig) extends Bundle {
  val hit = Bool()
  val phtValid = Bool()
  val branchType = UInt(PredictedBranchType.Width bits)
  val phtState = UInt(2 bits)
  val phtIndex = UInt(config.predictorPhtIndexWidth bits)
  val target = UInt(config.xlen bits)
}

/** Four-bank synchronous BTB/PHT with speculative and architectural history state.
  *
  * A 16-byte group reads all lane banks in parallel. BTB and PHT arrays are cleared through their
  * write ports after reset, preserving block-RAM inference. Speculative GHR/RAS state advances when
  * a predicted group enters L1I and is restored from architectural state on a precise redirect.
  */
final class BankedFetchPredictor(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit,
    btbEntriesPerBank: Int = 128,
    rasDepth: Int = 8
) extends Component {
  private val phtEntriesPerBank = config.predictorPhtEntriesPerBank
  private val historyWidth = config.predictorHistoryWidth
  private val fetchGroupOffsetWidth = log2Up(config.fetchWidth * 4)
  private val btbRowWidth = log2Up(btbEntriesPerBank)
  private val phtRowWidth = log2Up(phtEntriesPerBank)
  private val btbTagWidth = config.xlen - fetchGroupOffsetWidth - btbRowWidth
  private val rasIndexWidth = log2Up(rasDepth)
  private val rasCountWidth = log2Up(rasDepth + 1)

  private val btbTargetLsb = 0
  private val btbTypeLsb = config.xlen
  private val btbTagLsb = btbTypeLsb + PredictedBranchType.Width
  private val btbValidBit = btbTagLsb + btbTagWidth
  private val btbDirectionTrainedBit = btbValidBit + 1
  private val btbStaticTakenBit = btbDirectionTrainedBit + 1
  private val btbEntryWidth = btbStaticTakenBit + 1

  require(config.fetchWidth == 4)
  require(btbEntriesPerBank == 128)
  require(rasDepth == 8)

  // Vivado maps UInt/Bits equality onto a CARRY4 compare chain.  The BTB tag
  // hit feeds the live history-turnover prediction cone, so keep the 21-bit
  // match on a short XOR/NOR LUT tree.  Same cycle behavior, faster routing.
  private def lutTreeEqual(a: Bits, b: Bits): Bool =
    !((a.asUInt ^ b.asUInt).asBits.orR)

  val io = new Bundle {
    val lookupValid = in Bool ()
    val lookupPc = in UInt (config.xlen bits)
    val responseValid = out Bool ()
    val prediction = out Vec (BankedFetchPrediction(config), config.fetchWidth)
    val tableUpdateReady = out Bool ()

    val btbUpdateValid = in Bool ()
    val btbUpdatePc = in UInt (config.xlen bits)
    val btbUpdateTarget = in UInt (config.xlen bits)
    val btbUpdateType = in UInt (PredictedBranchType.Width bits)
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

  // Clear the widened PHT through its normal write ports without propagating its 4096-cycle
  // startup into the predictor-update queue or ROB commit.  Fetch remains active after the BTB
  // sweep and uses BTFNT until every row has a deterministic weak-not-taken state.  Precise PHT
  // updates during this non-architectural warmup window are intentionally discarded.
  val phtInvalidating = RegInit(if (config.enableLargeGshare) True else False)
  val phtInvalidateRow = Reg(UInt(phtRowWidth bits)) init (0)
  when(phtInvalidating) {
    when(phtInvalidateRow === U(phtEntriesPerBank - 1, phtRowWidth bits)) {
      phtInvalidating := False
    }.otherwise {
      phtInvalidateRow := phtInvalidateRow + 1
    }
  }

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

  val architecturalRasCountOneHot = Vec(Bits((rasDepth + 1) bits), config.commitWidth + 1)
  val architecturalRasPushAccepted = Bits(config.commitWidth bits)
  val architecturalRasPopAccepted = Bits(config.commitWidth bits)
  architecturalRasCountOneHot(0) := UIntToOh(architecturalRasCount, rasDepth + 1)
  for (lane <- 0 until config.commitWidth) {
    val pushOnly = io.architecturalRasPush(lane) && !io.architecturalRasPop(lane)
    val popOnly = io.architecturalRasPop(lane) && !io.architecturalRasPush(lane)
    architecturalRasPushAccepted(lane) := pushOnly &&
      !architecturalRasCountOneHot(lane)(rasDepth)
    architecturalRasPopAccepted(lane) := popOnly &&
      !architecturalRasCountOneHot(lane)(0)
    architecturalRasCountOneHot(lane + 1) := architecturalRasCountOneHot(lane)
    when(architecturalRasPushAccepted(lane)) {
      architecturalRasCountOneHot(lane + 1) :=
        (architecturalRasCountOneHot(lane) |<< 1).resize(rasDepth + 1)
    }.elsewhen(architecturalRasPopAccepted(lane)) {
      architecturalRasCountOneHot(lane + 1) :=
        (architecturalRasCountOneHot(lane) |>> 1).resize(rasDepth + 1)
    }
  }
  val architecturalRasNextCount = OHToUInt(architecturalRasCountOneHot(config.commitWidth))
  val architecturalRasNext = Vec(UInt(config.xlen bits), rasDepth)
  for (entry <- 0 until rasDepth) {
    val writeMatch = Bits(config.commitWidth bits)
    for (lane <- 0 until config.commitWidth) {
      writeMatch(lane) := architecturalRasPushAccepted(lane) &&
        architecturalRasCountOneHot(lane)(entry)
    }
    architecturalRasNext(entry) := architecturalRas(entry)
    for (lane <- 0 until config.commitWidth) {
      when(writeMatch(lane)) {
        architecturalRasNext(entry) := io.architecturalReturnAddress(lane)
      }
    }
  }
  when(io.architecturalRasPush.orR || io.architecturalRasPop.orR) {
    architecturalRasCount := architecturalRasNextCount
    for (entry <- 0 until rasDepth) {
      architecturalRas(entry) := architecturalRasNext(entry)
    }
  }
  val effectiveSpeculativeRasPush = Bool()
  val effectiveSpeculativeRasPop = Bool()
  val effectiveSpeculativeReturnAddress = UInt(config.xlen bits)
  if (config.enableRegisteredSpeculativeRasUpdate) {
    val stagedRasPush = RegInit(False)
    val stagedRasPop = RegInit(False)
    val stagedReturnAddress = Reg(UInt(config.xlen bits)) init (0)
    when(io.flush) {
      stagedRasPush := False
      stagedRasPop := False
      stagedReturnAddress := 0
    }.otherwise {
      stagedRasPush := io.speculativeRasPush
      stagedRasPop := io.speculativeRasPop
      stagedReturnAddress := io.speculativeReturnAddress
    }
    effectiveSpeculativeRasPush := stagedRasPush
    effectiveSpeculativeRasPop := stagedRasPop
    effectiveSpeculativeReturnAddress := stagedReturnAddress
  } else {
    effectiveSpeculativeRasPush := io.speculativeRasPush
    effectiveSpeculativeRasPop := io.speculativeRasPop
    effectiveSpeculativeReturnAddress := io.speculativeReturnAddress
  }
  when(effectiveSpeculativeRasPush && !effectiveSpeculativeRasPop) {
    when(speculativeRasCount =/= U(rasDepth, rasCountWidth bits)) {
      speculativeRas(speculativeRasCount(rasIndexWidth - 1 downto 0)) :=
        effectiveSpeculativeReturnAddress
      speculativeRasCount := speculativeRasCount + 1
    }
  }.elsewhen(effectiveSpeculativeRasPop && !effectiveSpeculativeRasPush) {
    when(speculativeRasCount =/= 0) {
      speculativeRasCount := speculativeRasCount - 1
    }
  }
  when(io.flush) {
    speculativeGhr := architecturalGhrStage(config.commitWidth)
    speculativeRasCount := architecturalRasNextCount
    for (entry <- 0 until rasDepth) {
      speculativeRas(entry) := architecturalRasNext(entry)
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
  val lookupPhtIndex = UInt(phtRowWidth bits)
  if (config.enableLargeGshare) {
    val foldedLower = lookupGhr.asUInt.resize(phtRowWidth)
    val foldedUpper = UInt(phtRowWidth bits)
    foldedUpper := 0
    if (historyWidth > phtRowWidth) {
      foldedUpper := lookupGhr(historyWidth - 1 downto phtRowWidth).asUInt
        .resize(phtRowWidth)
    }
    lookupPhtIndex := foldedLower ^
      foldedUpper ^
      io.lookupPc(fetchGroupOffsetWidth + phtRowWidth - 1 downto fetchGroupOffsetWidth)
  } else {
    lookupPhtIndex := (lookupGhr(4 downto 0) ##
      io.lookupPc(fetchGroupOffsetWidth + 4 downto fetchGroupOffsetWidth)).asUInt
  }
  val capturedTag = Reg(Bits(btbTagWidth bits)) init (0)
  val capturedPhtIndex = Reg(UInt(phtRowWidth bits)) init (0)
  // The synchronous table ports read continuously after initialization.  lookupFire qualifies
  // the response separately, so speculative/invalid addresses remain architecturally invisible
  // without placing the full frontend acceptance cone on every BRAM enable and metadata CE.
  capturedTag := lookupTag
  capturedPhtIndex := lookupPhtIndex
  io.responseValid := RegNext(lookupFire) init (False)

  val btbUpdateBank = io.btbUpdatePc(fetchGroupOffsetWidth - 1 downto 2)
  val btbUpdateBankMask = UIntToOh(btbUpdateBank, config.fetchWidth)
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
    btbTypeLsb + PredictedBranchType.Width - 1 downto btbTypeLsb
  ) := io.btbUpdateType.asBits
  btbUpdateEntry(btbTagLsb + btbTagWidth - 1 downto btbTagLsb) := btbUpdateTag
  btbUpdateEntry(btbValidBit) := True
  btbUpdateEntry(btbDirectionTrainedBit) := io.btbUpdateDirectionTrained
  // BTFNT is invariant for a learned PC/target pair. Compute it on the rare
  // update path instead of rebuilding a 32-bit comparator on every BTB hit.
  btbUpdateEntry(btbStaticTakenBit) := io.btbUpdateTarget < io.btbUpdatePc

  val phtUpdateBank = io.phtUpdatePc(fetchGroupOffsetWidth - 1 downto 2)
  val phtUpdateBankMask = UIntToOh(phtUpdateBank, config.fetchWidth)
  val phtNextState = UInt(2 bits)
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
  val speculativeRasTop = UInt(config.xlen bits)
  speculativeRasTop := 0
  when(speculativeRasCount =/= 0) {
    speculativeRasTop :=
      speculativeRas(speculativeRasCount(rasIndexWidth - 1 downto 0) - 1)
  }
  for (bank <- 0 until config.fetchWidth) {
    val btbWrite = io.btbUpdateValid && btbUpdateBankMask(bank) && !invalidating
    btbBanks(bank).write(
      address = Mux(invalidating, invalidateRow, btbUpdateRow),
      data = Mux(invalidating, B(0, btbEntryWidth bits), btbUpdateEntry),
      enable = invalidating || btbWrite
    )
    btbRead(bank) := btbBanks(bank).readSync(
      address = lookupBtbRow,
      enable = !invalidating
    )

    val phtWrite = io.phtUpdateValid && phtUpdateBankMask(bank) && !phtInvalidating
    phtBanks(bank).write(
      address = Mux(phtInvalidating, phtInvalidateRow, io.phtUpdateIndex),
      data = Mux(phtInvalidating, B"01", phtNextState.asBits),
      enable = phtInvalidating || phtWrite
    )
    phtRead(bank) := phtBanks(bank).readSync(
      address = lookupPhtIndex,
      enable = !invalidating
    )

    val entryTag = btbRead(bank)(btbTagLsb + btbTagWidth - 1 downto btbTagLsb)
    io.prediction(bank).hit := io.responseValid && btbRead(bank)(btbValidBit) &&
      lutTreeEqual(entryTag, capturedTag)
    if (config.enableLargeGshare) {
      io.prediction(bank).phtValid := io.prediction(bank).hit && !phtInvalidating
    } else {
      io.prediction(bank).phtValid := io.responseValid &&
        btbRead(bank)(btbDirectionTrainedBit)
    }
    io.prediction(bank).branchType :=
      btbRead(bank)(btbTypeLsb + PredictedBranchType.Width - 1 downto btbTypeLsb).asUInt
    val learnedPhtState = Bits(2 bits)
    learnedPhtState := phtRead(bank)
    val learnedPhtStateValid = Bool()
    if (config.enableLargeGshare) {
      learnedPhtStateValid := io.prediction(bank).phtValid
    } else {
      learnedPhtStateValid := btbRead(bank)(btbDirectionTrainedBit)
    }
    io.prediction(bank).phtState := Mux(
      learnedPhtStateValid,
      learnedPhtState,
      Mux(btbRead(bank)(btbStaticTakenBit), B"10", B"01")
    ).asUInt
    io.prediction(bank).phtIndex := capturedPhtIndex
    io.prediction(bank).target :=
      btbRead(bank)(btbTargetLsb + config.xlen - 1 downto btbTargetLsb).asUInt
    when(
      io.prediction(bank).branchType === PredictedBranchType.ret &&
        speculativeRasCount =/= 0
    ) {
      io.prediction(bank).target := speculativeRasTop
    }
  }
}
