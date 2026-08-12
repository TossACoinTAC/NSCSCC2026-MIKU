package miku.memory

import miku.compat.Axi3Compat
import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable.ArrayBuffer
import scala.language.reflectiveCalls
import scala.util.Random

private final class AxiLineBridgeProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val memoryReadValid = in Bool ()
    val memoryRead = in(LineReadRequest(config))
    val memoryReadReady = out Bool ()
    val memoryReadBeatValid = out Bool ()
    val memoryReadBeat = out(LineReadBeat(config))
    val memoryReadBeatReady = in Bool ()

    val memoryWriteValid = in Bool ()
    val memoryWriteLineAddress = in UInt (config.xlen bits)
    val memoryWriteDataWords = in Vec (Bits(32 bits), CacheContract.LineBytes / 4)
    val memoryWriteByteMask = in Bits (CacheContract.LineBytes bits)
    val memoryWriteMshrId = in UInt (log2Up(config.mshrEntries) bits)
    val memoryWriteReady = out Bool ()
    val memoryWriteResponseValid = out Bool ()
    val memoryWriteResponse = out(LineWriteResponse(config))

    val uncachedInstructionRequestValid = in Bool ()
    val uncachedInstructionRequest = in(InstructionCacheRequest(config))
    val uncachedInstructionRequestReady = out Bool ()
    val uncachedInstructionResponseValid = out Bool ()
    val uncachedInstructionResponse = out(InstructionCacheResponse(config))

    val uncachedDataRequestValid = in Bool ()
    val uncachedDataRequest = in(CacheRequest(config))
    val uncachedDataRequestReady = out Bool ()
    val uncachedDataResponseValid = out Bool ()
    val uncachedDataResponse = out(CacheResponse(config))

    val axi = master(Axi3Compat())
    val idle = out Bool ()
  }
  noIoPrefix()

  val bridge = new AxiLineBridge(config)
  bridge.io.memoryReadValid := io.memoryReadValid
  bridge.io.memoryRead := io.memoryRead
  io.memoryReadReady := bridge.io.memoryReadReady
  io.memoryReadBeatValid := bridge.io.memoryReadBeatValid
  io.memoryReadBeat := bridge.io.memoryReadBeat
  bridge.io.memoryReadBeatReady := io.memoryReadBeatReady

  bridge.io.memoryWriteValid := io.memoryWriteValid
  bridge.io.memoryWrite.lineAddress := io.memoryWriteLineAddress
  for (word <- 0 until CacheContract.LineBytes / 4) {
    bridge.io.memoryWrite.data(word * 32 + 31 downto word * 32) :=
      io.memoryWriteDataWords(word)
  }
  bridge.io.memoryWrite.byteMask := io.memoryWriteByteMask
  bridge.io.memoryWrite.mshrId := io.memoryWriteMshrId
  io.memoryWriteReady := bridge.io.memoryWriteReady
  io.memoryWriteResponseValid := bridge.io.memoryWriteResponseValid
  io.memoryWriteResponse := bridge.io.memoryWriteResponse

  bridge.io.uncachedInstructionRequestValid := io.uncachedInstructionRequestValid
  bridge.io.uncachedInstructionRequest := io.uncachedInstructionRequest
  io.uncachedInstructionRequestReady := bridge.io.uncachedInstructionRequestReady
  io.uncachedInstructionResponseValid := bridge.io.uncachedInstructionResponseValid
  io.uncachedInstructionResponse := bridge.io.uncachedInstructionResponse

  bridge.io.uncachedDataRequestValid := io.uncachedDataRequestValid
  bridge.io.uncachedDataRequest := io.uncachedDataRequest
  io.uncachedDataRequestReady := bridge.io.uncachedDataRequestReady
  io.uncachedDataResponseValid := bridge.io.uncachedDataResponseValid
  io.uncachedDataResponse := bridge.io.uncachedDataResponse

  io.axi.ar.valid := bridge.io.axi.ar.valid
  io.axi.ar.payload := bridge.io.axi.ar.payload
  bridge.io.axi.ar.ready := io.axi.ar.ready
  bridge.io.axi.r.valid := io.axi.r.valid
  bridge.io.axi.r.payload := io.axi.r.payload
  io.axi.r.ready := bridge.io.axi.r.ready
  io.axi.aw.valid := bridge.io.axi.aw.valid
  io.axi.aw.payload := bridge.io.axi.aw.payload
  bridge.io.axi.aw.ready := io.axi.aw.ready
  io.axi.w.valid := bridge.io.axi.w.valid
  io.axi.w.payload := bridge.io.axi.w.payload
  bridge.io.axi.w.ready := io.axi.w.ready
  bridge.io.axi.b.valid := io.axi.b.valid
  bridge.io.axi.b.payload := io.axi.b.payload
  io.axi.b.ready := bridge.io.axi.b.ready
  io.idle := bridge.io.idle
}

