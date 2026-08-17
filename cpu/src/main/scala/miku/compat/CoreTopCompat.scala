package miku.compat

import miku.core.{CustomInstructionBuildConfig, CustomInstructionProfile, OooCoreConfig, OooCoreSystem}
import spinal.core._

/** Locked compatibility boundary for the chiplab core_top interface. */
final case class CoreTopCompatConfig(
    tlbEntries: Int = 32,
    branchTraceObserver: Boolean = false,
    customInstructionProfile: CustomInstructionProfile =
      CustomInstructionBuildConfig.selectedProfile
) {
  require(tlbEntries == 32, "only the locked TLBNUM=32 configuration is currently verified")
}

final class CoreTopCompat(config: CoreTopCompatConfig = CoreTopCompatConfig()) extends Component {
  val io = new Bundle {
    val aclk = in Bool ()
    val aresetn = in Bool ()
    val intrpt = in Bits (8 bits)

    val arid = out Bits (4 bits)
    val araddr = out Bits (32 bits)
    val arlen = out Bits (8 bits)
    val arsize = out Bits (3 bits)
    val arburst = out Bits (2 bits)
    val arlock = out Bits (2 bits)
    val arcache = out Bits (4 bits)
    val arprot = out Bits (3 bits)
    val arvalid = out Bool ()
    val arready = in Bool ()

    val rid = in Bits (4 bits)
    val rdata = in Bits (32 bits)
    val rresp = in Bits (2 bits)
    val rlast = in Bool ()
    val rvalid = in Bool ()
    val rready = out Bool ()

    val awid = out Bits (4 bits)
    val awaddr = out Bits (32 bits)
    val awlen = out Bits (8 bits)
    val awsize = out Bits (3 bits)
    val awburst = out Bits (2 bits)
    val awlock = out Bits (2 bits)
    val awcache = out Bits (4 bits)
    val awprot = out Bits (3 bits)
    val awvalid = out Bool ()
    val awready = in Bool ()

    val wid = out Bits (4 bits)
    val wdata = out Bits (32 bits)
    val wstrb = out Bits (4 bits)
    val wlast = out Bool ()
    val wvalid = out Bool ()
    val wready = in Bool ()

    val bid = in Bits (4 bits)
    val bresp = in Bits (2 bits)
    val bvalid = in Bool ()
    val bready = out Bool ()

    val break_point = in Bool ()
    val infor_flag = in Bool ()
    val reg_num = in Bits (5 bits)
    val ws_valid = out Bool ()
    val rf_rdata = out Bits (32 bits)
    val debug0_wb_pc = out Bits (32 bits)
    val debug0_wb_rf_wen = out Bits (4 bits)
    val debug0_wb_rf_wnum = out Bits (5 bits)
    val debug0_wb_rf_wdata = out Bits (32 bits)
    val debug0_wb_inst = out Bits (32 bits)
  }

  noIoPrefix()

  // Keep the backend reset until the board controller has asserted external reset at least once.
  // This prevents the FPGA from running against stale DDR contents between configuration and the
  // JTAG download. The registered ~aresetn path remains cycle-compatible after that first pulse.
  val resetCaptureDomain = ClockDomain(
    clock = io.aclk,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = BOOT)
  )
  val resetCapture = new ClockingArea(resetCaptureDomain) {
    val externalResetSeen = Reg(Bool()) init (False)
    when(!io.aresetn) {
      externalResetSeen := True
    }
    val delayedActiveHigh = RegNext(!io.aresetn) init (True)
    val backendActiveHigh = delayedActiveHigh || !externalResetSeen
  }

  val coreClockDomain = ClockDomain(
    clock = io.aclk,
    reset = resetCapture.backendActiveHigh,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val backendArea = new ClockingArea(coreClockDomain) {
    val core = new OooCoreSystem(
      OooCoreConfig.FourIssueThreeCommit.copy(
        enableBranchTraceObserver = config.branchTraceObserver,
        customInstructionProfile = config.customInstructionProfile
      )
    )
  }
  val core = backendArea.core

  core.io.aclk := coreClockDomain.clock
  core.io.reset := coreClockDomain.reset
  core.io.intrpt := io.intrpt
  core.io.axi.ar.ready := io.arready
  core.io.axi.r.payload.id := io.rid
  core.io.axi.r.payload.data := io.rdata
  core.io.axi.r.payload.response := io.rresp
  core.io.axi.r.payload.last := io.rlast
  core.io.axi.r.valid := io.rvalid
  core.io.axi.aw.ready := io.awready
  core.io.axi.w.ready := io.wready
  core.io.axi.b.payload.id := io.bid
  core.io.axi.b.payload.response := io.bresp
  core.io.axi.b.valid := io.bvalid
  core.io.breakPoint := io.break_point
  core.io.informationSelect := io.infor_flag
  core.io.registerNumber := io.reg_num

  io.arid := core.io.axi.ar.payload.id
  io.araddr := core.io.axi.ar.payload.address
  io.arlen := core.io.axi.ar.payload.len
  io.arsize := core.io.axi.ar.payload.size
  io.arburst := core.io.axi.ar.payload.burst
  io.arlock := core.io.axi.ar.payload.lock
  io.arcache := core.io.axi.ar.payload.cache
  io.arprot := core.io.axi.ar.payload.prot
  io.arvalid := core.io.axi.ar.valid
  io.rready := core.io.axi.r.ready
  io.awid := core.io.axi.aw.payload.id
  io.awaddr := core.io.axi.aw.payload.address
  io.awlen := core.io.axi.aw.payload.len
  io.awsize := core.io.axi.aw.payload.size
  io.awburst := core.io.axi.aw.payload.burst
  io.awlock := core.io.axi.aw.payload.lock
  io.awcache := core.io.axi.aw.payload.cache
  io.awprot := core.io.axi.aw.payload.prot
  io.awvalid := core.io.axi.aw.valid
  io.wid := core.io.axi.w.payload.id
  io.wdata := core.io.axi.w.payload.data
  io.wstrb := core.io.axi.w.payload.byteMask
  io.wlast := core.io.axi.w.payload.last
  io.wvalid := core.io.axi.w.valid
  io.bready := core.io.axi.b.ready
  io.ws_valid := core.io.writebackValid
  io.rf_rdata := core.io.registerReadData
  io.debug0_wb_pc := core.io.debugPc
  io.debug0_wb_rf_wen := core.io.debugGprWriteMask
  io.debug0_wb_rf_wnum := core.io.debugGprIndex
  io.debug0_wb_rf_wdata := core.io.debugGprData
  io.debug0_wb_inst := core.io.debugInstruction
}
