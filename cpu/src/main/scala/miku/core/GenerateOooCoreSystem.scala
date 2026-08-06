package miku.core

import spinal.core._

object GenerateOooCoreSystem {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1)
      else "target/ooo-system"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val top = new OooCoreSystem(OooCoreConfig.FourIssueThreeCommit)
      top.setDefinitionName("ooo_core_system")
      top
    }
  }
}
