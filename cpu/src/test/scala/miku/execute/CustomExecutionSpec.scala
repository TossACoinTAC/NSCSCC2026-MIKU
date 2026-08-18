package miku.execute

import miku.backend._
import miku.core._
import miku.frontend.La32rDecoder
import miku.predict._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private object CustomInstructionTestCatalog {
  private val mixEvaluator = CustomComputeEvaluator.from { (source1, source2, instruction) =>
    ((source1 ^ source2).asUInt + instruction(25 downto 15).asUInt).resize(32).asBits
  }

  val Mix: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "test-mix",
    matchValue = BigInt("d0000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = mixEvaluator
  )
  val StandardOpcodeOverride: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "standard-opcode-override",
    matchValue = BigInt("00100000", 16),
    matchMask = BigInt("ffff8000", 16),
    evaluator = CustomComputeEvaluators.xor,
    allowStandardOpcode = true
  )
  val PcImmediate: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "pc-immediate",
    matchValue = BigInt("d4000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.add,
    source1 = CustomRegister.Unused,
    source2 = CustomRegister.Unused,
    immediate = CustomImmediate.SignedI12,
    source2IsImmediate = true,
    source1IsPc = true
  )
  val CountLeadingZeros: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "count-leading-zeros",
    matchValue = BigInt("d8000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.countLeadingZeros,
    source2 = CustomRegister.Unused
  )
  val CountTrailingZeros: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "count-trailing-zeros",
    matchValue = BigInt("dc000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.countTrailingZeros,
    source2 = CustomRegister.Unused
  )
  val PopCount: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "pop-count",
    matchValue = BigInt("e0000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.popCount,
    source2 = CustomRegister.Unused
  )
  val RotateRight: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "rotate-right",
    matchValue = BigInt("e4000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.rotateRight
  )
  val Parity: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "parity",
    matchValue = BigInt("e8000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.parity,
    source2 = CustomRegister.Unused
  )
  val ReadModifyWrite: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "read-modify-write",
    matchValue = BigInt("ec000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluators.add,
    source1 = CustomRegister.Rd,
    source2 = CustomRegister.Rj,
    destination = CustomRegister.Rd
  )
  val Rriwinz: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "rriwinz-test-only",
    matchValue = BigInt("e0000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluator.from { (oldRd, rj, instruction) =>
      val rjBase = instruction(14 downto 10).asUInt
      val offset = instruction(19 downto 15).asUInt
      val rdBase = instruction(24 downto 20).asUInt
      val rotateAmount = CustomBitFieldHelpers.popCountWithin(rj, rjBase, offset)
      CustomBitFieldHelpers.rotateRightWithin(oldRd, rdBase, offset, rotateAmount)
    },
    source1 = CustomRegister.Rd,
    source2 = CustomRegister.Rj,
    destination = CustomRegister.Rd,
    immediate = CustomImmediate.RawI16
  )
  val BitFieldPopCount: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "bit-field-pop-count",
    matchValue = BigInt("b4000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluator.from { (source1, _, instruction) =>
      CustomBitFieldHelpers
        .popCountWithin(
          source1,
          instruction(19 downto 15).asUInt,
          instruction(24 downto 20).asUInt
        )
        .resize(32)
        .asBits
    },
    source2 = CustomRegister.Unused
  )
  val BitFieldRotate: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "bit-field-rotate",
    matchValue = BigInt("b8000000", 16),
    matchMask = BigInt("fc000000", 16),
    evaluator = CustomComputeEvaluator.from { (source1, source2, instruction) =>
      CustomBitFieldHelpers.rotateRightWithin(
        source1,
        instruction(19 downto 15).asUInt,
        instruction(24 downto 20).asUInt,
        source2(5 downto 0).asUInt
      )
    }
  )
  val BranchEqual: CustomInstructionSpec = CustomInstructionSpec.branch(
    name = "branch-equal",
    matchValue = BigInt("c0000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    source2 = CustomRegister.Rd,
    immediate = CustomImmediate.SignedI16Shift2,
    branchKind = CustomBranchKind.Equal
  )
  val LoadByte: CustomInstructionSpec = CustomInstructionSpec.load(
    name = "load-byte",
    matchValue = BigInt("c4000000", 16),
    matchMask = BigInt("fc000000", 16),
    immediate = CustomImmediate.SignedI12,
    memorySize = CustomMemorySize.Byte,
    signExtend = true
  )
  val StoreHalf: CustomInstructionSpec = CustomInstructionSpec.store(
    name = "store-half",
    matchValue = BigInt("c8000000", 16),
    matchMask = BigInt("fc000000", 16),
    immediate = CustomImmediate.SignedI12,
    memorySize = CustomMemorySize.Half
  )
  val BranchSourceZero: CustomInstructionSpec = CustomInstructionSpec.branch(
    name = "branch-source-zero",
    matchValue = BigInt("cc000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    immediate = CustomImmediate.SignedI16Shift2,
    evaluator = Some(CustomBranchEvaluators.source1Zero)
  )
  val IndirectBranch: CustomInstructionSpec = CustomInstructionSpec.branch(
    name = "indirect-branch",
    matchValue = BigInt("f0000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    immediate = CustomImmediate.SignedI16Shift2,
    branchKind = CustomBranchKind.RegisterIndirect
  )
  val ByteSwap: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "byte-swap",
    matchValue = BigInt("f4000000", 16),
    matchMask = BigInt("ffc00000", 16),
    evaluator = CustomComputeEvaluators.byteSwap,
    source2 = CustomRegister.Unused
  )
  val BitReverse: CustomInstructionSpec = CustomInstructionSpec.compute(
    name = "bit-reverse",
    matchValue = BigInt("f4400000", 16),
    matchMask = BigInt("ffc00000", 16),
    evaluator = CustomComputeEvaluators.bitReverse,
    source2 = CustomRegister.Unused
  )
  val BranchLink: CustomInstructionSpec = CustomInstructionSpec.branch(
    name = "branch-link",
    matchValue = BigInt("fc000000", 16),
    matchMask = BigInt("fc000000", 16),
    destination = CustomRegister.Fixed(1),
    immediate = CustomImmediate.SignedI26Shift2
  )
  val PredicateIndirect: CustomInstructionSpec = CustomInstructionSpec.branch(
    name = "predicate-indirect",
    matchValue = BigInt("bc000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    immediate = CustomImmediate.SignedI16Shift2,
    branchKind = CustomBranchKind.RegisterIndirect,
    evaluator = Some(CustomBranchEvaluators.source1NonZero)
  )

  val Profile: CustomInstructionProfile = CustomInstructionProfile(
    "test-all",
    Vector(
      Mix,
      StandardOpcodeOverride,
      PcImmediate,
      CountLeadingZeros,
      CountTrailingZeros,
      PopCount,
      RotateRight,
      Parity,
      ReadModifyWrite,
      BitFieldPopCount,
      BitFieldRotate,
      BranchEqual,
      LoadByte,
      StoreHalf,
      BranchSourceZero,
      IndirectBranch,
      ByteSwap,
      BitReverse,
      BranchLink,
      PredicateIndirect
    )
  )

  val RriwinzProfile: CustomInstructionProfile =
    CustomInstructionProfile("test-rriwinz", Vector(Rriwinz))

  def encodeMix(rd: Int, rj: Int, rk: Int, payload: Int): BigInt =
    Mix.matchValue | (BigInt(payload) << 15) | (BigInt(rk) << 10) |
      (BigInt(rj) << 5) | rd

  def encodeReadModifyWrite(rd: Int, rj: Int): BigInt =
    ReadModifyWrite.matchValue | (BigInt(rj) << 5) | rd

  def encodeRriwinz(
      rd: Int,
      rj: Int,
      rjBase: Int,
      offset: Int,
      rdBase: Int
  ): BigInt = {
    val i16 = BigInt(rjBase) | (BigInt(offset) << 5) | (BigInt(rdBase) << 10)
    Rriwinz.matchValue | (i16 << 10) | (BigInt(rj) << 5) | rd
  }
}

private final class CustomExecutionProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val decodedRd = out UInt (config.archRegIndexWidth bits)
    val decodedRs1 = out UInt (config.archRegIndexWidth bits)
    val decodedRs2 = out UInt (config.archRegIndexWidth bits)
    val decodedImmediate = out Bits (config.xlen bits)
    val source1Used = out Bool ()
    val source2Used = out Bool ()
    val writesGpr = out Bool ()
    val fuType = out UInt (ExecutionUnitType.Width bits)
    val exceptionValid = out Bool ()
    val exceptionCode = out UInt (6 bits)
    val isLoad = out Bool ()
    val isStore = out Bool ()
    val isBranch = out Bool ()
    val branchKind = out UInt (3 bits)
    val isCsr = out Bool ()
    val serializing = out Bool ()
    val systemOperation = out UInt (SystemOperation.Width bits)
    val memorySize = out Bits (2 bits)
    val memorySignExtend = out Bool ()
    val result = out Bits (config.xlen bits)
    val predecodeValid = out Bool ()
    val predecodeType = out UInt (PredictedBranchType.Width bits)
    val predecodeTarget = out UInt (config.xlen bits)
    val predecodeStaticTaken = out Bool ()
    val predecodeIndirect = out Bool ()
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val alu = new Alu
  val aluSource1 = Mux(decoder.io.decoded.source1IsPc, decoder.io.decoded.pc.asBits, io.source1)
  val aluSource2 = Mux(
    decoder.io.decoded.source2IsImmediate,
    decoder.io.decoded.immediate,
    Mux(decoder.io.decoded.source2IsFour, B(4, config.xlen bits), io.source2)
  )
  alu.io.alu_op := decoder.io.decoded.operation
  alu.io.alu_src1 := aluSource1
  alu.io.alu_src2 := aluSource2

  val predecode = FetchPredecode(config)
  FetchPredecoder.drive(predecode, config, U(config.resetVector, config.xlen bits), io.instruction)

  io.decodedRd := decoder.io.decoded.rd
  io.decodedRs1 := decoder.io.decoded.rs1
  io.decodedRs2 := decoder.io.decoded.rs2
  io.decodedImmediate := decoder.io.decoded.immediate
  io.source1Used := decoder.io.decoded.source1Used
  io.source2Used := decoder.io.decoded.source2Used
  io.writesGpr := decoder.io.decoded.writesGpr
  io.fuType := decoder.io.decoded.fuType
  io.exceptionValid := decoder.io.decoded.exception.valid
  io.exceptionCode := decoder.io.decoded.exception.ecode
  io.isLoad := decoder.io.decoded.isLoad
  io.isStore := decoder.io.decoded.isStore
  io.isBranch := decoder.io.decoded.isBranch
  io.branchKind := decoder.io.decoded.branchKind
  io.isCsr := decoder.io.decoded.isCsr
  io.serializing := decoder.io.decoded.serializing
  io.systemOperation := decoder.io.decoded.systemOperation
  io.memorySize := decoder.io.decoded.memorySize
  io.memorySignExtend := decoder.io.decoded.memorySignExtend
  io.result := CustomExecution.computeResult(
    config,
    decoder.io.decoded,
    aluSource1,
    aluSource2,
    alu.io.alu_result
  )
  io.predecodeValid := predecode.valid
  io.predecodeType := predecode.branchType
  io.predecodeTarget := predecode.target
  io.predecodeStaticTaken := predecode.staticTaken
  io.predecodeIndirect := predecode.indirect
}

private final class CustomDispatchProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val inputValid = in Bool ()
    val portReady = in Bits (config.executionWidth bits)
    val inputReady = out Bool ()
    val selectedPorts = out Bits (config.executionWidth bits)
  }
  noIoPrefix()

  val router = new DispatchRouter(config)
  router.io.inputValid := 0
  router.io.inputValid(0) := io.inputValid
  router.io.portReady := io.portReady
  for (lane <- 0 until config.dispatchWidth) {
    router.io.input(lane).assignFromBits(B(0, router.io.input(lane).getBitsWidth bits))
  }
  router.io.input(0).decoded.instruction.allowOverride()
  router.io.input(0).decoded.fuType.allowOverride()
  router.io.input(0).decoded.instruction := io.instruction
  router.io.input(0).decoded.fuType := ExecutionUnitType.alu

  io.inputReady := router.io.inputReady(0)
  io.selectedPorts := router.io.portValid
}

