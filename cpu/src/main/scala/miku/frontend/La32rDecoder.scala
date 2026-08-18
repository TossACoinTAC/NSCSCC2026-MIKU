package miku.frontend

import miku.backend._
import miku.core._
import spinal.core._

/** Pure LA32R decoder for the OoO frontend. It contains no GPR, forwarding, or occupancy state. */
final class La32rDecoder(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  private def balancedOr(values: IndexedSeq[Bool]): Bool = {
    require(values.nonEmpty, "a decode OR tree requires at least one input")
    if (values.length == 1) values.head
    else {
      val midpoint = values.length / 2
      balancedOr(values.take(midpoint)) || balancedOr(values.drop(midpoint))
    }
  }

  private def any(values: Bool*): Bool = balancedOr(values.toIndexedSeq)
  private def eq(value: UInt, literal: Int): Bool = value === U(literal, value.getWidth bits)

  val io = new Bundle {
    val pc = in UInt (config.xlen bits)
    val instruction = in Bits (32 bits)
    val fetchSlot = in UInt (config.fetchSlotWidth bits)
    val predictedTaken = in Bool ()
    val predictedTarget = in UInt (config.xlen bits)
    val predictorMetadata = in Bits (16 bits)
    val fetchException = in(ExceptionMetadata())
    val privilege = in Bits (2 bits)
    val interruptPending = in Bool ()
    val decoded = out(DecodedMicroOp(config))
  }

  val instruction = io.instruction
  val op31To26 = instruction(31 downto 26).asUInt
  val op25To22 = instruction(25 downto 22).asUInt
  val op21To20 = instruction(21 downto 20).asUInt
  val op19To15 = instruction(19 downto 15).asUInt
  val rd = instruction(4 downto 0).asUInt
  val rj = instruction(9 downto 5).asUInt
  val rk = instruction(14 downto 10).asUInt
  val i12 = instruction(21 downto 10)
  val i14 = instruction(23 downto 10)
  val i20 = instruction(24 downto 5)
  val i16 = instruction(25 downto 10)
  val i26 = instruction(9 downto 0) ## instruction(25 downto 10)
  val csrIndex = instruction(23 downto 10).asUInt

  private def full(op21: Int, op19: Int, op25: Int = 0): Bool =
    eq(op31To26, 0) && eq(op25To22, op25) && eq(op21To20, op21) && eq(op19To15, op19)

  val instAddW = full(1, 0x00)
  val instSubW = full(1, 0x02)
  val instSlt = full(1, 0x04)
  val instSltu = full(1, 0x05)
  val instNor = full(1, 0x08)
  val instAnd = full(1, 0x09)
  val instOr = full(1, 0x0a)
  val instXor = full(1, 0x0b)
  val instOrn = full(1, 0x0c)
  val instAndn = full(1, 0x0d)
  val instSllW = full(1, 0x0e)
  val instSrlW = full(1, 0x0f)
  val instSraW = full(1, 0x10)
  val instMulW = full(1, 0x18)
  val instMulhW = full(1, 0x19)
  val instMulhWu = full(1, 0x1a)
  val instDivW = full(2, 0x00)
  val instModW = full(2, 0x01)
  val instDivWu = full(2, 0x02)
  val instModWu = full(2, 0x03)
  val instBreak = full(2, 0x14)
  val instSyscall = full(2, 0x16)
  val instSlliW = full(0, 0x01, 1)
  val instSrliW = full(0, 0x09, 1)
  val instSraiW = full(0, 0x11, 1)
  val instIdle = eq(op31To26, 1) && eq(op25To22, 9) && eq(op21To20, 0) && eq(op19To15, 0x11)
  val instInvTlb = eq(op31To26, 1) && eq(op25To22, 9) && eq(op21To20, 0) && eq(op19To15, 0x13)
  val instDbar = eq(op31To26, 0x0e) && eq(op25To22, 1) && eq(op21To20, 3) && eq(op19To15, 4)
  val instIbar = eq(op31To26, 0x0e) && eq(op25To22, 1) && eq(op21To20, 3) && eq(op19To15, 5)
  val instSlti = eq(op31To26, 0) && eq(op25To22, 8)
  val instSltui = eq(op31To26, 0) && eq(op25To22, 9)
  val instAddiW = eq(op31To26, 0) && eq(op25To22, 0x0a)
  val instAndi = eq(op31To26, 0) && eq(op25To22, 0x0d)
  val instOri = eq(op31To26, 0) && eq(op25To22, 0x0e)
  val instXori = eq(op31To26, 0) && eq(op25To22, 0x0f)
  val instLdB = eq(op31To26, 0x0a) && eq(op25To22, 0)
  val instLdH = eq(op31To26, 0x0a) && eq(op25To22, 1)
  val instLdW = eq(op31To26, 0x0a) && eq(op25To22, 2)
  val instStB = eq(op31To26, 0x0a) && eq(op25To22, 4)
  val instStH = eq(op31To26, 0x0a) && eq(op25To22, 5)
  val instStW = eq(op31To26, 0x0a) && eq(op25To22, 6)
  val instLdBu = eq(op31To26, 0x0a) && eq(op25To22, 8)
  val instLdHu = eq(op31To26, 0x0a) && eq(op25To22, 9)
  val instCacop = eq(op31To26, 1) && eq(op25To22, 8)
  val instPreload = eq(op31To26, 0x0a) && eq(op25To22, 0x0b)
  val instJirl = eq(op31To26, 0x13)
  val instB = eq(op31To26, 0x14)
  val instBl = eq(op31To26, 0x15)
  val instBeq = eq(op31To26, 0x16)
  val instBne = eq(op31To26, 0x17)
  val instBlt = eq(op31To26, 0x18)
  val instBge = eq(op31To26, 0x19)
  val instBltu = eq(op31To26, 0x1a)
  val instBgeu = eq(op31To26, 0x1b)
  val instLu12iW = eq(op31To26, 5) && !instruction(25)
  val instPcaddi = eq(op31To26, 6) && !instruction(25)
  val instPcaddu12i = eq(op31To26, 7) && !instruction(25)
  val baseCsr = eq(op31To26, 1) && !instruction(25) && !instruction(24)
  val instCsrXchg = baseCsr && rj =/= 0 && rj =/= 1
  val instLlW = eq(op31To26, 8) && !instruction(25) && !instruction(24)
  val instScW = eq(op31To26, 8) && !instruction(25) && instruction(24)
  val instCsrRead = baseCsr && rj === 0
  val instCsrWrite = baseCsr && rj === 1
  val counterBase = full(0, 0x00)
  val instRdCntIdW = counterBase && rk === 0x18 && rd === 0
  val instRdCntVlW = counterBase && rk === 0x18 && rj === 0 && rd =/= 0
  val instRdCntVhW = counterBase && rk === 0x19 && rj === 0
  val privilegedBase = eq(op31To26, 1) && eq(op25To22, 9) && eq(op21To20, 0) &&
    eq(op19To15, 0x10) && rj === 0 && rd === 0
  val instErtn = privilegedBase && rk === 0x0e
  val instTlbSearch = privilegedBase && rk === 0x0a
  val instTlbRead = privilegedBase && rk === 0x0b
  val instTlbWrite = privilegedBase && rk === 0x0c
  val instTlbFill = privilegedBase && rk === 0x0d
  val instCpuCfg = counterBase && rk === 0x1b

  val destination = Mux(instBl, U(1, 5 bits), Mux(instRdCntIdW, rj, rd))
  val validCacop = instCacop &&
    (destination(2 downto 0) === 0 || destination(2 downto 0) === 1 ||
      destination(2 downto 0) === 2) &&
    destination(4 downto 3) =/= 3
  val cacopNop = instCacop &&
    ((destination(2 downto 0) =/= 0 && destination(2 downto 0) =/= 1 &&
      destination(2 downto 0) =/= 2) ||
      destination(4 downto 3) === 3)

  val aluOperation = Bits(14 bits)
  aluOperation := 0
  aluOperation(0) := any(
    instAddW,
    instAddiW,
    instLdB,
    instLdH,
    instLdW,
    instStB,
    instStH,
    instStW,
    instLdBu,
    instLdHu,
    instLlW,
    instScW,
    instJirl,
    instBl,
    instPcaddi,
    instPcaddu12i,
    validCacop,
    instPreload
  )
  aluOperation(1) := instSubW
  aluOperation(2) := instSlt || instSlti
  aluOperation(3) := instSltu || instSltui
  aluOperation(4) := instAnd || instAndi
  aluOperation(5) := instNor
  aluOperation(6) := instOr || instOri
  aluOperation(7) := instXor || instXori
  aluOperation(8) := instSllW || instSlliW
  aluOperation(9) := instSrlW || instSrliW
  aluOperation(10) := instSraW || instSraiW
  aluOperation(11) := instLu12iW
  aluOperation(12) := instAndn
  aluOperation(13) := instOrn

  val mulDivOperation = Bits(4 bits)
  mulDivOperation := 0
  mulDivOperation(0) := instMulW
  mulDivOperation(1) := instMulhW || instMulhWu
  mulDivOperation(2) := instDivW || instDivWu
  mulDivOperation(3) := instModW || instModWu
  val mulDivSigned = any(instMulW, instMulhW, instDivW, instModW)

  val needUi5 = any(instSlliW, instSrliW, instSraiW)
  val needSi12 = any(
    instAddiW,
    instLdB,
    instLdH,
    instLdW,
    instStB,
    instStH,
    instStW,
    instLdBu,
    instLdHu,
    instSlti,
    instSltui,
    validCacop,
    instPreload
  )
  val needUi12 = any(instAndi, instOri, instXori)
  val needSi14Pc = instLlW || instScW
  val needSi16Pc = any(instJirl, instBeq, instBne, instBlt, instBge, instBltu, instBgeu)
  val needSi20 = instLu12iW || instPcaddu12i
  val needSi20Pc = instPcaddi
  val needSi26Pc = instB || instBl
  val immediate = Bits(32 bits)
  immediate := 0
  when(needUi5) { immediate := rk.asBits.resize(32) }
  when(needSi12) { immediate := i12.asSInt.resize(32).asBits }
  when(needUi12) { immediate := i12.resize(32) }
  when(needSi14Pc) { immediate := (i14 ## B(0, 2 bits)).asSInt.resize(32).asBits }
  when(needSi16Pc) { immediate := (i16 ## B(0, 2 bits)).asSInt.resize(32).asBits }
  when(needSi20) { immediate := i20 ## B(0, 12 bits) }
  when(needSi20Pc) { immediate := (i20 ## B(0, 2 bits)).asSInt.resize(32).asBits }
  when(needSi26Pc) { immediate := (i26 ## B(0, 2 bits)).asSInt.resize(32).asBits }

  val sourceRegisterIsRd = any(
    instBeq,
    instBne,
    instBlt,
    instBltu,
    instBge,
    instBgeu,
    instStB,
    instStH,
    instStW,
    instScW,
    instCsrWrite,
    instCsrXchg
  )
  val source1IsPc = any(instJirl, instBl, instPcaddi, instPcaddu12i)
  val source2IsImmediate = any(
    instSlliW,
    instSrliW,
    instSraiW,
    instAddiW,
    instSlti,
    instSltui,
    instAndi,
    instOri,
    instXori,
    instPcaddi,
    instPcaddu12i,
    instLdB,
    instLdH,
    instLdW,
    instLdBu,
    instLdHu,
    instStB,
    instStH,
    instStW,
    instLlW,
    instScW,
    instLu12iW,
    validCacop,
    instPreload
  )
  val source2IsFour = instJirl || instBl
  val loadOperation = any(instLdB, instLdH, instLdW, instLdBu, instLdHu, instLlW)
  val storeOperation = any(instStB, instStH, instStW, instScW)
  val byteMemory = any(instLdB, instLdBu, instStB)
  val halfMemory = any(instLdH, instLdHu, instStH)
  val memorySignExtend = instLdB || instLdH

  val needRj = any(
    instAddW,
    instSubW,
    instAddiW,
    instSlt,
    instSltu,
    instSlti,
    instSltui,
    instAnd,
    instOr,
    instNor,
    instXor,
    instOrn,
    instAndn,
    instAndi,
    instOri,
    instXori,
    instMulW,
    instMulhW,
    instMulhWu,
    instDivW,
    instDivWu,
    instModW,
    instModWu,
    instSllW,
    instSrlW,
    instSraW,
    instSlliW,
    instSrliW,
    instSraiW,
    instBeq,
    instBne,
    instBlt,
    instBltu,
    instBge,
    instBgeu,
    instJirl,
    instLdB,
    instLdBu,
    instLdH,
    instLdHu,
    instLdW,
    instStB,
    instStH,
    instStW,
    instPreload,
    instLlW,
    instScW,
    instCsrXchg,
    validCacop,
    instInvTlb,
    instCpuCfg
  )
  val needRkd = any(
    instAddW,
    instSubW,
    instSlt,
    instSltu,
    instAnd,
    instOr,
    instNor,
    instXor,
    instOrn,
    instAndn,
    instMulW,
    instMulhW,
    instMulhWu,
    instDivW,
    instDivWu,
    instModW,
    instModWu,
    instSllW,
    instSrlW,
    instSraW,
    instBeq,
    instBne,
    instBlt,
    instBltu,
    instBge,
    instBgeu,
    instStB,
    instStH,
    instStW,
    instScW,
    instCsrWrite,
    instCsrXchg,
    instInvTlb
  )

  val instructionValid = any(
    instAddW,
    instSubW,
    instSlt,
    instSltu,
    instNor,
    instAnd,
    instOr,
    instXor,
    instOrn,
    instAndn,
    instSllW,
    instSrlW,
    instSraW,
    instMulW,
    instMulhW,
    instMulhWu,
    instDivW,
    instModW,
    instDivWu,
    instModWu,
    instBreak,
    instSyscall,
    instSlliW,
    instSrliW,
    instSraiW,
    instIdle,
    instSlti,
    instSltui,
    instAddiW,
    instAndi,
    instOri,
    instXori,
    instLdB,
    instLdH,
    instLdW,
    instStB,
    instStH,
    instStW,
    instLdBu,
    instLdHu,
    instLlW,
    instScW,
    instJirl,
    instB,
    instBl,
    instBeq,
    instBne,
    instBlt,
    instBge,
    instBltu,
    instBgeu,
    instLu12iW,
    instPcaddi,
    instPcaddu12i,
    instCsrRead,
    instCsrWrite,
    instCsrXchg,
    instRdCntIdW,
    instRdCntVhW,
    instRdCntVlW,
    instErtn,
    validCacop,
    instPreload,
    instDbar,
    instIbar,
    instTlbSearch,
    instTlbRead,
    instTlbWrite,
    instTlbFill,
    cacopNop,
    instCpuCfg,
    instInvTlb && rd <= 6
  )
  val kernelInstruction = any(
    instCsrRead,
    instCsrWrite,
    instCsrXchg,
    validCacop && destination(4 downto 3) =/= 2,
    instTlbSearch,
    instTlbRead,
    instTlbWrite,
    instTlbFill,
    instInvTlb,
    instErtn,
    instIdle
  )
  val privilegeException = kernelInstruction && io.privilege === B"11"
  val illegalInstruction = !instructionValid
  val branchInstruction = any(
    instBeq,
    instBne,
    instBlt,
    instBge,
    instBltu,
    instBgeu,
    instJirl,
    instBl,
    instB
  )
  val csrInstruction = any(
    instCsrRead,
    instCsrWrite,
    instCsrXchg,
    instRdCntIdW,
    instRdCntVlW,
    instRdCntVhW,
    instCpuCfg
  )
  val privilegedInstruction = any(
    instErtn,
    instTlbSearch,
    instTlbRead,
    instTlbWrite,
    instTlbFill,
    instInvTlb,
    instDbar,
    instIbar,
    instIdle
  )
  val serializing = any(
    csrInstruction,
    privilegedInstruction,
    validCacop,
    instPreload,
    instLlW,
    instScW
  )

  val systemOperation = UInt(SystemOperation.Width bits)
  systemOperation := SystemOperation.none
  when(instCsrRead) { systemOperation := SystemOperation.csrRead }
  when(instCsrWrite) { systemOperation := SystemOperation.csrWrite }
  when(instCsrXchg) { systemOperation := SystemOperation.csrExchange }
  when(instRdCntIdW) { systemOperation := SystemOperation.counterId }
  when(instRdCntVlW) { systemOperation := SystemOperation.counterLow }
  when(instRdCntVhW) { systemOperation := SystemOperation.counterHigh }
  when(instCpuCfg) { systemOperation := SystemOperation.cpuConfig }
  when(instErtn) { systemOperation := SystemOperation.ertn }
  when(instTlbSearch) { systemOperation := SystemOperation.tlbSearch }
  when(instTlbRead) { systemOperation := SystemOperation.tlbRead }
  when(instTlbWrite) { systemOperation := SystemOperation.tlbWrite }
  when(instTlbFill) { systemOperation := SystemOperation.tlbFill }
  when(instInvTlb) { systemOperation := SystemOperation.invalidateTlb }
  when(instDbar) { systemOperation := SystemOperation.dataBarrier }
  when(instIbar) { systemOperation := SystemOperation.instructionBarrier }
  when(instIdle) { systemOperation := SystemOperation.idle }
  when(validCacop) { systemOperation := SystemOperation.cacheOperation }
  when(instPreload) { systemOperation := SystemOperation.preload }
  when(instLlW) { systemOperation := SystemOperation.loadLinked }
  when(instScW) { systemOperation := SystemOperation.storeConditional }

  val gprWrite = instructionValid && !any(
    storeOperation && !instScW,
    instBeq,
    instBne,
    instBlt,
    instBge,
    instBltu,
    instBgeu,
    instB,
    instSyscall,
    instBreak,
    instTlbSearch,
    instTlbRead,
    instTlbWrite,
    instTlbFill,
    instInvTlb,
    validCacop,
    instPreload,
    instDbar,
    instIbar,
    instIdle,
    cacopNop,
    instErtn
  )

  val fuType = UInt(ExecutionUnitType.Width bits)
  fuType := ExecutionUnitType.alu
  when(branchInstruction) { fuType := ExecutionUnitType.branch }
  when(any(instMulW, instMulhW, instMulhWu)) { fuType := ExecutionUnitType.multiply }
  when(any(instDivW, instDivWu, instModW, instModWu)) { fuType := ExecutionUnitType.divide }
  when(csrInstruction) { fuType := ExecutionUnitType.csr }
  // PRELD is currently an architectural no-op completed at retirement. Route
  // it through an ALU lane so the dedicated LSU lane never needs to arbitrate
  // a direct completion against an older memory completion.
  when(any(loadOperation, storeOperation)) {
    fuType := ExecutionUnitType.loadStore
  }
  when(privilegedInstruction) { fuType := ExecutionUnitType.serial }
  when(any(instDbar, instIbar, validCacop)) { fuType := ExecutionUnitType.barrier }

  io.decoded.pc := io.pc
  io.decoded.instruction := instruction
  io.decoded.fetchSlot := io.fetchSlot
  io.decoded.rd := destination
  io.decoded.rs1 := rj
  io.decoded.rs2 := Mux(sourceRegisterIsRd, rd, rk)
  io.decoded.immediate := immediate
  io.decoded.source1Used := needRj
  io.decoded.source2Used := needRkd
  io.decoded.source1IsPc := source1IsPc
  io.decoded.source2IsImmediate := source2IsImmediate
  io.decoded.fuType := fuType
  io.decoded.operation := aluOperation
  io.decoded.mulDivOperation := mulDivOperation
  io.decoded.mulDivSigned := mulDivSigned
  io.decoded.memorySize := halfMemory.asBits ## byteMemory.asBits
  io.decoded.memorySignExtend := memorySignExtend
  io.decoded.source2IsFour := source2IsFour
  io.decoded.writesGpr := gprWrite
  io.decoded.isLoad := loadOperation
  io.decoded.isStore := storeOperation
  io.decoded.isBranch := branchInstruction
  io.decoded.branchKind := U(0, 3 bits)
  when(instBeq) { io.decoded.branchKind := U(1, 3 bits) }
  when(instBne) { io.decoded.branchKind := U(2, 3 bits) }
  when(instBlt) { io.decoded.branchKind := U(3, 3 bits) }
  when(instBge) { io.decoded.branchKind := U(4, 3 bits) }
  when(instBltu) { io.decoded.branchKind := U(5, 3 bits) }
  when(instBgeu) { io.decoded.branchKind := U(6, 3 bits) }
  when(instJirl) { io.decoded.branchKind := U(7, 3 bits) }
  io.decoded.isCsr := csrInstruction || privilegedInstruction
  io.decoded.isLl := instLlW
  io.decoded.isSc := instScW
  io.decoded.isCacheOperation := validCacop
  io.decoded.isPreload := instPreload
  io.decoded.isErtn := instErtn
  io.decoded.isTlbSearch := instTlbSearch
  io.decoded.isTlbWrite := instTlbWrite
  io.decoded.isTlbFill := instTlbFill
  io.decoded.isTlbRead := instTlbRead
  io.decoded.isTlbInvalidate := instInvTlb
  io.decoded.isRefetch := any(instTlbWrite, instTlbFill, instTlbRead, instInvTlb, instIbar)
  io.decoded.csrReadData := B(0, config.xlen bits)
  io.decoded.csrAddress := csrIndex
  io.decoded.csrWrite := instCsrWrite || instCsrXchg
  io.decoded.csrMask := instCsrXchg
  io.decoded.resultFromCsr := csrInstruction || instScW
  io.decoded.systemOperation := systemOperation
  io.decoded.serializing := serializing
  io.decoded.predictedTaken := io.predictedTaken
  io.decoded.predictedTarget := io.predictedTarget
  io.decoded.predictorMetadata := io.predictorMetadata

  io.decoded.exception := io.fetchException
  when(io.interruptPending) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x00, 6 bits)
    io.decoded.exception.esubcode := U(0, 9 bits)
    io.decoded.exception.badVAddrValid := False
    io.decoded.exception.badVAddr := U(0, 32 bits)
    io.decoded.exception.tlbRefill := False
  }.elsewhen(io.fetchException.valid) {
    // Fetch faults describe the instruction slot itself and therefore take
    // precedence over decoding the placeholder instruction carried with it.
  }.elsewhen(instSyscall) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x0b, 6 bits)
  }.elsewhen(instBreak) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x0c, 6 bits)
  }.elsewhen(illegalInstruction) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x0d, 6 bits)
  }.elsewhen(privilegeException) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x0e, 6 bits)
  }
}
