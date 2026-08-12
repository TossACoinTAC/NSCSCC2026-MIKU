package miku.memory

import miku.core._
import spinal.core._

object GenerateDataCacheHierarchy {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1)
      else "target/ooo-vivado/data-cache-hierarchy"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val hierarchy = new DataCacheHierarchy(OooCoreConfig.FourIssueThreeCommit)
      hierarchy.setDefinitionName("ooo_data_cache_hierarchy")
      hierarchy
    }
  }
}
