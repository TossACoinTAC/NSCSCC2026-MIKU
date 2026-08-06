package miku.compat

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import scala.jdk.CollectionConverters._

private final class Axi3ManifestTop extends Component {
  val io = new Bundle {
    val axi = Axi3Compat()
    assert(axi.ar.payload.flatten.forall(_.isDirectionLess))
    assert(axi.r.payload.flatten.forall(_.isDirectionLess))
    assert(axi.aw.payload.flatten.forall(_.isDirectionLess))
    assert(axi.w.payload.flatten.forall(_.isDirectionLess))
    assert(axi.b.payload.flatten.forall(_.isDirectionLess))
    assert(axi.ar.payload.id.getBitsWidth == 4)
    assert(axi.ar.payload.address.getBitsWidth == 32)
    assert(axi.ar.payload.len.getBitsWidth == 8)
    assert(axi.aw.payload.id.getBitsWidth == 4)
    assert(axi.aw.payload.address.getBitsWidth == 32)
    assert(axi.aw.payload.len.getBitsWidth == 8)
    assert(axi.w.payload.id.getBitsWidth == 4)
    assert(axi.w.payload.data.getBitsWidth == 32)
    assert(axi.w.payload.byteMask.getBitsWidth == 4)
    master(axi)
  }
  noIoPrefix()

  io.axi.ar.valid := False
  io.axi.ar.payload.assignDontCare()
  io.axi.r.ready := False
  io.axi.aw.valid := False
  io.axi.aw.payload.assignDontCare()
  io.axi.w.valid := False
  io.axi.w.payload.assignDontCare()
  io.axi.b.ready := False
}

class Axi3CompatSpec extends AnyFunSuite {
  test("five directionless AXI3 payloads flatten to the chiplab master port manifest") {
    val outputDirectory = Files.createTempDirectory("axi3-compat-rtl-")
    try {
      SpinalConfig(targetDirectory = outputDirectory.toString).generateVerilog(new Axi3ManifestTop)
      val rtl =
        Files.readString(outputDirectory.resolve("Axi3ManifestTop.v"), StandardCharsets.UTF_8)
      val moduleHeader = "(?s)module\\s+Axi3ManifestTop\\s*\\((.*?)\\);".r
        .findFirstMatchIn(rtl)
        .map(_.group(1))
        .getOrElse(fail("generated RTL does not contain Axi3ManifestTop"))

      val declarations =
        "(?m)^\\s*(input|output)\\s+wire(?:\\s+\\[([^]]+)\\])?\\s+(axi_[A-Za-z0-9_]+)\\s*,?\\s*$".r
          .findAllMatchIn(moduleHeader)
          .map { entry =>
            val width = Option(entry.group(2)).fold(1) { range =>
              val bounds = range.split(":").map(_.trim.toInt)
              bounds(0) - bounds(1) + 1
            }
            entry.group(3) -> (entry.group(1), width)
          }
          .toMap

      val expected = Map(
        "axi_ar_valid" -> ("output", 1),
        "axi_ar_ready" -> ("input", 1),
        "axi_ar_payload_id" -> ("output", 4),
        "axi_ar_payload_address" -> ("output", 32),
        "axi_ar_payload_len" -> ("output", 8),
        "axi_ar_payload_size" -> ("output", 3),
        "axi_ar_payload_burst" -> ("output", 2),
        "axi_ar_payload_lock" -> ("output", 2),
        "axi_ar_payload_cache" -> ("output", 4),
        "axi_ar_payload_prot" -> ("output", 3),
        "axi_r_valid" -> ("input", 1),
        "axi_r_ready" -> ("output", 1),
        "axi_r_payload_id" -> ("input", 4),
        "axi_r_payload_data" -> ("input", 32),
        "axi_r_payload_response" -> ("input", 2),
        "axi_r_payload_last" -> ("input", 1),
        "axi_aw_valid" -> ("output", 1),
        "axi_aw_ready" -> ("input", 1),
        "axi_aw_payload_id" -> ("output", 4),
        "axi_aw_payload_address" -> ("output", 32),
        "axi_aw_payload_len" -> ("output", 8),
        "axi_aw_payload_size" -> ("output", 3),
        "axi_aw_payload_burst" -> ("output", 2),
        "axi_aw_payload_lock" -> ("output", 2),
        "axi_aw_payload_cache" -> ("output", 4),
        "axi_aw_payload_prot" -> ("output", 3),
        "axi_w_valid" -> ("output", 1),
        "axi_w_ready" -> ("input", 1),
        "axi_w_payload_id" -> ("output", 4),
        "axi_w_payload_data" -> ("output", 32),
        "axi_w_payload_byteMask" -> ("output", 4),
        "axi_w_payload_last" -> ("output", 1),
        "axi_b_valid" -> ("input", 1),
        "axi_b_ready" -> ("output", 1),
        "axi_b_payload_id" -> ("input", 4),
        "axi_b_payload_response" -> ("input", 2)
      )

      assert(declarations == expected)
      assert(!rtl.contains("Axi4"))
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
