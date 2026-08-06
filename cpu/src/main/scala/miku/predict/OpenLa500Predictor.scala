package miku.predict

import miku.config.CoreConfig
import spinal.core._
import spinal.lib._

final case class PredictorLookupRequest() extends Bundle {
  val pc = UInt(32 bits)
}

final case class PredictorPrediction() extends Bundle {
  val taken = Bool()
  val target = UInt(32 bits)
  val legacyIndex = UInt(5 bits)
}

final case class PredictorUpdate() extends Bundle {
  val popReturnStack = Bool()
  val pushReturnStack = Bool()
  val addEntry = Bool()
  val predictionError = Bool()
  val predictionRight = Bool()
  val targetError = Bool()
  val actualTaken = Bool()
  val actualTarget = UInt(32 bits)
  val pc = UInt(32 bits)
  val legacyIndex = UInt(5 bits)
}

/** Active official 32-entry branch predictor.
  *
  * Lookup is a one-cycle Flow contract. Updates have no backpressure and are applied exactly once
  * when their Flow is valid. The component owns the BTB, return-site matcher, LFSR, and return
  * stack. A reset invalidates all observable state; payload storage behind invalid bits need not be
  * reset.
  */
final class OpenLa500Predictor(config: CoreConfig = CoreConfig.Locked) extends Component {
  private val BtbIndexWidth = log2Up(config.btbEntries)
  private val ReturnSiteIndexWidth = log2Up(config.rasEntries)
  private val ReturnStackDepth = config.returnStackDepth
  private val ReturnStackIndexWidth = log2Up(ReturnStackDepth)
  private val ReturnDepthWidth = log2Up(ReturnStackDepth + 1)

  require(config.btbEntries == 32, "the active predictor requires the official 32-entry BTB")
  require(
    config.rasEntries == 16,
    "the active predictor requires the official 16-entry return-site matcher"
  )
  require(config.returnStackDepth == 8, "the active predictor requires an eight-entry return stack")

  val io = new Bundle {
    val lookup = slave Flow (PredictorLookupRequest())
    val prediction = master Flow (PredictorPrediction())
    val update = slave Flow (PredictorUpdate())
  }

  private def selectLowest(mask: Bits, width: Int): UInt = {
    val selected = UInt(width bits)
    selected := 0
    for (index <- (0 until mask.getWidth).reverse) {
      when(mask(index)) {
        selected := U(index, width bits)
      }
    }
    selected
  }

  val branchValid = Vec.fill(config.btbEntries)(Reg(Bool()) init (False))
  val branchPc = Vec.fill(config.btbEntries)(Reg(UInt(32 bits)))
  val branchTarget = Vec.fill(config.btbEntries)(Reg(UInt(32 bits)))
  val branchCounter = Vec.fill(config.btbEntries)(Reg(UInt(2 bits)))

  val returnSiteValid = Vec.fill(config.rasEntries)(Reg(Bool()) init (False))
  val returnSitePc = Vec.fill(config.rasEntries)(Reg(UInt(32 bits)))
  val returnStack = Vec.fill(ReturnStackDepth)(Reg(UInt(32 bits)))
  val returnDepth = Reg(UInt(ReturnDepthWidth bits)) init (0)

  val lfsr = Reg(Bits(6 bits)) init (B(0x22, 6 bits))
  val lfsrNext = Bits(6 bits)
  lfsrNext(0) := lfsr(5)
  lfsrNext(1) := lfsr(0)
  lfsrNext(2) := lfsr(1)
  lfsrNext(3) := lfsr(2) ^ lfsr(5)
  lfsrNext(4) := lfsr(3) ^ lfsr(5)
  lfsrNext(5) := lfsr(4)
  lfsr := lfsrNext

  val lookupValid = RegNext(io.lookup.valid) init (False)
  val lookupPc = Reg(UInt(32 bits)) init (U(config.resetVector, 32 bits))
  when(io.lookup.valid) {
    lookupPc := io.lookup.payload.pc
  }

  val branchLookupMatches = Bits(config.btbEntries bits)
  val branchLookupTaken = Vec(Bool(), config.btbEntries)
  for (index <- 0 until config.btbEntries) {
    branchLookupMatches(index) :=
      lookupValid && branchValid(index) && branchPc(index) === lookupPc
    branchLookupTaken(index) := branchCounter(index)(1)
  }
  val branchLookupHit = branchLookupMatches.orR
  val branchLookupIndex = selectLowest(branchLookupMatches, BtbIndexWidth)

