SHELL := /bin/zsh
.DEFAULT_GOAL := help

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
CPU_DIR ?= $(ROOT_DIR)/nscscc-cpu
CHIPLAB_HOME ?= $(ROOT_DIR)/chiplab
TEAM_CI_DIR ?= $(ROOT_DIR)/nscscc-team-ci
SIM_DIR ?= $(CHIPLAB_HOME)/sims/verilator/run_prog
SOC_RUN_DIR ?= $(CHIPLAB_HOME)/fpga/nscscc-team/run_vivado

VIVADO_HOME ?= /opt/Xilinx/Vivado/2023.2
VIVADO ?= $(VIVADO_HOME)/bin/vivado
SURFER ?= /mnt/d/Surfer/surfer.exe

RUN_SOFTWARE ?= func/func_lab19
TIME_LIMIT ?= 1300000
JOBS ?= 8
SIM_CONFIG_ARGS ?= --run $(RUN_SOFTWARE) --disable-simu-trace --output-uart-info --dump-fst
WAVE ?= $(SIM_DIR)/log/$(RUN_SOFTWARE)_log/simu_trace.fst

CHIPLAB_COMMIT ?= 68c20a539e2be8a05300e714296f5fda8373ee80

.PHONY: help doctor status ci-check cpu-check cpu-generate chiplab-sync \
	sim-configure sim-build sim-run sim wave soc-project soc-impl soc-timing \
	soc-timing-check

help:
	@printf '%s\n' \
		'NSCSCC 2026 workspace entry points' \
		'' \
		'  make doctor          Check WSL2, tools, paths, branches, and platform revision' \
		'  make status          Show root and nested repository state (read-only)' \
		'  make ci-check        Parse the official CI template YAML' \
		'  make cpu-check       Run the nscscc-cpu local correctness/publication gates' \
		'  make cpu-generate    Generate and publish mycpu_top.v from SpinalHDL' \
		'  make chiplab-sync    Generate CPU RTL and copy it into Chiplab IP/myCPU' \
		'  make sim              Build software/model and run Verilator' \
		'  make wave             Open WAVE in Windows Surfer' \
		'  make soc-project      Recreate the local nscscc-team Vivado project' \
		'  make soc-impl         Run complete-SoC implementation, bitstream, and timing gate' \
		'  make soc-timing       Print key lines from the latest implementation timing report' \
		'  make soc-timing-check Reject the latest report if setup/hold timing is negative' \
		'' \
		'Common overrides:' \
		'  RUN_SOFTWARE=func/func_lab19  TIME_LIMIT=1300000  JOBS=8' \
		'  WAVE=/absolute/path/to/file.fst  VIVADO_HOME=/path/to/Vivado/2023.2'

doctor:
	@set -u; \
	missing=0; \
	check_cmd() { \
		if command -v "$$1" >/dev/null 2>&1; then \
			printf '[ok]      %-12s %s\n' "$$1" "$$(command -v "$$1")"; \
		else \
			printf '[missing] %-12s\n' "$$1"; \
			missing=1; \
		fi; \
	}; \
	check_dir() { \
		if [[ -d "$$2" ]]; then \
			printf '[ok]      %-12s %s\n' "$$1" "$$2"; \
		else \
			printf '[missing] %-12s %s\n' "$$1" "$$2"; \
			missing=1; \
		fi; \
	}; \
	if grep -qi microsoft /proc/sys/kernel/osrelease; then \
		printf '[ok]      %-12s %s\n' WSL2 "$$(uname -r)"; \
	else \
		printf '[warning] %-12s %s\n' WSL2 "$$(uname -r)"; \
	fi; \
	check_dir CPU_DIR "$(CPU_DIR)"; \
	check_dir CHIPLAB_HOME "$(CHIPLAB_HOME)"; \
	check_dir TEAM_CI_DIR "$(TEAM_CI_DIR)"; \
	check_cmd java; \
	check_cmd sbt; \
	check_cmd verilator; \
	check_cmd git; \
	check_cmd make; \
	check_cmd wslpath; \
	if [[ -x "$(VIVADO)" ]]; then \
		printf '[ok]      %-12s %s\n' Vivado "$(VIVADO)"; \
	else \
		printf '[missing] %-12s %s\n' Vivado "$(VIVADO)"; \
		missing=1; \
	fi; \
	if [[ -f "$(SURFER)" ]]; then \
		printf '[ok]      %-12s %s\n' Surfer "$(SURFER)"; \
	else \
		printf '[missing] %-12s %s\n' Surfer "$(SURFER)"; \
		missing=1; \
	fi; \
	if [[ -d "$(CPU_DIR)/.git" ]]; then \
		printf '[info]    %-12s branch=%s head=%s\n' CPU \
			"$$(git -C "$(CPU_DIR)" branch --show-current)" \
			"$$(git -C "$(CPU_DIR)" rev-parse --short=12 HEAD)"; \
	fi; \
	if [[ -d "$(CHIPLAB_HOME)/.git" ]]; then \
		chiplab_head="$$(git -C "$(CHIPLAB_HOME)" rev-parse HEAD)"; \
		chiplab_branch="$$(git -C "$(CHIPLAB_HOME)" branch --show-current)"; \
		[[ -n "$$chiplab_branch" ]] || chiplab_branch=detached; \
		printf '[info]    %-12s branch=%s head=%s\n' Chiplab \
			"$$chiplab_branch" \
			"$${chiplab_head:0:12}"; \
		if [[ "$$chiplab_head" == "$(CHIPLAB_COMMIT)" ]]; then \
			printf '[ok]      %-12s %s\n' ChiplabCI "$(CHIPLAB_COMMIT)"; \
		else \
			printf '[error]   Chiplab must be checked out at exact CI snapshot %s\n' \
				"$(CHIPLAB_COMMIT)"; \
			missing=1; \
		fi; \
	fi; \
	exit "$$missing"

