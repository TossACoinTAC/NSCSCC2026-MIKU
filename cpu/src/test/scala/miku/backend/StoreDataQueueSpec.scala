package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class StoreDataQueueSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  test("Store data waits for writeback and holds its registered PRF request") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-store-data-queue")
      .compile(new StoreDataQueue(config))
      .doSim("ooo-store-data-queue-wakeup-hold", 0x4c71) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.enqueueValid #= false
        dut.io.enqueue.psrc2 #= 0
        dut.io.enqueue.source2Ready #= false
        dut.io.enqueue.robPointer #= 0
        dut.io.enqueue.storeQueueIndex #= 0
        dut.io.wakeupValid #= 0
        for (lane <- 0 until config.writebackWidth) {
          dut.io.wakeupPdst(lane) #= 0
        }
        dut.io.readReady #= false
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        dut.io.enqueueValid #= true
        dut.io.enqueue.psrc2 #= 5
        dut.io.enqueue.source2Ready #= false
        dut.io.enqueue.robPointer #= 9
        dut.io.enqueue.storeQueueIndex #= 3
        assert(dut.io.enqueueReady.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.enqueueValid #= false
        assert(!dut.io.readValid.toBoolean)

        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 5
        dut.clockDomain.waitSampling()
        dut.io.wakeupValid #= 0
        sleep(1)
        assert(dut.io.readValid.toBoolean)
        assert(dut.io.readPsrc.toBigInt == 5)
        assert(dut.io.readRobPointer.toBigInt == 9)
        assert(dut.io.readStoreQueueIndex.toBigInt == 3)

        dut.clockDomain.waitSampling(2)
        assert(dut.io.readValid.toBoolean)
        assert(dut.io.readRobPointer.toBigInt == 9)

        dut.io.readReady #= true
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(!dut.io.readValid.toBoolean)
        assert(dut.io.occupancy.toBigInt == 0)
      }
  }

  test("Store data observation detects physical-slot selection younger than another ready Store") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-store-data-age")
      .compile(new StoreDataQueue(config))
      .doSim("ooo-store-data-physical-order-observation", 0x5d82) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.enqueueValid #= false
        dut.io.enqueue.psrc2 #= 0
        dut.io.enqueue.source2Ready #= false
        dut.io.enqueue.robPointer #= 0
        dut.io.enqueue.storeQueueIndex #= 0
        dut.io.wakeupValid #= 0
        for (lane <- 0 until config.writebackWidth) {
          dut.io.wakeupPdst(lane) #= 0
        }
        dut.io.readReady #= false
        dut.io.flush #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling()

        // A ready filler occupies physical slot 0.
        dut.io.enqueueValid #= true
        dut.io.enqueue.psrc2 #= 3
        dut.io.enqueue.source2Ready #= true
        dut.io.enqueue.robPointer #= 9
        dut.io.enqueue.storeQueueIndex #= 0
        dut.clockDomain.waitSampling()

        // While the filler moves into the held output register, an older
        // waiting Store enters slot 1 and slot 0 becomes reusable.
        dut.io.enqueue.psrc2 #= 4
        dut.io.enqueue.source2Ready #= false
        dut.io.enqueue.robPointer #= 10
        dut.io.enqueue.storeQueueIndex #= 1
        dut.clockDomain.waitSampling()

        // A younger ready Store reuses physical slot 0.
        dut.io.enqueue.psrc2 #= 5
        dut.io.enqueue.source2Ready #= true
        dut.io.enqueue.robPointer #= 11
        dut.io.enqueue.storeQueueIndex #= 2
        dut.clockDomain.waitSampling()
        dut.io.enqueueValid #= false

        // Wake the older Store in slot 1. Lowest-slot selection now points at
        // younger pointer 11 even though pointer 10 is also ready.
        dut.io.wakeupValid #= 1
        dut.io.wakeupPdst(0) #= 4
        sleep(1)
        assert(dut.io.multipleReady.toBoolean)
        assert(dut.io.physicalSelectionOutOfAgeOrder.toBoolean)

        dut.clockDomain.waitSampling()
        dut.io.wakeupValid #= 0
        sleep(1)
        assert(dut.io.multipleReady.toBoolean)
        assert(dut.io.physicalSelectionOutOfAgeOrder.toBoolean)
      }
  }
}
