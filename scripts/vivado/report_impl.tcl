if {$argc != 2} {
    error "usage: report_impl.tcl ROUTED_DCP OUTPUT_DIR"
}

set routed_dcp [file normalize [lindex $argv 0]]
set output_dir [file normalize [lindex $argv 1]]
if {![file isfile $routed_dcp]} {
    error "routed DCP does not exist: $routed_dcp"
}
file mkdir $output_dir

open_checkpoint $routed_dcp
set cpu_clock [get_clocks -quiet cpu_clk]
if {[llength $cpu_clock] != 1} {
    error "expected exactly one cpu_clk in $routed_dcp"
}

report_timing -delay_type max -group cpu_clk \
    -max_paths 50 -nworst 1 -sort_by group -path_type full_clock_expanded \
    -file [file join $output_dir cpu_setup_top50.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_utilization -hierarchical -file [file join $output_dir utilization_routed.rpt]
close_design
