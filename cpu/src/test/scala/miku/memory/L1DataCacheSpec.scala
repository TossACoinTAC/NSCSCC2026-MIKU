package miku.memory

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class L1DataCacheProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(CacheRequest(config))
    val requestReady = out Bool ()
    val responseValid = out Bool ()
    val response = out(CacheResponse(config))
    val lineReadValid = out Bool ()
    val lineRead = out(LineReadRequest(config))
    val lineReadReady = in Bool ()
    val lineReadBeatValid = in Bool ()
    val lineReadBeat = in(LineReadBeat(config))
    val lineReadBeatReady = out Bool ()
    val lineWriteValid = out Bool ()
    val lineWrite = out(LineWriteRequest(config))
    val lineWriteReady = in Bool ()
    val lineWriteResponseError = in Bool ()
    val invalidate = in Bool ()
    val maintenanceValid = in Bool ()
    val maintenanceRequest = in(CacheMaintenanceRequest(config))
    val maintenanceReady = out Bool ()
    val maintenanceDone = out Bool ()
    val invalidateBusy = out Bool ()
  }
  noIoPrefix()

  val cache = new L1DataCache(config)
  cache.io.requestValid := io.requestValid
  cache.io.request := io.request
  cache.io.lineReadReady := io.lineReadReady
  cache.io.lineReadBeatValid := io.lineReadBeatValid
  cache.io.lineReadBeat := io.lineReadBeat
  cache.io.lineWriteReady := io.lineWriteReady
  val lineWriteFire = cache.io.lineWriteValid && io.lineWriteReady
  val lineWriteResponseValid = RegNext(lineWriteFire) init (False)
  val lineWriteResponseMshrId = Reg(UInt(log2Up(config.mshrEntries) bits))
  val lineWriteResponseError = Reg(Bool())
  when(lineWriteFire) {
    lineWriteResponseMshrId := cache.io.lineWrite.mshrId
    lineWriteResponseError := io.lineWriteResponseError
  }
  cache.io.lineWriteResponseValid := lineWriteResponseValid
  cache.io.lineWriteResponse.mshrId := lineWriteResponseMshrId
  cache.io.lineWriteResponse.error := lineWriteResponseError
  cache.io.invalidate := io.invalidate
  cache.io.writebackInvalidate := False
  cache.io.maintenanceRequest.valid := io.maintenanceValid
  cache.io.maintenanceRequest.payload := io.maintenanceRequest

  io.requestReady := cache.io.requestReady
  io.responseValid := cache.io.responseValid
  io.response := cache.io.response
  io.lineReadValid := cache.io.lineReadValid
  io.lineRead := cache.io.lineRead
  io.lineReadBeatReady := cache.io.lineReadBeatReady
  io.lineWriteValid := cache.io.lineWriteValid
  io.lineWrite := cache.io.lineWrite
  io.invalidateBusy := cache.io.invalidateBusy
  io.maintenanceReady := cache.io.maintenanceRequest.ready
  io.maintenanceDone := cache.io.maintenanceDone
}