private final class CustomBackendPathProbe(config: OooCoreConfig) extends Component {
  private val branchPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Branch))
  private val loadStorePort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.LoadStore))
  require(branchPort >= 0 && loadStorePort >= 0 && branchPort != loadStorePort)

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val source1 = in Bits (config.xlen bits)
    val source2 = in Bits (config.xlen bits)
    val issueValid = in Bool ()
    val predictedTaken = in Bool ()
    val predictedTarget = in UInt (config.xlen bits)
    val issueReady = out Bool ()
    val branchCompletionValid = out Bool ()
    val branchResolved = out Bool ()
    val branchTaken = out Bool ()
    val branchTarget = out UInt (config.xlen bits)
    val branchMispredict = out Bool ()
    val branchWritesPdst = out Bool ()
    val branchResult = out Bits (config.xlen bits)
    val aguValid = out Bool ()
    val aguVirtualAddress = out UInt (config.xlen bits)
    val aguIsWrite = out Bool ()
    val aguSize = out Bits (3 bits)
    val aguByteMask = out Bits (4 bits)
    val aguWriteData = out Bits (config.xlen bits)
    val memorySignExtend = out Bool ()
    val computeResult = out Bits (config.xlen bits)
    val exceptionValid = out Bool ()
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := io.predictedTaken
  decoder.io.predictedTarget := io.predictedTarget
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val alu = new Alu
  val aluSource1 = Mux(decoder.io.decoded.source1IsPc, decoder.io.decoded.pc.asBits, io.source1)
  val aluSource2 = Mux(
    decoder.io.decoded.source2IsImmediate,
    decoder.io.decoded.immediate,
    Mux(decoder.io.decoded.source2IsFour, B(4, config.xlen bits), io.source2)
  )
  alu.io.alu_op := decoder.io.decoded.operation
  alu.io.alu_src1 := aluSource1
  alu.io.alu_src2 := aluSource2

  val execution = new OooExecutionCluster(config)
  execution.io.issueValid := 0
  val isBranch = decoder.io.decoded.fuType === ExecutionUnitType.branch
  val isMemory = decoder.io.decoded.fuType === ExecutionUnitType.loadStore &&
    (decoder.io.decoded.isLoad || decoder.io.decoded.isStore)
  execution.io.issueValid(branchPort) := io.issueValid && isBranch
  execution.io.issueValid(loadStorePort) := io.issueValid && isMemory
  for (port <- 0 until config.executionWidth) {
    if (port == branchPort || port == loadStorePort) {
      execution.io.issue(port).decoded := decoder.io.decoded
      execution.io.issue(port).pdst := Mux(
        decoder.io.decoded.writesGpr,
        U(9, config.physicalRegIndexWidth bits),
        U(0, config.physicalRegIndexWidth bits)
      )
      execution.io.issue(port).oldPdst := 0
      execution.io.issue(port).psrc1 := 0
      execution.io.issue(port).psrc2 := 0
      execution.io.issue(port).source1Ready := True
      execution.io.issue(port).source2Ready := True
      execution.io.issue(port).robPointer := 3
      execution.io.issue(port).recoveryEpoch := 0
      execution.io.issue(port).loadQueueIndex := 1
      execution.io.issue(port).storeQueueIndex := 2
      execution.io.source1(port) := io.source1
      execution.io.source2(port) := io.source2
    } else {
      execution.io.issue(port).assignFromBits(B(0, execution.io.issue(port).getBitsWidth bits))
      execution.io.source1(port) := 0
      execution.io.source2(port) := 0
    }
  }
  execution.io.flush := False
  execution.io.systemReadData := 0
  execution.io.timer := 0
  execution.io.timerId := 0
  execution.io.aguReady := True
  execution.io.loadStoreCompletionValid := False
  execution.io.loadStoreCompletion.assignFromBits(
    B(0, execution.io.loadStoreCompletion.getBitsWidth bits)
  )
  execution.io.olderStorePending := False
  execution.io.memorySubsystemIdle := True
  execution.io.instructionBarrierMaintenanceReady := True
  execution.io.instructionBarrierMaintenanceDone := False
  execution.io.cacheTranslationRequest.ready := True
  execution.io.cacheTranslationResponse.valid := False
  execution.io.cacheTranslationResponse.payload.assignFromBits(
    B(0, execution.io.cacheTranslationResponse.payload.getBitsWidth bits)
  )
  execution.io.cacheMaintenanceRequest.ready := True
  execution.io.cacheMaintenanceResponse.valid := False
  execution.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, execution.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )

  io.issueReady := Mux(
    isBranch,
    execution.io.issueReady(branchPort),
    execution.io.issueReady(loadStorePort)
  )
  io.branchCompletionValid := execution.io.completionValid(branchPort)
  io.branchResolved := execution.io.completion(branchPort).branchResolved
  io.branchTaken := execution.io.completion(branchPort).branchTaken
  io.branchTarget := execution.io.completion(branchPort).branchTarget
  io.branchMispredict := execution.io.completion(branchPort).branchMispredict
  io.branchWritesPdst := execution.io.completion(branchPort).writesPdst
  io.branchResult := execution.io.completion(branchPort).data
  io.aguValid := execution.io.aguValid
  io.aguVirtualAddress := execution.io.agu.virtualAddress
  io.aguIsWrite := execution.io.agu.isWrite
  io.aguSize := execution.io.agu.size
  io.aguByteMask := execution.io.agu.byteMask
  io.aguWriteData := execution.io.agu.writeData
  io.memorySignExtend := decoder.io.decoded.memorySignExtend
  io.computeResult := CustomExecution.computeResult(
    config,
    decoder.io.decoded,
    aluSource1,
    aluSource2,
    alu.io.alu_result
  )
  io.exceptionValid := decoder.io.decoded.exception.valid
}

