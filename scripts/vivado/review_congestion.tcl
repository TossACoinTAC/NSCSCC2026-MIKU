if {$argc != 2} {
    error "usage: review_congestion.tcl PLACED_OR_PHYSOPT_DCP OUTPUT_DIR"
}

set input_dcp [file normalize [lindex $argv 0]]
set output_dir [file normalize [lindex $argv 1]]
if {![file isfile $input_dcp]} {
    error "DCP does not exist: $input_dcp"
}
file mkdir $output_dir

open_checkpoint $input_dcp
set cpu_clock [get_clocks -quiet cpu_clk]
if {[llength $cpu_clock] != 1} {
    error "expected exactly one cpu_clk in $input_dcp"
}

# These reports are intentionally read-only. They expose placement density,
# logic complexity and timing topology without converting a partial run into
# implementation evidence.
report_design_analysis -congestion -min_congestion_level 3 \
    -file [file join $output_dir design_congestion.rpt]
set complexity_report [file join $output_dir design_complexity.rpt]
set complexity_cells [list \
    u_cpu/backendArea_core/systemArea_core/frontend \
    u_cpu/backendArea_core/systemArea_core/backend/backend/backend/rob \
    u_cpu/backendArea_core/systemArea_core/backend/backend/backend/issueQueues_0 \
    u_cpu/backendArea_core/systemArea_core/backend/backend/backend/issueQueues_1 \
    u_cpu/backendArea_core/systemArea_core/backend/backend/backend/issueQueues_2 \
    u_cpu/backendArea_core/systemArea_core/backend/backend/backend/issueQueues_3 \
    u_cpu/backendArea_core/systemArea_core/backend/backend/loadStoreQueue_1 \
    u_cpu/backendArea_core/systemArea_core/backend/cacheHierarchy/l1i \
    u_cpu/backendArea_core/systemArea_core/backend/cacheHierarchy/l1d \
    u_cpu/backendArea_core/systemArea_core/backend/cacheHierarchy/l2]
set first_complexity_report true
foreach cell_name $complexity_cells {
    set cell [get_cells -quiet $cell_name]
    if {[llength $cell] == 1} {
        if {$first_complexity_report} {
            report_design_analysis -complexity -cells $cell \
                -rent_greater_than 0.0 -instances_greater_than 100 \
                -av_fanout_greater_than 0.0 -file $complexity_report
            set first_complexity_report false
        } else {
            report_design_analysis -complexity -cells $cell \
                -rent_greater_than 0.0 -instances_greater_than 100 \
                -av_fanout_greater_than 0.0 -append -file $complexity_report
        }
    }
}
report_timing -delay_type max -group cpu_clk \
    -max_paths 100 -nworst 1 -sort_by group -path_type full_clock_expanded \
    -file [file join $output_dir cpu_setup_top100.rpt]
report_high_fanout_nets -timing -load_types -max_nets 200 \
    -file [file join $output_dir high_fanout_timing.rpt]
report_utilization -hierarchical \
    -file [file join $output_dir utilization_hierarchical.rpt]
report_control_sets -verbose \
    -file [file join $output_dir control_sets.rpt]
close_design
