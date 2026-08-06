package miku.observe

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import scala.jdk.CollectionConverters._

private final class CommitContractsProbe extends Component {
  private def assertDirectionless(data: Data): Unit =
    assert(
      data.flatten.forall(_.isDirectionLess),
      s"${data.getClass.getSimpleName} has directed leaves"
    )

  val io = new Bundle {
    val commit = CommitEvent.flow()
    assert(commit.valid.isDirectionLess)
    assertDirectionless(commit.payload)

    val event = commit.payload
    assert(event.pc.getBitsWidth == 32)
    assert(event.instruction.getBitsWidth == 32)
    assert(event.retired.getBitsWidth == 1)
    assert(event.ertn.getBitsWidth == 1)
    assert(event.isCounterInstruction.getBitsWidth == 1)
    assert(event.csrRstat.getBitsWidth == 1)
    assert(event.csrReadData.getBitsWidth == 32)
    assert(event.timer.getBitsWidth == 64)
    assert(event.retired ne event.exception.valid)
    assert(event.retired ne event.ertn)
    assert(event.isCounterInstruction ne event.timer)
    assert(event.csrRstat ne event.csrWrite.valid)
    assert(event.csrReadData ne event.csrWrite.data)
    assert(event.getBitsWidth == 505)

    val gprWrite = event.gprWrite
    assert(gprWrite.valid.getBitsWidth == 1)
    assert(gprWrite.index.getBitsWidth == 5)
    assert(gprWrite.data.getBitsWidth == 32)
    assert(gprWrite.getBitsWidth == 38)

    val csrWrite = event.csrWrite
    assert(csrWrite.valid.getBitsWidth == 1)
    assert(csrWrite.address.getBitsWidth == 14)
    assert(csrWrite.data.getBitsWidth == 32)
    assert(csrWrite.getBitsWidth == 47)

    val exception = event.exception
    assert(exception.ecode.getBitsWidth == 6)
    assert(exception.esubcode.getBitsWidth == 9)
    assert(exception.badVAddr.getBitsWidth == 32)
    assert(exception.tlbVppn.getBitsWidth == 19)
    assert(exception.getBitsWidth == 70)

    val load = event.load
    assert(load.instructionMask.getBitsWidth == 8)
    assert(load.pAddr.getBitsWidth == 32)
    assert(load.vAddr.getBitsWidth == 32)
    assert(load.getBitsWidth == 72)

    val store = event.store
    assert(store.instructionMask.getBitsWidth == 8)
    assert(store.pAddr.getBitsWidth == 32)
    assert(store.vAddr.getBitsWidth == 32)
    assert(store.data.getBitsWidth == 32)
    assert(store.byteMask.getBitsWidth == 4)
    assert(store.getBitsWidth == 108)

    val tlbFill = event.tlbFill
    assert(tlbFill.valid.getBitsWidth == 1)
    assert(tlbFill.index.getBitsWidth == 5)
    assert(tlbFill.getBitsWidth == 6)

    master(commit)

    val archState = ArchState()
    assertDirectionless(archState)
    assert(archState.gpr.length == 32)
    assert(archState.gpr.forall(_.getBitsWidth == 32))
    assert(archState.x0 eq archState.gpr(0))
    assert(archState.x0IsZero.getBitsWidth == 1)

    val csrState = Seq(
      archState.crmd,
      archState.prmd,
      archState.euen,
      archState.ecfg,
      archState.estat,
      archState.era,
      archState.badv,
      archState.eentry,
      archState.tlbidx,
      archState.tlbehi,
      archState.tlbelo0,
      archState.tlbelo1,
      archState.asid,
      archState.pgdl,
      archState.pgdh,
      archState.save0,
      archState.save1,
      archState.save2,
      archState.save3,
      archState.tid,
      archState.tcfg,
      archState.tval,
      archState.ticlr,
      archState.llbctl,
      archState.tlbrentry,
      archState.dmw0,
      archState.dmw1
    )
    assert(csrState.size == 27)
    assert(csrState.forall(_.getBitsWidth == 32))
    assert(archState.getBitsWidth == (32 + 27) * 32)
    out(archState)

    val loadActive = out Bool ()
    val storeActive = out Bool ()
  }
  noIoPrefix()

  assert(io.commit.valid.isOutput)
  assert(io.commit.payload.flatten.forall(_.isOutput))
  assert(io.archState.flatten.forall(_.isOutput))
  assert(io.loadActive.isOutput)
  assert(io.storeActive.isOutput)

  io.commit.valid := False
  io.commit.payload.assignDontCare()
  io.archState.assignDontCare()
  io.loadActive := io.commit.payload.load.active
  io.storeActive := io.commit.payload.store.active
}

class CommitContractsSpec extends AnyFunSuite {
  test("commit and architectural state contracts elaborate as real producer ports") {
    val outputDirectory = Files.createTempDirectory("miku-commit-contracts-")
    try {
      SpinalConfig(targetDirectory = outputDirectory.toString)
        .generateVerilog(new CommitContractsProbe)
    } finally {
      Files.walk(outputDirectory).iterator().asScala.toSeq.reverse.foreach(Files.delete)
    }
  }
}
