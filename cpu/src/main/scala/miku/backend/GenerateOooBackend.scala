package miku.backend

import miku.core._
import spinal.core._

object GenerateOooBackend {
  def main(args: Array[String]): Unit = {
    val outIndex = args.indexOf("--out-dir")
    val targetDirectory =
      if (outIndex >= 0 && outIndex + 1 < args.length) args(outIndex + 1) else "target/ooo-vivado"
    val spinalConfig = SpinalConfig(
      targetDirectory = targetDirectory,
      oneFilePerComponent = false,
      headerWithDate = false,
      headerWithRepoHash = false
    )
    spinalConfig.withTimescale = false
    spinalConfig.generateVerilog {
      val backend = new OooBackendWithExecution(OooCoreConfig.FourIssueThreeCommit)
      backend.setDefinitionName("ooo_backend_with_execution")
      backend
    }
  }
}
