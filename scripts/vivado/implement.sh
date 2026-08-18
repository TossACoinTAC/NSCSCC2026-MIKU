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

(
  cd "$run_dir"
  "$vivado" -mode batch -source create_project.tcl
  "$vivado" -mode batch -source bit.tcl -tclargs "$mode" "$mhz"
)

impl_dir="$run_dir/project/loongson.runs/impl_1"
"$vivado" -mode batch -source "$workspace/scripts/vivado/report_impl.tcl" \
  -tclargs "$impl_dir/soc_top_routed.dcp" "$impl_dir"

printf 'Vivado 完成: mode=%s requested_mhz=%s build=%s\n' "$mode" "$mhz" "$build_dir"
