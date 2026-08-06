if {$argc != 2} {
    error "usage: report_incremental_reuse.tcl <project.xpr> <report.rpt>"
}

set project_xpr [file normalize [lindex $argv 0]]
set report_path [file normalize [lindex $argv 1]]

if {![file isfile $project_xpr]} {
    error "Vivado project not found: $project_xpr"
}

file mkdir [file dirname $report_path]
open_project $project_xpr
set impl_run [get_runs -quiet impl_1]
if {[llength $impl_run] != 1} {
    error "Expected exactly one implementation run named impl_1"
}

open_run $impl_run
report_incremental_reuse -hierarchical -hierarchical_depth 3 -file $report_path
if {![file isfile $report_path] || [file size $report_path] == 0} {
    error "Incremental reuse report was not generated: $report_path"
}
puts "Incremental reuse report: $report_path"
close_design
close_project
