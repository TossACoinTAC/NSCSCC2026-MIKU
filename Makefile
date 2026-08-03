SHELL := /bin/zsh
.DEFAULT_GOAL := help

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
CPU_DIR ?= $(ROOT_DIR)/nscscc-cpu
CHIPLAB_HOME := $(ROOT_DIR)/chiplab-nscscc2026
TEAM_CI_DIR ?= $(ROOT_DIR)/nscscc-team-ci
SUBMISSION_DIR ?= $(ROOT_DIR)/T2026144230012607
OFFICIAL_CI_TEMPLATE_DIR ?= $(ROOT_DIR)/build/official-ci-template
SIM_DIR ?= $(CHIPLAB_HOME)/sims/verilator/run_prog
SOC_RUN_DIR ?= $(CHIPLAB_HOME)/fpga/nscscc-team/run_vivado
SOC_PLATFORM_IP_DIR ?= $(CHIPLAB_HOME)/chip/soc_demo/nscscc-team/xilinx_ip
SOC_VIO_DIR ?= $(SOC_PLATFORM_IP_DIR)/vio
SOC_PROJECT_XPR ?= $(SOC_RUN_DIR)/project/loongson.xpr
SOC_IMPL_DIR ?= $(SOC_RUN_DIR)/project/loongson.runs/impl_1
SOC_TIMING_REPORT ?= $(SOC_IMPL_DIR)/timing_summary.rpt
SOC_INCREMENTAL_DIR ?= $(ROOT_DIR)/build/vivado/incremental
SOC_INCREMENTAL_REFERENCE_SOURCE ?= $(SOC_IMPL_DIR)/soc_top_routed.dcp
SOC_INCREMENTAL_REFERENCE_DCP ?= $(SOC_INCREMENTAL_DIR)/reference/soc_top_routed.dcp
SOC_INCREMENTAL_REFERENCE_MANIFEST ?= $(SOC_INCREMENTAL_DIR)/reference/manifest.txt
SOC_INCREMENTAL_REUSE_REPORT ?= $(SOC_INCREMENTAL_DIR)/results/incremental_reuse.rpt
SOC_FUNC_CHIPLAB_DIR ?= $(ROOT_DIR)/build/chiplab-func
SOC_FUNC_RUN_DIR ?= $(SOC_FUNC_CHIPLAB_DIR)/fpga/nscscc-team/run_vivado
SOC_FUNC_IMPL_DIR ?= $(SOC_FUNC_RUN_DIR)/project/loongson.runs/impl_1
SOC_TIMING_POLICY ?= strict
SOC_ARCHIVE_CLASS ?= candidate
SOC_ARCHIVE_NAME ?= perf_$(PERF_CPU_MHZ)mhz
SOC_ARCHIVE_REQUESTED_CPU_MHZ ?= $(PERF_CPU_MHZ)
GATE_DOCKERFILE ?= $(ROOT_DIR)/docker/nscscc-local-gates.Dockerfile
GATE_IMAGE ?= nscscc-local-gates:ubuntu24.04-v1

VIVADO_HOME ?= /opt/Xilinx/Vivado/2023.2
VIVADO ?= $(VIVADO_HOME)/bin/vivado
SURFER ?= /mnt/d/Surfer/surfer.exe
VERILATOR_HOME ?= $(if $(VERILATOR_ROOT),$(VERILATOR_ROOT),/usr/local/share/verilator)
SIM_CPU_DIR ?= $(CPU_DIR)/rtl
SIM_EXTRA_LIBS ?= -llz4
CPU_SBT ?= $(ROOT_DIR)/tools/sbt-local

RUN_SOFTWARE ?= func/func_lab19
TIME_LIMIT ?= 1300000
JOBS ?= 8
AXI_SEED ?= 5570815
PERF_CPU_MHZ ?= 100
SIM_CONFIG_ARGS ?= --run $(RUN_SOFTWARE) --disable-simu-trace --output-uart-info --dump-fst
WAVE ?= $(SIM_DIR)/log/$(RUN_SOFTWARE)_log/simu_trace.fst

CHIPLAB_COMMIT ?= c398d274812f164d387146fa7d8f612a4a1296d9
OFFICIAL_CI_TEMPLATE_COMMIT ?= 6915882af5c8d3a0c856f570cb914920a3e5ff99
OFFICIAL_CI_TEMPLATE_URL ?= ssh://git@111.4.16.59:63222/2026nscsccteam/ci-template-sync-lab-20260721/ci-template.git
MAIN_CPU_COMMIT ?= d9bab16ef46540eb3348b0781afc4d0949f28adc

