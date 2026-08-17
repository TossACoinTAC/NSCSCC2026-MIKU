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

  private def customRegister(
      field: CustomRegister,
      rd: UInt,
      rj: UInt,
      rk: UInt
  ): UInt = field match {
    case CustomRegister.Rd     => rd
    case CustomRegister.Rj     => rj
    case CustomRegister.Rk     => rk
    case CustomRegister.Unused => U(0, config.archRegIndexWidth bits)
    case CustomRegister.Fixed(index) => U(index, config.archRegIndexWidth bits)
  }

  private def constantBool(value: Boolean): Bool = if (value) True else False

  private final class CustomDecode(
      val valid: Bool,
      val operation: Bits,
      val rs1: UInt,
      val rs2: UInt,
      val destination: UInt,
      val immediate: Bits,
      val source1Used: Bool,
      val source2Used: Bool,
      val source2IsImmediate: Bool,
      val writesGpr: Bool,
      val isLoad: Bool,
      val isStore: Bool,
      val isBranch: Bool,
      val memorySize: Bits,
      val memorySignExtend: Bool,
      val branchKind: UInt,
      val source1IsPc: Bool,
      val source2IsFour: Bool
  )

  private val customIndexedSpecifications =
    config.customInstructionProfile.indexedSpecifications
  private val customSpecifications = customIndexedSpecifications.map(_._1)
  private val customDecode: Option[CustomDecode] =
    if (customSpecifications.isEmpty) None
    else {
      val matches = customSpecifications.map { specification =>
        (instruction.asUInt & U(specification.matchMask, 32 bits)) ===
          U(specification.matchValue, 32 bits)
      }
      val valid = matches.reduce(_ || _)
      val operation = Bits(14 bits)
      val rs1 = UInt(config.archRegIndexWidth bits)
      val rs2 = UInt(config.archRegIndexWidth bits)
      val destination = UInt(config.archRegIndexWidth bits)
      val immediate = Bits(config.xlen bits)
      val source1Used = Bool()
      val source2Used = Bool()
      val source2IsImmediate = Bool()
      val writesGpr = Bool()
      val isLoad = Bool()
      val isStore = Bool()
      val isBranch = Bool()
      val memorySize = Bits(2 bits)
      val memorySignExtend = Bool()
      val branchKind = UInt(3 bits)
      val source1IsPc = Bool()
      val source2IsFour = Bool()

      operation := 0
      rs1 := 0
      rs2 := 0
      destination := rd
      immediate := 0
      source1Used := False
      source2Used := False
      source2IsImmediate := False
      writesGpr := False
      isLoad := False
      isStore := False
      isBranch := False
      memorySize := 0
      memorySignExtend := False
      branchKind := 0
      source1IsPc := False
      source2IsFour := False

      for (((specification, operationCode), matched) <-
          customIndexedSpecifications.zip(matches)) {
        when(matched) {
          if (specification.kind == CustomInstructionKind.Compute) {
            operation := B(operationCode, 14 bits)
          } else if (specification.branchLink) {
            operation := B(1, 14 bits)
          }
          rs1 := customRegister(specification.source1, rd, rj, rk)
          rs2 := customRegister(specification.source2, rd, rj, rk)
          destination := customRegister(specification.destination, rd, rj, rk)
          immediate := specification.immediate.decode(instruction, config.xlen)
          source1Used := constantBool(specification.source1 != CustomRegister.Unused)
          source2Used := constantBool(specification.source2 != CustomRegister.Unused)
          source2IsImmediate := constantBool(specification.source2IsImmediate)
          writesGpr := constantBool(specification.writesGpr)
          isLoad := constantBool(specification.kind == CustomInstructionKind.Load)
          isStore := constantBool(specification.kind == CustomInstructionKind.Store)
          isBranch := constantBool(specification.kind == CustomInstructionKind.Branch)
          memorySize := B(specification.memorySize, 2 bits)
          memorySignExtend := constantBool(specification.memorySignExtend)
          branchKind := U(specification.branchKind, 3 bits)
          source1IsPc := constantBool(specification.source1IsPc || specification.branchLink)
          source2IsFour := constantBool(specification.source2IsFour || specification.branchLink)
        }
      }

      Some(
        new CustomDecode(
          valid,
          operation,
          rs1,
          rs2,
          destination,
          immediate,
          source1Used,
          source2Used,
          source2IsImmediate,
          writesGpr,
          isLoad,
          isStore,
          isBranch,
          memorySize,
          memorySignExtend,
          branchKind,
          source1IsPc,
          source2IsFour
        )
      )
    }

  private def customBits(standard: Bits)(custom: CustomDecode => Bits): Bits =
    customDecode.map(decoded => Mux(decoded.valid, custom(decoded), standard)).getOrElse(standard)

  private def customUInt(standard: UInt)(custom: CustomDecode => UInt): UInt =
    customDecode.map(decoded => Mux(decoded.valid, custom(decoded), standard)).getOrElse(standard)

  private def customBool(standard: Bool)(custom: CustomDecode => Bool): Bool =
    customDecode.map(decoded => Mux(decoded.valid, custom(decoded), standard)).getOrElse(standard)

  private def standardOnly(value: Bool): Bool =
    customDecode.map(decoded => !decoded.valid && value).getOrElse(value)

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

  val destination = customUInt(Mux(instBl, U(1, 5 bits), Mux(instRdCntIdW, rj, rd)))(
    _.destination
  )
  val validCacop = standardOnly(
    instCacop &&
      (destination(2 downto 0) === 0 || destination(2 downto 0) === 1 ||
        destination(2 downto 0) === 2) &&
      destination(4 downto 3) =/= 3
  )
  val cacopNop = standardOnly(
    instCacop &&
      ((destination(2 downto 0) =/= 0 && destination(2 downto 0) =/= 1 &&
        destination(2 downto 0) =/= 2) ||
        destination(4 downto 3) === 3)
  )

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
  val loadOperation = customBool(any(instLdB, instLdH, instLdW, instLdBu, instLdHu, instLlW))(
    _.isLoad
  )
  val storeOperation = customBool(any(instStB, instStH, instStW, instScW))(_.isStore)
  val byteMemory = standardOnly(any(instLdB, instLdBu, instStB))
  val halfMemory = standardOnly(any(instLdH, instLdHu, instStH))
  val memorySignExtend = customBool(instLdB || instLdH)(_.memorySignExtend)

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

  val instructionValid = customBool(
    any(
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
  )(_ => True)
  val kernelInstruction = standardOnly(
    any(
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
  )
  val privilegeException = kernelInstruction && io.privilege === B"11"
  val illegalInstruction = !instructionValid
  val branchInstruction = customBool(
    any(
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
  )(_.isBranch)
  val csrInstruction = standardOnly(
    any(
      instCsrRead,
      instCsrWrite,
      instCsrXchg,
      instRdCntIdW,
      instRdCntVlW,
      instRdCntVhW,
      instCpuCfg
    )
  )
  val privilegedInstruction = standardOnly(
    any(
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
  )
  val serializing = standardOnly(
    any(
      csrInstruction,
      privilegedInstruction,
      validCacop,
      instPreload,
      instLlW,
      instScW
    )
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

  val gprWrite = customBool(
    instructionValid && !any(
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
  )(_.writesGpr)

  val fuType = UInt(ExecutionUnitType.Width bits)
  fuType := ExecutionUnitType.alu
  when(branchInstruction) { fuType := ExecutionUnitType.branch }
  when(standardOnly(any(instMulW, instMulhW, instMulhWu))) {
    fuType := ExecutionUnitType.multiply
  }
  when(standardOnly(any(instDivW, instDivWu, instModW, instModWu))) {
    fuType := ExecutionUnitType.divide
  }
  when(csrInstruction) { fuType := ExecutionUnitType.csr }
  // PRELD is currently an architectural no-op completed at retirement. Route
  // it through an ALU lane so the dedicated LSU lane never needs to arbitrate
  // a direct completion against an older memory completion.
  when(any(loadOperation, storeOperation)) {
    fuType := ExecutionUnitType.loadStore
  }
  when(privilegedInstruction) { fuType := ExecutionUnitType.serial }
  when(standardOnly(any(instDbar, instIbar, validCacop))) {
    fuType := ExecutionUnitType.barrier
  }
  io.decoded.pc := io.pc
  io.decoded.instruction := instruction
  io.decoded.fetchSlot := io.fetchSlot
  io.decoded.rd := destination
  io.decoded.rs1 := customUInt(rj)(_.rs1)
  io.decoded.rs2 := customUInt(Mux(sourceRegisterIsRd, rd, rk))(_.rs2)
  io.decoded.immediate := customBits(immediate)(_.immediate)
  io.decoded.source1Used := customBool(needRj)(_.source1Used)
  io.decoded.source2Used := customBool(needRkd)(_.source2Used)
  io.decoded.source1IsPc := customBool(source1IsPc)(_.source1IsPc)
  io.decoded.source2IsImmediate := customBool(source2IsImmediate)(_.source2IsImmediate)
  io.decoded.fuType := fuType
  io.decoded.operation := customBits(aluOperation)(_.operation)
  io.decoded.mulDivOperation := mulDivOperation
  io.decoded.mulDivSigned := mulDivSigned
  io.decoded.memorySize := customBits(halfMemory.asBits ## byteMemory.asBits)(_.memorySize)
  io.decoded.memorySignExtend := customBool(memorySignExtend)(_.memorySignExtend)
  io.decoded.source2IsFour := customBool(source2IsFour)(_.source2IsFour)
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
  customDecode.foreach { decoded =>
    when(decoded.valid && decoded.isBranch) { io.decoded.branchKind := decoded.branchKind }
  }
  io.decoded.isCsr := csrInstruction || privilegedInstruction
  io.decoded.isLl := standardOnly(instLlW)
  io.decoded.isSc := standardOnly(instScW)
  io.decoded.isCacheOperation := validCacop
  io.decoded.isPreload := standardOnly(instPreload)
  io.decoded.isErtn := standardOnly(instErtn)
  io.decoded.isTlbSearch := standardOnly(instTlbSearch)
  io.decoded.isTlbWrite := standardOnly(instTlbWrite)
  io.decoded.isTlbFill := standardOnly(instTlbFill)
  io.decoded.isTlbRead := standardOnly(instTlbRead)
  io.decoded.isTlbInvalidate := standardOnly(instInvTlb)
  io.decoded.isRefetch := standardOnly(
    any(instTlbWrite, instTlbFill, instTlbRead, instInvTlb, instIbar)
  )
  io.decoded.csrReadData := B(0, config.xlen bits)
  io.decoded.csrAddress := csrIndex
  io.decoded.csrWrite := standardOnly(instCsrWrite || instCsrXchg)
  io.decoded.csrMask := standardOnly(instCsrXchg)
  io.decoded.resultFromCsr := standardOnly(csrInstruction || instScW)
  io.decoded.systemOperation := customUInt(systemOperation)(_ => SystemOperation.none)
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
  }.elsewhen(standardOnly(instSyscall)) {
    io.decoded.exception.valid := True
    io.decoded.exception.ecode := U(0x0b, 6 bits)
  }.elsewhen(standardOnly(instBreak)) {
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
