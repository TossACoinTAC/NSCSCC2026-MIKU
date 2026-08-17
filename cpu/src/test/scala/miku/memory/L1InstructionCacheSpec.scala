package miku.memory

import miku.core._
import miku.frontend._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class L1InstructionCacheProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(InstructionCacheRequest(config))
    val requestReady = out Bool ()
    val hitResponsePending = out Bool ()
    val responseValid = out Bool ()
    val response = out(InstructionCacheResponse(config))
    val kill = in Bool ()
    val lineReadValid = out Bool ()
    val lineRead = out(LineReadRequest(config))
    val lineReadReady = in Bool ()
    val lineReadBeatValid = in Bool ()
    val lineReadBeat = in(LineReadBeat(config))
    val lineReadBeatReady = out Bool ()
    val invalidate = in Bool ()
    val maintenanceValid = in Bool ()
    val maintenanceRequest = in(CacheMaintenanceRequest(config))
    val maintenanceReady = out Bool ()
    val maintenanceDone = out Bool ()
    val invalidateBusy = out Bool ()
  }
  noIoPrefix()

  val cache = new L1InstructionCache(config)
  cache.io.requestValid := io.requestValid
  cache.io.request := io.request
  cache.io.kill := io.kill
  cache.io.lineReadReady := io.lineReadReady
  cache.io.lineReadBeatValid := io.lineReadBeatValid
  cache.io.lineReadBeat := io.lineReadBeat
  cache.io.invalidate := io.invalidate
  cache.io.maintenanceRequest.valid := io.maintenanceValid
  cache.io.maintenanceRequest.payload := io.maintenanceRequest

    io.requestReady := cache.io.requestReady
    io.hitResponsePending := cache.io.hitResponsePending
    io.responseValid := cache.io.responseValid
  io.response := cache.io.response
  io.lineReadValid := cache.io.lineReadValid
  io.lineRead := cache.io.lineRead
  io.lineReadBeatReady := cache.io.lineReadBeatReady
  io.invalidateBusy := cache.io.invalidateBusy
  io.maintenanceReady := cache.io.maintenanceRequest.ready
  io.maintenanceDone := cache.io.maintenanceDone
}

private final class OooFrontendL1InstructionCacheProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val decodeReady = in Bits (config.decodeWidth bits)
    val translationEnable = in Bool ()
    val redirectValid = in Bool ()
    val redirectTarget = in UInt (config.xlen bits)
    val invalidate = in Bool ()
    val lineReadValid = out Bool ()
    val lineRead = out(LineReadRequest(config))
    val lineReadReady = in Bool ()
    val lineReadBeatValid = in Bool ()
    val lineReadBeat = in(LineReadBeat(config))
    val lineReadBeatReady = out Bool ()
    val cacheRequestFire = out Bool ()
    val cacheRequestAddress = out UInt (config.xlen bits)
    val cacheResponseValid = out Bool ()
    val cacheKill = out Bool ()
    val fetchPc = out UInt (config.xlen bits)
    val frontendOccupancy = out UInt (log2Up(config.instructionBufferEntries + 1) bits)
  }
  noIoPrefix()

  val frontend = new OooFrontend(config)
  val cache = new L1InstructionCache(config)

  // Model the direct-address ATU path with the same one-entry response replacement contract.
  val translationValid = RegInit(False)
  val translationAddress = Reg(UInt(config.xlen bits)) init (U(config.resetVector, config.xlen bits))
  val translationResponseFire =
    translationValid && io.translationEnable && frontend.io.translationResponse.ready
  frontend.io.translationRequest.ready :=
    io.translationEnable && (!translationValid || translationResponseFire)
  val translationRequestFire =
    frontend.io.translationRequest.valid && frontend.io.translationRequest.ready
  when(translationRequestFire) {
    translationValid := True
    translationAddress := frontend.io.translationRequest.virtualAddress
  }
  when(translationResponseFire && !translationRequestFire) { translationValid := False }
  frontend.io.translationResponse.valid := translationValid && io.translationEnable
  frontend.io.translationResponse.virtualAddress := translationAddress
  frontend.io.translationResponse.physicalAddress := translationAddress
  frontend.io.translationResponse.uncached := False
  frontend.io.translationResponse.cancelled := False
  frontend.io.translationResponse.exception.assignFromBits(
    B(0, frontend.io.translationResponse.exception.getBitsWidth bits)
  )

  cache.io.requestValid := frontend.io.cacheRequestValid
  cache.io.request := frontend.io.cacheRequest
  frontend.io.cacheRequestReady := cache.io.requestCapacityReady
  frontend.io.cacheHitResponsePending := cache.io.hitResponsePending
  when(frontend.io.cacheRequestValid) {
    assert(cache.io.requestCapacityReady === cache.io.requestReady)
  }
  frontend.io.cacheResponseValid := cache.io.responseValid
  frontend.io.cacheResponse := cache.io.response
  cache.io.kill := frontend.io.cacheKill

  frontend.io.decodeReady := io.decodeReady
  frontend.io.redirectValid := io.redirectValid
  frontend.io.redirectTarget := io.redirectTarget
  frontend.io.predictorUpdateValid := False
  frontend.io.predictorUpdatePc := 0
  frontend.io.predictorUpdateTaken := False
  frontend.io.predictorUpdateTarget := 0
  frontend.io.predictorUpdateType := 0
  frontend.io.predictorUpdateMetadata := 0
  frontend.io.predictorUpdateIsCall := False
  frontend.io.predictorUpdateIsReturn := False
  frontend.io.predictorRetireValid := 0
  frontend.io.predictorRetireTaken := 0
  for (lane <- 0 until config.commitWidth) {
    frontend.io.predictorRetireType(lane) := 0
    frontend.io.predictorRetireReturnAddress(lane) := 0
  }
  frontend.io.predictorRetireIsCall := 0
  frontend.io.predictorRetireIsReturn := 0
  frontend.io.privilege := 0
  frontend.io.interruptPending := False

  cache.io.lineReadReady := io.lineReadReady
  cache.io.lineReadBeatValid := io.lineReadBeatValid
  cache.io.lineReadBeat := io.lineReadBeat
  cache.io.invalidate := io.invalidate
  cache.io.maintenanceRequest.valid := False
  cache.io.maintenanceRequest.payload.assignFromBits(
    B(0, cache.io.maintenanceRequest.payload.getBitsWidth bits)
  )

  io.lineReadValid := cache.io.lineReadValid
  io.lineRead := cache.io.lineRead
  io.lineReadBeatReady := cache.io.lineReadBeatReady
  io.cacheRequestFire := frontend.io.cacheRequestValid && cache.io.requestReady
  io.cacheRequestAddress := frontend.io.cacheRequest.physicalAddress
  io.cacheResponseValid := cache.io.responseValid
  io.cacheKill := frontend.io.cacheKill
  io.fetchPc := frontend.io.fetchPc
  io.frontendOccupancy := frontend.io.occupancy
}

