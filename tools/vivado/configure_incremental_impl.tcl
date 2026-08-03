if {$argc != 2} {
    error "usage: configure_incremental_impl.tcl <project.xpr> <reference.dcp>"
}

set project_xpr [file normalize [lindex $argv 0]]
set reference_dcp [file normalize [lindex $argv 1]]

if {![file isfile $project_xpr]} {
    error "Vivado project not found: $project_xpr"
}
if {![file isfile $reference_dcp]} {
    error "Incremental reference DCP not found: $reference_dcp"
}

open_project $project_xpr
set impl_run [get_runs -quiet impl_1]
if {[llength $impl_run] != 1} {
    error "Expected exactly one implementation run named impl_1"
}

set_property INCREMENTAL_CHECKPOINT $reference_dcp $impl_run
set configured_dcp [file normalize [get_property INCREMENTAL_CHECKPOINT $impl_run]]
if {$configured_dcp ne $reference_dcp} {
    error "Incremental checkpoint property mismatch: $configured_dcp"
}

puts "Configured impl_1 incremental checkpoint: $configured_dcp"
close_project
