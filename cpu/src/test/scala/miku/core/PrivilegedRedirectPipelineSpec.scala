package miku.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class PrivilegedRedirectPipelineSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("privileged redirect context stays aligned across consecutive requests") {
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-privileged-redirect"
      )
      .compile(new PrivilegedRedirectPipeline(config))
      .doSim("privileged-redirect-context", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.request #= false
        dut.io.ertn #= false
        dut.io.serialPc #= 0
        dut.io.ertnTarget #= 0
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        dut.io.request #= true
        dut.io.serialPc #= BigInt("1c001000", 16)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.redirectValid.toBoolean)

        dut.io.request #= true
        dut.io.ertn #= true
        dut.io.serialPc #= BigInt("1c002000", 16)
        dut.io.ertnTarget #= BigInt("1c003000", 16)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.redirectValid.toBoolean)
        assert(dut.io.redirectTarget.toBigInt == BigInt("1c001004", 16))

        dut.io.request #= false
        dut.io.ertn #= false
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.redirectValid.toBoolean)
        assert(dut.io.redirectTarget.toBigInt == BigInt("1c003000", 16))

        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.redirectValid.toBoolean)
      }
  }
}
