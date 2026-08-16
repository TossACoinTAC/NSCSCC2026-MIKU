SHELL := /bin/bash
.DEFAULT_GOAL := help

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
-include $(ROOT_DIR)/config/local.env

WORKSPACE_ROOT ?= $(ROOT_DIR)
CPU_DIR ?= $(ROOT_DIR)/cpu
CHIPLAB_HOME ?= $(ROOT_DIR)/chiplab
LINUX_KERNEL_DIR ?= $(ROOT_DIR)/nscscc-linux-kernel
LABAGENT_DIR ?= $(ROOT_DIR)/fpga-lab-agent
LABAGENT_HOST ?= 10.19.75.72
LABAGENT_SSH_KEY ?= $(HOME)/.ssh/id_ed25519
BOARDCTL ?= $(ROOT_DIR)/scripts/board/boardctl
BUILD_ROOT ?= $(ROOT_DIR)/build
DOCKER_IMAGE ?= nscscc-dev:ubuntu24.04-v1
DOCKERFILE ?= $(ROOT_DIR)/docker/nscscc-dev.Dockerfile
DOCKER_CACHE_VOLUME ?= nscscc-sbt-cache-v1
VIVADO_HOME ?= /opt/Xilinx/Vivado/2023.2
VIVADO ?= $(VIVADO_HOME)/bin/vivado
SURFER ?= /mnt/d/Surfer/surfer.exe
JOBS ?= 8
CPU_TEST ?=
CPU_VARIANT ?= default
RUN_SOFTWARE ?= func/func_lab19
TIME_LIMIT ?= 1300000
AXI_SEED ?= 5570815
SIM_PROFILE ?= clean
SIM_SUITE ?= standard
SIM_MEMORY_MODE ?= random
SIM_ARTIFACT_ROOT ?= $(BUILD_ROOT)/sim
SIM_WORKLOADS ?= $(RUN_SOFTWARE)
SIM_SEEDS ?= $(AXI_SEED)
SIM_LANES ?= 2
SIM_ALLOW_THREE ?= 0
SIM_LANE_PEAK_MB ?=
SIM_REBUILD ?= 0
PERF_CPU_MHZ ?= 100
SOC_ARCHIVE_CLASS ?= auto
SOC_BUILD_KIND ?= perf
SOC_BUILD_DIR ?= $(BUILD_ROOT)/chiplab-$(SOC_BUILD_KIND)
SOC_IMPL_DIR ?=
SOC_IMPL_STAGE ?= full
SOC_EXPERIMENT_MANIFEST ?=
EXPERIMENT_ID ?= experiment-$(shell date +%Y%m%d-%H%M%S)
EXPERIMENT_EVIDENCE ?=
EXPERIMENT_MANIFEST ?= $(BUILD_ROOT)/reports/experiments/$(EXPERIMENT_ID)/experiment-manifest.json
BASE_MATRIX ?=
CANDIDATE_MATRIX ?=
COMPARE_ID ?= comparison-$(shell date +%Y%m%d-%H%M%S)
COMPARE_OUT ?= $(BUILD_ROOT)/reports/comparisons/$(COMPARE_ID).json
TIMING_REPORT ?=
TIMING_OUT ?= $(BUILD_ROOT)/reports/timing/$(notdir $(basename $(TIMING_REPORT))).json
PERF_OBSERVATION_MATRIX ?=
PERF_OBSERVATION_OUT ?= $(BUILD_ROOT)/reports/observations/perf20-$(shell date +%Y%m%d-%H%M%S).json
TEST_BASE ?= HEAD
TEST_IMPACT_OUT ?= $(BUILD_ROOT)/reports/test-impact/$(shell date +%Y%m%d-%H%M%S).json
BOARD_JOB ?=
POST_ROUTE_INPUT_DCP ?= $(BUILD_ROOT)/chiplab-perf/fpga/nscscc-team/run_vivado/project/loongson.runs/impl_1/soc_top_routed.dcp
POST_ROUTE_OUTPUT ?= $(BUILD_ROOT)/vivado/postroute-$(shell date +%Y%m%d-%H%M%S)
CHIPLAB_COMMIT ?= c398d274812f164d387146fa7d8f612a4a1296d9
PERF20_TIME_LIMIT ?= 600000000
FUNC58_TIME_LIMIT ?= 30000000
LINUX_TIME_LIMIT ?= 50000000
PERF20_WORKLOADS := perf20/bitcount,perf20/bubble_sort,perf20/coremark,perf20/crc32,perf20/dhrystone,perf20/quick_sort,perf20/select_sort,perf20/sha,perf20/stream_copy,perf20/stringsearch,perf20/fireye_A0,perf20/fireye_B2,perf20/fireye_C0,perf20/fireye_D1,perf20/fireye_I2,perf20/inner_product,perf20/lookup_table,perf20/loop_induction,perf20/my_memcmp,perf20/minmax_sequence
FUNC58_WORKLOADS := func58

