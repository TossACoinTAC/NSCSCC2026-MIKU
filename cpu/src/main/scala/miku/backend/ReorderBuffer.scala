package miku.backend

import miku.core._
import miku.observe.PerfObservationV1
import miku.predict.PredictedBranchType
import spinal.core._
import spinal.lib._

final case class ReorderBufferPayload(config: OooCoreConfig) extends Bundle {
  val instruction = Bits(32 bits)
  val rd = UInt(config.archRegIndexWidth bits)
  val pdst = UInt(config.physicalRegIndexWidth bits)
  val oldPdst = UInt(config.physicalRegIndexWidth bits)
  val writesGpr = Bool()
  val csrAddress = UInt(14 bits)
  val csrWrite = Bool()
  val csrMask = Bool()
  val predictorMetadata = Bits(16 bits)
  val decodedException = ExceptionMetadata()
}

final case class ReorderBufferRetirementMetadata(config: OooCoreConfig) extends Bundle {
  val serializing = Bool()
  val systemOperation = UInt(SystemOperation.Width bits)
  val systemOperationIsNone = Bool()
  val systemOperationIsMemoryBarrier = Bool()
  val pc = UInt(config.xlen bits)
  val isLoad = Bool()
  val isStore = Bool()
  val isBranch = Bool()
  val predictorType = UInt(PredictedBranchType.Width bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val storeQueueIndex = UInt(config.storeQueueIndexWidth bits)
}

final case class ReorderBufferCompletionPayload(config: OooCoreConfig) extends Bundle {
  val result = Bits(config.xlen bits)
  // Completion exceptions use this word as badVAddr; resolved branches use it
  // as their target; all other completions use it as sideEffectData.
  val auxiliary = Bits(config.xlen bits)
  val exceptionEcode = UInt(6 bits)
  val exceptionEsubcode = UInt(9 bits)
  val exceptionBadVAddrValid = Bool()
  val exceptionTlbRefill = Bool()
  val branchTaken = Bool()
  val branchMispredict = Bool()
}

final case class ReorderBufferState(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val complete = Bool()
  val payloadReady = Bool()
  val decodedExceptionValid = Bool()
  // The Vec index already carries the physical ROB index.  Only the wrap
  // generation is resident state needed to reject a stale completion.
  val generation = Bool()
  val completionExceptionValid = Bool()
  val completionSource = UInt(log2Up(config.writebackWidth + 2) bits)
}

final case class ReorderBufferEntry(config: OooCoreConfig) extends Bundle {
  val state = ReorderBufferState(config)
  val payload = ReorderBufferPayload(config)
  val exception = ExceptionMetadata()
}

final class ReorderBuffer(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit) extends Component {
  private def selectLowest(mask: Bits, width: Int): UInt = {
    val selected = UInt(width bits)
    selected := 0
    for (index <- (0 until mask.getWidth).reverse) {
      when(mask(index)) { selected := U(index, width bits) }
    }
    selected
  }

  val io = new Bundle {
    val allocateValid = in Bits (config.renameWidth bits)
    val allocate = in Vec (ReorderBufferAllocate(config), config.renameWidth)
    val allocateReady = out Bool ()
    val allocateCapacityReady = out Bool ()
    val allocateOldestReady = out Bool ()
    val allocateTwoReady = out Bool ()
    val allocateAccept = in Bool ()
    val allocateAcceptMask = in Bits (config.renameWidth bits)
    val allocatedPointer = out Vec (UInt(config.robPointerWidth bits), config.renameWidth)

    val completionValid = in Bits (config.writebackWidth bits)
    val completion = in Vec (Completion(config), config.writebackWidth)
    val storeCompletionBypassValid = in Bool ()
    val storeCompletionBypass = in(StoreCompletionIdentity(config))
    val completionWakeupValid = out Bits (config.writebackWidth bits)
    val completionWakeupCandidateValid = out Bits (config.writebackWidth bits)
    val completionWakeupPdst =
      out Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
    val completionWakeupData = out Vec (Bits(config.xlen bits), config.writebackWidth)
    val currentEpoch = in UInt (config.recoveryEpochWidth bits)
    val predictorUpdateCapacity = in UInt (log2Up(config.commitWidth + 1) bits)
    val observationRenameAdmission = in Bits (8 bits)

    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (CommitRecord(config), config.commitWidth)
    val recoveryValid = out Bool ()
    val recovery = out(RecoveryRequest(config))

    val flush = in Bool ()
    val empty = out Bool ()
    val occupancy = out UInt (log2Up(config.robEntries + 1) bits)
    val headPointer = out UInt (config.robPointerWidth bits)
  }

  val allocatePointer = Reg(UInt(config.robPointerWidth bits)) init (0)
  val commitPointer = Reg(UInt(config.robPointerWidth bits)) init (0)
  val occupancy = Reg(UInt(log2Up(config.robEntries + 1) bits)) init (0)
  val entries = Vec.fill(config.robEntries)(Reg(ReorderBufferState(config)))
  for (entry <- entries) {
    entry.valid.init(False)
    entry.complete.init(False)
    entry.payloadReady.init(False)
    entry.decodedExceptionValid.init(False)
  }
  private val payloadBankCount = 4
  private val payloadBankWidth = log2Up(payloadBankCount)
  private val payloadDepth = config.robEntries / payloadBankCount
  private val payloadWidth = ReorderBufferPayload(config).getBitsWidth
  private val retirementMetadataWidth = ReorderBufferRetirementMetadata(config).getBitsWidth
  private val completionPayloadWidth = ReorderBufferCompletionPayload(config).getBitsWidth
  private val storeCompletionSource = config.writebackWidth
  private val decodedCompletionSource = config.writebackWidth + 1
  require(config.robEntries % payloadBankCount == 0)
  require(config.renameWidth < payloadBankCount)
  require(config.commitWidth < payloadBankCount)
  val payloadBanks = Array.fill(payloadBankCount)(Mem(Bits(payloadWidth bits), payloadDepth))
  val retirementMetadataBanks =
    Array.fill(payloadBankCount)(Mem(Bits(retirementMetadataWidth bits), payloadDepth))
  // A producer owns one write per cycle, while three consecutive commit pointers
  // always select distinct low-two-bit banks.  Four shallow banks therefore provide
  // the required one-write/three-read bandwidth without three full-depth replicas.
  val completionPayloadMemories = Array.fill(config.writebackWidth, payloadBankCount)(
    Mem(Bits(completionPayloadWidth bits), payloadDepth)
  )
  val appliedCompletionValid = Reg(Bits(config.writebackWidth bits)) init (0)
  val appliedCompletionPointer = Vec.fill(config.writebackWidth)(
    Reg(UInt(config.robPointerWidth bits)) init (0)
  )
  val appliedCompletionPayload = Vec.fill(config.writebackWidth)(
    Reg(Bits(completionPayloadWidth bits)) init (0)
  )

  val allocatePrefix = Vec(UInt(log2Up(config.renameWidth + 1) bits), config.renameWidth + 1)
  val allocationDestination = Vec(UInt(config.robPointerWidth bits), config.renameWidth)
  allocatePrefix(0) := U(0, allocatePrefix(0).getWidth bits)
  for (lane <- 0 until config.renameWidth) {
    allocatePrefix(lane + 1) := allocatePrefix(lane) + io.allocateValid(lane).asUInt
    allocationDestination(lane) := (allocatePointer + allocatePrefix(lane)).resized
    io.allocatedPointer(lane) := allocationDestination(lane)
  }

  val requested = allocatePrefix(config.renameWidth)
  val freeSlots = U(config.robEntries, occupancy.getWidth bits) - occupancy
  val acceptedMask = Bits(config.renameWidth bits)
  acceptedMask := Mux(
    io.allocateAcceptMask.orR,
    io.allocateAcceptMask,
    Mux(io.allocateAccept, io.allocateValid, B(0, config.renameWidth bits))
  )
  io.allocateCapacityReady := freeSlots >= requested
  io.allocateReady := !io.flush && io.allocateCapacityReady
  io.allocateOldestReady := !io.flush && freeSlots =/= 0
  if (config.renameWidth >= 2) {
    io.allocateTwoReady := !io.flush && freeSlots >= U(2, occupancy.getWidth bits)
  } else {
    io.allocateTwoReady := False
  }

  val allocationPayload = Vec(ReorderBufferPayload(config), config.renameWidth)
  val allocationRetirementMetadata =
    Vec(ReorderBufferRetirementMetadata(config), config.renameWidth)
  for (lane <- 0 until config.renameWidth) {
    allocationPayload(lane).instruction := io.allocate(lane).uop.decoded.instruction
    allocationPayload(lane).rd := io.allocate(lane).uop.decoded.rd
    allocationPayload(lane).pdst := io.allocate(lane).uop.pdst
    allocationPayload(lane).oldPdst := io.allocate(lane).uop.oldPdst
    allocationPayload(lane).writesGpr := io.allocate(lane).uop.decoded.writesGpr
    allocationPayload(lane).csrAddress := io.allocate(lane).uop.decoded.csrAddress
    allocationPayload(lane).csrWrite := io.allocate(lane).uop.decoded.csrWrite
    allocationPayload(lane).csrMask := io.allocate(lane).uop.decoded.csrMask
    val instruction = io.allocate(lane).uop.decoded.instruction
    val opcode = instruction(31 downto 26).asUInt
    val isJirl = opcode === U(0x13, 6 bits)
    val isReturn = isJirl && instruction(4 downto 0) === 0 &&
      instruction(9 downto 5) === 1 && instruction(25 downto 10) === 0
    val isCall = opcode === U(0x15, 6 bits) ||
      (isJirl && instruction(4 downto 0) === 1)
    val allocationPredictorType = UInt(PredictedBranchType.Width bits)
    allocationPredictorType := PredictedBranchType.direct
    when(
      io.allocate(lane).uop.decoded.branchKind >= 1 &&
        io.allocate(lane).uop.decoded.branchKind <= 6
    ) {
      allocationPredictorType := PredictedBranchType.conditional
    }.elsewhen(isReturn) {
      allocationPredictorType := PredictedBranchType.ret
    }.elsewhen(isCall) {
      allocationPredictorType := PredictedBranchType.call
    }.elsewhen(isJirl) {
      allocationPredictorType := PredictedBranchType.indirect
    }
    allocationPayload(lane).predictorMetadata :=
      io.allocate(lane).uop.decoded.predictorMetadata
    allocationPayload(lane).decodedException := io.allocate(lane).uop.decoded.exception

    allocationRetirementMetadata(lane).serializing :=
      io.allocate(lane).uop.decoded.serializing
    allocationRetirementMetadata(lane).systemOperation :=
      io.allocate(lane).uop.decoded.systemOperation
    allocationRetirementMetadata(lane).systemOperationIsNone :=
      io.allocate(lane).uop.decoded.systemOperation === SystemOperation.none
    allocationRetirementMetadata(lane).systemOperationIsMemoryBarrier :=
      io.allocate(lane).uop.decoded.systemOperation === SystemOperation.dataBarrier ||
        io.allocate(lane).uop.decoded.systemOperation === SystemOperation.instructionBarrier ||
        io.allocate(lane).uop.decoded.systemOperation === SystemOperation.cacheOperation
    allocationRetirementMetadata(lane).pc := io.allocate(lane).uop.decoded.pc
    allocationRetirementMetadata(lane).isLoad := io.allocate(lane).uop.decoded.isLoad
    allocationRetirementMetadata(lane).isStore := io.allocate(lane).uop.decoded.isStore
    allocationRetirementMetadata(lane).isBranch := io.allocate(lane).uop.decoded.isBranch
    allocationRetirementMetadata(lane).predictorType := allocationPredictorType
    allocationRetirementMetadata(lane).loadQueueIndex := io.allocate(lane).uop.loadQueueIndex
    allocationRetirementMetadata(lane).storeQueueIndex := io.allocate(lane).uop.storeQueueIndex

    when(acceptedMask(lane)) {
      val destination = allocationDestination(lane)
      entries(destination(config.robIndexWidth - 1 downto 0)).valid := True
      entries(destination(config.robIndexWidth - 1 downto 0)).complete :=
        io.allocate(lane).uop.decoded.exception.valid
      entries(destination(config.robIndexWidth - 1 downto 0)).payloadReady := False
      entries(destination(config.robIndexWidth - 1 downto 0)).decodedExceptionValid :=
        io.allocate(lane).uop.decoded.exception.valid
      entries(destination(config.robIndexWidth - 1 downto 0)).generation := destination.msb
      entries(destination(config.robIndexWidth - 1 downto 0)).completionExceptionValid := False
      entries(destination(config.robIndexWidth - 1 downto 0)).completionSource :=
        U(decodedCompletionSource, log2Up(config.writebackWidth + 2) bits)
    }
  }

  // A decoded exception is complete at allocation time, while the payload RAM is written
  // on that same edge.  Delay retirement eligibility until the next synchronous read has
  // observed the new payload instead of relying on device-specific read-during-write data.
  val stagedAllocationValid = Reg(Bits(config.renameWidth bits)) init (0)
  val stagedAllocationPointer = Vec.fill(config.renameWidth)(
    Reg(UInt(config.robPointerWidth bits)) init (0)
  )
  when(io.flush) {
    stagedAllocationValid := 0
  }.otherwise {
    for (lane <- 0 until config.renameWidth) {
      stagedAllocationValid(lane) := acceptedMask(lane)
      stagedAllocationPointer(lane) := allocationDestination(lane)
    }
  }
  for (lane <- 0 until config.renameWidth) {
    val pointer = stagedAllocationPointer(lane)
    val state = entries(pointer(config.robIndexWidth - 1 downto 0))
    when(
      !io.flush && stagedAllocationValid(lane) && state.valid &&
        state.generation === pointer.msb
    ) {
      state.payloadReady := True
    }
  }

  // Three consecutive ROB destinations always occupy distinct low-two-bit banks, including wrap.
  // Each bank therefore needs one physical write port even though allocation is three-wide.
  for (bank <- 0 until payloadBankCount) {
    val writeMask = Bits(config.renameWidth bits)
    for (lane <- 0 until config.renameWidth) {
      writeMask(lane) := acceptedMask(lane) &&
        allocationDestination(lane)(payloadBankWidth - 1 downto 0) ===
        U(bank, payloadBankWidth bits)
    }
    val writeLane = selectLowest(writeMask, log2Up(config.renameWidth))
    payloadBanks(bank).write(
      address = allocationDestination(writeLane)(config.robIndexWidth - 1 downto payloadBankWidth),
      data = allocationPayload(writeLane).asBits,
      enable = writeMask.orR
    )
    retirementMetadataBanks(bank).write(
      address = allocationDestination(writeLane)(
        config.robIndexWidth - 1 downto payloadBankWidth
      ),
      data = allocationRetirementMetadata(writeLane).asBits,
      enable = writeMask.orR
    )
  }

  // Prefetch the next commit group through synchronous payload-bank reads.  Feeding the
  // current commit pointer directly into an asynchronous 182-bit LUTRAM read put the
  // predictor update behind the ROB address decoder and bank crossbar.  The prefetch
  // pointer advances by this cycle's commit count, so a full three-wide commit still
  // presents the following group without a bubble.
  val payloadReadAdvance = UInt(log2Up(config.commitWidth + 1) bits)
  val payloadReadBase = UInt(config.robPointerWidth bits)
  payloadReadBase := (commitPointer + payloadReadAdvance).resized
  when(io.flush) {
    payloadReadBase := allocatePointer
  }
  val payloadReadPointer = Vec(UInt(config.robPointerWidth bits), config.commitWidth)
  val candidatePointer = Vec.fill(config.commitWidth)(
    Reg(UInt(config.robPointerWidth bits)) init (0)
  )
  for (lane <- 0 until config.commitWidth) {
    payloadReadPointer(lane) :=
      (payloadReadBase + U(lane, config.robPointerWidth bits)).resized
    candidatePointer(lane) := payloadReadPointer(lane)
  }
  val payloadBankRead = Vec(Bits(payloadWidth bits), payloadBankCount)
  val retirementMetadataBankRead =
    Vec(Bits(retirementMetadataWidth bits), payloadBankCount)
  val payloadBankReadLane = Vec(UInt(log2Up(config.commitWidth) bits), payloadBankCount)
  for (bank <- 0 until payloadBankCount) {
    val readMask = Bits(config.commitWidth bits)
    for (lane <- 0 until config.commitWidth) {
      readMask(lane) := payloadReadPointer(lane)(payloadBankWidth - 1 downto 0) ===
        U(bank, payloadBankWidth bits)
    }
    payloadBankReadLane(bank) := selectLowest(readMask, log2Up(config.commitWidth))
    payloadBankRead(bank) := payloadBanks(bank).readSync(
      address = payloadReadPointer(payloadBankReadLane(bank))(
        config.robIndexWidth - 1 downto payloadBankWidth
      ),
      enable = True
    )
    retirementMetadataBankRead(bank) := retirementMetadataBanks(bank).readSync(
      address = payloadReadPointer(payloadBankReadLane(bank))(
        config.robIndexWidth - 1 downto payloadBankWidth
      ),
      enable = True
    )
  }

  val completionPayloadBankRead = Vec.fill(config.writebackWidth)(
    Vec.fill(payloadBankCount)(Bits(completionPayloadWidth bits))
  )
  for (producerLane <- 0 until config.writebackWidth; bank <- 0 until payloadBankCount) {
    completionPayloadBankRead(producerLane)(bank) :=
      completionPayloadMemories(producerLane)(bank).readSync(
        address = payloadReadPointer(payloadBankReadLane(bank))(
          config.robIndexWidth - 1 downto payloadBankWidth
        ),
        enable = True
      )
  }

  val candidates = Vec(ReorderBufferEntry(config), config.commitWidth)
  val candidateCompletionPayload = Vec(
    ReorderBufferCompletionPayload(config),
    config.commitWidth
  )
  val candidateRetirementMetadata =
    Vec(ReorderBufferRetirementMetadata(config), config.commitWidth)
  for (lane <- 0 until config.commitWidth) {
    val pointer = candidatePointer(lane)
    val bank = pointer(payloadBankWidth - 1 downto 0)
    candidates(lane).state := entries(pointer(config.robIndexWidth - 1 downto 0))
    candidates(lane).payload.assignFromBits(payloadBankRead(bank))
    candidateRetirementMetadata(lane).assignFromBits(retirementMetadataBankRead(bank))
    val selectedCompletionPayload = Bits(completionPayloadWidth bits)
    selectedCompletionPayload := 0
    for (producerLane <- 0 until config.writebackWidth) {
      when(candidates(lane).state.completionSource === producerLane) {
        selectedCompletionPayload := completionPayloadBankRead(producerLane)(bank)
      }
      // A completion can make lane 1 or 2 eligible on the same edge that its
      // synchronous memory is written.  Define that case explicitly instead
      // of relying on the RAM primitive's read-during-write mode.
      when(
        candidates(lane).state.completionSource === producerLane &&
          appliedCompletionValid(producerLane) &&
          appliedCompletionPointer(producerLane) === pointer
      ) {
        selectedCompletionPayload := appliedCompletionPayload(producerLane)
      }
    }
    candidateCompletionPayload(lane).assignFromBits(selectedCompletionPayload)
    // Exception validity is retirement-control state.  Keeping that hot bit beside valid/complete
    // avoids routing a block-RAM payload output through the three-wide commit stop chain.  The
    // cold exception payload remains banked, preserving almost all of the storage reduction.
    candidates(lane).exception.valid := candidates(lane).state.decodedExceptionValid
    candidates(lane).exception.ecode := candidates(lane).payload.decodedException.ecode
    candidates(lane).exception.esubcode := candidates(lane).payload.decodedException.esubcode
    candidates(lane).exception.badVAddrValid :=
      candidates(lane).payload.decodedException.badVAddrValid
    candidates(lane).exception.badVAddr := candidates(lane).payload.decodedException.badVAddr
    candidates(lane).exception.tlbRefill := candidates(lane).payload.decodedException.tlbRefill
    when(candidates(lane).state.completionExceptionValid) {
      candidates(lane).exception.valid := True
      candidates(lane).exception.ecode := candidateCompletionPayload(lane).exceptionEcode
      candidates(lane).exception.esubcode := candidateCompletionPayload(lane).exceptionEsubcode
      candidates(lane).exception.badVAddrValid :=
        candidateCompletionPayload(lane).exceptionBadVAddrValid
      candidates(lane).exception.badVAddr := candidateCompletionPayload(lane).auxiliary.asUInt
      candidates(lane).exception.tlbRefill :=
        candidateCompletionPayload(lane).exceptionTlbRefill
    }
  }

  // Qualify and select an incoming completion against the head that will be
  // presented after this edge.  Registering this narrow bypass context keeps
  // the writeback-lane compare tree and result mux out of the commit prefix.
  val stagedHeadCompletionBypassValid = Reg(Bool()) init (False)
  val stagedHeadCompletionBypassResult = Reg(Bits(config.xlen bits)) init (0)
  val headCompletionBypass = Bool()
  val headCompletionBypassResult = Bits(config.xlen bits)
  val stagedHeadBranchBypassValid = Reg(Bool()) init (False)
  val stagedHeadBranchBypassResult = Reg(Bits(config.xlen bits)) init (0)
  val stagedHeadBranchBypassTaken = Reg(Bool()) init (False)
  val stagedHeadBranchBypassTarget = Reg(UInt(config.xlen bits)) init (0)
  val stagedHeadBranchBypassMispredict = Reg(Bool()) init (False)
  val headBranchBypass = Bool()
  val effectiveBranchTaken = Vec(Bool(), config.commitWidth)
  val effectiveBranchTarget = Vec(UInt(config.xlen bits), config.commitWidth)
  val effectiveBranchMispredict = Vec(Bool(), config.commitWidth)
  val canCommit = Vec(Bool(), config.commitWidth)
  val stopAfter = Vec(Bool(), config.commitWidth)
  val branchPrefix = Vec(UInt(log2Up(config.commitWidth + 1) bits), config.commitWidth)
  for (lane <- 0 until config.commitWidth) {
    val retiringBranch = candidateRetirementMetadata(lane).isBranch &&
      !candidates(lane).exception.valid
    if (lane == 0) {
      effectiveBranchTaken(lane) := Mux(
        headBranchBypass,
        stagedHeadBranchBypassTaken,
        candidateCompletionPayload(lane).branchTaken
      )
      effectiveBranchTarget(lane) := Mux(
        headBranchBypass,
        stagedHeadBranchBypassTarget,
        candidateCompletionPayload(lane).auxiliary.asUInt
      )
      effectiveBranchMispredict(lane) := Mux(
        headBranchBypass,
        stagedHeadBranchBypassMispredict,
        candidateCompletionPayload(lane).branchMispredict
      )
    } else {
      effectiveBranchTaken(lane) := candidateCompletionPayload(lane).branchTaken
      effectiveBranchTarget(lane) := candidateCompletionPayload(lane).auxiliary.asUInt
      effectiveBranchMispredict(lane) := candidateCompletionPayload(lane).branchMispredict
    }
    if (lane == 0) {
      branchPrefix(lane) := retiringBranch.asUInt.resized
    } else {
      branchPrefix(lane) := branchPrefix(lane - 1) + retiringBranch.asUInt
    }
    val predictorHasCapacity = !retiringBranch ||
      branchPrefix(lane) <= io.predictorUpdateCapacity
    stopAfter(lane) := candidates(lane).exception.valid ||
      candidateRetirementMetadata(lane).serializing || effectiveBranchMispredict(lane)
    if (lane == 0) {
      canCommit(lane) := candidates(lane).state.valid &&
        (candidates(lane).state.complete || headCompletionBypass || headBranchBypass) &&
        candidates(lane).state.payloadReady && predictorHasCapacity
    } else {
      canCommit(lane) := candidates(lane).state.valid && candidates(lane).state.complete &&
        candidates(lane).state.payloadReady && canCommit(lane - 1) && !stopAfter(lane - 1) &&
        predictorHasCapacity
    }
    io.commitValid(lane) := canCommit(lane)
    io.commit(lane).pc := candidateRetirementMetadata(lane).pc
    io.commit(lane).instruction := candidates(lane).payload.instruction
    io.commit(lane).robPointer := candidatePointer(lane)
    io.commit(lane).rd := candidates(lane).payload.rd
    io.commit(lane).pdst := candidates(lane).payload.pdst
    io.commit(lane).oldPdst := candidates(lane).payload.oldPdst
    io.commit(lane).writesGpr := candidates(lane).payload.writesGpr
    if (lane == 0) {
      io.commit(lane).result := Mux(
        headBranchBypass,
        stagedHeadBranchBypassResult,
        Mux(
          headCompletionBypass,
          headCompletionBypassResult,
          candidateCompletionPayload(lane).result
        )
      )
    } else {
      io.commit(lane).result := candidateCompletionPayload(lane).result
    }
    io.commit(lane).systemOperation := candidateRetirementMetadata(lane).systemOperation
    io.commit(lane).systemOperationIsMemoryBarrier :=
      candidateRetirementMetadata(lane).systemOperationIsMemoryBarrier
    io.commit(lane).csrAddress := candidates(lane).payload.csrAddress
    io.commit(lane).csrWrite := candidates(lane).payload.csrWrite
    io.commit(lane).csrMask := candidates(lane).payload.csrMask
    io.commit(lane).sideEffectData := candidateCompletionPayload(lane).auxiliary
    io.commit(lane).retired := canCommit(lane) && !candidates(lane).exception.valid
    io.commit(lane).serializing := candidateRetirementMetadata(lane).serializing
    io.commit(lane).isLoad := candidateRetirementMetadata(lane).isLoad
    io.commit(lane).isStore := candidateRetirementMetadata(lane).isStore
    io.commit(lane).isBranch := candidateRetirementMetadata(lane).isBranch
    io.commit(lane).predictorType := candidateRetirementMetadata(lane).predictorType
    io.commit(lane).branchTaken := effectiveBranchTaken(lane)
    io.commit(lane).branchTarget := effectiveBranchTarget(lane)
    io.commit(lane).predictorMetadata := candidates(lane).payload.predictorMetadata
    io.commit(lane).loadQueueIndex := candidateRetirementMetadata(lane).loadQueueIndex
    io.commit(lane).storeQueueIndex := candidateRetirementMetadata(lane).storeQueueIndex
    io.commit(lane).exception := candidates(lane).exception
  }

  val committedCount = CountOne(io.commitValid)
  payloadReadAdvance := committedCount
  val recoveryMask = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    recoveryMask(lane) := io.commitValid(lane) &&
      (candidates(lane).exception.valid || effectiveBranchMispredict(lane))
  }
  io.recoveryValid := recoveryMask.orR
  io.recovery.cause := RecoveryCause.none
  io.recovery.robPointer := U(0, config.robPointerWidth bits)
  io.recovery.pc := U(0, config.xlen bits)
  io.recovery.taken := False
  io.recovery.target := U(0, config.xlen bits)
  io.recovery.exception.valid := False
  io.recovery.exception.ecode := U(0, 6 bits)
  io.recovery.exception.esubcode := U(0, 9 bits)
  io.recovery.exception.badVAddrValid := False
  io.recovery.exception.badVAddr := U(0, 32 bits)
  io.recovery.exception.tlbRefill := False
  when(recoveryMask.orR) {
    val recoveryIndex = selectLowest(recoveryMask, log2Up(config.commitWidth))
    io.recovery.robPointer := candidatePointer(recoveryIndex)
    io.recovery.pc := candidateRetirementMetadata(recoveryIndex).pc
    io.recovery.taken := effectiveBranchTaken(recoveryIndex)
    io.recovery.target := effectiveBranchTarget(recoveryIndex)
    io.recovery.exception := candidates(recoveryIndex).exception
    when(candidates(recoveryIndex).exception.valid) {
      io.recovery.cause := RecoveryCause.exception
    }.otherwise {
      io.recovery.cause := RecoveryCause.branchMispredict
    }
  }

  // Register the narrow completion identity before validating it against the
  // ROB.  This retains next-cycle wakeup while preventing the LSU completion
  // cone from driving a full bank of one-hot hit registers.
  val stagedCompletionValid = Reg(Bits(config.writebackWidth bits)) init (0)
  val stagedRobPointer = Vec.fill(config.writebackWidth)(
    Reg(UInt(config.robPointerWidth bits))
  )
  val stagedRecoveryEpoch = Vec.fill(config.writebackWidth)(
    Reg(UInt(config.recoveryEpochWidth bits))
  )
  val stagedResult = Vec.fill(config.writebackWidth)(Reg(Bits(config.xlen bits)))
  val stagedPdst = Vec.fill(config.writebackWidth)(Reg(UInt(config.physicalRegIndexWidth bits)))
  val stagedWritesPdst = Vec.fill(config.writebackWidth)(Reg(Bool()))
  val stagedSideEffectData = Vec.fill(config.writebackWidth)(Reg(Bits(config.xlen bits)))
  val stagedException = Vec.fill(config.writebackWidth)(Reg(ExceptionMetadata()))
  val stagedBranchResolved = Vec.fill(config.writebackWidth)(Reg(Bool()))
  val stagedBranchTaken = Vec.fill(config.writebackWidth)(Reg(Bool()))
  val stagedBranchMispredict = Vec.fill(config.writebackWidth)(Reg(Bool()))
  val stagedBranchTarget = Vec.fill(config.writebackWidth)(Reg(UInt(config.xlen bits)))
  // Epoch validation is registered alongside the completion payload.  The
  // payload is already staged before wakeup, so this preserves the existing
  // wakeup latency while keeping currentEpoch out of the IQ select-to-uop
  // write path.
  val stagedCompletionCurrent = Reg(Bits(config.writebackWidth bits)) init (0)
  val stagedStoreCompletionValid = RegInit(False)
  val stagedStoreCompletionCurrent = RegInit(False)
  val stagedStoreCompletionRobPointer = Reg(UInt(config.robPointerWidth bits))
  val stagedCompletionTargets = Vec(Bits(config.robEntries bits), config.writebackWidth)
  for (lane <- 0 until config.writebackWidth) {
    val stagedIndex = stagedRobPointer(lane)(config.robIndexWidth - 1 downto 0)
    stagedCompletionTargets(lane) := Mux(
      stagedCompletionValid(lane) && stagedCompletionCurrent(lane),
      UIntToOh(stagedIndex, config.robEntries),
      B(0, config.robEntries bits)
    )
  }
  val stagedStoreCompletionTarget = Bits(config.robEntries bits)
  stagedStoreCompletionTarget := Mux(
    stagedStoreCompletionValid && stagedStoreCompletionCurrent,
    UIntToOh(
      stagedStoreCompletionRobPointer(config.robIndexWidth - 1 downto 0),
      config.robEntries
    ),
    B(0, config.robEntries bits)
  )
  val stagedCompletionMatches = Vec(Bits(config.writebackWidth bits), config.robEntries)
  val stagedStoreCompletionMatches = Bits(config.robEntries bits)
  for (entryIndex <- 0 until config.robEntries) {
    for (lane <- 0 until config.writebackWidth) {
      stagedCompletionMatches(entryIndex)(lane) := stagedCompletionTargets(lane)(entryIndex) &&
        entries(entryIndex).valid && !entries(entryIndex).complete &&
        entries(entryIndex).generation === stagedRobPointer(lane).msb
    }
    stagedStoreCompletionMatches(entryIndex) := stagedStoreCompletionTarget(entryIndex) &&
      entries(entryIndex).valid && !entries(entryIndex).complete &&
      entries(entryIndex).generation === stagedStoreCompletionRobPointer.msb
  }
  val stagedCompletionPayload = Vec(
    ReorderBufferCompletionPayload(config),
    config.writebackWidth
  )
  for (lane <- 0 until config.writebackWidth) {
    stagedCompletionPayload(lane).result := stagedResult(lane)
    stagedCompletionPayload(lane).auxiliary := stagedSideEffectData(lane)
    when(stagedBranchResolved(lane)) {
      stagedCompletionPayload(lane).auxiliary := stagedBranchTarget(lane).asBits
    }
    when(stagedException(lane).valid) {
      stagedCompletionPayload(lane).auxiliary := stagedException(lane).badVAddr.asBits
    }
    stagedCompletionPayload(lane).exceptionEcode := stagedException(lane).ecode
    stagedCompletionPayload(lane).exceptionEsubcode := stagedException(lane).esubcode
    stagedCompletionPayload(lane).exceptionBadVAddrValid :=
      stagedException(lane).badVAddrValid
    stagedCompletionPayload(lane).exceptionTlbRefill := stagedException(lane).tlbRefill
    stagedCompletionPayload(lane).branchTaken := stagedBranchTaken(lane)
    stagedCompletionPayload(lane).branchMispredict := stagedBranchMispredict(lane)
  }
  val completionWriteValid = Bits(config.writebackWidth bits)
  for (lane <- 0 until config.writebackWidth) {
    val laneMatches = Bits(config.robEntries bits)
    for (entryIndex <- 0 until config.robEntries) {
      laneMatches(entryIndex) := stagedCompletionMatches(entryIndex)(lane)
    }
    completionWriteValid(lane) := !io.flush && laneMatches.orR
    for (bank <- 0 until payloadBankCount) {
      completionPayloadMemories(lane)(bank).write(
        address = stagedRobPointer(lane)(
          config.robIndexWidth - 1 downto payloadBankWidth
        ),
        data = stagedCompletionPayload(lane).asBits,
        enable = completionWriteValid(lane) &&
          stagedRobPointer(lane)(payloadBankWidth - 1 downto 0) ===
          U(bank, payloadBankWidth bits)
      )
    }
  }
  when(io.flush) {
    appliedCompletionValid := 0
  }.otherwise {
    for (lane <- 0 until config.writebackWidth) {
      appliedCompletionValid(lane) := completionWriteValid(lane)
      when(completionWriteValid(lane)) {
        appliedCompletionPointer(lane) := stagedRobPointer(lane)
        appliedCompletionPayload(lane) := stagedCompletionPayload(lane).asBits
      }
    }
  }
  when(io.flush) {
    stagedStoreCompletionValid := False
    stagedStoreCompletionCurrent := False
  }.otherwise {
    stagedStoreCompletionValid := io.storeCompletionBypassValid
    stagedStoreCompletionRobPointer := io.storeCompletionBypass.robPointer
    stagedStoreCompletionCurrent := io.storeCompletionBypassValid &&
      io.storeCompletionBypass.recoveryEpoch === io.currentEpoch
  }
  for (lane <- 0 until config.writebackWidth) {
    io.completionWakeupCandidateValid(lane) := stagedCompletionCurrent(lane) &&
      stagedWritesPdst(lane) && stagedPdst(lane) =/= 0
    io.completionWakeupValid(lane) :=
      !io.flush && io.completionWakeupCandidateValid(lane)
    io.completionWakeupPdst(lane) := stagedPdst(lane)
    io.completionWakeupData(lane) := stagedResult(lane)
    when(io.flush) {
      stagedCompletionValid(lane) := False
      stagedCompletionCurrent(lane) := False
    }.otherwise {
      stagedCompletionValid(lane) := io.completionValid(lane)
      stagedRobPointer(lane) := io.completion(lane).robPointer
      stagedRecoveryEpoch(lane) := io.completion(lane).recoveryEpoch
      stagedCompletionCurrent(lane) :=
        io.completionValid(lane) && io.completion(lane).recoveryEpoch === io.currentEpoch
      // Valid and pointer define payload validity. Capturing each lane avoids
      // turning completionValid into a wide payload-register enable.
      stagedResult(lane) := io.completion(lane).data
      stagedPdst(lane) := io.completion(lane).pdst
      stagedWritesPdst(lane) := io.completion(lane).writesPdst
      stagedSideEffectData(lane) := io.completion(lane).sideEffectData
      stagedException(lane) := io.completion(lane).exception
      stagedBranchResolved(lane) := io.completion(lane).branchResolved
      stagedBranchTaken(lane) := io.completion(lane).branchTaken
      stagedBranchMispredict(lane) := io.completion(lane).branchMispredict
      stagedBranchTarget(lane) := io.completion(lane).branchTarget
    }
  }

  if (config.enableHeadCompletionCommitBypass) {
    val incomingHeadCompletionBypassMask = Bits(config.writebackWidth bits)
    val incomingHeadBranchBypassMask = Bits(config.writebackWidth bits)
    val incomingHeadStoreCompletionBypass = io.storeCompletionBypassValid &&
      io.storeCompletionBypass.recoveryEpoch === io.currentEpoch &&
      io.storeCompletionBypass.robPointer === payloadReadPointer(0)
    val incomingHeadCompletionBypassResult = Bits(config.xlen bits)
    incomingHeadCompletionBypassResult := 0
    for (lane <- 0 until config.writebackWidth) {
      incomingHeadCompletionBypassMask(lane) := io.completionValid(lane) &&
        io.completion(lane).recoveryEpoch === io.currentEpoch &&
        io.completion(lane).robPointer === payloadReadPointer(0) &&
        !io.completion(lane).exception.valid && !io.completion(lane).branchResolved
      incomingHeadBranchBypassMask(lane) := io.completionValid(lane) &&
        io.completion(lane).recoveryEpoch === io.currentEpoch &&
        io.completion(lane).robPointer === payloadReadPointer(0) &&
        !io.completion(lane).exception.valid && io.completion(lane).branchResolved
      when(incomingHeadCompletionBypassMask(lane)) {
        incomingHeadCompletionBypassResult := io.completion(lane).data
      }
    }
    when(io.flush) {
      stagedHeadCompletionBypassValid := False
      stagedHeadBranchBypassValid := False
    }.otherwise {
      stagedHeadCompletionBypassValid :=
        incomingHeadCompletionBypassMask.orR || incomingHeadStoreCompletionBypass
      stagedHeadCompletionBypassResult := Mux(
        incomingHeadStoreCompletionBypass,
        B(0, config.xlen bits),
        incomingHeadCompletionBypassResult
      )
      if (config.enableBranchHeadCompletionBypass) {
        stagedHeadBranchBypassValid := incomingHeadBranchBypassMask.orR
        for (lane <- 0 until config.writebackWidth) {
          when(incomingHeadBranchBypassMask(lane)) {
            stagedHeadBranchBypassResult := io.completion(lane).data
            stagedHeadBranchBypassTaken := io.completion(lane).branchTaken
            stagedHeadBranchBypassTarget := io.completion(lane).branchTarget
            stagedHeadBranchBypassMispredict := io.completion(lane).branchMispredict
          }
        }
      } else {
        stagedHeadBranchBypassValid := False
        stagedHeadBranchBypassResult := 0
        stagedHeadBranchBypassTaken := False
        stagedHeadBranchBypassTarget := 0
        stagedHeadBranchBypassMispredict := False
      }
    }
  } else {
    stagedHeadCompletionBypassValid := False
    stagedHeadBranchBypassValid := False
    stagedHeadBranchBypassResult := 0
    stagedHeadBranchBypassTaken := False
    stagedHeadBranchBypassTarget := 0
    stagedHeadBranchBypassMispredict := False
  }

  for (entryIndex <- 0 until config.robEntries) {
    for (lane <- 0 until config.writebackWidth) {
      when(!io.flush && stagedCompletionMatches(entryIndex)(lane)) {
        entries(entryIndex).complete := True
        entries(entryIndex).completionExceptionValid := stagedException(lane).valid
        entries(entryIndex).completionSource := lane
      }
    }
    when(!io.flush && stagedStoreCompletionMatches(entryIndex)) {
      entries(entryIndex).complete := True
      entries(entryIndex).completionExceptionValid := False
      entries(entryIndex).completionSource := storeCompletionSource
    }
  }

  if (config.enableHeadCompletionCommitBypass) {
    // Ordinary current-epoch completions and, when enabled, fully resolved branches
    // may bypass the final entry.complete register. Serializing/system operations and
    // either decoded or completion exceptions retain the precise retirement boundary.
    headCompletionBypass := !io.flush && candidates(0).state.payloadReady &&
      stagedHeadCompletionBypassValid &&
      candidates(0).state.valid && !candidates(0).state.complete &&
      !candidates(0).exception.valid && !candidateRetirementMetadata(0).serializing &&
      !candidateRetirementMetadata(0).isBranch &&
      candidateRetirementMetadata(0).systemOperationIsNone
    headCompletionBypassResult := stagedHeadCompletionBypassResult
    if (config.enableBranchHeadCompletionBypass) {
      headBranchBypass := !io.flush && candidates(0).state.payloadReady &&
        stagedHeadBranchBypassValid && candidates(0).state.valid &&
        !candidates(0).state.complete && !candidates(0).exception.valid &&
        !candidateRetirementMetadata(0).serializing &&
        candidateRetirementMetadata(0).isBranch &&
        candidateRetirementMetadata(0).systemOperationIsNone
    } else {
      headBranchBypass := False
    }
  } else {
    headCompletionBypass := False
    headCompletionBypassResult := 0
    headBranchBypass := False
  }

  when(io.flush) {
    // Keep the next-free pointer across a flush so delayed completions from
    // the discarded window cannot alias the first entry of the new window.
    commitPointer := allocatePointer
    occupancy := U(0, occupancy.getWidth bits)
    for (entry <- entries) {
      entry.valid := False
      entry.complete := False
      entry.payloadReady := False
      entry.decodedExceptionValid := False
    }
  }.otherwise {
    when(acceptedMask.orR) {
      allocatePointer := allocatePointer + CountOne(acceptedMask)
    }
    for (lane <- 0 until config.commitWidth) {
      when(io.commitValid(lane)) {
        val pointer = (commitPointer + U(lane, config.robPointerWidth bits)).resized
        entries(pointer(config.robIndexWidth - 1 downto 0)).valid := False
      }
    }
    commitPointer := commitPointer + committedCount
    occupancy := occupancy + CountOne(acceptedMask) - committedCount
  }

  io.empty := occupancy === 0
  io.occupancy := occupancy
  io.headPointer := commitPointer

  require(config.writebackWidth == 5)
  val perfObservationV1Word4 = Bits(PerfObservationV1.WordWidth bits)
  perfObservationV1Word4 := 0
  val observationBranchResolved = Bits(config.writebackWidth bits)
  val observationBranchMispredict = Bits(config.writebackWidth bits)
  val observationCompletionActive = stagedCompletionValid & stagedCompletionCurrent
  for (lane <- 0 until config.writebackWidth) {
    observationBranchResolved(lane) :=
      observationCompletionActive(lane) && stagedBranchResolved(lane)
    observationBranchMispredict(lane) :=
      observationBranchResolved(lane) && stagedBranchMispredict(lane)
    perfObservationV1Word4(10 + lane * 6 + 5 downto 10 + lane * 6) :=
      stagedRobPointer(lane).asBits.resize(6)
  }
  perfObservationV1Word4(4 downto 0) := observationBranchResolved
  perfObservationV1Word4(9 downto 5) := observationBranchMispredict
  val observationHeadRetiringBranch =
    candidateRetirementMetadata(0).isBranch && !candidates(0).exception.valid
  val observationHeadPredictorHasCapacity =
    !observationHeadRetiringBranch || io.predictorUpdateCapacity =/= 0
  // Bits 40..51 extend the reserved portion of the V1 ABI. Existing readers
  // ignore them, while newer monitors can classify zero-retirement cycles.
  perfObservationV1Word4(40) := candidates(0).state.valid
  perfObservationV1Word4(41) :=
    candidates(0).state.complete || headCompletionBypass || headBranchBypass
  perfObservationV1Word4(42) := candidates(0).state.payloadReady
  perfObservationV1Word4(43) := observationHeadPredictorHasCapacity
  perfObservationV1Word4(44) := candidates(0).exception.valid
  perfObservationV1Word4(45) := candidateRetirementMetadata(0).serializing
  perfObservationV1Word4(46) := effectiveBranchMispredict(0)
  perfObservationV1Word4(47) := candidateRetirementMetadata(0).isLoad
  perfObservationV1Word4(48) := candidateRetirementMetadata(0).isStore
  perfObservationV1Word4(49) := candidateRetirementMetadata(0).isBranch
  perfObservationV1Word4(50) :=
    !candidateRetirementMetadata(0).systemOperationIsNone
  perfObservationV1Word4(51) := headCompletionBypass
  val observationIncomingHeadBranchCompletion = Bits(config.writebackWidth bits)
  val observationIncomingHeadMispredictCompletion = Bits(config.writebackWidth bits)
  for (lane <- 0 until config.writebackWidth) {
    observationIncomingHeadBranchCompletion(lane) := io.completionValid(lane) &&
      io.completion(lane).recoveryEpoch === io.currentEpoch &&
      io.completion(lane).robPointer === payloadReadPointer(0) &&
      io.completion(lane).branchResolved && !io.completion(lane).exception.valid &&
      candidates(0).state.valid && !candidates(0).state.complete &&
      candidates(0).state.payloadReady && candidateRetirementMetadata(0).isBranch
    observationIncomingHeadMispredictCompletion(lane) :=
      observationIncomingHeadBranchCompletion(lane) && io.completion(lane).branchMispredict
  }
  perfObservationV1Word4(52) := observationIncomingHeadBranchCompletion.orR
  perfObservationV1Word4(53) := observationIncomingHeadMispredictCompletion.orR
  perfObservationV1Word4(61 downto 54) := io.observationRenameAdmission
  PerfObservationV1.expose(perfObservationV1Word4, 4)
}
