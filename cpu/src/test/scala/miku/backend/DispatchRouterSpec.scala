package miku.backend

import miku.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

private final class DispatchRouterProbe(config: OooCoreConfig) extends Component {
  val io = new Bundle {
    val inputValid = in Bits (config.dispatchWidth bits)
    val inputFuType = in Vec (UInt(ExecutionUnitType.Width bits), config.dispatchWidth)
    val portReady = in Bits (config.executionWidth bits)
    val inputReady = out Bits (config.dispatchWidth bits)
    val portValid = out Bits (config.executionWidth bits)
    val portRobPointer = out Vec (UInt(config.robPointerWidth bits), config.executionWidth)
  }
  noIoPrefix()

  val router = new DispatchRouter(config)
  router.io.inputValid := io.inputValid
  router.io.portReady := io.portReady
  for (lane <- 0 until config.dispatchWidth) {
    router.io.input(lane).assignFromBits(B(0, router.io.input(lane).getBitsWidth bits))
    router.io.input(lane).decoded.fuType.allowOverride()
    router.io.input(lane).robPointer.allowOverride()
    router.io.input(lane).decoded.fuType := io.inputFuType(lane)
    router.io.input(lane).robPointer := U(lane + 1, config.robPointerWidth bits)
  }
  io.inputReady := router.io.inputReady
  io.portValid := router.io.portValid
  for (port <- 0 until config.executionWidth) {
    io.portRobPointer(port) := router.io.portInput(port).robPointer
  }
  io.portValid.simPublic()
  io.portRobPointer.foreach(_.simPublic())
}

class DispatchRouterSpec extends AnyFunSuite {
  private def dispatchedPointers(capabilityAware: Boolean): Seq[Int] = {
    val config = OooCoreConfig.FourIssueThreeCommit.copy(
      enableCapabilityAwareDispatchReservation = capabilityAware
    )
    var dispatched = Seq.empty[Int]
    SimConfig.withVerilator
      .workspacePath(
        sys.env.getOrElse("SPINAL_SIM_WORKSPACE_ROOT", "target") +
          s"/sim-workspace-ooo-dispatch-router-$capabilityAware"
      )
      .compile(new DispatchRouterProbe(config))
      .doSim(s"ooo-dispatch-router-$capabilityAware", if (capabilityAware) 0x4d21 else 0x4d20) {
        dut =>
          dut.io.inputValid #= 0x7
          dut.io.portReady #= 0x7
          dut.io.inputFuType(0) #= 0
          dut.io.inputFuType(1) #= 4
          dut.io.inputFuType(2) #= 3
          sleep(1)

          val validMask = dut.io.portValid.toInt
          dispatched = (0 until config.executionWidth)
            .filter(port => (validMask & (1 << port)) != 0)
            .map(port => dut.io.portRobPointer(port).toInt)
            .sorted
      }
    dispatched
  }

  test("capability-aware dispatch preserves the maximum in-order prefix") {
    assert(dispatchedPointers(capabilityAware = true) == Seq(1, 2, 3))
  }

  test("legacy lowest-port dispatch exposes flexible-uop priority inversion") {
    assert(dispatchedPointers(capabilityAware = false) == Seq(1))
  }
}
