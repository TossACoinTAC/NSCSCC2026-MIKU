package miku.privileged

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import spinal.core._

private object AddrTransGenerator {
  private def addLockedParameter(path: java.nio.file.Path, module: String): Unit = {
    val source = Files.readString(path, StandardCharsets.US_ASCII)
    val marker = s"module $module ("
    val replacement = s"module $module #(\n  parameter integer TLBNUM = 32\n) ("
    require(source.indexOf(marker) >= 0 && source.indexOf(marker) == source.lastIndexOf(marker))
    Files.writeString(path, source.replace(marker, replacement), StandardCharsets.US_ASCII)
  }

  private def parameterizeTlb(path: java.nio.file.Path, module: String): Unit = {
    addLockedParameter(path, module)
    val source = Files.readString(path, StandardCharsets.US_ASCII)
    val writeIndex = "input  wire [4:0]    w_index,"
    val readIndex = "input  wire [4:0]    r_index,"
    require(source.contains(writeIndex) && source.contains(readIndex))
    val rewritten = source
      .replace(writeIndex, "input  wire [$clog2(TLBNUM)-1:0] w_index,")
      .replace(readIndex, "input  wire [$clog2(TLBNUM)-1:0] r_index,")
    Files.writeString(path, rewritten, StandardCharsets.US_ASCII)
  }

  private def output(args: Array[String]): String = args match {
    case Array(path) if path.nonEmpty              => path
    case Array("--out-dir", path) if path.nonEmpty => path
    case _ => throw new IllegalArgumentException("usage: generator [--out-dir] <directory>")
  }

  private def config(directory: String): SpinalConfig = {
    Files.createDirectories(Paths.get(directory))
    val spinal = SpinalConfig(
      targetDirectory = directory,
      oneFilePerComponent = false,
      headerWithDate = false,
      anonymSignalPrefix = "tmp"
    )
    spinal.withTimescale = false
    spinal
  }

  def generateTlb(args: Array[String]): Unit = {
    val directory = output(args)
    config(directory).generateVerilog(new TlbArray())
    parameterizeTlb(Paths.get(directory).resolve("tlb_entry.v"), "tlb_entry")
  }

  def generateAddrTrans(args: Array[String]): Unit = {
    val directory = output(args)
    config(directory).generateVerilog {
      val dut = new LegacyAddressTranslator
      dut.setDefinitionName("addr_trans")
      dut
    }
    val rtl = Paths.get(directory).resolve("addr_trans.v")
    addLockedParameter(rtl, "addr_trans")
    parameterizeTlb(rtl, "tlb_array_impl")
    val source = Files.readString(rtl, StandardCharsets.US_ASCII)
    val instance = "tlb_array_impl tlb ("
    require(
      source.indexOf(instance) >= 0 && source.indexOf(instance) == source.lastIndexOf(instance)
    )
    Files.writeString(
      rtl,
      source.replace(instance, "tlb_array_impl #(.TLBNUM(TLBNUM)) tlb ("),
      StandardCharsets.US_ASCII
    )
  }
}

object GenerateTlbArray {
  def main(args: Array[String]): Unit = AddrTransGenerator.generateTlb(args)
}

object GenerateLegacyAddressTranslator {
  def main(args: Array[String]): Unit = AddrTransGenerator.generateAddrTrans(args)
}
