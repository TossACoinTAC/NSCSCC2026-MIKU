package miku.memory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters._

class OpenLa500ICacheSpec extends AnyFunSuite {
  test("generator emits one exact legacy icache module") {
    val outputDirectory = Files.createTempDirectory("miku-icache-rtl-")
    try {
      GenerateOpenLa500ICache.main(Array(outputDirectory.toString))
      val rtl = Files.readString(outputDirectory.resolve("icache.v"), StandardCharsets.UTF_8)
      val header = "(?s)module\\s+icache\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain icache"))
      val ports =
        "(?m)^\\s*(?:input|output)\\s+wire(?:\\s+\\[[^]]+\\])?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*$".r
          .findAllMatchIn(header)
          .map(_.group(1))
          .toSeq
      assert(
        ports == Seq(
          "clk",
          "reset",
          "valid",
          "op",
          "index",
          "tag",
          "offset",
          "wstrb",
          "wdata",
          "addr_ok",
          "data_ok",
          "rdata",
          "uncache_en",
          "icacop_op_en",
          "cacop_op_mode",
          "cacop_op_addr_index",
          "cacop_op_addr_tag",
          "cacop_op_addr_offset",
          "icache_unbusy",
          "tlb_excp_cancel_req",
          "rd_req",
          "rd_type",
          "rd_addr",
          "rd_rdy",
          "ret_valid",
          "ret_last",
          "ret_data",
          "wr_req",
          "wr_type",
          "wr_addr",
          "wr_wstrb",
          "wr_data",
          "wr_rdy",
          "cache_miss"
        )
      )
      assert(!header.contains("io_"))
      assert(!rtl.contains("`timescale"))
      assert("(?m)^\\s*module\\s+".r.findAllIn(rtl).length == 1)
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