.PHONY: help doctor status ci-production-sync ci-check gate-image cpu-locked-gates cpu-locked-gates-run cpu-check cpu-generate chiplab-sync \
	sim-configure sim-build sim-run sim wave soc-project soc-impl soc-timing \
	soc-func soc-perf soc-timing-check soc-incremental-reference soc-impl-incremental \
	soc-archive soc-incremental-archive

help:
	@printf '%s\n' \
		'NSCSCC 2026 workspace entry points' \
		'' \
		'  make doctor          Check WSL2, tools, paths, branches, and platform revision' \
		'  make status          Show root and nested repository state (read-only)' \
		'  make ci-production-sync  Fetch and verify the production GitLab CI template' \
		'  make ci-check        Validate the submission include and production CI pins' \
		'  make gate-image      Build the reusable locked Verilator/Yosys image' \
		'  make cpu-locked-gates Refresh version metadata and run RTL gates in that locked image' \
		'  make cpu-locked-gates-run  Reuse the existing locked image without rebuilding it' \
		'  make cpu-check       Run the nscscc-cpu local correctness/publication gates' \
		'  make cpu-generate    Generate and publish mycpu_top.v from SpinalHDL' \
		'  make chiplab-sync    Generate CPU RTL and copy it into Chiplab IP/myCPU' \
		'  make sim              Build software/model and run Verilator' \
		'  make wave             Open WAVE in Windows Surfer' \
		'  make soc-project      Recreate the local nscscc-team Vivado project' \
		'  make soc-func         Run a clean isolated functional-test SoC implementation' \
		'  make soc-impl         Run the 100 MHz complete-SoC implementation and timing gate' \
		'  make soc-archive      Archive the latest implementation with class and timing status' \
		'  make soc-incremental-reference  Preserve the latest routed DCP outside the Vivado project' \
		'  make soc-impl-incremental  Re-synthesize, then implement with the preserved routed DCP' \
		'  make soc-incremental-archive  Archive the current incremental artifacts and hashes' \
		'  make soc-perf         Run the official performance-clock SoC flow (default: 100 MHz)' \
		'  make soc-timing       Print key lines from the latest implementation timing report' \
		'  make soc-timing-check Reject the latest report if setup/hold timing is negative' \
		'' \
		'Common overrides:' \
		'  RUN_SOFTWARE=func/func_lab19  TIME_LIMIT=1300000  JOBS=8  AXI_SEED=5570815' \
		'  PERF_CPU_MHZ=100' \
		'  SOC_ARCHIVE_CLASS=candidate|stable' \
		'  SOC_TIMING_POLICY=strict|report  (report is only for comparison artifacts)' \
		'  VERILATOR_ROOT=/path/to/verilator/source-or-share-root' \
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
	check_dir SUBMISSION "$(SUBMISSION_DIR)"; \
	check_cmd java; \
	if [[ -x "$(CPU_SBT)" ]]; then \
		printf '[ok]      %-12s %s\n' SBTWrapper "$(CPU_SBT)"; \
	else \
		printf '[missing] %-12s %s\n' SBTWrapper "$(CPU_SBT)"; \
		missing=1; \
	fi; \
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
	if git -C "$(CHIPLAB_HOME)" rev-parse --is-inside-work-tree >/dev/null 2>&1; then \
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
	if git -C "$(SUBMISSION_DIR)" rev-parse --is-inside-work-tree >/dev/null 2>&1; then \
		printf '[info]    %-12s branch=%s head=%s\n' Submission \
			"$$(git -C "$(SUBMISSION_DIR)" branch --show-current)" \
			"$$(git -C "$(SUBMISSION_DIR)" rev-parse --short=12 HEAD)"; \
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
	@printf '%s\n' '== official submission =='
	@git -C "$(SUBMISSION_DIR)" status --short --branch

ci-production-sync:
	@mkdir -p "$(dir $(OFFICIAL_CI_TEMPLATE_DIR))"
	@if [[ ! -d "$(OFFICIAL_CI_TEMPLATE_DIR)/.git" ]]; then \
		git clone --no-checkout "$(OFFICIAL_CI_TEMPLATE_URL)" \
			"$(OFFICIAL_CI_TEMPLATE_DIR)"; \
	fi
	@git -C "$(OFFICIAL_CI_TEMPLATE_DIR)" fetch origin master
	@actual="$$(git -C "$(OFFICIAL_CI_TEMPLATE_DIR)" rev-parse origin/master)"; \
	if [[ "$$actual" != "$(OFFICIAL_CI_TEMPLATE_COMMIT)" ]]; then \
		printf 'production CI template moved: expected %s, found %s\n' \
			"$(OFFICIAL_CI_TEMPLATE_COMMIT)" "$$actual" >&2; \
		exit 1; \
	fi; \
	printf 'production CI template: %s\n' "$$actual"

