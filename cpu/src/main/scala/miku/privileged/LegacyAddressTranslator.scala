package miku.privileged

import spinal.core._

/** Address translation, TLB management, DMW selection and cache tag formation.
  *
  * Virtual addresses are captured only when their fetch strobes are asserted. Cache index/offset
  * remain combinational from the current request, while translated tags use the captured address,
  * exactly matching the active golden module.
  */
final class LegacyAddressTranslator(managementSearchEnabled: Boolean = false) extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val asid = in Bits (10 bits)
    val inst_addr_trans_en = in Bool ()
    val data_addr_trans_en = in Bool ()
    val inst_fetch = in Bool ()
    val inst_vaddr = in Bits (32 bits)
    val inst_dmw0_en = in Bool ()
    val inst_dmw1_en = in Bool ()
    val inst_index = out Bits (8 bits)
    val inst_tag = out Bits (20 bits)
    val inst_offset = out Bits (4 bits)
    val inst_tlb_found = out Bool ()
    val inst_tlb_v = out Bool ()
    val inst_tlb_d = out Bool ()
    val inst_tlb_mat = out Bits (2 bits)
    val inst_tlb_plv = out Bits (2 bits)

    val data_fetch = in Bool ()
    val data_vaddr = in Bits (32 bits)
    val data_dmw0_en = in Bool ()
    val data_dmw1_en = in Bool ()
    val cacop_op_mode_di = in Bool ()
    val data_index = out Bits (8 bits)
    val data_tag = out Bits (20 bits)
    val data_offset = out Bits (4 bits)
    val data_tlb_found = out Bool ()
    val data_tlb_index = out Bits (5 bits)
    val data_tlb_v = out Bool ()
    val data_tlb_d = out Bool ()
    val data_tlb_mat = out Bits (2 bits)
    val data_tlb_plv = out Bits (2 bits)

    val management_search_vppn = managementSearchEnabled generate in Bits (19 bits)
    val management_search_found = managementSearchEnabled generate out Bool ()
    val management_search_index = managementSearchEnabled generate out Bits (5 bits)

    val tlbfill_en = in Bool ()
    val tlbwr_en = in Bool ()
    val rand_index = in UInt (5 bits)
    val tlbehi_in = in Bits (32 bits)
    val tlbelo0_in = in Bits (32 bits)
    val tlbelo1_in = in Bits (32 bits)
    val tlbidx_in = in Bits (32 bits)
    val ecode_in = in Bits (6 bits)
    val tlbehi_out = out Bits (32 bits)
    val tlbelo0_out = out Bits (32 bits)
    val tlbelo1_out = out Bits (32 bits)
    val tlbidx_out = out Bits (32 bits)
    val asid_out = out Bits (10 bits)

    val invtlb_en = in Bool ()
    val invtlb_asid = in Bits (10 bits)
    val invtlb_vpn = in Bits (19 bits)
    val invtlb_op = in Bits (5 bits)
    val csr_dmw0 = in Bits (32 bits)
    val csr_dmw1 = in Bits (32 bits)
    val csr_da = in Bool ()
    val csr_pg = in Bool ()
  }
  noIoPrefix()

  private val domain = ClockDomain(clock = io.clk)
  private val captured = new ClockingArea(domain) {
    val instVaddr = Reg(Bits(32 bits))
    val dataVaddr = Reg(Bits(32 bits))
    when(io.inst_fetch) { instVaddr := io.inst_vaddr }
    when(io.data_fetch) { dataVaddr := io.data_vaddr }
  }

  private val tlb = new TlbArray(
    definitionName = "tlb_array_impl",
    exposeInstructionIndex = false,
    exposeManagementSearch = managementSearchEnabled
  )
  tlb.io.clk := io.clk
  tlb.io.s0_fetch := io.inst_fetch
  tlb.io.s0_vppn := io.inst_vaddr(31 downto 13)
  tlb.io.s0_odd_page := io.inst_vaddr(12)
  tlb.io.s0_asid := io.asid
  tlb.io.s1_fetch := io.data_fetch
  tlb.io.s1_vppn := io.data_vaddr(31 downto 13)
  tlb.io.s1_odd_page := io.data_vaddr(12)
  tlb.io.s1_asid := io.asid

  private val writeEnable = io.tlbfill_en || io.tlbwr_en
  tlb.io.we := writeEnable
  tlb.io.w_index := Mux(io.tlbfill_en, io.rand_index, io.tlbidx_in(4 downto 0).asUInt)
  tlb.io.w_vppn := io.tlbehi_in(31 downto 13)
  tlb.io.w_asid := io.asid
  tlb.io.w_g := io.tlbelo0_in(6) && io.tlbelo1_in(6)
  tlb.io.w_ps := io.tlbidx_in(29 downto 24)
  tlb.io.w_e := Mux(io.ecode_in === B(0x3f, 6 bits), True, !io.tlbidx_in(31))
  tlb.io.w_v0 := io.tlbelo0_in(0)
  tlb.io.w_d0 := io.tlbelo0_in(1)
  tlb.io.w_plv0 := io.tlbelo0_in(3 downto 2)
  tlb.io.w_mat0 := io.tlbelo0_in(5 downto 4)
  tlb.io.w_ppn0 := io.tlbelo0_in(27 downto 8)
  tlb.io.w_v1 := io.tlbelo1_in(0)
  tlb.io.w_d1 := io.tlbelo1_in(1)
  tlb.io.w_plv1 := io.tlbelo1_in(3 downto 2)
  tlb.io.w_mat1 := io.tlbelo1_in(5 downto 4)
  tlb.io.w_ppn1 := io.tlbelo1_in(27 downto 8)

  tlb.io.r_index := io.tlbidx_in(4 downto 0).asUInt
  tlb.io.inv_en := io.invtlb_en
  tlb.io.inv_op := io.invtlb_op
  tlb.io.inv_asid := io.invtlb_asid
  tlb.io.inv_vpn := io.invtlb_vpn

  io.inst_tlb_found := tlb.io.s0_found
  io.inst_tlb_v := tlb.io.s0_v
  io.inst_tlb_d := tlb.io.s0_d
  io.inst_tlb_mat := tlb.io.s0_mat
  io.inst_tlb_plv := tlb.io.s0_plv
  io.data_tlb_found := tlb.io.s1_found
  io.data_tlb_index := tlb.io.s1_index
  io.data_tlb_v := tlb.io.s1_v
  io.data_tlb_d := tlb.io.s1_d
  io.data_tlb_mat := tlb.io.s1_mat
  io.data_tlb_plv := tlb.io.s1_plv
  if (managementSearchEnabled) {
    tlb.io.management_vppn := io.management_search_vppn
    tlb.io.management_asid := io.asid
    io.management_search_found := tlb.io.management_found
    io.management_search_index := tlb.io.management_index
  }

  io.tlbehi_out := tlb.io.r_vppn ## B(0, 13 bits)
  io.tlbelo0_out := B(0, 4 bits) ## tlb.io.r_ppn0 ## B(0, 1 bits) ## tlb.io.r_g ##
    tlb.io.r_mat0 ## tlb.io.r_plv0 ## tlb.io.r_d0 ## tlb.io.r_v0
  io.tlbelo1_out := B(0, 4 bits) ## tlb.io.r_ppn1 ## B(0, 1 bits) ## tlb.io.r_g ##
    tlb.io.r_mat1 ## tlb.io.r_plv1 ## tlb.io.r_d1 ## tlb.io.r_v1
  io.tlbidx_out := (!tlb.io.r_e).asBits ## B(0, 1 bits) ## tlb.io.r_ps ## B(0, 24 bits)
  io.asid_out := tlb.io.r_asid

  private val pagingMode = !io.csr_da && io.csr_pg
  private val instPhysical = Bits(32 bits)
  private val dataPhysical = Bits(32 bits)
  instPhysical := captured.instVaddr
  when(pagingMode && io.inst_dmw0_en) {
    instPhysical := io.csr_dmw0(27 downto 25) ## captured.instVaddr(28 downto 0)
  } elsewhen (pagingMode && io.inst_dmw1_en) {
    instPhysical := io.csr_dmw1(27 downto 25) ## captured.instVaddr(28 downto 0)
  }
  dataPhysical := captured.dataVaddr
  when(pagingMode && io.data_dmw0_en && !io.cacop_op_mode_di) {
    dataPhysical := io.csr_dmw0(27 downto 25) ## captured.dataVaddr(28 downto 0)
  } elsewhen (pagingMode && io.data_dmw1_en && !io.cacop_op_mode_di) {
    dataPhysical := io.csr_dmw1(27 downto 25) ## captured.dataVaddr(28 downto 0)
  }

  io.inst_offset := io.inst_vaddr(3 downto 0)
  io.inst_index := io.inst_vaddr(11 downto 4)
  io.inst_tag := Mux(
    io.inst_addr_trans_en,
    Mux(
      tlb.io.s0_ps === B(12, 6 bits),
      tlb.io.s0_ppn,
      tlb.io.s0_ppn(19 downto 10) ## instPhysical(21 downto 12)
    ),
    instPhysical(31 downto 12)
  )
  io.data_offset := io.data_vaddr(3 downto 0)
  io.data_index := io.data_vaddr(11 downto 4)
  io.data_tag := Mux(
    io.data_addr_trans_en,
    Mux(
      tlb.io.s1_ps === B(12, 6 bits),
      tlb.io.s1_ppn,
      tlb.io.s1_ppn(19 downto 10) ## dataPhysical(21 downto 12)
    ),
    dataPhysical(31 downto 12)
  )
}