private final class CustomIssueQueueInstructionProbe(config: OooCoreConfig) extends Component {
  private val branchPort =
    config.executionPorts.indexWhere(_.capabilities.contains(ExecutionUnitKind.Branch))
  require(branchPort >= 0)

  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val enqueueValid = in Bool ()
    val enqueueReady = out Bool ()
    val issueReady = in Bool ()
    val issueValid = out Bool ()
    val issueInstruction = out Bits (32 bits)
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val queue = new IssueQueue(config, branchPort)
  queue.io.enqueueValid := io.enqueueValid
  queue.io.enqueue.decoded := decoder.io.decoded
  queue.io.enqueue.pdst := 0
  queue.io.enqueue.oldPdst := 0
  queue.io.enqueue.psrc1 := 1
  queue.io.enqueue.psrc2 := 2
  queue.io.enqueue.source1Ready := True
  queue.io.enqueue.source2Ready := True
  queue.io.enqueue.robPointer := 0
  queue.io.enqueue.recoveryEpoch := 0
  queue.io.enqueue.loadQueueIndex := 0
  queue.io.enqueue.storeQueueIndex := 0
  queue.io.wakeupValid := 0
  queue.io.selectWakeupValid := 0
  for (lane <- 0 until config.writebackWidth) {
    queue.io.wakeupPdst(lane) := 0
    queue.io.selectWakeupPdst(lane) := 0
  }
  queue.io.issueReady := io.issueReady
  queue.io.robHeadPointer := 0
  queue.io.flush := False

  io.enqueueReady := queue.io.enqueueReady
  io.issueValid := queue.io.issueValid
  io.issueInstruction := queue.io.issue.decoded.instruction
}