status:
	@printf '%s\n' '== workspace root =='
	@git -C "$(ROOT_DIR)" status --short --branch
	@printf '%s\n' '== nscscc-cpu =='
	@git -C "$(CPU_DIR)" status --short --branch
	@printf '%s\n' '== chiplab =='
	@git -C "$(CHIPLAB_HOME)" status --short --branch
	@printf '%s\n' '== nscscc-team-ci =='
	@git -C "$(TEAM_CI_DIR)" status --short --branch

ci-check:
	ruby -e 'require "yaml"; ARGV.each { |path| YAML.load_file(path) }' \
		"$(TEAM_CI_DIR)/parent.yml" "$(TEAM_CI_DIR)/child.yml"

cpu-check:
	$(MAKE) -C "$(CPU_DIR)" all port-check lint yosys-check publish-check

cpu-generate:
	$(MAKE) -C "$(CPU_DIR)" generate-core

chiplab-sync: cpu-generate
	@test -f "$(CPU_DIR)/rtl/mycpu_top.v"
	@mkdir -p "$(CHIPLAB_HOME)/IP/myCPU"
	install -m 0644 "$(CPU_DIR)/rtl/mycpu_top.v" \
		"$(CHIPLAB_HOME)/IP/myCPU/mycpu_top.v"

sim-configure: chiplab-sync
	cd "$(SIM_DIR)" && ./configure.sh $(SIM_CONFIG_ARGS)

sim-build: sim-configure
	$(MAKE) -C "$(SIM_DIR)" -j"$(JOBS)" verilator
	$(MAKE) -C "$(SIM_DIR)" -j"$(JOBS)" testbench
	$(MAKE) -C "$(SIM_DIR)" soft_compile

sim-run: sim-build
	$(MAKE) -C "$(SIM_DIR)" simulation_run_prog TIME_LIMIT="$(TIME_LIMIT)"

sim: sim-run

wave:
	@test -f "$(WAVE)" || { printf 'waveform not found: %s\n' "$(WAVE)" >&2; exit 1; }
	@test -f "$(SURFER)" || { printf 'Surfer not found: %s\n' "$(SURFER)" >&2; exit 1; }
	"$(SURFER)" "$$(wslpath -w "$(WAVE)")"

soc-project: chiplab-sync
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch -source create_project.tcl

soc-impl: soc-project
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch -source bit.tcl
	$(MAKE) soc-timing-check

soc-timing:
	@report="$(SOC_RUN_DIR)/project/loongson.runs/impl_1/timing_summary.rpt"; \
	test -f "$$report" || { printf 'timing report not found: %s\n' "$$report" >&2; exit 1; }; \
	rg -n 'Design Timing Summary|WNS\(ns\)|TNS\(ns\)|WHS\(ns\)|THS\(ns\)|Timing constraints are not met|All user specified timing constraints are met' "$$report"

soc-timing-check:
	@report="$(SOC_RUN_DIR)/project/loongson.runs/impl_1/timing_summary.rpt"; \
	test -f "$$report" || { printf 'timing report not found: %s\n' "$$report" >&2; exit 1; }; \
	summary="$$(awk ' \
		/^[[:space:]]+WNS\(ns\).*WHS\(ns\)/ { found=1; next } \
		found && /^[[:space:]]+-/ { next } \
		found && NF >= 6 { print $$1, $$2, $$5, $$6; exit } \
	' "$$report")"; \
	test -n "$$summary" || { printf 'cannot parse timing summary: %s\n' "$$report" >&2; exit 1; }; \
	read -r wns tns whs ths <<< "$$summary"; \
	printf 'complete-SoC timing: WNS=%s ns TNS=%s ns WHS=%s ns THS=%s ns\n' \
		"$$wns" "$$tns" "$$whs" "$$ths"; \
	awk -v wns="$$wns" -v tns="$$tns" -v whs="$$whs" -v ths="$$ths" \
		'BEGIN { exit !((wns + 0) >= 0 && (tns + 0) == 0 && (whs + 0) >= 0 && (ths + 0) == 0) }' || { \
		printf 'timing gate failed: setup and hold slack must be nonnegative, TNS/THS must be zero\n' >&2; \
		exit 1; \
	}
