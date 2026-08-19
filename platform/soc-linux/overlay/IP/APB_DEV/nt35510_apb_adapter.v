/*
 * APB adapter for the NT35510 parallel LCD interface.
 *
 * Register offset 0 is the instruction port and offset 4 is the data port.
 * The controller stretches APB accesses to satisfy the LCD read/write timing.
 * This implementation is derived from the controller used by
 * trivialmips/nontrivial-mips and keeps its externally verified timing values.
 */
module nt35510_apb_adapter (
    input  wire        reset_n,
    input  wire        clk,

    input  wire [19:0] apb_addr,
    input  wire        apb_psel,
    input  wire        apb_penable,
    input  wire        apb_pwrite,
    input  wire [31:0] apb_pwdata,
    output wire        apb_pready,
    output reg  [31:0] apb_prdata,

    output wire        lcd_nrst,
    output reg         lcd_csel,
    output reg         lcd_rs,
    output reg         lcd_wr,
    output reg         lcd_rd,
    input  wire [15:0] lcd_data_in,
    output reg  [15:0] lcd_data_out,
    output reg  [15:0] lcd_data_t
);

    localparam [2:0] STATE_SETUP    = 3'd0;
    localparam [2:0] STATE_SETUP_RS = 3'd1;
    localparam [2:0] STATE_ACCESS   = 3'd2;
    localparam [2:0] STATE_READY    = 3'd3;
    localparam [2:0] STATE_STALL    = 3'd4;

    localparam integer LCD_RD_CYCLES = 50;
    localparam integer LCD_WR_CYCLES = 5;
    localparam integer LCD_RS_CYCLES = 3;

    reg [2:0] state;
    reg [8:0] cycle_count;
    reg [8:0] target_count;

    assign lcd_nrst = reset_n;
    assign apb_pready = (state == STATE_READY);

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            state <= STATE_SETUP;
            cycle_count <= 9'b0;
            target_count <= 9'b0;
            apb_prdata <= 32'b0;
            lcd_csel <= 1'b1;
            lcd_wr <= 1'b1;
            lcd_rs <= 1'b0;
            lcd_rd <= 1'b1;
            lcd_data_out <= 16'b0;
            lcd_data_t <= 16'hffff;
        end else begin
            case (state)
                STATE_SETUP: begin
                    if (apb_psel && apb_penable) begin
                        lcd_rs <= apb_addr[2];
                        state <= STATE_SETUP_RS;
                        cycle_count <= 9'b0;
                    end
                end

                STATE_SETUP_RS: begin
                    cycle_count <= cycle_count + 1'b1;
                    if (cycle_count == 9'd2) begin
                        cycle_count <= 9'b0;
                        lcd_csel <= 1'b0;
                        if (apb_pwrite) begin
                            lcd_data_t <= 16'h0000;
                            lcd_data_out <= apb_pwdata[15:0];
                            lcd_wr <= 1'b0;
                            target_count <= 9'd5;
                        end else begin
                            lcd_rd <= 1'b0;
                            target_count <= 9'd50;
                        end
                        state <= STATE_ACCESS;
                    end
                end

                STATE_ACCESS: begin
                    cycle_count <= cycle_count + 1'b1;
                    if (cycle_count == target_count - 1'b1) begin
                        if (!apb_pwrite)
                            apb_prdata <= {16'b0, lcd_data_in};
                        lcd_wr <= 1'b1;
                        lcd_rd <= 1'b1;
                        state <= STATE_READY;
                    end
                end

                STATE_READY: begin
                    if (!(apb_psel && apb_penable)) begin
                        cycle_count <= 9'b0;
                        state <= STATE_STALL;
                    end
                end

                STATE_STALL: begin
                    cycle_count <= cycle_count + 1'b1;
                    if (cycle_count == target_count - 1'b1) begin
                        state <= STATE_SETUP;
                        lcd_csel <= 1'b1;
                        lcd_data_t <= 16'hffff;
                    end
                end

                default: state <= STATE_SETUP;
            endcase
        end
    end
endmodule
