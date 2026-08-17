package miku.memory

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.language.reflectiveCalls

class L2CacheSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit.copy(enableL2WriteBack = false)

  private def sample(dut: L2Cache): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: L2Cache): Unit = {
    dut.io.readValid #= false
    dut.io.read.lineAddress #= 0
    dut.io.read.mshrId #= 0
    dut.io.read.criticalBeat #= 0
    dut.io.readBeatReady #= true
    dut.io.writeValid #= false
    dut.io.write.lineAddress #= 0
    dut.io.write.data #= 0
    dut.io.write.byteMask #= 0
    dut.io.write.mshrId #= 0
    dut.io.memoryReadReady #= false
    dut.io.memoryReadBeatValid #= false
    dut.io.memoryReadBeat.mshrId #= 0
    dut.io.memoryReadBeat.beat #= 0
    dut.io.memoryReadBeat.data #= 0
    dut.io.memoryReadBeat.last #= false
    dut.io.memoryReadBeat.error #= false
    dut.io.memoryWriteReady #= false
    dut.io.memoryWriteResponseValid #= false
    dut.io.memoryWriteResponse.mshrId #= 0
    dut.io.memoryWriteResponse.error #= false
    dut.io.invalidate #= false
    dut.io.writebackInvalidate #= false
    dut.io.maintenanceRequest.valid #= false
    dut.io.maintenanceRequest.code #= 0
    dut.io.maintenanceRequest.virtualAddress #= 0
    dut.io.maintenanceRequest.physicalAddress #= 0
    dut.io.maintenanceRequest.robPointer #= 0
    dut.io.maintenanceRequest.recoveryEpoch #= 0
  }

  test("an L1D writeback is written through and retained as a clean L2 hit") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-cache")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-write-through", 0x4c61) { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        clearInputs(dut)

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)
        assert(!dut.io.invalidateBusy.toBoolean)

        val address = BigInt("d0100000", 16)
        val beats = (0 until CacheContract.BeatsPerLine).map { beat =>
          BigInt("1111000000000000", 16) + beat
        }
        val line = beats.zipWithIndex.foldLeft(BigInt(0)) { case (value, (beat, index)) =>
          value | (beat << (index * CacheContract.BeatBits))
        }

        dut.io.writeValid #= true
        dut.io.write.lineAddress #= address
        dut.io.write.data #= line
        dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
        dut.io.write.mshrId #= 2
        dut.io.readValid #= true
        dut.io.read.lineAddress #= address + CacheContract.LineBytes
        dut.io.read.mshrId #= 1
        sleep(1)
        assert(dut.io.writeReady.toBoolean)
        assert(!dut.io.readReady.toBoolean)
        while (!dut.io.writeReady.toBoolean) { dut.clockDomain.waitSampling() }
        dut.clockDomain.waitSampling()
        dut.io.writeValid #= false
        dut.io.readValid #= false

        var writeWait = 0
        while (!dut.io.memoryWriteValid.toBoolean && writeWait < 8) {
          dut.clockDomain.waitSampling()
          writeWait += 1
        }
        assert(dut.io.memoryWriteValid.toBoolean)
        assert(dut.io.memoryWrite.lineAddress.toBigInt == address)
        assert(dut.io.memoryWrite.data.toBigInt == line)
        assert(
          dut.io.memoryWrite.byteMask.toBigInt ==
            (BigInt(1) << CacheContract.LineBytes) - 1
        )

        dut.io.memoryWriteReady #= true
        dut.clockDomain.waitSampling()
        dut.io.memoryWriteReady #= false
        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 2
        dut.clockDomain.waitSampling()
        dut.io.memoryWriteResponseValid #= false

        dut.io.readBeatReady #= false
        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 3
        dut.io.read.criticalBeat #= 5
        sleep(1)
        while (!dut.io.readReady.toBoolean) { dut.clockDomain.waitSampling() }
        assert(dut.io.readReady.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.readValid #= false

        var firstBeatWait = 0
        while (!dut.io.readBeatValid.toBoolean && firstBeatWait < 8) {
          dut.clockDomain.waitSampling()
          sleep(1)
          firstBeatWait += 1
        }
        assert(dut.io.readBeatValid.toBoolean)
        assert(!dut.io.readBeatReady.toBoolean)
        for (_ <- 0 until 2) {
          sleep(1)
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.beat.toBigInt == 5)
          assert(dut.io.readBeat.data.toBigInt == beats(5))
          dut.clockDomain.waitSampling()
        }
        dut.io.readBeatReady #= true

        val expectedOrder = Seq(5, 6, 7, 0, 1, 2, 3, 4)
        for ((expectedBeat, responseIndex) <- expectedOrder.zipWithIndex) {
          var responseWait = 0
          while (!dut.io.readBeatValid.toBoolean && responseWait < 8) {
            assert(!dut.io.memoryReadValid.toBoolean)
            dut.clockDomain.waitSampling()
            responseWait += 1
          }
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.mshrId.toBigInt == 3)
          assert(dut.io.readBeat.beat.toBigInt == expectedBeat)
          assert(dut.io.readBeat.data.toBigInt == beats(expectedBeat))
          assert(
            dut.io.readBeat.last.toBoolean ==
              (responseIndex == CacheContract.BeatsPerLine - 1)
          )
          dut.clockDomain.waitSampling()
          sleep(1)
        }
      }
  }

  test("write-back L2 defers memory writes until dirty eviction or maintenance") {
    val writeBackConfig = config.copy(enableL2WriteBack = true)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-write-back")
      .compile(new L2Cache(writeBackConfig))
      .doSim("ooo-l2-write-back", 0x4c70) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        SimTimeout(20000)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(writeBackConfig.level2Cache.sets + 4)

        def waitUntil(condition: => Boolean, clue: String, maxCycles: Int = 64): Unit = {
          var cycles = 0
          while (!condition && cycles < maxCycles) {
            sample(dut)
            cycles += 1
          }
          assert(condition, clue)
        }

        def writeLine(address: BigInt, data: BigInt, mshrId: Int): Unit = {
          dut.io.writeValid #= true
          dut.io.write.lineAddress #= address
          dut.io.write.data #= data
          dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
          dut.io.write.mshrId #= mshrId
          waitUntil(dut.io.writeReady.toBoolean, s"write 0x${address.toString(16)} was not accepted")
          sample(dut)
          dut.io.writeValid #= false
        }

        def expectLocalCompletion(mshrId: Int): Unit = {
          var cycles = 0
          while (!dut.io.writeResponseValid.toBoolean && cycles < 16) {
            assert(!dut.io.memoryWriteValid.toBoolean)
            sample(dut)
            cycles += 1
          }
          assert(dut.io.writeResponseValid.toBoolean)
          assert(dut.io.writeResponse.mshrId.toBigInt == mshrId)
          assert(!dut.io.writeResponse.error.toBoolean)
          assert(!dut.io.memoryWriteValid.toBoolean)
          sample(dut)
        }

        val setStride = BigInt(writeBackConfig.level2Cache.sets) * CacheContract.LineBytes
        val addressA = BigInt("d0400000", 16)
        val addressB = addressA + setStride
        val addressC = addressB + setStride
        val dataA = BigInt(0x11)
        val dataB = BigInt(0x22)
        val dataC = BigInt(0x33)

        writeLine(addressA, dataA, 0)
        expectLocalCompletion(0)
        writeLine(addressB, dataB, 1)
        expectLocalCompletion(1)

        // Both ways now contain dirty lines. The replacement must first preserve A in memory.
        writeLine(addressC, dataC, 2)
        waitUntil(dut.io.memoryWriteValid.toBoolean, "dirty L2 victim was not written back")
        assert(dut.io.memoryWrite.lineAddress.toBigInt == addressA)
        assert(dut.io.memoryWrite.data.toBigInt == dataA)
        assert(dut.io.memoryWrite.mshrId.toBigInt == 2)
        dut.io.memoryWriteReady #= true
        sample(dut)
        dut.io.memoryWriteReady #= false
        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 2
        sample(dut)
        dut.io.memoryWriteResponseValid #= false
        expectLocalCompletion(2)

        // Hit-mode CACOP must also write a dirty line before invalidating it.
        waitUntil(dut.io.maintenanceRequest.ready.toBoolean, "L2 did not become idle")
        dut.io.maintenanceRequest.valid #= true
        dut.io.maintenanceRequest.code #= 0x12
        dut.io.maintenanceRequest.virtualAddress #= addressC
        dut.io.maintenanceRequest.physicalAddress #= addressC
        sample(dut)
        dut.io.maintenanceRequest.valid #= false
        waitUntil(dut.io.memoryWriteValid.toBoolean, "dirty CACOP target was not written back")
        assert(dut.io.memoryWrite.lineAddress.toBigInt == addressC)
        assert(dut.io.memoryWrite.data.toBigInt == dataC)
        dut.io.memoryWriteReady #= true
        sample(dut)
        dut.io.memoryWriteReady #= false
        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 0
        sample(dut)
        dut.io.memoryWriteResponseValid #= false
        waitUntil(dut.io.maintenanceDone.toBoolean, "dirty CACOP did not complete")
        sample(dut)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= addressC
        dut.io.read.mshrId #= 3
        waitUntil(dut.io.readReady.toBoolean, "post-CACOP lookup was not accepted")
        sample(dut)
        dut.io.readValid #= false
        waitUntil(dut.io.memoryReadValid.toBoolean, "CACOP target remained valid in L2")
        assert(dut.io.memoryRead.lineAddress.toBigInt == addressC)
      }
  }

  test("each dirty read miss retains its victim data across another miss and error retry") {
    val writeBackConfig = config.copy(enableL2WriteBack = true)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-victim-owner")
      .compile(new L2Cache(writeBackConfig))
      .doSim("ooo-l2-victim-owner-error-retry", 0x4c71) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        SimTimeout(30000)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(writeBackConfig.level2Cache.sets + 4)

        def waitUntil(condition: => Boolean, clue: String, maxCycles: Int = 64): Unit = {
          var cycles = 0
          while (!condition && cycles < maxCycles) {
            sample(dut)
            cycles += 1
          }
          assert(condition, clue)
        }

        def writeLine(address: BigInt, data: BigInt, mshrId: Int): Unit = {
          dut.io.writeValid #= true
          dut.io.write.lineAddress #= address
          dut.io.write.data #= data
          dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
          dut.io.write.mshrId #= mshrId
          waitUntil(dut.io.writeReady.toBoolean, s"write 0x${address.toString(16)} was not accepted")
          sample(dut)
          dut.io.writeValid #= false
          waitUntil(dut.io.writeResponseValid.toBoolean, "write-back L2 did not complete locally")
          assert(!dut.io.writeResponse.error.toBoolean)
          sample(dut)
        }

        def readMiss(address: BigInt, mshrId: Int): Unit = {
          dut.io.readValid #= true
          dut.io.read.lineAddress #= address
          dut.io.read.mshrId #= mshrId
          dut.io.read.criticalBeat #= 0
          sleep(1)
          waitUntil(dut.io.readReady.toBoolean, s"read 0x${address.toString(16)} was not accepted")
          sample(dut)
          dut.io.readValid #= false
        }

        val setStride = BigInt(writeBackConfig.level2Cache.sets) * CacheContract.LineBytes
        val set0A = BigInt("d0600000", 16)
        val set0B = set0A + setStride
        val set0C = set0B + setStride
        val set1A = set0A + CacheContract.LineBytes
        val set1B = set1A + setStride
        val set1C = set1B + setStride
        val victim0 = BigInt("1111222233334444", 16)
        val victim1 = BigInt("aaaabbbbccccdddd", 16)

        writeLine(set0A, victim0, 0)
        writeLine(set0B, BigInt(0x20), 1)
        writeLine(set1A, victim1, 2)
        writeLine(set1B, BigInt(0x40), 3)

        readMiss(set0C, 0)
        waitUntil(
          dut.io.memoryWriteValid.toBoolean && dut.io.memoryWrite.mshrId.toBigInt == 0,
          "first dirty victim did not reach memory"
        )
        assert(dut.io.memoryWrite.lineAddress.toBigInt == set0A)
        assert(dut.io.memoryWrite.data.toBigInt == victim0)
        dut.io.memoryWriteReady #= true
        sample(dut)
        dut.io.memoryWriteReady #= false

        readMiss(set1C, 1)
        waitUntil(
          dut.io.memoryWriteValid.toBoolean && dut.io.memoryWrite.mshrId.toBigInt == 1,
          "second dirty victim did not reach memory"
        )
        assert(dut.io.memoryWrite.lineAddress.toBigInt == set1A)
        assert(dut.io.memoryWrite.data.toBigInt == victim1)

        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 0
        dut.io.memoryWriteResponse.error #= true
        sample(dut)
        dut.io.memoryWriteResponseValid #= false
        dut.io.memoryWriteResponse.error #= false
        sleep(1)

        assert(dut.io.memoryWriteValid.toBoolean)
        assert(dut.io.memoryWrite.mshrId.toBigInt == 0)
        assert(dut.io.memoryWrite.lineAddress.toBigInt == set0A)
        assert(
          dut.io.memoryWrite.data.toBigInt == victim0,
          "retry used victim data captured by another MSHR"
        )
      }
  }

  test("an L2 hit returns while an unrelated memory miss is outstanding") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-cache")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-hit-under-miss", 0x4c64) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)

        val hitAddress = BigInt("d0100000", 16)
        val missAddress = BigInt("00002000", 16)
        val beats = (0 until CacheContract.BeatsPerLine).map { beat =>
          BigInt("5a5a000000000000", 16) + beat
        }
        val line = beats.zipWithIndex.foldLeft(BigInt(0)) { case (value, (beat, index)) =>
          value | (beat << (index * CacheContract.BeatBits))
        }

        dut.io.writeValid #= true
        dut.io.write.lineAddress #= hitAddress
        dut.io.write.data #= line
        dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
        dut.io.write.mshrId #= 3
        while (!dut.io.writeReady.toBoolean) { dut.clockDomain.waitSampling() }
        dut.clockDomain.waitSampling()
        dut.io.writeValid #= false
        while (!dut.io.memoryWriteValid.toBoolean) { dut.clockDomain.waitSampling() }
        dut.io.memoryWriteReady #= true
        dut.clockDomain.waitSampling()
        dut.io.memoryWriteReady #= false
        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 3
        dut.clockDomain.waitSampling()
        dut.io.memoryWriteResponseValid #= false
        dut.clockDomain.waitSampling(2)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= missAddress
        dut.io.read.mshrId #= 0
        while (!dut.io.readReady.toBoolean) { dut.clockDomain.waitSampling() }
        dut.clockDomain.waitSampling()
        dut.io.readValid #= false
        while (!dut.io.memoryReadValid.toBoolean) { dut.clockDomain.waitSampling() }
        assert(dut.io.memoryRead.lineAddress.toBigInt == missAddress)
        assert(dut.io.memoryRead.mshrId.toBigInt == 0)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= hitAddress
        dut.io.read.mshrId #= 1
        while (!dut.io.readReady.toBoolean) { dut.clockDomain.waitSampling() }
        dut.clockDomain.waitSampling()
        dut.io.readValid #= false

        for (beat <- beats.indices) {
          while (!dut.io.readBeatValid.toBoolean) { dut.clockDomain.waitSampling() }
          assert(dut.io.readBeat.mshrId.toBigInt == 1)
          assert(dut.io.readBeat.beat.toBigInt == beat)
          assert(dut.io.readBeat.data.toBigInt == beats(beat))
          assert(dut.io.memoryReadValid.toBoolean)
          assert(dut.io.memoryRead.mshrId.toBigInt == 0)
          dut.clockDomain.waitSampling()
        }
      }
  }

  test("a memory refill streams beats to the requesting L1 before the L2 install") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-streaming-refill")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-streaming-refill", 0x4c62) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)

        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)

        val address = BigInt("1c123400", 16)
        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 2
        while (!dut.io.readReady.toBoolean) { dut.clockDomain.waitSampling() }
        dut.clockDomain.waitSampling()
        dut.io.readValid #= false

        while (!dut.io.memoryReadValid.toBoolean) { dut.clockDomain.waitSampling() }
        assert(dut.io.memoryRead.lineAddress.toBigInt == address)
        assert(dut.io.memoryRead.mshrId.toBigInt == 2)
        dut.io.memoryReadReady #= true
        dut.clockDomain.waitSampling()
        dut.io.memoryReadReady #= false

        val beats = (0 until CacheContract.BeatsPerLine).map { beat =>
          BigInt("abcd000000000000", 16) + beat
        }
        // The empty elastic slot accepts the critical beat even while L1 is stalled.
        dut.io.readBeatReady #= false
        dut.io.memoryReadBeatValid #= true
        dut.io.memoryReadBeat.mshrId #= 2
        dut.io.memoryReadBeat.beat #= 0
        dut.io.memoryReadBeat.data #= beats(0)
        dut.io.memoryReadBeat.last #= false
        sleep(1)
        assert(dut.io.memoryReadBeatReady.toBoolean)
        assert(!dut.io.readBeatValid.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        assert(dut.io.readBeatValid.toBoolean)
        assert(dut.io.readBeat.beat.toBigInt == 0)
        assert(dut.io.readBeat.data.toBigInt == beats(0))
        assert(!dut.io.memoryReadBeatReady.toBoolean)

        for (_ <- 0 until 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.beat.toBigInt == 0)
          assert(dut.io.readBeat.data.toBigInt == beats(0))
          assert(!dut.io.memoryReadBeatReady.toBoolean)
        }

        // Pop the buffered beat and replace it on the same edge, then sustain one beat/cycle.
        dut.io.readBeatReady #= true
        for (beat <- 1 until beats.size) {
          dut.io.memoryReadBeat.beat #= beat
          dut.io.memoryReadBeat.data #= beats(beat)
          dut.io.memoryReadBeat.last #= beat == beats.size - 1
          sleep(1)
          assert(dut.io.memoryReadBeatReady.toBoolean)
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.mshrId.toBigInt == 2)
          assert(dut.io.readBeat.beat.toBigInt == beat)
          assert(dut.io.readBeat.data.toBigInt == beats(beat))
          assert(dut.io.readBeat.last.toBoolean == (beat == beats.size - 1))
        }
        dut.io.memoryReadBeatValid #= false

        dut.clockDomain.waitSampling(2)
        assert(dut.io.readReady.toBoolean)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 3
        dut.clockDomain.waitSampling()
        dut.io.readValid #= false
        // The L2 hit path uses the same registered response boundary as a refill.
        // Hold the first beat to prove its selected MSHR identity remains stable
        // before the downstream L1 accepts it.
        dut.io.readBeatReady #= false
        var firstHitWaitCycles = 0
        while (!dut.io.readBeatValid.toBoolean && firstHitWaitCycles < 8) {
          assert(!dut.io.memoryReadValid.toBoolean)
          dut.clockDomain.waitSampling()
          firstHitWaitCycles += 1
        }
        assert(dut.io.readBeatValid.toBoolean)
        assert(dut.io.readBeat.mshrId.toBigInt == 3)
        assert(dut.io.readBeat.beat.toBigInt == 0)
        assert(dut.io.readBeat.data.toBigInt == beats(0))
        for (_ <- 0 until 2) {
          dut.clockDomain.waitSampling()
          sleep(1)
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.mshrId.toBigInt == 3)
          assert(dut.io.readBeat.beat.toBigInt == 0)
          assert(dut.io.readBeat.data.toBigInt == beats(0))
        }
        dut.io.readBeatReady #= true
        for (beat <- beats.indices) {
          var waitCycles = 0
          while (!dut.io.readBeatValid.toBoolean && waitCycles < 8) {
            assert(!dut.io.memoryReadValid.toBoolean)
            dut.clockDomain.waitSampling()
            waitCycles += 1
          }
          assert(dut.io.readBeatValid.toBoolean)
          assert(dut.io.readBeat.mshrId.toBigInt == 3)
          assert(dut.io.readBeat.beat.toBigInt == beat)
          assert(dut.io.readBeat.data.toBigInt == beats(beat))
          dut.clockDomain.waitSampling()
        }
      }
  }

  test("four L2 miss slots route interleaved memory returns by global identity") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-four-mshr")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-four-mshr-interleaved", 0x4c63) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)

        val addresses = (0 until config.mshrEntries).map(index => BigInt(0x400 + index * 0x40))
        for ((address, id) <- addresses.zipWithIndex) {
          dut.io.readValid #= true
          dut.io.read.lineAddress #= address
          dut.io.read.mshrId #= id
          var wait = 0
          while (!dut.io.readReady.toBoolean && wait < 8) {
            dut.clockDomain.waitSampling()
            wait += 1
          }
          assert(dut.io.readReady.toBoolean)
          dut.clockDomain.waitSampling()
          dut.io.readValid #= false
          dut.clockDomain.waitSampling()
        }

        dut.io.readValid #= true
        dut.io.read.lineAddress #= 0x800
        dut.io.read.mshrId #= 0
        for (_ <- 0 until 3) dut.clockDomain.waitSampling()
        assert(!dut.io.readReady.toBoolean)
        dut.io.readValid #= false

        dut.io.memoryReadReady #= true
        for ((address, id) <- addresses.zipWithIndex) {
          var wait = 0
          while (!dut.io.memoryReadValid.toBoolean && wait < 8) {
            dut.clockDomain.waitSampling()
            wait += 1
          }
          assert(dut.io.memoryReadValid.toBoolean)
          assert(dut.io.memoryRead.lineAddress.toBigInt == address)
          assert(dut.io.memoryRead.mshrId.toBigInt == id)
          dut.clockDomain.waitSampling()
          sleep(1)
        }
        dut.io.memoryReadReady #= false

        for (beat <- 0 until CacheContract.BeatsPerLine) {
          for (id <- (0 until config.mshrEntries).reverse) {
            val data = BigInt(id * 0x100 + beat)
            dut.io.memoryReadBeatValid #= true
            dut.io.memoryReadBeat.mshrId #= id
            dut.io.memoryReadBeat.beat #= beat
            dut.io.memoryReadBeat.data #= data
            dut.io.memoryReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
            sleep(1)
            assert(dut.io.memoryReadBeatReady.toBoolean)
            dut.clockDomain.waitSampling()
            sleep(1)
            assert(dut.io.readBeatValid.toBoolean)
            assert(dut.io.readBeat.mshrId.toBigInt == id)
            assert(dut.io.readBeat.beat.toBigInt == beat)
            assert(dut.io.readBeat.data.toBigInt == data)
            assert(dut.io.readBeat.last.toBoolean == (beat == CacheContract.BeatsPerLine - 1))
          }
        }
        dut.io.memoryReadBeatValid #= false
      }
  }

  test("L2 does not install a line when a memory refill reports an error") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-refill-error")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-refill-error", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)

        val address = BigInt(0x5400)
        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 1
        while (!dut.io.readReady.toBoolean) sample(dut)
        sample(dut)
        dut.io.readValid #= false
        while (!dut.io.memoryReadValid.toBoolean) sample(dut)
        dut.io.memoryReadReady #= true
        sample(dut)
        dut.io.memoryReadReady #= false

        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.memoryReadBeatValid #= true
          dut.io.memoryReadBeat.mshrId #= 1
          dut.io.memoryReadBeat.beat #= beat
          dut.io.memoryReadBeat.data #= BigInt("fedc000000000000", 16) + beat
          dut.io.memoryReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          dut.io.memoryReadBeat.error #= beat == 5
          assert(dut.io.memoryReadBeatReady.toBoolean)
          sample(dut)
        }
        dut.io.memoryReadBeatValid #= false
        dut.io.memoryReadBeat.error #= false
        sample(dut)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 2
        while (!dut.io.readReady.toBoolean) sample(dut)
        sample(dut)
        dut.io.readValid #= false
        var waitCycles = 0
        while (!dut.io.memoryReadValid.toBoolean && waitCycles < 8) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(dut.io.memoryRead.lineAddress.toBigInt == address)
        assert(dut.io.memoryRead.mshrId.toBigInt == 2)
      }
  }

  test("L2 reports a failed write-through and leaves the replacement uninstalled") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-write-error")
      .compile(new L2Cache(config))
      .doSim("ooo-l2-write-error", 0x4c69) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        SimTimeout(10000)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)
        while (dut.io.invalidateBusy.toBoolean) sample(dut)

        val address = BigInt("d0200000", 16)
        dut.io.writeValid #= true
        dut.io.write.lineAddress #= address
        dut.io.write.data #= BigInt("123456789abcdef", 16)
        dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
        dut.io.write.mshrId #= 3
        sleep(1)
        assert(dut.io.write.byteMask.toBigInt == (BigInt(1) << CacheContract.LineBytes) - 1)
        var waitCycles = 0
        while (!dut.io.writeReady.toBoolean && waitCycles < 16) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.writeReady.toBoolean)
        sample(dut)
        dut.io.writeValid #= false
        waitCycles = 0
        while (!dut.io.memoryWriteValid.toBoolean && waitCycles < 16) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.memoryWriteValid.toBoolean)
        assert(dut.io.memoryWrite.lineAddress.toBigInt == address)
        dut.io.memoryWriteReady #= true
        sample(dut)
        dut.io.memoryWriteReady #= false
        dut.io.memoryWriteResponseValid #= true
        dut.io.memoryWriteResponse.mshrId #= 3
        dut.io.memoryWriteResponse.error #= true
        sample(dut)
        dut.io.memoryWriteResponseValid #= false
        dut.io.memoryWriteResponse.error #= false
        assert(dut.io.writeResponseValid.toBoolean)
        assert(dut.io.writeResponse.mshrId.toBigInt == 3)
        assert(dut.io.writeResponse.error.toBoolean)
        sample(dut)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= address
        dut.io.read.mshrId #= 1
        waitCycles = 0
        while (!dut.io.readReady.toBoolean && waitCycles < 16) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.readReady.toBoolean)
        sample(dut)
        dut.io.readValid #= false
        waitCycles = 0
        while (!dut.io.memoryReadValid.toBoolean && waitCycles < 8) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(dut.io.memoryRead.lineAddress.toBigInt == address)
      }
  }

  test("L2 CACOP modes preserve unrelated lines and Hit miss has no side effect") {
    val compiled = SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l2-maintenance")
      .compile(new L2Cache(config))

    for ((mode, seed) <- Seq(0 -> 0x4c65, 1 -> 0x4c66, 2 -> 0x4c67)) {
      compiled.doSim(s"ooo-l2-exact-maintenance-$mode", seed) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        SimTimeout(10000)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 4)

        def waitUntil(condition: => Boolean, clue: String, maxCycles: Int = 64): Unit = {
          var cycles = 0
          while (!condition && cycles < maxCycles) {
            sample(dut)
            cycles += 1
          }
          assert(condition, clue)
        }

        def install(address: BigInt, pattern: BigInt): Unit = {
          dut.io.writeValid #= true
          dut.io.write.lineAddress #= address
          dut.io.write.data #= pattern
          dut.io.write.byteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
          dut.io.write.mshrId #= 0
          sleep(1)
          waitUntil(dut.io.writeReady.toBoolean, s"L2 write for 0x${address.toString(16)} was not accepted")
          sample(dut)
          dut.io.writeValid #= false
          waitUntil(
            dut.io.memoryWriteValid.toBoolean,
            s"L2 write-through for 0x${address.toString(16)} was not issued"
          )
          assert(dut.io.memoryWrite.lineAddress.toBigInt == address)
          dut.io.memoryWriteReady #= true
          sample(dut)
          dut.io.memoryWriteReady #= false
          dut.io.memoryWriteResponseValid #= true
          dut.io.memoryWriteResponse.mshrId #= 0
          sample(dut)
          dut.io.memoryWriteResponseValid #= false
          waitUntil(
            dut.io.maintenanceRequest.ready.toBoolean,
            s"L2 did not become idle after installing 0x${address.toString(16)}"
          )
        }

        def maintain(code: Int, virtualAddress: BigInt, physicalAddress: BigInt): Unit = {
          waitUntil(dut.io.maintenanceRequest.ready.toBoolean, s"CACOP 0x${code.toHexString} was not accepted")
          dut.io.maintenanceRequest.valid #= true
          dut.io.maintenanceRequest.code #= code
          dut.io.maintenanceRequest.virtualAddress #= virtualAddress
          dut.io.maintenanceRequest.physicalAddress #= physicalAddress
          sleep(1)
          sample(dut)
          dut.io.maintenanceRequest.valid #= false
          waitUntil(dut.io.maintenanceDone.toBoolean, s"CACOP 0x${code.toHexString} did not complete", 16)
          sample(dut)
        }

        def expectHit(address: BigInt): Unit = {
          dut.io.readValid #= true
          dut.io.read.lineAddress #= address
          dut.io.read.mshrId #= 1
          dut.io.read.criticalBeat #= 0
          sleep(1)
          waitUntil(dut.io.readReady.toBoolean, s"L2 hit lookup for 0x${address.toString(16)} was not accepted")
          sample(dut)
          dut.io.readValid #= false
          for (beat <- 0 until CacheContract.BeatsPerLine) {
            var cycles = 0
            while (!dut.io.readBeatValid.toBoolean && cycles < 8) {
              assert(!dut.io.memoryReadValid.toBoolean)
              sample(dut)
              cycles += 1
            }
            assert(dut.io.readBeatValid.toBoolean)
            assert(dut.io.readBeat.beat.toBigInt == beat)
            sample(dut)
          }
        }

        val line0 = BigInt(0x100)
        val line1 = BigInt(0x8100)
        install(line0, BigInt(1))
        install(line1, BigInt(2))

        // Hit-on-address miss must complete without touching either resident line.
        maintain(0x12, 0x10100, 0x10100)
        expectHit(line1)

        val code = (mode << 3) | 2
        val virtualAddress = if (mode == 1) line0 else line0
        val physicalAddress = if (mode == 2) line0 else BigInt(0)
        maintain(code, virtualAddress, physicalAddress)
        expectHit(line1)

        dut.io.readValid #= true
        dut.io.read.lineAddress #= line0
        dut.io.read.mshrId #= 2
        dut.io.read.criticalBeat #= 0
        sleep(1)
        waitUntil(dut.io.readReady.toBoolean, "invalidated L2 line lookup was not accepted")
        sample(dut)
        dut.io.readValid #= false
        var cycles = 0
        while (!dut.io.memoryReadValid.toBoolean && cycles < 8) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(dut.io.memoryRead.lineAddress.toBigInt == line0)
      }
    }
  }
}
