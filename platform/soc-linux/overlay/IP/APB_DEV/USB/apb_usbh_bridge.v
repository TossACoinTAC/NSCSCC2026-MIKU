// SPDX-License-Identifier: GPL-2.0-or-later
//
// One-outstanding-transaction APB to AXI bridge for the USB host.
// The Xilinx clock converter carries register accesses from the SoC APB clock
// to the UTMI clock used by the USB controller.
module apb_usbh_bridge (
    input  wire        apb_clk,
    input  wire        apb_resetn,
    input  wire        apb_psel,
    input  wire        apb_penable,
    input  wire        apb_pwrite,
    input  wire [19:0] apb_paddr,
    input  wire [31:0] apb_pwdata,
    output reg  [31:0] apb_prdata,
    output wire        apb_pready,

    input  wire        usb_clk,
    input  wire        usb_resetn,
    input  wire        usb_clock_present,
    output wire [31:0] cfg_awaddr,
    output wire        cfg_awvalid,
    input  wire        cfg_awready,
    output wire [31:0] cfg_wdata,
    output wire [3:0]  cfg_wstrb,
    output wire        cfg_wvalid,
    input  wire        cfg_wready,
    input  wire [1:0]  cfg_bresp,
    input  wire        cfg_bvalid,
    output wire        cfg_bready,
    output wire [31:0] cfg_araddr,
    output wire        cfg_arvalid,
    input  wire        cfg_arready,
    input  wire [31:0] cfg_rdata,
    input  wire [1:0]  cfg_rresp,
    input  wire        cfg_rvalid,
    output wire        cfg_rready
);

localparam [2:0] IDLE     = 3'd0;
localparam [2:0] WRITE_A  = 3'd1;
localparam [2:0] WRITE_D  = 3'd2;
localparam [2:0] WRITE_B  = 3'd3;
localparam [2:0] READ_A   = 3'd4;
localparam [2:0] READ_D   = 3'd5;
localparam [2:0] COMPLETE = 3'd6;

reg [2:0]  state;
reg [31:0] request_addr;
reg [31:0] request_data;

wire [1:0]  source_bresp;
wire        source_bvalid;
wire        source_awready;
wire        source_wready;
wire [31:0] source_rdata;
wire [1:0]  source_rresp;
wire        source_rvalid;
wire        source_arready;
wire [2:0]  master_awprot;
wire [2:0]  master_arprot;

assign apb_pready = (state == COMPLETE);

always @(posedge apb_clk or negedge apb_resetn) begin
    if (!apb_resetn) begin
        state        <= IDLE;
        request_addr <= 32'b0;
        request_data <= 32'b0;
        apb_prdata   <= 32'b0;
    end else begin
        case (state)
        IDLE: begin
            if (apb_psel && apb_penable) begin
                if (!usb_clock_present) begin
                    apb_prdata <= 32'b0;
                    state      <= COMPLETE;
                end else begin
                    request_addr <= {12'b0, apb_paddr};
                    request_data <= apb_pwdata;
                    state <= apb_pwrite ? WRITE_A : READ_A;
                end
            end
        end
        WRITE_A: begin
            if (source_awready)
                state <= WRITE_D;
        end
        WRITE_D: begin
            if (source_wready)
                state <= WRITE_B;
        end
        WRITE_B: begin
            if (source_bvalid) begin
                apb_prdata <= {30'b0, source_bresp};
                state      <= COMPLETE;
            end
        end
        READ_A: begin
            if (source_arready)
                state <= READ_D;
        end
        READ_D: begin
            if (source_rvalid) begin
                apb_prdata <= source_rdata;
                state      <= COMPLETE;
            end
        end
        COMPLETE: begin
            state <= IDLE;
        end
        default: state <= IDLE;
        endcase
    end
end

// This dedicated AXI4-Lite converter matches the hardware-tested JIT-THU USB
// integration. WRITE_A and WRITE_D keep the address handshake ahead of the
// data handshake required by the JIT-THU register block.
usb_clock_converter usb_register_clock_converter (
    .s_axi_aclk       (apb_clk),
    .s_axi_aresetn    (apb_resetn),
    .s_axi_awaddr     (request_addr),
    .s_axi_awprot     (3'b0),
    .s_axi_awvalid    (state == WRITE_A),
    .s_axi_awready    (source_awready),
    .s_axi_wdata      (request_data),
    .s_axi_wstrb      (4'hf),
    .s_axi_wvalid     (state == WRITE_D),
    .s_axi_wready     (source_wready),
    .s_axi_bresp      (source_bresp),
    .s_axi_bvalid     (source_bvalid),
    .s_axi_bready     (state == WRITE_B),
    .s_axi_araddr     (request_addr),
    .s_axi_arprot     (3'b0),
    .s_axi_arvalid    (state == READ_A),
    .s_axi_arready    (source_arready),
    .s_axi_rdata      (source_rdata),
    .s_axi_rresp      (source_rresp),
    .s_axi_rvalid     (source_rvalid),
    .s_axi_rready     (state == READ_D),

    .m_axi_aclk       (usb_clk),
    .m_axi_aresetn    (usb_resetn),
    .m_axi_awaddr     (cfg_awaddr),
    .m_axi_awprot     (master_awprot),
    .m_axi_awvalid    (cfg_awvalid),
    .m_axi_awready    (cfg_awready),
    .m_axi_wdata      (cfg_wdata),
    .m_axi_wstrb      (cfg_wstrb),
    .m_axi_wvalid     (cfg_wvalid),
    .m_axi_wready     (cfg_wready),
    .m_axi_bresp      (cfg_bresp),
    .m_axi_bvalid     (cfg_bvalid),
    .m_axi_bready     (cfg_bready),
    .m_axi_araddr     (cfg_araddr),
    .m_axi_arprot     (master_arprot),
    .m_axi_arvalid    (cfg_arvalid),
    .m_axi_arready    (cfg_arready),
    .m_axi_rdata      (cfg_rdata),
    .m_axi_rresp      (cfg_rresp),
    .m_axi_rvalid     (cfg_rvalid),
    .m_axi_rready     (cfg_rready)
);

wire unused_response_metadata = |{source_rresp, master_awprot,
                                  master_arprot};

endmodule
