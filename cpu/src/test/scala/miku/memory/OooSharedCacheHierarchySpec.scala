package miku.memory

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class OooSharedCacheHierarchyProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val instructionRequestValid = in Bool ()
    val instructionRequest = in(OooInstructionCacheRequest(config))
    val instructionRequestReady = out Bool ()
    val instructionResponseValid = out Bool ()
    val instructionResponse = out(OooInstructionCacheResponse(config))
    val instructionKill = in Bool ()
    val dataRequestValid = in Bool ()
    val dataRequest = in(OooCacheRequest(config))
    val dataRequestReady = out Bool ()
    val dataResponseValid = out Bool ()
    val dataResponse = out(OooCacheResponse(config))
    val uncachedInstructionRequestValid = out Bool ()
    val uncachedInstructionRequest = out(OooInstructionCacheRequest(config))
    val uncachedInstructionRequestReady = in Bool ()
    val uncachedInstructionResponseValid = in Bool ()
    val uncachedInstructionResponse = in(OooInstructionCacheResponse(config))
    val uncachedDataRequestValid = out Bool ()
    val uncachedDataRequest = out(OooCacheRequest(config))
    val uncachedDataRequestReady = in Bool ()
    val uncachedDataResponseValid = in Bool ()
    val uncachedDataResponse = in(OooCacheResponse(config))
    val memoryReadValid = out Bool ()
    val memoryRead = out(OooLineReadRequest(config))
    val memoryReadReady = in Bool ()
    val memoryReadBeatValid = in Bool ()
    val memoryReadBeat = in(OooLineReadBeat(config))
    val memoryReadBeatReady = out Bool ()
    val memoryWriteValid = out Bool ()
    val memoryWriteLineAddress = out UInt (config.xlen bits)
    val memoryWriteDataWords = out Vec (Bits(32 bits), OooCacheContract.LineBytes / 4)
    val memoryWriteReady = in Bool ()
    val invalidate = in Bool ()
    val dataInvalidate = in Bool ()
    val dataWritebackInvalidate = in Bool ()
    val level2Invalidate = in Bool ()
    val invalidateBusy = out Bool ()
    val barrierDrain = in Bool ()
    val instructionBarrierMaintenanceStart = in Bool ()
    val instructionBarrierMaintenanceReady = out Bool ()
    val instructionBarrierMaintenanceDone = out Bool ()
    val idle = out Bool ()
  }
  noIoPrefix()

  val hierarchy = new OooSharedCacheHierarchy(config)
  hierarchy.cachedDataResponseValid.simPublic()
  hierarchy.io.instructionRequestValid := io.instructionRequestValid && !io.instructionRequest.uncached
  hierarchy.io.instructionUncachedRequestValid :=
    io.instructionRequestValid && io.instructionRequest.uncached
  hierarchy.io.instructionRequest := io.instructionRequest
  hierarchy.io.instructionKill := io.instructionKill
  hierarchy.io.dataRequestValid := io.dataRequestValid
  hierarchy.io.dataRequest := io.dataRequest
  hierarchy.io.uncachedInstructionRequestReady := io.uncachedInstructionRequestReady
  hierarchy.io.uncachedInstructionResponseValid := io.uncachedInstructionResponseValid
  hierarchy.io.uncachedInstructionResponse := io.uncachedInstructionResponse
  hierarchy.io.uncachedDataRequestReady := io.uncachedDataRequestReady
  hierarchy.io.uncachedDataResponseValid := io.uncachedDataResponseValid
  hierarchy.io.uncachedDataResponse := io.uncachedDataResponse
  hierarchy.io.memoryReadReady := io.memoryReadReady
  hierarchy.io.memoryReadBeatValid := io.memoryReadBeatValid
  hierarchy.io.memoryReadBeat := io.memoryReadBeat
  hierarchy.io.memoryWriteReady := io.memoryWriteReady
  val memoryWriteFire = hierarchy.io.memoryWriteValid && io.memoryWriteReady
  val memoryWriteResponseValid = RegNext(memoryWriteFire) init (False)
  val memoryWriteResponseMshrId = Reg(UInt(log2Up(config.mshrEntries) bits))
  when(memoryWriteFire) {
    memoryWriteResponseMshrId := hierarchy.io.memoryWrite.mshrId
  }
  hierarchy.io.memoryWriteResponseValid := memoryWriteResponseValid
  hierarchy.io.memoryWriteResponse.mshrId := memoryWriteResponseMshrId
  hierarchy.io.memoryWriteResponse.error := False
  hierarchy.io.invalidate := io.invalidate
  hierarchy.io.dataInvalidate := io.dataInvalidate
  hierarchy.io.dataWritebackInvalidate := io.dataWritebackInvalidate
  hierarchy.io.level2Invalidate := io.level2Invalidate
  hierarchy.io.barrierDrain := io.barrierDrain
  hierarchy.io.instructionBarrierMaintenanceStart :=
    io.instructionBarrierMaintenanceStart
  hierarchy.io.cacheMaintenanceRequest.valid := False
  hierarchy.io.cacheMaintenanceRequest.payload.assignFromBits(
    B(0, hierarchy.io.cacheMaintenanceRequest.payload.getBitsWidth bits)
  )
  hierarchy.io.cacheMaintenanceResponse.ready := True

  io.instructionRequestReady := hierarchy.io.instructionRequestReady
  io.instructionResponseValid := hierarchy.io.instructionResponseValid
  io.instructionResponse := hierarchy.io.instructionResponse
  io.dataRequestReady := hierarchy.io.dataRequestReady
  io.dataResponseValid := hierarchy.io.dataResponseValid
  io.dataResponse := hierarchy.io.dataResponse
  io.uncachedInstructionRequestValid := hierarchy.io.uncachedInstructionRequestValid
  io.uncachedInstructionRequest := hierarchy.io.uncachedInstructionRequest
  io.uncachedDataRequestValid := hierarchy.io.uncachedDataRequestValid
  io.uncachedDataRequest := hierarchy.io.uncachedDataRequest
  io.memoryReadValid := hierarchy.io.memoryReadValid
  io.memoryRead := hierarchy.io.memoryRead
  io.memoryReadBeatReady := hierarchy.io.memoryReadBeatReady
  io.memoryWriteValid := hierarchy.io.memoryWriteValid
  io.memoryWriteLineAddress := hierarchy.io.memoryWrite.lineAddress
  for (word <- 0 until OooCacheContract.LineBytes / 4) {
    io.memoryWriteDataWords(word) :=
      hierarchy.io.memoryWrite.data(word * 32 + 31 downto word * 32)
  }
  io.invalidateBusy := hierarchy.io.invalidateBusy
  io.instructionBarrierMaintenanceReady :=
    hierarchy.io.instructionBarrierMaintenanceReady
  io.instructionBarrierMaintenanceDone := hierarchy.io.instructionBarrierMaintenanceDone
  io.idle := hierarchy.io.idle
}

class OooSharedCacheHierarchySpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: OooSharedCacheHierarchyProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: OooSharedCacheHierarchyProbe): Unit = {
    dut.io.instructionRequestValid #= false
    dut.io.instructionRequest.virtualAddress #= 0
    dut.io.instructionRequest.physicalAddress #= 0
    dut.io.instructionRequest.uncached #= false
    dut.io.instructionKill #= false
    dut.io.dataRequestValid #= false
    dut.io.dataRequest.virtualAddress #= 0
    dut.io.dataRequest.physicalAddress #= 0
    dut.io.dataRequest.isWrite #= false
    dut.io.dataRequest.size #= 2
    dut.io.dataRequest.byteMask #= 0xf
    dut.io.dataRequest.writeData #= 0
    dut.io.dataRequest.uncached #= false
    dut.io.dataRequest.robPointer #= 0
    dut.io.dataRequest.recoveryEpoch #= 0
    dut.io.dataRequest.pdst #= 0
    dut.io.dataRequest.loadQueueIndex #= 0
    dut.io.uncachedInstructionRequestReady #= false
    dut.io.uncachedInstructionResponseValid #= false
    dut.io.uncachedInstructionResponse.virtualAddress #= 0
    dut.io.uncachedInstructionResponse.physicalAddress #= 0
    dut.io.uncachedInstructionResponse.error #= false
    for (lane <- 0 until config.fetchWidth) {
      dut.io.uncachedInstructionResponse.instructions(lane) #= 0
      dut.io.uncachedInstructionResponse.predecode(lane).valid #= false
      dut.io.uncachedInstructionResponse.predecode(lane).branchType #= 1
      dut.io.uncachedInstructionResponse.predecode(lane).target #= 0
      dut.io.uncachedInstructionResponse.predecode(lane).staticTaken #= false
      dut.io.uncachedInstructionResponse.predecode(lane).indirect #= false
    }
    dut.io.uncachedDataRequestReady #= false
    dut.io.uncachedDataResponseValid #= false
    dut.io.uncachedDataResponse.robPointer #= 0
    dut.io.uncachedDataResponse.recoveryEpoch #= 0
    dut.io.uncachedDataResponse.pdst #= 0
    dut.io.uncachedDataResponse.loadQueueIndex #= 0
    dut.io.uncachedDataResponse.data #= 0
    dut.io.uncachedDataResponse.error #= false
    dut.io.memoryReadReady #= false
    dut.io.memoryReadBeatValid #= false
    dut.io.memoryReadBeat.mshrId #= 0
    dut.io.memoryReadBeat.beat #= 0
    dut.io.memoryReadBeat.data #= 0
    dut.io.memoryReadBeat.last #= false
    dut.io.memoryReadBeat.error #= false
    dut.io.memoryWriteReady #= false
    dut.io.invalidate #= false
    dut.io.dataInvalidate #= false
    dut.io.dataWritebackInvalidate #= false
    dut.io.level2Invalidate #= false
    dut.io.barrierDrain #= false
    dut.io.instructionBarrierMaintenanceStart #= false
  }

  private def instructionBeat(firstInstruction: Int, beat: Int): BigInt = {
    val low = BigInt(firstInstruction + beat * 2) & BigInt("ffffffff", 16)
    val high = BigInt(firstInstruction + beat * 2 + 1) & BigInt("ffffffff", 16)
    (high << 32) | low
  }

  test("simultaneous L1I and L1D misses share one L2 refill without cross-routing") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-same-line", 0x4c52) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        var initCycles = 0
        while (dut.io.invalidateBusy.toBoolean && initCycles < config.level2Cache.sets + 16) {
          sample(dut)
          initCycles += 1
        }
        assert(!dut.io.invalidateBusy.toBoolean)
        assert(dut.io.instructionRequestReady.toBoolean)
        assert(dut.io.dataRequestReady.toBoolean)

        dut.io.instructionRequestValid #= true
        dut.io.instructionRequest.virtualAddress #= BigInt("1c000410", 16)
        dut.io.instructionRequest.physicalAddress #= 0x410
        dut.io.dataRequestValid #= true
        dut.io.dataRequest.virtualAddress #= 0x408
        dut.io.dataRequest.physicalAddress #= 0x408
        dut.io.dataRequest.isWrite #= false
        dut.io.dataRequest.robPointer #= 5
        dut.io.dataRequest.pdst #= 9
        sample(dut)
        dut.io.instructionRequestValid #= false
        dut.io.dataRequestValid #= false

        var waitCycles = 0
        while (!dut.io.memoryReadValid.toBoolean && waitCycles < 32) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(dut.io.memoryRead.lineAddress.toBigInt == 0x400)
        dut.io.memoryReadReady #= true
        sample(dut)
        dut.io.memoryReadReady #= false

        var sawInstruction = false
        var sawData = false
        for (beat <- 0 until OooCacheContract.BeatsPerLine) {
          dut.io.memoryReadBeatValid #= true
          dut.io.memoryReadBeat.mshrId #= 0
          dut.io.memoryReadBeat.beat #= beat
          dut.io.memoryReadBeat.data #= instructionBeat(300, beat)
          dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.memoryReadBeatReady.toBoolean)
          sample(dut)
          if (dut.io.instructionResponseValid.toBoolean) {
            assert(!sawInstruction)
            assert(dut.io.instructionResponse.virtualAddress.toBigInt == BigInt("1c000410", 16))
            for (lane <- 0 until config.fetchWidth) {
              assert(dut.io.instructionResponse.instructions(lane).toBigInt == 304 + lane)
            }
            sawInstruction = true
          }
          if (dut.io.dataResponseValid.toBoolean) {
            assert(!sawData)
            assert(dut.io.dataResponse.robPointer.toBigInt == 5)
            assert(dut.io.dataResponse.pdst.toBigInt == 9)
            assert(dut.io.dataResponse.data.toBigInt == 302)
            sawData = true
          }
        }
        dut.io.memoryReadBeatValid #= false

        var drainCycles = 0
        while (!(sawInstruction && sawData) && drainCycles < 64) {
          assert(!dut.io.memoryReadValid.toBoolean)
          assert(!dut.io.memoryWriteValid.toBoolean)
          if (dut.io.instructionResponseValid.toBoolean) {
            assert(!sawInstruction)
            assert(dut.io.instructionResponse.virtualAddress.toBigInt == BigInt("1c000410", 16))
            for (lane <- 0 until config.fetchWidth) {
              assert(dut.io.instructionResponse.instructions(lane).toBigInt == 304 + lane)
            }
            sawInstruction = true
          }
          if (dut.io.dataResponseValid.toBoolean) {
            assert(!sawData)
            assert(dut.io.dataResponse.robPointer.toBigInt == 5)
            assert(dut.io.dataResponse.pdst.toBigInt == 9)
            assert(dut.io.dataResponse.data.toBigInt == 302)
            sawData = true
          }
          sample(dut)
          drainCycles += 1
        }
        assert(sawInstruction)
        assert(sawData)

        dut.io.invalidate #= true
        sample(dut)
        dut.io.invalidate #= false
        var invalidateCycles = 0
        while (dut.io.invalidateBusy.toBoolean && invalidateCycles < config.level2Cache.sets + 16) {
          sample(dut)
          invalidateCycles += 1
        }
        assert(!dut.io.invalidateBusy.toBoolean)

        // I-cache maintenance keeps the private data copy but removes the shared instruction copy.
        dut.io.dataRequestValid #= true
        dut.io.dataRequest.virtualAddress #= 0x408
        dut.io.dataRequest.physicalAddress #= 0x408
        while (!dut.io.dataRequestReady.toBoolean) { sample(dut) }
        sample(dut)
        dut.io.dataRequestValid #= false
        var dataHit = false
        var dataHitCycles = 0
        while (!dataHit && dataHitCycles < 16) {
          assert(!dut.io.memoryReadValid.toBoolean)
          dataHit = dut.io.dataResponseValid.toBoolean
          if (!dataHit) sample(dut)
          dataHitCycles += 1
        }
        assert(dataHit)
        assert(dut.io.dataResponse.data.toBigInt == 302)

        dut.io.instructionRequestValid #= true
        dut.io.instructionRequest.virtualAddress #= BigInt("1c000410", 16)
        dut.io.instructionRequest.physicalAddress #= 0x410
        while (!dut.io.instructionRequestReady.toBoolean) { sample(dut) }
        sample(dut)
        dut.io.instructionRequestValid #= false
        var refillWait = 0
        while (!dut.io.memoryReadValid.toBoolean && refillWait < 32) {
          sample(dut)
          refillWait += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(dut.io.memoryRead.lineAddress.toBigInt == 0x400)
        dut.io.memoryReadReady #= true
        sample(dut)
        dut.io.memoryReadReady #= false
        var instructionRefill = false
        for (beat <- 0 until OooCacheContract.BeatsPerLine) {
          dut.io.memoryReadBeatValid #= true
          dut.io.memoryReadBeat.mshrId #= 0
          dut.io.memoryReadBeat.beat #= beat
          dut.io.memoryReadBeat.data #= instructionBeat(400, beat)
          dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.memoryReadBeatReady.toBoolean)
          sample(dut)
          if (dut.io.instructionResponseValid.toBoolean) {
            assert(!instructionRefill)
            for (lane <- 0 until config.fetchWidth) {
              assert(dut.io.instructionResponse.instructions(lane).toBigInt == 404 + lane)
            }
            instructionRefill = true
          }
        }
        dut.io.memoryReadBeatValid #= false

        var instructionRefillCycles = 0
        while (!instructionRefill && instructionRefillCycles < 64) {
          instructionRefill = dut.io.instructionResponseValid.toBoolean
          if (instructionRefill) {
            for (lane <- 0 until config.fetchWidth) {
              assert(dut.io.instructionResponse.instructions(lane).toBigInt == 404 + lane)
            }
          }
          if (!instructionRefill) sample(dut)
          instructionRefillCycles += 1
        }
        assert(instructionRefill)
      }
  }

  test("uncached instruction and data requests bypass L1 and L2") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-uncached-bypass", 0x4c53) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 8)
        sleep(1)

        dut.io.instructionRequestValid #= true
        dut.io.instructionRequest.virtualAddress #= BigInt("1c00100c", 16)
        dut.io.instructionRequest.physicalAddress #= 0x100c
        dut.io.instructionRequest.uncached #= true
        dut.io.uncachedInstructionRequestReady #= true
        sleep(1)
        assert(dut.io.instructionRequestReady.toBoolean)
        assert(dut.io.uncachedInstructionRequestValid.toBoolean)
        assert(dut.io.uncachedInstructionRequest.physicalAddress.toBigInt == 0x100c)
        assert(!dut.io.memoryReadValid.toBoolean)
        sample(dut)
        dut.io.instructionRequestValid #= false
        dut.io.uncachedInstructionRequestReady #= false

        dut.io.uncachedInstructionResponseValid #= true
        dut.io.uncachedInstructionResponse.virtualAddress #= BigInt("1c00100c", 16)
        dut.io.uncachedInstructionResponse.physicalAddress #= 0x100c
        for (lane <- 0 until config.fetchWidth) {
          dut.io.uncachedInstructionResponse.instructions(lane) #= 0x700 + lane
        }
        sleep(1)
        assert(dut.io.instructionResponseValid.toBoolean)
        assert(dut.io.instructionResponse.instructions(3).toBigInt == 0x703)
        sample(dut)
        dut.io.uncachedInstructionResponseValid #= false

        dut.io.dataRequestValid #= true
        dut.io.dataRequest.virtualAddress #= BigInt("bfe00104", 16)
        dut.io.dataRequest.physicalAddress #= BigInt("1fe00104", 16)
        dut.io.dataRequest.uncached #= true
        dut.io.dataRequest.robPointer #= 9
        dut.io.dataRequest.recoveryEpoch #= 31
        dut.io.dataRequest.pdst #= 13
        dut.io.dataRequest.loadQueueIndex #= 7
        dut.io.uncachedDataRequestReady #= true
        sleep(1)
        assert(dut.io.dataRequestReady.toBoolean)
        assert(dut.io.uncachedDataRequestValid.toBoolean)
        assert(dut.io.uncachedDataRequest.physicalAddress.toBigInt == BigInt("1fe00104", 16))
        assert(!dut.io.memoryReadValid.toBoolean)
        sample(dut)
        dut.io.dataRequestValid #= false
        dut.io.uncachedDataRequestReady #= false

        dut.io.uncachedDataResponseValid #= true
        dut.io.uncachedDataResponse.robPointer #= 9
        dut.io.uncachedDataResponse.recoveryEpoch #= 31
        dut.io.uncachedDataResponse.pdst #= 13
        dut.io.uncachedDataResponse.loadQueueIndex #= 7
        dut.io.uncachedDataResponse.data #= BigInt("89abcdef", 16)
        sleep(1)
        assert(dut.io.dataResponseValid.toBoolean)
        assert(dut.io.dataResponse.recoveryEpoch.toBigInt == 31)
        assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 7)
        assert(dut.io.dataResponse.data.toBigInt == BigInt("89abcdef", 16))
      }
  }

  test("simultaneous cached and uncached data responses are both delivered") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-data-response-collision", 0x4c77) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.level2Cache.sets + 8)
        sleep(1)

        def requestCached(pointer: Int, epoch: Int, pdst: Int): Unit = {
          dut.io.dataRequestValid #= true
          dut.io.dataRequest.virtualAddress #= 0x408
          dut.io.dataRequest.physicalAddress #= 0x408
          dut.io.dataRequest.isWrite #= false
          dut.io.dataRequest.uncached #= false
          dut.io.dataRequest.robPointer #= pointer
          dut.io.dataRequest.recoveryEpoch #= epoch
          dut.io.dataRequest.pdst #= pdst
          dut.io.dataRequest.loadQueueIndex #= (pointer & 0xf)
          var readyWait = 0
          while (!dut.io.dataRequestReady.toBoolean && readyWait < 32) {
            sample(dut)
            readyWait += 1
          }
          assert(dut.io.dataRequestReady.toBoolean)
          sample(dut)
          dut.io.dataRequestValid #= false
        }

        requestCached(pointer = 5, epoch = 41, pdst = 9)
        var readWait = 0
        while (!dut.io.memoryReadValid.toBoolean && readWait < 32) {
          sample(dut)
          readWait += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        dut.io.memoryReadReady #= true
        sample(dut)
        dut.io.memoryReadReady #= false

        var firstResponseSeen = false
        for (beat <- 0 until OooCacheContract.BeatsPerLine) {
          dut.io.memoryReadBeatValid #= true
          dut.io.memoryReadBeat.mshrId #= 0
          dut.io.memoryReadBeat.beat #= beat
          dut.io.memoryReadBeat.data #= instructionBeat(300, beat)
          dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.memoryReadBeatReady.toBoolean)
          sample(dut)
          if (dut.io.dataResponseValid.toBoolean) {
            assert(dut.io.dataResponse.robPointer.toBigInt == 5)
            assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 5)
            firstResponseSeen = true
          }
        }
        dut.io.memoryReadBeatValid #= false
        var responseWait = 0
        while (!firstResponseSeen && responseWait < 32) {
          if (dut.io.dataResponseValid.toBoolean) {
            assert(dut.io.dataResponse.robPointer.toBigInt == 5)
            assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 5)
            firstResponseSeen = true
          } else {
            sample(dut)
          }
          responseWait += 1
        }
        assert(firstResponseSeen)

        requestCached(pointer = 6, epoch = 42, pdst = 10)
        var hitWait = 0
        while (!dut.hierarchy.cachedDataResponseValid.toBoolean && hitWait < 16) {
          sample(dut)
          hitWait += 1
        }
        assert(dut.hierarchy.cachedDataResponseValid.toBoolean)
        assert(dut.io.dataResponse.robPointer.toBigInt == 6)
        assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 6)

        dut.io.uncachedDataResponseValid #= true
        dut.io.uncachedDataResponse.robPointer #= 9
        dut.io.uncachedDataResponse.recoveryEpoch #= 43
        dut.io.uncachedDataResponse.pdst #= 13
        dut.io.uncachedDataResponse.loadQueueIndex #= 7
        dut.io.uncachedDataResponse.data #= BigInt("89abcdef", 16)
        sleep(1)
        assert(dut.io.dataResponseValid.toBoolean)
        assert(dut.io.dataResponse.robPointer.toBigInt == 9)
        assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 7)
        assert(dut.io.dataResponse.data.toBigInt == BigInt("89abcdef", 16))

        dut.clockDomain.waitSampling()
        dut.io.uncachedDataResponseValid #= false
        sleep(1)
        assert(dut.io.dataResponseValid.toBoolean)
        assert(dut.io.dataResponse.robPointer.toBigInt == 6)
        assert(dut.io.dataResponse.recoveryEpoch.toBigInt == 42)
        assert(dut.io.dataResponse.pdst.toBigInt == 10)
        assert(dut.io.dataResponse.loadQueueIndex.toBigInt == 6)
        assert(dut.io.dataResponse.data.toBigInt == 302)
        sample(dut)
        assert(!dut.io.dataResponseValid.toBoolean)
      }
  }

  test("uncached stores write back and invalidate cached aliases before reaching memory") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-uncached-store-alias", 0x4c54) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        var initializationCycles = 0
        while (!dut.io.idle.toBoolean && initializationCycles < 2048) {
          sample(dut)
          initializationCycles += 1
        }
        assert(dut.io.idle.toBoolean)

        val address = BigInt("00102000", 16)
        val oldWord = BigInt("11111111", 16)
        val dirtyWord = BigInt("aaaaaaaa", 16)
        val uncachedWord = BigInt("22222222", 16)
        val beatMask = (BigInt(1) << OooCacheContract.BeatBits) - 1
        var backingLine = oldWord
        for (word <- 1 until OooCacheContract.LineBytes / 4) {
          backingLine |= BigInt(0x300 + word) << (word * 32)
        }

        def requestData(isWrite: Boolean, uncached: Boolean, data: BigInt): Unit = {
          dut.io.dataRequestValid #= true
          dut.io.dataRequest.virtualAddress #= address
          dut.io.dataRequest.physicalAddress #= address
          dut.io.dataRequest.isWrite #= isWrite
          dut.io.dataRequest.writeData #= data
          dut.io.dataRequest.byteMask #= 0xf
          dut.io.dataRequest.uncached #= uncached
          var waitCycles = 0
          while (!dut.io.dataRequestReady.toBoolean && waitCycles < 256) {
            sample(dut)
            waitCycles += 1
          }
          assert(dut.io.dataRequestReady.toBoolean)
          sample(dut)
          dut.io.dataRequestValid #= false
        }

        def serviceRead(line: BigInt): BigInt = {
          var requestWait = 0
          while (!dut.io.memoryReadValid.toBoolean && requestWait < 256) {
            sample(dut)
            requestWait += 1
          }
          assert(dut.io.memoryReadValid.toBoolean)
          assert(dut.io.memoryRead.lineAddress.toBigInt == address)
          val mshrId = dut.io.memoryRead.mshrId.toBigInt
          dut.io.memoryReadReady #= true
          sample(dut)
          dut.io.memoryReadReady #= false

          var response = Option.empty[BigInt]
          for (beat <- 0 until OooCacheContract.BeatsPerLine) {
            dut.io.memoryReadBeatValid #= true
            dut.io.memoryReadBeat.mshrId #= mshrId
            dut.io.memoryReadBeat.beat #= beat
            dut.io.memoryReadBeat.data #=
              (line >> (beat * OooCacheContract.BeatBits)) & beatMask
            dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
            while (!dut.io.memoryReadBeatReady.toBoolean) { sample(dut) }
            sample(dut)
            if (dut.io.dataResponseValid.toBoolean) {
              response = Some(dut.io.dataResponse.data.toBigInt)
            }
          }
          dut.io.memoryReadBeatValid #= false
          var responseWait = 0
          while (response.isEmpty && responseWait < 128) {
            sample(dut)
            if (dut.io.dataResponseValid.toBoolean) {
              response = Some(dut.io.dataResponse.data.toBigInt)
            }
            responseWait += 1
          }
          assert(response.nonEmpty)
          response.get
        }

        requestData(isWrite = false, uncached = false, data = 0)
        assert(serviceRead(backingLine) == oldWord)

        requestData(isWrite = true, uncached = false, data = dirtyWord)
        var dirtyStoreIdleWait = 0
        while (!dut.io.idle.toBoolean && dirtyStoreIdleWait < 128) {
          sample(dut)
          dirtyStoreIdleWait += 1
        }
        assert(dut.io.idle.toBoolean)

        dut.io.uncachedDataRequestReady #= false
        requestData(isWrite = true, uncached = true, data = uncachedWord)
        assert(!dut.io.uncachedDataRequestValid.toBoolean)

        var sawWriteback = false
        var forwardWait = 0
        while (!dut.io.uncachedDataRequestValid.toBoolean && forwardWait < 1024) {
          sleep(1)
          if (dut.io.memoryWriteValid.toBoolean) {
            assert(dut.io.memoryWriteLineAddress.toBigInt == address)
            backingLine = 0
            for (word <- 0 until OooCacheContract.LineBytes / 4) {
              backingLine |= dut.io.memoryWriteDataWords(word).toBigInt << (word * 32)
            }
            sawWriteback = true
            dut.io.memoryWriteReady #= true
          } else {
            dut.io.memoryWriteReady #= false
          }
          sample(dut)
          forwardWait += 1
        }
        dut.io.memoryWriteReady #= false
        assert(dut.io.uncachedDataRequestValid.toBoolean)
        assert(sawWriteback)
        assert((backingLine & BigInt("ffffffff", 16)) == dirtyWord)
        assert(dut.io.uncachedDataRequest.isWrite.toBoolean)
        assert(dut.io.uncachedDataRequest.writeData.toBigInt == uncachedWord)

        dut.io.uncachedDataRequestReady #= true
        sample(dut)
        dut.io.uncachedDataRequestReady #= false
        backingLine = (backingLine & ~BigInt("ffffffff", 16)) | uncachedWord

        dut.io.uncachedDataResponseValid #= true
        dut.io.uncachedDataResponse.robPointer #= dut.io.uncachedDataRequest.robPointer.toBigInt
        dut.io.uncachedDataResponse.recoveryEpoch #=
          dut.io.uncachedDataRequest.recoveryEpoch.toBigInt
        dut.io.uncachedDataResponse.pdst #= dut.io.uncachedDataRequest.pdst.toBigInt
        dut.io.uncachedDataResponse.loadQueueIndex #=
          dut.io.uncachedDataRequest.loadQueueIndex.toBigInt
        sample(dut)
        dut.io.uncachedDataResponseValid #= false

        requestData(isWrite = false, uncached = false, data = 0)
        assert(serviceRead(backingLine) == uncachedWord)
      }
  }

  test("barrier drain ignores a held unaccepted instruction request") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-barrier-held-instruction", 0x4c76) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        var initializationCycles = 0
        while (!dut.io.idle.toBoolean && initializationCycles < 2048) {
          sample(dut)
          initializationCycles += 1
        }
        assert(dut.io.idle.toBoolean)

        // This is the frontend state seen after a DBAR reaches the ROB head:
        // ready is withdrawn, so the next speculative fetch remains valid at
        // the hierarchy boundary until the barrier's PC+4 refetch flushes it.
        dut.io.barrierDrain #= true
        dut.io.instructionRequestValid #= true
        dut.io.instructionRequest.virtualAddress #= BigInt("1c001000", 16)
        dut.io.instructionRequest.physicalAddress #= 0x1000
        for (_ <- 0 until 4) {
          sample(dut)
          assert(!dut.io.instructionRequestReady.toBoolean)
        }
        assert(dut.io.idle.toBoolean)

        // Reopening ingress makes the same held request real work again.  It
        // must be accepted and keep the hierarchy non-idle while its miss is
        // outstanding.
        dut.io.barrierDrain #= false
        sleep(1)
        assert(dut.io.instructionRequestReady.toBoolean)
        sample(dut)
        dut.io.instructionRequestValid #= false
        for (_ <- 0 until 4) {
          sample(dut)
          assert(!dut.io.idle.toBoolean)
        }
        assert(dut.io.memoryReadValid.toBoolean)
      }
  }

  test("IBAR writes back modified code, invalidates L2 and L1I, then refills fresh instructions") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-shared-cache")
      .compile(new OooSharedCacheHierarchyProbe(config))
      .doSim("ooo-shared-cache-ibar-self-modifying-code", 0x4c75) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()

        var initializationCycles = 0
        while (!dut.io.idle.toBoolean && initializationCycles < 2048) {
          sample(dut)
          initializationCycles += 1
        }
        assert(dut.io.idle.toBoolean)

        val oldInstruction = BigInt("11111111", 16)
        val newInstruction = BigInt("22222222", 16)
        var backingLine = oldInstruction
        for (word <- 1 until OooCacheContract.LineBytes / 4) {
          backingLine |= BigInt(0x100 + word) << (word * 32)
        }
        val beatMask = (BigInt(1) << OooCacheContract.BeatBits) - 1

        def requestInstruction(): Unit = {
          dut.io.instructionRequestValid #= true
          dut.io.instructionRequest.virtualAddress #= BigInt("1c001000", 16)
          dut.io.instructionRequest.physicalAddress #= 0x1000
          var readyWait = 0
          var accepted = false
          while (!accepted && readyWait < 32) {
            sleep(1)
            accepted = dut.io.instructionRequestReady.toBoolean
            sample(dut)
            readyWait += 1
          }
          assert(accepted)
          dut.io.instructionRequestValid #= false
        }

        def serviceMemoryRead(line: BigInt): Option[BigInt] = {
          var observedInstruction = Option.empty[BigInt]
          var requestWait = 0
          while (!dut.io.memoryReadValid.toBoolean && requestWait < 64) {
            sample(dut)
            requestWait += 1
          }
          assert(dut.io.memoryReadValid.toBoolean)
          assert(dut.io.memoryRead.lineAddress.toBigInt == 0x1000)
          val mshrId = dut.io.memoryRead.mshrId.toBigInt
          dut.io.memoryReadReady #= true
          sample(dut)
          dut.io.memoryReadReady #= false
          for (beat <- 0 until OooCacheContract.BeatsPerLine) {
            dut.io.memoryReadBeatValid #= true
            dut.io.memoryReadBeat.mshrId #= mshrId
            dut.io.memoryReadBeat.beat #= beat
            dut.io.memoryReadBeat.data #= (line >> (beat * OooCacheContract.BeatBits)) & beatMask
            dut.io.memoryReadBeat.last #= beat == OooCacheContract.BeatsPerLine - 1
            while (!dut.io.memoryReadBeatReady.toBoolean) { sample(dut) }
            sample(dut)
            if (dut.io.instructionResponseValid.toBoolean) {
              observedInstruction = Some(dut.io.instructionResponse.instructions(0).toBigInt)
            }
          }
          dut.io.memoryReadBeatValid #= false
          observedInstruction
        }

        requestInstruction()
        var oldInstructionObserved = serviceMemoryRead(backingLine)
        var oldResponseWait = 0
        while (oldInstructionObserved.isEmpty && oldResponseWait < 64) {
          sample(dut)
          if (dut.io.instructionResponseValid.toBoolean) {
            oldInstructionObserved = Some(dut.io.instructionResponse.instructions(0).toBigInt)
          }
          oldResponseWait += 1
        }
        assert(oldInstructionObserved.contains(oldInstruction))

        dut.io.dataRequestValid #= true
        dut.io.dataRequest.virtualAddress #= 0x1000
        dut.io.dataRequest.physicalAddress #= 0x1000
        dut.io.dataRequest.isWrite #= true
        dut.io.dataRequest.writeData #= newInstruction
        dut.io.dataRequest.byteMask #= 0xf
        var storeReadyWait = 0
        while (!dut.io.dataRequestReady.toBoolean && storeReadyWait < 64) {
          sample(dut)
          storeReadyWait += 1
        }
        assert(dut.io.dataRequestReady.toBoolean)
        sample(dut)
        dut.io.dataRequestValid #= false

        var storeIdleWait = 0
        while (!dut.io.idle.toBoolean && storeIdleWait < 256) {
          sample(dut)
          storeIdleWait += 1
        }
        assert(dut.io.idle.toBoolean)

        dut.io.barrierDrain #= true
        sleep(1)
        assert(dut.io.instructionBarrierMaintenanceReady.toBoolean)
        dut.io.instructionBarrierMaintenanceStart #= true
        sample(dut)
        dut.io.instructionBarrierMaintenanceStart #= false

        var sawWriteback = false
        var maintenanceCycles = 0
        while (!dut.io.instructionBarrierMaintenanceDone.toBoolean && maintenanceCycles < 5000) {
          sleep(1)
          if (dut.io.memoryWriteValid.toBoolean) {
            assert(dut.io.memoryWriteLineAddress.toBigInt == 0x1000)
            backingLine = 0
            for (word <- 0 until OooCacheContract.LineBytes / 4) {
              backingLine |= dut.io.memoryWriteDataWords(word).toBigInt << (word * 32)
            }
            sawWriteback = true
            dut.io.memoryWriteReady #= true
          } else {
            dut.io.memoryWriteReady #= false
          }
          sample(dut)
          maintenanceCycles += 1
        }
        assert(dut.io.instructionBarrierMaintenanceDone.toBoolean)
        assert(sawWriteback)
        assert((backingLine & BigInt("ffffffff", 16)) == newInstruction)
        sample(dut)
        dut.io.memoryWriteReady #= false
        dut.io.barrierDrain #= false

        var postMaintenanceIdleWait = 0
        while (!dut.io.idle.toBoolean && postMaintenanceIdleWait < 64) {
          sample(dut)
          postMaintenanceIdleWait += 1
        }
        assert(dut.io.idle.toBoolean)

        requestInstruction()
        var freshInstructionObserved = serviceMemoryRead(backingLine)
        var freshResponseWait = 0
        while (freshInstructionObserved.isEmpty && freshResponseWait < 64) {
          sample(dut)
          if (dut.io.instructionResponseValid.toBoolean) {
            freshInstructionObserved = Some(dut.io.instructionResponse.instructions(0).toBigInt)
          }
          freshResponseWait += 1
        }
        assert(freshInstructionObserved.contains(newInstruction))
      }
  }
}
