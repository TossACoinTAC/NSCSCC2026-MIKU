package miku.memory

import miku.core._
import spinal.core._

/** Blocking L1D/L2 hierarchy with a 64-byte line interface to external memory.
  *
  * The first integrated hierarchy deliberately keeps one outstanding data miss. This establishes
  * the complete writeback/refill path before the line protocol is widened to use all four MSHRs and
  * shared with L1I.
  */
final class OooDataCacheHierarchy(
    config: OooCoreConfig = OooCoreConfig.FourIssueThreeCommit
) extends Component {
  val io = new Bundle {
    val requestValid = in Bool ()
    val request = in(OooCacheRequest(config))
    val requestReady = out Bool ()
    val responseValid = out Bool ()
    val response = out(OooCacheResponse(config))

    val memoryReadValid = out Bool ()
    val memoryRead = out(OooLineReadRequest(config))
    val memoryReadReady = in Bool ()
    val memoryReadBeatValid = in Bool ()
    val memoryReadBeat = in(OooLineReadBeat(config))
    val memoryReadBeatReady = out Bool ()

    val memoryWriteValid = out Bool ()
    val memoryWrite = out(OooLineWriteRequest(config))
    val memoryWriteReady = in Bool ()
    val memoryWriteResponseValid = in Bool ()
    val memoryWriteResponse = in(OooLineWriteResponse(config))

    val invalidate = in Bool ()
    val invalidateBusy = out Bool ()
  }

  val l1d = new OooL1DataCache(config)
  val l2 = new OooL2Cache(config)

  l1d.io.requestValid := io.requestValid
  l1d.io.request := io.request
  io.requestReady := l1d.io.requestReady
  io.responseValid := l1d.io.responseValid
  io.response := l1d.io.response

  l2.io.readValid := l1d.io.lineReadValid
  l2.io.read := l1d.io.lineRead
  l1d.io.lineReadReady := l2.io.readReady
  l1d.io.lineReadBeatValid := l2.io.readBeatValid
  l1d.io.lineReadBeat := l2.io.readBeat
  l2.io.readBeatReady := l1d.io.lineReadBeatReady

  l2.io.writeValid := l1d.io.lineWriteValid
  l2.io.write := l1d.io.lineWrite
  l1d.io.lineWriteReady := l2.io.writeReady
  l1d.io.lineWriteResponseValid := l2.io.writeResponseValid
  l1d.io.lineWriteResponse := l2.io.writeResponse

  io.memoryReadValid := l2.io.memoryReadValid
  io.memoryRead := l2.io.memoryRead
  l2.io.memoryReadReady := io.memoryReadReady
  l2.io.memoryReadBeatValid := io.memoryReadBeatValid
  l2.io.memoryReadBeat := io.memoryReadBeat
  io.memoryReadBeatReady := l2.io.memoryReadBeatReady

  io.memoryWriteValid := l2.io.memoryWriteValid
  io.memoryWrite := l2.io.memoryWrite
  l2.io.memoryWriteReady := io.memoryWriteReady
  l2.io.memoryWriteResponseValid := io.memoryWriteResponseValid
  l2.io.memoryWriteResponse := io.memoryWriteResponse

  l1d.io.invalidate := io.invalidate
  l1d.io.writebackInvalidate := False
  l1d.io.maintenanceRequest.valid := False
  l1d.io.maintenanceRequest.payload.assignFromBits(
    B(0, l1d.io.maintenanceRequest.payload.getBitsWidth bits)
  )
  l2.io.invalidate := io.invalidate
  l2.io.writebackInvalidate := False
  l2.io.maintenanceRequest.valid := False
  l2.io.maintenanceRequest.payload.assignFromBits(
    B(0, l2.io.maintenanceRequest.payload.getBitsWidth bits)
  )
  io.invalidateBusy := l1d.io.invalidateBusy || l2.io.invalidateBusy
}
