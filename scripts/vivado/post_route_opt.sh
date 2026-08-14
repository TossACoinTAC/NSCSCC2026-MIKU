#!/usr/bin/env bash
set -euo pipefail

if (($# != 5)); then
  printf 'usage: %s WORKSPACE INPUT_DCP OUTPUT_DIR BUILD_KIND REQUESTED_MHZ\n' "$0" >&2
  exit 2
fi

workspace=$(realpath "$1")
input_dcp=$(realpath "$2")
output_dir=$(realpath -m "$3")
build_kind=$4
requested_mhz=$5
vivado=${VIVADO:-/opt/Xilinx/Vivado/2023.2/bin/vivado}

case "$output_dir" in
  "$workspace"/build/vivado/*) ;;
  *) printf 'post-route 输出必须位于 %s/build/vivado: %s\n' "$workspace" "$output_dir" >&2; exit 2 ;;
esac
[[ -x $vivado ]] || { printf 'Vivado 不可执行: %s\n' "$vivado" >&2; exit 127; }
[[ -f $input_dcp ]] || { printf 'routed DCP 不存在: %s\n' "$input_dcp" >&2; exit 1; }
case "$build_kind" in perf|func) ;; *) printf 'BUILD_KIND 必须是 perf 或 func\n' >&2; exit 2 ;; esac

mkdir -p "$output_dir"
"$vivado" -mode batch -log "$output_dir/runme.log" -journal "$output_dir/vivado.jou" \
  -source "$workspace/scripts/vivado/post_route_opt.tcl" \
  -tclargs "$input_dcp" "$output_dir" "$build_kind" "$requested_mhz"