CONTAINER_RUN := WORKSPACE_ROOT=$(ROOT_DIR) DOCKER_IMAGE=$(DOCKER_IMAGE) DOCKER_CACHE_VOLUME=$(DOCKER_CACHE_VOLUME) $(ROOT_DIR)/scripts/env/run-in-container
CONTAINER_SIM_PATH := /opt/nscscc/toolchains/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin:/opt/nscscc/toolchains/la32r-QEMU-x86_64-ubuntu-22.04:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

.PHONY: help doctor status ide-setup env-build toolchain-check docs-check experiment-freeze experiment-compare timing-analyze test-impact perf-observation-summary \
  cpu-test cpu-test-all cpu-contract-test cpu-generate cpu-check cpu-locked-gates \
  sim sim-prepare sim-matrix func58-sim perf20-sim linux-sim wave soc-impl soc-func soc-postroute-opt soc-archive soc-timing \
  board-queue board-status board-result \
  clean clean-build clean-cpu clean-sim clean-vivado clean-ide-state clean-all

help:
	@printf '%s\n' \
		'NSCSCC 2026 MIKU 工作区入口' '' \
		'  make doctor             只读检查路径、Docker 和嵌套仓库' \
		'  make status             显示根仓库及受支持子仓库状态' \
		'  make ide-setup          生成指向 cpu/build.sbt 的 BSP 配置' \
		'  make env-build          构建锁定 CPU/仿真工具镜像' \
		'  make docs-check         验证文档入口与候选账本结构' \
		'  make experiment-freeze  冻结源码、RTL、工具和显式证据身份' \
		'  make experiment-compare 比较两组身份兼容的完整 perf20' \
		'  make timing-analyze      自动归类 Vivado top timing paths' \
		'  make test-impact         按变更路径列出必须运行的测试' \
		'  make perf-observation-summary 汇总 instrumented perf20 ROI' \
		'  make cpu-test CPU_TEST=miku.execute.OooExecutionClusterSpec' \
		'  make cpu-contract-test 运行全部轻量 Python 合同测试' \
		'  make cpu-check          Scala、Python、RTL 接口、lint、Yosys 完整门禁' \
		'  make cpu-generate       Docker 内生成并发布 build/rtl/mycpu_top.v' \
		'  make sim                单个软件仿真（RUN_SOFTWARE 可覆盖）' \
		'  make sim-prepare        生成或复用内容寻址的 Chiplab/Verilator 模型' \
		'  make sim-matrix         独立 workload/seed 并行运行' \
		'  make func58-sim         全 func58 固定 seeds' \
		'  make perf20-sim         完整 perf20（包含 stringsearch）' \
		'  make linux-sim          Linux 软件仿真入口' \
		'  make board-queue        查询远程 LabAgent 队列' \
		'  make board-status BOARD_JOB=<id>  查询板测状态' \
		'  make board-result BOARD_JOB=<id>  查询板测终态证据' \
		'  make soc-impl           Vivado 宿主机完整 SoC 实现' \
		'  make soc-postroute-opt  复用 routed DCP 做时序探索（非竞赛产物）' \
		'  make soc-archive        校验并归档当前完整 SoC 实现' \
		'  make wave WAVE=...      用宿主机 Surfer 查看波形' \
		'  make clean              清理可再生构建输出，保留 IDE 状态' \
		'  make clean-all          额外清理显式 IDE 状态' '' \
		'路径覆盖：VIVADO_HOME VIVADO SURFER LABAGENT_HOST LABAGENT_SSH_KEY DOCKER_IMAGE JOBS SIM_LANES' \
		'实现归档：SOC_EXPERIMENT_MANIFEST=... SOC_ARCHIVE_CLASS=auto|candidate|stable' \
		'缓存失效：SIM_REBUILD=1 仅重建当前 sim-prepare 请求对应的缓存项'