private final class CustomRetirementProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val renameValid = in Bits (config.renameWidth bits)
    val pc = in Vec (UInt(config.xlen bits), config.renameWidth)
    val instruction = in Vec (Bits(32 bits), config.renameWidth)
    val renameReady = out Bits (config.renameWidth bits)
    val debugReadAddress = in UInt (config.archRegIndexWidth bits)
    val debugReadData = out Bits (config.xlen bits)
    val commitValid = out Bits (config.commitWidth bits)
    val commit = out Vec (CommitRecord(config), config.commitWidth)
  }
  noIoPrefix()

  val backend = new OooBackendWithExecution(config)
  val decoders = Seq.fill(config.renameWidth)(new La32rDecoder(config))
  for (lane <- 0 until config.renameWidth) {
    val decoder = decoders(lane)
    decoder.io.pc := io.pc(lane)
    decoder.io.instruction := io.instruction(lane)
    decoder.io.fetchSlot := lane
    decoder.io.predictedTaken := False
    decoder.io.predictedTarget := io.pc(lane) + 4
    decoder.io.predictorMetadata := 0
    decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
    decoder.io.privilege := 0
    decoder.io.interruptPending := False
    backend.io.rename(lane) := decoder.io.decoded
  }

  backend.io.renameValid := io.renameValid
  backend.io.dataRequestReady := True
  backend.io.dataResponseValid := False
  backend.io.dataResponse.assignFromBits(B(0, backend.io.dataResponse.getBitsWidth bits))
  backend.io.translationRequest.ready := True
  backend.io.translationResponse.valid := False
  backend.io.translationResponse.payload.assignFromBits(
    B(0, backend.io.translationResponse.payload.getBitsWidth bits)
  )
  backend.io.translationBypass.assignFromBits(B(0, backend.io.translationBypass.getBitsWidth bits))
  backend.io.reservationValid := False
  backend.io.reservationLineAddress := 0
  backend.io.systemReadData := 0
  backend.io.timer := 0
  backend.io.timerId := 0
  backend.io.debugReadAddress := io.debugReadAddress
  backend.io.predictorUpdateCapacity := config.commitWidth
  backend.io.memorySubsystemIdle := True
  backend.io.instructionBarrierMaintenanceReady := True
  backend.io.instructionBarrierMaintenanceDone := False
  backend.io.cacheMaintenanceRequest.ready := True
  backend.io.cacheMaintenanceResponse.valid := False
  backend.io.cacheMaintenanceResponse.payload.assignFromBits(
    B(0, backend.io.cacheMaintenanceResponse.payload.getBitsWidth bits)
  )
  backend.io.flush := False

  io.renameReady := backend.io.renameReady
  io.debugReadData := backend.io.debugReadData
  io.commitValid := backend.io.commitValid
  io.commit := backend.io.commit
}

private final class CustomRobPredictorProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val allocateValid = in Bool ()
    val completionValid = in Bool ()
    val allocatedPointer = out UInt (config.robPointerWidth bits)
    val commitValid = out Bool ()
    val commitPredictorType = out UInt (PredictedBranchType.Width bits)
  }
  noIoPrefix()

  val decoder = new La32rDecoder(config)
  decoder.io.pc := U(config.resetVector, config.xlen bits)
  decoder.io.instruction := io.instruction
  decoder.io.fetchSlot := 0
  decoder.io.predictedTaken := False
  decoder.io.predictedTarget := U(config.resetVector + 4, config.xlen bits)
  decoder.io.predictorMetadata := 0
  decoder.io.fetchException.assignFromBits(B(0, decoder.io.fetchException.getBitsWidth bits))
  decoder.io.privilege := 0
  decoder.io.interruptPending := False

  val rob = new ReorderBuffer(config)
  rob.io.allocateValid := 0
  rob.io.allocateValid(0) := io.allocateValid
  rob.io.allocateAccept := io.allocateValid && rob.io.allocateReady
  val allocatedPointer = RegNextWhen(
    rob.io.allocatedPointer(0),
    rob.io.allocateAccept && rob.io.allocateValid(0)
  ) init (0)
  for (lane <- 0 until config.renameWidth) {
    if (lane == 0) {
      rob.io.allocate(lane).uop.decoded := decoder.io.decoded
      rob.io.allocate(lane).uop.pdst := 0
      rob.io.allocate(lane).uop.oldPdst := 0
      rob.io.allocate(lane).uop.psrc1 := 0
      rob.io.allocate(lane).uop.psrc2 := 0
      rob.io.allocate(lane).uop.source1Ready := True
      rob.io.allocate(lane).uop.source2Ready := True
      rob.io.allocate(lane).uop.robPointer := 0
      rob.io.allocate(lane).uop.recoveryEpoch := 0
      rob.io.allocate(lane).uop.loadQueueIndex := 0
      rob.io.allocate(lane).uop.storeQueueIndex := 0
    } else {
      rob.io.allocate(lane).assignFromBits(B(0, rob.io.allocate(lane).getBitsWidth bits))
    }
  }

  rob.io.completionValid := 0
  rob.io.completionValid(0) := io.completionValid
  for (lane <- 0 until config.writebackWidth) {
    if (lane == 0) {
      rob.io.completion(lane).robPointer := allocatedPointer
      rob.io.completion(lane).recoveryEpoch := 0
      rob.io.completion(lane).pdst := 0
      rob.io.completion(lane).writesPdst := False
      rob.io.completion(lane).data := 0
      rob.io.completion(lane).sideEffectData := 0
      rob.io.completion(lane).exception.assignFromBits(
        B(0, rob.io.completion(lane).exception.getBitsWidth bits)
      )
      rob.io.completion(lane).branchResolved := True
      rob.io.completion(lane).branchTaken := True
      rob.io.completion(lane).branchTarget := U(config.resetVector + 0x40, config.xlen bits)
      rob.io.completion(lane).branchMispredict := False
    } else {
      rob.io.completion(lane).assignFromBits(B(0, rob.io.completion(lane).getBitsWidth bits))
    }
  }
  rob.io.storeCompletionBypassValid := False
  rob.io.storeCompletionBypass.assignFromBits(
    B(0, rob.io.storeCompletionBypass.getBitsWidth bits)
  )
  rob.io.currentEpoch := 0
  rob.io.predictorUpdateCapacity := config.commitWidth
  rob.io.flush := False

  io.allocatedPointer := rob.io.allocatedPointer(0)
  io.commitValid := rob.io.commitValid(0)
  io.commitPredictorType := rob.io.commit(0).predictorType
}

