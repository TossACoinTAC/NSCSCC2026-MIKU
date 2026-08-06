#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)); then
  printf 'usage: %s CHIPLAB_DIR\n' "$0" >&2
  exit 2
fi
root=$(realpath "$1")
[[ -d $root/.git ]] || { printf '不是 Chiplab 工作树: %s\n' "$root" >&2; exit 2; }

targets=(
  "$root/sims/verilator/run_prog/obj"
  "$root/sims/verilator/run_prog/obj_dir"
  "$root/sims/verilator/run_prog/log"
  "$root/sims/verilator/run_prog/output"
  "$root/sims/verilator/run_prog/ram.dat"
  "$root/sims/verilator/run_prog/config.log"
  "$root/fpga/nscscc-team/run_vivado/project"
  "$root/fpga/nscscc-team/run_vivado/perf_clock_generated.txt"
  "$root/fpga/nscscc-team/run_vivado/vivado.jou"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/hdl"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/synth"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0.dcp"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0.xdc"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0.xml"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0_ooc.xdc"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0_sim_netlist.v"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0_sim_netlist.vhdl"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0_stub.v"
  "$root/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0_stub.vhdl"
)
for target in "${targets[@]}"; do
  case "$target" in "$root"/*) ;; *) exit 2 ;; esac
  if [[ -e $target || -L $target ]]; then
    rm -rf -- "$target"
    printf 'clean: %s\n' "${target#"$root/"}"
  fi
done
find "$root/fpga/nscscc-team/run_vivado" -maxdepth 1 -type f \
  \( -name 'vivado_*.backup.jou' -o -name 'vivado*.log' \) -delete
