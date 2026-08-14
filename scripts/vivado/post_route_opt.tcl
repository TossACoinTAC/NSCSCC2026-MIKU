if {$argc != 4} {
    error "usage: post_route_opt.tcl INPUT_DCP OUTPUT_DIR BUILD_KIND REQUESTED_MHZ"
}

proc ClockFrequencyMHz {clock_name} {
    set clock [get_clocks -quiet $clock_name]
    if {[llength $clock] != 1} {
        error "expected exactly one timing clock named '$clock_name'"
    }
    return [expr {1000.0 / double([get_property PERIOD $clock])}]
}

set input_dcp [file normalize [lindex $argv 0]]
set output_dir [file normalize [lindex $argv 1]]
set build_kind [lindex $argv 2]
set requested_mhz [expr {double([lindex $argv 3])}]
if {![file isfile $input_dcp]} {
    error "routed DCP does not exist: $input_dcp"
}
file mkdir $output_dir

open_checkpoint $input_dcp
report_timing_summary -delay_type min_max -report_unconstrained \
    -file [file join $output_dir timing_before.rpt]

phys_opt_design -directive AggressiveExplore
route_design -directive AggressiveExplore

report_timing_summary -delay_type min_max -report_unconstrained \
    -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -group cpu_clk -max_paths 50 -nworst 1 \
    -sort_by group -path_type full_clock_expanded \
    -file [file join $output_dir cpu_setup_top50.rpt]
report_drc -file [file join $output_dir soc_top_drc_routed.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_utilization -hierarchical -file [file join $output_dir utilization_routed.rpt]
report_utilization -file [file join $output_dir soc_top_utilization_placed.rpt]

set setup_path [lindex [get_timing_paths -delay_type max -max_paths 1 -nworst 1] 0]
set hold_path [lindex [get_timing_paths -delay_type min -max_paths 1 -nworst 1] 0]
if {$setup_path eq "" || $hold_path eq ""} {
    error "unable to obtain setup and hold timing paths"
}
set setup_wns [get_property SLACK $setup_path]
set hold_wns [get_property SLACK $hold_path]
set actual_cpu_mhz [ClockFrequencyMHz cpu_clk]
set actual_sys_mhz [ClockFrequencyMHz sys_clk]
set actual_ddr_mhz [ClockFrequencyMHz ddr_clk]

set validation [open [file join $output_dir clock_timing_validation.txt] w]
puts $validation [format "build_kind=%s" $build_kind]
puts $validation [format "requested_cpu_mhz=%.6f" $requested_mhz]
puts $validation [format "actual_cpu_mhz=%.6f" $actual_cpu_mhz]
puts $validation [format "actual_sys_mhz=%.6f" $actual_sys_mhz]
puts $validation [format "actual_ddr_mhz=%.6f" $actual_ddr_mhz]
puts $validation [format "setup_wns_ns=%.6f" $setup_wns]
puts $validation [format "hold_wns_ns=%.6f" $hold_wns]
puts $validation [format "source_dcp=%s" $input_dcp]
close $validation

write_checkpoint -force [file join $output_dir soc_top_routed.dcp]
write_bitstream -force [file join $output_dir soc_top.bit]
write_debug_probes -force [file join $output_dir soc_top.ltx]

puts [format "POST_ROUTE_RESULT setup_WNS=%.6f hold_WNS=%.6f" $setup_wns $hold_wns]
close_design
