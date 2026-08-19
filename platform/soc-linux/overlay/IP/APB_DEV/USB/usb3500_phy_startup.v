`timescale 1ns/1ps

// SPDX-License-Identifier: GPL-2.0-or-later
//
// Power-state recovery and reset sequencing for the experiment-box USB3500.
module usb3500_phy_startup #(
    parameter [31:0] SUSPEND_CYCLES       = 32'd100000,
    parameter [31:0] CLOCK_TIMEOUT_CYCLES = 32'd500000,
    parameter [31:0] RESET_ASSERT_CYCLES  = 32'd1000,
    parameter [31:0] RETRY_DELAY_CYCLES   = 32'd1000000,
    parameter [31:0] CLOCK_LOST_CYCLES    = 32'd500000,
    parameter [3:0]  CLOCK_STABLE_EDGES   = 4'd8
) (
    input  wire uncore_clk,
    input  wire resetn,
    input  wire utmi_clk,
    input  wire controller_reset,
    input  wire controller_suspend_n,
    output wire phy_reset,
    output reg  phy_suspend_n,
    output reg  clock_present,
    output reg  clock_missing,
    output wire phy_ready
);

localparam [2:0] STATE_SUSPEND      = 3'd0;
localparam [2:0] STATE_WAIT_CLOCK   = 3'd1;
localparam [2:0] STATE_ASSERT_RESET = 3'd2;
localparam [2:0] STATE_RUN          = 3'd3;
localparam [2:0] STATE_RETRY_DELAY  = 3'd4;

reg [2:0]  state;
reg [31:0] cycle_count;
reg [3:0]  stable_edge_count;
reg        startup_reset_request;

// A toggle crosses clock activity into the uncore domain. This remains usable
// while the PHY clock is absent, unlike logic clocked directly by utmi_clk.
reg utmi_clock_toggle;
(* ASYNC_REG = "TRUE" *) reg [2:0] utmi_clock_toggle_sync;
wire utmi_clock_edge = utmi_clock_toggle_sync[2] ^
                       utmi_clock_toggle_sync[1];

always @(posedge utmi_clk or negedge resetn) begin
    if (!resetn)
        utmi_clock_toggle <= 1'b0;
    else
        utmi_clock_toggle <= ~utmi_clock_toggle;
end

always @(posedge uncore_clk or negedge resetn) begin
    if (!resetn) begin
        utmi_clock_toggle_sync <= 3'b000;
        state                  <= STATE_SUSPEND;
        cycle_count            <= 32'b0;
        stable_edge_count      <= 4'b0;
        startup_reset_request  <= 1'b0;
        phy_suspend_n          <= 1'b0;
        clock_present          <= 1'b0;
        clock_missing          <= 1'b0;
    end else begin
        utmi_clock_toggle_sync <= {utmi_clock_toggle_sync[1:0],
                                   utmi_clock_toggle};

        case (state)
        STATE_SUSPEND: begin
            phy_suspend_n         <= 1'b0;
            startup_reset_request <= 1'b0;
            clock_present         <= 1'b0;
            stable_edge_count     <= 4'b0;
            if (cycle_count >= SUSPEND_CYCLES - 1) begin
                cycle_count   <= 32'b0;
                phy_suspend_n <= controller_suspend_n;
                state         <= STATE_WAIT_CLOCK;
            end else begin
                cycle_count <= cycle_count + 1'b1;
            end
        end

        STATE_WAIT_CLOCK: begin
            phy_suspend_n <= controller_suspend_n;
            if (utmi_clock_edge) begin
                cycle_count <= 32'b0;
                if (stable_edge_count >= CLOCK_STABLE_EDGES - 1'b1) begin
                    stable_edge_count     <= 4'b0;
                    startup_reset_request <= 1'b1;
                    clock_present         <= 1'b1;
                    state                 <= STATE_ASSERT_RESET;
                end else begin
                    stable_edge_count <= stable_edge_count + 1'b1;
                end
            end else if (cycle_count >= CLOCK_TIMEOUT_CYCLES - 1) begin
                cycle_count       <= 32'b0;
                stable_edge_count <= 4'b0;
                clock_missing     <= 1'b1;
                state             <= STATE_RETRY_DELAY;
            end else begin
                cycle_count <= cycle_count + 1'b1;
            end
        end

        STATE_ASSERT_RESET: begin
            phy_suspend_n         <= controller_suspend_n;
            startup_reset_request <= 1'b1;
            if (cycle_count >= RESET_ASSERT_CYCLES - 1) begin
                cycle_count            <= 32'b0;
                startup_reset_request <= 1'b0;
                state                  <= STATE_RUN;
            end else begin
                cycle_count <= cycle_count + 1'b1;
            end
        end

        STATE_RUN: begin
            phy_suspend_n         <= controller_suspend_n;
            startup_reset_request <= 1'b0;
            if (utmi_clock_edge) begin
                cycle_count <= 32'b0;
            end else if (cycle_count >= CLOCK_LOST_CYCLES - 1) begin
                cycle_count   <= 32'b0;
                clock_present <= 1'b0;
                clock_missing <= 1'b1;
                phy_suspend_n <= 1'b0;
                state         <= STATE_SUSPEND;
            end else begin
                cycle_count <= cycle_count + 1'b1;
            end
        end

        STATE_RETRY_DELAY: begin
            phy_suspend_n         <= controller_suspend_n;
            startup_reset_request <= 1'b0;
            if (utmi_clock_edge) begin
                cycle_count       <= 32'b0;
                stable_edge_count <= 4'b0;
                state             <= STATE_WAIT_CLOCK;
            end else if (cycle_count >= RETRY_DELAY_CYCLES - 1) begin
                cycle_count   <= 32'b0;
                phy_suspend_n <= 1'b0;
                state         <= STATE_SUSPEND;
            end else begin
                cycle_count <= cycle_count + 1'b1;
            end
        end

        default: begin
            state                 <= STATE_SUSPEND;
            cycle_count           <= 32'b0;
            stable_edge_count     <= 4'b0;
            startup_reset_request <= 1'b0;
            phy_suspend_n         <= 1'b0;
            clock_present         <= 1'b0;
        end
        endcase
    end
end

// USB3500 allows asynchronous RESET assertion but requires synchronous
// deassertion. The shift register also supplies the required post-reset clocks.
wire combined_reset_request = startup_reset_request | controller_reset;
reg [2:0] reset_release_sync;
reg [7:0] ready_shift;

always @(posedge utmi_clk or negedge resetn or posedge combined_reset_request) begin
    if (!resetn) begin
        reset_release_sync <= 3'b000;
        ready_shift        <= 8'b00000000;
    end else if (combined_reset_request) begin
        reset_release_sync <= 3'b111;
        ready_shift        <= 8'b00000000;
    end else begin
        reset_release_sync <= {reset_release_sync[1:0], 1'b0};
        ready_shift        <= {ready_shift[6:0], 1'b1};
    end
end

assign phy_reset = reset_release_sync[2];
assign phy_ready = clock_present && &ready_shift;

endmodule