ci-check: ci-production-sync
	@ruby -e 'require "yaml"; YAML.load_file(ARGV.fetch(0))' \
		"$(SUBMISSION_DIR)/.gitlab-ci.yml"
	@git -C "$(OFFICIAL_CI_TEMPLATE_DIR)" show \
		"$(OFFICIAL_CI_TEMPLATE_COMMIT):parent.yml" | \
		ruby -e 'require "yaml"; YAML.load(STDIN.read)'
	@git -C "$(OFFICIAL_CI_TEMPLATE_DIR)" show \
		"$(OFFICIAL_CI_TEMPLATE_COMMIT):child.yml" | \
		ruby -e 'require "yaml"; YAML.load(STDIN.read)'
	@git -C "$(OFFICIAL_CI_TEMPLATE_DIR)" show \
		"$(OFFICIAL_CI_TEMPLATE_COMMIT):child.yml" | \
		rg -q 'EXPECTED_CHIPLAB_COMMIT: "$(CHIPLAB_COMMIT)"'
	@rg -q "project: '2026nscsccteam/ci-template-sync-lab-20260721/ci-template'" \
		"$(SUBMISSION_DIR)/.gitlab-ci.yml"
	@printf 'production CI pins Chiplab %s\n' "$(CHIPLAB_COMMIT)"

gate-image:
	docker build --pull=false -f "$(GATE_DOCKERFILE)" -t "$(GATE_IMAGE)" \
		"$(ROOT_DIR)/docker"

cpu-locked-gates: gate-image cpu-locked-gates-run

cpu-locked-gates-run: cpu-generate
	@docker image inspect "$(GATE_IMAGE)" >/dev/null 2>&1 || { \
		printf 'locked gate image not found: %s\n' "$(GATE_IMAGE)" >&2; \
		printf 'build it first with: make gate-image\n' >&2; \
		exit 1; \
	}
	rm -rf "$(CPU_DIR)/build/core_top/locked-gates"
	docker run --rm \
		--user "$$(id -u):$$(id -g)" \
		-e HOME=/tmp \
		-v "$(ROOT_DIR):/work" \
		-w /work/nscscc-cpu \
		"$(GATE_IMAGE)" sh -ec ' \
			git config --global --add safe.directory /work/nscscc-cpu; \
			python3 -I tools/core_top_gate.py refresh-metadata \
				--repo-root . --manifest reference/manifest.lock \
				--ports reference/core-top.ports.json --rtl rtl/mycpu_top.v \
				--replacement-spec reference/component-replacements/core-top.json \
				--lint-waivers reference/core-top-lint-waivers.json \
				--out-dir build/core_top/locked-gates/metadata \
				--verilator /usr/bin/verilator; \
			python3 -I tools/core_top_gate.py port-check \
				--repo-root . --manifest reference/manifest.lock \
				--ports reference/core-top.ports.json --rtl rtl/mycpu_top.v \
				--out-dir build/core_top/locked-gates/port --yosys /usr/bin/yosys; \
			python3 -I tools/core_top_gate.py lint \
				--repo-root . --manifest reference/manifest.lock \
				--ports reference/core-top.ports.json --rtl rtl/mycpu_top.v \
				--out-dir build/core_top/locked-gates/lint \
				--verilator /usr/bin/verilator --environment-profile locked \
				--waivers reference/core-top-lint-waivers.json; \
			python3 -I tools/core_top_gate.py yosys-check \
				--repo-root . --manifest reference/manifest.lock \
				--ports reference/core-top.ports.json --rtl rtl/mycpu_top.v \
				--out-dir build/core_top/locked-gates/yosys --yosys /usr/bin/yosys; \
			python3 -I tools/core_top_gate.py publish-check \
				--repo-root . --manifest reference/manifest.lock \
				--ports reference/core-top.ports.json --rtl rtl/mycpu_top.v \
				--tracked-rtl rtl/mycpu_top.v \
				--replacement-spec reference/component-replacements/core-top.json \
				--out-dir build/core_top/locked-gates/publish \
		'

