package miku.backend

import miku.core._
import spinal.core._
import spinal.lib._

final case class IssueAluPayload(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val immediate = Bits(config.xlen bits)
  val source1IsPc = Bool()
  val source2IsImmediate = Bool()
  val source2IsFour = Bool()
  val operation = Bits(14 bits)
}

final case class IssueBranchPayload(config: OooCoreConfig) extends Bundle {
  val isBranch = Bool()
  val branchKind = UInt(3 bits)
  val predictedTaken = Bool()
  val predictedTarget = UInt(config.xlen bits)
}

final case class IssueMulDivPayload() extends Bundle {
  val operation = Bits(4 bits)
  val signed = Bool()
}

final case class IssueSystemPayload(config: OooCoreConfig) extends Bundle {
  val rd = UInt(config.archRegIndexWidth bits)
  val csrAddress = UInt(14 bits)
  val csrMask = Bool()
  val resultFromCsr = Bool()
  val systemOperation = UInt(SystemOperation.Width bits)
  val serializing = Bool()
}

final case class IssueMemoryPayload(config: OooCoreConfig) extends Bundle {
  val pc = UInt(config.xlen bits)
  val immediate = Bits(config.xlen bits)
  val isLoad = Bool()
  val isStore = Bool()
  val memorySize = Bits(2 bits)
  val signExtend = Bool()
  val isLl = Bool()
  val isSc = Bool()
}

/** Per-port resident IQ payload.
  *
  * Dispatch has already copied architectural/retirement-only information into
  * the ROB.  Each fixed execution port therefore stores only the decoded
  * fields that one of its functional units can consume.  The full renamed-uop
  * interface is reconstructed at the IQ boundary, keeping this optimization
  * internal to scheduling.
  */
final case class IssueEntry(config: OooCoreConfig, portIndex: Int) extends Bundle {
  private val capabilities = config.executionPorts(portIndex).capabilities
  private val hasAlu = capabilities.contains(ExecutionUnitKind.Alu)
  private val hasBranch = capabilities.contains(ExecutionUnitKind.Branch)
  private val hasMultiply = capabilities.contains(ExecutionUnitKind.Multiply)
  private val hasDivide = capabilities.contains(ExecutionUnitKind.Divide)
  private val hasSystem = capabilities.contains(ExecutionUnitKind.Csr) ||
    capabilities.contains(ExecutionUnitKind.Serial)
  private val hasMemory = capabilities.contains(ExecutionUnitKind.LoadStore)

  val fuType = UInt(ExecutionUnitType.Width bits)
  val alu = if (hasAlu) IssueAluPayload(config) else null
  val branch = if (hasBranch) IssueBranchPayload(config) else null
  val mulDiv = if (hasMultiply || hasDivide) IssueMulDivPayload() else null
  val system = if (hasSystem) IssueSystemPayload(config) else null
  val memory = if (hasMemory) IssueMemoryPayload(config) else null
  // Decoded exceptions complete through an ordinary ALU-class uop.  A
  // dedicated LSU receives translation/data exceptions from the LSQ instead.
  val exception = if (!hasMemory) ExceptionMetadata() else null

  val pdst = UInt(config.physicalRegIndexWidth bits)
  val psrc1 = UInt(config.physicalRegIndexWidth bits)
  val psrc2 = UInt(config.physicalRegIndexWidth bits)
  val source1Ready = Bool()
  val source2Ready = Bool()
  val robPointer = UInt(config.robPointerWidth bits)
  val recoveryEpoch = UInt(config.recoveryEpochWidth bits)
  val loadQueueIndex = UInt(config.loadQueueIndexWidth bits)
  val storeQueueIndex = UInt(config.storeQueueIndexWidth bits)
}