  val returnLookupMatches = Bits(config.rasEntries bits)
  for (index <- 0 until config.rasEntries) {
    returnLookupMatches(index) :=
      lookupValid && returnSiteValid(index) && returnSitePc(index) === lookupPc
  }
  val returnStackEmpty = returnDepth === 0
  val returnStackFull = returnDepth === U(ReturnStackDepth, ReturnDepthWidth bits)
  val returnLookupHit = returnLookupMatches.orR && !returnStackEmpty
  val returnLookupIndex = selectLowest(returnLookupMatches, ReturnSiteIndexWidth)
  val returnTopIndex = returnDepth(ReturnStackIndexWidth - 1 downto 0) - 1
  val returnTarget = returnStack(returnTopIndex)
  val predictedBranchTarget = branchTarget(branchLookupIndex)

  io.prediction.valid := returnLookupHit || branchLookupHit
  io.prediction.payload.taken :=
    returnLookupHit || (branchLookupHit && branchLookupTaken(branchLookupIndex))
  io.prediction.payload.target := 0
  io.prediction.payload.legacyIndex := 0
  when(branchLookupHit) {
    io.prediction.payload.target := predictedBranchTarget
    io.prediction.payload.legacyIndex := branchLookupIndex(4 downto 0)
  }
  when(returnLookupHit) {
    io.prediction.payload.target := returnTarget
    io.prediction.payload.legacyIndex := returnLookupIndex.resize(5)
  }

  val invalidBranchEntries = Bits(config.btbEntries bits)
  val stronglyUntakenEntries = Bits(config.btbEntries bits)
  for (index <- 0 until config.btbEntries) {
    invalidBranchEntries(index) := !branchValid(index)
    stronglyUntakenEntries(index) := branchValid(index) && branchCounter(index) === 0
  }
  val invalidBranchIndex = selectLowest(invalidBranchEntries, BtbIndexWidth)
  val stronglyUntakenIndex = selectLowest(stronglyUntakenEntries, BtbIndexWidth)
  val branchReplacementIndex = UInt(BtbIndexWidth bits)
  branchReplacementIndex := lfsr(BtbIndexWidth - 1 downto 0).asUInt
  when(invalidBranchEntries.orR) {
    branchReplacementIndex := invalidBranchIndex
  }.elsewhen(stronglyUntakenEntries.orR) {
    branchReplacementIndex := stronglyUntakenIndex
  }

  val invalidReturnSites = Bits(config.rasEntries bits)
  for (index <- 0 until config.rasEntries) {
    invalidReturnSites(index) := !returnSiteValid(index)
  }
  val invalidReturnIndex = selectLowest(invalidReturnSites, ReturnSiteIndexWidth)
  val returnReplacementIndex = UInt(ReturnSiteIndexWidth bits)
  returnReplacementIndex := lfsr(ReturnSiteIndexWidth - 1 downto 0).asUInt
  when(invalidReturnSites.orR) {
    returnReplacementIndex := invalidReturnIndex
  }

  when(io.update.valid) {
    when(!io.update.payload.popReturnStack) {
      when(io.update.payload.addEntry) {
        branchValid(branchReplacementIndex) := True
        branchPc(branchReplacementIndex) := io.update.payload.pc
        branchTarget(branchReplacementIndex) := io.update.payload.actualTarget
        branchCounter(branchReplacementIndex) := 2
      }.elsewhen(io.update.payload.targetError) {
        branchTarget(io.update.payload.legacyIndex) := io.update.payload.actualTarget
        branchCounter(io.update.payload.legacyIndex) := 2
      }.elsewhen(io.update.payload.predictionError || io.update.payload.predictionRight) {
        when(io.update.payload.actualTaken) {
          when(branchCounter(io.update.payload.legacyIndex) =/= 3) {
            branchCounter(io.update.payload.legacyIndex) :=
              branchCounter(io.update.payload.legacyIndex) + 1
          }
        } otherwise {
          when(branchCounter(io.update.payload.legacyIndex) =/= 0) {
            branchCounter(io.update.payload.legacyIndex) :=
              branchCounter(io.update.payload.legacyIndex) - 1
          }
        }
      }
    } otherwise {
      when(io.update.payload.addEntry) {
        returnSiteValid(returnReplacementIndex) := True
        returnSitePc(returnReplacementIndex) := io.update.payload.pc
      }
    }

    when(io.update.payload.pushReturnStack && !returnStackFull) {
      returnStack(returnDepth(ReturnStackIndexWidth - 1 downto 0)) := io.update.payload.pc + 4
      returnDepth := returnDepth + 1
    }.elsewhen(io.update.payload.popReturnStack && !returnStackEmpty) {
      returnDepth := returnDepth - 1
    }
  }
}
