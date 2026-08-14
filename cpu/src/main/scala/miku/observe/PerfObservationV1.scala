package miku.observe

import spinal.core._
import spinal.core.sim.SimPublic

/** Versioned, simulation-only observation ABI.
  *
  * The words are deliberately the only generated RTL names consumed by the
  * external performance monitor.  Vivado can prune the read-only cone because
  * it has no architectural output, while Verilator keeps the named words for
  * the instrumented model.
  */
object PerfObservationV1 {
  val Magic: BigInt = BigInt("4d494b55", 16) // "MIKU"
  val Version: Int = 1
  val WordWidth: Int = 64
  val WordCount: Int = 8

  def expose(word: Bits, index: Int): Unit = {
    require(index >= 0 && index < WordCount)
    require(word.getBitsWidth == WordWidth)
    word.setName(s"perfObservationV1Word$index")
    SimPublic(word)
    word.allowPruning()
  }
}
