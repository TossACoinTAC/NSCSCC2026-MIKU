package miku.frontend

import miku.backend._
import miku.core._
import miku.memory._
import miku.observe.PerfObservationV1
import miku.predict._
import miku.privileged._
import spinal.core._
import spinal.lib._

final case class FrontendSlot(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val instruction = Bits(32 bits)
  val exception = ExceptionMetadata()
  val predictedTaken = Bool()
  val predictedTarget = UInt(config.xlen bits)
  val predictorMetadata = Bits(16 bits)
}

/** Four-slot fetch frontend with an eight-entry fetch-to-decode buffer.
  *
  * The cache returns one aligned 16-byte group. Slots preceding an unaligned redirect target are
  * discarded, and the remaining stream is compacted into the fixed three-wide decoder. Translated
  * memory attributes are carried to the hierarchy so uncached fetches bypass every cache level.
  */
final class OooFrontend(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val pointerWidth = log2Up(config.instructionBufferEntries)
  private val countWidth = log2Up(config.instructionBufferEntries + 1)
  private val enqueueCountWidth = log2Up(config.fetchWidth + 1)
  private val fetchGroupBytes = config.fetchWidth * 4
  private val fetchGroupOffsetWidth = log2Up(fetchGroupBytes)
  require(fetchGroupBytes == 16)

  val io = new Bundle {
    val translationRequest = master(Stream(TranslationRequest(config)))
    val translationResponse = slave(Stream(TranslationResponse(config)))

    val cacheRequestValid = out Bool ()
    val cacheUncachedRequestValid = out Bool ()
    val cacheRequest = out(InstructionCacheRequest(config))
    val cacheRequestReady = in Bool ()
    val cacheHitResponsePending = in Bool ()
    val cacheResponseValid = in Bool ()
    val cacheResponse = in(InstructionCacheResponse(config))
    val cacheKill = out Bool ()

    val decodeValid = out Bits (config.decodeWidth bits)
    val decoded = out Vec (DecodedMicroOp(config), config.decodeWidth)
    val decodeReady = in Bits (config.decodeWidth bits)

    val redirectValid = in Bool ()
    val redirectTarget = in UInt (config.xlen bits)
    val predictorUpdateValid = in Bool ()
    val predictorUpdatePc = in UInt (config.xlen bits)
    val predictorUpdateTaken = in Bool ()
    val predictorUpdateTarget = in UInt (config.xlen bits)
    val predictorUpdateType = in UInt (PredictedBranchType.Width bits)
    val predictorUpdateMetadata = in Bits (16 bits)
    val predictorUpdateIsCall = in Bool ()
    val predictorUpdateIsReturn = in Bool ()
    val predictorUpdateReady = out Bool ()
    val predictorRetireValid = in Bits (config.commitWidth bits)
    val predictorRetireTaken = in Bits (config.commitWidth bits)
    val predictorRetireType = in Vec (
      UInt(PredictedBranchType.Width bits),
      config.commitWidth
    )
    val predictorRetireIsCall = in Bits (config.commitWidth bits)
    val predictorRetireIsReturn = in Bits (config.commitWidth bits)
    val predictorRetireReturnAddress = in Vec (UInt(config.xlen bits), config.commitWidth)
    val privilege = in Bits (2 bits)
    val interruptPending = in Bool ()

    val fetchPc = out UInt (config.xlen bits)
    val occupancy = out UInt (countWidth bits)
    val predictorDebugTaken = out Bool ()
    val predictorDebugHit = out Bool ()
    val predictorDebugType = out UInt (PredictedBranchType.Width bits)
    val predictorDebugPhtState = out UInt (2 bits)
  }

  val entries = Vec.fill(config.instructionBufferEntries)(Reg(FrontendSlot(config)))
  for (entry <- entries) {
    entry.pc.addAttribute("extract_reset", "no")
    entry.instruction.addAttribute("extract_reset", "no")
    entry.exception.valid.addAttribute("extract_reset", "no")
    entry.exception.ecode.addAttribute("extract_reset", "no")
    entry.exception.esubcode.addAttribute("extract_reset", "no")
    entry.exception.badVAddrValid.addAttribute("extract_reset", "no")
    entry.exception.badVAddr.addAttribute("extract_reset", "no")
    entry.exception.tlbRefill.addAttribute("extract_reset", "no")
    entry.predictedTaken.addAttribute("extract_reset", "no")
    entry.predictedTarget.addAttribute("extract_reset", "no")
    entry.predictorMetadata.addAttribute("extract_reset", "no")
  }
  val head = Reg(UInt(pointerWidth bits)) init (0)
  val tail = Reg(UInt(pointerWidth bits)) init (0)
  val count = Reg(UInt(countWidth bits)) init (0)
  val nextFetchPc = Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val translationOutstanding = RegInit(False)
  val translationDropPending = RegInit(False)
  val translatedRequestValid = RegInit(False)
  val translatedPc =
    Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val translatedPhysicalAddress = Reg(UInt(config.xlen bits))
  val translatedUncached = Reg(Bool())
  val translatedExceptionValid = RegInit(False)
  val translatedException = Reg(ExceptionMetadata())
  val cacheOutstanding = RegInit(False)
  val cacheDropPending = RegInit(False)
  val cacheResponseContextPending = RegInit(False)
  val predictionCorrectionFlushPending = RegInit(False)
  val predictionCorrectionNextPc =
    Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  // Preload the single active owner before allocation.  Idle requests track nextFetchPc, while a
  // turnover response installs its predicted successor independently of translator ready.  This
  // preserves the acceptance timing cut without leaving a bank selector on every owner consumer.
  val translationPc =
    Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val cachePc = Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val pendingResponsePc = Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val predictionPendingValid = RegInit(False)
  val pendingPrediction = Vec.fill(config.fetchWidth)(Reg(BankedFetchPrediction(config)))
  val translatedPrediction = Vec.fill(config.fetchWidth)(Reg(BankedFetchPrediction(config)))
  val cachePrediction = Vec.fill(config.fetchWidth)(Reg(BankedFetchPrediction(config)))
  val pendingResponsePrediction =
    Vec.fill(config.fetchWidth)(Reg(BankedFetchPrediction(config)))
  for (lane <- 0 until config.fetchWidth) {
    pendingPrediction(lane).hit.init(False)
    pendingPrediction(lane).phtValid.init(False)
    pendingPrediction(lane).branchType.init(PredictedBranchType.conditional)
    pendingPrediction(lane).phtState.init(1)
    pendingPrediction(lane).phtIndex.init(0)
    pendingPrediction(lane).target.init(0)
    translatedPrediction(lane).hit.init(False)
    translatedPrediction(lane).phtValid.init(False)
    translatedPrediction(lane).branchType.init(PredictedBranchType.conditional)
    translatedPrediction(lane).phtState.init(1)
    translatedPrediction(lane).phtIndex.init(0)
    translatedPrediction(lane).target.init(0)
    cachePrediction(lane).hit.init(False)
    cachePrediction(lane).phtValid.init(False)
    cachePrediction(lane).branchType.init(PredictedBranchType.conditional)
    cachePrediction(lane).phtState.init(1)
    cachePrediction(lane).phtIndex.init(0)
    cachePrediction(lane).target.init(0)
    pendingResponsePrediction(lane).hit.init(False)
    pendingResponsePrediction(lane).phtValid.init(False)
    pendingResponsePrediction(lane).branchType.init(PredictedBranchType.conditional)
    pendingResponsePrediction(lane).phtState.init(1)
    pendingResponsePrediction(lane).phtIndex.init(0)
    pendingResponsePrediction(lane).target.init(0)
  }
  val cachePredictedTaken = RegInit(False)
  val cachePredictedLane = Reg(UInt(config.fetchSlotWidth bits)) init (0)
  val cachePredictedTarget =
    Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val pendingResponsePredictedTaken = RegInit(False)
  val pendingResponsePredictedLane = Reg(UInt(config.fetchSlotWidth bits)) init (0)
  val pendingResponsePredictedTarget =
    Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  // Prefill the inactive response owner from the registered active owner every cycle.  Only the
  // narrow pending-valid bit is qualified by the synchronous L1I hit, keeping tag-hit control out
  // of the wide predictor-context register enables.  On a turnover edge these assignments observe
  // the old cache owner while requestFire installs the younger owner into cache* registers.
  pendingResponsePc := cachePc
  pendingResponsePredictedTaken := cachePredictedTaken
  pendingResponsePredictedLane := cachePredictedLane
  pendingResponsePredictedTarget := cachePredictedTarget
  for (lane <- 0 until config.fetchWidth) {
    pendingResponsePrediction(lane) := cachePrediction(lane)
  }
  io.predictorDebugTaken := cachePredictedTaken
  io.predictorDebugHit := cachePrediction(0).hit
  io.predictorDebugType := cachePrediction(0).branchType
  io.predictorDebugPhtState := cachePrediction(0).phtState

  val targetPredictor = new BankedFetchPredictor(config)
  val targetPredictorLookupPc = UInt(config.xlen bits)
  targetPredictor.io.lookupPc := targetPredictorLookupPc

  val predictionForTranslation = Vec(BankedFetchPrediction(config), config.fetchWidth)
  for (lane <- 0 until config.fetchWidth) {
    predictionForTranslation(lane) := pendingPrediction(lane)
    when(!predictionPendingValid) {
      predictionForTranslation(lane).hit := False
      predictionForTranslation(lane).phtValid := False
      predictionForTranslation(lane).branchType := PredictedBranchType.conditional
      predictionForTranslation(lane).phtState := 1
      predictionForTranslation(lane).phtIndex := 0
      predictionForTranslation(lane).target := 0
    }
    when(targetPredictor.io.responseValid) {
      predictionForTranslation(lane) := targetPredictor.io.prediction(lane)
    }
  }

  io.translationResponse.ready := !translatedRequestValid && !translatedExceptionValid &&
    (translationOutstanding || translationDropPending)
  val translationResponseFire = io.translationResponse.valid && io.translationResponse.ready
  // A delayed response must belong to the request currently held by the frontend.  This
  // protects the virtual-PC tag from being paired with a physical address from a stale request
  // after a redirect or a translator response race.
  val translationResponseMatches =
    io.translationResponse.virtualAddress === translationPc
  val translationResponseBypassValid = if (config.enableFrontendTranslationResponseBypass) {
    translationResponseFire && translationOutstanding && translationResponseMatches &&
      !io.translationResponse.cancelled && !io.translationResponse.exception.valid &&
      !io.redirectValid
  } else {
    False
  }
  // A successful translation response is the ownership handoff for this fetch group.  Keep the
  // predictor turnover token tied to that narrow, matched event instead of the later L1I request
  // handshake, whose ready/hit feedback otherwise reaches the predictor RAM address path.
  val translationResponseAcceptedValid = translationResponseFire && translationOutstanding &&
    translationResponseMatches && !io.translationResponse.cancelled &&
    !io.translationResponse.exception.valid && !io.redirectValid
  // Once a response matches, the registered owner is the same architectural PC and is already
  // local to the frontend.  Keep the live response VA only in the identity comparator so it does
  // not also drive the cache/predictor payload path.
  val acceptedTranslationPc = translationPc
  val requestTranslationPc = Mux(
    translationResponseBypassValid,
    acceptedTranslationPc,
    Mux(translatedRequestValid, translatedPc, translationPc)
  )
  val requestPrediction = Vec(BankedFetchPrediction(config), config.fetchWidth)
  for (lane <- 0 until config.fetchWidth) {
    requestPrediction(lane) := translatedPrediction(lane)
    when(translationResponseAcceptedValid) {
      requestPrediction(lane) := predictionForTranslation(lane)
    }
  }

  val translatedGroupBase = requestTranslationPc &
    U(((BigInt(1) << config.xlen) - 1) ^ (fetchGroupBytes - 1), config.xlen bits)
  val translatedFirstSlot = requestTranslationPc(fetchGroupOffsetWidth - 1 downto 2)
  val translatedPredictionTaken = Vec(Bool(), config.fetchWidth)
  val translatedConditionalSeen = Vec(Bool(), config.fetchWidth)
  val earlierTranslatedPredictionTaken = Vec(Bool(), config.fetchWidth + 1)
  val rawTranslatedPredictionTaken = Vec(Bool(), config.fetchWidth)
  for (lane <- 0 until config.fetchWidth) {
    val laneInGroup = U(lane, config.fetchSlotWidth bits) >= translatedFirstSlot
    val laneTaken = requestPrediction(lane).branchType =/=
      PredictedBranchType.conditional || requestPrediction(lane).phtState(1)
    rawTranslatedPredictionTaken(lane) := requestPrediction(lane).hit && laneTaken && laneInGroup
  }
  earlierTranslatedPredictionTaken(0) := False
  if (config.enableBalancedFrontendPredictionSelect) {
    val lowerTaken = rawTranslatedPredictionTaken(0) || rawTranslatedPredictionTaken(1)
    translatedPredictionTaken(0) := rawTranslatedPredictionTaken(0)
    translatedPredictionTaken(1) := rawTranslatedPredictionTaken(1) &&
      !rawTranslatedPredictionTaken(0)
    translatedPredictionTaken(2) := rawTranslatedPredictionTaken(2) && !lowerTaken
    translatedPredictionTaken(3) := rawTranslatedPredictionTaken(3) && !lowerTaken &&
      !rawTranslatedPredictionTaken(2)
    for (lane <- 0 until config.fetchWidth) {
      val laneInGroup = U(lane, config.fetchSlotWidth bits) >= translatedFirstSlot
      val conditional = requestPrediction(lane).hit && laneInGroup &&
        requestPrediction(lane).branchType === PredictedBranchType.conditional
      val earlierTaken = lane match {
        case 0 => False
        case 1 => rawTranslatedPredictionTaken(0)
        case 2 => lowerTaken
        case 3 => lowerTaken || rawTranslatedPredictionTaken(2)
      }
      translatedConditionalSeen(lane) := conditional && !earlierTaken
    }
    earlierTranslatedPredictionTaken(1) := rawTranslatedPredictionTaken(0)
    earlierTranslatedPredictionTaken(2) := lowerTaken
    earlierTranslatedPredictionTaken(3) := lowerTaken || rawTranslatedPredictionTaken(2)
    earlierTranslatedPredictionTaken(4) := lowerTaken || rawTranslatedPredictionTaken(2) ||
      rawTranslatedPredictionTaken(3)
  } else {
    for (lane <- 0 until config.fetchWidth) {
      translatedPredictionTaken(lane) := rawTranslatedPredictionTaken(lane) &&
        !earlierTranslatedPredictionTaken(lane)
      translatedConditionalSeen(lane) := requestPrediction(lane).hit &&
        requestPrediction(lane).branchType === PredictedBranchType.conditional &&
        U(lane, config.fetchSlotWidth bits) >= translatedFirstSlot &&
        !earlierTranslatedPredictionTaken(lane)
      earlierTranslatedPredictionTaken(lane + 1) :=
        earlierTranslatedPredictionTaken(lane) || translatedPredictionTaken(lane)
    }
  }
  val requestPredictedTaken = Bool()
  val requestPredictedPc = UInt(config.xlen bits)
  val requestPredictedTarget = UInt(config.xlen bits)
  val requestPredictedType = UInt(PredictedBranchType.Width bits)
  if (config.enableBalancedFrontendPredictionSelect) {
    val lowerTaken = rawTranslatedPredictionTaken(0) || rawTranslatedPredictionTaken(1)
    val upperTaken = rawTranslatedPredictionTaken(2) || rawTranslatedPredictionTaken(3)
    val lowerLane = Mux(
      rawTranslatedPredictionTaken(0),
      U(0, config.fetchSlotWidth bits),
      U(1, config.fetchSlotWidth bits)
    )
    val upperLane = Mux(
      rawTranslatedPredictionTaken(2),
      U(2, config.fetchSlotWidth bits),
      U(3, config.fetchSlotWidth bits)
    )
    val predictedLane = Mux(lowerTaken, lowerLane, upperLane)
    val lowerTarget = Mux(
      rawTranslatedPredictionTaken(0),
      requestPrediction(0).target,
      requestPrediction(1).target
    )
    val upperTarget = Mux(
      rawTranslatedPredictionTaken(2),
      requestPrediction(2).target,
      requestPrediction(3).target
    )
    val lowerType = Mux(
      rawTranslatedPredictionTaken(0),
      requestPrediction(0).branchType,
      requestPrediction(1).branchType
    )
    val upperType = Mux(
      rawTranslatedPredictionTaken(2),
      requestPrediction(2).branchType,
      requestPrediction(3).branchType
    )
    requestPredictedTaken := lowerTaken || upperTaken
    requestPredictedPc := translatedGroupBase
    requestPredictedTarget := translatedGroupBase + fetchGroupBytes
    requestPredictedType := PredictedBranchType.conditional
    when(requestPredictedTaken) {
      requestPredictedPc(fetchGroupOffsetWidth - 1 downto 2) := predictedLane
      requestPredictedTarget := Mux(lowerTaken, lowerTarget, upperTarget)
      requestPredictedType := Mux(lowerTaken, lowerType, upperType)
    }
  } else {
    requestPredictedTaken := earlierTranslatedPredictionTaken(config.fetchWidth)
    requestPredictedPc := translatedGroupBase
    requestPredictedTarget := translatedGroupBase + fetchGroupBytes
    requestPredictedType := PredictedBranchType.conditional
    for (lane <- (0 until config.fetchWidth).reverse) {
      when(translatedPredictionTaken(lane)) {
        requestPredictedPc := translatedGroupBase + lane * 4
        requestPredictedTarget := requestPrediction(lane).target
        requestPredictedType := requestPrediction(lane).branchType
      }
    }
  }
  val requestHistoryValid = translatedConditionalSeen.asBits.orR
  // A call link is one of four fixed offsets inside an aligned 16-byte fetch
  // group.  Build it from the group base and lane so the selected branch PC
  // does not feed a full-width +4 carry chain on the speculative RAS path.
  val requestPredictedLane =
    requestPredictedPc(fetchGroupOffsetWidth - 1 downto 2)
  val sameGroupReturnAddress = UInt(config.xlen bits)
  sameGroupReturnAddress := translatedGroupBase
  sameGroupReturnAddress(fetchGroupOffsetWidth - 1 downto 2) :=
    (requestPredictedLane + 1).resized
  val requestPredictedReturnAddress = Mux(
    requestPredictedLane === U(config.fetchWidth - 1, config.fetchSlotWidth bits),
    translatedGroupBase + fetchGroupBytes,
    sameGroupReturnAddress
  )

  // The predictor can fold this group's speculative GHR update into a same-cycle lookup; its RAS
  // update reaches the synchronous lookup response on the following edge.  The history-turnover
  // switch retains the earlier conservative mode for timing and cycle A/B.
  val requestPredictedNextPc = UInt(config.xlen bits)
  requestPredictedNextPc := Mux(
    requestPredictedTaken,
    requestPredictedTarget,
    translatedGroupBase + fetchGroupBytes
  )
  val translationTurnoverEligible = if (config.enableFrontendTranslationTurnover) {
    if (config.enableFrontendHistoryTurnover) {
      translationResponseBypassValid
    } else {
      translationResponseBypassValid && !requestHistoryValid &&
        requestPredictedType =/= PredictedBranchType.call &&
        requestPredictedType =/= PredictedBranchType.ret
    }
  } else {
    False
  }
  val translationTurnoverTokenValid = if (config.enableFrontendTranslationTurnover) {
    translationResponseAcceptedValid
  } else {
    False
  }

  val freeSlots = U(config.instructionBufferEntries, countWidth bits) - count
  val translationExceptionFire = translationResponseFire && translationOutstanding &&
    !io.redirectValid && translationResponseMatches && !io.translationResponse.cancelled &&
    io.translationResponse.exception.valid
  val predictionCorrectionOnResponse = Bool()
  val correctionKillsCachedRequest = Bool()
  val cachedCorrectionKillPending = RegNext(correctionKillsCachedRequest) init (False)
  val cacheRequestCapacityAvailable = Bool()
  // Cached requests can be killed at the L1 boundary, but an already accepted uncached AXI burst
  // still completes.  Do not let that stale response satisfy a newer request after redirect.
  val pendingCacheResponseMatches =
    io.cacheResponse.virtualAddress === pendingResponsePc
  val activeCacheResponseMatches = io.cacheResponse.virtualAddress === cachePc
  // Responses are ordered at the single-request L1I boundary.  Select the wide prediction owner
  // from registered state; address matching qualifies acceptance without feeding the comparator
  // result back through every prediction lane.
  val responseSelectsPendingContext = cacheResponseContextPending
  val responseUsesPendingContext = io.cacheResponseValid && responseSelectsPendingContext &&
    pendingCacheResponseMatches && !cachedCorrectionKillPending
  val responseUsesActiveContext = io.cacheResponseValid && !responseSelectsPendingContext &&
    cacheOutstanding && activeCacheResponseMatches
  val responseFire = !io.redirectValid &&
    (responseUsesPendingContext || (responseUsesActiveContext && !cacheDropPending))
  val droppedResponseFire = !io.redirectValid && responseUsesActiveContext && cacheDropPending

  val hitTurnoverContextAvailable = !cacheResponseContextPending || responseUsesPendingContext
  val earlyCachedResponsePending = if (config.enableFrontendCacheHitTurnover) {
    io.cacheHitResponsePending && cacheOutstanding && !cacheDropPending &&
      hitTurnoverContextAvailable && !io.redirectValid
  } else {
    False
  }

  val requestUncached = Mux(
    translationResponseBypassValid,
    io.translationResponse.uncached,
    translatedUncached
  )

  val cacheRequestBaseValid = (translatedRequestValid || translationResponseBypassValid) &&
    (!cacheOutstanding || responseFire || droppedResponseFire ||
      (earlyCachedResponsePending && !requestUncached)) && !io.redirectValid &&
    cacheRequestCapacityAvailable
  io.cacheRequestValid := cacheRequestBaseValid && !requestUncached
  io.cacheUncachedRequestValid := cacheRequestBaseValid && requestUncached
  io.cacheRequest.virtualAddress := requestTranslationPc
  io.cacheRequest.physicalAddress := Mux(
    translationResponseBypassValid,
    io.translationResponse.physicalAddress,
    translatedPhysicalAddress
  )
  io.cacheRequest.uncached := requestUncached
  val cachedRequestFire = io.cacheRequestValid && io.cacheRequestReady
  val uncachedRequestFire = io.cacheUncachedRequestValid && io.cacheRequestReady
  val requestFire = cachedRequestFire || uncachedRequestFire
  // A confirmed cached hit will be delivered through the registered response on the next cycle.
  // Keep its narrow prediction context in a separate slot while L1I starts the younger lookup.
  val earlyCachedHandoffFire = earlyCachedResponsePending && cachedRequestFire
  // A consumed translation response has copied all state needed by the current group into either
  // L1I's request or translatedRequest.  Launch the next lookup from that acceptance event rather
  // than from L1I acceptance, so a tag-array hit on the older group cannot feed the next
  // translation's valid/address cone.  translatedRequest backpressures the younger response until
  // the buffered group enters L1I, preserving request order with at most two live groups.
  val translationRequestCanTurnover = translationTurnoverEligible &&
    !translationDropPending
  io.translationRequest.valid := (!translationOutstanding || translationRequestCanTurnover) &&
    !translatedRequestValid && !translatedExceptionValid && !io.redirectValid &&
    !predictionCorrectionFlushPending && freeSlots >= config.fetchWidth
  val translationRequestPc = Mux(translationRequestCanTurnover, requestPredictedNextPc, nextFetchPc)
  io.translationRequest.virtualAddress := translationRequestPc
  io.translationRequest.isWrite := False
  val translationRequestFire = io.translationRequest.valid && io.translationRequest.ready
  when(!translationOutstanding && !translationDropPending) {
    translationPc := nextFetchPc
  }
  when(translationRequestCanTurnover) {
    translationPc := requestPredictedNextPc
  }
  targetPredictorLookupPc := translationRequestPc
  targetPredictor.io.lookupValid := translationRequestFire
  correctionKillsCachedRequest := predictionCorrectionOnResponse &&
    (cachedRequestFire || (responseUsesPendingContext && cacheOutstanding))
  // The cache-array lookup is synchronous, so canceling the just-accepted wrong-path request on
  // the following cycle still prevents both a hit response and a miss allocation.  Registering
  // this pulse also keeps response predecode out of the L1I response-register enable cone.
  io.cacheKill := (io.redirectValid && cacheOutstanding) || cachedCorrectionKillPending
  // A correction blocks the next lookup until predictionCorrectionFlushPending restores the
  // architectural GHR/RAS.  Let a concurrently accepted wrong-path request update speculative
  // state transiently: the registered restore wins before the corrected lookup, and response
  // predecode no longer feeds the predictor RAM address and RAS write-enable cones.
  val predictorSpeculativeUpdateValid = Bool()
  val predictorSpeculativeHistoryValid = Bool()
  val predictorSpeculativeHistoryTaken = Bool()
  val predictorSpeculativeRasPush = Bool()
  val predictorSpeculativeRasPop = Bool()
  val predictorSpeculativeReturnAddress = UInt(config.xlen bits)
  if (config.enableFrontendHistoryTurnover) {
    // The token is formed at translation acceptance.  This removes L1I tag-hit/request-ready
    // feedback from the speculative predictor update while retaining the same-cycle lookup fold.
    predictorSpeculativeUpdateValid := translationTurnoverTokenValid
    predictorSpeculativeHistoryValid := requestHistoryValid
    predictorSpeculativeHistoryTaken := requestPredictedTaken &&
      requestPredictedType === PredictedBranchType.conditional
    predictorSpeculativeRasPush := requestPredictedTaken &&
      requestPredictedType === PredictedBranchType.call
    predictorSpeculativeRasPop := requestPredictedTaken &&
      requestPredictedType === PredictedBranchType.ret
    predictorSpeculativeReturnAddress := requestPredictedReturnAddress
  } else {
    val delayedUpdateValid = RegNext(requestFire) init (False)
    val delayedHistoryValid = Reg(Bool()) init (False)
    val delayedHistoryTaken = Reg(Bool()) init (False)
    val delayedRasPush = Reg(Bool()) init (False)
    val delayedRasPop = Reg(Bool()) init (False)
    val delayedReturnAddress =
      Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
    when(requestFire) {
      delayedHistoryValid := requestHistoryValid
      delayedHistoryTaken := requestPredictedTaken &&
        requestPredictedType === PredictedBranchType.conditional
      delayedRasPush := requestPredictedTaken &&
        requestPredictedType === PredictedBranchType.call
      delayedRasPop := requestPredictedTaken &&
        requestPredictedType === PredictedBranchType.ret
      delayedReturnAddress := requestPredictedReturnAddress
    }
    predictorSpeculativeUpdateValid := delayedUpdateValid
    predictorSpeculativeHistoryValid := delayedHistoryValid
    predictorSpeculativeHistoryTaken := delayedHistoryTaken
    predictorSpeculativeRasPush := delayedRasPush
    predictorSpeculativeRasPop := delayedRasPop
    predictorSpeculativeReturnAddress := delayedReturnAddress
  }
  targetPredictor.io.speculativeHistoryValid := predictorSpeculativeUpdateValid &&
    predictorSpeculativeHistoryValid
  targetPredictor.io.speculativeHistoryTaken := predictorSpeculativeHistoryTaken
  targetPredictor.io.speculativeRasPush := predictorSpeculativeUpdateValid &&
    predictorSpeculativeRasPush
  targetPredictor.io.speculativeRasPop := predictorSpeculativeUpdateValid &&
    predictorSpeculativeRasPop
  targetPredictor.io.speculativeReturnAddress := predictorSpeculativeReturnAddress
  // FixBranch correction is response-predecode work.  Delay only its predictor-state restore,
  // matching ysyx's registered FixBranch redirect, so the wide RAS/GHR recovery enables do not
  // sit in the cache-response timing cone.  Hold lookup for that restore cycle; the corrected PC
  // is already installed, and the following lookup therefore observes recovered history.
  targetPredictor.io.flush := io.redirectValid || predictionCorrectionFlushPending

  val responseContextPc = Mux(
    responseSelectsPendingContext,
    pendingResponsePc,
    cachePc
  )
  val responseContextPrediction = Vec(BankedFetchPrediction(config), config.fetchWidth)
  for (lane <- 0 until config.fetchWidth) {
    responseContextPrediction(lane) := cachePrediction(lane)
    when(responseSelectsPendingContext) {
      responseContextPrediction(lane) := pendingResponsePrediction(lane)
    }
  }
  val responseContextPredictedTaken = Mux(
    responseSelectsPendingContext,
    pendingResponsePredictedTaken,
    cachePredictedTaken
  )
  val responseContextPredictedLane = Mux(
    responseSelectsPendingContext,
    pendingResponsePredictedLane,
    cachePredictedLane
  )
  val responseContextPredictedTarget = Mux(
    responseSelectsPendingContext,
    pendingResponsePredictedTarget,
    cachePredictedTarget
  )
  val groupBase = responseContextPc &
    U(((BigInt(1) << config.xlen) - 1) ^ (fetchGroupBytes - 1), config.xlen bits)
  val firstSlot = responseContextPc(fetchGroupOffsetWidth - 1 downto 2)
  val responseSlotCandidateValid = Vec(Bool(), config.fetchWidth)
  val responseSlotValid = Vec(Bool(), config.fetchWidth)
  val responsePayloadWriteValid = Vec(Bool(), config.fetchWidth)
  val responsePredictionTaken = Vec(Bool(), config.fetchWidth)
  val responseDynamicPredictionHit = Vec(Bool(), config.fetchWidth)
  val responseControlTransfer = Vec(Bool(), config.fetchWidth)
  val responseActualType = Vec(UInt(PredictedBranchType.Width bits), config.fetchWidth)
  val responseActualTarget = Vec(UInt(config.xlen bits), config.fetchWidth)
  val responsePredictorMetadata = Vec(Bits(16 bits), config.fetchWidth)
  val earlierResponsePredictionTaken = Vec(Bool(), config.fetchWidth + 1)
  val responsePredictionTarget = Vec(UInt(config.xlen bits), config.fetchWidth)
  val responsePrefix = Vec(UInt(enqueueCountWidth bits), config.fetchWidth + 1)
  earlierResponsePredictionTaken(0) := False
  responsePrefix(0) := 0
  for (lane <- 0 until config.fetchWidth) {
    val predecode = io.cacheResponse.predecode(lane)
    responseControlTransfer(lane) := predecode.valid
    responseActualType(lane) := predecode.branchType
    responseActualTarget(lane) := predecode.target
    val targetMatches = predecode.indirect ||
      responseContextPrediction(lane).target === responseActualTarget(lane)
    val dynamicPredictionHit = responseContextPrediction(lane).hit && predecode.valid &&
      responseContextPrediction(lane).branchType === predecode.branchType && targetMatches
    responseDynamicPredictionHit(lane) := dynamicPredictionHit
    // A cold BTB miss does not identify a stable branch location yet. Preserve BTFNT for that
    // first encounter; the carried PHT state is trained at commit and becomes active with the BTB.
    val fallbackTaken = predecode.staticTaken
    val dynamicTaken = predecode.branchType =/= PredictedBranchType.conditional || Mux(
      responseContextPrediction(lane).phtValid,
      responseContextPrediction(lane).phtState(1),
      fallbackTaken
    )
    val lanePredictionTaken = Mux(
      dynamicPredictionHit,
      dynamicTaken,
      fallbackTaken
    )
    responseSlotCandidateValid(lane) :=
      U(lane, config.fetchSlotWidth bits) >= firstSlot &&
      !earlierResponsePredictionTaken(lane)
    responseSlotValid(lane) := responseFire && responseSlotCandidateValid(lane)
    // Payload storage is intentionally wider than the visible prefix.  A young lane truncated
    // by a taken branch may be written into a reserved, invisible slot; count/tail alone expose
    // the surviving prefix to decode.  Keep this enable independent of predecode and predictor
    // metadata so response control does not fan into every wide FrontendSlot register CE.
    responsePayloadWriteValid(lane) := responseFire &&
      U(lane, config.fetchSlotWidth bits) >= firstSlot
    responsePredictionTaken(lane) := responseSlotCandidateValid(lane) &&
      !io.cacheResponse.error && lanePredictionTaken
    earlierResponsePredictionTaken(lane + 1) :=
      earlierResponsePredictionTaken(lane) || responsePredictionTaken(lane)
    responsePredictionTarget(lane) := Mux(
      dynamicPredictionHit,
      responseContextPrediction(lane).target,
      predecode.target
    )
    responsePredictorMetadata(lane) := 0
    responsePredictorMetadata(lane)(9 downto 0) :=
      responseContextPrediction(lane).phtIndex.asBits
    responsePredictorMetadata(lane)(11 downto 10) :=
      responseContextPrediction(lane).phtState.asBits
    responsePredictorMetadata(lane)(12) := responseContextPrediction(lane).phtValid
    responsePredictorMetadata(lane)(15 downto 13) := predecode.branchType.asBits
    responsePrefix(lane + 1) :=
      responsePrefix(lane) + responseSlotCandidateValid(lane).asUInt
  }
  val enqueueCount = Mux(
    responseFire,
    responsePrefix(config.fetchWidth),
    U(0, enqueueCountWidth bits)
  )
  val overlapRequiredSlots = U(config.fetchWidth * 2, countWidth bits)
  val registeredHitOverlapRequiredSlots = U(config.fetchWidth * 3, countWidth bits)
  val registeredHitOverlap = responseUsesPendingContext && earlyCachedResponsePending
  // A recycled response-context register does not release the IBUF space represented by that
  // response.  Reserve the registered response, current hit, and proposed younger request before
  // allowing a second consecutive hit handoff.
  cacheRequestCapacityAvailable := Mux(
    registeredHitOverlap,
    freeSlots >= registeredHitOverlapRequiredSlots,
    Mux(
      responseFire || earlyCachedResponsePending,
      freeSlots >= overlapRequiredSlots,
      freeSlots >= config.fetchWidth
    )
  )
  val responsePredictedTaken = earlierResponsePredictionTaken(config.fetchWidth)
  val responsePredictedTarget = UInt(config.xlen bits)
  responsePredictedTarget := groupBase + fetchGroupBytes
  for (lane <- (0 until config.fetchWidth).reverse) {
    when(responsePredictionTaken(lane)) {
      responsePredictedTarget := responsePredictionTarget(lane)
    }
  }
  val responseCorrectedNextPc = Mux(
    responsePredictedTaken,
    responsePredictedTarget,
    groupBase + fetchGroupBytes
  )
  // FixBranch already reserves the following cycle for predictor-state recovery.
  // Capture the corrected target at the response boundary, then install it during
  // that reserved cycle so response predecode no longer drives the wide next-PC D.
  predictionCorrectionNextPc := responseCorrectedNextPc
  val earlyLanePredictionTaken = responsePredictionTaken(responseContextPredictedLane)
  val earlyLanePredictionTarget = responsePredictionTarget(responseContextPredictedLane)
  val responsePredictionMatchesRequest = Mux(
    responseContextPredictedTaken,
    earlyLanePredictionTaken && responseContextPredictedTarget === earlyLanePredictionTarget,
    !responsePredictedTaken
  )
  predictionCorrectionOnResponse := responseFire && !responsePredictionMatchesRequest
  predictionCorrectionFlushPending := predictionCorrectionOnResponse

  val responseLearnMask = Bits(config.fetchWidth bits)
  for (lane <- 0 until config.fetchWidth) {
    responseLearnMask(lane) := responseSlotValid(lane) && !io.cacheResponse.error &&
      responseControlTransfer(lane) && !responseDynamicPredictionHit(lane) &&
      !io.cacheResponse.predecode(lane).indirect
  }
  val responseLearnPc = UInt(config.xlen bits)
  val responseLearnTarget = UInt(config.xlen bits)
  val responseLearnType = UInt(PredictedBranchType.Width bits)
  responseLearnPc := groupBase
  responseLearnTarget := groupBase + fetchGroupBytes
  responseLearnType := PredictedBranchType.direct
  for (lane <- (0 until config.fetchWidth).reverse) {
    when(responseLearnMask(lane)) {
      responseLearnPc := groupBase + lane * 4
      responseLearnTarget := responseActualTarget(lane)
      responseLearnType := responseActualType(lane)
    }
  }
  val responseLearnPending = RegNext(responseLearnMask.orR) init (False)
  val responseLearnPcReg = RegNextWhen(responseLearnPc, responseFire) init (0)
  val responseLearnTargetReg = RegNextWhen(responseLearnTarget, responseFire) init (0)
  val responseLearnTypeReg = RegNextWhen(responseLearnType, responseFire) init (
    PredictedBranchType.direct
  )
  io.predictorUpdateReady := targetPredictor.io.tableUpdateReady
  val preciseUpdate = io.predictorUpdateValid && io.predictorUpdateReady
  val preciseBtbUpdate = preciseUpdate && io.predictorUpdateTaken
  targetPredictor.io.btbUpdateValid := preciseBtbUpdate || responseLearnPending
  targetPredictor.io.btbUpdatePc := responseLearnPcReg
  targetPredictor.io.btbUpdateTarget := responseLearnTargetReg
  targetPredictor.io.btbUpdateType := responseLearnTypeReg
  targetPredictor.io.btbUpdateDirectionTrained := False
  when(preciseBtbUpdate) {
    targetPredictor.io.btbUpdatePc := io.predictorUpdatePc
    targetPredictor.io.btbUpdateTarget := io.predictorUpdateTarget
    targetPredictor.io.btbUpdateType := io.predictorUpdateType
    targetPredictor.io.btbUpdateDirectionTrained := True
  }
  targetPredictor.io.phtUpdateValid := preciseUpdate &&
    io.predictorUpdateType === PredictedBranchType.conditional
  targetPredictor.io.phtUpdatePc := io.predictorUpdatePc
  targetPredictor.io.phtUpdateIndex := io.predictorUpdateMetadata(9 downto 0).asUInt
  targetPredictor.io.phtUpdateOldState := io.predictorUpdateMetadata(11 downto 10).asUInt
  targetPredictor.io.phtUpdateOldValid := io.predictorUpdateMetadata(12)
  targetPredictor.io.phtUpdateTaken := io.predictorUpdateTaken
  targetPredictor.io.commitRasPush := io.predictorUpdateValid && io.predictorUpdateIsCall
  targetPredictor.io.commitRasPop := io.predictorUpdateValid && io.predictorUpdateIsReturn
  targetPredictor.io.commitReturnAddress := io.predictorUpdatePc + 4
  for (lane <- 0 until config.commitWidth) {
    targetPredictor.io.architecturalHistoryValid(lane) := io.predictorRetireValid(lane) &&
      io.predictorRetireType(lane) === PredictedBranchType.conditional
    targetPredictor.io.architecturalHistoryTaken(lane) := io.predictorRetireTaken(lane)
    targetPredictor.io.architecturalRasPush(lane) := io.predictorRetireValid(lane) &&
      io.predictorRetireIsCall(lane)
    targetPredictor.io.architecturalRasPop(lane) := io.predictorRetireValid(lane) &&
      io.predictorRetireIsReturn(lane)
    targetPredictor.io.architecturalReturnAddress(lane) :=
      io.predictorRetireReturnAddress(lane)
  }
  val decodeInputValid = Bits(config.fetchWidth bits)
  val decodePc = Vec(UInt(config.xlen bits), config.fetchWidth)
  val decodeInstruction = Vec(Bits(32 bits), config.fetchWidth)
  val decodeException = Vec(ExceptionMetadata(), config.fetchWidth)
  for (lane <- 0 until config.fetchWidth) {
    if (lane < config.decodeWidth) {
      val source = (head + U(lane, pointerWidth bits)).resized
      decodeInputValid(lane) := count > U(lane, countWidth bits)
      decodePc(lane) := entries(source).pc
      decodeInstruction(lane) := entries(source).instruction
      decodeException(lane) := entries(source).exception
    } else {
      decodeInputValid(lane) := False
      decodePc(lane) := 0
      decodeInstruction(lane) := 0
      decodeException(lane).valid := False
      decodeException(lane).ecode := 0
      decodeException(lane).esubcode := 0
      decodeException(lane).badVAddrValid := False
      decodeException(lane).badVAddr := 0
      decodeException(lane).tlbRefill := False
    }
  }

  val wideDecode = new WideDecode(config)
  wideDecode.io.inputValid := decodeInputValid
  wideDecode.io.pc := decodePc
  wideDecode.io.instruction := decodeInstruction
  wideDecode.io.predictedTaken := 0
  for (lane <- 0 until config.fetchWidth) {
    val decodeSource = (head + U(lane, pointerWidth bits)).resized
    val decodePredictionTaken = decodeInputValid(lane) && !decodeException(lane).valid &&
      entries(decodeSource).predictedTaken
    wideDecode.io.predictedTaken(lane) := decodePredictionTaken
    wideDecode.io.predictedTarget(lane) := Mux(
      decodePredictionTaken,
      entries(decodeSource).predictedTarget,
      decodePc(lane) + 4
    )
    wideDecode.io.predictorMetadata(lane) := Mux(
      decodeInputValid(lane),
      entries(decodeSource).predictorMetadata,
      B(0, 16 bits)
    )
    wideDecode.io.fetchException(lane) := decodeException(lane)
  }
  wideDecode.io.privilege := io.privilege
  wideDecode.io.interruptPending := io.interruptPending
  io.decodeValid := wideDecode.io.outputValid
  io.decoded := wideDecode.io.decoded

  val dequeueFire = Bits(config.decodeWidth bits)
  val dequeueAccepted = Vec(Bool(), config.decodeWidth + 1)
  dequeueAccepted(0) := True
  for (lane <- 0 until config.decodeWidth) {
    dequeueAccepted(lane + 1) :=
      dequeueAccepted(lane) && io.decodeValid(lane) && io.decodeReady(lane)
    dequeueFire(lane) := dequeueAccepted(lane + 1)
  }
  val dequeueCount = CountOne(dequeueFire)

  when(io.redirectValid) {
    head := 0
    tail := 0
    count := 0
    nextFetchPc := io.redirectTarget
    translationOutstanding := False
    translationDropPending :=
      (translationOutstanding || translationDropPending) && !translationResponseFire
    translatedRequestValid := False
    translatedExceptionValid := False
    cacheOutstanding := False
    cacheDropPending := False
    cacheResponseContextPending := False
    predictionPendingValid := False
  }.otherwise {
    when(translationRequestFire) {
      translationOutstanding := True
      predictionPendingValid := False
    }
    when(targetPredictor.io.responseValid) {
      predictionPendingValid := True
      for (lane <- 0 until config.fetchWidth) {
        pendingPrediction(lane) := targetPredictor.io.prediction(lane)
      }
    }
    when(translationResponseFire) {
      predictionPendingValid := False
      when(translationDropPending) {
        translationDropPending := False
      }.elsewhen(translationOutstanding) {
        translationOutstanding := translationRequestFire
        when(io.translationResponse.cancelled) {
          translatedRequestValid := False
          translatedExceptionValid := False
        }.elsewhen(translationResponseMatches && !io.translationResponse.exception.valid) {
          translatedRequestValid := True
          translatedPc := acceptedTranslationPc
          translatedPhysicalAddress := io.translationResponse.physicalAddress
          translatedUncached := io.translationResponse.uncached
          for (lane <- 0 until config.fetchWidth) {
            translatedPrediction(lane) := predictionForTranslation(lane)
          }
        }.elsewhen(translationResponseMatches) {
          // Preserve the original immediate exception path when no older cache request exists.
          // A speculative next-group fault waits behind the older instruction group.
          when(cacheOutstanding) {
            translatedExceptionValid := True
            translatedException := io.translationResponse.exception
          }
        }
      }
    }
    when(requestFire) {
      translatedRequestValid := False
      cacheOutstanding := True
      cacheDropPending := False
      cachePc := requestTranslationPc
      cachePredictedTaken := requestPredictedTaken
      cachePredictedLane := requestPredictedPc(fetchGroupOffsetWidth - 1 downto 2)
      cachePredictedTarget := requestPredictedTarget
      for (lane <- 0 until config.fetchWidth) {
        cachePrediction(lane) := requestPrediction(lane)
      }
      nextFetchPc := Mux(
        requestPredictedTaken,
        requestPredictedTarget,
        translatedGroupBase + fetchGroupBytes
      )
    }
    when(responseFire) {
      // A cached wrong-path handoff is accepted to keep response decode out of the L1I lookup
      // enable, then canceled at the L1 boundary.  Uncached AXI requests cannot be canceled and
      // therefore retain the response-drain protocol.
      when(!responseUsesPendingContext) {
        cacheOutstanding := requestFire && !correctionKillsCachedRequest
        cacheDropPending := predictionCorrectionOnResponse && uncachedRequestFire
      }.elsewhen(predictionCorrectionOnResponse) {
        cacheOutstanding := requestFire && !correctionKillsCachedRequest
        cacheDropPending := predictionCorrectionOnResponse && uncachedRequestFire
      }
      when(responseUsesPendingContext) {
        cacheResponseContextPending := False
      }
      when(predictionCorrectionOnResponse) {
        translatedRequestValid := False
        translatedExceptionValid := False
        translationOutstanding := False
        translationDropPending :=
          translationRequestFire ||
            ((translationOutstanding || translationDropPending) && !translationResponseFire)
        predictionPendingValid := False
      }
      for (lane <- 0 until config.fetchWidth) {
        when(responsePayloadWriteValid(lane)) {
          // All lanes after firstSlot use the same compacted positions.  The visible prefix is
          // still bounded by enqueueCount, so post-taken payload writes cannot reach decode.
          val compactedLaneOffset =
            U(lane, enqueueCountWidth bits) - firstSlot.resize(enqueueCountWidth)
          val destination = (tail + compactedLaneOffset).resized
          entries(destination).pc := groupBase + lane * 4
          entries(destination).instruction := io.cacheResponse.instructions(lane)
          entries(destination).exception.valid := io.cacheResponse.error
          entries(destination).exception.ecode := U(8, 6 bits)
          entries(destination).exception.esubcode := U(0, 9 bits)
          entries(destination).exception.badVAddrValid := io.cacheResponse.error
          entries(destination).exception.badVAddr := groupBase + lane * 4
          entries(destination).exception.tlbRefill := False
          entries(destination).predictedTaken := responsePredictionTaken(lane)
          entries(destination).predictedTarget := responsePredictionTarget(lane)
          entries(destination).predictorMetadata := responsePredictorMetadata(lane)
        }
      }
      tail := tail + enqueueCount
    }
    when(droppedResponseFire) {
      cacheOutstanding := requestFire
      cacheDropPending := False
    }
    // Capture the payload independent of response correction.  If the older pending response
    // corrects, cachedCorrectionKillPending suppresses this synchronous lookup and clears the
    // transient owner on the following cycle.
    when(earlyCachedHandoffFire) {
      cacheResponseContextPending := True
    }
    when(cachedCorrectionKillPending) {
      cacheResponseContextPending := False
    }
    // The flush-pending cycle already blocks translationRequest.valid. Installing
    // the registered target here preserves the first corrected request cycle.
    when(predictionCorrectionFlushPending) {
      nextFetchPc := predictionCorrectionNextPc
    }
    val translationExceptionCommit = !cacheOutstanding && !translatedRequestValid &&
      !io.redirectValid && freeSlots =/= 0 &&
      (translatedExceptionValid || translationExceptionFire)
    when(translationExceptionCommit) {
      entries(tail).pc := translationPc
      entries(tail).instruction := B(0, 32 bits)
      entries(tail).exception := Mux(
        translationExceptionFire,
        io.translationResponse.exception,
        translatedException
      )
      entries(tail).predictedTaken := False
      entries(tail).predictedTarget := translationPc + 4
      entries(tail).predictorMetadata := B(0, 16 bits)
      tail := tail + 1
      nextFetchPc := translationPc + 4
      translatedExceptionValid := False
    }
    head := head + dequeueCount
    val acceptedCount = Mux(
      responseFire,
      enqueueCount,
      Mux(translationExceptionCommit, U(1, enqueueCountWidth bits), U(0, enqueueCountWidth bits))
    )
    count := count + acceptedCount - dequeueCount
  }

  // Preserve the public observation contract on the recovery cycle even though
  // the internal next-PC register is intentionally updated at its end.
  io.fetchPc := Mux(
    predictionCorrectionFlushPending,
    predictionCorrectionNextPc,
    nextFetchPc
  )
  io.occupancy := count

  val perfObservationV1Word2 = Bits(PerfObservationV1.WordWidth bits)
  perfObservationV1Word2 := 0
  perfObservationV1Word2(0) := io.translationRequest.valid
  perfObservationV1Word2(1) := io.translationRequest.ready
  perfObservationV1Word2(2) := translationRequestFire
  perfObservationV1Word2(3) := translationOutstanding
  perfObservationV1Word2(4) := io.translationResponse.valid
  perfObservationV1Word2(5) := io.translationResponse.ready
  perfObservationV1Word2(6) := translationResponseFire
  perfObservationV1Word2(7) := translatedRequestValid
  perfObservationV1Word2(8) := cacheRequestBaseValid
  perfObservationV1Word2(9) := io.cacheRequestValid
  perfObservationV1Word2(10) := io.cacheUncachedRequestValid
  perfObservationV1Word2(11) := io.cacheRequestReady
  perfObservationV1Word2(12) := requestFire
  perfObservationV1Word2(13) := io.cacheResponseValid
  perfObservationV1Word2(14) := responseFire
  perfObservationV1Word2(15) := cacheOutstanding
  perfObservationV1Word2(16) := io.cacheHitResponsePending
  perfObservationV1Word2(17) := io.cacheKill
  perfObservationV1Word2(18) := io.redirectValid
  perfObservationV1Word2(19) := io.predictorUpdateValid
  perfObservationV1Word2(20) := io.predictorUpdateReady
  perfObservationV1Word2(21) := io.predictorUpdateValid && io.predictorUpdateReady
  perfObservationV1Word2(26) := translationTurnoverTokenValid
  perfObservationV1Word2(27) := predictionPendingValid
  perfObservationV1Word2(28) := cacheDropPending
  PerfObservationV1.expose(perfObservationV1Word2, 2)
}
