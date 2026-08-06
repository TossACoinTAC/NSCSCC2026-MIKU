package miku.backend

import miku.core._
import spinal.core._

object GenerateOooBackendWithDataCache {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1)
      else "target/ooo-vivado/backend-with-data-cache"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
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
  }
}
