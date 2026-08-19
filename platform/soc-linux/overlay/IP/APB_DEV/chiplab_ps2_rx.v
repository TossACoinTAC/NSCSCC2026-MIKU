`timescale 1ns/1ps

/*
 * PS/2 host controller for the Chiplab experiment box.
 *
 * The two-register interface matches the Linux altera_ps2 driver. Reads from
 * offset 0 return FIFO status and one received byte, writes to offset 0 send a
 * host-to-device command, and bit 0 at offset 4 enables the receive interrupt.
 * The transmit timing follows the PS/2 host protocol at a 100 MHz APB clock.
 */
module chiplab_ps2_rx #(
    parameter [20:0] INHIBIT_LIMIT = 21'd10099,
    parameter [20:0] START_HOLD_LIMIT = 21'd499,
    parameter [20:0] WAIT_TIMEOUT_LIMIT = 21'd1499999,
    parameter [20:0] TRANSFER_TIMEOUT_LIMIT = 21'd199999,
    parameter [20:0] RX_TIMEOUT_LIMIT = 21'd199999,
    parameter [7:0]  IDLE_FILTER_LIMIT = 8'hff
) (
    input  wire        clk,
    input  wire        reset_n,

    input  wire [19:0] apb_addr,
    input  wire        apb_psel,
    input  wire        apb_penable,
    input  wire        apb_pwrite,
    input  wire [31:0] apb_pwdata,
    output wire [31:0] apb_prdata,
    output wire        apb_pready,

    input  wire        ps2_clk_i,
    output wire        ps2_clk_o,
    output wire        ps2_clk_t,
    input  wire        ps2_dat_i,
    output wire        ps2_dat_o,
    output wire        ps2_dat_t,
    output wire        irq
);

    localparam [3:0] TX_IDLE       = 4'd0;
    localparam [3:0] TX_INHIBIT    = 4'd1;
    localparam [3:0] TX_START_HOLD = 4'd2;
    localparam [3:0] TX_WAIT_CLOCK = 4'd3;
    localparam [3:0] TX_DATA       = 4'd4;
    localparam [3:0] TX_STOP       = 4'd5;
    localparam [3:0] TX_ACK        = 4'd6;
    localparam [3:0] TX_DONE       = 4'd7;

    reg ps2_clk_meta;
    reg ps2_clk_sync;
    reg ps2_clk_last;
    reg ps2_dat_meta;
    reg ps2_dat_sync;

    reg [7:0] fifo_mem [0:15];
    reg [3:0] fifo_rd_ptr;
    reg [3:0] fifo_wr_ptr;
    reg [4:0] fifo_count;
    reg irq_enable;
    reg command_error;

    reg command_active;
    reg [7:0] command_data;
    reg [3:0] tx_state;
    reg [20:0] tx_count;
    reg [7:0] tx_idle_count;
    reg [3:0] tx_bit;
    reg [8:0] tx_frame;
    reg tx_done;
    reg tx_failed;

    reg rx_active;
    reg [3:0] rx_bit;
    reg [7:0] rx_shift;
    reg rx_parity;
    reg rx_parity_bit;
    reg [7:0] rx_data;
    reg rx_valid;
    reg [20:0] rx_timeout_count;

    wire ps2_clk_fall = ps2_clk_last && !ps2_clk_sync;
    wire ps2_clk_rise = !ps2_clk_last && ps2_clk_sync;
    wire apb_access = apb_psel && apb_penable;
    wire data_read = apb_access && !apb_pwrite && !apb_addr[2];
    wire data_write = apb_access && apb_pwrite && !apb_addr[2];
    wire control_write = apb_access && apb_pwrite && apb_addr[2];
    wire fifo_empty = (fifo_count == 0);
    wire fifo_full = (fifo_count == 5'd16);
    wire fifo_push = rx_valid && !fifo_full;
    wire fifo_pop = data_read && !fifo_empty;
    wire command_complete = tx_done || tx_failed;

    /*
     * APB is a memory-mapped CPU bus, so a write must not wait for a PS/2
     * device to finish a millisecond-scale serial transaction.  Accept the
     * byte in one APB access and run the PS/2 transaction in the background.
     * A write while the one-byte command slot is busy is acknowledged and
     * reported through command_error instead of stalling the CPU.
     */
    assign apb_pready = apb_access;
    assign apb_prdata = apb_addr[2]
        ? {21'b0, command_error, 1'b0, irq, 7'b0, irq_enable}
        : {11'b0, fifo_count, !fifo_empty, 7'b0,
           fifo_empty ? 8'b0 : fifo_mem[fifo_rd_ptr]};
    assign irq = irq_enable && !fifo_empty;

    // PS/2 uses open-drain signaling. A low T drives zero; a high T releases.
    assign ps2_clk_o = 1'b0;
    assign ps2_clk_t = (tx_state == TX_INHIBIT ||
                        tx_state == TX_START_HOLD) ? 1'b0 : 1'b1;
    assign ps2_dat_o = 1'b0;
    assign ps2_dat_t =
        (tx_state == TX_START_HOLD || tx_state == TX_WAIT_CLOCK) ? 1'b0 :
        (tx_state == TX_DATA) ? tx_frame[tx_bit] : 1'b1;

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            ps2_clk_meta <= 1'b1;
            ps2_clk_sync <= 1'b1;
            ps2_clk_last <= 1'b1;
            ps2_dat_meta <= 1'b1;
            ps2_dat_sync <= 1'b1;
        end else begin
            ps2_clk_meta <= ps2_clk_i;
            ps2_clk_sync <= ps2_clk_meta;
            ps2_clk_last <= ps2_clk_sync;
            ps2_dat_meta <= ps2_dat_i;
            ps2_dat_sync <= ps2_dat_meta;
        end
    end

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            command_active <= 1'b0;
            command_data <= 8'b0;
            irq_enable <= 1'b0;
            command_error <= 1'b0;
        end else begin
            if (command_active && command_complete)
                command_active <= 1'b0;

            if (data_write) begin
                if (!command_active) begin
                    command_active <= 1'b1;
                    command_data <= apb_pwdata[7:0];
                    command_error <= 1'b0;
                end else begin
                    command_error <= 1'b1;
                end
            end

            if (control_write) begin
                irq_enable <= apb_pwdata[0];
                if (apb_pwdata[10])
                    command_error <= 1'b0;
            end
            if (tx_failed)
                command_error <= 1'b1;
        end
    end

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            tx_state <= TX_IDLE;
            tx_count <= 21'b0;
            tx_idle_count <= 8'b0;
            tx_bit <= 4'b0;
            tx_frame <= 9'b0;
            tx_done <= 1'b0;
            tx_failed <= 1'b0;
        end else begin
            tx_done <= 1'b0;
            tx_failed <= 1'b0;

            case (tx_state)
                TX_IDLE: begin
                    tx_bit <= 4'b0;
                    if (!command_active) begin
                        tx_count <= 21'b0;
                        tx_idle_count <= 8'b0;
                    end else if (!rx_active && ps2_clk_sync && ps2_dat_sync) begin
                        if (tx_idle_count == IDLE_FILTER_LIMIT) begin
                            tx_frame <= {~^command_data, command_data};
                            tx_idle_count <= 8'b0;
                            tx_count <= 21'b0;
                            tx_state <= TX_INHIBIT;
                        end else if (tx_count == WAIT_TIMEOUT_LIMIT) begin
                            tx_idle_count <= 8'b0;
                            tx_count <= 21'b0;
                            tx_failed <= 1'b1;
                            tx_state <= TX_DONE;
                        end else begin
                            tx_idle_count <= tx_idle_count + 1'b1;
                            tx_count <= tx_count + 1'b1;
                        end
                    end else if (tx_count == WAIT_TIMEOUT_LIMIT) begin
                        tx_idle_count <= 8'b0;
                        tx_count <= 21'b0;
                        tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else begin
                        tx_idle_count <= 8'b0;
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_INHIBIT: begin
                    if (tx_count == INHIBIT_LIMIT) begin
                        tx_count <= 21'b0;
                        tx_state <= TX_START_HOLD;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_START_HOLD: begin
                    if (tx_count == START_HOLD_LIMIT) begin
                        tx_count <= 21'b0;
                        tx_state <= TX_WAIT_CLOCK;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_WAIT_CLOCK: begin
                    if (ps2_clk_fall) begin
                        tx_count <= 21'b0;
                        tx_bit <= 4'b0;
                        tx_state <= TX_DATA;
                    end else if (tx_count == WAIT_TIMEOUT_LIMIT) begin
                        tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_DATA: begin
                    if (ps2_clk_fall) begin
                        tx_count <= 21'b0;
                        if (tx_bit == 4'd8)
                            tx_state <= TX_STOP;
                        else
                            tx_bit <= tx_bit + 1'b1;
                    end else if (tx_count == TRANSFER_TIMEOUT_LIMIT) begin
                        tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_STOP: begin
                    if (ps2_clk_fall) begin
                        tx_count <= 21'b0;
                        tx_state <= TX_ACK;
                    end else if (tx_count == TRANSFER_TIMEOUT_LIMIT) begin
                        tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_ACK: begin
                    if (ps2_clk_rise) begin
                        if (!ps2_dat_sync)
                            tx_done <= 1'b1;
                        else
                            tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else if (tx_count == TRANSFER_TIMEOUT_LIMIT) begin
                        tx_failed <= 1'b1;
                        tx_state <= TX_DONE;
                    end else begin
                        tx_count <= tx_count + 1'b1;
                    end
                end

                TX_DONE: begin
                    tx_count <= 21'b0;
                    if (!command_active)
                        tx_state <= TX_IDLE;
                end

                default: tx_state <= TX_IDLE;
            endcase
        end
    end

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            rx_active <= 1'b0;
            rx_bit <= 4'b0;
            rx_shift <= 8'b0;
            rx_parity <= 1'b0;
            rx_parity_bit <= 1'b0;
            rx_data <= 8'b0;
            rx_valid <= 1'b0;
            rx_timeout_count <= 21'b0;
        end else begin
            rx_valid <= 1'b0;
            if (tx_state != TX_IDLE) begin
                rx_active <= 1'b0;
                rx_bit <= 4'b0;
                rx_timeout_count <= 21'b0;
            end else if (ps2_clk_fall) begin
                rx_timeout_count <= 21'b0;
                if (!rx_active) begin
                    if (!ps2_dat_sync) begin
                        rx_active <= 1'b1;
                        rx_bit <= 4'b0;
                        rx_parity <= 1'b0;
                    end
                end else if (rx_bit < 4'd8) begin
                    rx_shift[rx_bit[2:0]] <= ps2_dat_sync;
                    rx_parity <= rx_parity ^ ps2_dat_sync;
                    rx_bit <= rx_bit + 1'b1;
                end else if (rx_bit == 4'd8) begin
                    rx_parity_bit <= ps2_dat_sync;
                    rx_bit <= rx_bit + 1'b1;
                end else begin
                    rx_active <= 1'b0;
                    rx_bit <= 4'b0;
                    if (ps2_dat_sync && (rx_parity ^ rx_parity_bit)) begin
                        rx_data <= rx_shift;
                        rx_valid <= 1'b1;
                    end
                end
            end else if (rx_active) begin
                if (rx_timeout_count == RX_TIMEOUT_LIMIT) begin
                    rx_active <= 1'b0;
                    rx_bit <= 4'b0;
                    rx_timeout_count <= 21'b0;
                end else begin
                    rx_timeout_count <= rx_timeout_count + 1'b1;
                end
            end else begin
                rx_timeout_count <= 21'b0;
            end
        end
    end

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            fifo_rd_ptr <= 4'b0;
            fifo_wr_ptr <= 4'b0;
            fifo_count <= 5'b0;
        end else begin
            if (fifo_push) begin
                fifo_mem[fifo_wr_ptr] <= rx_data;
                fifo_wr_ptr <= fifo_wr_ptr + 1'b1;
            end
            if (fifo_pop)
                fifo_rd_ptr <= fifo_rd_ptr + 1'b1;

            case ({fifo_push, fifo_pop})
                2'b10: fifo_count <= fifo_count + 1'b1;
                2'b01: fifo_count <= fifo_count - 1'b1;
                default: ;
            endcase
        end
    end

endmodule
