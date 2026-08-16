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
# The locked Chiplab flow defaults to Performance_Explore.  MIKU closes at
# 100 MHz with the standard post-route physical optimization strategy; keep
# this explicit so the archived timing reports describe the bitstream's
# actual post-route state rather than the pre-postroute routed DCP.
sed -i 's|set_property strategy Performance_Explore .get_runs impl_1.|set_property strategy Performance_ExplorePostRoutePhysOpt [get_runs impl_1]|' \
  "$build_dir/fpga/nscscc-team/run_vivado/create_project.tcl"

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

(
  cd "$run_dir"
  "$vivado" -mode batch -source create_project.tcl
  "$vivado" -mode batch -source bit.tcl -tclargs "$mode" "$mhz"
)

impl_dir="$run_dir/project/loongson.runs/impl_1"
final_dcp="$impl_dir/soc_top_routed.dcp"
if [[ -f "$impl_dir/soc_top_postroute_physopt.dcp" ]]; then
  final_dcp="$impl_dir/soc_top_postroute_physopt.dcp"
fi
"$vivado" -mode batch -source "$workspace/scripts/vivado/report_impl.tcl" \
  -tclargs "$final_dcp" "$impl_dir"

if [[ ${REQUIRE_TIMING_CLOSURE:-1} == 1 ]]; then
  awk -F= '
    /^setup_wns_ns=/ { setup = $2 + 0.0 }
    /^hold_wns_ns=/ { hold = $2 + 0.0 }
    END {
      if (setup < 0.0 || hold < 0.0) {
        printf "timing closure check failed: setup=%f hold=%f\n", setup, hold > "/dev/stderr"
        exit 1
      }
    }
  ' "$impl_dir/clock_timing_validation.txt"
fi

printf 'Vivado 完成: mode=%s requested_mhz=%s build=%s final_dcp=%s\n' \
  "$mode" "$mhz" "$build_dir" "$(basename "$final_dcp")"
