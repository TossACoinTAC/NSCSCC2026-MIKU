/*------------------------------------------------------------------------------
--------------------------------------------------------------------------------
Copyright (c) 2016, Loongson Technology Corporation Limited.

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this 
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, 
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

3. Neither the name of Loongson Technology Corporation Limited nor the names of 
its contributors may be used to endorse or promote products derived from this 
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
DISCLAIMED. IN NO EVENT SHALL LOONGSON TECHNOLOGY CORPORATION LIMITED BE LIABLE
TO ANY PARTY FOR DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) 
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT 
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
--------------------------------------------------------------------------------
------------------------------------------------------------------------------*/

`include "config.h"

module axi2apb_misc
(
clk,
rst_n,

axi_s_awid,
axi_s_awaddr,
axi_s_awlen,
axi_s_awsize,
axi_s_awburst,
axi_s_awlock,
axi_s_awcache,
axi_s_awprot,
axi_s_awvalid,
axi_s_awready,
axi_s_wid,
axi_s_wdata,
axi_s_wstrb,
axi_s_wlast,
axi_s_wvalid,
axi_s_wready,
axi_s_bid,
axi_s_bresp,
axi_s_bvalid,
axi_s_bready,
axi_s_arid,
axi_s_araddr,
axi_s_arlen,
axi_s_arsize,
axi_s_arburst,
axi_s_arlock,
axi_s_arcache,
axi_s_arprot,
axi_s_arvalid,
axi_s_arready,
axi_s_rid,
axi_s_rdata,
axi_s_rresp,
axi_s_rlast,
axi_s_rvalid,
axi_s_rready,

apb_rw_dma,
apb_psel_dma,
apb_enab_dma,
apb_addr_dma,
apb_valid_dma,
apb_wdata_dma,
apb_rdata_dma,
apb_ready_dma,
dma_grant,

dma_req_o,
dma_ack_i,

uart0_txd_i,
uart0_txd_o,
uart0_txd_oe,
uart0_rxd_i,
uart0_rxd_o,
uart0_rxd_oe,
uart0_rts_o,
uart0_dtr_o,
uart0_cts_i,
uart0_dsr_i,
uart0_dcd_i,
uart0_ri_i,

uart0_int,
nand_int,
ps2_int,
usb_int,

ps2_clk_i,
ps2_clk_o,
ps2_clk_t,
ps2_dat_i,
ps2_dat_o,
ps2_dat_t,

usb_clk,
usb_resetn,
usb_clock_present,
utmi_data_in,
utmi_data_out,
utmi_data_t,
utmi_reset,
utmi_txready,
utmi_rxvalid,
utmi_rxactive,
utmi_rxerror,
utmi_linestate,
utmi_hostdisc,
utmi_iddig,
utmi_vbusvalid,
utmi_sessend,
utmi_txvalid,
utmi_opmode,
utmi_xcvrsel,
utmi_termsel,
utmi_dppulldown,
utmi_dmpulldown,
utmi_idpullup,
utmi_chrgvbus,
utmi_dischrgvbus,
utmi_suspend_n,

lcd_nrst,
lcd_csel,
lcd_rd,
lcd_rs,
lcd_wr,
lcd_data_i,
lcd_data_o,
lcd_data_t,

nand_type,
nand_cle   ,
nand_ale   ,
nand_rdy   ,
nand_rd    ,
nand_ce,
nand_wr    ,
nand_dat_i ,
nand_dat_o ,
nand_dat_oe
);

parameter ADDR_APB = 20,
          DATA_APB = 8,
          L_ADDR = 64,
          L_ID   = 8,
          L_DATA = 128,
          L_MASK = 16;

input          clk;
input                  rst_n;

input  [`LID         -1 :0] axi_s_awid;
input  [`Lawaddr     -1 :0] axi_s_awaddr;
input  [`Lawlen      -1 :0] axi_s_awlen;
input  [`Lawsize     -1 :0] axi_s_awsize;
input  [`Lawburst    -1 :0] axi_s_awburst;
input  [`Lawlock     -1 :0] axi_s_awlock;
input  [`Lawcache    -1 :0] axi_s_awcache;
input  [`Lawprot     -1 :0] axi_s_awprot;
input                       axi_s_awvalid;
output                      axi_s_awready;
input  [`LID         -1 :0] axi_s_wid;
input  [`Lwdata      -1 :0] axi_s_wdata;
input  [`Lwstrb      -1 :0] axi_s_wstrb;
input                       axi_s_wlast;
input                       axi_s_wvalid;
output                      axi_s_wready;
output [`LID         -1 :0] axi_s_bid;
output [`Lbresp      -1 :0] axi_s_bresp;
output                      axi_s_bvalid;
input                       axi_s_bready;
input  [`LID         -1 :0] axi_s_arid;
input  [`Laraddr     -1 :0] axi_s_araddr;
input  [`Larlen      -1 :0] axi_s_arlen;
input  [`Larsize     -1 :0] axi_s_arsize;
input  [`Larburst    -1 :0] axi_s_arburst;
input  [`Larlock     -1 :0] axi_s_arlock;
input  [`Larcache    -1 :0] axi_s_arcache;
input  [`Larprot     -1 :0] axi_s_arprot;
input                       axi_s_arvalid;
output                      axi_s_arready;
output [`LID         -1 :0] axi_s_rid;
output [`Lrdata      -1 :0] axi_s_rdata;
output [`Lrresp      -1 :0] axi_s_rresp;
output                      axi_s_rlast;
output                      axi_s_rvalid;
input                       axi_s_rready;

output                 apb_ready_dma;
input                  apb_rw_dma;
input                  apb_psel_dma;
input                  apb_enab_dma;
input [ADDR_APB-1:0]   apb_addr_dma;
input [31:0]   	       apb_wdata_dma;
output[31:0]   	       apb_rdata_dma;
input                  apb_valid_dma;
output                 dma_grant;

output                 dma_req_o;
input                  dma_ack_i;

input               uart0_txd_i;
output              uart0_txd_o;
output              uart0_txd_oe;
input               uart0_rxd_i;
output              uart0_rxd_o;
output              uart0_rxd_oe;
output              uart0_rts_o;
output              uart0_dtr_o;
input               uart0_cts_i;
input               uart0_dsr_i;
input               uart0_dcd_i;
input               uart0_ri_i;

input   [3:0]nand_rdy;
output  [3:0]nand_ce;
output  nand_cle;
output  nand_ale;
output  nand_rd;
output  nand_wr;
output  nand_dat_oe;
input   [7:0]nand_dat_i ;
output  [7:0]nand_dat_o ;

output uart0_int;
output nand_int;
output ps2_int;
output usb_int;

input  ps2_clk_i;
output ps2_clk_o;
output ps2_clk_t;
input  ps2_dat_i;
output ps2_dat_o;
output ps2_dat_t;

input         usb_clk;
input         usb_resetn;
input         usb_clock_present;
input  [7:0]  utmi_data_in;
output [7:0]  utmi_data_out;
output        utmi_data_t;
output        utmi_reset;
input         utmi_txready;
input         utmi_rxvalid;
input         utmi_rxactive;
input         utmi_rxerror;
input  [1:0]  utmi_linestate;
input         utmi_hostdisc;
input         utmi_iddig;
input         utmi_vbusvalid;
input         utmi_sessend;
output        utmi_txvalid;
output [1:0]  utmi_opmode;
output [1:0]  utmi_xcvrsel;
output        utmi_termsel;
output        utmi_dppulldown;
output        utmi_dmpulldown;
output        utmi_idpullup;
output        utmi_chrgvbus;
output        utmi_dischrgvbus;
output        utmi_suspend_n;

output        lcd_nrst;
output        lcd_csel;
output        lcd_rd;
output        lcd_rs;
output        lcd_wr;
input  [15:0] lcd_data_i;
output [15:0] lcd_data_o;
output [15:0] lcd_data_t;
input  [1:0]nand_type;

wire nand_dma_req_o;
assign  dma_req_o      = nand_dma_req_o;
assign  nand_dma_ack_i = dma_ack_i; 

wire                    apb_ready_cpu;
wire                    apb_rw_cpu;
wire                    apb_psel_cpu;
wire                    apb_enab_cpu;
wire [ADDR_APB-1 :0]    apb_addr_cpu;
wire [DATA_APB-1:0]     apb_datai_cpu;
wire [DATA_APB-1:0]     apb_datao_cpu;
wire                    apb_clk_cpu;
wire                    apb_reset_n_cpu; 
wire                    apb_word_trans_cpu;
wire                    apb_valid_cpu;
wire                    dma_grant;
wire  [23:0]            apb_high_24b_rd;
wire  [23:0]            apb_high_24b_wr;

wire                    apb_rw_dma;
wire                    apb_psel_dma;
wire                    apb_enab_dma;
wire [31:0]             apb_wdata_dma;
wire [31:0]             apb_rdata_dma;
wire                    apb_clk_dma;
wire                    apb_reset_n_dma; 

wire                apb_uart0_req;
wire                apb_uart0_ack;
wire                apb_uart0_rw;
wire                apb_uart0_enab;
wire                apb_uart0_psel;
wire  [ADDR_APB -1:0] apb_uart0_addr;
wire  [DATA_APB -1:0] apb_uart0_datai;
wire  [DATA_APB -1:0] apb_uart0_datao;

wire                apb_nand_req; 
wire                apb_nand_ack; 
wire                apb_nand_rw; 
wire                apb_nand_enab; 
wire                apb_nand_psel; 
wire  [ADDR_APB -1:0] apb_nand_addr; 
wire  [31:0]        apb_nand_datai; 
wire  [31:0]        apb_nand_datao; 

wire                apb_ps2_req;
wire                apb_ps2_ack;
wire                apb_ps2_rw;
wire                apb_ps2_enab;
wire                apb_ps2_psel;
wire  [ADDR_APB-1:0] apb_ps2_addr;
wire  [31:0]        apb_ps2_datai;
wire  [31:0]        apb_ps2_datao;

wire                apb_lcd_req;
wire                apb_lcd_ack;
wire                apb_lcd_rw;
wire                apb_lcd_enab;
wire                apb_lcd_psel;
wire  [ADDR_APB-1:0] apb_lcd_addr;
wire  [31:0]        apb_lcd_datai;
wire  [31:0]        apb_lcd_datao;

wire                apb_usb_req;
wire                apb_usb_ack;
wire                apb_usb_rw;
wire                apb_usb_enab;
wire                apb_usb_psel;
wire  [ADDR_APB-1:0] apb_usb_addr;
wire  [31:0]         apb_usb_datai;
wire  [31:0]         apb_usb_datao;

wire [31:0] usb_cfg_awaddr;
wire        usb_cfg_awvalid;
wire        usb_cfg_awready;
wire [31:0] usb_cfg_wdata;
wire [3:0]  usb_cfg_wstrb;
wire        usb_cfg_wvalid;
wire        usb_cfg_wready;
wire [1:0]  usb_cfg_bresp;
wire        usb_cfg_bvalid;
wire        usb_cfg_bready;
wire [31:0] usb_cfg_araddr;
wire        usb_cfg_arvalid;
wire        usb_cfg_arready;
wire [31:0] usb_cfg_rdata;
wire [1:0]  usb_cfg_rresp;
wire        usb_cfg_rvalid;
wire        usb_cfg_rready;

axi2apb_bridge AA_axi2apb_bridge_cpu 
(
.clk                (clk                ),
.rst_n              (rst_n              ),
.axi_s_awid         (axi_s_awid         ),
.axi_s_awaddr       (axi_s_awaddr       ),
.axi_s_awlen        (axi_s_awlen        ),
.axi_s_awsize       (axi_s_awsize       ),
.axi_s_awburst      (axi_s_awburst      ),
.axi_s_awlock       (axi_s_awlock       ),
.axi_s_awcache      (axi_s_awcache      ),
.axi_s_awprot       (axi_s_awprot       ),
.axi_s_awvalid      (axi_s_awvalid      ),
.axi_s_awready      (axi_s_awready      ),
.axi_s_wid          (axi_s_wid          ),
.axi_s_wdata        (axi_s_wdata        ),
.axi_s_wstrb        (axi_s_wstrb        ),
.axi_s_wlast        (axi_s_wlast        ),
.axi_s_wvalid       (axi_s_wvalid       ),
.axi_s_wready       (axi_s_wready       ),
.axi_s_bid          (axi_s_bid          ),
.axi_s_bresp        (axi_s_bresp        ),
.axi_s_bvalid       (axi_s_bvalid       ),
.axi_s_bready       (axi_s_bready       ),
.axi_s_arid         (axi_s_arid         ),
.axi_s_araddr       (axi_s_araddr       ),
.axi_s_arlen        (axi_s_arlen        ),
.axi_s_arsize       (axi_s_arsize       ),
.axi_s_arburst      (axi_s_arburst      ),
.axi_s_arlock       (axi_s_arlock       ),
.axi_s_arcache      (axi_s_arcache      ),
.axi_s_arprot       (axi_s_arprot       ),
.axi_s_arvalid      (axi_s_arvalid      ),
.axi_s_arready      (axi_s_arready      ),
.axi_s_rid          (axi_s_rid          ),
.axi_s_rdata        (axi_s_rdata        ),
.axi_s_rresp        (axi_s_rresp        ),
.axi_s_rlast        (axi_s_rlast        ),
.axi_s_rvalid       (axi_s_rvalid       ),
.axi_s_rready       (axi_s_rready       ),

.apb_word_trans     (apb_word_trans_cpu ),
.apb_high_24b_rd    (apb_high_24b_rd    ),
.apb_high_24b_wr    (apb_high_24b_wr    ),
.apb_valid_cpu      (apb_valid_cpu      ),
.cpu_grant          (~dma_grant         ),

.apb_clk            (apb_clk_cpu        ),
.apb_reset_n        (apb_reset_n_cpu    ),
.reg_psel           (apb_psel_cpu       ),
.reg_enable         (apb_enab_cpu       ),
.reg_rw             (apb_rw_cpu         ),
.reg_addr           (apb_addr_cpu       ),
.reg_datai          (apb_datai_cpu      ),
.reg_datao          (apb_datao_cpu      ),
.reg_ready_1        (apb_ready_cpu      )
);

apb_mux2 AA_apb_mux16
(
.clk                (clk                ),
.rst_n              (rst_n              ),
.apb_ready_dma      (apb_ready_dma      ),
.apb_rw_dma         (apb_rw_dma         ),
.apb_addr_dma       (apb_addr_dma       ),
.apb_psel_dma       (apb_psel_dma       ),
.apb_enab_dma       (apb_enab_dma       ),
.apb_wdata_dma      (apb_wdata_dma      ),
.apb_rdata_dma      (apb_rdata_dma      ),
.apb_valid_dma      (apb_valid_dma      ),
.apb_valid_cpu      (apb_valid_cpu      ),
.dma_grant          (dma_grant          ),

.apb_ack_cpu        (apb_ready_cpu      ),
.apb_rw_cpu         (apb_rw_cpu         ),
.apb_addr_cpu       (apb_addr_cpu       ),
.apb_psel_cpu       (apb_psel_cpu       ),
.apb_enab_cpu       (apb_enab_cpu       ),
.apb_datai_cpu      (apb_datai_cpu      ),
.apb_datao_cpu      (apb_datao_cpu      ),
.apb_high_24b_rd    (apb_high_24b_rd),
.apb_high_24b_wr    (apb_high_24b_wr),
.apb_word_trans_cpu (apb_word_trans_cpu ),

.apb0_req           (apb_uart0_req      ),
.apb0_ack           (apb_uart0_ack      ),
.apb0_rw            (apb_uart0_rw       ),
.apb0_psel          (apb_uart0_psel     ),
.apb0_enab          (apb_uart0_enab     ),
.apb0_addr          (apb_uart0_addr     ),
.apb0_datai         (apb_uart0_datai    ),
.apb0_datao         (apb_uart0_datao    ),
                                        
.apb1_req           (apb_nand_req       ),
.apb1_ack           (apb_nand_ack       ),
.apb1_rw            (apb_nand_rw        ),
.apb1_enab          (apb_nand_enab      ),
.apb1_psel          (apb_nand_psel      ),
.apb1_addr          (apb_nand_addr      ),
.apb1_datai         (apb_nand_datai     ),
.apb1_datao         (apb_nand_datao     ),

.apb2_req           (apb_ps2_req        ),
.apb2_ack           (apb_ps2_ack        ),
.apb2_rw            (apb_ps2_rw         ),
.apb2_enab          (apb_ps2_enab       ),
.apb2_psel          (apb_ps2_psel       ),
.apb2_addr          (apb_ps2_addr       ),
.apb2_datai         (apb_ps2_datai      ),
.apb2_datao         (apb_ps2_datao      ),

.apb3_req           (apb_lcd_req        ),
.apb3_ack           (apb_lcd_ack        ),
.apb3_rw            (apb_lcd_rw         ),
.apb3_enab          (apb_lcd_enab       ),
.apb3_psel          (apb_lcd_psel       ),
.apb3_addr          (apb_lcd_addr       ),
.apb3_datai         (apb_lcd_datai      ),
.apb3_datao         (apb_lcd_datao      ),

.apb4_req           (apb_usb_req        ),
.apb4_ack           (apb_usb_ack        ),
.apb4_rw            (apb_usb_rw         ),
.apb4_enab          (apb_usb_enab       ),
.apb4_psel          (apb_usb_psel       ),
.apb4_addr          (apb_usb_addr       ),
.apb4_datai         (apb_usb_datai      ),
.apb4_datao         (apb_usb_datao      )
                                        
);

//uart0
assign apb_uart0_ack = apb_uart0_enab;
UART_TOP uart0
(
.PCLK              (clk              ),
.clk_carrier       (1'b0             ),
.PRST_             (rst_n            ),
.PSEL              (apb_uart0_psel   ),
.PENABLE           (apb_uart0_enab   ),
.PADDR             (apb_uart0_addr[7:0] ),
.PWRITE            (apb_uart0_rw     ),
.PWDATA            (apb_uart0_datai  ),
.URT_PRDATA        (apb_uart0_datao  ),
.INT               (uart0_int         ),
.TXD_o             (uart0_txd_o       ),
.TXD_i             (uart0_txd_i       ),
.TXD_oe            (uart0_txd_oe      ),
.RXD_o             (uart0_rxd_o       ),
.RXD_i             (uart0_rxd_i       ),
.RXD_oe            (uart0_rxd_oe      ),
.RTS               (uart0_rts_o       ),
.CTS               (uart0_cts_i       ),
.DSR               (uart0_dsr_i       ),
.DCD               (uart0_dcd_i       ),
.DTR               (uart0_dtr_o       ),
.RI                (uart0_ri_i        )
);

chiplab_ps2_rx ps2_controller
(
.clk               (clk                 ),
.reset_n           (rst_n               ),
.apb_addr          (apb_ps2_addr        ),
.apb_psel          (apb_ps2_psel        ),
.apb_penable       (apb_ps2_enab        ),
.apb_pwrite        (apb_ps2_rw          ),
.apb_pwdata        (apb_ps2_datai       ),
.apb_prdata        (apb_ps2_datao       ),
.apb_pready        (apb_ps2_ack         ),
.ps2_clk_i         (ps2_clk_i           ),
.ps2_clk_o         (ps2_clk_o           ),
.ps2_clk_t         (ps2_clk_t           ),
.ps2_dat_i         (ps2_dat_i           ),
.ps2_dat_o         (ps2_dat_o           ),
.ps2_dat_t         (ps2_dat_t           ),
.irq               (ps2_int             )
);

nt35510_apb_adapter lcd_controller
(
.reset_n           (rst_n               ),
.clk               (clk                 ),
.apb_addr          (apb_lcd_addr        ),
.apb_psel          (apb_lcd_psel        ),
.apb_penable       (apb_lcd_enab        ),
.apb_pwrite        (apb_lcd_rw          ),
.apb_pwdata        (apb_lcd_datai       ),
.apb_pready        (apb_lcd_ack         ),
.apb_prdata        (apb_lcd_datao       ),
.lcd_nrst          (lcd_nrst            ),
.lcd_csel          (lcd_csel            ),
.lcd_rs            (lcd_rs              ),
.lcd_wr            (lcd_wr              ),
.lcd_rd            (lcd_rd              ),
.lcd_data_in       (lcd_data_i          ),
.lcd_data_out      (lcd_data_o          ),
.lcd_data_t        (lcd_data_t          )
);

apb_usbh_bridge usb_apb_bridge
(
.apb_clk           (clk                 ),
.apb_resetn        (rst_n               ),
.apb_psel          (apb_usb_psel        ),
.apb_penable       (apb_usb_enab        ),
.apb_pwrite        (apb_usb_rw          ),
.apb_paddr         (apb_usb_addr        ),
.apb_pwdata        (apb_usb_datai       ),
.apb_prdata        (apb_usb_datao       ),
.apb_pready        (apb_usb_ack         ),
.usb_clk           (usb_clk             ),
.usb_resetn        (usb_resetn          ),
.usb_clock_present (usb_clock_present   ),
.cfg_awaddr        (usb_cfg_awaddr      ),
.cfg_awvalid       (usb_cfg_awvalid     ),
.cfg_awready       (usb_cfg_awready     ),
.cfg_wdata         (usb_cfg_wdata       ),
.cfg_wstrb         (usb_cfg_wstrb       ),
.cfg_wvalid        (usb_cfg_wvalid      ),
.cfg_wready        (usb_cfg_wready      ),
.cfg_bresp         (usb_cfg_bresp       ),
.cfg_bvalid        (usb_cfg_bvalid      ),
.cfg_bready        (usb_cfg_bready      ),
.cfg_araddr        (usb_cfg_araddr      ),
.cfg_arvalid       (usb_cfg_arvalid     ),
.cfg_arready       (usb_cfg_arready     ),
.cfg_rdata         (usb_cfg_rdata       ),
.cfg_rresp         (usb_cfg_rresp       ),
.cfg_rvalid        (usb_cfg_rvalid      ),
.cfg_rready        (usb_cfg_rready      )
);

usbh_top usb_host_controller
(
.aclk              (usb_clk             ),
.aresetn           (usb_resetn          ),
.intr              (usb_int             ),
.cfg_awvalid       (usb_cfg_awvalid     ),
.cfg_awaddr        (usb_cfg_awaddr      ),
.cfg_wvalid        (usb_cfg_wvalid      ),
.cfg_wdata         (usb_cfg_wdata       ),
.cfg_wstrb         (usb_cfg_wstrb       ),
.cfg_bready        (usb_cfg_bready      ),
.cfg_arvalid       (usb_cfg_arvalid     ),
.cfg_araddr        (usb_cfg_araddr      ),
.cfg_rready        (usb_cfg_rready      ),
.cfg_awready       (usb_cfg_awready     ),
.cfg_wready        (usb_cfg_wready      ),
.cfg_bvalid        (usb_cfg_bvalid      ),
.cfg_bresp         (usb_cfg_bresp       ),
.cfg_arready       (usb_cfg_arready     ),
.cfg_rvalid        (usb_cfg_rvalid      ),
.cfg_rdata         (usb_cfg_rdata       ),
.cfg_rresp         (usb_cfg_rresp       ),
.utmi_data_in      (utmi_data_in        ),
.utmi_data_out     (utmi_data_out       ),
.utmi_data_t       (utmi_data_t         ),
.utmi_reset        (utmi_reset          ),
.utmi_txready      (utmi_txready        ),
.utmi_rxvalid      (utmi_rxvalid        ),
.utmi_rxactive     (utmi_rxactive       ),
.utmi_rxerror      (utmi_rxerror        ),
.utmi_linestate    (utmi_linestate      ),
.utmi_txvalid      (utmi_txvalid        ),
.utmi_opmode       (utmi_opmode         ),
.utmi_xcvrsel      (utmi_xcvrsel        ),
.utmi_termsel      (utmi_termsel        ),
.utmi_dppulldown   (utmi_dppulldown     ),
.utmi_dmpulldown   (utmi_dmpulldown     ),
.utmi_idpullup     (utmi_idpullup       ),
.utmi_chrgvbus     (utmi_chrgvbus       ),
.utmi_dischrgvbus  (utmi_dischrgvbus    ),
.utmi_suspend_n    (utmi_suspend_n      ),
.utmi_hostdisc     (utmi_hostdisc       ),
.utmi_iddig        (utmi_iddig          ),
.utmi_vbusvalid    (utmi_vbusvalid      ),
.utmi_sessend      (utmi_sessend        )
);

//NAND
nand_module nand_module 
(
.nand_type         (nand_type           ),

.clk               (clk                 ),
.rst_n             (rst_n               ),

.apb_psel          (apb_nand_psel       ),
.apb_enab          (apb_nand_enab       ),
.apb_rw            (apb_nand_rw         ),
.apb_addr          (apb_nand_addr       ),
.apb_datai         (apb_nand_datai      ),
.apb_datao         (apb_nand_datao      ),
.apb_ack           (apb_nand_ack        ),

.nand_dma_req_o    (nand_dma_req_o      ),
.nand_dma_ack_i    (nand_dma_ack_i      ),

.nand_ce           (nand_ce             ),
.nand_dat_i        (nand_dat_i          ),
.nand_dat_o        (nand_dat_o          ),
.nand_dat_oe       (nand_dat_oe         ),
.nand_ale          (nand_ale            ),
.nand_cle          (nand_cle            ),
.nand_wr           (nand_wr             ),
.nand_rd           (nand_rd             ),
.nand_rdy          (nand_rdy            ),
.nand_int          (nand_int            )
);

endmodule
