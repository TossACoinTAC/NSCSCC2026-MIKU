if {$argc != 2} {
  puts stderr "usage: vivado -mode batch -source scripts/vivado/core_top_synth.tcl -tclargs <rtl> <out-dir>"
  exit 2
}

# Keep individual Vivado commands within the host's established thread budget.
set_param general.maxThreads 8

set rtl [file normalize [lindex $argv 0]]
set out_dir [file normalize [lindex $argv 1]]
file mkdir $out_dir

read_verilog -sv $rtl
synth_design -top core_top -part xc7a200tfbg676-2 -flatten_hierarchy rebuilt
create_clock -name cpu_clk -period 10.000 [get_ports aclk]

report_utilization -hierarchical -file [file join $out_dir utilization.rpt]
report_timing_summary -delay_type max -max_paths 20 -file [file join $out_dir timing.rpt]
report_drc -file [file join $out_dir drc.rpt]
write_checkpoint -force [file join $out_dir ooo_core_top_synth.dcp]

set worst_slack [get_property SLACK [get_timing_paths -delay_type max -max_paths 1]]
puts "OOO_CORE_TOP_SYNTH_WNS=$worst_slack"
puts "OOO_CORE_TOP_SYNTH_OUT=$out_dir"
exit 0