board-queue:
	@LABAGENT_HOST="$(LABAGENT_HOST)" LABAGENT_SSH_KEY="$(LABAGENT_SSH_KEY)" $(BOARDCTL) queue

board-status:
	@test -n "$(BOARD_JOB)" || { echo "BOARD_JOB is required" >&2; exit 2; }
	@LABAGENT_HOST="$(LABAGENT_HOST)" LABAGENT_SSH_KEY="$(LABAGENT_SSH_KEY)" $(BOARDCTL) status "$(BOARD_JOB)"

board-result:
	@test -n "$(BOARD_JOB)" || { echo "BOARD_JOB is required" >&2; exit 2; }
	@LABAGENT_HOST="$(LABAGENT_HOST)" LABAGENT_SSH_KEY="$(LABAGENT_SSH_KEY)" $(BOARDCTL) result "$(BOARD_JOB)"

doctor:
	@WORKSPACE_ROOT=$(ROOT_DIR) VIVADO=$(VIVADO) SURFER=$(SURFER) DOCKER_IMAGE=$(DOCKER_IMAGE) \
		python3 scripts/env/doctor.py

status:
	@printf '%s\n' '== 根仓库 ==' && git status --short --branch
	@for repo in chiplab nscscc-linux-kernel fpga-lab-agent; do \
		if git -C "$$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then \
			printf '== %s ==\n' "$$repo"; git -C "$$repo" status --short --branch; \
		else \
			printf '== %s ==\n未初始化（运行 git submodule update --init）\n' "$$repo"; \
		fi; \
	done

ide-setup:
	@python3 scripts/env/ide_setup.py

env-build:
	docker build --pull=false -f "$(DOCKERFILE)" -t "$(DOCKER_IMAGE)" "$(ROOT_DIR)"

toolchain-check: env-build
	@$(CONTAINER_RUN) sh -ec 'java -version; sbt --version; verilator --version; yosys -V; loongarch32r-linux-gnusf-gcc --version | head -n 1'

docs-check:
	@python3 scripts/common/check_docs.py

experiment-freeze: cpu-generate
	@python3 scripts/experiment/freeze.py --root "$(ROOT_DIR)" \
		--experiment-id "$(EXPERIMENT_ID)" --chiplab-dir "$(CHIPLAB_HOME)" \
		--chiplab-commit "$(CHIPLAB_COMMIT)" --docker-image "$(DOCKER_IMAGE)" \
		--generation-manifest "$(BUILD_ROOT)/rtl/generation-manifest.json" \
		$(foreach item,$(EXPERIMENT_EVIDENCE),--evidence "$(item)") --out "$(EXPERIMENT_MANIFEST)"

experiment-compare:
	@test -n "$(strip $(BASE_MATRIX))" || { printf 'BASE_MATRIX 不能为空\n' >&2; exit 2; }
	@test -n "$(strip $(CANDIDATE_MATRIX))" || { printf 'CANDIDATE_MATRIX 不能为空\n' >&2; exit 2; }
	@python3 scripts/experiment/compare.py --baseline "$(BASE_MATRIX)" \
		--candidate "$(CANDIDATE_MATRIX)" --out "$(COMPARE_OUT)"