class AxiLineBridgeSpec extends AnyFunSuite {
  private val config = OooCoreConfig.FourIssueThreeCommit

  private def sample(dut: AxiLineBridgeProbe): Unit = {
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  private def clearInputs(dut: AxiLineBridgeProbe): Unit = {
    dut.io.memoryReadValid #= false
    dut.io.memoryRead.lineAddress #= 0
    dut.io.memoryRead.mshrId #= 0
    dut.io.memoryRead.criticalBeat #= 0
    dut.io.memoryReadBeatReady #= true
    dut.io.memoryWriteValid #= false
    dut.io.memoryWriteLineAddress #= 0
    for (word <- 0 until CacheContract.LineBytes / 4) {
      dut.io.memoryWriteDataWords(word) #= 0
    }
    dut.io.memoryWriteByteMask #= 0
    dut.io.memoryWriteMshrId #= 0
    dut.io.uncachedInstructionRequestValid #= false
    dut.io.uncachedInstructionRequest.virtualAddress #= 0
    dut.io.uncachedInstructionRequest.physicalAddress #= 0
    dut.io.uncachedInstructionRequest.uncached #= true
    dut.io.uncachedDataRequestValid #= false
    dut.io.uncachedDataRequest.virtualAddress #= 0
    dut.io.uncachedDataRequest.physicalAddress #= 0
    dut.io.uncachedDataRequest.isWrite #= false
    dut.io.uncachedDataRequest.size #= 2
    dut.io.uncachedDataRequest.byteMask #= 0xf
    dut.io.uncachedDataRequest.writeData #= 0
    dut.io.uncachedDataRequest.uncached #= true
    dut.io.uncachedDataRequest.robPointer #= 0
    dut.io.uncachedDataRequest.recoveryEpoch #= 0
    dut.io.uncachedDataRequest.pdst #= 0
    dut.io.uncachedDataRequest.loadQueueIndex #= 0
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

  test("cached writes take priority over simultaneous uncached requests") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-write")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-cached-write-priority", 0x4c6a) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.memoryWriteValid #= true
        dut.io.memoryWriteLineAddress #= 0x9000
        dut.io.memoryWriteByteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
        dut.io.uncachedDataRequestValid #= true
        dut.io.uncachedDataRequest.physicalAddress #= BigInt("1fe00100", 16)
        dut.io.uncachedInstructionRequestValid #= true
        dut.io.uncachedInstructionRequest.virtualAddress #= BigInt("1c001000", 16)
        dut.io.uncachedInstructionRequest.physicalAddress #= 0x1000
        sleep(1)

        assert(dut.io.memoryWriteReady.toBoolean)
        assert(!dut.io.uncachedDataRequestReady.toBoolean)
        assert(!dut.io.uncachedInstructionRequestReady.toBoolean)
        sample(dut)
        dut.io.memoryWriteValid #= false
        dut.io.uncachedInstructionRequestValid #= false

        assert(dut.io.axi.aw.valid.toBoolean)
        assert(dut.io.axi.aw.payload.address.toBigInt == 0x9000)
        assert(dut.io.axi.aw.payload.id.toBigInt == 1)
        dut.io.axi.aw.ready #= true
        sample(dut)
        dut.io.axi.aw.ready #= false
        dut.io.axi.w.ready #= true
        for (_ <- 0 until CacheContract.LineBytes / 4) { sample(dut) }
        dut.io.axi.w.ready #= false

        assert(dut.io.axi.b.ready.toBoolean)
        dut.io.memoryWriteValid #= true
        dut.io.axi.b.valid #= true
        sample(dut)
        dut.io.axi.b.valid #= false
        sleep(1)
        assert(dut.io.uncachedDataRequestReady.toBoolean)
        assert(!dut.io.memoryWriteReady.toBoolean)
      }
  }

  test("critical-word-first line reads use AXI wrapping and return eight internal beats") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-read")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-line-read", 0x4c64) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.memoryReadValid #= true
        dut.io.memoryRead.lineAddress #= 0x4000
        dut.io.memoryRead.mshrId #= 3
        dut.io.memoryRead.criticalBeat #= 5
        sleep(1)
        assert(dut.io.memoryReadReady.toBoolean)
        sample(dut)
        dut.io.memoryReadValid #= false

        assert(dut.io.axi.ar.valid.toBoolean)
        assert(dut.io.axi.ar.payload.address.toBigInt == 0x4028)
        assert(dut.io.axi.ar.payload.id.toBigInt == 7)
        assert(dut.io.axi.ar.payload.len.toBigInt == 15)
        assert(dut.io.axi.ar.payload.size.toBigInt == 2)
        assert(dut.io.axi.ar.payload.burst.toBigInt == 2)
        dut.io.axi.ar.ready #= true
        sample(dut)
        dut.io.axi.ar.ready #= false

        val observed = ArrayBuffer.empty[BigInt]
        fork {
          while (observed.size < CacheContract.BeatsPerLine) {
            sample(dut)
            if (dut.io.memoryReadBeatValid.toBoolean) {
              val responseIndex = observed.size
              val beat = (5 + responseIndex) % CacheContract.BeatsPerLine
              assert(dut.io.memoryReadBeat.mshrId.toBigInt == 3)
              assert(dut.io.memoryReadBeat.beat.toBigInt == beat)
              assert(
                dut.io.memoryReadBeat.last.toBoolean ==
                  (responseIndex == CacheContract.BeatsPerLine - 1)
              )
              assert(!dut.io.memoryReadBeat.error.toBoolean)
              observed += dut.io.memoryReadBeat.data.toBigInt
            }
          }
        }

        for (word <- 0 until 16) {
          dut.io.axi.r.valid #= true
          dut.io.axi.r.payload.id #= 7
          dut.io.axi.r.payload.data #= (0x100 + word)
          dut.io.axi.r.payload.response #= 0
          dut.io.axi.r.payload.last #= word == 15
          sleep(1)
          assert(dut.io.axi.r.ready.toBoolean)
          sample(dut)
        }
        dut.io.axi.r.valid #= false
        while (observed.size < CacheContract.BeatsPerLine) { sample(dut) }
        for (responseIndex <- observed.indices) {
          assert(
            observed(responseIndex) ==
              (BigInt(0x101 + responseIndex * 2) << 32 |
                BigInt(0x100 + responseIndex * 2))
          )
        }
      }
  }

  test("four cached line reads keep independent state under interleaved AXI responses") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-read")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-four-interleaved-line-reads", 0x4c69) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        for (id <- 0 until config.mshrEntries) {
          dut.io.memoryReadValid #= true
          dut.io.memoryRead.lineAddress #= 0x4000 + id * CacheContract.LineBytes
          dut.io.memoryRead.mshrId #= id
          sleep(1)
          assert(dut.io.memoryReadReady.toBoolean)
          sample(dut)
          dut.io.memoryReadValid #= false

          assert(dut.io.axi.ar.valid.toBoolean)
          assert(dut.io.axi.ar.payload.id.toBigInt == 4 + id)
          assert(
            dut.io.axi.ar.payload.address.toBigInt ==
              0x4000 + id * CacheContract.LineBytes
          )
          dut.io.axi.ar.ready #= true
          sample(dut)
          dut.io.axi.ar.ready #= false
        }

        val observed = ArrayBuffer.empty[(Int, Int, BigInt, Boolean)]
        fork {
          while (observed.size < config.mshrEntries * CacheContract.BeatsPerLine) {
            sample(dut)
            if (dut.io.memoryReadBeatValid.toBoolean) {
              observed += ((
                dut.io.memoryReadBeat.mshrId.toInt,
                dut.io.memoryReadBeat.beat.toInt,
                dut.io.memoryReadBeat.data.toBigInt,
                dut.io.memoryReadBeat.last.toBoolean
              ))
              assert(!dut.io.memoryReadBeat.error.toBoolean)
            }
          }
        }

        val responseOrder = Seq(3, 0, 2, 1)
        def sendWord(id: Int, data: BigInt, last: Boolean): Unit = {
          dut.io.axi.r.valid #= true
          dut.io.axi.r.payload.id #= 4 + id
          dut.io.axi.r.payload.data #= data
          dut.io.axi.r.payload.last #= last
          sleep(1)
          while (!dut.io.axi.r.ready.toBoolean) { sample(dut) }
          sample(dut)
        }
        for (beat <- 0 until CacheContract.BeatsPerLine) {
          for (id <- responseOrder) {
            val lowWord = 0x1000 + id * 0x100 + beat * 2
            sendWord(id, lowWord, last = false)
          }
          for (id <- responseOrder.reverse) {
            val highWord = 0x1001 + id * 0x100 + beat * 2
            sendWord(
              id,
              highWord,
              last = beat == CacheContract.BeatsPerLine - 1
            )
          }
        }
        dut.io.axi.r.valid #= false
        while (observed.size < config.mshrEntries * CacheContract.BeatsPerLine) {
          sample(dut)
        }

        for (id <- 0 until config.mshrEntries; beat <- 0 until CacheContract.BeatsPerLine) {
          val matches = observed.filter(entry => entry._1 == id && entry._2 == beat)
          assert(matches.size == 1)
          val expected =
            (BigInt(0x1001 + id * 0x100 + beat * 2) << 32) |
              BigInt(0x1000 + id * 0x100 + beat * 2)
          assert(matches.head._3 == expected)
          assert(matches.head._4 == (beat == CacheContract.BeatsPerLine - 1))
        }
      }
  }

  test("64-byte line writes split data and byte masks into sixteen AXI words") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-write")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-line-write", 0x4c65) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)
        assert(dut.io.idle.toBoolean)

        val words = (0 until 16).map(index => BigInt("a5000000", 16) | index)
        val line =
          words.zipWithIndex.map { case (word, index) => word << (index * 32) }.reduce(_ | _)
        val masks = (0 until 16).map(index => BigInt(index & 0xf) << (index * 4)).reduce(_ | _)
        dut.io.memoryWriteValid #= true
        dut.io.memoryWriteLineAddress #= 0x8000
        for (word <- 0 until CacheContract.LineBytes / 4) {
          dut.io.memoryWriteDataWords(word) #= (line >> (word * 32)) & BigInt("ffffffff", 16)
        }
        dut.io.memoryWriteByteMask #= masks
        dut.io.memoryWriteMshrId #= 2
        sleep(1)
        assert(dut.io.memoryWriteReady.toBoolean)
        sample(dut)
        dut.io.memoryWriteValid #= false
        assert(!dut.io.idle.toBoolean)

        assert(dut.io.axi.aw.valid.toBoolean)
        assert(dut.io.axi.aw.payload.address.toBigInt == 0x8000)
        assert(dut.io.axi.aw.payload.len.toBigInt == 15)
        for (_ <- 0 until 3) {
          sample(dut)
          assert(dut.io.axi.aw.valid.toBoolean)
          assert(!dut.io.idle.toBoolean)
        }
        dut.io.axi.aw.ready #= true
        sample(dut)
        dut.io.axi.aw.ready #= false

        val random = new Random(0x4c65)
        for (word <- 0 until 16) {
          dut.io.axi.w.ready #= false
          for (_ <- 0 until random.nextInt(4)) {
            sample(dut)
            assert(dut.io.axi.w.valid.toBoolean)
            assert(!dut.io.idle.toBoolean)
          }
          dut.io.axi.w.ready #= true
          sleep(1)
          assert(dut.io.axi.w.valid.toBoolean)
          assert(dut.io.axi.w.payload.data.toBigInt == words(word))
          assert(dut.io.axi.w.payload.byteMask.toBigInt == (word & 0xf))
          assert(dut.io.axi.w.payload.last.toBoolean == (word == 15))
          assert(!dut.io.idle.toBoolean)
          sample(dut)
        }
        dut.io.axi.w.ready #= false
        assert(dut.io.axi.b.ready.toBoolean)
        for (_ <- 0 until 7) {
          sample(dut)
          assert(dut.io.axi.b.ready.toBoolean)
          assert(!dut.io.idle.toBoolean)
        }
        dut.io.axi.b.valid #= true
        sample(dut)
        dut.io.axi.b.valid #= false
        assert(!dut.io.idle.toBoolean)
        var idleWait = 0
        while (!dut.io.idle.toBoolean && idleWait < 4) {
          sample(dut)
          idleWait += 1
        }
        assert(dut.io.idle.toBoolean)
        assert(dut.io.memoryWriteReady.toBoolean)
      }
  }

  test("cached line writes report the matching AXI B error") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-write-response")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-line-write-response-error", 0x4c7c) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.memoryWriteValid #= true
        dut.io.memoryWriteLineAddress #= 0x8400
        dut.io.memoryWriteByteMask #= (BigInt(1) << CacheContract.LineBytes) - 1
        dut.io.memoryWriteMshrId #= 3
        sleep(1)
        assert(dut.io.memoryWriteReady.toBoolean)
        sample(dut)
        dut.io.memoryWriteValid #= false

        dut.io.axi.aw.ready #= true
        sample(dut)
        dut.io.axi.aw.ready #= false
        dut.io.axi.w.ready #= true
        for (_ <- 0 until CacheContract.LineBytes / 4) sample(dut)
        dut.io.axi.w.ready #= false
        assert(dut.io.axi.b.ready.toBoolean)
        assert(!dut.io.memoryWriteResponseValid.toBoolean)

        dut.io.axi.b.valid #= true
        dut.io.axi.b.payload.id #= 1
        dut.io.axi.b.payload.response #= 2
        sample(dut)
        dut.io.axi.b.valid #= false
        assert(dut.io.memoryWriteResponseValid.toBoolean)
        assert(dut.io.memoryWriteResponse.mshrId.toBigInt == 3)
        assert(dut.io.memoryWriteResponse.error.toBoolean)
        sample(dut)
        assert(!dut.io.memoryWriteResponseValid.toBoolean)
      }
  }

  test("uncached instruction fetches use one aligned four-word AXI burst") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-read")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-uncached-instruction-read", 0x4c67) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.uncachedInstructionRequestValid #= true
        dut.io.uncachedInstructionRequest.virtualAddress #= BigInt("1c00100c", 16)
        dut.io.uncachedInstructionRequest.physicalAddress #= 0x100c
        sleep(1)
        assert(dut.io.uncachedInstructionRequestReady.toBoolean)
        sample(dut)
        dut.io.uncachedInstructionRequestValid #= false

        assert(dut.io.axi.ar.valid.toBoolean)
        assert(dut.io.axi.ar.payload.id.toBigInt == 2)
        assert(dut.io.axi.ar.payload.address.toBigInt == 0x1000)
        assert(dut.io.axi.ar.payload.len.toBigInt == 3)
        assert(dut.io.axi.ar.payload.size.toBigInt == 2)
        dut.io.axi.ar.ready #= true
        sample(dut)
        dut.io.axi.ar.ready #= false

        for (word <- 0 until config.fetchWidth) {
          dut.io.axi.r.valid #= true
          dut.io.axi.r.payload.id #= 2
          dut.io.axi.r.payload.data #= 0x600 + word
          dut.io.axi.r.payload.response #= 0
          dut.io.axi.r.payload.last #= word == config.fetchWidth - 1
          sleep(1)
          assert(dut.io.axi.r.ready.toBoolean)
          sample(dut)
        }
        dut.io.axi.r.valid #= false
        assert(dut.io.uncachedInstructionResponseValid.toBoolean)
        assert(
          dut.io.uncachedInstructionResponse.virtualAddress.toBigInt ==
            BigInt("1c00100c", 16)
        )
        assert(dut.io.uncachedInstructionResponse.physicalAddress.toBigInt == 0x100c)
        assert(!dut.io.uncachedInstructionResponse.error.toBoolean)
        for (word <- 0 until config.fetchWidth) {
          assert(dut.io.uncachedInstructionResponse.instructions(word).toBigInt == 0x600 + word)
        }
      }
  }

  test("uncached data accesses preserve size and wait for the write response") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-write")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-uncached-data", 0x4c68) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.uncachedDataRequestValid #= true
        dut.io.uncachedDataRequest.physicalAddress #= BigInt("1fe00101", 16)
        dut.io.uncachedDataRequest.size #= 0
        dut.io.uncachedDataRequest.robPointer #= 17
        dut.io.uncachedDataRequest.recoveryEpoch #= 29
        dut.io.uncachedDataRequest.pdst #= 23
        dut.io.uncachedDataRequest.loadQueueIndex #= 6
        sleep(1)
        assert(dut.io.uncachedDataRequestReady.toBoolean)
        sample(dut)
        dut.io.uncachedDataRequestValid #= false

        assert(dut.io.axi.ar.valid.toBoolean)
        assert(dut.io.axi.ar.payload.id.toBigInt == 3)
        assert(dut.io.axi.ar.payload.address.toBigInt == BigInt("1fe00101", 16))
        assert(dut.io.axi.ar.payload.len.toBigInt == 0)
        assert(dut.io.axi.ar.payload.size.toBigInt == 0)
        dut.io.axi.ar.ready #= true
        sample(dut)
        dut.io.axi.ar.ready #= false
        dut.io.axi.r.valid #= true
        dut.io.axi.r.payload.id #= 3
        dut.io.axi.r.payload.data #= BigInt("00005a00", 16)
        dut.io.axi.r.payload.response #= 0
        dut.io.axi.r.payload.last #= true
        sample(dut)
        dut.io.axi.r.valid #= false
        assert(dut.io.uncachedDataResponseValid.toBoolean)
        assert(dut.io.uncachedDataResponse.robPointer.toBigInt == 17)
        assert(dut.io.uncachedDataResponse.recoveryEpoch.toBigInt == 29)
        assert(dut.io.uncachedDataResponse.pdst.toBigInt == 23)
        assert(dut.io.uncachedDataResponse.loadQueueIndex.toBigInt == 6)
        assert(dut.io.uncachedDataResponse.data.toBigInt == BigInt("00005a00", 16))

        sample(dut)
        dut.io.uncachedDataRequestValid #= true
        dut.io.uncachedDataRequest.isWrite #= true
        dut.io.uncachedDataRequest.physicalAddress #= BigInt("1fe00102", 16)
        dut.io.uncachedDataRequest.size #= 1
        dut.io.uncachedDataRequest.byteMask #= 0xc
        dut.io.uncachedDataRequest.writeData #= BigInt("abcd0000", 16)
        dut.io.uncachedDataRequest.robPointer #= 18
        dut.io.uncachedDataRequest.recoveryEpoch #= 30
        dut.io.uncachedDataRequest.pdst #= 0
        dut.io.uncachedDataRequest.loadQueueIndex #= 0
        sleep(1)
        assert(dut.io.uncachedDataRequestReady.toBoolean)
        sample(dut)
        dut.io.uncachedDataRequestValid #= false

        assert(dut.io.axi.aw.valid.toBoolean)
        assert(dut.io.axi.aw.payload.id.toBigInt == 3)
        assert(dut.io.axi.aw.payload.address.toBigInt == BigInt("1fe00102", 16))
        assert(dut.io.axi.aw.payload.len.toBigInt == 0)
        assert(dut.io.axi.aw.payload.size.toBigInt == 1)
        dut.io.axi.aw.ready #= true
        sample(dut)
        dut.io.axi.aw.ready #= false
        dut.io.axi.w.ready #= true
        sleep(1)
        assert(dut.io.axi.w.valid.toBoolean)
        assert(dut.io.axi.w.payload.id.toBigInt == 3)
        assert(dut.io.axi.w.payload.data.toBigInt == BigInt("abcd0000", 16))
        assert(dut.io.axi.w.payload.byteMask.toBigInt == 0xc)
        assert(dut.io.axi.w.payload.last.toBoolean)
        sample(dut)
        dut.io.axi.w.ready #= false
        assert(dut.io.axi.b.ready.toBoolean)
        assert(!dut.io.uncachedDataRequestReady.toBoolean)
        assert(!dut.io.uncachedDataResponseValid.toBoolean)
        dut.io.axi.b.valid #= true
        dut.io.axi.b.payload.id #= 3
        dut.io.axi.b.payload.response #= 0
        sample(dut)
        dut.io.axi.b.valid #= false
        assert(dut.io.uncachedDataResponseValid.toBoolean)
        assert(dut.io.uncachedDataResponse.robPointer.toBigInt == 18)
        assert(dut.io.uncachedDataResponse.recoveryEpoch.toBigInt == 30)
        assert(!dut.io.uncachedDataResponse.error.toBoolean)
      }
  }

  test("uncached write B errors retain the request token") {
    SimConfig.withVerilator
      .workspacePath(sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") + "/sim-workspace-ooo-axi-line-write-error")
      .compile(new AxiLineBridgeProbe(config))
      .doSim("ooo-axi-uncached-write-error", 0x4c69) { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        clearInputs(dut)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(2)
        dut.clockDomain.deassertReset()
        sample(dut)

        dut.io.uncachedDataRequestValid #= true
        dut.io.uncachedDataRequest.isWrite #= true
        dut.io.uncachedDataRequest.physicalAddress #= BigInt("1fe00100", 16)
        dut.io.uncachedDataRequest.robPointer #= 21
        dut.io.uncachedDataRequest.recoveryEpoch #= 7
        sleep(1)
        assert(dut.io.uncachedDataRequestReady.toBoolean)
        sample(dut)
        dut.io.uncachedDataRequestValid #= false

        dut.io.axi.aw.ready #= true
        sample(dut)
        dut.io.axi.aw.ready #= false
        dut.io.axi.w.ready #= true
        sample(dut)
        dut.io.axi.w.ready #= false
        assert(dut.io.axi.b.ready.toBoolean)
        assert(!dut.io.uncachedDataResponseValid.toBoolean)

        dut.io.axi.b.valid #= true
        dut.io.axi.b.payload.id #= 2
        dut.io.axi.b.payload.response #= 2
        sample(dut)
        dut.io.axi.b.valid #= false
        assert(dut.io.uncachedDataResponseValid.toBoolean)
        assert(dut.io.uncachedDataResponse.robPointer.toBigInt == 21)
        assert(dut.io.uncachedDataResponse.recoveryEpoch.toBigInt == 7)
        assert(dut.io.uncachedDataResponse.error.toBoolean)
      }
  }
}
