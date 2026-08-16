package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class OooCommitAdapterProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val commitValid = in Bits (config.commitWidth bits)
    val retired = in Bits (config.commitWidth bits)
    val serializing = in Bits (config.commitWidth bits)
    val systemOperation = in Vec (UInt(SystemOperation.Width bits), config.commitWidth)
    val sideEffectData = in Vec (Bits(config.xlen bits), config.commitWidth)
    val instruction = in Vec (Bits(32 bits), config.commitWidth)
    val pc = in Vec (UInt(config.xlen bits), config.commitWidth)
    val rd = in Vec (UInt(config.archRegIndexWidth bits), config.commitWidth)
    val refetchValid = out Bool ()
    val serialCommitPc = out UInt (config.xlen bits)
    val idleValid = out Bool ()
    val cacheInvalidateValid = out Bool ()
    val dataCacheInvalidateValid = out Bool ()
    val dataCacheWritebackInvalidateValid = out Bool ()
    val level2CacheInvalidateValid = out Bool ()
    val tlbInvalidateValid = out Bool ()
    val tlbInvalidateAsid = out Bits (10 bits)
    val tlbInvalidateVpn = out Bits (19 bits)
    val tlbInvalidateOperation = out Bits (5 bits)
    val reservationBitSet = out Bool ()
    val reservationBitValue = out Bool ()
    val reservationAddressSet = out Bool ()
    val reservationLineAddress = out Bits (config.reservationAddressWidth bits)
  }
  noIoPrefix()

  val adapter = new OooCommitAdapter(config)
  adapter.io.commitValid := io.commitValid
  adapter.io.flush := False
  for (lane <- 0 until config.commitWidth) {
    val commit = CommitRecord(config)
    commit.pc := io.pc(lane)
    commit.instruction := io.instruction(lane)
    commit.robPointer := 0
    commit.rd := io.rd(lane)
    commit.pdst := 0
    commit.oldPdst := 0
    commit.writesGpr := False
    commit.result := 0
    commit.retired := io.retired(lane)
    commit.serializing := io.serializing(lane)
    commit.isLoad := False
    commit.isStore := False
    commit.loadQueueIndex := 0
    commit.storeQueueIndex := 0
    commit.exception.valid := False
    commit.exception.ecode := 0
    commit.exception.esubcode := 0
    commit.exception.badVAddrValid := False
    commit.exception.badVAddr := 0
    commit.exception.tlbRefill := False
    commit.systemOperation := io.systemOperation(lane)
    commit.systemOperationIsMemoryBarrier :=
      io.systemOperation(lane) === SystemOperation.dataBarrier ||
        io.systemOperation(lane) === SystemOperation.instructionBarrier ||
        io.systemOperation(lane) === SystemOperation.cacheOperation
    commit.csrAddress := 0
    commit.csrWrite := False
    commit.csrMask := False
    commit.sideEffectData := io.sideEffectData(lane)
    adapter.io.commit(lane) := commit
  }

  io.refetchValid := adapter.io.refetchValid
  io.serialCommitPc := adapter.io.serialCommitPc
  io.idleValid := adapter.io.idleValid
  io.cacheInvalidateValid := adapter.io.cacheInvalidateValid
  io.dataCacheInvalidateValid := adapter.io.dataCacheInvalidateValid
  io.dataCacheWritebackInvalidateValid := adapter.io.dataCacheWritebackInvalidateValid
  io.level2CacheInvalidateValid := adapter.io.level2CacheInvalidateValid
  io.tlbInvalidateValid := adapter.io.tlbInvalidateValid
  io.tlbInvalidateAsid := adapter.io.tlbInvalidateAsid
  io.tlbInvalidateVpn := adapter.io.tlbInvalidateVpn
  io.tlbInvalidateOperation := adapter.io.tlbInvalidateOperation
  io.reservationBitSet := adapter.io.reservationBitSet
  io.reservationBitValue := adapter.io.reservationBitValue
  io.reservationAddressSet := adapter.io.reservationAddressSet
  io.reservationLineAddress := adapter.io.reservationLineAddress
}

class OooCommitAdapterSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("serial side effects use the actual commit lane and ignore exception entries") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-commit-adapter")
      .compile(new OooCommitAdapterProbe(config))
      .doSim("ooo-commit-adapter-side-effects", 0x4c58) { dut =>
        dut.io.commitValid #= 0
        dut.io.retired #= 0
        dut.io.serializing #= 0
        for (lane <- 0 until config.commitWidth) {
          dut.io.systemOperation(lane) #= 0
          dut.io.sideEffectData(lane) #= 0
          dut.io.instruction(lane) #= 0
          dut.io.pc(lane) #= 0
          dut.io.rd(lane) #= 0
        }

        val asid = BigInt(0x155)
        val vpn = BigInt(0x54321)
        dut.io.commitValid #= 2
        dut.io.retired #= 2
        dut.io.serializing #= 2
        dut.io.systemOperation(1) #= 13
        dut.io.sideEffectData(1) #= ((vpn << 13) | asid)
        dut.io.instruction(1) #= 6
        dut.io.pc(1) #= BigInt("1c001234", 16)
        sleep(1)
        assert(dut.io.tlbInvalidateValid.toBoolean)
        assert(dut.io.tlbInvalidateAsid.toBigInt == asid)
        assert(dut.io.tlbInvalidateVpn.toBigInt == vpn)
        assert(dut.io.tlbInvalidateOperation.toBigInt == 6)
        assert(dut.io.serialCommitPc.toBigInt == BigInt("1c001234", 16))

        dut.io.commitValid #= 4
        dut.io.retired #= 4
        dut.io.serializing #= 4
        dut.io.systemOperation(2) #= 19
        dut.io.sideEffectData(2) #= BigInt("23456780", 16)
        sleep(1)
        assert(dut.io.refetchValid.toBoolean)
        assert(dut.io.reservationBitSet.toBoolean)
        assert(dut.io.reservationBitValue.toBoolean)
        assert(dut.io.reservationAddressSet.toBoolean)
        assert(dut.io.reservationLineAddress.toBigInt == BigInt("008d159e", 16))

        dut.io.sideEffectData(2) #= BigInt("23456781", 16)
        sleep(1)
        assert(dut.io.reservationBitSet.toBoolean)
        assert(!dut.io.reservationBitValue.toBoolean)
        assert(!dut.io.reservationAddressSet.toBoolean)

        dut.io.systemOperation(2) #= 20
        sleep(1)
        assert(dut.io.refetchValid.toBoolean)
        assert(dut.io.reservationBitSet.toBoolean)
        assert(!dut.io.reservationBitValue.toBoolean)

        dut.io.systemOperation(2) #= 15
        sleep(1)
        assert(dut.io.refetchValid.toBoolean)
        assert(!dut.io.cacheInvalidateValid.toBoolean)

        dut.io.systemOperation(2) #= 17
        for (code <- Seq(0, 1, 2, 9, 16, 17, 18)) {
          dut.io.rd(2) #= code
          sleep(1)
          assert(dut.io.refetchValid.toBoolean)
          assert(!dut.io.cacheInvalidateValid.toBoolean)
          assert(!dut.io.dataCacheInvalidateValid.toBoolean)
          assert(!dut.io.dataCacheWritebackInvalidateValid.toBoolean)
          assert(!dut.io.level2CacheInvalidateValid.toBoolean)
        }

        dut.io.systemOperation(2) #= 16
        sleep(1)
        assert(dut.io.idleValid.toBoolean)
        assert(!dut.io.cacheInvalidateValid.toBoolean)
        assert(!dut.io.dataCacheInvalidateValid.toBoolean)
        assert(!dut.io.dataCacheWritebackInvalidateValid.toBoolean)
        assert(!dut.io.level2CacheInvalidateValid.toBoolean)

        dut.io.commitValid #= 1
        dut.io.retired #= 1
        dut.io.serializing #= 1
        dut.io.systemOperation(0) #= 16
        dut.io.pc(0) #= BigInt("1c001000", 16)
        sleep(1)
        assert(dut.io.idleValid.toBoolean)
        assert(dut.io.serialCommitPc.toBigInt == BigInt("1c001000", 16))

        dut.io.retired #= 0
        sleep(1)
        assert(!dut.io.refetchValid.toBoolean)
        assert(!dut.io.tlbInvalidateValid.toBoolean)
        assert(!dut.io.reservationBitSet.toBoolean)
        assert(!dut.io.idleValid.toBoolean)
      }
  }
}
