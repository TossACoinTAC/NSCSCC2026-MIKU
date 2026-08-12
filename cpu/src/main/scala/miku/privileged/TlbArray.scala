package miku.privileged

import spinal.core._

/** Active 32-entry MIKU TLB storage and two registered search ports.
  *
  * Search keys are captured on `fetch` and observed combinationally one cycle later. The complete
  * array intentionally has no reset, matching the golden RTL. A write to one entry has priority
  * over a simultaneous invalidate of that entry.
  */
final class TlbArray(
    definitionName: String = "tlb_entry",
    exposeInstructionIndex: Boolean = true,
    exposeManagementSearch: Boolean = false
) extends Component {
  setDefinitionName(definitionName)

  val io = new Bundle {
    val clk = in Bool ()

    val s0_fetch = in Bool ()
    val s0_vppn = in Bits (19 bits)
    val s0_odd_page = in Bool ()
    val s0_asid = in Bits (10 bits)
    val s0_found = out Bool ()
    val s0_index = exposeInstructionIndex generate out(Bits(5 bits))
    val s0_ps = out Bits (6 bits)
    val s0_ppn = out Bits (20 bits)
    val s0_v = out Bool ()
    val s0_d = out Bool ()
    val s0_mat = out Bits (2 bits)
    val s0_plv = out Bits (2 bits)

    val s1_fetch = in Bool ()
    val s1_vppn = in Bits (19 bits)
    val s1_odd_page = in Bool ()
    val s1_asid = in Bits (10 bits)
    val s1_found = out Bool ()
    val s1_index = out Bits (5 bits)
    val s1_ps = out Bits (6 bits)
    val s1_ppn = out Bits (20 bits)
    val s1_v = out Bool ()
    val s1_d = out Bool ()
    val s1_mat = out Bits (2 bits)
    val s1_plv = out Bits (2 bits)

    val management_vppn = exposeManagementSearch generate in Bits (19 bits)
    val management_asid = exposeManagementSearch generate in Bits (10 bits)
    val management_found = exposeManagementSearch generate out Bool ()
    val management_index = exposeManagementSearch generate out Bits (5 bits)

    val we = in Bool ()
    val w_index = in UInt (5 bits)
    val w_vppn = in Bits (19 bits)
    val w_asid = in Bits (10 bits)
    val w_g = in Bool ()
    val w_ps = in Bits (6 bits)
    val w_e = in Bool ()
    val w_v0 = in Bool ()
    val w_d0 = in Bool ()
    val w_mat0 = in Bits (2 bits)
    val w_plv0 = in Bits (2 bits)
    val w_ppn0 = in Bits (20 bits)
    val w_v1 = in Bool ()
    val w_d1 = in Bool ()
    val w_mat1 = in Bits (2 bits)
    val w_plv1 = in Bits (2 bits)
    val w_ppn1 = in Bits (20 bits)

    val r_index = in UInt (5 bits)
    val r_vppn = out Bits (19 bits)
    val r_asid = out Bits (10 bits)
    val r_g = out Bool ()
    val r_ps = out Bits (6 bits)
    val r_e = out Bool ()
    val r_v0 = out Bool ()
    val r_d0 = out Bool ()
    val r_mat0 = out Bits (2 bits)
    val r_plv0 = out Bits (2 bits)
    val r_ppn0 = out Bits (20 bits)
    val r_v1 = out Bool ()
    val r_d1 = out Bool ()
    val r_mat1 = out Bits (2 bits)
    val r_plv1 = out Bits (2 bits)
    val r_ppn1 = out Bits (20 bits)

    val inv_en = in Bool ()
    val inv_op = in Bits (5 bits)
    val inv_asid = in Bits (10 bits)
    val inv_vpn = in Bits (19 bits)
  }
  noIoPrefix()

  private val domain = ClockDomain(clock = io.clk)
  private val state = new ClockingArea(domain) {
    val vppn = Vec.fill(32)(Reg(Bits(19 bits)))
    val enabled = Vec.fill(32)(Reg(Bool()))
    val asid = Vec.fill(32)(Reg(Bits(10 bits)))
    val global = Vec.fill(32)(Reg(Bool()))
    val pageSize = Vec.fill(32)(Reg(Bits(6 bits)))
    val ppn0 = Vec.fill(32)(Reg(Bits(20 bits)))
    val plv0 = Vec.fill(32)(Reg(Bits(2 bits)))
    val mat0 = Vec.fill(32)(Reg(Bits(2 bits)))
    val dirty0 = Vec.fill(32)(Reg(Bool()))
    val valid0 = Vec.fill(32)(Reg(Bool()))
    val ppn1 = Vec.fill(32)(Reg(Bits(20 bits)))
    val plv1 = Vec.fill(32)(Reg(Bits(2 bits)))
    val mat1 = Vec.fill(32)(Reg(Bits(2 bits)))
    val dirty1 = Vec.fill(32)(Reg(Bool()))
    val valid1 = Vec.fill(32)(Reg(Bool()))

    val s0Vppn = Reg(Bits(19 bits))
    val s0OddPage = Reg(Bool())
    val s0Asid = Reg(Bits(10 bits))
    val s1Vppn = Reg(Bits(19 bits))
    val s1OddPage = Reg(Bool())
    val s1Asid = Reg(Bits(10 bits))

    when(io.s0_fetch) {
      s0Vppn := io.s0_vppn
      s0OddPage := io.s0_odd_page
      s0Asid := io.s0_asid
    }
    when(io.s1_fetch) {
      s1Vppn := io.s1_vppn
      s1OddPage := io.s1_odd_page
      s1Asid := io.s1_asid
    }

    when(io.we) {
      vppn(io.w_index) := io.w_vppn
      asid(io.w_index) := io.w_asid
      global(io.w_index) := io.w_g
      pageSize(io.w_index) := io.w_ps
      ppn0(io.w_index) := io.w_ppn0
      plv0(io.w_index) := io.w_plv0
      mat0(io.w_index) := io.w_mat0
      dirty0(io.w_index) := io.w_d0
      valid0(io.w_index) := io.w_v0
      ppn1(io.w_index) := io.w_ppn1
      plv1(io.w_index) := io.w_plv1
      mat1(io.w_index) := io.w_mat1
      dirty1(io.w_index) := io.w_d1
      valid1(io.w_index) := io.w_v1
    }

    for (index <- 0 until 32) {
      val sameSmallPage = vppn(index) === io.inv_vpn
      val sameLargePage = vppn(index)(18 downto 9) === io.inv_vpn(18 downto 9)
      val samePage = Mux(pageSize(index) === B(12, 6 bits), sameSmallPage, sameLargePage)
      when(io.we && io.w_index === index) {
        enabled(index) := io.w_e
      } elsewhen (io.inv_en) {
        switch(io.inv_op) {
          is(B(0, 5 bits), B(1, 5 bits)) { enabled(index) := False }
          is(B(2, 5 bits)) {
            when(global(index)) { enabled(index) := False }
          }
          is(B(3, 5 bits)) {
            when(!global(index)) { enabled(index) := False }
          }
          is(B(4, 5 bits)) {
            when(!global(index) && asid(index) === io.inv_asid) { enabled(index) := False }
          }
          is(B(5, 5 bits)) {
            when(!global(index) && asid(index) === io.inv_asid && samePage) {
              enabled(index) := False
            }
          }
          is(B(6, 5 bits)) {
            when((global(index) || asid(index) === io.inv_asid) && samePage) {
              enabled(index) := False
            }
          }
        }
      }
    }
  }

  private def pageMatches(index: Int, vppn: Bits): Bool =
    Mux(
      state.pageSize(index) === B(12, 6 bits),
      vppn === state.vppn(index),
      vppn(18 downto 9) === state.vppn(index)(18 downto 9)
    )

  private val match0 = Bits(32 bits)
  private val match1 = Bits(32 bits)
  for (index <- 0 until 32) {
    match0(index) := state.enabled(index) && pageMatches(index, state.s0Vppn) &&
      (state.s0Asid === state.asid(index) || state.global(index))
    match1(index) := state.enabled(index) && pageMatches(index, state.s1Vppn) &&
      (state.s1Asid === state.asid(index) || state.global(index))
  }

  private val index0 = (0 until 32)
    .map(index => Mux(match0(index), U(index, 5 bits), U(0, 5 bits)))
    .reduce(_ | _)
  private val index1 = (0 until 32)
    .map(index => Mux(match1(index), U(index, 5 bits), U(0, 5 bits)))
    .reduce(_ | _)
  private val odd0 = Mux(state.pageSize(index0) === B(12, 6 bits), state.s0OddPage, state.s0Vppn(8))
  private val odd1 = Mux(state.pageSize(index1) === B(12, 6 bits), state.s1OddPage, state.s1Vppn(8))

  io.s0_found := match0.orR
  if (exposeInstructionIndex) io.s0_index := index0.asBits
  io.s0_ps := state.pageSize(index0)
  io.s0_ppn := Mux(odd0, state.ppn1(index0), state.ppn0(index0))
  io.s0_v := Mux(odd0, state.valid1(index0), state.valid0(index0))
  io.s0_d := Mux(odd0, state.dirty1(index0), state.dirty0(index0))
  io.s0_mat := Mux(odd0, state.mat1(index0), state.mat0(index0))
  io.s0_plv := Mux(odd0, state.plv1(index0), state.plv0(index0))

  io.s1_found := match1.orR
  io.s1_index := index1.asBits
  io.s1_ps := state.pageSize(index1)
  io.s1_ppn := Mux(odd1, state.ppn1(index1), state.ppn0(index1))
  io.s1_v := Mux(odd1, state.valid1(index1), state.valid0(index1))
  io.s1_d := Mux(odd1, state.dirty1(index1), state.dirty0(index1))
  io.s1_mat := Mux(odd1, state.mat1(index1), state.mat0(index1))
  io.s1_plv := Mux(odd1, state.plv1(index1), state.plv0(index1))

  if (exposeManagementSearch) {
    val managementMatch = Bits(32 bits)
    for (index <- 0 until 32) {
      managementMatch(index) := state.enabled(index) &&
        pageMatches(index, io.management_vppn) &&
        (io.management_asid === state.asid(index) || state.global(index))
    }
    val managementIndex = (0 until 32)
      .map(index => Mux(managementMatch(index), U(index, 5 bits), U(0, 5 bits)))
      .reduce(_ | _)
    io.management_found := managementMatch.orR
    io.management_index := managementIndex.asBits
  }

  io.r_vppn := state.vppn(io.r_index)
  io.r_asid := state.asid(io.r_index)
  io.r_g := state.global(io.r_index)
  io.r_ps := state.pageSize(io.r_index)
  io.r_e := state.enabled(io.r_index)
  io.r_v0 := state.valid0(io.r_index)
  io.r_d0 := state.dirty0(io.r_index)
  io.r_mat0 := state.mat0(io.r_index)
  io.r_plv0 := state.plv0(io.r_index)
  io.r_ppn0 := state.ppn0(io.r_index)
  io.r_v1 := state.valid1(io.r_index)
  io.r_d1 := state.dirty1(io.r_index)
  io.r_mat1 := state.mat1(io.r_index)
  io.r_plv1 := state.plv1(io.r_index)
  io.r_ppn1 := state.ppn1(io.r_index)
}