cpu-check:
	$(MAKE) -C "$(CPU_DIR)" all port-check lint yosys-check publish-check \
		SBT="$(CPU_SBT)"

cpu-generate:
	$(MAKE) -C "$(CPU_DIR)" generate-core SBT="$(CPU_SBT)"

chiplab-sync: cpu-generate
	@test -f "$(CPU_DIR)/rtl/mycpu_top.v"
	@test -f "$(CPU_DIR)/xilinx_ip/sram/data_bank_sram.xcix"
	@test -f "$(CPU_DIR)/xilinx_ip/sram/tagv_sram.xcix"
	@mkdir -p "$(CHIPLAB_HOME)/IP/myCPU/xilinx_ip/data_bank_sram"
	@mkdir -p "$(CHIPLAB_HOME)/IP/myCPU/xilinx_ip/tagv_sram"
	install -m 0644 "$(CPU_DIR)/rtl/mycpu_top.v" \
		"$(CHIPLAB_HOME)/IP/myCPU/mycpu_top.v"
	install -m 0644 "$(CPU_DIR)/xilinx_ip/sram/data_bank_sram.xcix" \
		"$(CHIPLAB_HOME)/IP/myCPU/xilinx_ip/data_bank_sram/data_bank_sram.xcix"
	install -m 0644 "$(CPU_DIR)/xilinx_ip/sram/tagv_sram.xcix" \
		"$(CHIPLAB_HOME)/IP/myCPU/xilinx_ip/tagv_sram/tagv_sram.xcix"

sim-configure: cpu-generate
	cd "$(SIM_DIR)" && ./configure.sh $(SIM_CONFIG_ARGS)

sim-build: sim-configure
	$(MAKE) -C "$(SIM_DIR)" clean
	$(MAKE) -C "$(SIM_DIR)" -j"$(JOBS)" verilator \
		MYCPU_SRC="$(SIM_CPU_DIR)" VERILATOR_HOME="$(VERILATOR_HOME)"
	$(MAKE) -C "$(SIM_DIR)" -j"$(JOBS)" testbench \
		MYCPU_SRC="$(SIM_CPU_DIR)" VERILATOR_HOME="$(VERILATOR_HOME)" \
		ALL_LIB='./obj_dir/*__ALL.a $(SIM_EXTRA_LIBS)'
	$(MAKE) -C "$(SIM_DIR)" soft_compile

sim-run: sim-build
	$(MAKE) -C "$(SIM_DIR)" simulation_run_prog \
		TIME_LIMIT="$(TIME_LIMIT)" BUS_DELAY_RANDOM_SEED="$(AXI_SEED)"

sim: sim-run

wave:
	@test -f "$(WAVE)" || { printf 'waveform not found: %s\n' "$(WAVE)" >&2; exit 1; }
	@test -f "$(SURFER)" || { printf 'Surfer not found: %s\n' "$(SURFER)" >&2; exit 1; }
	"$(SURFER)" "$$(wslpath -w "$(WAVE)")"

soc-project: chiplab-sync
	rm -rf "$(SOC_VIO_DIR)/gen" "$(SOC_PLATFORM_IP_DIR)/clk_pll_ddr/gen"
	rm -rf "$(SOC_VIO_DIR)/hdl" "$(SOC_VIO_DIR)/synth"
	rm -f "$(SOC_VIO_DIR)/vio_0.dcp" "$(SOC_VIO_DIR)/vio_0.xdc" \
		"$(SOC_VIO_DIR)/vio_0.xml" "$(SOC_VIO_DIR)/vio_0_ooc.xdc" \
		"$(SOC_VIO_DIR)/vio_0_sim_netlist.v" \
		"$(SOC_VIO_DIR)/vio_0_sim_netlist.vhdl" \
		"$(SOC_VIO_DIR)/vio_0_stub.v" "$(SOC_VIO_DIR)/vio_0_stub.vhdl"
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch \
		-source generate_perf_pll.tcl -tclargs "$(PERF_CPU_MHZ)" \
		"$(SOC_PLATFORM_IP_DIR)/clk_pll/clk_pll.xci" \
		"$(SOC_RUN_DIR)/perf_clock_generated.txt"
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch -source create_project.tcl

soc-impl: soc-project
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch -source bit.tcl \
		-tclargs perf "$(PERF_CPU_MHZ)"
	@set +e; \
	$(MAKE) soc-timing-check; \
	timing_status=$$?; \
	set -e; \
	$(MAKE) soc-archive SOC_ARCHIVE_CLASS="$(SOC_ARCHIVE_CLASS)"; \
	exit "$$timing_status"