timing-analyze:
	@test -n "$(strip $(TIMING_REPORT))" || { printf 'TIMING_REPORT 不能为空\n' >&2; exit 2; }
	@python3 scripts/experiment/timing_analyze.py --report "$(TIMING_REPORT)" --out "$(TIMING_OUT)"

perf-observation-summary:
	@test -n "$(strip $(PERF_OBSERVATION_MATRIX))" || { printf 'PERF_OBSERVATION_MATRIX 不能为空\n' >&2; exit 2; }
	@python3 scripts/experiment/perf_observation_summary.py \
		--matrix "$(PERF_OBSERVATION_MATRIX)" --out "$(PERF_OBSERVATION_OUT)"

test-impact:
	@python3 scripts/experiment/test_impact.py --root "$(ROOT_DIR)" \
		--manifest "$(CPU_DIR)/tests/manifest.yml" --base "$(TEST_BASE)" --out "$(TEST_IMPACT_OUT)"

cpu-test:
	@test -n "$(strip $(CPU_TEST))" || { printf 'CPU_TEST 必须是完整 suite 名称\n' >&2; exit 2; }
	@test -f "$(CPU_DIR)/src/test/scala/$(subst .,/,$(CPU_TEST)).scala" || { printf '找不到 Scala suite: %s\n' "$(CPU_TEST)" >&2; exit 2; }
	@SPINAL_SIM_WORKSPACE_ROOT="$(CPU_DIR)/target/spinal-sim/workspaces" SPINAL_SIM_WORKSPACE="$(CPU_DIR)/target/spinal-sim/contracts" $(CONTAINER_RUN) sh -ec 'cd "$(CPU_DIR)"; sbt -batch "testOnly $(CPU_TEST)"'

cpu-test-all:
	@SPINAL_SIM_WORKSPACE_ROOT="$(CPU_DIR)/target/spinal-sim/workspaces" SPINAL_SIM_WORKSPACE="$(CPU_DIR)/target/spinal-sim/contracts" $(CONTAINER_RUN) sh -ec 'cd "$(CPU_DIR)"; sbt -batch test'

cpu-contract-test:
	@$(CONTAINER_RUN) python3 -I -m unittest discover \
		-s "$(CPU_DIR)/tests/python" -p 'test_*.py'

cpu-generate:
	@mkdir -p "$(BUILD_ROOT)/rtl/raw" "$(BUILD_ROOT)/rtl/package"
	@rm -rf "$(BUILD_ROOT)/rtl/raw" "$(BUILD_ROOT)/rtl/package"
	@mkdir -p "$(BUILD_ROOT)/rtl/raw" "$(BUILD_ROOT)/rtl/package"
	@$(CONTAINER_RUN) sh -ec 'cd "$(CPU_DIR)"; sbt -batch "runMain miku.compat.GenerateCoreTopCompat --out-dir $(BUILD_ROOT)/rtl/raw --core-variant $(CPU_VARIANT)"'
	@test -f "$(BUILD_ROOT)/rtl/raw/core_top.v"
	@$(CONTAINER_RUN) python3 -I "$(ROOT_DIR)/scripts/cpu/rtl_contract.py" package \
		--repo-root "$(ROOT_DIR)" --manifest "$(CPU_DIR)/reference/manifest.lock" \
		--ports "$(CPU_DIR)/reference/core-top.ports.json" --rtl "$(BUILD_ROOT)/rtl/raw/core_top.v" \
		--out-dir "$(BUILD_ROOT)/rtl/package"
	@install -m 0644 "$(BUILD_ROOT)/rtl/package/rtl/mycpu_top.v" "$(BUILD_ROOT)/rtl/mycpu_top.v"
	@$(CONTAINER_RUN) python3 scripts/cpu/write_generation_manifest.py --root "$(ROOT_DIR)" \
		--raw "$(BUILD_ROOT)/rtl/raw/core_top.v" --published "$(BUILD_ROOT)/rtl/mycpu_top.v" \
		--core-variant "$(CPU_VARIANT)" --out "$(BUILD_ROOT)/rtl/generation-manifest.json"