class L1DataCacheSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def clearInputs(dut: L1DataCacheProbe): Unit = {
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
    dut.io.lineReadReady #= false
    dut.io.lineReadBeatValid #= false
    dut.io.lineReadBeat.mshrId #= 0
    dut.io.lineReadBeat.beat #= 0
    dut.io.lineReadBeat.data #= 0
    dut.io.lineReadBeat.last #= false
    dut.io.lineReadBeat.error #= false
    dut.io.lineWriteReady #= false
    dut.io.lineWriteResponseError #= false
    dut.io.invalidate #= false
    dut.io.maintenanceValid #= false
    dut.io.maintenanceRequest.code #= 0
    dut.io.maintenanceRequest.virtualAddress #= 0
    dut.io.maintenanceRequest.physicalAddress #= 0
    dut.io.maintenanceRequest.robPointer #= 0
    dut.io.maintenanceRequest.recoveryEpoch #= 0
  }

  private def sample(dut: L1DataCacheProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def setRequest(
      dut: L1DataCacheProbe,
      address: BigInt,
      isWrite: Boolean,
      data: BigInt,
      mask: BigInt,
      robPointer: BigInt,
      pdst: BigInt,
      recoveryEpoch: BigInt = 0,
      loadQueueIndex: Int = 0
  ): Unit = {
    dut.io.requestValid #= true
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
    dut.io.request.loadQueueIndex #= loadQueueIndex
  }

  private def refillLine(
      dut: L1DataCacheProbe,
      expectedAddress: BigInt,
      beatData: Int => BigInt
  ): (BigInt, BigInt, BigInt, BigInt) = {
    var response = Option.empty[(BigInt, BigInt, BigInt, BigInt)]
    def captureResponse(): Unit = {
      if (dut.io.responseValid.toBoolean) {
        assert(response.isEmpty)
        response = Some(
          (
            dut.io.response.robPointer.toBigInt,
            dut.io.response.pdst.toBigInt,
            dut.io.response.data.toBigInt,
            dut.io.response.loadQueueIndex.toBigInt
          )
        )
      }
    }

    dut.io.lineReadReady #= false
    var waitCycles = 0
    while (!dut.io.lineReadValid.toBoolean && waitCycles < 6) {
      sample(dut)
      captureResponse()
      waitCycles += 1
    }
    assert(dut.io.lineReadValid.toBoolean)
    val address = dut.io.lineRead.lineAddress.toBigInt
    assert(address == expectedAddress)
    dut.io.lineReadReady #= true
    sleep(1)
    assert(dut.io.lineReadValid.toBoolean)
    sample(dut)
    captureResponse()
    dut.io.lineReadReady #= false

    for (beat <- 0 until CacheContract.BeatsPerLine) {
      dut.io.lineReadBeatValid #= true
      dut.io.lineReadBeat.mshrId #= 0
      dut.io.lineReadBeat.beat #= beat
      dut.io.lineReadBeat.data #= beatData(beat)
      dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
      dut.io.lineReadBeat.error #= false
      sleep(1)
      assert(dut.io.lineReadBeatReady.toBoolean)
      sample(dut)
      captureResponse()
    }
    dut.io.lineReadBeatValid #= false
    var responseWait = 0
    while (response.isEmpty && responseWait < 4) {
      sample(dut)
      captureResponse()
      responseWait += 1
    }
    assert(response.nonEmpty)

    var installWait = 0
    while (!dut.io.requestReady.toBoolean && installWait < 4) {
      sample(dut)
      captureResponse()
      installWait += 1
    }
    assert(dut.io.requestReady.toBoolean)
    response.get
  }

  private def firstMergedWaiterResponse(ageOrdered: Boolean): BigInt = {
    val arbitrationConfig = config.copy(enableAgeOrderedL1DWaiterResponse = ageOrdered)
    var firstResponsePdst = BigInt(-1)
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          s"/sim-workspace-ooo-l1d-waiter-age-$ageOrdered"
      )
      .compile(new L1DataCacheProbe(arbitrationConfig))
      .doSim(s"ooo-l1d-waiter-age-$ageOrdered", if (ageOrdered) 0x4c62 else 0x4c63) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(arbitrationConfig.dataCache.sets + 8)
        sleep(1)

        def issue(address: BigInt, robPointer: Int, pdst: Int): Unit = {
          setRequest(
            dut,
            address,
            isWrite = false,
            data = 0,
            mask = 0xf,
            robPointer = robPointer,
            pdst = pdst
          )
          var waitCycles = 0
          while (!dut.io.requestReady.toBoolean && waitCycles < 12) {
            sample(dut)
            waitCycles += 1
          }
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          dut.io.requestValid #= false
        }

        def acceptRead(expectedAddress: BigInt): Int = {
          var waitCycles = 0
          while (!dut.io.lineReadValid.toBoolean && waitCycles < 12) {
            sample(dut)
            waitCycles += 1
          }
          assert(dut.io.lineReadValid.toBoolean)
          assert(dut.io.lineRead.lineAddress.toBigInt == expectedAddress)
          val mshrId = dut.io.lineRead.mshrId.toInt
          dut.io.lineReadReady #= true
          sample(dut)
          dut.io.lineReadReady #= false
          mshrId
        }

        var dummySeen = false
        def captureDummyResponse(): Unit = {
          if (dut.io.responseValid.toBoolean && dut.io.response.pdst.toInt == 5) dummySeen = true
        }

        def sendBeat(mshrId: Int, beat: Int, data: BigInt, last: Boolean): Unit = {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= mshrId
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= data
          dut.io.lineReadBeat.last #= last
          dut.io.lineReadBeat.error #= false
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
          captureDummyResponse()
          dut.io.lineReadBeatValid #= false
        }

        // The dummy miss owns waiter slot 0 while the older measured load is allocated in slot 1.
        issue(address = 0x100, robPointer = 5, pdst = 5)
        val dummyMshrId = acceptRead(expectedAddress = 0x100)
        issue(address = 0x200, robPointer = 10, pdst = 10)
        val measuredMshrId = acceptRead(expectedAddress = 0x200)

        // Complete the dummy line, freeing slot 0 without completing the measured line.
        for (beat <- 0 until CacheContract.BeatsPerLine) {
          sendBeat(
            dummyMshrId,
            beat,
            BigInt(0x1000 + beat),
            last = beat == CacheContract.BeatsPerLine - 1
          )
        }
        var settleCycles = 0
        while ((!dummySeen || !dut.io.requestReady.toBoolean) && settleCycles < 12) {
          captureDummyResponse()
          sample(dut)
          settleCycles += 1
        }
        assert(dummySeen)

        // A younger load now reuses slot 0 and merges into the older slot-1 miss.  Their common
        // critical beat makes both waiters ready on the same edge.
        issue(address = 0x200, robPointer = 12, pdst = 12)
        sendBeat(measuredMshrId, beat = 0, data = BigInt("1122334455667788", 16), last = false)
        var responseWait = 0
        while (!dut.io.responseValid.toBoolean && responseWait < 4) {
          sample(dut)
          responseWait += 1
        }
        assert(dut.io.responseValid.toBoolean)
        firstResponsePdst = dut.io.response.pdst.toBigInt
      }
    firstResponsePdst
  }

  test("L1D returns the oldest same-epoch ready refill waiter") {
    assert(firstMergedWaiterResponse(ageOrdered = true) == 10)
  }

  test("L1D legacy refill arbitration exposes physical-slot priority") {
    assert(firstMergedWaiterResponse(ageOrdered = false) == 12)
  }

  test("L1D preserves a four-bit load queue identity through a miss") {
    val expandedConfig = config.copy(loadQueueEntries = 16)
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d-ldq16")
      .compile(new L1DataCacheProbe(expandedConfig))
      .doSim("ooo-l1d-ldq16-response-identity", 0x4c61) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(expandedConfig.dataCache.sets + 8)
        sleep(1)

        setRequest(
          dut,
          0x10c,
          isWrite = false,
          data = 0,
          mask = 0xf,
          robPointer = 3,
          pdst = 9,
          loadQueueIndex = 15
        )
        sample(dut)
        dut.io.requestValid #= false
        val response = refillLine(
          dut,
          expectedAddress = 0x100,
          beat => if (beat == 1) BigInt("1122334455667788", 16) else BigInt(beat + 1)
        )
        assert(response._1 == 3)
        assert(response._2 == 9)
        assert(response._3 == BigInt("11223344", 16))
        assert(response._4 == 15)
      }
  }

  private def maintain(
      dut: L1DataCacheProbe,
      code: Int,
      virtualAddress: BigInt,
      physicalAddress: BigInt,
      expectedWritebackAddress: Option[BigInt]
  ): Unit = {
    var cycles = 0
    while (!dut.io.maintenanceReady.toBoolean && cycles < 80) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.maintenanceReady.toBoolean)
    dut.io.maintenanceValid #= true
    dut.io.maintenanceRequest.code #= code
    dut.io.maintenanceRequest.virtualAddress #= virtualAddress
    dut.io.maintenanceRequest.physicalAddress #= physicalAddress
    sample(dut)
    dut.io.maintenanceValid #= false

    expectedWritebackAddress.foreach { expected =>
      cycles = 0
      while (!dut.io.lineWriteValid.toBoolean && cycles < 16) {
        sample(dut)
        cycles += 1
      }
      assert(dut.io.lineWriteValid.toBoolean)
      assert(dut.io.lineWrite.lineAddress.toBigInt == expected)
      val heldData = dut.io.lineWrite.data.toBigInt
      sample(dut)
      assert(dut.io.lineWriteValid.toBoolean)
      assert(dut.io.lineWrite.data.toBigInt == heldData)
      dut.io.lineWriteReady #= true
      sample(dut)
      dut.io.lineWriteReady #= false
    }

    cycles = 0
    while (!dut.io.maintenanceDone.toBoolean && cycles < 16) {
      if (expectedWritebackAddress.isEmpty) assert(!dut.io.lineWriteValid.toBoolean)
      sample(dut)
      cycles += 1
    }
    assert(dut.io.maintenanceDone.toBoolean)
    sample(dut)
  }

  test("L1D invalidates, refills eight beats, hits, and merges byte stores") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-refill-hit-merge", 0x4c31) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        assert(dut.io.invalidateBusy.toBoolean)
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)
        assert(!dut.io.invalidateBusy.toBoolean)
        assert(dut.io.requestReady.toBoolean)

        setRequest(dut, 0x10c, isWrite = false, data = 0, mask = 0xf, robPointer = 3, pdst = 9)
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        assert(!dut.io.lineReadValid.toBoolean)

        val refillResponse = refillLine(
          dut,
          expectedAddress = 0x100,
          beat => if (beat == 1) BigInt("1122334455667788", 16) else BigInt(beat + 1)
        )
        assert(refillResponse._1 == 3)
        assert(refillResponse._2 == 9)
        assert(refillResponse._3 == BigInt("11223344", 16))
        sample(dut)
        assert(!dut.io.responseValid.toBoolean)

        setRequest(dut, 0x10c, isWrite = false, data = 0, mask = 0xf, robPointer = 4, pdst = 10)
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(!dut.io.lineReadValid.toBoolean)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.data.toBigInt == BigInt("11223344", 16))
        sample(dut)

        setRequest(
          dut,
          0x10c,
          isWrite = true,
          data = BigInt("aabbccdd", 16),
          mask = 0x5,
          robPointer = 5,
          pdst = 0
        )
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(!dut.io.responseValid.toBoolean)

        setRequest(dut, 0x10c, isWrite = false, data = 0, mask = 0xf, robPointer = 6, pdst = 11)
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.data.toBigInt == BigInt("11bb33dd", 16))
      }
  }

  test("L1D returns a load as soon as its refill beat arrives") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-critical-beat-response", 0x4c38) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        setRequest(
          dut,
          0x12c,
          isWrite = false,
          data = 0,
          mask = 0xf,
          robPointer = 7,
          pdst = 12,
          recoveryEpoch = 11
        )
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        assert(dut.io.lineRead.lineAddress.toBigInt == 0x100)
        assert(dut.io.lineRead.criticalBeat.toBigInt == 5)
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        dut.io.lineReadBeatValid #= true
        dut.io.lineReadBeat.mshrId #= 0
        dut.io.lineReadBeat.beat #= 5
        dut.io.lineReadBeat.data #= BigInt("1122334455667788", 16)
        dut.io.lineReadBeat.last #= false
        sleep(1)
        assert(dut.io.lineReadBeatReady.toBoolean)
        sample(dut)
        dut.io.lineReadBeatValid #= false

        var responseWait = 0
        while (!dut.io.responseValid.toBoolean && responseWait < 3) {
          sample(dut)
          responseWait += 1
        }
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.robPointer.toBigInt == 7)
        assert(dut.io.response.recoveryEpoch.toBigInt == 11)
        assert(dut.io.response.pdst.toBigInt == 12)
        assert(dut.io.response.data.toBigInt == BigInt("11223344", 16))
        sample(dut)

        for (beat <- Seq(0, 1, 2, 3, 4, 6, 7)) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= BigInt(0x80 + beat)
          dut.io.lineReadBeat.last #= beat == 7
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
          assert(!dut.io.responseValid.toBoolean)
        }
        dut.io.lineReadBeatValid #= false
        dut.clockDomain.waitSampling(3)

        setRequest(dut, 0x12c, isWrite = false, data = 0, mask = 0xf, robPointer = 8, pdst = 13)
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.robPointer.toBigInt == 8)
        assert(dut.io.response.data.toBigInt == BigInt("11223344", 16))
      }
  }

  test("L1D readies a merged load accepted with its refill beat") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-merge-refill-same-cycle", 0x4c3b) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        setRequest(dut, 0x100, isWrite = false, 0, 0xf, robPointer = 1, pdst = 8)
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) sample(dut)
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        setRequest(dut, 0x108, isWrite = false, 0, 0xf, robPointer = 2, pdst = 9)
        dut.io.lineReadBeatValid #= true
        dut.io.lineReadBeat.mshrId #= 0
        dut.io.lineReadBeat.beat #= 1
        dut.io.lineReadBeat.data #= BigInt("1122334455667788", 16)
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        assert(dut.io.lineReadBeatReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        dut.io.lineReadBeatValid #= false

        var waitCycles = 0
        while (!dut.io.responseValid.toBoolean && waitCycles < 3) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.robPointer.toBigInt == 2)
        assert(dut.io.response.pdst.toBigInt == 9)
        assert(dut.io.response.data.toBigInt == BigInt("55667788", 16))
      }
  }

  test("L1D line read request remains stable until lower-level acceptance") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-read-backpressure", 0x4c32) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)

        setRequest(dut, 0x240, isWrite = false, data = 0, mask = 0xf, robPointer = 1, pdst = 2)
        sample(dut)
        dut.io.requestValid #= false
        dut.clockDomain.waitSampling(2)
        sleep(1)
        assert(dut.io.lineReadValid.toBoolean)
        val heldAddress = dut.io.lineRead.lineAddress.toBigInt
        for (_ <- 0 until 3) {
          sample(dut)
          assert(dut.io.lineReadValid.toBoolean)
          assert(dut.io.lineRead.lineAddress.toBigInt == heldAddress)
          assert(!dut.io.lineWriteValid.toBoolean)
        }
      }
  }

  test("L1D allocates all four miss identities under lower-level backpressure") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-four-mshr-allocation", 0x4c34) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        val addresses = Seq(BigInt(0x100), BigInt(0x180), BigInt(0x200), BigInt(0x280))
        for ((address, index) <- addresses.zipWithIndex) {
          setRequest(
            dut,
            address,
            isWrite = false,
            data = 0,
            mask = 0xf,
            robPointer = index,
            pdst = index + 1
          )
          var wait = 0
          while (!dut.io.requestReady.toBoolean && wait < 8) {
            sample(dut)
            wait += 1
          }
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          dut.io.requestValid #= false
          sample(dut)
        }

        setRequest(dut, 0x300, isWrite = false, data = 0, mask = 0xf, robPointer = 4, pdst = 5)
        for (_ <- 0 until 3) sample(dut)
        assert(!dut.io.requestReady.toBoolean)
        dut.io.requestValid #= false

        for ((address, id) <- addresses.zipWithIndex) {
          var wait = 0
          while (!dut.io.lineReadValid.toBoolean && wait < 8) {
            sample(dut)
            wait += 1
          }
          assert(dut.io.lineReadValid.toBoolean)
          assert(dut.io.lineRead.lineAddress.toBigInt == address)
          assert(dut.io.lineRead.mshrId.toBigInt == id)
          dut.io.lineReadReady #= true
          sample(dut)
          dut.io.lineReadReady #= false
        }
      }
  }

  test("L1D routes interleaved refills and returns every merged load") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-interleaved-refill-merge", 0x4c35) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        def acceptRequest(address: BigInt, pointer: Int, pdst: Int): Unit = {
          setRequest(
            dut,
            address,
            isWrite = false,
            data = 0,
            mask = 0xf,
            robPointer = pointer,
            pdst = pdst
          )
          var wait = 0
          while (!dut.io.requestReady.toBoolean && wait < 8) {
            sample(dut)
            wait += 1
          }
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          dut.io.requestValid #= false
          sample(dut)
        }

        acceptRequest(0x100, pointer = 1, pdst = 8)
        acceptRequest(0x180, pointer = 2, pdst = 9)

        for ((address, id) <- Seq(BigInt(0x100), BigInt(0x180)).zipWithIndex) {
          var wait = 0
          while (!dut.io.lineReadValid.toBoolean && wait < 8) {
            sample(dut)
            wait += 1
          }
          assert(dut.io.lineRead.lineAddress.toBigInt == address)
          assert(dut.io.lineRead.mshrId.toBigInt == id)
          dut.io.lineReadReady #= true
          sample(dut)
          dut.io.lineReadReady #= false
        }

        acceptRequest(0x104, pointer = 3, pdst = 10)

        val responses = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        def captureResponse(): Unit = {
          if (dut.io.responseValid.toBoolean) {
            responses += dut.io.response.robPointer.toBigInt -> dut.io.response.data.toBigInt
          }
        }
        def sendBeat(id: Int, beat: Int, data: BigInt): Unit = {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= id
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= data
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
          captureResponse()
        }

        for (beat <- 0 until CacheContract.BeatsPerLine) {
          val dataB = if (beat == 0) BigInt("bbbbbbbbaaaaaaaa", 16) else BigInt(0x80 + beat)
          val dataA = if (beat == 0) BigInt("2222222211111111", 16) else BigInt(0x10 + beat)
          sendBeat(id = 1, beat = beat, data = dataB)
          sendBeat(id = 0, beat = beat, data = dataA)
        }
        dut.io.lineReadBeatValid #= false
        for (_ <- 0 until 4) {
          sample(dut)
          captureResponse()
        }

        assert(
          responses.toSeq == Seq(
            BigInt(2) -> BigInt("aaaaaaaa", 16),
            BigInt(1) -> BigInt("11111111", 16),
            BigInt(3) -> BigInt("22222222", 16)
          )
        )
      }
  }

  test("L1D preserves a byte store accepted with the matching refill beat") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-store-refill-same-cycle", 0x4c37) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        setRequest(dut, 0x100, isWrite = false, data = 0, mask = 0xf, robPointer = 1, pdst = 8)
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        assert(dut.io.lineRead.mshrId.toBigInt == 0)
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        setRequest(
          dut,
          0x10c,
          isWrite = true,
          data = BigInt("aabbccdd", 16),
          mask = 0x5,
          robPointer = 2,
          pdst = 0
        )
        dut.io.lineReadBeatValid #= true
        dut.io.lineReadBeat.mshrId #= 0
        dut.io.lineReadBeat.beat #= 1
        dut.io.lineReadBeat.data #= BigInt("1122334455667788", 16)
        dut.io.lineReadBeat.last #= false
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        assert(dut.io.lineReadBeatReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        dut.io.lineReadBeatValid #= false

        setRequest(dut, 0x10c, isWrite = false, data = 0, mask = 0xf, robPointer = 3, pdst = 9)
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false

        val responses = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        def sendBeat(beat: Int, data: BigInt): Unit = {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= 0
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= data
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
          if (dut.io.responseValid.toBoolean) {
            responses += dut.io.response.robPointer.toBigInt -> dut.io.response.data.toBigInt
          }
        }

        sendBeat(0, BigInt("aaaabbbbccccdddd", 16))
        for (beat <- 2 until CacheContract.BeatsPerLine) {
          sendBeat(beat, BigInt(0x80 + beat))
        }
        dut.io.lineReadBeatValid #= false
        for (_ <- 0 until 4) {
          sample(dut)
          if (dut.io.responseValid.toBoolean) {
            responses += dut.io.response.robPointer.toBigInt -> dut.io.response.data.toBigInt
          }
        }

        assert(
          responses.toSeq == Seq(
            BigInt(3) -> BigInt("11bb33dd", 16),
            BigInt(1) -> BigInt("ccccdddd", 16)
          )
        )
      }
  }

  test("L1D preserves aligned byte masks written before refill data arrives") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-store-before-refill-byte-offset", 0x4c39) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        setRequest(
          dut,
          0x100,
          isWrite = true,
          data = 0x3,
          mask = 0x1,
          robPointer = 1,
          pdst = 0
        )
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        assert(dut.io.lineRead.lineAddress.toBigInt == 0x100)
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        setRequest(
          dut,
          0x101,
          isWrite = true,
          data = 0x300,
          mask = 0x2,
          robPointer = 2,
          pdst = 0
        )
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)

        setRequest(
          dut,
          0x102,
          isWrite = true,
          data = 0x30000,
          mask = 0x4,
          robPointer = 3,
          pdst = 0
        )
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false

        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= 0
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= 0
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          dut.io.lineReadBeat.error #= false
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        while (!dut.io.requestReady.toBoolean) { sample(dut) }

        setRequest(
          dut,
          0x100,
          isWrite = false,
          data = 0,
          mask = 0xf,
          robPointer = 4,
          pdst = 9
        )
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.robPointer.toBigInt == 4)
        assert(dut.io.response.data.toBigInt == BigInt("00030303", 16))
      }
  }

  test("L1D serves a hit while an unrelated miss waits below the cache") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-hit-under-miss", 0x4c36) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        setRequest(dut, 0x100, isWrite = false, data = 0, mask = 0xf, robPointer = 1, pdst = 4)
        sample(dut)
        dut.io.requestValid #= false
        val refillResponse = refillLine(
          dut,
          expectedAddress = 0x100,
          beat => if (beat == 0) BigInt("1122334455667788", 16) else BigInt(beat)
        )
        assert(refillResponse._1 == 1)
        assert(refillResponse._3 == BigInt("55667788", 16))
        sample(dut)

        setRequest(dut, 0x180, isWrite = false, data = 0, mask = 0xf, robPointer = 2, pdst = 5)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        assert(dut.io.lineRead.lineAddress.toBigInt == 0x180)

        setRequest(dut, 0x100, isWrite = false, data = 0, mask = 0xf, robPointer = 3, pdst = 6)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == 0x180)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.robPointer.toBigInt == 3)
        assert(dut.io.response.pdst.toBigInt == 6)
        assert(dut.io.response.data.toBigInt == BigInt("55667788", 16))
      }
  }

  test("L1D writes a dirty 64-byte victim before refilling its replacement") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-dirty-writeback", 0x4c33) { dut =>
        def loadMiss(address: BigInt, fill: BigInt, pointer: BigInt): Unit = {
          setRequest(
            dut,
            address,
            isWrite = false,
            data = 0,
            mask = 0xf,
            robPointer = pointer,
            pdst = 3
          )
          sleep(1)
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          dut.io.requestValid #= false
          val refillResponse = refillLine(dut, address & ~BigInt(0x3f), beat => fill + beat)
          assert(refillResponse._1 == pointer)
          sample(dut)
        }

        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)
        sleep(1)

        val setSpan = BigInt(config.dataCache.sets * config.dataCache.lineBytes)
        val line0 = BigInt(0x100)
        val line1 = line0 + setSpan
        val line2 = line0 + setSpan * 2
        val line3 = line0 + setSpan * 3
        loadMiss(line0, BigInt("1000000000000000", 16), 1)
        loadMiss(line1, BigInt("2000000000000000", 16), 2)

        setRequest(
          dut,
          line0,
          isWrite = true,
          data = BigInt("deadbeef", 16),
          mask = 0xf,
          robPointer = 3,
          pdst = 0
        )
        sample(dut)
        dut.io.requestValid #= false
        sample(dut)
        assert(!dut.io.lineWriteValid.toBoolean)

        loadMiss(line2, BigInt("3000000000000000", 16), 4)

        setRequest(dut, line3, isWrite = false, data = 0, mask = 0xf, robPointer = 5, pdst = 4)
        sample(dut)
        dut.io.requestValid #= false
        var waitCycles = 0
        while (!dut.io.lineWriteValid.toBoolean && waitCycles < 6) {
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.lineWriteValid.toBoolean)
        assert(dut.io.lineWrite.lineAddress.toBigInt == line0)
        assert((dut.io.lineWrite.data.toBigInt & BigInt("ffffffff", 16)) == BigInt("deadbeef", 16))

        val heldData = dut.io.lineWrite.data.toBigInt
        for (_ <- 0 until 3) {
          sample(dut)
          assert(dut.io.lineWriteValid.toBoolean)
          assert(dut.io.lineWrite.lineAddress.toBigInt == line0)
          assert(dut.io.lineWrite.data.toBigInt == heldData)
        }

        dut.io.lineWriteResponseError #= true
        dut.io.lineWriteReady #= true
        sample(dut)
        dut.io.lineWriteReady #= false
        dut.io.lineWriteResponseError #= false
        waitCycles = 0
        while (!dut.io.lineWriteValid.toBoolean && waitCycles < 8) {
          assert(!dut.io.lineReadValid.toBoolean)
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.lineWriteValid.toBoolean)
        assert(dut.io.lineWrite.lineAddress.toBigInt == line0)
        assert(dut.io.lineWrite.data.toBigInt == heldData)
        dut.io.lineWriteReady #= true
        sample(dut)
        dut.io.lineWriteReady #= false
        while (!dut.io.lineReadValid.toBoolean) sample(dut)
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == line3)
      }
  }

  test("L1D does not install a line when any refill beat reports an error") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d-refill-error")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-refill-error", 0x4c3a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)

        val address = BigInt(0x500)
        setRequest(dut, address, isWrite = false, 0, 0xf, robPointer = 1, pdst = 4)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        while (!dut.io.lineReadValid.toBoolean) sample(dut)
        val mshrId = dut.io.lineRead.mshrId.toBigInt
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        var sawErrorResponse = false
        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= mshrId
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= BigInt("1234000000000000", 16) + beat
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          dut.io.lineReadBeat.error #= beat == 0
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
          if (dut.io.responseValid.toBoolean) {
            sawErrorResponse = true
            assert(dut.io.response.error.toBoolean)
          }
        }
        dut.io.lineReadBeatValid #= false
        dut.io.lineReadBeat.error #= false
        for (_ <- 0 until 4) {
          if (dut.io.responseValid.toBoolean) {
            sawErrorResponse = true
            assert(dut.io.response.error.toBoolean)
          }
          sample(dut)
        }
        assert(sawErrorResponse)

        setRequest(dut, address, isWrite = false, 0, 0xf, robPointer = 2, pdst = 5)
        while (!dut.io.requestReady.toBoolean) sample(dut)
        sample(dut)
        dut.io.requestValid #= false
        var waitCycles = 0
        while (!dut.io.lineReadValid.toBoolean && waitCycles < 8) {
          assert(!dut.io.responseValid.toBoolean)
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == address)
      }
  }

  test("L1D CACOP preserves dirty data for Index and Hit operations") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1d-maintenance")
      .compile(new L1DataCacheProbe(config))
      .doSim("ooo-l1d-exact-maintenance", 0x4c39) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.dataCache.sets + 8)

        var pointer = BigInt(1)
        def install(address: BigInt, base: BigInt): Unit = {
          setRequest(dut, address, isWrite = false, 0, 0xf, pointer, 4)
          while (!dut.io.requestReady.toBoolean) sample(dut)
          sample(dut)
          dut.io.requestValid #= false
          val response = refillLine(dut, address & ~BigInt(0x3f), beat => base + beat)
          assert(response._1 == pointer)
          pointer += 1
          sample(dut)
        }
        def dirty(address: BigInt, data: BigInt): Unit = {
          setRequest(dut, address, isWrite = true, data, 0xf, pointer, 0)
          while (!dut.io.requestReady.toBoolean) sample(dut)
          sample(dut)
          dut.io.requestValid #= false
          sample(dut)
          pointer += 1
        }
        def expectHit(address: BigInt): Unit = {
          setRequest(dut, address, isWrite = false, 0, 0xf, pointer, 5)
          while (!dut.io.requestReady.toBoolean) sample(dut)
          sample(dut)
          dut.io.requestValid #= false
          sample(dut)
          assert(dut.io.responseValid.toBoolean)
          assert(!dut.io.lineReadValid.toBoolean)
          pointer += 1
          sample(dut)
        }

        val setSpan = BigInt(config.dataCache.sets * config.dataCache.lineBytes)
        val line0 = BigInt(0x100)
        val line1 = line0 + setSpan
        val absentLine = line0 + setSpan * 2
        install(line0, BigInt("1000000000000000", 16))
        install(line1, BigInt("2000000000000000", 16))

        dirty(line0, BigInt("deadbeef", 16))
        maintain(dut, 0x01, line0, 0, expectedWritebackAddress = None)
        expectHit(line1)
        install(line0, BigInt("3000000000000000", 16))

        dirty(line0, BigInt("cafebabe", 16))
        maintain(dut, 0x09, line0, 0, expectedWritebackAddress = Some(line0))
        expectHit(line1)
        install(line0, BigInt("4000000000000000", 16))

        dirty(line0, BigInt("0badf00d", 16))
        maintain(dut, 0x11, absentLine, absentLine, expectedWritebackAddress = None)
        expectHit(line0)
        maintain(dut, 0x11, line0, line0, expectedWritebackAddress = Some(line0))
        expectHit(line1)
        install(line0, BigInt("5000000000000000", 16))
      }
  }
}
