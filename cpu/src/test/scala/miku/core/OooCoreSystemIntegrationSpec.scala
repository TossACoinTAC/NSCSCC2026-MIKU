package miku.core

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class OooCoreSystemIntegrationSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: OooCoreSystem): Unit = {
    dut.systemClockDomain.waitSampling()
    sleep(1)
  }

  private def addiW(rd: Int, immediate: Int): BigInt =
    BigInt("02800000", 16) | (BigInt(immediate & 0xfff) << 10) | rd

  private def clearInputs(dut: OooCoreSystem): Unit = {
    dut.io.intrpt #= 0
    dut.io.breakPoint #= false
    dut.io.informationSelect #= false
    dut.io.registerNumber #= 0
    dut.io.axi.ar.ready #= false
    dut.io.axi.r.valid #= false
    dut.io.axi.r.payload.id #= 0
    dut.io.axi.r.payload.data #= 0
    dut.io.axi.r.payload.response #= 0
    dut.io.axi.r.payload.last #= false
    dut.io.axi.aw.ready #= false
    dut.io.axi.w.ready #= false
    dut.io.axi.b.valid #= false
    dut.io.axi.b.payload.id #= 1
    dut.io.axi.b.payload.response #= 0
  }

  test("OoO core system honors reset-time uncached fetch attributes at the board boundary") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-core-system")
      .compile(new OooCoreSystem(config))
      .doSim("ooo-core-system-axi-commit", 0x4c66) { dut =>
        dut.systemClockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.systemClockDomain.assertReset()
        dut.systemClockDomain.waitSampling(2)
        dut.systemClockDomain.deassertReset()
        sample(dut)

        var requestWait = 0
        while (!dut.io.axi.ar.valid.toBoolean && requestWait < config.level2Cache.sets + 100) {
          sample(dut)
          requestWait += 1
        }
        assert(dut.io.axi.ar.valid.toBoolean)
        assert(dut.io.axi.ar.payload.id.toBigInt == 2)
        assert(dut.io.axi.ar.payload.address.toBigInt == config.resetVector)
        assert(dut.io.axi.ar.payload.len.toBigInt == config.fetchWidth - 1)
        assert(dut.io.axi.ar.payload.size.toBigInt == 2)
        dut.io.axi.ar.ready #= true
        sample(dut)
        dut.io.axi.ar.ready #= false

        val instructions = IndexedSeq.tabulate(config.fetchWidth) { index =>
          addiW(index + 1, index + 1)
        }
        for (word <- instructions.indices) {
          dut.io.axi.r.valid #= true
          dut.io.axi.r.payload.id #= 2
          dut.io.axi.r.payload.data #= instructions(word)
          dut.io.axi.r.payload.response #= 0
          dut.io.axi.r.payload.last #= word == instructions.size - 1
          sleep(1)
          assert(dut.io.axi.r.ready.toBoolean)
          sample(dut)
        }
        dut.io.axi.r.valid #= false

        var commitWait = 0
        while (!dut.io.writebackValid.toBoolean && commitWait < 120) {
          sample(dut)
          commitWait += 1
        }
        assert(dut.io.writebackValid.toBoolean)
        assert(dut.io.debugPc.toBigInt == config.resetVector)
        assert(dut.io.debugInstruction.toBigInt == instructions.head)
        assert(dut.io.debugGprWriteMask.toBigInt == 0xf)
        assert(dut.io.debugGprIndex.toBigInt == 1)
        assert(dut.io.debugGprData.toBigInt == 1)
      }
  }
}