soc-func: cpu-generate
	@func_dir="$$(realpath -m -- "$(SOC_FUNC_CHIPLAB_DIR)")"; \
	case "$$func_dir" in \
		"$(ROOT_DIR)"/build/*) ;; \
		*) printf 'SOC_FUNC_CHIPLAB_DIR must be below %s/build\n' "$(ROOT_DIR)" >&2; exit 2 ;; \
	esac; \
	rm -rf -- "$$func_dir"; \
	mkdir -p "$$func_dir"
	git -C "$(CHIPLAB_HOME)" archive "$(CHIPLAB_COMMIT)" | \
		tar -xf - -C "$(SOC_FUNC_CHIPLAB_DIR)"
	rm -rf "$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU"
	mkdir -p "$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU/xilinx_ip/data_bank_sram"
	mkdir -p "$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU/xilinx_ip/tagv_sram"
	install -m 0644 "$(CPU_DIR)/rtl/mycpu_top.v" \
		"$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU/mycpu_top.v"
	install -m 0644 "$(CPU_DIR)/xilinx_ip/sram/data_bank_sram.xcix" \
		"$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU/xilinx_ip/data_bank_sram/data_bank_sram.xcix"
	install -m 0644 "$(CPU_DIR)/xilinx_ip/sram/tagv_sram.xcix" \
		"$(SOC_FUNC_CHIPLAB_DIR)/IP/myCPU/xilinx_ip/tagv_sram/tagv_sram.xcix"
	sed -i '2s|.*|`define RUN_FUNC_TEST|' \
		"$(SOC_FUNC_CHIPLAB_DIR)/chip/soc_demo/nscscc-team/soc_config.vh"
	sed -i '3s|.*|// `define RUN_PERF_TEST|' \
		"$(SOC_FUNC_CHIPLAB_DIR)/chip/soc_demo/nscscc-team/soc_config.vh"
	cd "$(SOC_FUNC_RUN_DIR)" && "$(VIVADO)" -mode batch \
		-source create_project.tcl
	cd "$(SOC_FUNC_RUN_DIR)" && "$(VIVADO)" -mode batch \
		-source bit.tcl -tclargs func
	$(MAKE) soc-archive \
		CHIPLAB_HOME="$(SOC_FUNC_CHIPLAB_DIR)" \
		CHIPLAB_COMMIT="$(CHIPLAB_COMMIT)" \
		SOC_IMPL_DIR="$(SOC_FUNC_IMPL_DIR)" \
		SOC_ARCHIVE_NAME=func \
		SOC_ARCHIVE_REQUESTED_CPU_MHZ=platform-default \
		SOC_ARCHIVE_CLASS="$(SOC_ARCHIVE_CLASS)"

soc-archive:
	@set -eu; \
	case "$(SOC_ARCHIVE_CLASS)" in \
		candidate|stable) ;; \
		*) printf 'SOC_ARCHIVE_CLASS must be candidate or stable\n' >&2; exit 2 ;; \
	esac; \
	validation="$(SOC_IMPL_DIR)/clock_timing_validation.txt"; \
	cpu_short="$$(git -C "$(CPU_DIR)" rev-parse --short=12 HEAD)"; \
	if [[ -e "$(CHIPLAB_HOME)/.git" ]]; then \
		chiplab_commit="$$(git -C "$(CHIPLAB_HOME)" rev-parse HEAD)"; \
	else \
		chiplab_commit="$(CHIPLAB_COMMIT)"; \
	fi; \
	chiplab_short="$$(printf '%.12s' "$$chiplab_commit")"; \
	for artifact in \
		"$(CPU_DIR)/rtl/mycpu_top.v" \
		"$(SOC_IMPL_DIR)/soc_top.bit" \
		"$(SOC_IMPL_DIR)/soc_top.ltx" \
		"$(SOC_IMPL_DIR)/soc_top_routed.dcp" \
		"$(SOC_IMPL_DIR)/timing_summary.rpt" \
		"$$validation" \
		"$(SOC_IMPL_DIR)/soc_top_drc_routed.rpt"; do \
		test -s "$$artifact" || { \
			printf 'implementation artifact missing or empty: %s\n' "$$artifact" >&2; \
			exit 1; \
		}; \
	done; \
	build_time="$$(date -r "$$validation" +%Y%m%d-%H%M%S)"; \
	archive="$(ROOT_DIR)/Stable_Backup/cpu_$${cpu_short}_chiplab_$${chiplab_short}_$(SOC_ARCHIVE_NAME)_$${build_time}_$(SOC_ARCHIVE_CLASS)"; \
	if awk -F= ' \
		$$1 == "setup_wns_ns" { setup=$$2; have_setup=1 } \
		$$1 == "hold_wns_ns" { hold=$$2; have_hold=1 } \
		END { exit !(have_setup && have_hold && setup >= 0 && hold >= 0) } \
	' "$$validation"; then \
		timing_status=pass; \
	else \
		timing_status=fail; \
	fi; \
	if [[ "$(SOC_ARCHIVE_CLASS)" == stable && "$$timing_status" != pass ]]; then \
		printf 'cannot mark negative-slack implementation as stable\n' >&2; \
		exit 1; \
	fi; \
	if [[ -e "$$archive" ]]; then \
		printf 'implementation artifacts already archived: %s\n' "$$archive"; \
		exit 0; \
	fi; \
	mkdir -p "$$archive"; \
	install -m 0644 "$(CPU_DIR)/rtl/mycpu_top.v" "$$archive/mycpu_top.v"; \
	for file in soc_top.bit soc_top.ltx soc_top_routed.dcp timing_summary.rpt \
		clock_timing_validation.txt soc_top_drc_routed.rpt; do \
		install -m 0644 "$(SOC_IMPL_DIR)/$$file" "$$archive/$$file"; \
	done; \
	{ \
		printf 'artifact_class=%s\n' "$(SOC_ARCHIVE_CLASS)"; \
		printf 'timing_status=%s\n' "$$timing_status"; \
		printf 'cpu_commit=%s\n' "$$(git -C "$(CPU_DIR)" rev-parse HEAD)"; \
		printf 'chiplab_commit=%s\n' "$$chiplab_commit"; \
		printf 'requested_cpu_mhz=%s\n' "$(SOC_ARCHIVE_REQUESTED_CPU_MHZ)"; \
		for file in mycpu_top.v soc_top.bit soc_top.ltx soc_top_routed.dcp \
			timing_summary.rpt clock_timing_validation.txt soc_top_drc_routed.rpt; do \
			printf '%s_sha256=%s\n' "$${file//./_}" \
				"$$(sha256sum "$$archive/$$file" | awk '{print $$1}')"; \
		done; \
		printf '%s\n' 'clock_timing_validation:'; \
		sed 's/^/  /' "$$archive/clock_timing_validation.txt"; \
	} > "$$archive/manifest.txt"; \
	printf '%s implementation (%s timing) archived: %s\n' \
		"$(SOC_ARCHIVE_CLASS)" "$$timing_status" "$$archive"

soc-incremental-reference:
	@test -f "$(SOC_INCREMENTAL_REFERENCE_SOURCE)" || { \
		printf 'incremental reference DCP not found: %s\n' \
			"$(SOC_INCREMENTAL_REFERENCE_SOURCE)" >&2; \
		exit 1; \
	}
	@mkdir -p "$(dir $(SOC_INCREMENTAL_REFERENCE_DCP))"
	install -m 0644 "$(SOC_INCREMENTAL_REFERENCE_SOURCE)" \
		"$(SOC_INCREMENTAL_REFERENCE_DCP)"
	@dcp_hash="$$(sha256sum "$(SOC_INCREMENTAL_REFERENCE_DCP)" | awk '{print $$1}')"; \
	rtl_hash="$$(sha256sum "$(CPU_DIR)/rtl/mycpu_top.v" | awk '{print $$1}')"; \
	{ \
		printf 'reference_dcp=%s\n' "$(SOC_INCREMENTAL_REFERENCE_DCP)"; \
		printf 'reference_dcp_sha256=%s\n' "$$dcp_hash"; \
		printf 'source_dcp=%s\n' "$(SOC_INCREMENTAL_REFERENCE_SOURCE)"; \
		printf 'chiplab_head=%s\n' \
			"$$(git -C "$(CHIPLAB_HOME)" rev-parse HEAD)"; \
		printf 'cpu_head_at_staging=%s\n' \
			"$$(git -C "$(CPU_DIR)" rev-parse HEAD)"; \
		printf 'workspace_rtl_sha256_at_staging=%s\n' "$$rtl_hash"; \
		printf '%s\n' \
			'provenance_note=workspace CPU/RTL values describe staging context; the DCP itself does not embed a source commit or RTL hash'; \
	} > "$(SOC_INCREMENTAL_REFERENCE_MANIFEST)"
	@printf 'incremental reference: %s\n' "$(SOC_INCREMENTAL_REFERENCE_DCP)"
	@sha256sum "$(SOC_INCREMENTAL_REFERENCE_DCP)"

soc-impl-incremental: soc-incremental-reference
	@case "$(SOC_TIMING_POLICY)" in \
		strict|report) ;; \
		*) printf 'SOC_TIMING_POLICY must be strict or report\n' >&2; exit 2 ;; \
	esac
	$(MAKE) soc-project
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch \
		-source "$(ROOT_DIR)/tools/vivado/configure_incremental_impl.tcl" \
		-tclargs "$(SOC_PROJECT_XPR)" "$(SOC_INCREMENTAL_REFERENCE_DCP)"
	@set +e; \
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch -source bit.tcl \
		-tclargs perf "$(PERF_CPU_MHZ)"; \
	bit_status=$$?; \
	cd "$(SOC_RUN_DIR)" && "$(VIVADO)" -mode batch \
		-source "$(ROOT_DIR)/tools/vivado/report_incremental_reuse.tcl" \
		-tclargs "$(SOC_PROJECT_XPR)" "$(SOC_INCREMENTAL_REUSE_REPORT)"; \
	report_status=$$?; \
	set -e; \
	(( report_status == 0 )) || exit "$$report_status"; \
	if (( bit_status != 0 )); then \
		if [[ "$(SOC_TIMING_POLICY)" != report ]]; then \
			exit "$$bit_status"; \
		fi; \
		validation="$(SOC_IMPL_DIR)/clock_timing_validation.txt"; \
		bitstream="$(SOC_IMPL_DIR)/soc_top.bit"; \
		test -s "$$validation" && test -s "$$bitstream" || { \
			printf 'Vivado failed before producing a validated comparison bitstream\n' >&2; \
			exit "$$bit_status"; \
		}; \
		awk -F= -v expected="$(PERF_CPU_MHZ)" ' \
			$$1 == "actual_cpu_mhz" { cpu=$$2 } \
			$$1 == "actual_sys_mhz" { sys=$$2 } \
			$$1 == "actual_ddr_mhz" { ddr=$$2 } \
			$$1 == "setup_wns_ns" { setup=$$2 } \
			$$1 == "hold_wns_ns" { hold=$$2 } \
			END { \
				tol=expected * 0.01; if (tol < 0.001) tol=0.001; \
				dcpu=cpu-expected; if (dcpu < 0) dcpu=-dcpu; \
				exit !((dcpu <= tol) && sys == 100 && ddr == 200 && \
					(setup < 0 || hold < 0)); \
			} \
		' "$$validation" || { \
			printf 'Vivado failure was not an allowed negative-slack comparison result\n' >&2; \
			exit "$$bit_status"; \
		}; \
		printf 'comparison bitstream retained despite negative slack (SOC_TIMING_POLICY=report)\n'; \
	fi
	@if [[ "$(SOC_TIMING_POLICY)" == strict ]]; then \
		$(MAKE) soc-timing-check; \
	else \
		$(MAKE) soc-timing; \
	fi
	$(MAKE) soc-incremental-archive

soc-incremental-archive:
	@set -u; \
	cpu_short="$$(git -C "$(CPU_DIR)" rev-parse --short=12 HEAD)"; \
	chiplab_short="$$(git -C "$(CHIPLAB_HOME)" rev-parse --short=12 HEAD)"; \
	archive="$(ROOT_DIR)/Stable_Backup/cpu_$${cpu_short}_chiplab_$${chiplab_short}_incremental_$(PERF_CPU_MHZ)mhz"; \
	for artifact in \
		"$(CPU_DIR)/rtl/mycpu_top.v" \
		"$(SOC_IMPL_DIR)/soc_top.bit" \
		"$(SOC_IMPL_DIR)/soc_top_routed.dcp" \
		"$(SOC_IMPL_DIR)/timing_summary.rpt" \
		"$(SOC_IMPL_DIR)/clock_timing_validation.txt" \
		"$(SOC_IMPL_DIR)/soc_top_drc_routed.rpt" \
		"$(SOC_IMPL_DIR)/soc_top_incremental_reuse_routed.rpt" \
		"$(SOC_INCREMENTAL_REUSE_REPORT)" \
		"$(SOC_INCREMENTAL_REFERENCE_MANIFEST)"; do \
		test -s "$$artifact" || { \
			printf 'incremental artifact missing or empty: %s\n' "$$artifact" >&2; \
			exit 1; \
		}; \
	done; \
	mkdir -p "$$archive"; \
	install -m 0644 "$(CPU_DIR)/rtl/mycpu_top.v" "$$archive/mycpu_top.v"; \
	install -m 0644 "$(SOC_IMPL_DIR)/soc_top.bit" "$$archive/soc_top.bit"; \
	install -m 0644 "$(SOC_IMPL_DIR)/soc_top_routed.dcp" \
		"$$archive/soc_top_routed.dcp"; \
	install -m 0644 "$(SOC_IMPL_DIR)/timing_summary.rpt" \
		"$$archive/timing_summary.rpt"; \
	install -m 0644 "$(SOC_IMPL_DIR)/clock_timing_validation.txt" \
		"$$archive/clock_timing_validation.txt"; \
	install -m 0644 "$(SOC_IMPL_DIR)/soc_top_drc_routed.rpt" \
		"$$archive/soc_top_drc_routed.rpt"; \
	install -m 0644 "$(SOC_IMPL_DIR)/soc_top_incremental_reuse_routed.rpt" \
		"$$archive/incremental_reuse_routed.rpt"; \
	install -m 0644 "$(SOC_INCREMENTAL_REUSE_REPORT)" \
		"$$archive/incremental_reuse_hierarchical.rpt"; \
	install -m 0644 "$(SOC_INCREMENTAL_REFERENCE_MANIFEST)" \
		"$$archive/reference_manifest.txt"; \
	{ \
		printf 'cpu_commit=%s\n' "$$(git -C "$(CPU_DIR)" rev-parse HEAD)"; \
		printf 'chiplab_commit=%s\n' "$$(git -C "$(CHIPLAB_HOME)" rev-parse HEAD)"; \
		printf 'requested_cpu_mhz=%s\n' "$(PERF_CPU_MHZ)"; \
		printf 'reference_dcp_sha256=%s\n' \
			"$$(sha256sum "$(SOC_INCREMENTAL_REFERENCE_DCP)" | awk '{print $$1}')"; \
		for file in mycpu_top.v soc_top.bit soc_top_routed.dcp timing_summary.rpt \
			clock_timing_validation.txt soc_top_drc_routed.rpt \
			incremental_reuse_routed.rpt incremental_reuse_hierarchical.rpt \
			reference_manifest.txt; do \
			printf '%s_sha256=%s\n' "$${file//./_}" \
				"$$(sha256sum "$$archive/$$file" | awk '{print $$1}')"; \
		done; \
		printf '%s\n' 'clock_timing_validation:'; \
		sed 's/^/  /' "$$archive/clock_timing_validation.txt"; \
	} > "$$archive/manifest.txt"; \
	printf 'incremental artifacts archived: %s\n' "$$archive"

soc-perf: soc-impl

soc-timing:
	@report="$(SOC_TIMING_REPORT)"; \
	test -f "$$report" || { printf 'timing report not found: %s\n' "$$report" >&2; exit 1; }; \
	rg -n 'Design Timing Summary|WNS\(ns\)|TNS\(ns\)|WHS\(ns\)|THS\(ns\)|Timing constraints are not met|All user specified timing constraints are met' "$$report"

soc-timing-check:
	@report="$(SOC_TIMING_REPORT)"; \
	test -f "$$report" || { printf 'timing report not found: %s\n' "$$report" >&2; exit 1; }; \
	summary="$$(awk -f "$(ROOT_DIR)/tools/vivado/parse_timing_summary.awk" \
		"$$report")" || { \
		printf 'cannot parse timing summary: %s\n' "$$report" >&2; \
		exit 1; \
	}; \
	read -r wns tns whs ths <<< "$$summary"; \
	printf 'complete-SoC timing: WNS=%s ns TNS=%s ns WHS=%s ns THS=%s ns\n' \
		"$$wns" "$$tns" "$$whs" "$$ths"; \
	awk -v wns="$$wns" -v tns="$$tns" -v whs="$$whs" -v ths="$$ths" \
		'BEGIN { exit !((wns + 0) >= 0 && (tns + 0) == 0 && (whs + 0) >= 0 && (ths + 0) == 0) }' || { \
		printf 'timing gate failed: setup and hold slack must be nonnegative, TNS/THS must be zero\n' >&2; \
		exit 1; \
	}
