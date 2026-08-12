package miku.privileged

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class IdleControllerSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("IDLE flushes younger work, sleeps at PC plus four, and wakes on interrupt") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-idle-controller")
      .compile(new IdleController(config))
      .doSim("ooo-idle-controller-wakeup", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.enterValid #= false
        dut.io.enterPc #= 0
        dut.io.interruptPending #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.redirectValid.toBoolean)
        assert(!dut.io.sleeping.toBoolean)

        val idlePc = BigInt("1c001200", 16)
        dut.io.enterPc #= idlePc
        dut.io.enterValid #= true
        dut.clockDomain.waitSampling()
        dut.io.enterValid #= false
        sleep(1)
        assert(dut.io.redirectValid.toBoolean)
        assert(dut.io.redirectTarget.toBigInt == idlePc + 4)

        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.redirectValid.toBoolean)
        assert(dut.io.sleeping.toBoolean)
        dut.clockDomain.waitSampling(3)
        sleep(1)
        assert(dut.io.redirectValid.toBoolean)
        assert(dut.io.sleeping.toBoolean)

        dut.io.interruptPending #= true
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.redirectValid.toBoolean)
        assert(!dut.io.sleeping.toBoolean)
        assert(dut.io.redirectTarget.toBigInt == idlePc + 4)
      }
  }
}
