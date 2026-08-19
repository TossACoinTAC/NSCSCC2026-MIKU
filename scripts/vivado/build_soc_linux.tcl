# Build the legacy Linux Chiplab design from a staged, locked source tree.
#
# Arguments:
#   <system_run.xpr> <artifact_dir> <cpu_mhz> <uncore_mhz> [jobs]
#
# The script deliberately uses one normal Vivado implementation run. It does
# not invoke a separate post-route Tcl script or an AggressiveExplore pass.

if {$argc < 4 || $argc > 5} {
    puts stderr {usage: build_soc_linux.tcl <system_run.xpr> <artifact_dir> <cpu_mhz> <uncore_mhz> [jobs]}
    exit 2
}

set project_path [file normalize [lindex $argv 0]]
set artifact_dir [file normalize [lindex $argv 1]]
set cpu_mhz [lindex $argv 2]
set uncore_mhz [lindex $argv 3]
set jobs 8
if {$argc == 5} {
    set jobs [lindex $argv 4]
}

set project_dir [file dirname $project_path]
set chiplab_root [file normalize [file join $project_dir .. .. ..]]
set cpu_dir [file join $chiplab_root IP myCPU]

proc require_file {path label} {
    if {![file isfile $path]} {
        return -code error "$label does not exist: $path"
    }
}

proc require_one {objects label} {
    if {[llength $objects] != 1} {
        return -code error "expected one $label, found [llength $objects]"
    }
    return [lindex $objects 0]
}

proc require_positive_number {value label} {
    if {![string is double -strict $value] || ![expr {$value > 0.0}]} {
        return -code error "invalid $label: $value"
    }
}

proc require_positive_integer {value label} {
    if {![string is integer -strict $value] || ![expr {$value > 0}]} {
        return -code error "invalid $label: $value"
    }
}

proc clock_frequency_mhz {pattern label} {
    set clocks [get_clocks -quiet $pattern]
    if {[llength $clocks] != 1} {
        return -code error "expected one $label clock '$pattern', found [llength $clocks]"
    }
    set period [get_property PERIOD [lindex $clocks 0]]
    if {![string is double -strict $period] || ![expr {$period > 0.0}]} {
        return -code error "$label clock has invalid period: $period"
    }
    return [expr {1000.0 / double($period)}]
}

proc write_clock_validation {path requested_cpu requested_uncore actual_cpu actual_uncore setup_wns setup_tns hold_wns hold_ths} {
    set stream [open $path w]
    puts $stream [format "requested_cpu_mhz=%.6f" $requested_cpu]
    puts $stream [format "requested_uncore_mhz=%.6f" $requested_uncore]
    puts $stream [format "actual_cpu_mhz=%.6f" $actual_cpu]
    puts $stream [format "actual_uncore_mhz=%.6f" $actual_uncore]
    puts $stream [format "setup_wns_ns=%.6f" $setup_wns]
    puts $stream [format "setup_tns_ns=%.6f" $setup_tns]
    puts $stream [format "hold_wns_ns=%.6f" $hold_wns]
    puts $stream [format "hold_ths_ns=%.6f" $hold_ths]
    close $stream
}

require_positive_number $cpu_mhz "CPU frequency"
require_positive_number $uncore_mhz "uncore frequency"
require_positive_integer $jobs "parallel job count"
file mkdir $artifact_dir

