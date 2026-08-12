package miku.memory

import spinal.core._
import spinal.lib._

/** CPU-side memory request. This payload is directionless; ownership is declared by MemoryPort.
  *
  * A request with `prefetchValid` or `cacheOpValid` is a one-way PRELD/CACOP command: after
  * `request.fire`, no [[MemRsp]] is owed. Every ordinary load/store request must produce exactly
  * one in-order response.
  */
final case class MemReq() extends Bundle {
  val virtualAddress = UInt(32 bits)
  val physicalAddress = UInt(32 bits)
  val isWrite = Bool()
  val size = Bits(3 bits)
  val byteMask = Bits(4 bits)
  val writeData = Bits(32 bits)
  val isUncached = Bool()
  val cacheOpValid = Bool()
  val cacheOpMode = Bits(2 bits)
  val cacheOpAddress = UInt(32 bits)
  val prefetchValid = Bool()
  val prefetchHint = Bits(5 bits)
}

/** In-order CPU-side memory response. The legacy cache response cannot be backpressured. */
final case class MemRsp() extends Bundle {
  val readData = Bits(32 bits)
}

/** CPU-to-memory contract.
  *
  * `cancel` is an event sideband independent of the request handshake. It may arrive after a
  * request fires and only squashes work that has not produced an irreversible side effect. A
  * producer must not represent cancellation by withdrawing `request.valid` while stalled.
  */
final case class MemoryPort() extends Bundle with IMasterSlave {
  val request = Stream(MemReq())
  val response = Flow(MemRsp())
  val cancel = Bool()

  override def asMaster(): Unit = {
    master(request)
    slave(response)
    out(cancel)
  }
}

/** Memory quiescence observed by ID for DBAR/IBAR ordering.
  *
  * This is deliberately separate from [[MemReq]]: it describes subsystem state, not one memory
  * transaction. The memory subsystem is the master/provider and the pipeline is the slave/consumer.
  */
final case class MemoryStatus() extends Bundle with IMasterSlave {
  val writeBufferEmpty = Bool()
  val dataCacheEmpty = Bool()

  override def asMaster(): Unit = {
    out(writeBufferEmpty)
    out(dataCacheEmpty)
  }
}

/** Locked request-type encodings used by the active MIKU cache/AXI bridge. */
object LineRequestType {
  val Byte: Int = 0
  val HalfWord: Int = 1
  val Word: Int = 2
  val CacheLine: Int = 4
}

/** Cache-line read request. `requestType == 3'b100` denotes a four-word line transfer. */
final case class LineReq() extends Bundle {
  val requestType = Bits(3 bits)
  val address = UInt(32 bits)
}

/** One read-return beat. A cache-line transfer completes when `last` is asserted. */
final case class LineRsp() extends Bundle {
  val data = Bits(32 bits)
  val last = Bool()
}

/** Cache-line or scalar write request; line data remains a distinct 128-bit contract. */
final case class LineWriteReq() extends Bundle {
  val requestType = Bits(3 bits)
  val address = UInt(32 bits)
  val byteMask = Bits(4 bits)
  val data = Bits(128 bits)
}

/** Instruction-cache transaction contract. The active I-cache never issues a write request. */
final case class LineReadPort() extends Bundle with IMasterSlave {
  val read = Stream(LineReq())
  val readResponse = Flow(LineRsp())

  override def asMaster(): Unit = {
    master(read)
    slave(readResponse)
  }
}

/** Data-cache transaction contract. Read and write requests remain independent channels. */
final case class LineReadWritePort() extends Bundle with IMasterSlave {
  val read = Stream(LineReq())
  val readResponse = Flow(LineRsp())
  val write = Stream(LineWriteReq())

  override def asMaster(): Unit = {
    master(read)
    slave(readResponse)
    master(write)
  }
}
