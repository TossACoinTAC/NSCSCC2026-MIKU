package miku.memory

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class DataCacheHierarchyProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(CacheRequest(config))
    val requestReady = out Bool ()
    val responseValid = out Bool ()
    val response = out(CacheResponse(config))
    val memoryReadValid = out Bool ()
    val memoryRead = out(LineReadRequest(config))
    val memoryReadReady = in Bool ()
    val memoryReadBeatValid = in Bool ()
    val memoryReadBeat = in(LineReadBeat(config))
    val memoryReadBeatReady = out Bool ()
    val memoryWriteValid = out Bool ()
    val memoryWrite = out(LineWriteRequest(config))
    val memoryWriteReady = in Bool ()
    val invalidate = in Bool ()
    val invalidateBusy = out Bool ()
  }
  noIoPrefix()

  val hierarchy = new DataCacheHierarchy(config)
  hierarchy.io.requestValid := io.requestValid
  hierarchy.io.request := io.request
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

  io.requestReady := hierarchy.io.requestReady
  io.responseValid := hierarchy.io.responseValid
  io.response := hierarchy.io.response
  io.memoryReadValid := hierarchy.io.memoryReadValid
  io.memoryRead := hierarchy.io.memoryRead
  io.memoryReadBeatReady := hierarchy.io.memoryReadBeatReady
  io.memoryWriteValid := hierarchy.io.memoryWriteValid
  io.memoryWrite := hierarchy.io.memoryWrite
  io.invalidateBusy := hierarchy.io.invalidateBusy
}

class DataCacheHierarchySpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: DataCacheHierarchyProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: DataCacheHierarchyProbe): Unit = {
    dut.io.requestValid #= false
    dut.io.request.virtualAddress #= 0
    dut.io.request.physicalAddress #= 0
    dut.io.request.isWrite #= false
    dut.io.request.size #= 2
    dut.io.request.byteMask #= 0xf
    dut.io.request.writeData #= 0
    dut.io.request.uncached #= false
    dut.io.request.robPointer #= 0
    dut.io.request.recoveryEpoch #= 0
    dut.io.request.pdst #= 0
    dut.io.request.loadQueueIndex #= 0
    dut.io.memoryReadReady #= false
    dut.io.memoryReadBeatValid #= false
    dut.io.memoryReadBeat.mshrId #= 0
    dut.io.memoryReadBeat.beat #= 0
    dut.io.memoryReadBeat.data #= 0
    dut.io.memoryReadBeat.last #= false
    dut.io.memoryReadBeat.error #= false
    dut.io.memoryWriteReady #= false
    dut.io.invalidate #= false
  }

  private def waitForInitialization(dut: DataCacheHierarchyProbe): Unit = {
    var cycles = 0
    while (dut.io.invalidateBusy.toBoolean && cycles < config.level2Cache.sets + 16) {
      sample(dut)
      cycles += 1
    }
    assert(!dut.io.invalidateBusy.toBoolean)
    assert(dut.io.requestReady.toBoolean)
  }

  private def acceptRequest(
      dut: DataCacheHierarchyProbe,
      address: BigInt,
      isWrite: Boolean,
      data: BigInt,
      mask: BigInt,
      robPointer: BigInt,
      pdst: BigInt,
      recoveryEpoch: BigInt = 0
  ): Unit = {
    dut.io.requestValid #= false
    dut.io.request.virtualAddress #= address
    dut.io.request.physicalAddress #= address
    dut.io.request.isWrite #= isWrite
    dut.io.request.size #= 2
    dut.io.request.byteMask #= mask
    dut.io.request.writeData #= data
    dut.io.request.uncached #= false
    dut.io.request.robPointer #= robPointer
    dut.io.request.recoveryEpoch #= recoveryEpoch
    dut.io.request.pdst #= pdst
    dut.io.request.loadQueueIndex #= 0
    sleep(1)

    var cycles = 0
    while (!dut.io.requestReady.toBoolean && cycles < 40) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.requestReady.toBoolean)
    dut.io.requestValid #= true
    sleep(1)
    withClue(s"request rob=$robPointer address=0x${address.toString(16)}: ") {
      assert(dut.io.requestReady.toBoolean)
    }
    sample(dut)
    dut.io.requestValid #= false
  }

  private def serviceMemoryRefill(
      dut: DataCacheHierarchyProbe,
      expectedAddress: BigInt,
      beatData: Int => BigInt
  ): (BigInt, BigInt, BigInt, Boolean) = {
    var response = Option.empty[(BigInt, BigInt, BigInt, Boolean)]
    def captureResponse(): Unit = {
      if (dut.io.responseValid.toBoolean) {
        assert(response.isEmpty)
        response = Some(
          (
            dut.io.response.data.toBigInt,
            dut.io.response.robPointer.toBigInt,
            dut.io.response.pdst.toBigInt,
            dut.io.response.error.toBoolean
          )
        )
      }
    }

    var cycles = 0
    while (!dut.io.memoryReadValid.toBoolean && cycles < 30) {
      sample(dut)
      captureResponse()
      cycles += 1
    }
    assert(dut.io.memoryReadValid.toBoolean)
    assert(dut.io.memoryRead.lineAddress.toBigInt == expectedAddress)
    val memoryMshrId = dut.io.memoryRead.mshrId.toBigInt
    assert(dut.io.memoryRead.criticalBeat.toBigInt == 0)

    for (_ <- 0 until 2) {
      sample(dut)
      captureResponse()
      assert(dut.io.memoryReadValid.toBoolean)
      assert(dut.io.memoryRead.lineAddress.toBigInt == expectedAddress)
    }

    dut.io.memoryReadReady #= true
    sample(dut)
    captureResponse()
    dut.io.memoryReadReady #= false

    for (beat <- 0 until CacheContract.BeatsPerLine) {
      dut.io.memoryReadBeatValid #= true
      dut.io.memoryReadBeat.mshrId #= memoryMshrId
      dut.io.memoryReadBeat.beat #= beat
      dut.io.memoryReadBeat.data #= beatData(beat)
      dut.io.memoryReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
      dut.io.memoryReadBeat.error #= false
      sleep(1)
      assert(dut.io.memoryReadBeatReady.toBoolean)
      sample(dut)
      captureResponse()
    }
    dut.io.memoryReadBeatValid #= false

    var responseCycles = 0
    while (response.isEmpty && responseCycles < 40) {
      sample(dut)
      captureResponse()
      responseCycles += 1
    }
    assert(response.nonEmpty)

    var installCycles = 0
    while (!dut.io.requestReady.toBoolean && installCycles < 40) {
      sample(dut)
      captureResponse()
      installCycles += 1
    }
    assert(dut.io.requestReady.toBoolean)
    response.get
  }

  private def expectResponse(
      dut: DataCacheHierarchyProbe,
      data: BigInt,
      robPointer: BigInt,
      pdst: BigInt,
      forbidMemoryRead: Boolean = false
  ): Unit = {
    var cycles = 0
    while (!dut.io.responseValid.toBoolean && cycles < 40) {
      if (forbidMemoryRead) assert(!dut.io.memoryReadValid.toBoolean)
      sample(dut)
      cycles += 1
    }
    assert(dut.io.responseValid.toBoolean)
    assert(dut.io.response.data.toBigInt == data)
    assert(dut.io.response.robPointer.toBigInt == robPointer)
    assert(dut.io.response.pdst.toBigInt == pdst)
    assert(!dut.io.response.error.toBoolean)
    if (forbidMemoryRead) assert(!dut.io.memoryReadValid.toBoolean)
    sample(dut)
  }

  private def loadFromMemory(
      dut: DataCacheHierarchyProbe,
      address: BigInt,
      base: BigInt,
      robPointer: BigInt
  ): Unit = {
    acceptRequest(dut, address, isWrite = false, 0, 0xf, robPointer, pdst = 7)
    val response = serviceMemoryRefill(
      dut,
      address & ~BigInt(CacheContract.LineBytes - 1),
      beat => base + beat
    )
    assert(response._1 == (base & BigInt("ffffffff", 16)))
    assert(response._2 == robPointer)
    assert(response._3 == 7)
    assert(!response._4)
  }

  test("L1D and L2 form a coherent 64-byte writeback hierarchy") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-data-hierarchy")
      .compile(new DataCacheHierarchyProbe(config))
      .doSim("ooo-data-hierarchy-writeback", 0x4c34) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        assert(dut.io.invalidateBusy.toBoolean)
        waitForInitialization(dut)

        val sameSetStride = config.dataCache.sets * config.dataCache.lineBytes
        val addressA = BigInt(0x0100)
        val addressB = addressA + sameSetStride
        val addressC = addressB + sameSetStride
        val addressD = addressC + sameSetStride
        val lineA = BigInt("1111000000000000", 16)
        loadFromMemory(dut, addressA, lineA, robPointer = 1)

        acceptRequest(dut, addressA, isWrite = false, 0, 0xf, robPointer = 2, pdst = 8)
        expectResponse(dut, 0, robPointer = 2, pdst = 8, forbidMemoryRead = true)

        acceptRequest(
          dut,
          addressA,
          isWrite = true,
          data = BigInt("deadbeef", 16),
          mask = 0xf,
          robPointer = 3,
          pdst = 0
        )
        sample(dut)

        acceptRequest(dut, addressA, isWrite = false, 0, 0xf, robPointer = 4, pdst = 9)
        expectResponse(
          dut,
          BigInt("deadbeef", 16),
          robPointer = 4,
          pdst = 9,
          forbidMemoryRead = true
        )

        loadFromMemory(dut, addressB, BigInt("2222000000000000", 16), robPointer = 5)
        acceptRequest(dut, addressC, isWrite = false, 0, 0xf, robPointer = 6, pdst = 7)
        var refillWaitCycles = 0
        while (!dut.io.memoryReadValid.toBoolean && refillWaitCycles < 30) {
          assert(!dut.io.memoryWriteValid.toBoolean)
          sample(dut)
          refillWaitCycles += 1
        }
        assert(dut.io.memoryReadValid.toBoolean)
        assert(!dut.io.memoryWriteValid.toBoolean)
        val refillResponse =
          serviceMemoryRefill(dut, addressC, beat => BigInt("3333000000000000", 16) + beat)
        assert(refillResponse._1 == 0)
        assert(refillResponse._2 == 6)
        assert(refillResponse._3 == 7)
        assert(!refillResponse._4)
        loadFromMemory(dut, addressD, BigInt("4444000000000000", 16), robPointer = 7)

        acceptRequest(dut, addressA, isWrite = false, 0, 0xf, robPointer = 8, pdst = 10)
        expectResponse(
          dut,
          BigInt("deadbeef", 16),
          robPointer = 8,
          pdst = 10,
          forbidMemoryRead = true
        )
        assert(!dut.io.memoryWriteValid.toBoolean)
      }
  }
}