cpu-locked-gates: cpu-generate
	@rm -rf "$(BUILD_ROOT)/gates/port" "$(BUILD_ROOT)/gates/lint" "$(BUILD_ROOT)/gates/yosys"
	@mkdir -p "$(BUILD_ROOT)/gates"
	@$(CONTAINER_RUN) python3 -I "$(ROOT_DIR)/scripts/cpu/rtl_contract.py" port-check \
		--repo-root "$(ROOT_DIR)" --manifest "$(CPU_DIR)/reference/manifest.lock" \
		--ports "$(CPU_DIR)/reference/core-top.ports.json" --rtl "$(BUILD_ROOT)/rtl/mycpu_top.v" \
		--out-dir "$(BUILD_ROOT)/gates/port" --yosys /usr/bin/yosys
	@$(CONTAINER_RUN) python3 -I "$(ROOT_DIR)/scripts/cpu/rtl_contract.py" lint \
		--repo-root "$(ROOT_DIR)" --manifest "$(CPU_DIR)/reference/manifest.lock" \
		--ports "$(CPU_DIR)/reference/core-top.ports.json" --rtl "$(BUILD_ROOT)/rtl/mycpu_top.v" \
		--out-dir "$(BUILD_ROOT)/gates/lint" --verilator /usr/bin/verilator --environment-profile local
	@$(CONTAINER_RUN) python3 -I "$(ROOT_DIR)/scripts/cpu/rtl_contract.py" yosys-check \
		--repo-root "$(ROOT_DIR)" --manifest "$(CPU_DIR)/reference/manifest.lock" \
		--ports "$(CPU_DIR)/reference/core-top.ports.json" --rtl "$(BUILD_ROOT)/rtl/mycpu_top.v" \
		--out-dir "$(BUILD_ROOT)/gates/yosys" --yosys /usr/bin/yosys

cpu-check: cpu-test-all cpu-generate cpu-locked-gates docs-check cpu-contract-test

sim-prepare: cpu-generate
	@$(CONTAINER_RUN) "$(ROOT_DIR)/scripts/sim/prepare" \
		--workspace "$(ROOT_DIR)" --artifact-root "$(SIM_ARTIFACT_ROOT)" --cpu-dir "$(CPU_DIR)" \
		--chiplab-dir "$(CHIPLAB_HOME)" --chiplab-commit "$(CHIPLAB_COMMIT)" \
		--profile "$(SIM_PROFILE)" --suite "$(SIM_SUITE)" --workloads "$(SIM_WORKLOADS)" \
		--config-args '--run $(RUN_SOFTWARE) --disable-trace-comp --disable-simu-trace --output-uart-info --dump-fst' \
		--jobs "$(JOBS)" --sim-path "$(CONTAINER_SIM_PATH)" --verilator-home /usr/share/verilator --extra-libs '-llz4' \
		--rebuild "$(SIM_REBUILD)"

sim-matrix:
	@$(CONTAINER_RUN) "$(ROOT_DIR)/scripts/sim/matrix" \
		--workspace "$(ROOT_DIR)" --artifact-root "$(SIM_ARTIFACT_ROOT)" --cpu-dir "$(CPU_DIR)" \
		--chiplab-commit "$(CHIPLAB_COMMIT)" --profile "$(SIM_PROFILE)" --suite "$(SIM_SUITE)" \
		--memory-mode "$(SIM_MEMORY_MODE)" --workloads "$(SIM_WORKLOADS)" --seeds "$(SIM_SEEDS)" \
		--lanes "$(SIM_LANES)" --time-limit "$(TIME_LIMIT)" --sim-path "$(CONTAINER_SIM_PATH)" \
		--allow-three "$(SIM_ALLOW_THREE)" --lane-peak-mb "$(SIM_LANE_PEAK_MB)"

