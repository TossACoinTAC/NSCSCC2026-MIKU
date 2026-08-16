package miku.backend

import miku.core._
import spinal.core._

final class OooCommitAdapter(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val commitValid = in Bits (config.commitWidth bits)
    val commit = in Vec (CommitRecord(config), config.commitWidth)
    val flush = in Bool ()

    val debugCommitValid = out Bool ()
    val debugCommit = out(CommitRecord(config))
    val csrWriteValid = out Bool ()
    val csrAddress = out UInt (14 bits)
    val csrWriteData = out Bits (config.xlen bits)
    val csrMask = out Bool ()
    val serialCommitPc = out UInt (config.xlen bits)
    val ertnValid = out Bool ()
    val idleValid = out Bool ()
    val refetchValid = out Bool ()
    val cacheInvalidateValid = out Bool ()
    val dataCacheInvalidateValid = out Bool ()
    val dataCacheWritebackInvalidateValid = out Bool ()
    val level2CacheInvalidateValid = out Bool ()
    val tlbSearchValid = out Bool ()
    val tlbReadValid = out Bool ()
    val tlbWriteValid = out Bool ()
    val tlbFillValid = out Bool ()
    val tlbInvalidateValid = out Bool ()
    val tlbInvalidateAsid = out Bits (10 bits)
    val tlbInvalidateVpn = out Bits (19 bits)
    val tlbInvalidateOperation = out Bits (5 bits)
    val reservationBitSet = out Bool ()
    val reservationBitValue = out Bool ()
    val reservationAddressSet = out Bool ()
    val reservationLineAddress = out Bits (config.reservationAddressWidth bits)
    val exceptionValid = out Bool ()
    val exceptionPc = out UInt (config.xlen bits)
    val exception = out(ExceptionMetadata())
  }

  io.debugCommitValid := !io.flush && io.commitValid.orR
  io.debugCommit := io.commit(0)
  for (lane <- 1 until config.commitWidth) {
    when(!io.commitValid(0) && io.commitValid(lane)) {
      io.debugCommit := io.commit(lane)
    }
  }

  io.csrWriteValid := False
  io.csrAddress := U(0, 14 bits)
  io.csrWriteData := B(0, config.xlen bits)
  io.csrMask := False
  io.serialCommitPc := 0
  io.ertnValid := False
  io.idleValid := False
  io.refetchValid := False
  io.cacheInvalidateValid := False
  io.dataCacheInvalidateValid := False
  io.dataCacheWritebackInvalidateValid := False
  io.level2CacheInvalidateValid := False
  io.tlbSearchValid := False
  io.tlbReadValid := False
  io.tlbWriteValid := False
  io.tlbFillValid := False
  io.tlbInvalidateValid := False
  io.tlbInvalidateAsid := 0
  io.tlbInvalidateVpn := 0
  io.tlbInvalidateOperation := 0
  io.reservationBitSet := False
  io.reservationBitValue := False
  io.reservationAddressSet := False
  io.reservationLineAddress := 0
  io.exceptionValid := False
  io.exceptionPc := U(0, config.xlen bits)
  io.exception.valid := False
  io.exception.ecode := U(0, 6 bits)
  io.exception.esubcode := U(0, 9 bits)
  io.exception.badVAddrValid := False
  io.exception.badVAddr := U(0, 32 bits)
  io.exception.tlbRefill := False

  val serialCommitMask = Bits(config.commitWidth bits)
  for (lane <- 0 until config.commitWidth) {
    serialCommitMask(lane) := !io.flush && io.commitValid(lane) && io.commit(lane).retired &&
      io.commit(lane).serializing
  }
  val serialPc = UInt(config.xlen bits)
  val serialCsrAddress = UInt(14 bits)
  val serialSideEffectData = Bits(config.xlen bits)
  val serialCsrMask = Bool()
  val serialCsrWrite = Bool()
  val serialSystemOperation = UInt(SystemOperation.Width bits)
  val serialInstructionOperation = Bits(5 bits)
  serialPc := io.commit(0).pc
  serialCsrAddress := io.commit(0).csrAddress
  serialSideEffectData := io.commit(0).sideEffectData
  serialCsrMask := io.commit(0).csrMask
  serialCsrWrite := io.commit(0).csrWrite
  serialSystemOperation := io.commit(0).systemOperation
  serialInstructionOperation := io.commit(0).instruction(4 downto 0)
  for (lane <- 1 until config.commitWidth) {
    when(serialCommitMask(lane)) {
      serialPc := io.commit(lane).pc
      serialCsrAddress := io.commit(lane).csrAddress
      serialSideEffectData := io.commit(lane).sideEffectData
      serialCsrMask := io.commit(lane).csrMask
      serialCsrWrite := io.commit(lane).csrWrite
      serialSystemOperation := io.commit(lane).systemOperation
      serialInstructionOperation := io.commit(lane).instruction(4 downto 0)
    }
  }
  when(serialCommitMask.orR) {
    io.serialCommitPc := serialPc
    io.csrAddress := serialCsrAddress
    io.csrWriteData := serialSideEffectData
    io.csrMask := serialCsrMask
    io.csrWriteValid := serialCsrWrite
    when(serialSystemOperation === SystemOperation.ertn) {
      io.ertnValid := True
    }
    when(serialSystemOperation === SystemOperation.idle) {
      io.idleValid := True
    }
    when(serialSystemOperation === SystemOperation.tlbSearch) {
      io.tlbSearchValid := True
    }
    when(serialSystemOperation === SystemOperation.tlbRead) {
      io.tlbReadValid := True
    }
    when(serialSystemOperation === SystemOperation.tlbWrite) {
      io.tlbWriteValid := True
    }
    when(serialSystemOperation === SystemOperation.tlbFill) {
      io.tlbFillValid := True
    }
    when(serialSystemOperation === SystemOperation.invalidateTlb) {
      io.tlbInvalidateValid := True
      io.tlbInvalidateAsid := serialSideEffectData(9 downto 0)
      io.tlbInvalidateVpn := serialSideEffectData(31 downto 13)
      io.tlbInvalidateOperation := serialInstructionOperation
    }
    when(serialSystemOperation === SystemOperation.loadLinked) {
      io.reservationBitSet := True
      io.reservationBitValue := !serialSideEffectData(0)
      io.reservationAddressSet := !serialSideEffectData(0)
      io.reservationLineAddress := serialSideEffectData(
        config.xlen - 1 downto config.dataCache.offsetWidth
      )
      io.refetchValid := True
    }
    when(serialSystemOperation === SystemOperation.storeConditional) {
      io.reservationBitSet := True
      io.reservationBitValue := False
      io.refetchValid := True
    }
    when(
      serialSystemOperation === SystemOperation.instructionBarrier ||
        serialSystemOperation === SystemOperation.dataBarrier ||
        serialSystemOperation === SystemOperation.cacheOperation ||
        serialSystemOperation === SystemOperation.preload
    ) {
      io.refetchValid := True
    }
    when(serialCsrWrite) { io.refetchValid := True }
  }

  for (lane <- 0 until config.commitWidth) {
    when(!io.flush && io.commitValid(lane) && io.commit(lane).exception.valid) {
      io.exceptionValid := True
      io.exceptionPc := io.commit(lane).pc
      io.exception := io.commit(lane).exception
    }
  }
}
