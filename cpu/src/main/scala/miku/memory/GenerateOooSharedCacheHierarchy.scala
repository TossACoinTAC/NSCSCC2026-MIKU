package miku.memory

import miku.core._
import spinal.core._

object GenerateOooSharedCacheHierarchy {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1)
      else "target/ooo-vivado/shared-cache-hierarchy"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val top = new OooSharedCacheHierarchy(OooCoreConfig.FourIssueThreeCommit)
      top.setDefinitionName("ooo_shared_cache_hierarchy")
      top
    }
  }
}
