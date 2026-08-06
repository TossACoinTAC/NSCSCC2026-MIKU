package miku.backend

import miku.core._
import spinal.core._

final class OooCommitAdapter(config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit)
    extends Component {
  val io = new Bundle {
    val commitValid = in Bits (config.commitWidth bits)
    val commit = in Vec (OooCommitRecord(config), config.commitWidth)
    val flush = in Bool ()

    val debugCommitValid = out Bool ()
    val debugCommit = out(OooCommitRecord(config))
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
    val exception = out(OooExceptionMeta())
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

  for (lane <- 0 until config.commitWidth) {
    val valid = !io.flush && io.commitValid(lane) && io.commit(lane).retired &&
      io.commit(lane).serializing
    when(valid) {
      io.serialCommitPc := io.commit(lane).pc
      io.csrAddress := io.commit(lane).csrAddress
      io.csrWriteData := io.commit(lane).sideEffectData
      io.csrMask := io.commit(lane).csrMask
      io.csrWriteValid := io.commit(lane).csrWrite
      when(io.commit(lane).systemOperation === OooSystemOp.ertn) {
        io.ertnValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.idle) {
        io.idleValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.tlbSearch) {
        io.tlbSearchValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.tlbRead) {
        io.tlbReadValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.tlbWrite) {
        io.tlbWriteValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.tlbFill) {
        io.tlbFillValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.invalidateTlb) {
        io.tlbInvalidateValid := True
        io.tlbInvalidateAsid := io.commit(lane).sideEffectData(9 downto 0)
        io.tlbInvalidateVpn := io.commit(lane).sideEffectData(31 downto 13)
        io.tlbInvalidateOperation := io.commit(lane).instruction(4 downto 0)
      }
      when(io.commit(lane).systemOperation === OooSystemOp.loadLinked) {
        io.reservationBitSet := True
        io.reservationBitValue := !io.commit(lane).sideEffectData(0)
        io.reservationAddressSet := !io.commit(lane).sideEffectData(0)
        io.reservationLineAddress := io.commit(lane).sideEffectData(
          config.xlen - 1 downto config.dataCache.offsetWidth
        )
        io.refetchValid := True
      }
      when(io.commit(lane).systemOperation === OooSystemOp.storeConditional) {
        io.reservationBitSet := True
        io.reservationBitValue := False
        io.refetchValid := True
      }
      when(
        io.commit(lane).systemOperation === OooSystemOp.instructionBarrier ||
          io.commit(lane).systemOperation === OooSystemOp.dataBarrier ||
          io.commit(lane).systemOperation === OooSystemOp.cacheOperation ||
          io.commit(lane).systemOperation === OooSystemOp.preload
      ) {
        io.refetchValid := True
      }
      when(io.commit(lane).csrWrite) { io.refetchValid := True }
    }
    when(!io.flush && io.commitValid(lane) && io.commit(lane).exception.valid) {
      io.exceptionValid := True
      io.exceptionPc := io.commit(lane).pc
      io.exception := io.commit(lane).exception
    }
  }
}
