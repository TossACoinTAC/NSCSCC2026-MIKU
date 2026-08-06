package miku.core

import spinal.core._

object GenerateOooCore {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1)
      else "target/ooo-vivado/core"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val top = new OooCore(OooCoreConfig.FourIssueThreeCommit)
      top.setDefinitionName("ooo_core")
      top
    }
  }
}