sim: sim-prepare sim-matrix

func58-sim:
	@$(MAKE) sim-prepare SIM_PROFILE="$(SIM_PROFILE)" SIM_SUITE=func58 SIM_WORKLOADS="$(FUNC58_WORKLOADS)" RUN_SOFTWARE=func/func_lab19
	@$(CONTAINER_RUN) "$(ROOT_DIR)/scripts/sim/matrix" --workspace "$(ROOT_DIR)" \
		--artifact-root "$(SIM_ARTIFACT_ROOT)" --cpu-dir "$(CPU_DIR)" --chiplab-commit "$(CHIPLAB_COMMIT)" \
		--profile "$(SIM_PROFILE)" --suite func58 --memory-mode random --workloads "$(FUNC58_WORKLOADS)" \
		--seeds 240,255,141 --lanes "$(SIM_LANES)" --time-limit "$(FUNC58_TIME_LIMIT)" \
		--sim-path "$(CONTAINER_SIM_PATH)" --allow-three "$(SIM_ALLOW_THREE)" --lane-peak-mb "$(SIM_LANE_PEAK_MB)"

perf20-sim:
	@$(MAKE) sim-prepare SIM_PROFILE="$(SIM_PROFILE)" SIM_SUITE=perf20 SIM_WORKLOADS="$(PERF20_WORKLOADS)" RUN_SOFTWARE=coremark
	@$(CONTAINER_RUN) "$(ROOT_DIR)/scripts/sim/matrix" --workspace "$(ROOT_DIR)" \
		--artifact-root "$(SIM_ARTIFACT_ROOT)" --cpu-dir "$(CPU_DIR)" --chiplab-commit "$(CHIPLAB_COMMIT)" \
		--profile "$(SIM_PROFILE)" --suite perf20 --memory-mode ideal --workloads "$(PERF20_WORKLOADS)" \
		--seeds 0 --lanes "$(SIM_LANES)" --time-limit "$(PERF20_TIME_LIMIT)" \
		--sim-path "$(CONTAINER_SIM_PATH)" --allow-three "$(SIM_ALLOW_THREE)" --lane-peak-mb "$(SIM_LANE_PEAK_MB)"

linux-sim:
	@$(MAKE) sim RUN_SOFTWARE=linux SIM_WORKLOADS=linux \
		TIME_LIMIT="$(LINUX_TIME_LIMIT)" SIM_LANES=1

wave:
	@test -f "$(SURFER)" || { printf 'Surfer 不存在: %s\n' "$(SURFER)" >&2; exit 1; }
	@test -n "$(WAVE)" || { printf '请指定 WAVE=/absolute/path/to/file.fst\n' >&2; exit 2; }
	"$(SURFER)" "$$(wslpath -w "$(WAVE)")"

soc-impl: cpu-generate
	@test -n "$(strip $(SOC_EXPERIMENT_MANIFEST))" || { printf 'SOC_EXPERIMENT_MANIFEST 不能为空；先运行 experiment-freeze\n' >&2; exit 2; }
	@test -f "$(SOC_EXPERIMENT_MANIFEST)" || { printf '实验清单不存在: %s\n' "$(SOC_EXPERIMENT_MANIFEST)" >&2; exit 2; }
	@VIVADO="$(VIVADO)" PERF_CPU_MHZ="$(PERF_CPU_MHZ)" \
		scripts/vivado/implement.sh "$(ROOT_DIR)" "$(CHIPLAB_HOME)" "$(CHIPLAB_COMMIT)" "$(BUILD_ROOT)/chiplab-perf"
	@$(MAKE) soc-archive SOC_BUILD_KIND=perf SOC_ARCHIVE_CLASS="$(SOC_ARCHIVE_CLASS)"