final class IssueQueue(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit,
    portIndex: Int = 0
) extends Component {
  require(portIndex >= 0 && portIndex < config.executionWidth)

  private val portCapabilities = config.executionPorts(portIndex).capabilities
  private val portHasAlu = portCapabilities.contains(ExecutionUnitKind.Alu)
  private val portHasBranch = portCapabilities.contains(ExecutionUnitKind.Branch)
  private val portHasMulDiv = portCapabilities.contains(ExecutionUnitKind.Multiply) ||
    portCapabilities.contains(ExecutionUnitKind.Divide)
  private val portHasSystem = portCapabilities.contains(ExecutionUnitKind.Csr) ||
    portCapabilities.contains(ExecutionUnitKind.Serial)
  private val portHasMemory = portCapabilities.contains(ExecutionUnitKind.LoadStore)

  private def packIssueEntry(
      target: IssueEntry,
      source: RenamedMicroOp,
      source1Ready: Bool,
      source2Ready: Bool
  ): Unit = {
    target.fuType := source.decoded.fuType
    if (portHasAlu) {
      target.alu.pc := source.decoded.pc
      target.alu.immediate := source.decoded.immediate
      target.alu.source1IsPc := source.decoded.source1IsPc
      target.alu.source2IsImmediate := source.decoded.source2IsImmediate
      target.alu.source2IsFour := source.decoded.source2IsFour
      target.alu.operation := source.decoded.operation
    }
    if (portHasBranch) {
      target.branch.isBranch := source.decoded.isBranch
      target.branch.branchKind := source.decoded.branchKind
      target.branch.predictedTaken := source.decoded.predictedTaken
      target.branch.predictedTarget := source.decoded.predictedTarget
    }
    if (portHasMulDiv) {
      target.mulDiv.operation := source.decoded.mulDivOperation
      target.mulDiv.signed := source.decoded.mulDivSigned
    }
    if (portHasSystem) {
      target.system.rd := source.decoded.rd
      target.system.csrAddress := source.decoded.csrAddress
      target.system.csrMask := source.decoded.csrMask
      target.system.resultFromCsr := source.decoded.resultFromCsr
      target.system.systemOperation := source.decoded.systemOperation
      target.system.serializing := source.decoded.serializing
    }
    if (portHasMemory) {
      target.memory.pc := source.decoded.pc
      target.memory.immediate := source.decoded.immediate
      target.memory.isLoad := source.decoded.isLoad
      target.memory.isStore := source.decoded.isStore
      target.memory.memorySize := source.decoded.memorySize
      target.memory.signExtend := source.decoded.memorySignExtend
      target.memory.isLl := source.decoded.isLl
      target.memory.isSc := source.decoded.isSc
    } else {
      target.exception := source.decoded.exception
    }
    target.pdst := source.pdst
    target.psrc1 := source.psrc1
    target.psrc2 := source.psrc2
    target.source1Ready := source1Ready
    target.source2Ready := source2Ready
    target.robPointer := source.robPointer
    target.recoveryEpoch := source.recoveryEpoch
    target.loadQueueIndex := source.loadQueueIndex
    target.storeQueueIndex := source.storeQueueIndex
  }

  private def copyIssueEntry(
      target: IssueEntry,
      source: IssueEntry,
      source1Ready: Bool,
      source2Ready: Bool
  ): Unit = {
    target.fuType := source.fuType
    if (portHasAlu) target.alu := source.alu
    if (portHasBranch) target.branch := source.branch
    if (portHasMulDiv) target.mulDiv := source.mulDiv
    if (portHasSystem) target.system := source.system
    if (portHasMemory) target.memory := source.memory
    else target.exception := source.exception
    target.pdst := source.pdst
    target.psrc1 := source.psrc1
    target.psrc2 := source.psrc2
    target.source1Ready := source1Ready
    target.source2Ready := source2Ready
    target.robPointer := source.robPointer
    target.recoveryEpoch := source.recoveryEpoch
    target.loadQueueIndex := source.loadQueueIndex
    target.storeQueueIndex := source.storeQueueIndex
  }

  private def unpackIssueEntry(target: RenamedMicroOp, source: IssueEntry): Unit = {
    target.decoded.fuType := source.fuType
    target.decoded.pc :=
      (if (portHasAlu) source.alu.pc
       else if (portHasMemory) source.memory.pc
       else U(0, config.xlen bits))
    target.decoded.instruction := 0
    target.decoded.fetchSlot := 0
    target.decoded.rd :=
      (if (portHasSystem) source.system.rd else U(0, config.archRegIndexWidth bits))
    target.decoded.rs1 := 0
    target.decoded.rs2 := 0
    target.decoded.immediate :=
      (if (portHasAlu) source.alu.immediate
       else if (portHasMemory) source.memory.immediate
       else B(0, config.xlen bits))
    target.decoded.source1Used := False
    target.decoded.source2Used := False
    target.decoded.source1IsPc :=
      (if (portHasAlu) source.alu.source1IsPc else False)
    target.decoded.source2IsImmediate :=
      (if (portHasAlu) source.alu.source2IsImmediate else False)
    target.decoded.source2IsFour :=
      (if (portHasAlu) source.alu.source2IsFour else False)
    target.decoded.operation :=
      (if (portHasAlu) source.alu.operation else B(0, 14 bits))
    target.decoded.mulDivOperation :=
      (if (portHasMulDiv) source.mulDiv.operation else B(0, 4 bits))
    target.decoded.mulDivSigned :=
      (if (portHasMulDiv) source.mulDiv.signed else False)
    target.decoded.memorySize :=
      (if (portHasMemory) source.memory.memorySize else B(0, 2 bits))
    target.decoded.memorySignExtend :=
      (if (portHasMemory) source.memory.signExtend else False)
    target.decoded.writesGpr := False
    target.decoded.isLoad := (if (portHasMemory) source.memory.isLoad else False)
    target.decoded.isStore := (if (portHasMemory) source.memory.isStore else False)
    target.decoded.isBranch := (if (portHasBranch) source.branch.isBranch else False)
    target.decoded.branchKind :=
      (if (portHasBranch) source.branch.branchKind else U(0, 3 bits))
    target.decoded.isCsr := False
    target.decoded.isLl := (if (portHasMemory) source.memory.isLl else False)
    target.decoded.isSc := (if (portHasMemory) source.memory.isSc else False)
    target.decoded.isCacheOperation := False
    target.decoded.isPreload := False
    target.decoded.isErtn := False
    target.decoded.isTlbSearch := False
    target.decoded.isTlbWrite := False
    target.decoded.isTlbFill := False
    target.decoded.isTlbRead := False
    target.decoded.isTlbInvalidate := False
    target.decoded.isRefetch := False
    target.decoded.csrReadData := 0
    target.decoded.csrAddress :=
      (if (portHasSystem) source.system.csrAddress else U(0, 14 bits))
    target.decoded.csrWrite := False
    target.decoded.csrMask := (if (portHasSystem) source.system.csrMask else False)
    target.decoded.resultFromCsr :=
      (if (portHasSystem) source.system.resultFromCsr else False)
    target.decoded.systemOperation :=
      (if (portHasSystem) source.system.systemOperation else SystemOperation.none)
    target.decoded.serializing :=
      (if (portHasSystem) source.system.serializing else False)
    target.decoded.predictedTaken :=
      (if (portHasBranch) source.branch.predictedTaken else False)
    target.decoded.predictedTarget :=
      (if (portHasBranch) source.branch.predictedTarget else U(0, config.xlen bits))
    target.decoded.predictorMetadata := 0
    if (portHasMemory)
      target.decoded.exception.assignFromBits(B(0, target.decoded.exception.getBitsWidth bits))
    else target.decoded.exception := source.exception
    target.pdst := source.pdst
    target.oldPdst := 0
    target.psrc1 := source.psrc1
    target.psrc2 := source.psrc2
    target.source1Ready := source.source1Ready
    target.source2Ready := source.source2Ready
    target.robPointer := source.robPointer
    target.recoveryEpoch := source.recoveryEpoch
    target.loadQueueIndex := source.loadQueueIndex
    target.storeQueueIndex := source.storeQueueIndex
  }

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(log2Up(config.issueQueueEntriesPerPort) bits)
    selected := 0
    for (index <- (0 until config.issueQueueEntriesPerPort).reverse) {
      when(mask(index)) { selected := U(index, selected.getWidth bits) }
    }
    selected
  }

  val io = new Bundle {
    val enqueueValid = in Bool ()
    val enqueue = in(RenamedMicroOp(config))
    val enqueueReady = out Bool ()

    val wakeupValid = in Bits (config.writebackWidth bits)
    val wakeupPdst = in Vec (UInt(config.physicalRegIndexWidth bits), config.writebackWidth)

    val issueValid = out Bool ()
    val issue = out(RenamedMicroOp(config))
    val issueReady = in Bool ()
    val robHeadPointer = in UInt (config.robPointerWidth bits)
    val flush = in Bool ()
    val occupancy = out UInt (log2Up(config.issueQueueEntriesPerPort + 1) bits)
  }

  // Keep resident uops compacted in age order, as the ysyx issue queue does.
  // Wakeup-to-select therefore crosses one payload lookup instead of an age
  // lookup followed by a second physical-slot lookup.
  val queue = Vec.fill(config.issueQueueEntriesPerPort)(Reg(IssueEntry(config, portIndex)))
  val count = Reg(UInt(log2Up(config.issueQueueEntriesPerPort + 1) bits)) init (0)

  val wakeupEntry1 = Bits(config.issueQueueEntriesPerPort bits)
  val wakeupEntry2 = Bits(config.issueQueueEntriesPerPort bits)
  for (entry <- 0 until config.issueQueueEntriesPerPort) {
    wakeupEntry1(entry) := False
    wakeupEntry2(entry) := False
    for (write <- 0 until config.writebackWidth) {
      when(io.wakeupValid(write) && io.wakeupPdst(write) === queue(entry).psrc1) {
        wakeupEntry1(entry) := True
      }
      when(io.wakeupValid(write) && io.wakeupPdst(write) === queue(entry).psrc2) {
        wakeupEntry2(entry) := True
      }
    }
  }

  val readyMap = Bits(config.issueQueueEntriesPerPort bits)
  for (entry <- 0 until config.issueQueueEntriesPerPort) {
    val storeDataIsDecoupled =
      if (config.executionPorts(portIndex).capabilities.contains(ExecutionUnitKind.LoadStore)) {
        queue(entry).memory.isStore
      } else {
        False
      }
    readyMap(entry) := U(entry, count.getWidth bits) < count &&
      (queue(entry).source1Ready || wakeupEntry1(entry)) &&
      (storeDataIsDecoupled || queue(entry).source2Ready || wakeupEntry2(entry)) &&
      (!(if (portHasSystem) queue(entry).system.serializing else False) ||
        queue(entry).robPointer === io.robHeadPointer)
  }

  val issueIndex = selectLowest(readyMap)
  val issueIndexWide = UInt(count.getWidth bits)
  issueIndexWide := issueIndex.resize(count.getWidth)
  val selectedUop = RenamedMicroOp(config)
  unpackIssueEntry(selectedUop, queue(issueIndex))
  val queueDequeue = Bool()

  if (config.executionPorts(portIndex).registeredIssueOutput) {
    val outputSlots = Vec.fill(2)(Reg(IssueEntry(config, portIndex)))
    val outputReadPointer = RegInit(False)
    val outputWritePointer = RegInit(False)
    val outputCount = Reg(UInt(2 bits)) init (0)
    val outputEnqueueReady = RegInit(True)

    val outputHead = RenamedMicroOp(config)
    val selectedOutputEntry = IssueEntry(config, portIndex)
    selectedOutputEntry := outputSlots(0)
    when(outputReadPointer) { selectedOutputEntry := outputSlots(1) }
    unpackIssueEntry(outputHead, selectedOutputEntry)
    io.issueValid := outputCount =/= 0
    io.issue := outputHead

    val outputDequeue = io.issueValid && io.issueReady
    queueDequeue := outputEnqueueReady && readyMap.orR
    val nextOutputCount = UInt(outputCount.getWidth bits)
    nextOutputCount := outputCount + queueDequeue.asUInt - outputDequeue.asUInt

    when(io.flush) {
      outputCount := 0
      outputReadPointer := False
      outputWritePointer := False
      outputEnqueueReady := True
    }.otherwise {
      outputCount := nextOutputCount
      outputEnqueueReady := nextOutputCount < 2
      when(queueDequeue) { outputWritePointer := !outputWritePointer }
      when(outputDequeue) { outputReadPointer := !outputReadPointer }
    }
    // outputCount alone defines visibility.  Let an invalid slot absorb the
    // flush-edge payload write so redirect does not drive every payload CE.
    when(queueDequeue) {
      when(outputWritePointer) {
        outputSlots(1) := queue(issueIndex)
      }.otherwise {
        outputSlots(0) := queue(issueIndex)
      }
    }

    io.occupancy := (count + outputCount).resized
  } else {
    io.issueValid := readyMap.orR
    io.issue := selectedUop
    queueDequeue := io.issueValid && io.issueReady
    io.occupancy := count
  }

  // Register the backpressure boundary. Reserve one slot because the ready
  // value describes the previous cycle's count; one enqueue per cycle per IQ
  // cannot overrun the remaining slot.
  val enqueueReadyReg = Reg(Bool()) init (True)
  enqueueReadyReg := count < U(config.issueQueueEntriesPerPort - 1, count.getWidth bits)
  // Ready/valid are candidate handshakes during recovery.  The sequential
  // flush branches below have priority over every enqueue/dequeue update.
  io.enqueueReady := enqueueReadyReg
  val enqueueFire = io.enqueueValid && io.enqueueReady
  val enqueueIndex = UInt(log2Up(config.issueQueueEntriesPerPort) bits)
  enqueueIndex := count.resized
  when(queueDequeue) { enqueueIndex := (count - 1).resized }

  val enqueueWakeup1 = io.wakeupValid.asBools
    .zip(io.wakeupPdst)
    .map { case (wakeValid, pdst) => wakeValid && pdst === io.enqueue.psrc1 }
    .reduce(_ || _)
  val enqueueWakeup2 = io.wakeupValid.asBools
    .zip(io.wakeupPdst)
    .map { case (wakeValid, pdst) => wakeValid && pdst === io.enqueue.psrc2 }
    .reduce(_ || _)
  val enqueued = IssueEntry(config, portIndex)
  packIssueEntry(
    enqueued,
    io.enqueue,
    io.enqueue.source1Ready || enqueueWakeup1,
    io.enqueue.source2Ready || enqueueWakeup2
  )

  when(io.flush) {
    count := 0
  }.otherwise {
    count := count + enqueueFire.asUInt - queueDequeue.asUInt
  }
  // count alone defines resident entries.  Payload mutation on a flush edge
  // is harmless and keeps the redirect net out of every wide queue CE.
  for (entry <- 0 until config.issueQueueEntriesPerPort) {
    val entryEnqueue = enqueueFire &&
      enqueueIndex === U(entry, log2Up(config.issueQueueEntriesPerPort) bits)
    if (entry < config.issueQueueEntriesPerPort - 1) {
      val entryShift = queueDequeue &&
        U(entry, count.getWidth bits) >= issueIndexWide &&
        U(entry + 1, count.getWidth bits) < count
      when(entryEnqueue) {
        queue(entry) := enqueued
      }.elsewhen(entryShift) {
        copyIssueEntry(
          queue(entry),
          queue(entry + 1),
          queue(entry + 1).source1Ready || wakeupEntry1(entry + 1),
          queue(entry + 1).source2Ready || wakeupEntry2(entry + 1)
        )
      }.otherwise {
        when(wakeupEntry1(entry)) { queue(entry).source1Ready := True }
        when(wakeupEntry2(entry)) { queue(entry).source2Ready := True }
      }
    } else {
      when(entryEnqueue) {
        queue(entry) := enqueued
      }.otherwise {
        when(wakeupEntry1(entry)) { queue(entry).source1Ready := True }
        when(wakeupEntry2(entry)) { queue(entry).source2Ready := True }
      }
    }
  }
}

