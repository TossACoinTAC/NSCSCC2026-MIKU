package miku.backend

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class OooBackendWithDataCacheSpec extends AnyFunSuite {
  test("the OoO backend elaborates with the shared 64-byte L1I, L1D, and L2 hierarchy") {
    val outputDirectory = Files.createTempDirectory("ooo-backend-data-cache-")
    val spinalConfig = SpinalConfig(
      targetDirectory = outputDirectory.toString,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val top = new OooBackendWithDataCache(OooCoreConfig.FourIssueThreeCommit)
      top.setDefinitionName("ooo_backend_with_data_cache")
      top
    }

    val rtl = Files.readString(
      outputDirectory.resolve("ooo_backend_with_data_cache.v"),
      StandardCharsets.UTF_8
    )
    assert(rtl.contains("module ooo_backend_with_data_cache"))
    assert(rtl.contains("module OooBackendWithExecution"))
    assert(rtl.contains("module OooL1InstructionCache"))
    assert(rtl.contains("module OooL1DataCache"))
    assert(rtl.contains("module OooL2Cache"))
    assert(rtl.contains("instructionRequestValid"))
    assert(rtl.contains("instructionKill"))
    assert(rtl.contains("memoryReadValid"))
    assert(rtl.contains("cacheInvalidateBusy"))
  }
}