soc-func: cpu-generate
	@test -n "$(strip $(SOC_EXPERIMENT_MANIFEST))" || { printf 'SOC_EXPERIMENT_MANIFEST 不能为空；先运行 experiment-freeze\n' >&2; exit 2; }
	@test -f "$(SOC_EXPERIMENT_MANIFEST)" || { printf '实验清单不存在: %s\n' "$(SOC_EXPERIMENT_MANIFEST)" >&2; exit 2; }
	@VIVADO="$(VIVADO)" PERF_CPU_MHZ=32.726797 \
		scripts/vivado/implement.sh "$(ROOT_DIR)" "$(CHIPLAB_HOME)" "$(CHIPLAB_COMMIT)" "$(BUILD_ROOT)/chiplab-func"
	@$(MAKE) soc-archive SOC_BUILD_KIND=func PERF_CPU_MHZ=32.726797 SOC_ARCHIVE_CLASS="$(SOC_ARCHIVE_CLASS)"

soc-postroute-opt:
	@VIVADO="$(VIVADO)" scripts/vivado/post_route_opt.sh \
		"$(ROOT_DIR)" "$(POST_ROUTE_INPUT_DCP)" "$(POST_ROUTE_OUTPUT)" \
		"$(SOC_BUILD_KIND)" "$(PERF_CPU_MHZ)"
	@$(MAKE) soc-archive SOC_BUILD_KIND="$(SOC_BUILD_KIND)" \
		SOC_BUILD_DIR="$(BUILD_ROOT)/chiplab-$(SOC_BUILD_KIND)" \
		SOC_IMPL_DIR="$(POST_ROUTE_OUTPUT)" SOC_IMPL_STAGE=postroute \
		SOC_ARCHIVE_CLASS=candidate PERF_CPU_MHZ="$(PERF_CPU_MHZ)"

soc-archive:
	@test -n "$(strip $(SOC_EXPERIMENT_MANIFEST))" || { printf 'SOC_EXPERIMENT_MANIFEST 不能为空\n' >&2; exit 2; }
	@test -f "$(SOC_EXPERIMENT_MANIFEST)" || { printf '实验清单不存在: %s\n' "$(SOC_EXPERIMENT_MANIFEST)" >&2; exit 2; }
	@case "$(SOC_BUILD_KIND)" in perf|func) ;; *) \
		printf 'SOC_BUILD_KIND 必须是 perf 或 func\n' >&2; exit 2 ;; esac
	@python3 scripts/vivado/archive.py --root "$(ROOT_DIR)" \
		--build-dir "$(SOC_BUILD_DIR)" \
		--chiplab-dir "$(CHIPLAB_HOME)" --chiplab-commit "$(CHIPLAB_COMMIT)" \
		--kind "$(SOC_BUILD_KIND)" --requested-mhz "$(PERF_CPU_MHZ)" \
		--experiment-manifest "$(SOC_EXPERIMENT_MANIFEST)" \
		--class "$(SOC_ARCHIVE_CLASS)" --stage "$(SOC_IMPL_STAGE)" \
		$(if $(strip $(SOC_IMPL_DIR)),--impl-dir "$(SOC_IMPL_DIR)",)

soc-timing:
	@find "$(BUILD_ROOT)" -name timing_summary.rpt -print | sort | tail -1 | xargs -r rg -n "WNS|TNS|Slack|Timing"

clean-build:
	@python3 scripts/common/clean.py build

clean-cpu:
	@python3 scripts/common/clean.py cpu

clean-sim:
	@python3 scripts/common/clean.py sim

clean-vivado:
	@python3 scripts/common/clean.py vivado

clean-ide-state:
	@python3 scripts/common/clean.py ide

clean: clean-build clean-cpu

clean-all: clean clean-ide-state
