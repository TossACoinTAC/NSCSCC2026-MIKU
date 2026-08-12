package miku.memory

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class SharedReadMshrRouterProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val instructionReadValid = in Bool ()
    val instructionRead = in(LineReadRequest(config))
    val instructionReadReady = out Bool ()
    val instructionReadBeatValid = out Bool ()
    val instructionReadBeat = out(LineReadBeat(config))
    val instructionReadBeatReady = in Bool ()
    val dataReadValid = in Bool ()
    val dataRead = in(LineReadRequest(config))
    val dataReadReady = out Bool ()
    val dataReadBeatValid = out Bool ()
    val dataReadBeat = out(LineReadBeat(config))
    val dataReadBeatReady = in Bool ()
    val lowerReadValid = out Bool ()
    val lowerRead = out(LineReadRequest(config))
    val lowerReadReady = in Bool ()
    val lowerReadBeatValid = in Bool ()
    val lowerReadBeat = in(LineReadBeat(config))
    val lowerReadBeatReady = out Bool ()
    val activeCount = out UInt (log2Up(config.mshrEntries + 1) bits)
  }
  noIoPrefix()

  val router = new SharedReadMshrRouter(config)
  router.io.instructionReadValid := io.instructionReadValid
  router.io.instructionRead := io.instructionRead
  router.io.instructionReadBeatReady := io.instructionReadBeatReady
  router.io.dataReadValid := io.dataReadValid
  router.io.dataRead := io.dataRead
  router.io.dataReadBeatReady := io.dataReadBeatReady
  router.io.lowerReadReady := io.lowerReadReady
  router.io.lowerReadBeatValid := io.lowerReadBeatValid
  router.io.lowerReadBeat := io.lowerReadBeat

  io.instructionReadReady := router.io.instructionReadReady
  io.instructionReadBeatValid := router.io.instructionReadBeatValid
  io.instructionReadBeat := router.io.instructionReadBeat
  io.dataReadReady := router.io.dataReadReady
  io.dataReadBeatValid := router.io.dataReadBeatValid
  io.dataReadBeat := router.io.dataReadBeat
  io.lowerReadValid := router.io.lowerReadValid
  io.lowerRead := router.io.lowerRead
  io.lowerReadBeatReady := router.io.lowerReadBeatReady
  io.activeCount := router.io.activeCount
}

class SharedReadMshrRouterSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: SharedReadMshrRouterProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: SharedReadMshrRouterProbe): Unit = {
    dut.io.instructionReadValid #= false
    dut.io.instructionRead.lineAddress #= 0
    dut.io.instructionRead.mshrId #= 0
    dut.io.instructionRead.criticalBeat #= 0
    dut.io.instructionReadBeatReady #= true
    dut.io.dataReadValid #= false
    dut.io.dataRead.lineAddress #= 0
    dut.io.dataRead.mshrId #= 0
    dut.io.dataRead.criticalBeat #= 0
    dut.io.dataReadBeatReady #= true
    dut.io.lowerReadReady #= true
    dut.io.lowerReadBeatValid #= false
    dut.io.lowerReadBeat.mshrId #= 0
    dut.io.lowerReadBeat.beat #= 0
    dut.io.lowerReadBeat.data #= 0
    dut.io.lowerReadBeat.last #= false
    dut.io.lowerReadBeat.error #= false
  }

  test("four MSHR identities route interleaved I and D return beats") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-read-mshrs")
      .compile(new SharedReadMshrRouterProbe(config))
      .doSim("ooo-shared-read-mshrs", 0x4c61) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        val ownersData = Array(false, true, false, true)
        val localIds = Array(1, 2, 3, 0)
        for (globalId <- 0 until config.mshrEntries) {
          val isData = ownersData(globalId)
          val address = 0x1000 + globalId * 0x40
          if (isData) {
            dut.io.dataReadValid #= true
            dut.io.dataRead.lineAddress #= address
            dut.io.dataRead.mshrId #= localIds(globalId)
            dut.io.dataRead.criticalBeat #= globalId + 1
          } else {
            dut.io.instructionReadValid #= true
            dut.io.instructionRead.lineAddress #= address
            dut.io.instructionRead.mshrId #= localIds(globalId)
            dut.io.instructionRead.criticalBeat #= globalId + 1
          }
          sleep(1)
          assert(
            if (isData) dut.io.dataReadReady.toBoolean
            else dut.io.instructionReadReady.toBoolean
          )
          sample(dut)
          assert(dut.io.lowerReadValid.toBoolean)
          assert(dut.io.lowerRead.lineAddress.toBigInt == address)
          assert(dut.io.lowerRead.mshrId.toBigInt == globalId)
          assert(dut.io.lowerRead.criticalBeat.toBigInt == globalId + 1)
          dut.io.dataReadValid #= false
          dut.io.instructionReadValid #= false
          sample(dut)
          assert(dut.io.activeCount.toBigInt == globalId + 1)
        }

        dut.io.instructionReadValid #= true
        dut.io.instructionRead.lineAddress #= 0x2000
        dut.io.instructionRead.mshrId #= 2
        sleep(1)
        assert(!dut.io.lowerReadValid.toBoolean)
        assert(!dut.io.instructionReadReady.toBoolean)
        dut.io.instructionReadValid #= false

        dut.io.dataReadBeatReady #= false
        dut.io.lowerReadBeatValid #= true
        dut.io.lowerReadBeat.mshrId #= 1
        dut.io.lowerReadBeat.beat #= 3
        dut.io.lowerReadBeat.data #= BigInt("1122334455667788", 16)
        dut.io.lowerReadBeat.last #= false
        sleep(1)
        assert(dut.io.dataReadBeatValid.toBoolean)
        assert(!dut.io.instructionReadBeatValid.toBoolean)
        assert(dut.io.dataReadBeat.mshrId.toBigInt == 2)
        assert(!dut.io.lowerReadBeatReady.toBoolean)
        dut.io.dataReadBeatReady #= true
        sample(dut)
        assert(dut.io.activeCount.toBigInt == 4)

        dut.io.lowerReadBeatValid #= false
        val releaseOrder = Seq(2, 0, 3, 1)
        releaseOrder.zipWithIndex.foreach { case (globalId, released) =>
          dut.io.lowerReadBeatValid #= true
          dut.io.lowerReadBeat.mshrId #= globalId
          dut.io.lowerReadBeat.beat #= 7
          dut.io.lowerReadBeat.data #= (0x8000 + globalId)
          dut.io.lowerReadBeat.last #= true
          sleep(1)
          assert(dut.io.lowerReadBeatReady.toBoolean)
          if (ownersData(globalId)) {
            assert(dut.io.dataReadBeatValid.toBoolean)
            assert(!dut.io.instructionReadBeatValid.toBoolean)
            assert(dut.io.dataReadBeat.mshrId.toBigInt == localIds(globalId))
          } else {
            assert(dut.io.instructionReadBeatValid.toBoolean)
            assert(!dut.io.dataReadBeatValid.toBoolean)
            assert(dut.io.instructionReadBeat.mshrId.toBigInt == localIds(globalId))
          }
          sample(dut)
          assert(dut.io.activeCount.toBigInt == config.mshrEntries - released - 1)
        }
        dut.io.lowerReadBeatValid #= false
      }
  }

  test("registered queue accepts two requests without a combinational L2 ready path") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-read-mshr-queue")
      .compile(new SharedReadMshrRouterProbe(config))
      .doSim("ooo-shared-read-mshr-queue", 0x51a7) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.io.lowerReadReady #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.dataReadValid #= true
        dut.io.dataRead.lineAddress #= 0x4000
        dut.io.dataRead.mshrId #= 2
        dut.io.dataRead.criticalBeat #= 3
        sleep(1)
        assert(dut.io.dataReadReady.toBoolean)
        sample(dut)
        dut.io.dataReadValid #= false
        assert(dut.io.lowerReadValid.toBoolean)
        assert(dut.io.lowerRead.lineAddress.toBigInt == 0x4000)
        assert(dut.io.lowerRead.mshrId.toBigInt == 0)

        dut.io.instructionReadValid #= true
        dut.io.instructionRead.lineAddress #= 0x5000
        dut.io.instructionRead.mshrId #= 1
        dut.io.instructionRead.criticalBeat #= 5
        sleep(1)
        assert(dut.io.instructionReadReady.toBoolean)
        sample(dut)
        dut.io.instructionReadValid #= false
        assert(dut.io.lowerReadValid.toBoolean)
        assert(dut.io.lowerRead.lineAddress.toBigInt == 0x4000)

        dut.io.dataReadValid #= true
        dut.io.dataRead.lineAddress #= 0x6000
        sleep(1)
        assert(!dut.io.dataReadReady.toBoolean)
        assert(dut.io.lowerRead.lineAddress.toBigInt == 0x4000)

        dut.io.lowerReadReady #= true
        sample(dut)
        assert(dut.io.lowerReadValid.toBoolean)
        assert(dut.io.lowerRead.lineAddress.toBigInt == 0x5000)
        assert(dut.io.lowerRead.mshrId.toBigInt == 1)
        assert(dut.io.dataReadReady.toBoolean)
        sample(dut)
        dut.io.dataReadValid #= false
        assert(dut.io.lowerReadValid.toBoolean)
        assert(dut.io.lowerRead.lineAddress.toBigInt == 0x6000)
        assert(dut.io.lowerRead.mshrId.toBigInt == 2)
      }
  }
}
