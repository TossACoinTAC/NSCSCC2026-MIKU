package miku.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import miku.backend._
import miku.execute._
import miku.frontend._
import miku.memory._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import scala.jdk.CollectionConverters._

class OooCoreSpec extends AnyFunSuite {
  private def elaborate(name: String)(component: => Component): String = {
    val outputDirectory = Files.createTempDirectory(s"$name-elaboration-")
    try {
      SpinalConfig(
        targetDirectory = outputDirectory.toString,
        oneFilePerComponent = false,
        headerWithDate = false,
        headerWithRepoHash = false
      ).generateVerilog(component)
      Files.readString(outputDirectory.resolve(s"$name.v"), StandardCharsets.UTF_8)
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }

  test("the initial backend locks four execution ports and three-wide retirement") {
    val config = OooCoreConfig.FourIssueThreeCommit
    assert(config.fetchWidth == 4)
    assert(config.renameWidth == 3)
    assert(config.executionWidth == 4)
    assert(config.writebackWidth == 5)
    assert(config.commitWidth == 3)
    assert(config.physicalRegs == 64)
    assert(config.robEntries == 32)
    assert(config.instructionBufferEntries == 16)
    assert(config.enableFrontendTranslationTurnover)
    assert(!config.enableFrontendHistoryTurnover)
    assert(config.enableFrontendCacheHitTurnover)
    assert(config.dispatchQueueEntries == 8)
    assert(config.mshrEntries == 4)
    assert(config.instructionCache.capacityBytes == 16384)
    assert(config.dataCache.capacityBytes == 16384)
    assert(config.level2Cache.capacityBytes == 65536)
    assert(config.instructionCache.lineBytes == 64)
    assert(config.dataCache.lineBytes == 64)
    assert(config.level2Cache.lineBytes == 64)
  }

  test("invalid width and cache configurations fail closed") {
    val invalid = Seq[() => Any](
      () => OooCoreConfig(fetchWidth = 3),
      () => OooCoreConfig(decodeWidth = 5),
      () => OooCoreConfig(renameWidth = 4),
      () => OooCoreConfig(commitWidth = 4),
      () => OooCoreConfig(physicalRegs = 48),
      () => OooCoreConfig(robEntries = 8),
      () => OooCoreConfig(instructionBufferEntries = 4),
      () => OooCoreConfig(dispatchQueueEntries = 4),
      () => OooCoreConfig(instructionCache = OooCacheGeometry(2, 64, 32)),
      () => OooCoreConfig(executionPorts = OooCoreConfig.DefaultExecutionPorts.dropRight(1))
    )
    invalid.foreach(make => intercept[IllegalArgumentException](make()))
  }

  test("the register-rename and retirement shell elaborates") {
    val rtl = elaborate("ooo_backend") {
      val backend = new OooBackend(OooCoreConfig.FourIssueThreeCommit)
      backend.setDefinitionName("ooo_backend")
      backend
    }
    assert(rtl.contains("module ooo_backend"))
    assert(rtl.contains("module OooRob"))
    assert(rtl.contains("module OooRegisterMap"))
    assert(rtl.contains("module OooFreeList"))
    assert(rtl.contains("module OooIssueQueue"))
  }

  test("the pure LA32R decoder elaborates independently of the legacy pipeline state") {
    val rtl = elaborate("ooo_la32r_decoder") {
      val decoder = new OooLa32rDecoder(OooCoreConfig.FourIssueThreeCommit)
      decoder.setDefinitionName("ooo_la32r_decoder")
      decoder
    }
    assert(rtl.contains("module ooo_la32r_decoder"))
    assert(rtl.contains("systemOperation"))
    assert(rtl.contains("mulDivOperation"))
  }

  test("the fetch/decode width adapter keeps the four-slot to three-uop boundary explicit") {
    val rtl = elaborate("ooo_wide_decode") {
      val decode = new OooWideDecode(OooCoreConfig.FourIssueThreeCommit)
      decode.setDefinitionName("ooo_wide_decode")
      decode
    }
    assert(rtl.contains("module ooo_wide_decode"))
    assert(rtl.contains("outputValid"))
  }

  test("the buffered frontend compacts four-slot cache groups into decode3") {
    val rtl = elaborate("ooo_frontend") {
      val frontend = new OooFrontend(OooCoreConfig.FourIssueThreeCommit)
      frontend.setDefinitionName("ooo_frontend")
      frontend
    }
    assert(rtl.contains("module ooo_frontend"))
    assert(rtl.contains("module OooWideDecode"))
    assert(rtl.contains("cacheKill"))
    assert(rtl.contains("decodeReady"))
  }

  test("the self-fetching OoO core closes the frontend, backend, and shared-cache path") {
    val rtl = elaborate("ooo_core") {
      val core = new OooCore(OooCoreConfig.FourIssueThreeCommit)
      core.setDefinitionName("ooo_core")
      core
    }
    assert(rtl.contains("module ooo_core"))
    assert(rtl.contains("module OooFrontend"))
    assert(rtl.contains("module OooDecodeRenameBuffer"))
    assert(rtl.contains("module OooBackendWithDataCache"))
    assert(rtl.contains("module OooSharedCacheHierarchy"))
    assert(rtl.contains("externalRedirectValid"))
    assert(rtl.contains("frontendOccupancy"))
  }

  test("the OoO core system owns CSR, TLB maintenance, and a 64-byte AXI bridge") {
    val rtl = elaborate("ooo_core_system") {
      val system = new OooCoreSystem(OooCoreConfig.FourIssueThreeCommit)
      system.setDefinitionName("ooo_core_system")
      system
    }
    assert(rtl.contains("module ooo_core_system"))
    assert(rtl.contains("module OooCore"))
    assert(rtl.contains("module OpenLa500Csr"))
    assert(rtl.contains("module OooHierarchicalTlb"))
    assert(rtl.contains("module OooAxiLineBridge"))
    assert(rtl.contains("axi_ar_payload_len"))
  }

  test("the four-port execution cluster elaborates with a fifth completion lane") {
    val rtl = elaborate("ooo_execution_cluster") {
      val execution = new OooExecutionCluster(OooCoreConfig.FourIssueThreeCommit)
      execution.setDefinitionName("ooo_execution_cluster")
      execution
    }
    assert(rtl.contains("module ooo_execution_cluster"))
    assert(rtl.contains("module OooDivideUnit"))
    assert(rtl.contains("module OooMultiplyPipe"))
    assert(rtl.contains("aguValid"))
  }

  test("the integrated backend exposes an ordered data-cache boundary") {
    val rtl = elaborate("ooo_backend_with_execution") {
      val backend = new OooBackendWithExecution(OooCoreConfig.FourIssueThreeCommit)
      backend.setDefinitionName("ooo_backend_with_execution")
      backend
    }
    assert(rtl.contains("module ooo_backend_with_execution"))
    assert(rtl.contains("module OooLoadStoreQueue"))
    assert(rtl.contains("dataRequestValid"))
    assert(rtl.contains("recoveryValid"))
  }

  test("ordered commit lanes reduce to one precise CSR/TLB side-effect stream") {
    val rtl = elaborate("ooo_commit_adapter") {
      val adapter = new OooCommitAdapter(OooCoreConfig.FourIssueThreeCommit)
      adapter.setDefinitionName("ooo_commit_adapter")
      adapter
    }
    assert(rtl.contains("module ooo_commit_adapter"))
    assert(rtl.contains("csrWriteValid"))
    assert(rtl.contains("exceptionValid"))
  }

  test("the 64-byte cache contract and four-entry MSHR table elaborate") {
    assert(OooCacheContract.LineBytes == 64)
    assert(OooCacheContract.LineBits == 512)
    assert(OooCacheContract.BeatsPerLine == 8)
    val rtl = elaborate("ooo_mshr_table") {
      val mshr = new OooMshrTable(OooCoreConfig.FourIssueThreeCommit)
      mshr.setDefinitionName("ooo_mshr_table")
      mshr
    }
    assert(rtl.contains("module ooo_mshr_table"))
    assert(rtl.contains("allocateMerged"))
  }

  test("L1 and L2 cache arrays retain BRAM-friendly 512-bit line storage") {
    val rtl = elaborate("ooo_l2_cache_array") {
      val l2 = new OooCacheArray(OooCoreConfig.FourIssueThreeCommit.level2Cache)
      l2.setDefinitionName("ooo_l2_cache_array")
      l2
    }
    assert(rtl.contains("module ooo_l2_cache_array"))
    assert(rtl.contains("lookupAddress"))
    assert(rtl.contains("writeData"))
  }

  test("four-slot L1I and L1D share one 64-byte L2 transaction boundary") {
    val rtl = elaborate("ooo_shared_cache_hierarchy") {
      val hierarchy = new OooSharedCacheHierarchy(OooCoreConfig.FourIssueThreeCommit)
      hierarchy.setDefinitionName("ooo_shared_cache_hierarchy")
      hierarchy
    }
    assert(rtl.contains("module ooo_shared_cache_hierarchy"))
    assert(rtl.contains("module OooL1InstructionCache"))
    assert(rtl.contains("module OooL1DataCache"))
    assert(rtl.contains("module OooSharedReadMshrRouter"))
    assert(rtl.contains("module OooL2Cache"))
    assert(rtl.contains("instructionKill"))
  }
}