set exit_code 0
if {[catch {
    require_file $project_path "Vivado project"
    require_file [file join $cpu_dir mycpu_top.v] "generated CPU RTL"
    require_file [file join $chiplab_root chip soc_demo loongson soc_top.v] "Linux soc_top"
    require_file [file join $chiplab_root chip soc_demo loongson config.h] "Linux config.h"
    require_file [file join $chiplab_root fpga loongson soc_up.xdc] "Linux constraints"

    open_project $project_path
    set part [get_property PART [current_project]]
    if {$part ne "xc7a200tfbg676-2"} {
        return -code error "unexpected FPGA part: $part"
    }
    set_property top soc_top [current_fileset]

    # The XPR already contains the locked Chiplab sources. These files are
    # overlay-only additions and are therefore added explicitly here.
    set cpu_source [file join $cpu_dir mycpu_top.v]
    add_files -scan_for_includes $cpu_source

    foreach peripheral_source [list \
        [file join $chiplab_root IP APB_DEV chiplab_ps2_rx.v] \
        [file join $chiplab_root IP APB_DEV nt35510_apb_adapter.v]] {
        require_file $peripheral_source "Linux peripheral source"
        add_files -norecurse $peripheral_source
    }

    set usb_dir [file join $chiplab_root IP APB_DEV USB]
    set usb_sources [glob -nocomplain -directory $usb_dir *.v]
    if {[llength $usb_sources] == 0} {
        return -code error "USB RTL directory is empty: $usb_dir"
    }
    foreach usb_source $usb_sources {
        require_file $usb_source "USB peripheral source"
    }
    add_files -scan_for_includes $usb_sources

    set usb_ip_file [file join $chiplab_root IP xilinx_ip 2023.2 usb_clock_converter usb_clock_converter.xcix]
    require_file $usb_ip_file "USB AXI clock-converter IP"
    add_files -norecurse $usb_ip_file

    update_ip_catalog
    update_compile_order -fileset sources_1
    set cpu_files [get_files -quiet *IP/myCPU/mycpu_top.v]
    require_one $cpu_files "generated CPU RTL source"
    set usb_ips [get_ips -quiet usb_clock_converter]
    require_one $usb_ips "USB AXI clock-converter IP"

    # clk_out1 drives the CPU and clk_out2 drives the uncore/APB domain in the
    # Linux top. Regenerate this IP after changing both requested frequencies.
    set clock_ip [require_one [get_ips -quiet clk_pll_33] "clk_pll_33 IP"]
    set_property -dict [list \
        CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $cpu_mhz \
        CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $uncore_mhz] $clock_ip
    generate_target all $clock_ip

    foreach ip [get_ips] {
        generate_target all $ip
    }
    report_ip_status -file [file join $artifact_dir ip-status.txt]

    set synth_run [require_one [get_runs synth_1] "synthesis run"]
    set impl_run [require_one [get_runs impl_1] "implementation run"]
    set_property strategy Flow_PerfOptimized_high $synth_run
    set_property strategy Performance_Explore $impl_run
    # Keep the implementation as one normal run. The separate post-route
    # AggressiveExplore flow is intentionally not part of this build.
    set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED false $impl_run
    reset_run $synth_run
    reset_run $impl_run
    launch_runs $impl_run -to_step write_bitstream -jobs $jobs
    wait_on_run $impl_run

    set run_status [get_property STATUS $impl_run]
    set run_progress [get_property PROGRESS $impl_run]
    puts "IMPL_STATUS=$run_status"
    puts "IMPL_PROGRESS=$run_progress"
    if {$run_progress ne "100%" || [string first "Complete" $run_status] < 0} {
        return -code error "implementation did not complete: status=$run_status progress=$run_progress"
    }

    open_run $impl_run
    report_timing_summary -delay_type min_max -report_unconstrained \
        -check_timing_verbose -max_paths 50 -input_pins \
        -file [file join $artifact_dir timing-summary.rpt]
    report_route_status -file [file join $artifact_dir route-status.rpt]
    report_utilization -hierarchical -file [file join $artifact_dir utilization.rpt]
    report_clock_utilization -file [file join $artifact_dir clock-utilization.rpt]
    report_clock_interaction -file [file join $artifact_dir clock-interaction.rpt]
    report_drc -file [file join $artifact_dir drc.rpt]
    report_methodology -file [file join $artifact_dir methodology.rpt]
    write_checkpoint -force [file join $artifact_dir soc_top-routed.dcp]
    set drc_errors [llength [get_drc_violations -quiet -filter {SEVERITY == Error}]]

    set setup_path [lindex [get_timing_paths -delay_type max -max_paths 1 -nworst 1] 0]
    set hold_path [lindex [get_timing_paths -delay_type min -max_paths 1 -nworst 1] 0]
    if {$setup_path eq "" || $hold_path eq ""} {
        return -code error "unable to obtain setup and hold timing paths"
    }
    set setup_wns [get_property SLACK $setup_path]
    set hold_wns [get_property SLACK $hold_path]
    set setup_tns [get_property STATS.TNS $impl_run]
    set hold_ths [get_property STATS.THS $impl_run]
    set actual_cpu_mhz [clock_frequency_mhz clk_out1_clk_pll_33 "CPU"]
    set actual_uncore_mhz [clock_frequency_mhz clk_out2_clk_pll_33 "uncore"]
    write_clock_validation [file join $artifact_dir clock-timing-validation.txt] \
        $cpu_mhz $uncore_mhz $actual_cpu_mhz $actual_uncore_mhz \
        $setup_wns $setup_tns $hold_wns $hold_ths

    set pin_report [open [file join $artifact_dir routed-pins.txt] w]
    foreach port [lsort [get_ports -quiet *]] {
        set name [get_property NAME $port]
        set package_pin [get_property PACKAGE_PIN $port]
        set iostandard [get_property IOSTANDARD $port]
        puts $pin_report "port=$name package_pin=$package_pin iostandard=$iostandard"
    }
    close $pin_report

    set bit_path [file join $project_dir system_run.runs impl_1 soc_top.bit]
    require_file $bit_path "system bitstream"
    file copy -force $bit_path [file join $artifact_dir soc_top.bit]
    foreach ltx [glob -nocomplain [file join $project_dir system_run.runs impl_1 *.ltx]] {
        file copy -force $ltx [file join $artifact_dir [file tail $ltx]]
    }

    puts [format "CLOCKS cpu=%.6f MHz uncore=%.6f MHz setup_WNS=%.6f ns hold_WNS=%.6f ns" \
        $actual_cpu_mhz $actual_uncore_mhz $setup_wns $hold_wns]
    if {$setup_wns < 0.0 || $hold_wns < 0.0} {
        return -code error "timing not met: setup_WNS=$setup_wns hold_WNS=$hold_wns"
    }
    if {$drc_errors != 0} {
        return -code error "DRC reports $drc_errors Error violations"
    }
} message options]} {
    puts stderr "ERROR=$message"
    if {[dict exists $options -errorinfo]} {
        puts stderr "DETAIL=[dict get $options -errorinfo]"
    }
    set exit_code 30
}

catch {close_project}
exit $exit_code