class L1InstructionCacheSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: L1InstructionCacheProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def sample(dut: OooFrontendL1InstructionCacheProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: L1InstructionCacheProbe): Unit = {
    dut.io.requestValid #= false
    dut.io.request.virtualAddress #= 0
    dut.io.request.physicalAddress #= 0
    dut.io.request.uncached #= false
    dut.io.kill #= false
    dut.io.lineReadReady #= false
    dut.io.lineReadBeatValid #= false
    dut.io.lineReadBeat.mshrId #= 0
    dut.io.lineReadBeat.beat #= 0
    dut.io.lineReadBeat.data #= 0
    dut.io.lineReadBeat.last #= false
    dut.io.lineReadBeat.error #= false
    dut.io.invalidate #= false
    dut.io.maintenanceValid #= false
    dut.io.maintenanceRequest.code #= 0
    dut.io.maintenanceRequest.virtualAddress #= 0
    dut.io.maintenanceRequest.physicalAddress #= 0
    dut.io.maintenanceRequest.robPointer #= 0
    dut.io.maintenanceRequest.recoveryEpoch #= 0
  }

  private def maintain(
      dut: L1InstructionCacheProbe,
      code: Int,
      virtualAddress: BigInt,
      physicalAddress: BigInt
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
    cycles = 0
    while (!dut.io.maintenanceDone.toBoolean && cycles < 16) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.maintenanceDone.toBoolean)
    sample(dut)
  }

  private def acceptRequest(
      dut: L1InstructionCacheProbe,
      virtualAddress: BigInt,
      physicalAddress: BigInt
  ): Unit = {
    var cycles = 0
    while (!dut.io.requestReady.toBoolean && cycles < 80) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.requestReady.toBoolean)
    dut.io.requestValid #= true
    dut.io.request.virtualAddress #= virtualAddress
    dut.io.request.physicalAddress #= physicalAddress
    sample(dut)
    dut.io.requestValid #= false
  }

  private def instructionBeat(firstInstruction: Int, beat: Int): BigInt = {
    val low = BigInt(firstInstruction + beat * 2) & BigInt("ffffffff", 16)
    val high = BigInt(firstInstruction + beat * 2 + 1) & BigInt("ffffffff", 16)
    (high << 32) | low
  }

  private def encodeDirectBranch(byteOffset: Int): BigInt = {
    require((byteOffset & 3) == 0)
    val encoded = (byteOffset >> 2) & ((1 << 26) - 1)
    val high10 = (encoded >> 16) & 0x3ff
    val low16 = encoded & 0xffff
    (BigInt(0x14) << 26) | (BigInt(low16) << 10) | high10
  }

  private def refill(
      dut: L1InstructionCacheProbe,
      expectedLineAddress: BigInt,
      firstInstruction: Int,
      expectedResponseFirstInstruction: Option[Int] = None
  ): Unit = {
    var cycles = 0
    while (!dut.io.lineReadValid.toBoolean && cycles < 16) {
      sample(dut)
      cycles += 1
    }
    assert(dut.io.lineReadValid.toBoolean)
    assert(dut.io.lineRead.lineAddress.toBigInt == expectedLineAddress)
    dut.io.lineReadReady #= true
    sample(dut)
    dut.io.lineReadReady #= false

    var responseCount = 0
    for (beat <- 0 until CacheContract.BeatsPerLine) {
      dut.io.lineReadBeatValid #= true
      dut.io.lineReadBeat.mshrId #= 0
      dut.io.lineReadBeat.beat #= beat
      dut.io.lineReadBeat.data #= instructionBeat(firstInstruction, beat)
      dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
      sleep(1)
      assert(dut.io.lineReadBeatReady.toBoolean)
      sample(dut)
      if (dut.io.responseValid.toBoolean) {
        responseCount += 1
        assert(expectedResponseFirstInstruction.nonEmpty)
        for (lane <- 0 until config.fetchWidth) {
          assert(
            dut.io.response.instructions(lane).toBigInt ==
              expectedResponseFirstInstruction.get + lane
          )
        }
      }
    }
    dut.io.lineReadBeatValid #= false
    sample(dut)
    assert(responseCount == expectedResponseFirstInstruction.size)
  }

  private def expectGroup(
      dut: L1InstructionCacheProbe,
      virtualAddress: BigInt,
      firstInstruction: Int,
      forbidLineRead: Boolean = false,
      expectedDirectBranchTarget: Option[BigInt] = None
  ): Unit = {
    var cycles = 0
    while (!dut.io.responseValid.toBoolean && cycles < 24) {
      if (forbidLineRead) assert(!dut.io.lineReadValid.toBoolean)
      sample(dut)
      cycles += 1
    }
    assert(dut.io.responseValid.toBoolean)
    assert(dut.io.response.virtualAddress.toBigInt == virtualAddress)
    assert(!dut.io.response.error.toBoolean)
    for (lane <- 0 until config.fetchWidth) {
      assert(dut.io.response.instructions(lane).toBigInt == firstInstruction + lane)
    }
    expectedDirectBranchTarget.foreach { target =>
      assert(dut.io.response.predecode(0).valid.toBoolean)
      assert(dut.io.response.predecode(0).branchType.toBigInt == 1)
      assert(dut.io.response.predecode(0).target.toBigInt == target)
      assert(dut.io.response.predecode(0).staticTaken.toBoolean)
      assert(!dut.io.response.predecode(0).indirect.toBoolean)
    }
    sample(dut)
  }

  private def installLine(
      dut: L1InstructionCacheProbe,
      address: BigInt,
      firstInstruction: Int
  ): Unit = {
    acceptRequest(dut, address, address)
    refill(
      dut,
      expectedLineAddress = address & ~BigInt(0x3f),
      firstInstruction = firstInstruction,
      expectedResponseFirstInstruction = Some(firstInstruction)
    )
  }

  private def assertResponse(
      dut: L1InstructionCacheProbe,
      virtualAddress: BigInt,
      firstInstruction: Int,
      expectedDirectBranchTarget: Option[BigInt] = None
  ): Unit = {
    assert(dut.io.responseValid.toBoolean)
    assert(dut.io.response.virtualAddress.toBigInt == virtualAddress)
    assert(!dut.io.response.error.toBoolean)
    for (lane <- 0 until config.fetchWidth) {
      assert(dut.io.response.instructions(lane).toBigInt == firstInstruction + lane)
    }
    expectedDirectBranchTarget match {
      case Some(target) =>
        assert(dut.io.response.predecode(0).valid.toBoolean)
        assert(dut.io.response.predecode(0).branchType.toBigInt == 1)
        assert(dut.io.response.predecode(0).target.toBigInt == target)
        assert(dut.io.response.predecode(0).staticTaken.toBoolean)
        assert(!dut.io.response.predecode(0).indirect.toBoolean)
      case None =>
        assert(!dut.io.response.predecode(0).valid.toBoolean)
    }
  }

  test("L1I predecode remains aligned with each registered turnover response") {
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          "/sim-workspace-ooo-l1i-registered-predecode"
      )
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-registered-predecode", 0x4c93) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        val branchAddress = BigInt(0x100)
        val plainAddress = BigInt(0x180)
        val branchInstruction = encodeDirectBranch(16).toInt
        installLine(dut, branchAddress, branchInstruction)
        installLine(dut, plainAddress, 1000)
        acceptRequest(dut, branchAddress, branchAddress)

        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= plainAddress
        dut.io.request.physicalAddress #= plainAddress
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        assertResponse(
          dut,
          branchAddress,
          branchInstruction,
          expectedDirectBranchTarget = Some(branchAddress + 16)
        )

        dut.io.request.virtualAddress #= branchAddress
        dut.io.request.physicalAddress #= branchAddress
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        assertResponse(dut, plainAddress, 1000)

        dut.io.requestValid #= false
        sample(dut)
        assertResponse(
          dut,
          branchAddress,
          branchInstruction,
          expectedDirectBranchTarget = Some(branchAddress + 16)
        )
      }
  }

  test("confirmed warm L1I hits turn the frontend owner over before registered data delivery") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-l1i-hit-turnover")
      .compile(new OooFrontendL1InstructionCacheProbe(config))
      .doSim("ooo-frontend-l1i-hit-turnover", 0x4c58) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.decodeReady #= (1 << config.decodeWidth) - 1
        dut.io.translationEnable #= true
        dut.io.redirectValid #= false
        dut.io.redirectTarget #= config.resetVector
        dut.io.invalidate #= false
        dut.io.lineReadReady #= false
        dut.io.lineReadBeatValid #= false
        dut.io.lineReadBeat.mshrId #= 0
        dut.io.lineReadBeat.beat #= 0
        dut.io.lineReadBeat.data #= 0
        dut.io.lineReadBeat.last #= false
        dut.io.lineReadBeat.error #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        var cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 24) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == config.resetVector)
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false
        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(1000, beat)
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        // Complete installation, then discard all first-pass frontend state without invalidating
        // the warm line.
        sample(dut)
        dut.io.redirectValid #= true
        dut.io.redirectTarget #= config.resetVector
        sample(dut)
        dut.io.redirectValid #= false

        val fires = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt)]
        val responseRequestOverlap = scala.collection.mutable.ArrayBuffer.empty[Int]
        cycles = 0
        while (fires.size < 4 && cycles < 32) {
          sleep(1)
          if (dut.io.cacheRequestFire.toBoolean) {
            fires += cycles -> dut.io.cacheRequestAddress.toBigInt
            if (dut.io.cacheResponseValid.toBoolean) {
              responseRequestOverlap += cycles
            }
          }
          sample(dut)
          cycles += 1
        }
        assert(fires.map(_._2) == (0 until 4).map(config.resetVector + _ * 16))
        // Fetch produces four instructions while decode consumes at most three, so the finite
        // frontend buffer must eventually throttle a sustained hit stream. The narrow confirmed-
        // hit token releases the owner one cycle before the registered instruction data arrives.
        withClue(
          s"cache request fires: ${fires.mkString(", ")}; overlap: ${responseRequestOverlap.mkString(", ")}"
        ) {
          assert(fires(1)._1 - fires(0)._1 == 1)
          assert(responseRequestOverlap.contains(fires(2)._1))
        }
      }
  }

  test("real frontend correction kills the registered-hit turnover accepted behind it") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-frontend-l1i-hit-correction")
      .compile(new OooFrontendL1InstructionCacheProbe(config))
      .doSim("ooo-frontend-l1i-hit-correction", 0x4c59) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.decodeReady #= (1 << config.decodeWidth) - 1
        dut.io.translationEnable #= true
        dut.io.redirectValid #= false
        dut.io.redirectTarget #= config.resetVector
        dut.io.invalidate #= false
        dut.io.lineReadReady #= false
        dut.io.lineReadBeatValid #= false
        dut.io.lineReadBeat.mshrId #= 0
        dut.io.lineReadBeat.beat #= 0
        dut.io.lineReadBeat.data #= 0
        dut.io.lineReadBeat.last #= false
        dut.io.lineReadBeat.error #= false
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        var cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 24) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        // Kill the cold request before critical-word return so its predecode cannot train the BTB.
        dut.io.translationEnable #= false
        dut.io.redirectValid #= true
        dut.io.redirectTarget #= config.resetVector + 0x1000
        sample(dut)
        dut.io.redirectValid #= false

        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false
        val branchTarget = config.resetVector + 0x20
        val lineInstructions = IndexedSeq.tabulate(16)(index => BigInt(2000 + index)).updated(
          1,
          encodeDirectBranch(branchTarget.toInt - (config.resetVector + 4).toInt)
        )
        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.beat #= beat
          val low = lineInstructions(beat * 2) & BigInt("ffffffff", 16)
          val high = lineInstructions(beat * 2 + 1) & BigInt("ffffffff", 16)
          dut.io.lineReadBeat.data #= (high << 32) | low
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        sample(dut)

        dut.io.redirectValid #= true
        dut.io.redirectTarget #= config.resetVector
        sample(dut)
        dut.io.redirectValid #= false
        dut.io.translationEnable #= true
        // Retain accepted instructions so a stale registered response is architecturally visible
        // to this test instead of disappearing through decode in the same cycle.
        dut.io.decodeReady #= 0

        val requestAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
        var sawCorrectionKill = false
        var sawPostCorrectionTargetRequest = false
        cycles = 0
        while ((!sawCorrectionKill || !sawPostCorrectionTargetRequest) && cycles < 40) {
          sleep(1)
          if (dut.io.cacheRequestFire.toBoolean) {
            val requestAddress = dut.io.cacheRequestAddress.toBigInt
            requestAddresses += requestAddress
            if (sawCorrectionKill && requestAddress == branchTarget) {
              sawPostCorrectionTargetRequest = true
            }
          }
          if (dut.io.cacheKill.toBoolean) {
            sawCorrectionKill = true
          }
          sample(dut)
          cycles += 1
        }
        withClue(
          s"requests=${requestAddresses.map(address => "0x" + address.toString(16)).mkString(",")}, " +
            s"kill=$sawCorrectionKill post-correction-target=$sawPostCorrectionTargetRequest " +
            s"occupancy=${dut.io.frontendOccupancy.toBigInt}: "
        ) {
          assert(requestAddresses.headOption.contains(config.resetVector))
          assert(requestAddresses.contains(config.resetVector + 16))
          assert(sawCorrectionKill)
          assert(sawPostCorrectionTargetRequest)
          // The registered response for the canceled sequential group may overlap the delayed kill,
          // but its four instructions must not enter the frontend behind the two pre-branch slots.
          assert(dut.io.frontendOccupancy.toBigInt == 2)
        }
      }
  }

  test("L1I turns confirmed hits over at one response per cycle and preserves hit-to-miss") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-hit-turnover")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-hit-turnover", 0x4c56) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        val addresses = Seq(BigInt(0x100), BigInt(0x180), BigInt(0x200))
        val instructions = Seq(1000, 2000, 3000)
        addresses.zip(instructions).foreach { case (address, firstInstruction) =>
          installLine(dut, address, firstInstruction)
        }

        acceptRequest(dut, addresses(0), addresses(0))
        for (next <- 1 until addresses.length) {
          dut.io.requestValid #= true
          dut.io.request.virtualAddress #= addresses(next)
          dut.io.request.physicalAddress #= addresses(next)
          sleep(1)
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          assertResponse(dut, addresses(next - 1), instructions(next - 1))
        }
        dut.io.requestValid #= false
        sample(dut)
        assertResponse(dut, addresses.last, instructions.last)
        sample(dut)
        assert(!dut.io.responseValid.toBoolean)

        val missAddress = BigInt(0x2c0)
        acceptRequest(dut, addresses.head, addresses.head)
        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= missAddress
        dut.io.request.physicalAddress #= missAddress
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        assertResponse(dut, addresses.head, instructions.head)

        var cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 8) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == (missAddress & ~BigInt(0x3f)))
        assert(!dut.io.requestReady.toBoolean)
      }
  }

  test("speculative instruction-array read preserves one-cycle hit turnover and miss recovery") {
    for ((enabled, decoupled) <- Seq((false, false), (true, false), (true, true))) {
      val testConfig = config.copy(
        enableSpeculativeInstructionArrayRead = enabled,
        enableInstructionArrayDataReadDecoupling = decoupled
      )
      SimConfig.withVerilator
        .workspacePath(
          sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
            s"/sim-workspace-ooo-l1i-array-read-$enabled-$decoupled"
        )
        .compile(new L1InstructionCacheProbe(testConfig))
        .doSim(s"ooo-l1i-array-read-$enabled-$decoupled", if (decoupled) 0x4c92 else if (enabled) 0x4c91 else 0x4c90) { dut =>
          dut.clockDomain.forkStimulus(period = 10)
          clearInputs(dut)
          dut.clockDomain.assertReset()
          dut.clockDomain.waitSampling(2)
          dut.clockDomain.deassertReset()
          dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

          val firstAddress = BigInt(0x100)
          val secondAddress = BigInt(0x180)
          val thirdAddress = BigInt(0x200)
          installLine(dut, firstAddress, 1000)
          installLine(dut, secondAddress, 2000)
          installLine(dut, thirdAddress, 3000)
          acceptRequest(dut, firstAddress, firstAddress)

          dut.io.requestValid #= true
          dut.io.request.virtualAddress #= secondAddress
          dut.io.request.physicalAddress #= secondAddress
          sleep(1)
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          assertResponse(dut, firstAddress, 1000)

          // The next accepted hit must use the same registered-response latency.
          // This remains true whether the array lookup was issued speculatively
          // or from the confirmed-turnover request path.
          dut.io.request.virtualAddress #= thirdAddress
          dut.io.request.physicalAddress #= thirdAddress
          sleep(1)
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          assertResponse(dut, secondAddress, 2000)
          dut.io.requestValid #= false
          sample(dut)
          assertResponse(dut, thirdAddress, 3000)
          sample(dut)
          assert(!dut.io.responseValid.toBoolean)

          val missAddress = BigInt(0x2c0)
          acceptRequest(dut, firstAddress, firstAddress)
          dut.io.requestValid #= true
          dut.io.request.virtualAddress #= missAddress
          dut.io.request.physicalAddress #= missAddress
          sleep(1)
          assert(dut.io.requestReady.toBoolean)
          sample(dut)
          dut.io.requestValid #= false
          assertResponse(dut, firstAddress, 1000)
          var missWait = 0
          while (!dut.io.lineReadValid.toBoolean && missWait < 8) {
            sample(dut)
            missWait += 1
          }
          assert(dut.io.lineReadValid.toBoolean)
          assert(dut.io.lineRead.lineAddress.toBigInt == (missAddress & ~BigInt(0x3f)))
        }
    }
  }

  test("L1I hit turnover yields to kill, invalidate, and maintenance") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-hit-turnover-control")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-hit-turnover-control", 0x4c57) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        val line0 = BigInt(0x100)
        val line1 = BigInt(0x180)
        installLine(dut, line0, 4000)
        installLine(dut, line1, 5000)

        acceptRequest(dut, line0, line0)
        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= line1
        dut.io.request.physicalAddress #= line1
        dut.io.kill #= true
        sleep(1)
        assert(!dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        dut.io.kill #= false
        assert(!dut.io.responseValid.toBoolean)

        acceptRequest(dut, line0, line0)
        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= line1
        dut.io.request.physicalAddress #= line1
        dut.io.maintenanceValid #= true
        dut.io.maintenanceRequest.code #= 0x10
        dut.io.maintenanceRequest.virtualAddress #= line0
        dut.io.maintenanceRequest.physicalAddress #= line0
        sleep(1)
        assert(!dut.io.requestReady.toBoolean)
        assert(!dut.io.maintenanceReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        assertResponse(dut, line0, 4000)
        sleep(1)
        assert(dut.io.maintenanceReady.toBoolean)
        sample(dut)
        dut.io.maintenanceValid #= false
        var cycles = 0
        while (!dut.io.maintenanceDone.toBoolean && cycles < 8) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.maintenanceDone.toBoolean)
        sample(dut)

        acceptRequest(dut, line1, line1)
        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= line0
        dut.io.request.physicalAddress #= line0
        dut.io.invalidate #= true
        sleep(1)
        assert(!dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        dut.io.invalidate #= false
        assert(!dut.io.responseValid.toBoolean)
        cycles = 0
        while (dut.io.invalidateBusy.toBoolean &&
            cycles < config.instructionCache.sets + 16) {
          sample(dut)
          cycles += 1
        }
        assert(!dut.io.invalidateBusy.toBoolean)

        acceptRequest(dut, line1, line1)
        cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 8) {
          assert(!dut.io.responseValid.toBoolean)
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == line1)
      }
  }

  test("L1I refills 64-byte lines, selects fetch groups, and suppresses killed responses") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-refill-hit-kill", 0x4c51) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)
        sleep(1)
        assert(dut.io.requestReady.toBoolean)

        // A prediction correction may arrive with an already translated next-group request.
        // Accept it to keep kill out of the lookup enable, then abort before allocating a miss.
        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= 0x1c000040
        dut.io.request.physicalAddress #= 0x40
        dut.io.request.uncached #= false
        dut.io.kill #= true
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        dut.io.kill #= false
        for (_ <- 0 until 3) {
          sample(dut)
          assert(!dut.io.responseValid.toBoolean)
          assert(!dut.io.lineReadValid.toBoolean)
        }
        assert(dut.io.requestReady.toBoolean)

        acceptRequest(dut, virtualAddress = 0x1c000130, physicalAddress = 0x130)
        refill(
          dut,
          expectedLineAddress = 0x100,
          firstInstruction = 100,
          expectedResponseFirstInstruction = Some(112)
        )

        acceptRequest(dut, virtualAddress = 0x1c000110, physicalAddress = 0x110)
        expectGroup(
          dut,
          virtualAddress = 0x1c000110,
          firstInstruction = 104,
          forbidLineRead = true
        )

        // Populate the other way of the same set with a direct branch.  A subsequent hit must
        // select the matching way's instruction and its independently predecoded branch facts.
        acceptRequest(dut, virtualAddress = 0x1c001100, physicalAddress = 0x1100)
        refill(
          dut,
          expectedLineAddress = 0x1100,
          firstInstruction = 0x50000000,
          expectedResponseFirstInstruction = Some(0x50000000)
        )
        acceptRequest(dut, virtualAddress = 0x1c001100, physicalAddress = 0x1100)
        expectGroup(
          dut,
          virtualAddress = 0x1c001100,
          firstInstruction = 0x50000000,
          forbidLineRead = true,
          expectedDirectBranchTarget = Some(0x1c001100)
        )

        acceptRequest(dut, virtualAddress = 0x1c000240, physicalAddress = 0x240)
        var cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 16) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        dut.io.kill #= true
        sample(dut)
        dut.io.kill #= false
        refill(dut, expectedLineAddress = 0x240, firstInstruction = 200)
        assert(!dut.io.responseValid.toBoolean)
        for (_ <- 0 until 3) {
          sample(dut)
          assert(!dut.io.responseValid.toBoolean)
        }

        acceptRequest(dut, virtualAddress = 0x1c000240, physicalAddress = 0x240)
        expectGroup(
          dut,
          virtualAddress = 0x1c000240,
          firstInstruction = 200,
          forbidLineRead = true
        )

        acceptRequest(dut, virtualAddress = 0x1c000340, physicalAddress = 0x340)
        cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 16) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        dut.io.invalidate #= true
        sample(dut)
        dut.io.invalidate #= false
        refill(dut, expectedLineAddress = 0x340, firstInstruction = 400)
        assert(!dut.io.responseValid.toBoolean)
        cycles = 0
        while (dut.io.invalidateBusy.toBoolean && cycles < config.instructionCache.sets + 16) {
          sample(dut)
          cycles += 1
        }
        assert(!dut.io.invalidateBusy.toBoolean)

        acceptRequest(dut, virtualAddress = 0x1c000340, physicalAddress = 0x340)
        cycles = 0
        while (!dut.io.lineReadValid.toBoolean && cycles < 16) {
          sample(dut)
          cycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
      }
  }

  test("L1I returns the requested 16-byte group before the complete line is installed") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-critical-group")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-critical-group-first", 0x4c52) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        acceptRequest(dut, virtualAddress = 0x1c000110, physicalAddress = 0x110)
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        for (beat <- 0 until 4) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= 0
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(500, beat)
          dut.io.lineReadBeat.last #= false
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          if (beat < 3) assert(!dut.io.responseValid.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        sleep(1)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.virtualAddress.toBigInt == 0x1c000110L)
        for (lane <- 0 until config.fetchWidth) {
          assert(dut.io.response.instructions(lane).toBigInt == 504 + lane)
        }
        sample(dut)
        assert(!dut.io.responseValid.toBoolean)
        assert(dut.io.requestReady.toBoolean)

        for (beat <- 4 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(500, beat)
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sleep(1)
          assert(dut.io.lineReadBeatReady.toBoolean)
          assert(!dut.io.responseValid.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        sample(dut)
        assert(!dut.io.responseValid.toBoolean)

        acceptRequest(dut, virtualAddress = 0x1c000130, physicalAddress = 0x130)
        expectGroup(
          dut,
          virtualAddress = 0x1c000130,
          firstInstruction = 512,
          forbidLineRead = true
        )
      }
  }

  test("L1I streams later fetch groups from the line being refilled") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-streaming-groups")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-streaming-groups", 0x4c53) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        val virtualBase = BigInt("1c000100", 16)
        val physicalBase = BigInt("100", 16)
        acceptRequest(dut, virtualBase, physicalBase)
        while (!dut.io.lineReadValid.toBoolean) { sample(dut) }
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        for (beat <- 0 until 2) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(700, beat)
          sample(dut)
        }
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.virtualAddress.toBigInt == virtualBase)

        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= virtualBase + 16
        dut.io.request.physicalAddress #= physicalBase + 16
        dut.io.lineReadBeat.beat #= 2
        dut.io.lineReadBeat.data #= instructionBeat(700, 2)
        sleep(1)
        assert(dut.io.requestReady.toBoolean)
        sample(dut)
        dut.io.requestValid #= false
        assert(!dut.io.responseValid.toBoolean)

        dut.io.lineReadBeat.beat #= 3
        dut.io.lineReadBeat.data #= instructionBeat(700, 3)
        sample(dut)
        assert(dut.io.responseValid.toBoolean)
        assert(dut.io.response.virtualAddress.toBigInt == virtualBase + 16)
        for (lane <- 0 until config.fetchWidth) {
          assert(dut.io.response.instructions(lane).toBigInt == 704 + lane)
        }

        dut.io.requestValid #= true
        dut.io.request.virtualAddress #= virtualBase + 64
        dut.io.request.physicalAddress #= physicalBase + 64
        sleep(1)
        assert(!dut.io.requestReady.toBoolean)
        dut.io.requestValid #= false

        for (beat <- 4 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(700, beat)
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        sample(dut)
        assert(dut.io.requestReady.toBoolean)
      }
  }

  test("L1I does not install a line when any refill beat reports an error") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-refill-error")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-refill-error", 0x4c55) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        val virtualAddress = BigInt("1c000500", 16)
        val physicalAddress = BigInt(0x500)
        acceptRequest(dut, virtualAddress, physicalAddress)
        while (!dut.io.lineReadValid.toBoolean) sample(dut)
        val mshrId = dut.io.lineRead.mshrId.toBigInt
        dut.io.lineReadReady #= true
        sample(dut)
        dut.io.lineReadReady #= false

        for (beat <- 0 until CacheContract.BeatsPerLine) {
          dut.io.lineReadBeatValid #= true
          dut.io.lineReadBeat.mshrId #= mshrId
          dut.io.lineReadBeat.beat #= beat
          dut.io.lineReadBeat.data #= instructionBeat(900, beat)
          dut.io.lineReadBeat.last #= beat == CacheContract.BeatsPerLine - 1
          dut.io.lineReadBeat.error #= beat == 3
          assert(dut.io.lineReadBeatReady.toBoolean)
          sample(dut)
        }
        dut.io.lineReadBeatValid #= false
        dut.io.lineReadBeat.error #= false
        sample(dut)

        acceptRequest(dut, virtualAddress, physicalAddress)
        var waitCycles = 0
        while (!dut.io.lineReadValid.toBoolean && waitCycles < 8) {
          assert(!dut.io.responseValid.toBoolean)
          sample(dut)
          waitCycles += 1
        }
        assert(dut.io.lineReadValid.toBoolean)
        assert(dut.io.lineRead.lineAddress.toBigInt == physicalAddress)
      }
  }

  test("L1I CACOP modes invalidate only the selected line") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-l1i-maintenance")
      .compile(new L1InstructionCacheProbe(config))
      .doSim("ooo-l1i-exact-maintenance", 0x4c54) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(config.instructionCache.sets + 8)

        def install(address: BigInt, firstInstruction: Int): Unit = {
          acceptRequest(dut, address, address)
          refill(dut, address & ~BigInt(0x3f), firstInstruction, Some(firstInstruction))
        }
        def expectMissAndRefill(address: BigInt, firstInstruction: Int): Unit = {
          acceptRequest(dut, address, address)
          var cycles = 0
          while (!dut.io.lineReadValid.toBoolean && cycles < 16) {
            sample(dut)
            cycles += 1
          }
          assert(dut.io.lineReadValid.toBoolean)
          refill(dut, address & ~BigInt(0x3f), firstInstruction, Some(firstInstruction))
        }

        val setSpan = BigInt(config.instructionCache.sets * config.instructionCache.lineBytes)
        val line0 = BigInt(0x100)
        val line1 = line0 + setSpan
        val absentLine = line0 + setSpan * 2
        install(line0, 0x1000)
        install(line1, 0x2000)

        // A hit operation that misses is a side-effect-free completion.
        maintain(dut, code = 0x10, virtualAddress = absentLine, physicalAddress = absentLine)
        acceptRequest(dut, line1, line1)
        expectGroup(dut, line1, 0x2000, forbidLineRead = true)

        // Store Tag and Index select the way from VA bit zero and the set from VA index bits.
        maintain(dut, code = 0x00, virtualAddress = line0, physicalAddress = 0)
        acceptRequest(dut, line1, line1)
        expectGroup(dut, line1, 0x2000, forbidLineRead = true)
        expectMissAndRefill(line0, 0x3000)

        maintain(dut, code = 0x08, virtualAddress = line0 + 1, physicalAddress = 0)
        acceptRequest(dut, line0, line0)
        expectGroup(dut, line0, 0x3000, forbidLineRead = true)
        expectMissAndRefill(line1, 0x4000)

        maintain(dut, code = 0x10, virtualAddress = line0, physicalAddress = line0)
        acceptRequest(dut, line1, line1)
        expectGroup(dut, line1, 0x4000, forbidLineRead = true)
        expectMissAndRefill(line0, 0x5000)
      }
  }
}
