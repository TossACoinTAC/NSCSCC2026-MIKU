#!/usr/bin/env bash
set -euo pipefail

if (($# != 4)); then
  printf 'usage: %s WORKSPACE CHIPLAB COMMIT BUILD_DIR\n' "$0" >&2
  exit 2
fi
workspace=$(realpath "$1")
chiplab=$(realpath "$2")
commit=$3
build_dir=$(realpath -m "$4")
vivado=${VIVADO:-/opt/Xilinx/Vivado/2023.2/bin/vivado}
mhz=${PERF_CPU_MHZ:-100}

case "$build_dir" in
  "$workspace"/build/*) ;;
  *) printf 'Vivado 输出必须位于 %s/build: %s\n' "$workspace" "$build_dir" >&2; exit 2 ;;
esac
[[ -x $vivado ]] || { printf 'Vivado 不可执行: %s\n' "$vivado" >&2; exit 127; }
[[ $(git -C "$chiplab" rev-parse HEAD) == "$commit" ]] || {
  printf 'Chiplab HEAD 与锁定提交不一致\n' >&2; exit 1;
}
[[ -f $workspace/build/rtl/mycpu_top.v ]] || {
  printf '缺少发布 RTL，请先运行 make cpu-generate\n' >&2; exit 1;
}

rm -rf -- "$build_dir"
mkdir -p "$build_dir"
git -C "$chiplab" archive "$commit" | tar -xf - -C "$build_dir"
rm -rf -- "$build_dir/IP/myCPU"
mkdir -p "$build_dir/IP/myCPU"
install -m 0644 "$workspace/build/rtl/mycpu_top.v" "$build_dir/IP/myCPU/mycpu_top.v"

run_dir="$build_dir/fpga/nscscc-team/run_vivado"
config="$build_dir/chip/soc_demo/nscscc-team/soc_config.vh"
mode=perf
if [[ $build_dir == *func* ]]; then
  mode=func
  sed -i '2s|.*|`define RUN_FUNC_TEST|' "$config"
  sed -i '3s|.*|// `define RUN_PERF_TEST|' "$config"
else
  sed -i '2s|.*|// `define RUN_FUNC_TEST|' "$config"
  sed -i '3s|.*|`define RUN_PERF_TEST|' "$config"
  "$vivado" -mode batch -source "$run_dir/generate_perf_pll.tcl" \
    -tclargs "$mhz" "$build_dir/chip/soc_demo/nscscc-team/xilinx_ip/clk_pll/clk_pll.xci" \
    "$run_dir/perf_clock_generated.txt"
fi

# The 100 MHz perf closure uses an AggressiveExplore post-route pass after the
# normal Performance_Explore flow.  Stop the run at route_design, physically
# optimize and reroute, then write the checked-in direct-full bitstream from
# that improved result.  Func builds keep the stock single-pass flow; set
# VIVADO_AGGRESSIVE_POSTROUTE=1 to force the pass there too.
aggressive_postroute=${VIVADO_AGGRESSIVE_POSTROUTE:-1}
if [[ $mode == func ]]; then
  aggressive_postroute=0
fi
if (( aggressive_postroute == 1 )); then
  python3 - "$run_dir/bit.tcl" <<'PY_PATCH_BIT'
import sys

path = sys.argv[1]
with open(path) as handle:
    script = handle.read()
script = script.replace(
    "launch_runs impl_1 -to_step write_bitstream",
    "launch_runs impl_1 -to_step route_design",
    1,
)
insert_before = "open_run impl_1\nreport_timing_summary"
inserted = (
    "open_run impl_1\n"
    "phys_opt_design -directive AggressiveExplore\n"
    "route_design -directive AggressiveExplore\n"
    "write_checkpoint -force project/loongson.runs/impl_1/soc_top_routed.dcp\n"
    "write_bitstream -force project/loongson.runs/impl_1/soc_top.bit\n"
    "write_debug_probes -force project/loongson.runs/impl_1/soc_top.ltx\n"
    "report_timing_summary"
)
if insert_before not in script:
    raise SystemExit("bit.tcl report block not found; cannot apply post-route pass")
script = script.replace(insert_before, inserted, 1)
with open(path, "w") as handle:
    handle.write(script)
PY_PATCH_BIT
fi

(
  cd "$run_dir"
  "$vivado" -mode batch -source create_project.tcl
  "$vivado" -mode batch -source bit.tcl -tclargs "$mode" "$mhz"
)

impl_dir="$run_dir/project/loongson.runs/impl_1"
"$vivado" -mode batch -source "$workspace/scripts/vivado/report_impl.tcl" \
  -tclargs "$impl_dir/soc_top_routed.dcp" "$impl_dir"

printf 'Vivado 完成: mode=%s requested_mhz=%s build=%s\n' "$mode" "$mhz" "$build_dir"
