package miku.memory

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import scala.jdk.CollectionConverters._

private final class MemoryContractsProbe extends Component {
  val io = new Bundle {
    val memory = MemoryPort()
    assert(memory.request.payload.flatten.forall(_.isDirectionLess))
    assert(memory.request.payload.virtualAddress.getBitsWidth == 32)
    assert(memory.request.payload.physicalAddress.getBitsWidth == 32)
    assert(memory.request.payload.isWrite.getBitsWidth == 1)
    assert(memory.request.payload.size.getBitsWidth == 3)
    assert(memory.request.payload.byteMask.getBitsWidth == 4)
    assert(memory.request.payload.writeData.getBitsWidth == 32)
    assert(memory.request.payload.isUncached.getBitsWidth == 1)
    assert(memory.request.payload.cacheOpValid.getBitsWidth == 1)
    assert(memory.request.payload.cacheOpMode.getBitsWidth == 2)
    assert(memory.request.payload.cacheOpAddress.getBitsWidth == 32)
    assert(memory.request.payload.prefetchValid.getBitsWidth == 1)
    assert(memory.request.payload.prefetchHint.getBitsWidth == 5)
    assert(memory.response.payload.flatten.forall(_.isDirectionLess))
    assert(memory.response.payload.readData.getBitsWidth == 32)
    master(memory)

    val statusProvider = master(MemoryStatus())
    val statusConsumer = slave(MemoryStatus())

    val instructionLine = LineReadPort()
    assert(instructionLine.read.payload.flatten.forall(_.isDirectionLess))
    assert(instructionLine.readResponse.payload.flatten.forall(_.isDirectionLess))
    assert(!instructionLine.elements.contains("write"))
    master(instructionLine)

    val line = LineReadWritePort()
    assert(line.read.payload.flatten.forall(_.isDirectionLess))
    assert(line.read.payload.requestType.getBitsWidth == 3)
    assert(line.read.payload.address.getBitsWidth == 32)
    assert(line.readResponse.payload.flatten.forall(_.isDirectionLess))
    assert(line.readResponse.payload.data.getBitsWidth == 32)
    assert(line.readResponse.payload.last.getBitsWidth == 1)
    assert(line.write.payload.flatten.forall(_.isDirectionLess))
    assert(line.write.payload.requestType.getBitsWidth == 3)
    assert(line.write.payload.address.getBitsWidth == 32)
    assert(line.write.payload.byteMask.getBitsWidth == 4)
    assert(line.write.payload.data.getBitsWidth == 128)
    master(line)
  }
  noIoPrefix()

  assert(io.memory.request.valid.isOutput)
  assert(io.memory.request.ready.isInput)
  assert(io.memory.request.payload.flatten.forall(_.isOutput))
  assert(io.memory.response.valid.isInput)
  assert(io.memory.response.payload.flatten.forall(_.isInput))
  assert(io.memory.cancel.isOutput)
  assert(io.statusProvider.writeBufferEmpty.isOutput)
  assert(io.statusProvider.dataCacheEmpty.isOutput)
  assert(io.statusConsumer.writeBufferEmpty.isInput)
  assert(io.statusConsumer.dataCacheEmpty.isInput)
  assert(io.instructionLine.read.valid.isOutput)
  assert(io.instructionLine.read.ready.isInput)
  assert(io.instructionLine.readResponse.valid.isInput)
  assert(io.instructionLine.readResponse.payload.flatten.forall(_.isInput))
  assert(io.line.read.valid.isOutput)
  assert(io.line.read.ready.isInput)
  assert(io.line.read.payload.flatten.forall(_.isOutput))
  assert(io.line.readResponse.valid.isInput)
  assert(io.line.readResponse.payload.flatten.forall(_.isInput))
  assert(io.line.write.valid.isOutput)
  assert(io.line.write.ready.isInput)
  assert(io.line.write.payload.flatten.forall(_.isOutput))

  io.memory.request.valid := False
  io.memory.request.payload.assignDontCare()
  io.memory.cancel := False
  io.statusProvider.writeBufferEmpty := False
  io.statusProvider.dataCacheEmpty := False
  io.instructionLine.read.valid := False
  io.instructionLine.read.payload.assignDontCare()
  io.line.read.valid := False
  io.line.read.payload.assignDontCare()
  io.line.write.valid := False
  io.line.write.payload.assignDontCare()
}

class MemoryContractsSpec extends AnyFunSuite {
  test("memory and line payload widths and master directions are locked") {
    val outputDirectory = Files.createTempDirectory("memory-contracts-rtl-")
    try {
      SpinalConfig(targetDirectory = outputDirectory.toString)
        .generateVerilog(new MemoryContractsProbe)
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }

  test("cache-line request type preserves the active RTL 3'b100 encoding") {
    assert(LineRequestType.CacheLine == 4)
    assert((LineRequestType.CacheLine & 0x7) == Integer.parseInt("100", 2))
  }
}
