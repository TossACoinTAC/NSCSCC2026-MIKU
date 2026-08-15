package miku.backend

import miku.core._
import miku.observe.PerfObservationV1
import spinal.core._
import spinal.lib._

final class OooBackend(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  require(loadStorePort >= 0)
  // Multiply results use their own writeback lane.  For an execution-port-indexed lane whose
  // remaining operations are ALU/Branch, every physical-register producer already emits a direct
  // wake at issue acceptance; its staged ROB wake is therefore only an IQ echo.
  private val directOnlyCompletionPorts = config.executionPorts.zipWithIndex.collect {
    case (port, index)
        if port.capabilities.forall(kind =>
          kind == ExecutionUnitKind.Alu || kind == ExecutionUnitKind.Branch ||
            kind == ExecutionUnitKind.Multiply
        ) => index
  }.toSet

  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val rename = in Vec (DecodedMicroOp(config), config.renameWidth)
    val renameReady = out Bits (config.renameWidth bits)

    val issueValid = out Bits (config.executionWidth bits)
    val issue = out Vec (RenamedMicroOp(config), config.executionWidth)
    val issueSource1 = out Vec (Bits(config.xlen bits), config.executionWidth)
    val issueSource2 = out Vec (Bits(config.xlen bits), config.executionWidth)
    val issueReady = in Bits (config.executionWidth bits)

    val completionValid = in Bits (config.writebackWidth bits)
    val completion = in Vec (Completion(config), config.writebackWidth)
    val storeCompletionBypassValid = in Bool ()
    val storeCompletionBypass = in(StoreCompletionIdentity(config))
    val directWakeupValid = in Bits (config.executionWidth bits)
    val directWakeupPdst =
      in Vec (UInt(config.physicalRegIndexWidth bits), config.executionWidth)
    val loadWakeupValid = in Bool ()
    val loadWakeupPdst = in UInt (config.physicalRegIndexWidth bits)
    val loadWakeupRecoveryEpoch = in UInt (config.recoveryEpochWidth bits)
    val loadWakeupEpochCurrent = in Bool ()
    val resultForwardValid = in Bool ()
    val resultForwardPdst = in UInt (config.physicalRegIndexWidth bits)
    val resultForwardData = in Bits (config.xlen bits)
    val storeDataValid = out Bool ()
    val storeDataRobPointer = out UInt (config.robPointerWidth bits)
    val storeDataStoreQueueIndex = out UInt (config.storeQueueIndexWidth bits)
    val storeData = out Bits (config.xlen bits)
    val storeDataReady = in Bool ()
    val loadStoreIssueOccupancy = out UInt (log2Up(config.issueQueueEntriesPerPort + 1) bits)
    val storeDataOccupancy = out UInt (log2Up(config.storeQueueEntries + 1) bits)
    val memoryAllocateValid = out Bits (config.renameWidth bits)
    val memoryAllocate = out Vec (LoadStoreQueueAllocate(config), config.renameWidth)
    val releaseLoadValid = in Bits (config.commitWidth bits)
    val releaseStoreValid = in Bits (config.commitWidth bits)
    val committedMemoryEpoch = out UInt (config.memoryEpochWidth bits)
    val speculativeMemoryEpoch = out UInt (config.memoryEpochWidth bits)
    val currentRecoveryEpoch = out UInt (config.recoveryEpochWidth bits)
    val robHeadPointer = out UInt (config.robPointerWidth bits)

    val debugReadAddress = in UInt (config.archRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)

    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (CommitRecord(config), config.commitWidth)
    val recoveryValid = out Bool ()
    val recovery = out(RecoveryRequest(config))
    val predictorUpdateCapacity = in UInt (log2Up(config.commitWidth + 1) bits)
    val flush = in Bool ()
  }

  val registerMap = new RenameMap(config)
  val freeList = new PhysicalRegisterFreeList(config)
  val rob = new ReorderBuffer(config)
  val lsqAllocator = new LoadStoreQueueAllocator(config)
  val prf = new PhysicalRegisterFile(config)
  val dispatchQueue = new DispatchQueue(config)
  val dispatchWindow = new DispatchWindow(config)
  val router = new DispatchRouter(config)
  val issueQueues = (0 until config.executionWidth).map { index =>
    val ordinaryPort = index != loadStorePort
    val tokenizedOutput = ordinaryPort && config.enableTokenizedOrdinaryIssueOutput
    val registeredOutput = config.executionPorts(index).registeredIssueOutput ||
      (ordinaryPort && !config.enableTokenizedOrdinaryIssueOutput)
    new IssueQueue(
      config,
      index,
      tokenizedIssueOutput = tokenizedOutput,
      forceRegisteredIssueOutput = registeredOutput
    )
  }
  val storeDataQueue = new StoreDataQueue(config)

  val issueOperandValid = RegInit(B(0, config.executionWidth bits))
  val issueOperandUop = Vec.fill(config.executionWidth)(Reg(RenamedMicroOp(config)))
  val issueOperandSource1 = Vec.fill(config.executionWidth)(Reg(Bits(config.xlen bits)))
  val issueOperandSource2 = Vec.fill(config.executionWidth)(Reg(Bits(config.xlen bits)))
  val recoveryEpoch = Reg(UInt(config.recoveryEpochWidth bits)) init (0)
  when(io.flush) { recoveryEpoch := recoveryEpoch + 1 }
  io.currentRecoveryEpoch := recoveryEpoch
  rob.io.currentEpoch := recoveryEpoch
  rob.io.predictorUpdateCapacity := io.predictorUpdateCapacity
  val committedMemoryEpoch = Reg(UInt(config.memoryEpochWidth bits)) init (0)
  val speculativeMemoryEpoch = Reg(UInt(config.memoryEpochWidth bits)) init (0)

  val renamedInput = Vec(RenamedMicroOp(config), config.renameWidth)
  val renamedMemoryEpoch = Vec(UInt(config.memoryEpochWidth bits), config.renameWidth)
  val dispatchInput = Vec(RenamedMicroOp(config), config.renameWidth)
  for (lane <- 0 until config.renameWidth) {
    val writesPhysicalDestination =
      io.rename(lane).writesGpr && io.rename(lane).rd =/= 0
    renamedInput(lane).decoded := io.rename(lane)
    renamedInput(lane).pdst := Mux(
      writesPhysicalDestination,
      freeList.io.allocatePdst(lane),
      U(0, config.physicalRegIndexWidth bits)
    )
    renamedInput(lane).oldPdst := registerMap.io.renameOldPdst(lane)
    renamedInput(lane).psrc1 := registerMap.io.renamePsrc1(lane)
    renamedInput(lane).psrc2 := registerMap.io.renamePsrc2(lane)
    renamedInput(lane).source1Ready := registerMap.io.renameSource1Ready(lane)
    renamedInput(lane).source2Ready := registerMap.io.renameSource2Ready(lane)
    renamedInput(lane).robPointer := rob.io.allocatedPointer(lane)
    renamedInput(lane).recoveryEpoch := recoveryEpoch
    if (lane == 0) {
      renamedMemoryEpoch(lane) := speculativeMemoryEpoch
    } else {
      val precedingBarrier = Bits(lane bits)
      for (preceding <- 0 until lane) {
        precedingBarrier(preceding) := io.renameValid(preceding) &&
          (io.rename(preceding).systemOperation === SystemOperation.dataBarrier ||
            io.rename(preceding).systemOperation === SystemOperation.instructionBarrier ||
            io.rename(preceding).systemOperation === SystemOperation.cacheOperation)
      }
      renamedMemoryEpoch(lane) := speculativeMemoryEpoch +
        CountOne(precedingBarrier).resize(config.memoryEpochWidth)
    }
    renamedInput(lane).loadQueueIndex := lsqAllocator.io.allocateLoadIndex(lane)
    renamedInput(lane).storeQueueIndex := lsqAllocator.io.allocateStoreIndex(lane)

    dispatchInput(lane).decoded := renamedInput(lane).decoded
    dispatchInput(lane).pdst := renamedInput(lane).pdst
    dispatchInput(lane).oldPdst := renamedInput(lane).oldPdst
    dispatchInput(lane).psrc1 := renamedInput(lane).psrc1
    dispatchInput(lane).psrc2 := renamedInput(lane).psrc2
    dispatchInput(lane).source1Ready := False
    dispatchInput(lane).source2Ready := False
    dispatchInput(lane).robPointer := renamedInput(lane).robPointer
    dispatchInput(lane).recoveryEpoch := renamedInput(lane).recoveryEpoch
    dispatchInput(lane).loadQueueIndex := renamedInput(lane).loadQueueIndex
    dispatchInput(lane).storeQueueIndex := renamedInput(lane).storeQueueIndex
  }

  dispatchQueue.io.enqueueValid := io.renameValid
  dispatchQueue.io.enqueue := dispatchInput
  dispatchWindow.io.inputValid := dispatchQueue.io.dequeueValid
  dispatchWindow.io.input := dispatchQueue.io.dequeue
  dispatchQueue.io.dequeueReady := dispatchWindow.io.inputReady
  router.io.inputValid := dispatchWindow.io.outputValid
  for (lane <- 0 until config.dispatchWidth) {
    router.io.input(lane).decoded := dispatchWindow.io.output(lane).decoded
    router.io.input(lane).pdst := dispatchWindow.io.output(lane).pdst
    router.io.input(lane).oldPdst := dispatchWindow.io.output(lane).oldPdst
    router.io.input(lane).psrc1 := dispatchWindow.io.output(lane).psrc1
    router.io.input(lane).psrc2 := dispatchWindow.io.output(lane).psrc2
    val dispatchSource1Ready = Bool()
    val dispatchSource2Ready = Bool()
    dispatchSource1Ready :=
      registerMap.io.physicalReady(dispatchWindow.io.output(lane).psrc1)
    dispatchSource2Ready :=
      registerMap.io.physicalReady(dispatchWindow.io.output(lane).psrc2)
    // A uop entering an IQ on the registered writeback edge must observe the
    // same bypass as a uop already resident in the IQ on the raw-completion edge.
    // IQ flush has priority over enqueue, so keep the global flush signal out
    // of this candidate-data path just as the resident-IQ wakeup path does.
    for (write <- 0 until config.writebackWidth) {
      when(
        rob.io.completionWakeupCandidateValid(write) &&
          rob.io.completionWakeupPdst(write) === dispatchWindow.io.output(lane).psrc1
      ) {
        dispatchSource1Ready := True
      }
      when(
        rob.io.completionWakeupCandidateValid(write) &&
          rob.io.completionWakeupPdst(write) === dispatchWindow.io.output(lane).psrc2
      ) {
        dispatchSource2Ready := True
      }
    }
    router.io.input(lane).source1Ready := dispatchSource1Ready
    router.io.input(lane).source2Ready := dispatchSource2Ready
    router.io.input(lane).robPointer := dispatchWindow.io.output(lane).robPointer
    router.io.input(lane).recoveryEpoch := dispatchWindow.io.output(lane).recoveryEpoch
    router.io.input(lane).loadQueueIndex := dispatchWindow.io.output(lane).loadQueueIndex
    router.io.input(lane).storeQueueIndex := dispatchWindow.io.output(lane).storeQueueIndex
  }
  val lsuDispatchIsStore = router.io.portValid(loadStorePort) &&
    router.io.portInput(loadStorePort).decoded.isStore
  for (port <- 0 until config.executionWidth) {
    if (port == loadStorePort) {
      // Keep readiness independent of the selected payload: the router uses
      // portReady while choosing that payload. Atomic Store acceptance is
      // enforced by the peer-ready valid gates below.
      router.io.portReady(port) := issueQueues(port).io.enqueueReady &&
        storeDataQueue.io.enqueueReady
    } else {
      router.io.portReady(port) := issueQueues(port).io.enqueueReady
    }
  }
  dispatchWindow.io.outputReady := router.io.inputReady
  dispatchWindow.io.flush := io.flush

  // Gate each half with the peer's ready.  The router keeps portValid asserted
  // under backpressure, so driving either queue directly would enqueue the
  // same Store repeatedly while the other queue is full.
  storeDataQueue.io.enqueueValid := lsuDispatchIsStore &&
    issueQueues(loadStorePort).io.enqueueReady
  storeDataQueue.io.enqueue := router.io.portInput(loadStorePort)
  storeDataQueue.io.wakeupValid := rob.io.completionWakeupValid
  for (write <- 0 until config.writebackWidth) {
    storeDataQueue.io.wakeupPdst(write) := rob.io.completionWakeupPdst(write)
  }
  storeDataQueue.io.readReady := io.storeDataReady && !io.flush
  storeDataQueue.io.flush := io.flush

  rob.io.allocateValid := io.renameValid
  lsqAllocator.io.allocateValid := io.renameValid
  for (lane <- 0 until config.renameWidth) {
    lsqAllocator.io.allocateIsLoad(lane) := io.rename(lane).isLoad
    lsqAllocator.io.allocateIsStore(lane) := io.rename(lane).isStore
    freeList.io.allocateValid(lane) := io.renameValid(lane) &&
      io.rename(lane).writesGpr && io.rename(lane).rd =/= 0
  }
  // Architectural mappings and speculative destinations share the physical
  // pool, so ROB capacity alone cannot prove that the FreeList has space.
  // Use its conservative group-capacity guard to keep rd/writesGpr decode out
  // of this global ready cone without permitting tag reuse.
  val resourcesReady = dispatchQueue.io.enqueueReady &&
    rob.io.allocateCapacityReady && freeList.io.allocateCapacityReady &&
    lsqAllocator.io.allocateCapacityReady && !io.flush
  val acceptAll = resourcesReady && io.renameValid.orR
  val accepted = Bits(config.renameWidth bits)
  val allLanes = B((BigInt(1) << config.renameWidth) - 1, config.renameWidth bits)
  accepted := Mux(acceptAll, io.renameValid, B(0, config.renameWidth bits))
  io.renameReady := Mux(resourcesReady, allLanes, B(0, config.renameWidth bits))

  val acceptedBarrier = Bits(config.renameWidth bits)
  for (lane <- 0 until config.renameWidth) {
    acceptedBarrier(lane) := accepted(lane) &&
      (io.rename(lane).systemOperation === SystemOperation.dataBarrier ||
        io.rename(lane).systemOperation === SystemOperation.instructionBarrier ||
        io.rename(lane).systemOperation === SystemOperation.cacheOperation)
  }
  val acceptedBarrierCount = CountOne(acceptedBarrier).resize(config.memoryEpochWidth)

  val committedBarrier = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    committedBarrier(lane) := rob.io.commitValid(lane) && rob.io.commit(lane).retired &&
      (rob.io.commit(lane).systemOperation === SystemOperation.dataBarrier ||
        rob.io.commit(lane).systemOperation === SystemOperation.instructionBarrier ||
        rob.io.commit(lane).systemOperation === SystemOperation.cacheOperation)
  }
  val committedBarrierCount = CountOne(committedBarrier).resize(config.memoryEpochWidth)
  val nextCommittedMemoryEpoch = committedMemoryEpoch + committedBarrierCount
  when(committedBarrier.orR) {
    committedMemoryEpoch := nextCommittedMemoryEpoch
  }
  when(io.flush) {
    speculativeMemoryEpoch := nextCommittedMemoryEpoch
  }.elsewhen(acceptedBarrier.orR) {
    speculativeMemoryEpoch := speculativeMemoryEpoch + acceptedBarrierCount
  }
  io.committedMemoryEpoch := committedMemoryEpoch
  io.speculativeMemoryEpoch := speculativeMemoryEpoch
  io.robHeadPointer := rob.io.headPointer

  registerMap.io.renameValid := accepted
  for (lane <- 0 until config.renameWidth) {
    // Immediate and PC-sourced encodings reuse the architectural source bit
    // fields for payload bits. Canonicalize those unused fields before the RAT
    // lookup so they cannot create false same-group or resident dependencies.
    registerMap.io.renameSource1(lane) := Mux(
      io.rename(lane).source1Used,
      io.rename(lane).rs1,
      U(0, config.archRegIndexWidth bits)
    )
    registerMap.io.renameSource2(lane) := Mux(
      io.rename(lane).source2Used,
      io.rename(lane).rs2,
      U(0, config.archRegIndexWidth bits)
    )
    registerMap.io.renameDestination(lane) := Mux(
      io.rename(lane).writesGpr,
      io.rename(lane).rd,
      U(0, config.archRegIndexWidth bits)
    )
    registerMap.io.renamePdst(lane) := freeList.io.allocatePdst(lane)
  }

  rob.io.allocateAccept := acceptAll
  freeList.io.allocateAccept := acceptAll
  lsqAllocator.io.allocateAccept := acceptAll
  dispatchQueue.io.enqueueAccept := acceptAll
  lsqAllocator.io.releaseLoadValid := io.releaseLoadValid
  lsqAllocator.io.releaseStoreValid := io.releaseStoreValid
  lsqAllocator.io.flush := io.flush
  for (lane <- 0 until config.renameWidth) {
    rob.io.allocate(lane).uop := renamedInput(lane)
    io.memoryAllocateValid(lane) := accepted(lane) &&
      (renamedInput(lane).decoded.isLoad || renamedInput(lane).decoded.isStore)
    io.memoryAllocate(lane).robPointer := renamedInput(lane).robPointer
    io.memoryAllocate(lane).recoveryEpoch := renamedInput(lane).recoveryEpoch
    io.memoryAllocate(lane).memoryEpoch := renamedMemoryEpoch(lane)
    io.memoryAllocate(lane).isLoad := renamedInput(lane).decoded.isLoad
    io.memoryAllocate(lane).isStore := renamedInput(lane).decoded.isStore
    io.memoryAllocate(lane).loadQueueIndex := renamedInput(lane).loadQueueIndex
    io.memoryAllocate(lane).storeQueueIndex := renamedInput(lane).storeQueueIndex
  }

  // Direct results bypass the variable-latency completion mux with a narrow
  // valid/tag event.  The consumer reaches the PRF bypass together with the
  // qualified writeback data; MUL, DIV and LSU keep the registered ROB wakeup.
  val earlyWakeupValid = Bits(config.writebackWidth bits)
  val earlyWakeupPdst = Vec(UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
  val fastSelectWakeupValid = Bits(config.writebackWidth bits)
  val fastSelectWakeupPdst = Vec(UInt(config.physicalRegIndexWidth bits), config.writebackWidth)
  for (write <- 0 until config.writebackWidth) {
    if (write < config.executionWidth && write != loadStorePort) {
      // IQ flush has priority over wakeup state updates, so this is a candidate
      // event and deliberately excludes the global flush signal from select.
      val registeredWake = rob.io.completionWakeupCandidateValid(write)
      val directWake = io.directWakeupValid(write) && io.directWakeupPdst(write) =/= 0
      fastSelectWakeupValid(write) := directWake
      fastSelectWakeupPdst(write) := io.directWakeupPdst(write)
      val suppressDirectOnlyEcho = config.enableDirectOnlyPortEchoSuppression &&
        directOnlyCompletionPorts.contains(write)
      val selectedRegisteredWake = if (suppressDirectOnlyEcho) {
        // Resident and same-edge enqueued consumers observed the direct wake.  A consumer arriving
        // later is qualified by dispatchSourceReady above, while PRF/RAT still consume the raw ROB
        // wake below.  Keeping stagedPdst off this IQ lane also lets a younger direct tag use it.
        False
      } else if (config.enableDirectWakeupEchoSuppression) {
        // A direct producer already woke every resident/enqueued IQ consumer.
        // Suppress only that producer's next-cycle registered echo, freeing the
        // lane for a new direct tag. A first-time DIV/other registered wake keeps
        // priority, and its covered direct tag returns through ROB one cycle later.
        val previousDirectBroadcast = Reg(Bool()) init (False)
        val registeredIsEcho = registeredWake && previousDirectBroadcast
        val selected = registeredWake && !registeredIsEcho
        val directBroadcast = directWake && !selected
        when(io.flush) {
          previousDirectBroadcast := False
        }.otherwise {
          previousDirectBroadcast := directBroadcast
        }
        selected
      } else {
        registeredWake
      }
      earlyWakeupValid(write) := directWake || selectedRegisteredWake
      earlyWakeupPdst(write) := Mux(
        selectedRegisteredWake,
        rob.io.completionWakeupPdst(write),
        io.directWakeupPdst(write)
      )
    } else if (write == loadStorePort) {
      val registeredWake = rob.io.completionWakeupCandidateValid(write)
      val loadWake = if (config.enableLoadCompletionEarlyWakeup) {
        // LSQ qualifies recoveryEpoch while registering this completion.
        io.loadWakeupValid && io.loadWakeupEpochCurrent && io.loadWakeupPdst =/= 0
      } else {
        False
      }
      fastSelectWakeupValid(write) := loadWake
      fastSelectWakeupPdst(write) := io.loadWakeupPdst
      val selectedRegisteredWake = if (
        config.enableDirectWakeupEchoSuppression && config.enableLoadCompletionEarlyWakeup
      ) {
        val previousLoadBroadcast = Reg(Bool()) init (False)
        val registeredIsEcho = registeredWake && previousLoadBroadcast
        val selected = registeredWake && !registeredIsEcho
        val loadBroadcast = loadWake && !selected
        when(io.flush) {
          previousLoadBroadcast := False
        }.otherwise {
          previousLoadBroadcast := loadBroadcast
        }
        selected
      } else {
        registeredWake
      }
      earlyWakeupValid(write) := loadWake || selectedRegisteredWake
      earlyWakeupPdst(write) := Mux(
        selectedRegisteredWake,
        rob.io.completionWakeupPdst(write),
        io.loadWakeupPdst
      )
    } else if (
      write == config.executionWidth && config.enableMultiplyCompletionEchoSuppression
    ) {
      // The multiplier already broadcasts its destination when the fixed-latency pipe accepts
      // the uop. Its dedicated result lane still writes the PRF/RAT and qualifies dispatch, but
      // repeating the tag through every resident IQ only recreates a completed dependency.
      earlyWakeupValid(write) := False
      earlyWakeupPdst(write) := 0
    } else {
      earlyWakeupValid(write) := rob.io.completionWakeupCandidateValid(write)
      earlyWakeupPdst(write) := rob.io.completionWakeupPdst(write)
      fastSelectWakeupValid(write) := False
      fastSelectWakeupPdst(write) := 0
    }
  }

  // A multiply wakes dependants when it enters the fixed-latency pipe.  Its
  // registered result is available one cycle later, exactly when the selected
  // consumer reads the PRF.  Keep this data path out of the IQ: only the tag
  // participates in select, while the value is muxed at the operand boundary.
  val operandReadData = Vec(Bits(config.xlen bits), config.executionWidth * 2)
  for (readPort <- 0 until config.executionWidth * 2) {
    operandReadData(readPort) := prf.io.readData(readPort)
    when(
      io.resultForwardValid && io.resultForwardPdst =/= 0 &&
        io.resultForwardPdst === prf.io.readAddress(readPort)
    ) {
      operandReadData(readPort) := io.resultForwardData
    }
  }

  for (port <- 0 until config.executionWidth) {
    if (port == loadStorePort) {
      issueQueues(port).io.enqueueValid := router.io.portValid(port) &&
        (!router.io.portInput(port).decoded.isStore || storeDataQueue.io.enqueueReady)
    } else {
      issueQueues(port).io.enqueueValid := router.io.portValid(port)
    }
    issueQueues(port).io.enqueue := router.io.portInput(port)
    issueQueues(port).io.robHeadPointer := rob.io.headPointer
    issueQueues(port).io.flush := io.flush
    if (port == loadStorePort) {
      prf.io.readAddress(port * 2) := issueQueues(port).io.issue.psrc1
      prf.io.readAddress(port * 2 + 1) := Mux(
        storeDataQueue.io.readValid,
        storeDataQueue.io.readPsrc,
        issueQueues(port).io.issue.psrc2
      )

      val lsuNeedsSource2 = issueQueues(port).io.issueValid &&
        !issueQueues(port).io.issue.decoded.isStore &&
        issueQueues(port).io.issue.psrc2 =/= 0
      val sharedReadConflict = storeDataQueue.io.readValid && lsuNeedsSource2
      val operandConsumed = issueOperandValid(port) && io.issueReady(port)
      val operandSlotAvailable = !issueOperandValid(port) || operandConsumed
      val operandCaptureReady = operandSlotAvailable && !sharedReadConflict
      issueQueues(port).io.issueReady := !io.flush && operandCaptureReady

      when(io.flush) {
        issueOperandValid(port) := False
      }.otherwise {
        // Consuming the current operand and capturing the next IQ entry are
        // separate events. A Store-data PRF conflict may prevent refill, but
        // must never replay an operand already accepted by the AGU.
        when(operandConsumed) {
          issueOperandValid(port) := False
        }
        when(issueQueues(port).io.issueValid && issueQueues(port).io.issueReady) {
          issueOperandValid(port) := True
          issueOperandUop(port) := issueQueues(port).io.issue
          issueOperandSource1(port) := operandReadData(port * 2)
          issueOperandSource2(port) := Mux(
            issueQueues(port).io.issue.decoded.isStore ||
              issueQueues(port).io.issue.psrc2 === 0,
            B(0, config.xlen bits),
            operandReadData(port * 2 + 1)
          )
        }
      }
    } else {
      prf.io.readAddress(port * 2) := issueQueues(port).io.issue.psrc1
      prf.io.readAddress(port * 2 + 1) := issueQueues(port).io.issue.psrc2

      val operandReady = !issueOperandValid(port) || io.issueReady(port)
      issueQueues(port).io.issueReady := operandReady
      when(io.flush) {
        issueOperandValid(port) := False
      }.otherwise {
        when(operandReady) {
          issueOperandValid(port) := issueQueues(port).io.issueValid
          when(issueQueues(port).io.issueValid) {
            issueOperandUop(port) := issueQueues(port).io.issue
            issueOperandSource1(port) := operandReadData(port * 2)
            issueOperandSource2(port) := operandReadData(port * 2 + 1)
          }
        }
      }
    }

    io.issueValid(port) := issueOperandValid(port)
    io.issue(port) := issueOperandUop(port)
    io.issueSource1(port) := issueOperandSource1(port)
    io.issueSource2(port) := issueOperandSource2(port)
    // Flush has priority over every IQ state update, so its combinational
    // value need not sit on the same-cycle wakeup-to-select timing path.
    issueQueues(port).io.wakeupValid := earlyWakeupValid
    for (write <- 0 until config.writebackWidth) {
      issueQueues(port).io.wakeupPdst(write) := earlyWakeupPdst(write)
    }
    if (
      (port == loadStorePort && config.enableLsuRegisteredWakeSelectDecoupling) ||
      (port != loadStorePort && config.enableOrdinaryRegisteredWakeSelectDecoupling)
    ) {
      issueQueues(port).io.selectWakeupValid := fastSelectWakeupValid
      for (write <- 0 until config.writebackWidth) {
        issueQueues(port).io.selectWakeupPdst(write) := fastSelectWakeupPdst(write)
      }
    } else {
      issueQueues(port).io.selectWakeupValid := earlyWakeupValid
      for (write <- 0 until config.writebackWidth) {
        issueQueues(port).io.selectWakeupPdst(write) := earlyWakeupPdst(write)
      }
    }
  }

  io.storeDataValid := storeDataQueue.io.readValid && !io.flush
  io.storeDataRobPointer := storeDataQueue.io.readRobPointer
  io.storeDataStoreQueueIndex := storeDataQueue.io.readStoreQueueIndex
  io.storeData := prf.io.readData(loadStorePort * 2 + 1)
  io.loadStoreIssueOccupancy := issueQueues(loadStorePort).io.occupancy
  io.storeDataOccupancy := storeDataQueue.io.occupancy

  rob.io.completionValid := io.completionValid
  rob.io.completion := io.completion
  rob.io.storeCompletionBypassValid := io.storeCompletionBypassValid
  rob.io.storeCompletionBypass := io.storeCompletionBypass
  for (write <- 0 until config.writebackWidth) {
    prf.io.writeValid(write) := rob.io.completionWakeupValid(write)
    prf.io.write(write).pdst := rob.io.completionWakeupPdst(write)
    prf.io.write(write).data := rob.io.completionWakeupData(write)
    registerMap.io.writebackValid(write) :=
      rob.io.completionWakeupValid(write)
    registerMap.io.writebackPdst(write) := rob.io.completionWakeupPdst(write)
  }
  prf.io.debugReadAddress := registerMap.io.architecturalMappings(io.debugReadAddress)
  io.debugReadData := prf.io.debugReadData

  io.commitValid := rob.io.commitValid
  io.commit := rob.io.commit
  io.recoveryValid := rob.io.recoveryValid
  io.recovery := rob.io.recovery
  // Physical-register reclamation is not architecturally visible in the
  // retirement cycle.  Register it here so the three-wide ROB prefix does not
  // drive the distant free-list write enables directly.  A recovery is
  // reported one cycle before the backend flush, so the free list deliberately
  // consumes this registered batch even when that following flush is asserted.
  val retiredFreeValid = Reg(Bits(config.commitWidth bits)) init (
    B(0, config.commitWidth bits)
  )
  val retiredFreePdst = Vec.fill(config.commitWidth)(
    Reg(UInt(config.physicalRegIndexWidth bits)) init (
      U(0, config.physicalRegIndexWidth bits)
    )
  )
  for (lane <- 0 until config.commitWidth) {
    val commitsDestination = rob.io.commitValid(lane) && rob.io.commit(lane).retired &&
      rob.io.commit(lane).writesGpr && rob.io.commit(lane).rd =/= 0
    registerMap.io.commitValid(lane) := commitsDestination
    registerMap.io.commitArch(lane) := rob.io.commit(lane).rd
    registerMap.io.commitPdst(lane) := rob.io.commit(lane).pdst
    retiredFreeValid(lane) := commitsDestination && !io.flush
    retiredFreePdst(lane) := rob.io.commit(lane).oldPdst
    freeList.io.commitFreeValid(lane) := retiredFreeValid(lane)
    freeList.io.commitFreePdst(lane) := retiredFreePdst(lane)
  }

  registerMap.io.flush := io.flush
  dispatchQueue.io.flush := io.flush
  freeList.io.flush := io.flush
  prf.io.flush := io.flush
  rob.io.flush := io.flush

  require(config.executionWidth == 4)
  val perfObservationV1Word3 = Bits(PerfObservationV1.WordWidth bits)
  perfObservationV1Word3 := 0
  perfObservationV1Word3(5 downto 0) := rob.io.occupancy.asBits.resized
  perfObservationV1Word3(11 downto 6) := rob.io.headPointer.asBits
  perfObservationV1Word3(14 downto 12) := io.renameValid
  perfObservationV1Word3(17 downto 15) := io.renameValid & io.renameReady
  perfObservationV1Word3(20 downto 18) := dispatchWindow.io.outputValid
  perfObservationV1Word3(24 downto 21) := issueOperandValid
  perfObservationV1Word3(28 downto 25) := issueOperandValid & io.issueReady
  perfObservationV1Word3(33 downto 29) := io.completionValid
  for (queue <- 0 until config.executionWidth) {
    perfObservationV1Word3(34 + queue * 4 + 3 downto 34 + queue * 4) :=
      issueQueues(queue).io.occupancy.asBits.resized
    perfObservationV1Word3(50 + queue) := issueQueues(queue).io.issueValid
    perfObservationV1Word3(54 + queue) := issueQueues(queue).io.issueReady
    perfObservationV1Word3(58 + queue) :=
      router.io.portValid(queue) && router.io.portReady(queue)
  }
  perfObservationV1Word3(62) := storeDataQueue.io.multipleReady
  perfObservationV1Word3(63) := storeDataQueue.io.physicalSelectionOutOfAgeOrder
  PerfObservationV1.expose(perfObservationV1Word3, 3)
}