final class DispatchRouter(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private def accepts(port: ExecutionPortConfig, uop: RenamedMicroOp): Bool = {
    val acceptedKinds = port.capabilities.toVector.map {
      case ExecutionUnitKind.Alu       => uop.decoded.fuType === ExecutionUnitType.alu
      case ExecutionUnitKind.Branch    => uop.decoded.fuType === ExecutionUnitType.branch
      case ExecutionUnitKind.Multiply  => uop.decoded.fuType === ExecutionUnitType.multiply
      case ExecutionUnitKind.Divide    => uop.decoded.fuType === ExecutionUnitType.divide
      case ExecutionUnitKind.Csr       => uop.decoded.fuType === ExecutionUnitType.csr
      case ExecutionUnitKind.Serial    =>
        uop.decoded.fuType === ExecutionUnitType.serial || ExecutionUnitType.isBarrier(uop.decoded.fuType)
      case ExecutionUnitKind.LoadStore => uop.decoded.fuType === ExecutionUnitType.loadStore
    }
    acceptedKinds.reduce(_ || _)
  }

  private def selectLowest(mask: Bits): UInt = {
    val selected = UInt(log2Up(config.executionWidth) bits)
    selected := 0
    for (index <- (0 until config.executionWidth).reverse) {
      when(mask(index)) { selected := U(index, selected.getWidth bits) }
    }
    selected
  }

  val io = new Bundle {
    val inputValid = in Bits (config.dispatchWidth bits)
    val input = in Vec (RenamedMicroOp(config), config.dispatchWidth)
    val inputReady = out Bits (config.dispatchWidth bits)
    val portReady = in Bits (config.executionWidth bits)
    val portValid = out Bits (config.executionWidth bits)
    val portInput = out Vec (RenamedMicroOp(config), config.executionWidth)
  }

  val portUsed = Vec(Bits(config.executionWidth bits), config.dispatchWidth + 1)
  portUsed(0) := 0
  val laneOpen = Vec(Bool(), config.dispatchWidth + 1)
  laneOpen(0) := True
  val choices = Vec(Bits(config.executionWidth bits), config.dispatchWidth)
  for (lane <- 0 until config.dispatchWidth) {
    val capable = Bits(config.executionWidth bits)
    for (port <- 0 until config.executionWidth) {
      capable(port) := io.inputValid(lane) && io.portReady(port) &&
        accepts(config.executionPorts(port), io.input(lane))
    }
    val available = capable & ~portUsed(lane)
    choices(lane) := B(0, config.executionWidth bits)
    when(laneOpen(lane) && available.orR) {
      choices(lane) := UIntToOh(selectLowest(available), config.executionWidth)
    }
    portUsed(lane + 1) := portUsed(lane) | choices(lane)
    io.inputReady(lane) := laneOpen(lane) && available.orR
    laneOpen(lane + 1) := laneOpen(lane) && (!io.inputValid(lane) || available.orR)
  }

  for (port <- 0 until config.executionWidth) {
    io.portValid(port) := choices.map(_(port)).reduce(_ || _)
    io.portInput(port) := io.input(0)
    for (lane <- (0 until config.dispatchWidth).reverse) {
      when(choices(lane)(port)) { io.portInput(port) := io.input(lane) }
    }
  }
}