class CustomExecutionSpec extends AnyFunSuite {
  private val wordMask = (BigInt(1) << 32) - 1
  private val config = OooCoreConfig.FourIssueThreeCommit.copy(
    customInstructionProfile = CustomInstructionTestCatalog.Profile
  )

  test("registered contest profiles execute their directed verification cases") {
    val profiles = ContestCustomInstructionProfiles.Available
    val verificationCases = ContestCustomInstructionProfiles.VerificationCases
    CustomInstructionVerificationCase.validateCoverage(profiles, verificationCases)

    for (profile <- profiles) {
      val profileCases = verificationCases.filter(_.profileName == profile.name)
      val profileConfig = OooCoreConfig.FourIssueThreeCommit.copy(
        customInstructionProfile = profile
      )
      val safeProfileName = profile.name.replaceAll("[^A-Za-z0-9_.-]", "_")
      val seed = 0x4c90 ^ profile.name.hashCode
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-custom-profile-$safeProfileName"
        )
        .compile(new CustomBackendPathProbe(profileConfig))
        .doSim(s"custom-profile-$safeProfileName", seed) { dut =>
          dut.io.issueValid #= true
          dut.io.predictedTaken #= false
          dut.io.predictedTarget #= profileConfig.resetVector + 4

          for (verification <- profileCases) {
            val specification = profile.specifications
              .find(_.name == verification.specificationName)
              .getOrElse(fail(s"missing specification ${verification.specificationName}"))
            dut.io.instruction #= verification.instruction
            dut.io.source1 #= verification.source1
            dut.io.source2 #= verification.source2
            sleep(1)

            assert(
              !dut.io.exceptionValid.toBoolean,
              s"${profile.name}/${specification.name} decoded as an exception"
            )
            verification match {
              case compute: CustomInstructionVerificationCase.Compute =>
                assert(
                  dut.io.computeResult.toBigInt == compute.expectedResult,
                  f"${profile.name}/${specification.name} result " +
                    f"0x${dut.io.computeResult.toBigInt}%08x != 0x${compute.expectedResult}%08x"
                )

              case branch: CustomInstructionVerificationCase.Branch =>
                assert(dut.io.issueReady.toBoolean)
                assert(dut.io.branchCompletionValid.toBoolean)
                assert(dut.io.branchResolved.toBoolean)
                assert(dut.io.branchTaken.toBoolean == branch.expectedTaken)
                assert(dut.io.branchTarget.toBigInt == branch.expectedTarget)
                assert(dut.io.branchWritesPdst.toBoolean == specification.writesGpr)
                if (specification.writesGpr) {
                  assert(
                    dut.io.branchResult.toBigInt ==
                      ((profileConfig.resetVector + 4) & wordMask)
                  )
                }

              case memory: CustomInstructionVerificationCase.Memory =>
                val expectedSize = specification.memorySize match {
                  case CustomMemorySize.Byte => 0
                  case CustomMemorySize.Half => 1
                  case CustomMemorySize.Word => 2
                  case invalid => fail(s"invalid memory size $invalid")
                }
                assert(dut.io.issueReady.toBoolean)
                assert(dut.io.aguValid.toBoolean)
                assert(dut.io.aguVirtualAddress.toBigInt == memory.expectedAddress)
                assert(
                  dut.io.aguIsWrite.toBoolean ==
                    (specification.kind == CustomInstructionKind.Store)
                )
                assert(dut.io.aguSize.toBigInt == expectedSize)
                assert(dut.io.aguByteMask.toBigInt == memory.expectedByteMask)
                assert(dut.io.memorySignExtend.toBoolean == specification.memorySignExtend)
                if (specification.kind == CustomInstructionKind.Store) {
                  assert(dut.io.aguWriteData.toBigInt == memory.expectedWriteData)
                }
            }
          }
        }
    }
  }

  test("disabled and enabled profiles preserve explicit decode selection") {
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-disabled")
      .compile(new CustomExecutionProbe(OooCoreConfig.FourIssueThreeCommit))
      .doSim("custom-disabled", 0x4c81) { dut =>
        dut.io.instruction #= CustomInstructionTestCatalog.encodeMix(3, 4, 5, 7)
        dut.io.source1 #= BigInt("12345678", 16)
        dut.io.source2 #= BigInt("89abcdef", 16)
        sleep(1)
        assert(dut.io.exceptionValid.toBoolean)
        assert(dut.io.exceptionCode.toBigInt == 0x0d)
        assert(!dut.io.writesGpr.toBoolean)
      }
  }

  test("compute evaluators decode directed and random values including old rd") {
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-compute")
      .compile(new CustomExecutionProbe(config))
      .doSim("custom-compute", 0x4c82) { dut =>
        val random = new scala.util.Random(0x4c82)
        for (_ <- 0 until 128) {
          val rd = random.nextInt(32)
          val rj = random.nextInt(32)
          val rk = random.nextInt(32)
          val payload = random.nextInt(1 << 11)
          val source1 = BigInt(random.nextInt()) & wordMask
          val source2 = BigInt(random.nextInt()) & wordMask
          dut.io.instruction #= CustomInstructionTestCatalog.encodeMix(rd, rj, rk, payload)
          dut.io.source1 #= source1
          dut.io.source2 #= source2
          sleep(1)
          assert(!dut.io.exceptionValid.toBoolean)
          assert(dut.io.decodedRd.toBigInt == rd)
          assert(dut.io.decodedRs1.toBigInt == rj)
          assert(dut.io.decodedRs2.toBigInt == rk)
          assert(dut.io.fuType.toBigInt == 0)
          assert(dut.io.result.toBigInt == (((source1 ^ source2) + payload) & wordMask))
        }

        val signedImmediate = -17
        dut.io.instruction #= CustomInstructionTestCatalog.PcImmediate.matchValue |
          (BigInt(signedImmediate & 0xfff) << 10) | 9
        dut.io.source1 #= BigInt("aaaaaaaa", 16)
        dut.io.source2 #= BigInt("55555555", 16)
        sleep(1)
        assert(dut.io.result.toBigInt == ((config.resetVector + signedImmediate) & wordMask))

        dut.io.instruction #= CustomInstructionTestCatalog.encodeReadModifyWrite(rd = 7, rj = 8)
        dut.io.source1 #= 5
        dut.io.source2 #= 3
        sleep(1)
        assert(dut.io.decodedRd.toBigInt == 7)
        assert(dut.io.decodedRs1.toBigInt == 7)
        assert(dut.io.decodedRs2.toBigInt == 8)
        assert(dut.io.source1Used.toBoolean && dut.io.source2Used.toBoolean)
        assert(dut.io.result.toBigInt == 8)

        dut.io.instruction #= CustomInstructionTestCatalog.StandardOpcodeOverride.matchValue |
          (BigInt(5) << 10) | (BigInt(4) << 5) | 3
        dut.io.source1 #= BigInt("12345678", 16)
        dut.io.source2 #= BigInt("89abcdef", 16)
        sleep(1)
        assert(!dut.io.exceptionValid.toBoolean)
        assert(dut.io.result.toBigInt == BigInt("9b9f9b97", 16))

        val values = Seq(
          BigInt(0),
          BigInt(1),
          BigInt("80000000", 16),
          BigInt("ffffffff", 16),
          BigInt("01234560", 16)
        )
        for (value <- values) {
          dut.io.source1 #= value
          dut.io.source2 #= 0
          dut.io.instruction #= CustomInstructionTestCatalog.PopCount.matchValue | (4 << 5) | 3
          sleep(1)
          assert(dut.io.result.toBigInt == value.bitCount)

          dut.io.instruction #= CustomInstructionTestCatalog.CountLeadingZeros.matchValue |
            (4 << 5) | 3
          sleep(1)
          val leading = if (value == 0) 32 else 32 - value.bitLength
          assert(dut.io.result.toBigInt == leading)

          dut.io.instruction #= CustomInstructionTestCatalog.CountTrailingZeros.matchValue |
            (4 << 5) | 3
          sleep(1)
          val trailing = if (value == 0) 32 else (0 until 32).find(bit => value.testBit(bit)).get
          assert(dut.io.result.toBigInt == trailing)

          dut.io.instruction #= CustomInstructionTestCatalog.Parity.matchValue | (4 << 5) | 3
          sleep(1)
          assert(dut.io.result.toBigInt == (value.bitCount & 1))

          for (shift <- Seq(0, 1, 7, 16, 31)) {
            dut.io.instruction #= CustomInstructionTestCatalog.RotateRight.matchValue |
              (5 << 10) | (4 << 5) | 3
            dut.io.source2 #= shift
            sleep(1)
            val expected = ((value >> shift) | (value << (32 - shift))) & wordMask
            assert(dut.io.result.toBigInt == expected)
          }

          dut.io.instruction #= CustomInstructionTestCatalog.ByteSwap.matchValue | (4 << 5) | 3
          sleep(1)
          val byteSwapped = (0 until 4).foldLeft(BigInt(0)) { (result, byte) =>
            result | (((value >> (byte * 8)) & 0xff) << ((3 - byte) * 8))
          }
          assert(dut.io.result.toBigInt == byteSwapped)

          dut.io.instruction #= CustomInstructionTestCatalog.BitReverse.matchValue | (4 << 5) | 3
          sleep(1)
          val bitReversed = (0 until 32).foldLeft(BigInt(0)) { (result, bit) =>
            (result << 1) | ((value >> bit) & 1)
          }
          assert(dut.io.result.toBigInt == bitReversed)
        }

        val bitFieldCases = Seq(
          (BigInt("12345678", 16), 0, 0, 0),
          (BigInt("89abcdef", 16), 5, 8, 0),
          (BigInt("89abcdef", 16), 5, 8, 8),
          (BigInt("f1234567", 16), 28, 8, 1),
          (BigInt("80000001", 16), 31, 31, 63),
          (BigInt("7fffffff", 16), 0, 31, 63)
        ) ++ Seq.fill(128) {
          (
            BigInt(random.nextInt()) & wordMask,
            random.nextInt(32),
            random.nextInt(32),
            random.nextInt(64)
          )
        }
        for ((value, base, requestedWidth, shift) <- bitFieldCases) {
          val effectiveWidth = math.min(requestedWidth, 32 - base)
          val mask = if (effectiveWidth == 0) BigInt(0) else (BigInt(1) << effectiveWidth) - 1
          val field = (value >> base) & mask
          val encodedFields = (BigInt(requestedWidth) << 20) | (BigInt(base) << 15)

          dut.io.instruction #= CustomInstructionTestCatalog.BitFieldPopCount.matchValue |
            encodedFields | (4 << 5) | 3
          dut.io.source1 #= value
          dut.io.source2 #= shift
          sleep(1)
          assert(dut.io.result.toBigInt == field.bitCount)

          dut.io.instruction #= CustomInstructionTestCatalog.BitFieldRotate.matchValue |
            encodedFields | (BigInt(5) << 10) | (4 << 5) | 3
          sleep(1)
          val amount = if (effectiveWidth == 0) 0 else shift % effectiveWidth
          val rotatedField =
            if (effectiveWidth == 0 || amount == 0) field
            else ((field >> amount) | (field << (effectiveWidth - amount))) & mask
          val placedMask = mask << base
          val expected = (value & (~placedMask & wordMask)) | (rotatedField << base)
          assert(dut.io.result.toBigInt == expected)
        }

        val branchImmediate = (-7) & 0xffff
        dut.io.instruction #= CustomInstructionTestCatalog.BranchEqual.matchValue |
          (BigInt(branchImmediate) << 10) | (6 << 5) | 5
        sleep(1)
        assert(dut.io.isBranch.toBoolean)
        assert(dut.io.fuType.toBigInt == 1)
        assert(dut.io.predecodeValid.toBoolean)
        assert(dut.io.predecodeType.toBigInt == 0)
        assert(dut.io.predecodeTarget.toBigInt == ((config.resetVector - 28) & wordMask))
        assert(dut.io.predecodeStaticTaken.toBoolean)

        dut.io.instruction #= CustomInstructionTestCatalog.BranchLink.matchValue |
          (BigInt(3) << 10)
        sleep(1)
        assert(dut.io.isBranch.toBoolean)
        assert(dut.io.decodedRd.toBigInt == 1)
        assert(dut.io.writesGpr.toBoolean)
        assert(!dut.io.source1Used.toBoolean && !dut.io.source2Used.toBoolean)
        assert(dut.io.result.toBigInt == ((config.resetVector + 4) & wordMask))
        assert(dut.io.predecodeValid.toBoolean)
        assert(dut.io.predecodeTarget.toBigInt == ((config.resetVector + 12) & wordMask))

        dut.io.instruction #= CustomInstructionTestCatalog.PredicateIndirect.matchValue |
          (BigInt(3) << 10) | (4 << 5)
        sleep(1)
        assert(dut.io.isBranch.toBoolean)
        assert(dut.io.predecodeValid.toBoolean)
        assert(dut.io.predecodeType.toBigInt == 2)
        assert(dut.io.predecodeIndirect.toBoolean)
        assert(!dut.io.predecodeStaticTaken.toBoolean)

        dut.io.instruction #= CustomInstructionTestCatalog.LoadByte.matchValue |
          (BigInt(0xffc) << 10) | (4 << 5) | 3
        sleep(1)
        assert(dut.io.isLoad.toBoolean && !dut.io.isStore.toBoolean)
        assert(dut.io.fuType.toBigInt == 5)
        assert(dut.io.memorySize.toBigInt == CustomMemorySize.Byte)
        assert(dut.io.memorySignExtend.toBoolean)

        dut.io.instruction #= CustomInstructionTestCatalog.StoreHalf.matchValue |
          (BigInt(2) << 10) | (8 << 5) | 7
        sleep(1)
        assert(!dut.io.isLoad.toBoolean && dut.io.isStore.toBoolean)
        assert(dut.io.memorySize.toBigInt == CustomMemorySize.Half)
        assert(!dut.io.writesGpr.toBoolean)
      }
  }

  test("rriwinz test fixture handles clipped fields and read-modify-write operands") {
    val rriConfig = OooCoreConfig.FourIssueThreeCommit.copy(
      customInstructionProfile = CustomInstructionTestCatalog.RriwinzProfile
    )

    def expected(oldRd: BigInt, rj: BigInt, rjBase: Int, offset: Int, rdBase: Int): BigInt = {
      def field(value: BigInt, base: Int): (BigInt, Int) = {
        val width = math.min(offset, 32 - base)
        val mask = if (width == 0) BigInt(0) else (BigInt(1) << width) - 1
        ((value >> base) & mask, width)
      }

      val (selectedRj, _) = field(rj, rjBase)
      val count = selectedRj.bitCount
      val (selectedRd, rdWidth) = field(oldRd, rdBase)
      val amount = if (rdWidth == 0) 0 else count % rdWidth
      val rotated =
        if (rdWidth == 0 || amount == 0) selectedRd
        else ((selectedRd >> amount) | (selectedRd << (rdWidth - amount))) &
          ((BigInt(1) << rdWidth) - 1)
      val rdMask = if (rdWidth == 0) BigInt(0) else ((BigInt(1) << rdWidth) - 1) << rdBase
      (oldRd & (~rdMask & wordMask)) | (rotated << rdBase)
    }

    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-rriwinz")
      .compile(new CustomExecutionProbe(rriConfig))
      .doSim("custom-rriwinz", 0x4c8a) { dut =>
        val random = new scala.util.Random(0x4c8a)
        val cases = Seq(
          (BigInt("00000009", 16), BigInt("00000007", 16), 0, 4, 0),
          (BigInt("80000001", 16), BigInt("f0000000", 16), 31, 8, 31),
          (BigInt("12345678", 16), BigInt("89abcdef", 16), 5, 0, 7),
          (BigInt("ffffffff", 16), BigInt("80000001", 16), 28, 16, 28)
        ) ++ Seq.fill(128) {
          (
            BigInt(random.nextInt()) & wordMask,
            BigInt(random.nextInt()) & wordMask,
            random.nextInt(32),
            random.nextInt(32),
            random.nextInt(32)
          )
        }

        for ((oldRd, rj, rjBase, offset, rdBase) <- cases) {
          dut.io.instruction #= CustomInstructionTestCatalog.encodeRriwinz(
            rd = 7,
            rj = 8,
            rjBase = rjBase,
            offset = offset,
            rdBase = rdBase
          )
          dut.io.source1 #= oldRd
          dut.io.source2 #= rj
          sleep(1)
          assert(!dut.io.exceptionValid.toBoolean)
          assert(dut.io.decodedRs1.toBigInt == 7)
          assert(dut.io.decodedRs2.toBigInt == 8)
          assert(dut.io.decodedRd.toBigInt == 7)
          assert(dut.io.result.toBigInt == expected(oldRd, rj, rjBase, offset, rdBase))
        }
      }
  }

  test("custom compute is restricted to one selected ALU port") {
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-dispatch")
      .compile(new CustomDispatchProbe(config))
      .doSim("custom-dispatch", 0x4c83) { dut =>
        dut.io.inputValid #= true
        dut.io.instruction #= CustomInstructionTestCatalog.Mix.matchValue | (5 << 10) | (4 << 5) | 3
        dut.io.portReady #= 0xf
        sleep(1)
        assert(dut.io.inputReady.toBoolean)
        assert(dut.io.selectedPorts.toBigInt == 1)

        dut.io.portReady #= 0xe
        sleep(1)
        assert(!dut.io.inputReady.toBoolean)
        assert(dut.io.selectedPorts.toBigInt == 0)

        dut.io.instruction #= BigInt("00110000", 16) | (5 << 10) | (4 << 5) | 3
        sleep(1)
        assert(dut.io.inputReady.toBoolean)
        assert(dut.io.selectedPorts.toBigInt == 2)
      }
  }

  test("custom branch and memory instructions reuse existing execution paths") {
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-backend-paths")
      .compile(new CustomBackendPathProbe(config))
      .doSim("custom-backend-paths", 0x4c84) { dut =>
        dut.io.issueValid #= true
        dut.io.predictedTaken #= false
        dut.io.predictedTarget #= config.resetVector + 4

        val branchImmediate = (-7) & 0xffff
        dut.io.instruction #= CustomInstructionTestCatalog.BranchEqual.matchValue |
          (BigInt(branchImmediate) << 10) | (6 << 5) | 5
        dut.io.source1 #= 11
        dut.io.source2 #= 11
        sleep(1)
        assert(dut.io.issueReady.toBoolean)
        assert(dut.io.branchCompletionValid.toBoolean)
        assert(dut.io.branchResolved.toBoolean && dut.io.branchTaken.toBoolean)
        assert(dut.io.branchTarget.toBigInt == ((config.resetVector - 28) & wordMask))
        assert(dut.io.branchMispredict.toBoolean)

        dut.io.instruction #= CustomInstructionTestCatalog.BranchSourceZero.matchValue |
          (BigInt(3) << 10) | (6 << 5)
        dut.io.source1 #= 0
        dut.io.source2 #= BigInt("ffffffff", 16)
        sleep(1)
        assert(dut.io.branchTaken.toBoolean)
        assert(dut.io.branchTarget.toBigInt == config.resetVector + 12)

        dut.io.instruction #= CustomInstructionTestCatalog.BranchLink.matchValue |
          (BigInt(3) << 10)
        dut.io.source1 #= BigInt("aaaaaaaa", 16)
        dut.io.source2 #= BigInt("55555555", 16)
        sleep(1)
        assert(dut.io.branchCompletionValid.toBoolean)
        assert(dut.io.branchTaken.toBoolean)
        assert(dut.io.branchTarget.toBigInt == config.resetVector + 12)
        assert(dut.io.branchWritesPdst.toBoolean)
        assert(dut.io.branchResult.toBigInt == config.resetVector + 4)

        dut.io.instruction #= CustomInstructionTestCatalog.LoadByte.matchValue |
          (BigInt(0xffc) << 10) | (4 << 5) | 3
        dut.io.source1 #= 0x1003
        dut.io.source2 #= 0
        sleep(1)
        assert(dut.io.issueReady.toBoolean && dut.io.aguValid.toBoolean)
        assert(dut.io.aguVirtualAddress.toBigInt == 0x0fff)
        assert(!dut.io.aguIsWrite.toBoolean)
        assert(dut.io.aguSize.toBigInt == 0)
        assert(dut.io.aguByteMask.toBigInt == 0x8)
        assert(dut.io.memorySignExtend.toBoolean)

        dut.io.instruction #= CustomInstructionTestCatalog.StoreHalf.matchValue |
          (BigInt(2) << 10) | (8 << 5) | 7
        dut.io.source1 #= 0x2000
        dut.io.source2 #= BigInt("1122aabb", 16)
        sleep(1)
        assert(dut.io.issueReady.toBoolean && dut.io.aguValid.toBoolean)
        assert(dut.io.aguVirtualAddress.toBigInt == 0x2002)
        assert(dut.io.aguIsWrite.toBoolean)
        assert(dut.io.aguSize.toBigInt == 1)
        assert(dut.io.aguByteMask.toBigInt == 0xc)
        assert(dut.io.aguWriteData.toBigInt == BigInt("aabb0000", 16))
      }
  }

  test("branch predicates retain the complete instruction through the issue queue") {
    val instruction = CustomInstructionTestCatalog.PredicateIndirect.matchValue |
      (BigInt(3) << 10) | (BigInt(4) << 5)
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-issue-instruction")
      .compile(new CustomIssueQueueInstructionProbe(config))
      .doSim("custom-issue-instruction", 0x4c87) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= instruction
        dut.io.enqueueValid #= false
        dut.io.issueReady #= false

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.enqueueValid #= true
        sleep(1)
        assert(dut.io.enqueueReady.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.enqueueValid #= false
        sleep(1)
        assert(dut.io.issueValid.toBoolean)
        assert(dut.io.issueInstruction.toBigInt == instruction)

        dut.io.issueReady #= true
        dut.clockDomain.waitSampling()
        dut.io.issueReady #= false
        sleep(1)
        assert(!dut.io.issueValid.toBoolean)
      }
  }

  test("rriwinz old rd survives rename issue writeback retirement and RAW dependencies") {
    val retirementConfig = OooCoreConfig.FourIssueThreeCommit.copy(
      customInstructionProfile = CustomInstructionTestCatalog.RriwinzProfile
    )
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-retirement")
      .compile(new CustomRetirementProbe(retirementConfig))
      .doSim("custom-retirement", 0x4c85) { dut =>
        def sample(): Unit = {
          dut.clockDomain.waitSampling()
          sleep(1)
        }

        def addiW(rd: Int, rj: Int, immediate: Int): BigInt =
          BigInt("02800000", 16) | (BigInt(immediate & 0xfff) << 10) |
            (BigInt(rj) << 5) | rd

        def submit(entries: Seq[(Int, BigInt, BigInt)]): Unit = {
          var valid = BigInt(0)
          for (lane <- 0 until retirementConfig.renameWidth) {
            dut.io.pc(lane) #= retirementConfig.resetVector
            dut.io.instruction(lane) #= 0
          }
          for ((lane, pc, instruction) <- entries) {
            valid |= BigInt(1) << lane
            dut.io.pc(lane) #= pc
            dut.io.instruction(lane) #= instruction
          }
          dut.io.renameValid #= valid
          var cycles = 0
          while ((dut.io.renameReady.toBigInt & valid) != valid && cycles < 16) {
            sample()
            cycles += 1
          }
          assert((dut.io.renameReady.toBigInt & valid) == valid)
          sample()
          dut.io.renameValid #= 0
        }

        dut.clockDomain.forkStimulus(period = 10)
        dut.io.renameValid #= 0
        dut.io.debugReadAddress #= 0
        for (lane <- 0 until retirementConfig.renameWidth) {
          dut.io.pc(lane) #= retirementConfig.resetVector
          dut.io.instruction(lane) #= 0
        }
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample()

        val firstPc = retirementConfig.resetVector
        submit(
          Seq(
            (0, firstPc, addiW(rd = 7, rj = 0, immediate = 9)),
            (1, firstPc + 4, addiW(rd = 8, rj = 0, immediate = 7)),
            (
              2,
              firstPc + 8,
              CustomInstructionTestCatalog.encodeRriwinz(
                rd = 7,
                rj = 8,
                rjBase = 0,
                offset = 4,
                rdBase = 0
              )
            )
          )
        )
        submit(Seq((0, firstPc + 12, addiW(rd = 9, rj = 7, immediate = 1))))

        val results = scala.collection.mutable.Map.empty[BigInt, BigInt]
        var cycles = 0
        while (results.size < 4 && cycles < 80) {
          sample()
          val valid = dut.io.commitValid.toBigInt
          for (
            lane <- 0 until retirementConfig.commitWidth
            if (valid & (BigInt(1) << lane)) != 0
          ) {
            results(dut.io.commit(lane).pc.toBigInt) = dut.io.commit(lane).result.toBigInt
          }
          cycles += 1
        }

        assert(results(firstPc) == 9)
        assert(results(firstPc + 4) == 7)
        assert(results(firstPc + 8) == 3)
        assert(results(firstPc + 12) == 4)

        dut.io.debugReadAddress #= 7
        sample()
        assert(dut.io.debugReadData.toBigInt == 3)
        dut.io.debugReadAddress #= 9
        sample()
        assert(dut.io.debugReadData.toBigInt == 4)
      }
  }

  test("ROB records custom indirect branch predictor metadata") {
    SimConfig.withVerilator
      .workspacePath("target/sim-workspace-custom-rob-predictor")
      .compile(new CustomRobPredictorProbe(config))
      .doSim("custom-rob-predictor", 0x4c86) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.instruction #= CustomInstructionTestCatalog.PredicateIndirect.matchValue | (4 << 5)
        dut.io.allocateValid #= false
        dut.io.completionValid #= false

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.allocateValid #= true
        sleep(1)
        assert(dut.io.allocatedPointer.toBigInt == 0)
        dut.clockDomain.waitSampling()
        dut.io.allocateValid #= false
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.commitValid.toBoolean)
        assert(dut.io.commitPredictorType.toBigInt == 2)
      }
  }
}
