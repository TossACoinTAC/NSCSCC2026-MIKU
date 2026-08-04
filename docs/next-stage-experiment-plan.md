# `6bbca9b` 后下一阶段实验计划

## 1. 目的与边界

本计划把 `Info/CurrentDesign/Analysis/current-microarchitecture-study-notes.md` 的十二阶段
分析转化为可执行实验。微架构语义审计区间固定为：

```text
d9bab16ef46540eb3348b0781afc4d0949f28adc
  ..6bbca9b330ba8d886c888e2804f70b95be18e4cd
```

`6bbca9b` 是本计划的主要功能基线：DBAR/IBAR、CACOP、CPUCFG、单核非一致平台下的
LL/SC、uncached Store 的 AXI B completion、response ID 和返回冲突等 Linux system
semantics 已进入当前实现。其后的 ignore、验证状态和说明文档提交不是新的微架构终点。

本计划解决四个问题：

1. 先证明二次静态审计发现的 `C01-C08` 不会破坏正确性；复现错误时先修复。
2. 建立可对账的仿真观测，解释当前 `IPC < 1` 的主要暴露瓶颈。
3. 让每个性能候选都有独立配置开关和配对 A/B；多个相互独立且已证明有效的候选可以
   合并进入一次完整 SoC implementation，以压缩 Vivado 周期。
4. 用 matching RTL 的完整 SoC 周期和 Fmax 联合判断，而不是单独追求 IPC 或 WNS。

本文是实验合同，不是验证结果。当前候选、哈希、仿真、实现和板测结果仍以
[`refactor/status.yml`](../nscscc-cpu/docs/refactor/status.yml) 为唯一状态源。每个实验还必须保存自己的不可变
manifest，不能因为 status 后来更新而改变历史实验身份。

本文引用的 `Cxx`、`Fxx`、`Exx`、`Hxx`、`Lxx`、`Uxx` 等编号，其完整机制、风险和
决策指标统一定义在
[`current-microarchitecture-study-notes.md` 的“5. 优化候选账本”](../Info/CurrentDesign/Analysis/current-microarchitecture-study-notes.md#5-优化候选账本)。
本计划只记录这些候选进入哪一轮实验、采用什么开关和通过何种门禁，不另建一套可能漂移的
候选定义。

## 2. 滚动证据基线

RTL 在本阶段持续演进，因此不再用一个“当前”标签混合不同候选的证据。每条结论必须绑定
source commit 和 generated RTL hash：

| 层级 | CPU / RTL | 可使用的证据 | 明确不能继承的结论 |
| --- | --- | --- | --- |
| 最近完整里程碑 | `60fba481888a` / `137657aa...` | local gates；func/perf 仿真；100 MHz `WNS +0.044 ns`、`WHS +0.050 ns`；func58/perf20 团队板通过 | 不能证明 P1 修复后的 RTL 时序或板级正确性 |
| P1 + L1D 时序候选 | `dd469eaff61a` / `c655a887...` | Scala/Verilator 177、Python 364、locked gates；func 两套各 3 seeds；Linux clean 50 ms；random-AXI Linux 200 ms × 3 seeds | 100 MHz setup 失败，不能作为板卡候选或 timing-closed 基线 |
| M01 观测基线 | `9de316494fc0` / `00d37c5b...` | 完整 `make cpu-check`；func 两套各 3 seeds；Linux clean/instrumented 50 ms；M0/M1 无扰动和守恒合同 | 不能代表其后性能 RTL 的周期、时序或板测结果 |
| 冻结软件/时序对照 | `5141e9bc1d7a` / `53ac072b...` | 三项性能候选的完整 gates、func58 3 seeds、perf20 20/20（含 stringsearch）、Linux random-AXI 200 ms 3 seeds；对应 Vivado 结果仍作为旧候选证据 | 不能继承到后续 H01 RTL 的周期、时序或板测结论 |
| H01 已实现候选 | `3822c78bb0a6` / `abbf89d2...` | DIV common-operand fast path 默认关闭；L2 真 write-back；完整 `cpu-check` 与 19 项 perf20 通过；matching 100 MHz bitstream/DRC 完成 | setup `WNS -0.323 ns`，只作 diagnostic；不能把时序、周期或板测结论继承给后续 RTL |
| 当前 100 MHz milestone | `758181a01c5b`（功能终点 `e622625`）/ `04d6e4b2...` | R01 unused-source 规范化、LSQ Store/forwarding 仲裁切分、16 KiB L1I、16 KiB L1D；完整 gates、func58/Linux 三 seeds；100 MHz setup `+0.041 ns`、hold `+0.050 ns`、DRC 0；团队板 perf20 20/20 且总周期相对同 profile 基线约 `-12.65%` | 这是下一批冻结基线；任何 RTL 变化均不得继承其 timing、bitstream 或板测结论 |
| L04 已停止候选 | experiment `c30fc470de4a` / generated RTL `8ae0b9ea...`；retired by `2765433e82e0` | age-aware 单 owner Load/Store translation 仲裁；完整 LSQ suite 32/32；19 项 paired perf20 均通过 | 7 项改善、8 项不变、4 项退化；总 cycles 约 `-0.054%`；实验移除后 published RTL 精确恢复为 `04d6e4b2...` |
| W01 已保留候选 | source `b44eaffcefade`；publication `15d06c98ef32` / `afe3c5df...` | 对已成功 direct wakeup 的同 lane completion 回声做有界抑制；19 项 paired perf20 全通过，17 项改善、2 项退化 | 总 cycles `71,588,939 -> 71,515,650`（`-0.102375%`）；尚无 matching timing、系统回归或板测证据 |
| E02 已保留候选 | source `08fbf1120115`；publication `71577a4fa0db` / `f59c5273...` | 只允许普通 ROB head 使用 exact/current-epoch staged completion 提前退休；19 项 paired perf20 全通过，17 项改善、2 项退化 | 相对 W01 为 `-0.6045%`，组合相对 milestone 为 `-0.7063%`；不得继承 milestone 的 timing、bitstream 或板测结论 |
| L05/W02 observer 通过 | workspace `7e50f16`，schema `nscc-m01-v6`；production RTL 未修改 | L05 的 `5,194,524` 次 D-side translation 仅 5 次走 TLB；W02 的 P0--P2 waiting-consumer 保守机会为 `2,922,347` 次 | 两项暴露均达到启动门槛；先做 L05 独立 A/B，再做 W02 独立增量 A/B，不能把联合结果作为单项归因 |

Chiplab 对上述各候选均固定为 `c398d274812f164d387146fa7d8f612a4a1296d9`。CPU 源提交始终
保留用户已有的 `D AGENTS.md`；生成/门禁期间 `rtl/mycpu_top.v` 可呈现 matching publication
修改，它不与该预存删除状态混合提交。

`dd469ea` 的 matching 100 MHz implementation 是当前最近的时序样本：setup `WNS -0.449 ns`、
`TNS -30.140 ns`、220 个失败端点；hold `WHS +0.047 ns`；DRC 0 error；bitstream 成功。资源为
89,689 LUT（67.03%）、53,573 FF（19.90%）、68.5 BRAM tile（18.77%）、8 DSP。最差路径已从
历史 `F03` 和 L1D 路径迁移到 LSQ：`loadHead_reg[1]_replica` 到
`completion_data_reg[11]`，data path 10.342 ns，其中 route 7.506 ns（72.6%）。归档位于
`Stable_Backup/cpu_dd469eaff61a_chiplab_c398d274812f_perf_100mhz_20260804-154312_candidate/`。

冻结对照 `5141e9b` 的 matching 100 MHz implementation 已于 2026-08-04 完成：requested/actual
CPU、sys、DDR 分别为 `100/100/200 MHz`，DRC 0 error，bitstream 成功；setup `WNS -0.327 ns`、
`TNS -30.620 ns`，hold `WHS +0.051 ns`、`THS 0 ns`，因 setup 负 slack 仍是 diagnostic
candidate。最差 CPU setup path 为 `LSQ stores_7_physicalAddress_reg[5]_replica/C ->
issueOperandSource2_3_reg[16]/R`，data path `9.666 ns`，逻辑 `2.340 ns`、route `7.326 ns`
（75.8%），13 级逻辑；当前 top path 不再是旧 F03，也不包含 wakeup/select。归档位于
`Stable_Backup/cpu_5141e9bc1d7a_chiplab_c398d274812f_perf_100mhz_20260804-231754_candidate/`。

H01 matching implementation 的 requested/actual CPU、sys、DDR 为 `100/100/200 MHz`，DRC
0 error、bitstream 成功；setup `WNS -0.323 ns`、`TNS -26.864 ns`、238 个失败端点，hold
`WHS +0.051 ns`、`THS 0 ns`。资源为 89,640 LUT（67.00%）、53,598 FF、68.5 BRAM tile、
8 DSP；相对 `5141e9b` 的 WNS 只改善 4 ps，H01 对时序基本中性。最差 CPU 路径已迁移为
`scheduledLoad_robPointer_reg[2]/C -> rob/stagedCompletionCurrent_reg[3]/D`，data path
`10.201 ns`，逻辑 `2.247 ns`、route `7.954 ns`（78%）；它经过 `unknownOlderStore`、Store
完成仲裁与 ROB epoch qualification。归档位于
`Stable_Backup/cpu_3822c78bb0a6_chiplab_c398d274812f_perf_100mhz_20260805-000136_candidate/`。
该路径只描述 H01 bit，后续 RTL 必须重新读取 top-N。

## 3. 总体执行顺序

```text
P0  冻结候选身份并复现最低成本 gate
 -> P1 关闭 C01-C08 正确性 gate
 -> P2 建立 M0/M1 非侵入观测与守恒检查
 -> P3 固定 workload/seed，采集 M2 基线
 -> P4 用短 trace 校验最大瓶颈的计数定义
 -> P5 对多个高优先级独立候选分别做开关化 A/B
 -> P6 合并已通过候选，做一次 matching RTL 的完整 SoC 时序/资源验证
 -> P7 里程碑候选进入 func/perf 构建与团队板流程
```

P1 中任何问题复现后，立即暂停性能实验：先修复、完成相关回归，再从修复后的 RTL 重新
执行 P0-P3。P3 之前不扩 ROB/PRF/cache/IQ，不增加执行端口，也不改变恢复协议。

### 3.1 P0：冻结候选和基线

为每次 baseline 或 candidate 创建 manifest，至少包含：

```text
experiment_id / role=baseline|candidate
cpu_source_commit / cpu_repository_head / git_dirty_state
generated_rtl_sha256 / chiplab_commit
software_name / software_image_sha256 / build_kind
Verilator / Java / SBT / Vivado versions
memory-delay configuration / random seed / ROI definition
requested and actual clocks / implementation strategy
```

工作树有并行修改时，实验必须说明使用的是已提交 commit、临时 worktree hash，还是明确的
dirty patch hash。不得用一个会继续变化的目录名代替 source identity。

从工作区根目录使用受支持入口：

```bash
make toolchain-check
make cpu-check
make cpu-generate
```

只有需要 SoC 行为时再运行 `make sim RUN_SOFTWARE=...`。不要通过系统 `sbt`、系统
`verilator` 或临时工具版本绕过锁定环境。

热开发采用流水调度：只要存在输入互不影响的长任务，先启动仿真、gate 或 Vivado 后台进程，
再做结果后处理、文档同步、只读设计审阅等独立工作，避免计算资源空闲。提交或任何会改变
CPU HEAD、generated RTL、prepared model 输入的操作不与读取这些身份的任务重叠；这类短暂
串行步骤先完成身份冻结，随后立即启动下一条后台流水。Vivado 仍独占主要计算和内存资源，
运行期间只做轻量后处理/审阅并约每三分钟提取一次阶段、资源和时序信息。

### 3.2 2026-08-04 启动与 P1 closure manifest

本轮明确采用“先关闭全部 `C01-C08`，再修改性能 RTL”的执行策略。定向 Scala suite 使用
`make cpu-test CPU_TEST=<fully-qualified-suite>`；完整 verdict 仍只能由 `make cpu-check` 给出。

| 字段 | 冻结值 |
| --- | --- |
| Experiment | `20260804-next-stage-p0-baseline`；`role=baseline` |
| 启动 CPU repository HEAD | `872bbd4e9f16ecdde8b0915316bd0f21976fc5ac`，branch `dev/ECHO` |
| M01 baseline CPU | `9de316494fc03d746b597afb1f4f271e9762114f`，branch `dev/ECHO` |
| 当前 CPU repository HEAD | `572588e`，branch `dev/ECHO`；W01+E02 generated RTL 与 gate metadata 已发布 |
| Functional implementation | `6bbca9b330ba8d886c888e2804f70b95be18e4cd` |
| Dirty state | 仅保留用户已有的 `D AGENTS.md`；binary diff SHA-256 `4fb5b8c92a389a56a89bd3d5adf5137ea25418806048ade3c157a41df13a86f3` |
| M01 baseline generated RTL | `00d37c5bc78fe0052cabf7e9d3ae665f31e0a7a0c238ea181ab935957b4c40c1` |
| 当前 generated RTL | `f59c5273c94e6a70f7fb5f73ee0fc2097806385b0718fdd6460db5cbeeaaab93` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9`；现有生成物/测试补丁不属于 clean baseline |
| Tools | SBT `1.10.11`；Java `21.0.11`；Verilator `5.020`；Vivado `2023.2` |
| M01 baseline reproduced gates | Scala/Verilator 177 tests；Python 364；locked port/lint/Yosys/publication 全通过；lint 856 warnings，signature `49ee79a...` |
| 最近完整 reproduced gates | `5141e9b` matching `make cpu-check`：Scala/Verilator 180、Python 364、locked port/lint/Yosys/publication；lint 875 warnings，signature `aeca4fbb...` |
| 当前 candidate gates | W01+E02 完整 `make cpu-check`：Scala/Verilator `186/186`、Python `364/364`、locked port/lint/Yosys/publication 全通过；lint 876 warnings，signature `b021ae6a...` |
| 当前 official-suite model | clean perf20 model `65652443...`，绑定 CPU `e622625`、RTL `04d6e4b2...`、c398 和 hash-locked harness patch `7ddcbf7e...` |

P1 已在 `dd469ea` matching RTL 上完成定向测试、完整 gate 和系统回归。后续时序 cut 不改变
`C01-C08` 的协议，但仍需对当前 candidate 做 matching 仿真后才可进入性能 A/B：

| ID | 状态 | CPU commit | 定向证据 | 系统回归 |
| --- | --- | --- | --- | --- |
| C01 | closed | `9590512` | `OooExecutionClusterSpec`：P0 busy 时 held producer 不 wake，accept 后恰好一次 wake | 177/177 + 系统回归通过 |
| C02 | closed | `93b5910` | `OooDivideUnitSpec`：有符号/无符号 DIV/MOD 边界、随机、逐迭代 flush/restart | 177/177 + 系统回归通过 |
| C03 | closed | `4ff1b99` | `OooRobSpec`：全 pointer wrap、epoch reuse、旧 completion/wakeup/commit 隔离 | 177/177 + 系统回归通过 |
| C04 | closed | `6252184` | `OooLoadStoreQueueSpec`：translation 未决阻塞，PA alias/overlap/forwarding | 177/177 + 系统回归通过 |
| C05 | closed | `2a3a44d` | bridge、L1I/L1D/L2 refill/BRESP error 与 dirty retry | 177/177 + 系统回归通过 |
| C06 | closed | `39fce68` | `OooLoadStoreQueueSpec`：MAT unknown、SUC head/drain、年轻访问顺序 | 177/177 + 系统回归通过 |
| C07 | closed | `191517f` | ATU、Frontend、LSQ、Execution mutation cancel/retry | 177/177 + 系统回归通过 |
| C08 | closed | `189677f` | ATU PS=21 非连续/反向 half、I/D odd/even、权限/dirty/MAT | 177/177 + 系统回归通过 |

### 3.3 隔离仿真入口

`make sim-prepare` 串行执行 RTL 生成，从锁定的 c398 commit 创建
`build/sim/prepared/cpu_<commit>_chiplab_<commit>/<profile[-suite]>/`，再编译模型与软件。
标准 `clean` profile 直接使用官方 testbench；`instrumented` 只应用
`tools/sim-patches/difftest-interrupt-memory-order.patch` 和 M0/M1 只读 monitor patch，并先校验
各自 SHA-256 lock。monitor 从 testbench C++ 读取 Verilator 层次信号，不驱动 DUT。
prepared manifest 保存 CPU/Chiplab/RTL/model/software、dirty patch 和 profile patch hash。

`func58` 和 `perf20` suite 从同一 clean c398 archive 建立各自模型，并应用 hash-locked
`tools/sim-patches/official-software-switch.patch`。该 test-only patch 只把 c398 已解析但原
`CpuTestbench` 路径未使用的 `--switch`/`--end-pc` 参数接入模型，并在结束时导出
`switch/num_data/LED` 快照；它不修改 DUT、官方软件或综合输入。所有这类结果均标成
patched-harness local Verilator evidence，不能升级为 clean official CI 或板测结论。

`make sim-matrix` 不重新生成或编译模型。它复核当前 HEAD、RTL 和 prepared model hash，随后为
每个 workload/seed/time-limit 创建独立的 `ram.dat`、日志与运行 manifest。非 Linux workload
必须到达 `Reached test end PC`；Linux 固定窗口必须无 DiffTest/trace error 并到达预期 time
limit。Chiplab 仿真进程即使超时也返回 0，因此 OS exit status 不能单独作为 verdict。

默认 `SIM_LANES=2`。`SIM_LANES=3` 还要求显式设置 `SIM_ALLOW_THREE=1`、提供实测
`SIM_LANE_PEAK_MB<2560`，且启动时 `MemAvailable>6144 MiB`。2026-08-04 的 standard 和
official-suite lane 峰值约 8 MiB RSS，当前机器三 lane 隔离运行成立；官方 suite 的便捷入口
因此默认 `OFFICIAL_SIM_LANES=3`。模型仍只编译一次，各 lane 使用独立 `ram.dat`、日志和运行
manifest。早期 endpoint 失败来自 c398 新 testbench 未消费 `--end-pc`，以及旧入口错误使用
`test_finish+offset`；当前从 ELF 解析精确 `test_finish` 并在实际 Emulator loop 检查。不得把
这些 harness 入口失败继续引用为 CPU 功能失败。

#### 3.3.1 官方软件仿真合同

| suite | 软件与选择方式 | 默认矩阵 | 必须满足 |
| --- | --- | --- | --- |
| `func58` | c398 tracked `nscscc_func/obj`；运行期 `switch=FF` 以移除软件等待 | random-AXI seeds `240/255/141`，三 lane 并行 | 到达 `test_finish`；`num_data=0x3a00003a`；两路 LED 为 1；无 DiffTest mismatch |
| `perf20` | c398 tracked `nscscc_perf/obj/allbench`；switch `7E..6B` 选择 20 项 | ideal-memory seed 0，最多三 lane并行 | 每项到达 `test_finish`；软件 `PASS`/两路 LED 为 1；保存 `num_data` CPU cycles；无 DiffTest mismatch |

板上流程先用 `F0/FF/stress` 在 reset 边界把 seed 锁存进 confreg AXI LFSR，释放 reset 后再把
运行期 switch 切回 `FF`；本地 Verilator 不执行这条 FPGA confreg LFSR 路径，因而用独立的
`--simu-bus-delay-random-seed` 提供 AXI 扰动并始终保持运行期 `switch=FF`。两者是相同验证
目的下的不同延迟实现，不能声称 bit-exact 随机序列相同。`perf20` 的 CPU cycle counter 只
覆盖 benchmark ROI；Verilator 墙钟和整段模拟周期还包含启动与 UART，不可写入性能 A/B。
`stringsearch` 尤其会在每轮 ROI 后输出大量文本，早期 100 ms 仿真窗口不足；官方 suite
默认已提高到 600 ms，其他项目到达 endpoint 后仍会提前退出。超窗且无 mismatch 只表示证据
未闭合，不构成功能失败。

### 3.4 RTL 演进与预期同步

每次 RTL 改动后按下表滚动，不把候选名称当作稳定微架构：

| 变更 | 已观察 | 下一步预期 | 使预期成立的证据 |
| --- | --- | --- | --- |
| P1 `C01-C08` | 定向和完整 gate、matching 系统仿真通过 | 作为性能实验正确性底座 | 当前 candidate matching DiffTest |
| `2c56740` L1D refill readiness | 原 L1D 路径退出 top path | 不再优先优化旧 L1D mux | `dd469ea` routed top-N |
| `e5212e3` LSQ selected-load translation cut | source/gate 通过 | 移除 `loadHead` PA mux；周期不变 | 下一次 matching routed top-N + paired cycles |
| M0/M1 harness | simulation-only；clean/instrumented commit trace、UART、cycles/instructions 一致；v2 source alignment 和 JSON 守恒通过 | 细分 Linux Store translation/completion 暴露原因 | M2 microbenchmark + 最大损失短 trace |
| `7f8a3c7` `E01` DIV fast path | 0、除零、`+1/-1` 直接完成；定向测试覆盖开关两侧和 completion collision；完整 19 项 perf20 中 DIV-only 比 baseline 多 16 cycles，未形成收益 | 已由 `f7d7cef` 默认关闭；保留实现和测试作为后续不同 DIV 机制的独立开关 | 若重启 E01，必须重新给出多项目操作数覆盖率、paired cycles 与 matching WNS/LUT |
| `c36c650` ordinary Store completion bypass | 只旁路无异常、非 SC、cached ordinary Store，异常/冲突继续走注册路径；曾因系统回归隔离后在 `acb90a3` 重启 | 当前 lab19 单项没有独立周期收益，保留开关以便 perf20 归因 | 相关 LSQ tests、三 seed系统回归、perf20 独立 A/B |
| `b6d30a0`/`bf93145` Store translation lookahead | ATU 空闲且 head Store/oldest Load 不需要翻译时，预翻译更年轻且地址已知的最老 Store；`func_lab19` seed 1 在前两项基础上再减少 1,496 cycles | 核查是否降低 Linux Store translation 桶，并确认没有挤压 Load translation | func58/perf20、random AXI、Linux M01 v3、matching LSQ top paths |
| 冻结组合 `5141e9b` | 三项均启用；matching gates、func58 3 seeds、perf20 20/20 和 Linux random-AXI 200 ms 3 seeds 均通过；19 项相对 `9de3164` 减少 16.4878% | 作为后续 cycle/timing 对照；DIV fast path 的独立归因已否决，Store bypass 与 Store translation lookahead 保留 | matching 100 MHz routed timing、资源与 top-N；不能把结果继承给 H01 |
| `f7d7cef` DIV-off | 只把已经证明无收益的 common-operand fast path 默认关闭，保持 divider 基本实现和定向测试 | 已随 H01 闭合；其 19 项影响仅为消除 16 个回归 cycles | 若重新启用，仍需新的 workload 覆盖率与 paired cycles |
| `95b1036` / `3822c78` H01 L2 write-back | dirty L1D eviction 可直接在 L2 安装 dirty line；只有 dirty L2 victim 或 maintenance 才写 DDR；定向测试覆盖无 DDR 安装、dirty victim、CACOP、uncached alias、barrier、IBAR 自修改代码和 BRESP/error ownership | 19 项合计 `74,473,164 -> 73,932,910`（-0.7254%），完整 gates 通过；matching 100 MHz bitstream 与 DRC 完成，但 setup `WNS -0.323 ns` | H01 周期收益保留；该负 slack 与 top-N 只作为下一批 RTL 的时序输入 |
| `87423e3` R01 unused-source 规范化 | RAT 对 opcode 未使用的源寄存器统一按 `r0` 处理，避免 rename/ready 路径上的假依赖 | 19 项 `73,932,910 -> 71,559,876`（-3.2097%），17 项改善，`fireye_C0/stream_copy` 仅有 `+0.008%/+0.012%` 微小波动 | 后端定向测试、组合完整 gates、func58/Linux 与 matching route |
| `985cec5` ordinary Store 关闭 fast completion | 用注册 completion 切断 Store 到 ROB 的直接路径，作为时序/周期消融 | 代表 5 项全部正确但合计回退 19.11%，其中 `fireye_I2` +35.894%；已由 `278011e` 恢复 fast completion | 否决该实现；不得用潜在 Fmax 收益掩盖确定的大幅周期损失 |
| `d09862e` Store/Load completion 仲裁切分 | 保留 ordinary Store fast completion；当同拍存在年轻 forwarding Load 时优先 Store，Load 下一拍注册完成，使 Store direct path 不再依赖深 forwarding predicate | LSQ `31/31`；代表 5 项相对 R01 合计 +0.2732%，功能均通过 | 只有 matching route 的频率收益超过该周期代价才保留 |
| `a97a120` H02-I 16 KiB L1I | 默认 L1I 从 8 KiB 扩至 16 KiB，CPUCFG 同步更新 | L1I/配置/core/CSR 定向测试通过；代表 5 项相对上一候选 -0.0548% | 完整 gates、func58/Linux、BRAM/LUT 与 matching route；收益需结合频率成本判断 |
| `e622625` H02-D 16 KiB L1D | 默认 L1D 从 8 KiB 扩至 16 KiB，CPUCFG 同步更新；测试按实际初始化周期与 set 数参数化 | L1D `12/12`、配置/core/CSR 通过；代表 5 项相对上一候选 -0.0560%，无单项回退；当前组合 19 项相对 H01 合计 -3.1704% | 完整 gates、func58/Linux 和 matching route；检查额外存储是否进入 BRAM、是否加重 cache mux/布线 |
| `b44eaff` W01 completion 回声抑制 | direct wakeup 已被 IQ 接受时，不再让同一 completion 在下一拍占用同 lane registered wakeup；不增加 wakeup lane | 19 项相对 milestone `-0.102375%`，17 项改善、2 项退化；matching func58 三 seed 58/58 | Linux；matching route 必须确认没有恶化当前 staged-wakeup 关键路径族 |
| `08fbf11` E02 head completion bypass | exact pointer/current epoch 的普通 head staged completion 可提前一拍退休；branch、exception、serializing、system operation 排除，年轻 lane 仍走既有 complete prefix | 19 项相对 W01 `-0.6045%`；ROB 测试覆盖 wrap/epoch/flush、结果值和所有排除边界，并显式证明三宽 commit 能力；matching func58 三 seed 58/58 | Linux；matching implementation 联合评价周期与 ROB/IQ top-N |
| L05 observer 通过 | `nscc-m01-v6` 分类 direct/DMW0/DMW1/TLB 的 Load/Store request，并累计 request-to-response latency 与 direct/DMW 老 Store 阻塞年轻 Load | 四项中非 TLB 占 `99.99990374%`，request 数相当于观测周期的 `12.0803%`；老 Store 严格阻塞占 `0.1861%` | 实现保留 `scheduledLoad` timing cut、同时覆盖 Load/Store 的独立 A/B；不能把 request 比例直接当作可消除周期 |
| W02 observer 通过 | 对 current-epoch、无异常且匹配有效 LQ 的 Load completion，统计 IQ0--IQ3 中真实等待 pdst 的 consumer | `3,517,657` 次合格 completion 中，P0--P2 waiting-consumer 为 `2,922,347`（`83.0765%`） | L05 归因冻结后，再实现不扩大敏感广播网络的独立增量 A/B；P3 需单独证明数据旁路时序 |

Vivado 调度采用合并里程碑：同一轮可以并行准备多个高优先级、相互较独立的候选，但每项
必须先有独立配置开关、同 workload/seed 的软件 A/B 和相关正确性回归。只把各自已有正收益、
组合仿真也通过的候选合成一次 matching 100 MHz implementation，同时验证 LSQ cut、组合
性能改动和新的 top-N path。热开发中的微架构实现本身要兼顾扇出、组合深度、bank/mux 和
流水边界，但不要求每次负 slack 后立即插入一个独立的纯时序优化 pass。若合并实现收敛，
该 matching bitstream 可在确认 profile 和板卡所有权后进入团队板测试，形成新的 milestone；
若未收敛，则保存 WNS/TNS、资源、拥塞和 top-N critical path，把它们作为下一轮 RTL 设计的
新输入。此时利用开关矩阵与独立 A/B 归因后继续开发，不为每个早期想法或一次负 slack 各跑
30 分钟实现。只有出现影响接口/正确性且仿真无法覆盖的高风险变化，才提前增加
implementation。release/official CI 的最终候选仍必须满足完整 setup/hold/DRC/bitstream 门槛。

### 3.5 当前 `9de3164` M0/M1 证据

`nscc-m01-v2` 是 simulation-only observer。DiffTest adapter 内部有三级寄存，再叠加 DPI
采样边界后，当前 Verilator source snapshot 比被消费的 commit packet 早 4 cycles；v2 用显式
四拍队列对齐两者。`func_lab19` 全窗口的 source retire width 与 DiffTest commit count 逐拍
一致，`source_retire_alignment_errors=0`。v1 的 cycles、instructions 和 commit trace 仍有效，
但同拍读取微架构信号得到的因果分类已作废，不得继续引用。结束/超时拍显式 cancel；探针
只读取当前 Verilator 层级。每次 RTL 改动都必须重新生成模型并复核层级绑定，prepared
manifest 中的 CPU commit、RTL、model、patch 和 source-manifest hash 是结果身份的一部分。

无扰动合同使用 `func_advance` seed 1：clean 与 instrumented 均为 15,886 cycles、1,517
instructions；1519 行 `simu_trace.txt` 逐字节相同，SHA-256 为
`a417ba89a8b415b63b0428fd88ecb56e29883b71440e673dfc920d249060fc3f`；UART 也相同。
Linux 50 ms 配对同为 24,999,995 cycles、13,627,566 instructions、IPC 0.545103，early-boot
UART SHA-256 同为 `1cf92c30b65c33bd11499964a1b1c9f94f04802add281ad0775cb9d3b2d4a17f`。

v2 的互斥 loss stack 如下。这里统计的是某拍零提交时 ROB head 的可见状态，属于 exposed
stall snapshot，不等价于把该桶全部消除后的周期收益：

| workload / seed | IPC | zero-retire | ROB empty | head incomplete | head complete blocked | recovery | ROB full | DIV busy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `func_lab19` / 1 | 0.22498 | 526,911 | 160,949 | 363,488 | 2,403 | 71 | 45,651 | 20,954 |
| Linux 50 ms / 1 | 0.545103 | 18,009,014 | 745,033 | 17,220,324 | 43,657 | 0 | 1,609,842 | 0 |

head-incomplete 的主要细分为：

| 原因 | `func_lab19` / 1 | 占其 head-incomplete | Linux 50 ms / 1 | 占其 head-incomplete |
| --- | ---: | ---: | ---: | ---: |
| Store translation 未完成 | 7,052 | 1.94% | 7,984,219 | 46.37% |
| Store completed、ROB 尚未完成 | 44,176 | 12.15% | 5,928,701 | 34.43% |
| Load completed、ROB 尚未完成 | 6,668 | 1.83% | 962,622 | 5.59% |
| Load response | 19,333 | 5.32% | 590,968 | 3.43% |
| ROB staged completion | 78,419 | 21.57% | 553,721 | 3.22% |
| Issue operand stage | 48,858 | 13.44% | 289,265 | 1.68% |
| Issue address stage | 24,618 | 6.77% | 104,449 | 0.61% |
| IQ ready、尚未 issue | 24,024 | 6.61% | 104,293 | 0.61% |
| Uncached Store response | 42,859 | 11.79% | 25,113 | 0.15% |
| DIV | 12,862 | 3.54% | 0 | 0% |
| execution/dispatch/untracked | 34,428 | 9.47% | 234,010 | 1.36% |

`ROB staged completion + issue operand/address stage` 在 `func_lab19` 共 151,895 cycles，证明旧
`issued_or_completion_pipeline=186,323` 大桶主要混入了正常流水驻留，不能直接形成“删掉一拍”
的优化结论。当前 Verilator 根层没有同时保留 dispatch valid、pointer 和 epoch，故 v2 不用仅有
valid 的信号猜测 ROB head 身份，剩余 dispatch 暂保留在 untracked 桶。

`func_lab19` 与 `func_advance` 的 seeds `1,19557,5570815` 均通过；三 seed 周期分别维持
`620754/620572/620555` 和 `15886/15968/15828`。所有有效 JSON 均满足 retire histogram、
instruction、zero-retire、prefix、source alignment 和 sampling protocol 守恒，
`unclassified=0`。最终 `func_lab19` model/counter hash 为 `f8f044ff...` / `173a51c3...`；最终
Linux model/counter hash 为 `5d53230a...` / `acb60011...`。Linux 中 branch-mispredict recovery
为 91,109 次，但零提交 loss stack 中 recovery 为 0，说明这些恢复拍与提交重叠；当前不把
redirect 单拍优化排在 Store 路径细分之前。Linux 两个最大桶合计占 head-incomplete 80.80%，
下一步必须区分 TLB hit/miss、ATU owner/等待、LSQ translation 调度，以及 Store completion 到
ROB 的固定协议拍；不能直接把 13.9M cycles 当成可消除收益。`func_lab19` DIV busy 约占 3.38%，
665 次启动中 ordinary 377、divisor zero 217、dividend zero 63、power-of-two 6、abs-one 2，
fast path/P1 HOL 适合作为独立 microbenchmark 候选，Linux 此窗口无 DIV，不能单独代表全局收益。

### 3.6 冻结对照 `5141e9b` official-suite 证据

本节只记录当前组合候选，不能回填到 `9de3164` 的 M01 baseline，也不能替代后续 paired
baseline。身份为 CPU `5141e9bc1d7a442422940246a2e6cf295c439b61`、RTL
`53ac072bc816e7c00d110fdcb71c1649c446c4ea0537aff90eef24aaedb43ca7`、Chiplab c398。
matching `make cpu-check` 已通过 Scala/Verilator 180、Python 364 和 locked
port/lint/Yosys/publication gates；locked lint 为 875 条已锁定 warning，signature
`aeca4fbb505f1ac0ffb14534db7af7453c6732878442f7fa26b13703d27f4c69`。

official-suite patch 为 `7ddcbf7e3efb76794cbcaa89a5ff40ee0d7ab1f8504640cbc74b47b825bb5592`。
func58 模型为 `932982b55266e62dfd291526f14f3921d0a360d190e90cda618706f2256cb9e6`，
软件 `main.elf` 为 `c5482259e2f48c76b8a667de71af11b96bf73bbb4d0494ef379e86a79b4b984e`。
runtime switch `FF` 下，random-AXI seeds `240/255/141` 均到达精确 `test_finish`，返回
`0x3a00003a`、LED `1/1` 且无 DiffTest mismatch；整段 cycles 分别为
`644017/644901/644748`。CSV 位于
`build/sim/runs/cpu_5141e9bc1d7a_chiplab_c398d274812f/clean-func58/random/matrix_1892a80af7f5_func58.csv`。

同 seed 1 的 source-aligned `func_lab19` v3 对照显示，`store_translation` 从 `7,052` 降到
`1,591`，`store_completion_to_rob` 从 `44,176` 降到 `5,653`，`divider` 从 `12,862`
降到 `12,143`；整段却只减少 `2,174` cycles。桶下降远大于总收益，说明等待被其他乱序工作
隐藏、与其他状态重叠或在更细 schema 中重新分类。它证明候选命中了预期路径，但不能把三个
桶差相加成理论收益，也不能替代各开关和 perf20 分项的 paired A/B。

perf20 模型为 `0da0c822dbd7bc9e400f4d0717f7cfefd21c872c3e29159c6c5d224c1dec7be3`，
exact `allbench` 的 `main.elf` / `inst_data.bin` 为 `0fd2e5b2...` / `a92afcdc...`。
首轮 100 ms 窗口有 19/20 项软件 PASS、LED `1/1`、endpoint 和 DiffTest 通过；
`stringsearch` 不是 mismatch，而是官方十轮程序的大量 UART 输出使窗口不足。最终 600 ms
单项到达 endpoint，CPU cycle counter 为 `792,405`，LED `1/1` 且无 DiffTest mismatch。
当前组合因此已在本地 patched-harness/ideal-memory 条件下闭合 perf20 20/20，20 项合计
`75,265,569` CPU cycles。

`stringsearch` 的长墙钟主要来自官方程序重复十轮及 UART，不适合在每个中间消融候选上重复
支付。首轮归因最终对 baseline、Store-only、DIV-only、DIV+Store 和当前组合都闭合了其余
19 项；只有当前正确性里程碑额外完整运行 `stringsearch`。同一软件、ideal-memory 和
patched c398 harness 下的 19 项合计如下：

| 候选 | 19 项 cycles | 相对 baseline | 决策 |
| --- | ---: | ---: | --- |
| `9de3164` baseline | 89,176,402 | 0 | 归因基线 |
| `c36c650` Store-only | 78,838,612 | -11.5925% | 保留 |
| `9228dae` DIV-only | 89,176,418 | +0.000018% | common-operand fast path 默认关闭 |
| `acb90a3` DIV+Store | 78,838,628 | -11.5925% | 与 Store-only 仅差 +16 cycles |
| `5141e9b` DIV+Store+translation lookahead | 74,473,164 | -16.4878% | 保留 Store lookahead |

Store translation lookahead 相对 DIV+Store 再减少 4,365,464 cycles（-5.5372%）。当前组合
19 项中只有 `crc32` 相对 baseline 增加 1,766 cycles（+0.0351%），其他 18 项均改善；
最大绝对收益来自 `inner_product`、`fireye_I2`、`my_memcmp` 和 `fireye_D1`。这些结果
足以完成首轮三个开关的取舍，不继续扩展成全因子组合。

后续独立候选默认只跑 2--4 个能覆盖其机制的高权重代表项，并加入一个通用控制项；Store、
LSQ、cache 或翻译候选优先从 `fireye_I2`、`inner_product`、`bubble_sort`、
`lookup_table` 中选择，`coremark` 作为控制项。只有代表集有明确收益、相关正确性测试通过
后才并入组合候选；组合候选只做一次完整 func58/perf20、Linux/random-AXI、local gates 和
matching Vivado。若候选可能专门改变字符串行为，再为 `stringsearch` 定义固定 ROI paired
A/B，而不重复官方十轮长尾。

### 3.7 当前 H01 热候选

当前源提交为 `3822c78bb0a6f14eea64bfdff9a9beb0dc6ed358`；功能 RTL 首次发布于
`95b1036970d7f0d93d1b56be16fd1a00e21f378b`，generated RTL hash 为
`abbf89d2aafaa10a12e9d59b20cf9b8ef6463e1df4bd86d081a4cd473acbad35`。两者 RTL 相同，
后一个 commit 只锁定 matching replacement/lint metadata。相对 `5141e9b`，候选关闭已经由
19 项归因证明无收益的 E01 common-operand fast path，并启用 H01 L2 true write-back。

H01 相关 Scala 测试为 L2 `8/8`、数据层次 `1/1`、共享层次 `6/6`；完整 `make cpu-check`
通过 Scala/Verilator `181/181`、Python `364` 和 locked port/lint/Yosys/publication gates。
locked lint 为 876 warnings，signature `a070ede4...`。clean perf20 model 为
`d4c68ade0531f00f5c84b18b054ade56dcb0aa511d4f16fd72397a21e7b9f0bd`。

19 个短项全部通过软件 endpoint、LED 和 DiffTest，合计从 `74,473,164` 降到
`73,932,910` cycles，减少 `540,254`（-0.7254%）。18 项不变或改善；唯一反向项
`fireye_I2` 为 `8,824,191 -> 8,825,772`（+0.0179%）。收益分散在 `fireye_A0/D1`、
`loop_induction`、`inner_product`、`lookup_table` 等，不由一个 benchmark 决定。`stringsearch`
按热迭代合同留给多个候选合并后的 milestone。

matching 100 MHz performance SoC 已完成 bitstream 和零 DRC error；setup 为
`WNS -0.323 ns`、`TNS -26.864 ns`、238 个违例端点，hold 为 `WHS +0.051 ns`。
资源为 LUT `89,640`（67%）、FF `53,598`、BRAM `68.5`、DSP `8`。最差 CPU path 是
scheduled Load 的 ROB pointer 到 ROB staged completion，data delay `10.201 ns`，其中
logic `2.247 ns`、route `7.954 ns`（78%），经过 older-Store 判定、Store completion 与
epoch qualification。该 bitstream 因 setup 未闭合只作诊断，不上板；归档位于
`Stable_Backup/cpu_3822c78bb0a6_chiplab_c398d274812f_perf_100mhz_20260805-000136_candidate/`。
这些数据只评价 H01，不继承给后续 RTL。

### 3.8 当前 R01/LSQ/H02 组合候选

当前功能源提交为 `e6226250bdb1e6c7b83a58921e5676205b42b508`；测试合同、RTL publication
和 matching gate metadata 提交依次为 `db6e30d`、`6ec6b69`、`758181a`。generated RTL hash 为
`04d6e4b2a36371d0f65a4652e0ec93cd84405e52c20a830c0c13e3b77afa8cfc`；clean perf20
model hash 为 `65652443e35e39c03307fcd5044d294d296f90c992c03e20d980f7584658b02e`。
组合依次包含 R01 unused-source 规范化、LSQ Store/forwarding 仲裁切分、16 KiB L1I 和
16 KiB L1D。每项均由独立提交与同一 5 项代表集 A/B 保留归因；关闭 fast Store completion
的强回退消融 `985cec5` 已独立恢复，没有混入当前候选。

当前 5 项相对 H01 的链式结果是：R01 `-4.066%`，LSQ 仲裁 `+0.2732%`，L1I
`-0.0548%`，L1D `-0.0560%`。R01 是明确的 cycle 候选；LSQ 仲裁是接受小幅周期代价换取
route 改善的 timing 候选；两级 cache 扩容目前只有小幅周期收益，必须由 matching 资源、
top-N 与频率共同决定。

当前组合的 19 个短 perf20 项全部到达软件 endpoint、LED 与 DiffTest pass，合计从 H01 的
`73,932,910` 降到 `71,588,939` cycles，减少 `2,343,971`（-3.1704%），19 项全部改善。
纯 R01 为 `71,559,876`，因此 LSQ 仲裁与两级 cache 扩容合计相对 R01 为 `+29,063`
（+0.0406%），不能把组合总收益归给 H02。代表集显示 LSQ 的局部代价被 cache 小收益部分
抵消；最终取舍还需结合 matching route。完整 `make cpu-check` 已通过 Scala/Verilator
`183/183`、Python `364/364` 和 locked port/lint/Yosys/publication；lint 为 876 warnings，
signature `b021ae6a...`。clean func58 model 为 `7462840b...`；random-AXI seeds
`240/255/141` 均通过 58 点、`num_data=0x3a00003a`、LED `1/1` 和 DiffTest，整段 cycles
分别为 `641568/642791/642748`。Linux instrumented model 为 `fcf40f7a...`，random-AXI
seeds `1/19557/5570815` 的 200 ms 固定窗口均通过，无 DiffTest/trace error；三个窗口各为
`99,999,995` cycles，retired instructions 分别为 `44,901,667/44,871,249/45,098,926`，
IPC 为 `0.449017/0.448713/0.450989`，M01 守恒检查全部通过。对应 counter hash 为
`92e964fc.../0ab44a75.../4d1369d0...`，矩阵摘要为
`build/sim/runs/cpu_758181a01c5b_chiplab_c398d274812f/instrumented/random/matrix_702d500d09d1_summary.txt`。

matching 100 MHz performance SoC 已完成并闭合：actual CPU/system/DDR clocks 为
`100/100/200 MHz`，setup `WNS +0.041 ns`、`TNS 0`，hold `WHS +0.050 ns`、`THS 0`，
DRC `0 error`，bitstream 成功。placed full-SoC 资源为 LUT `89,972`（67.24%）、FF
`53,724`（19.96%）、BRAM tile `68.5`（18.77%）、DSP `8`（1.08%）；相对 H01 仅增加
332 LUT、126 FF，BRAM/DSP 不变。当前组合因此整体保留为新的 100 MHz local milestone，
但这不证明 LSQ cut 或两级 cache 扩容单独改善周期；单项结论仍以各自 A/B 为准。

最差 CPU setup 路径族由 ROB `stagedPdst_2` 经 early-wakeup/ready 广播到 IQ0 queue 6 的
payload clock-enable，WNS `+0.041 ns`，data delay `9.627 ns`，其中 logic `1.483 ns`、route
`8.144 ns`（84.60%），10 个 LUT level。两个最差独立 endpoint 都属于该族且 slack 相同；
第一条不同路径族为 LSQ scheduled-load physical address 到 load completion/issue operand，
WNS `+0.164 ns`，只多 `0.123 ns`。旧 H01 的 scheduled-load ROB pointer 到 ROB staged
completion 已不再主导，但当前余量仍很小，后续 E02 或 wakeup 变更不得增加该广播扇出。
归档为
`Stable_Backup/cpu_758181a01c5b_chiplab_c398d274812f_perf_100mhz_20260805-021058_candidate/`；
bitstream、routed DCP、timing report hash 分别为 `a59257ad.../4ada5b08.../f310a836...`。
本轮没有补跑 matching clean `stringsearch`，因此本地软件证据明确记为 19 项，而不虚标
20/20。matching bitstream 已进入团队板 perf20：job
`20260804-182327-8f1c8193` 通过 20/20，总 SoC/CPU cycles 为
`69,476,960/69,466,027`；同板、同 c398、同 100 MHz profile 基线 job
`20260803-220447-d9b5b478` 为 `79,537,915/79,524,833`，两种口径均下降约
`12.65%`。19 项改善，test 4 约退化 `0.14%`，所以这是一项明确的组合整体收益，但仍不
拆分归因给 LSQ 或 cache 单项。板测 package SHA-256 为 `84ea549e...`，bitstream hash 为
`a59257ad...`，artifacts 保存在 `build/board-jobs/cpu_758181a01c5b_perf20/artifacts/`。

达到“板上性能确实提升”条件后，submission repository 已在同名 `dev/ECHO` 分支提交并
推送 `0a8ef8caf08c`，其中 CPU source/两份 generated RTL 均对应 `758181a` 与
`04d6e4b...`；`make ci-check` 验证 production template `6915882...` 固定 c398。推送已
触发 official CI；本地 `glab` token 已撤销且没有取得 pipeline ID，最终 verdict 无可记录
证据，不得写成 official CI passed。

首次完整 gate 的 Scala/Verilator 结果为 `182/183`：失败来自
`OooDataCacheHierarchySpec` 仍把 memory MSHR ID 固定为 0，并用旧 64-set L1D 的固定
`0x1000` stride 构造同-set地址。16 KiB/128-set L1D 下该夹具既拒绝合法的非零 MSHR ID，
也不再真正驱逐 dirty A 行。测试已改为回送实际 MSHR ID，并按
`dataCache.sets * lineBytes` 构造冲突地址；修复后的 hierarchy suite `1/1` 通过，重新覆盖
dirty L1D eviction 在 L2 保持所有权且不向 DDR 写回的 H01 合同。该修复只改变测试，不改变
生成 RTL；第二次完整 gate 已全部通过。

### 3.9 W01/E02 下一组合候选

M01 observer 在 workspace commit `babeac9e4c63` 升级为 `nscc-m01-v5`，只读采样 E02
exact-head/current-epoch staged completion，以及 W01 registered/direct tag 冲突后确有等待
消费者的周期。`coremark/inner_product/lookup_table/quick_sort` 合计 `43,190,065` 个观测
周期，E02 严格上界为 `7,454,851`（`17.2606%`），W01 affected conflict 为 `484,314`
（`1.1214%`）；所有 retire、source alignment、queue identity 和 sampling 守恒式通过。
两者都是启动 RTL 实验的机会证据，不能按计数直接宣称可取得同等周期收益。

W01 源提交 `b44eaffcefade` 抑制已经成功 early-broadcast 的 completion 回声，publication
提交 `15d06c98ef32` 的 generated RTL SHA-256 为
`afe3c5dfcff8cd3c795a2738e1aec6c6e7e89c7e2e42e10f4ee7321f760cf26d`。19 个短
perf20 项均通过，合计 `71,588,939 -> 71,515,650`，减少 `73,289`
（`-0.102375%`），17 项改善、2 项退化。

E02 源提交 `08fbf1120115` 只让普通 ROB head 的 matching staged completion 参与当拍退休；
exception、branch、serializing 和 system operation 保持原完成边界，年轻 lane 仍由已经写入
`entry.complete` 的有序 prefix 决定。publication 提交 `71577a4fa0db` 的 generated RTL
SHA-256 为 `f59c5273c94e6a70f7fb5f73ee0fc2097806385b0718fdd6460db5cbeeaaab93`。
以 W01 为 paired baseline 的 19 个短 perf20 项均通过，合计 `71,515,650 -> 71,083,365`，
减少 `432,285`（`-0.6045%`），17 项改善、2 项退化。两项组合相对 100 MHz milestone
减少 `505,574` cycles（`-0.7063%`）。ROB 定向测试覆盖 pointer wrap、epoch reuse、flush、
结果值和全部排除边界，并显式观察三路同时退休；核心短程序测试只要求稳定出现 multi-wide
retirement，避免把某一特定动态批次形状误当成架构合同。

test-contract 提交 `64bf153347fd` 后，matching 完整 `make cpu-check` 通过
Scala/Verilator `186/186`、Python `364/364` 和 locked port/lint/Yosys/publication；lint
保持 876 条锁定 warning，signature `b021ae6a...`。matching gate metadata 提交为
`572588e`。matching clean func58 又以 random-AXI seeds `240/255/141` 全部通过 `58/58`，
`num_data=0x3a00003a`、LED `1/1`，无 DiffTest mismatch，总 cycles 为
`636390/636760/637596`。这关闭了本地完整 RTL gate 与轻量系统回归；Linux 和完整 SoC
implementation 仍未由该组合的 matching 证据覆盖。

L05/W02 observer 固定于 workspace commit `7e50f16`、schema `nscc-m01-v6`，绑定 CPU
`572588e` 和 RTL `f59c5273...`。四个代表项合计 `42,999,823` 个观测周期，benchmark
cycles 与 clean E02 逐项一致，所有守恒式通过。`5,194,524` 次 D-side translation request 中，
DMW0 Load/Store 为 `2,822,126/1,560,994`，DMW1 为 `809,770/1,629`，TLB Load 只有 5 次；
非 TLB 比例为 `99.99990374%`。direct/DMW Store translation 严格阻塞年轻 Load 共
`80,028` cycles（观测周期的 `0.1861%`）。L05 因此达到 RTL A/B 门槛。

W02 共识别 `3,517,657` 次 current-epoch、无异常、有效 LQ identity 的 Load completion；
`3,000,948` 次存在任意 IQ waiting consumer，`2,922,347` 次在 P0--P2 存在 waiting consumer，
分别占 completion 的 `85.3110%/83.0765%`。P0--P2 保守机会数相当于观测周期的
`6.7962%`，同样达到 RTL A/B 门槛。translation 和 load-use 等待均可被乱序执行隐藏，不能
把 `12.0803%` 与 `6.7962%` 相加成预期加速比。首次 RTL 实验先做 L05，再在其结果冻结后
独立比较 W02，以保留归因并单独审计 P3 数据旁路。

## 4. P1：正确性 gate

`C01-C08` 的优先级高于全部性能候选。下面的“通过”表示相应定向测试、现有门禁和受影响
系统回归通过；仅靠静态阅读或一次 Linux 启动不能关闭条目。

| ID | 首个定向实验 | 必须观察 | 关闭条件 |
| --- | --- | --- | --- |
| C01 P0 accept-before-wakeup | 构造 P0 barrier busy，对未被 `issueReady` 接受的 producer 配置跨端口 consumer，并叠加 flush/exception | producer 的 `valid/ready/fire`、direct wake tag、consumer issue 和实际 operand | 未接受 producer 永不唤醒 consumer；或修复为 accept-qualified 后完成依赖/恢复回归 |
| C02 DIV 证明 | 对活动 `OooDivideUnit` 做 DIV/MOD signed/unsigned differential，覆盖 0、`+/-1`、最小负数、溢出、随机值和每个迭代点 flush | issue、busy、iteration、completion pulse、ROB/epoch/pdst、重启 | 数学结果正确；flush 后无旧 completion；每次接受 exactly one completion |
| C03 completion wraparound | flush 后保留旧 completion，推进 6-bit ROB pointer 完整绕回并复用相同 index/generation 组合 | staged/current epoch、pointer、entry complete、wakeup、commit | 旧事务不能完成新 entry；若靠延迟上界保证，测试必须覆盖所有 completion source |
| C04 VA synonym | 两个 VA 映射同一 PA，执行 older Store -> younger Load，覆盖 byte mask、部分覆盖、TLB/DMW、hit/miss 和 flush | Store/Load VA/PA、order decision、forwarding、cache request 和返回数据 | 年轻 Load 不读到旧值；存在物理比较、replay 或等价顺序证明 |
| C05 AXI error containment | 在 L1I/L1D/L2 refill 的各 beat 注入 RRESP error，并注入 cached writeback BRESP error | exception/error、line valid/dirty、后续 hit、victim ownership、retry/drop | 不安装 poisoned valid line，不静默丢 dirty data；或平台合同明确排除且 assertion/边界被证明 |
| C06 SUC 程序顺序 | delayed older instruction + older Store A（翻译后为 SUC）+ younger SUC/cached Load B，加入 B backpressure | 翻译完成时刻、MAT unknown、LSQ issue、AXI 到达顺序、ROB head/commit | 未知 MAT 的老 Store 不允许导致年轻访存违反 SUC 顺序；异常/flush/DBAR 同样成立 |
| C07 TLB mutation 取消 | TLBWR/TLBFILL/INVTLB 与 I-side walk、D-side LSQ/CACOP translation 在每个状态交叠 | ATU pending/response、owner、drop/cancel token、后续请求进展 | 每个已接受翻译都得到 completion/cancel，或所有 owner 同拍明确撤销；无永久 pending |
| C08 PS=21 拼接 | 非连续或反向的两个 2 MiB half，令 PPN0.bit9/PPN1.bit9 与 VA odd bit 不同 | odd select、PA、权限/MAT、TLBRD/WR/FILL、INVTLB match | PA 使用 `{selected_PPN[19:9], VA[20:0]}` 的架构语义并通过 differential |

建议先执行局部、确定性强的 `C01/C02/C03/C08`，随后执行需要完整 LSU/cache/TLB harness
的 `C04/C06/C07/C05`。这只是降低调试成本，不表示后四项优先级较低。

## 5. P2：M0/M1 观测合同

### 5.1 原则

第一版使用仿真观测，不把统计逻辑接入 DUT 的 ready/valid、选择、flush 或状态更新：

| 层级 | 内容 | 约束 |
| --- | --- | --- |
| R0 | commit、recovery、已有 occupancy 和握手信号 | harness 直接采样，不改 RTL |
| R1 | 仿真专用 valid/ready/fire、full、stall reason probe | 只观察；instrumented 与 uninstrumented commit trace 必须一致 |
| R2 | 带 identity 的 branch/DIV/load/miss/AXI 生命周期 trace | 使用 ROB pointer+generation/epoch、MSHR ID+generation，不能只用数组 index |
| H | 少量硬件计数器 | 推迟到指标证明有板上价值以后；需要重新实现并计入 WNS/资源 |

输出优先使用版本化 JSON/CSV。长仿真不默认保存全波形；先由计数器定位周期，再截取短 FST。

### 5.2 ROI 和退休账本

每个结果必须定义 ROI（Region of Interest）。性能程序使用固定 workload start/end；Linux
分别记录 reset、kernel entry、用户态入口和 shell 等里程碑，不能把不同里程碑的总 IPC
直接比较。

```text
Hretire[k]  = ROI 内每拍退休 k 条指令的周期数，k in 0..3
cycles      = sum(Hretire[0..3])
instructions = Hretire[1] + 2*Hretire[2] + 3*Hretire[3]
IPC         = instructions / cycles
unused_commit_slots = 3*cycles - instructions
```

### 5.3 第一版计数器矩阵

| 域 | 必需计数或直方图 |
| --- | --- |
| Global/retire | ROI cycles、retired instructions、retire width `[0..3]`、异常/ERTN/IDLE、commit slot-loss reason |
| Stage flow | fetch group、decode、rename、dispatch、各端口 issue、各 lane completion、commit 的 offered/accepted slots |
| Frontend | translation request/response fire、相邻 accept interval、uTLB/main TLB、L1I hit/miss/critical return、IBUF occupancy、decode-starved cycles |
| Branch | 分支类型、BTB/PHT/RAS、mispredict、丢失 training、resolve->commit->redirect->first fetch/commit latency |
| Rename/allocation | accepted prefix、ROB/FreeList/LQ/STQ/dispatchQ occupancy、raw-full 和 exposed-block |
| Dispatch/IQ | 四个 IQ occupancy/ready count、enqueue/dequeue、ready-not-selected、FU-blocked、operand-not-ready、wakeup tag conflict |
| Execution | 各 FU accepted/busy/completion、issue->completion latency、DIV operand class、completion arbitration |
| ROB/commit | occupancy、head age、head incomplete FU class、side-effect-blocked、commit stop lane/reason、recovery cause |
| LSQ | LDQ/STQ/SDQ occupancy；translation/order/overlap/partial/cache/SUC wait；Store data/head/maintenance/B wait；MAT-unknown 越序事件 |
| Cache/TLB | 各层 hit/miss/merge、dirty victim、critical beat、MSHR occupancy/full-block、refill latency |
| AXI | AR/AW/W/R/B fire 与 backpressure、active IDs、bytes、read-blocked-by-write、write-wait-idle、RRESP/BRESP |
| Serial/uncached | DBAR/IBAR/CACOP/SUC 次数及 wait-head、drain、request、response 分段 latency |
| Physical | actual MHz、WNS/TNS/WHS/THS、top-N path family、logic/route delay、LUT/FF/Slice/BRAM/DSP、strategy/seed |

所有 `_stall` 必须定义 exposure predicate。例如 `ROB full` 只有在 rename 实际提出有效工作
且因 ROB credit 不能接受时才记为 `rob_full_exposed`。raw occupancy/full 可以重叠；性能
决策优先看 exposed event。

### 5.4 互斥损失栈

raw event 允许同拍重叠。另建互斥的 zero-retire loss stack，使其可与 `Hretire[0]` 对账：

```text
outside ROI/reset
recovery or exception transition
ROB empty -> frontend/translation/L1I/IBUF/decode/dispatch starvation
ROB head incomplete -> ALU/MUL/DIV/load-order/cache/TLB/AXI wait
ROB head complete but blocked -> store/SUC/barrier/CACOP/CSR/interrupt side effect
unclassified
```

`unclassified` 必须接近 0。另对 1/2-wide retire 建立 commit slot-loss 分类，否则只看零提交
周期会遗漏长期带宽不足。

### 5.5 自动守恒检查

以下任一检查失败时，该份性能数据无效：

```text
sum(retire.width) == ROI cycles
sum(k * retire.width[k]) == retired instructions
exclusive zero-retire causes == retire.width[0]
sum(commit slot-loss causes) == 3*cycles - retired instructions
transaction start == complete + cancel + outstanding_end - outstanding_start
queue occupancy_next == occupancy + enqueue_fire - dequeue_fire
```

计数器使用 64 bit 或显式饱和检测。事件在 ROI 边界未完成时必须记录 outstanding/cancelled，
不能把截断事务算作无限延迟。

## 6. P3：基线工作负载矩阵

### 6.1 每次候选的验证层级

| 层级 | 内容 | 何时运行 |
| --- | --- | --- |
| L0 | elaboration、相关定向合同、生成 RTL 一致性 | 每次源码修改 |
| L1 | `make cpu-check` 的完整本地 gate | 每个可比较 candidate |
| L2 | 固定 func/perf workload 与 seeds 的 Verilator 配对 | 每个进入性能比较的 candidate |
| L3 | random AXI/backpressure 与 Linux 分段 | 访存、恢复、系统语义相关修改；里程碑候选 |
| L4 | standalone/完整 SoC Vivado 2023.2 | 通过功能与周期门槛的 candidate |
| L5 | func/perf bitstream 和团队板 | 团队选中的里程碑 candidate；不能替代本地 gate |

第一轮 M2 至少采集：

- `func/func_lab19` 和 `func/func_advance` 的固定 3 seeds；
- c398 tracked `allbench` 镜像的 perf20 每个 benchmark 独立 CPU cycles，不只保存总和；
  `make perf20-sim` 用 switch `7E..6B` 选择分项，并保存镜像、模型和 harness patch hash；
- c398 tracked `nscscc_func/obj` 的 func58 完整镜像，以 runtime switch `FF` 和固定三组
  random-AXI seed 并行运行，作为比单个 `func_lab19` 更宽的本地正确性置信来源；
- clean Linux early-boot 的固定时间窗和里程碑；
- patched random AXI 的固定 paired seeds，明确标成 patched-harness 诊断证据；
- 一个 ALU/branch 依赖链、一个 DIV 密集、一个 load-use、一个 cache miss/dirty eviction、
  一个 SUC/barrier 定向 microbenchmark，帮助解释大程序的混合计数。

M2 通过条件：所有结果有 manifest、ROI、JSON/CSV、日志/hash；没有 DiffTest mismatch、
counter overflow、invariant failure 或无法解释的大量 `unclassified`。

## 7. P4-P5：从数据选择单变量实验

先计算每个方向的周期收益上界：

```text
opportunity_cycles = exposed_event_count * max_removable_latency
cycle_speedup_upper = C0 / (C0 - opportunity_cycles)
```

上界很低的候选不进入高风险 RTL 修改。第一版证据路由如下：

| 主要观测 | 候选 | 进入实验前的条件 |
| --- | --- | --- |
| 热 uTLB/L1I 命中但 accept interval 长，IBUF 经常空 | `F01`、`H03`、`H07` | 先关闭 `C07/C08`；逐级解耦 translation/frontend/L1I，不形成长 ready 链 |
| routed top-N 再次稳定为某一路径族 | 对应局部 timing cut、`P02` | 必须读取 matching RTL 报告；`F03`、L1D、LSQ 都只保留为历史路径族 |
| mispredict 或 resolve->redirect 暴露周期高 | `B01`、`B02`、`B03`、`K01` | 分开预测准确率、commit-time recovery 和固定一拍 redirect；先统计再设计 checkpoint |
| DIV head-wait 或 P1 HOL 高 | `E01`、`E03` | 先关闭 `C02`；按 0、`+/-1`、2 的幂和普通数统计 fast-path 上界 |
| ROB full exposed 高，head 阻塞时仍有 younger-ready | `T02`、`R02` | 先缩减/分 bank hot/cold state；不可只把 64 PRF 改成 128 |
| IQ ready-not-issued 或 dispatch matching loss 高 | `W01`、`W02`、`I01`、`D01`、`D02`、`E04`、`Q01` | 先关闭 `C01`；按端口和原因分开，避免扩大全局 wakeup 比较网 |
| 假源依赖或 FreeList 边界 stall 高 | `R01`、`K02`、`A01` | 逐 opcode 验证 source-used；accepted-prefix 改动需重证原子分配 |
| Store alias/partial forwarding/oldest-Load HOL 高 | `L01`、`L02`、`L03` | 先关闭 `C04/C06`；容量只有在 exposed-full 显著时才扩 |
| dirty write、read-blocked-by-write 或总线空洞高 | `H01`、`H05` | 先固定 `C05` error/ownership 合同；重证 barrier/CACOP/dirty visibility |
| SUC maintenance/drain/B-response 占比高 | `U01`、`U02`、`U03` | 保持严格顺序；posted/token 方案先解决精确 BRESP error 与 interrupt/flush 合同 |
| micro-TLB miss/main-walk 排队高 | `V01`、`V02` | 容量与 walker 带宽分开实验；检查比较器和 TLB 时序 |
| L1D 宽 mux 或 DSP path 成为下一关键路径 | `H04`、`T03`、`P01` | cycle/Fmax 联合比较；新增流水级同步修改 forwarding/wakeup latency |

### 7.1 当前首轮实验序列

1. M0/M1 instrumented model、四拍 source alignment、守恒检查和 clean 对照已完成。
2. `5141e9b` 的 official func58 三个 random-AXI seeds、perf20 20/20 和 Linux random-AXI
   200 ms 三 seeds 已闭合；三项首轮开关归因完成，E01 默认关闭，Store bypass 与 Store
   translation lookahead 保留。
3. H01 L2 write-back 已完成定向测试、19 项 perf20、完整 gates 和 matching 100 MHz
   implementation；周期下降 0.7254%，但 setup `WNS -0.323 ns`，bitstream 仅作诊断。最差
   CPU path 的 78% 为 routing，经过 LSQ older-Store/Store completion/ROB epoch 路径。
4. 当前批次已独立筛入 R01 unused-source、LSQ Store/forwarding 仲裁切分、16 KiB L1I 和
   16 KiB L1D；强回退的 Store fast-completion-off 消融已恢复。19 个短 perf20 全部通过且
   相对 H01 合计 -3.1704%；相对纯 R01 +0.0406%。完整 gates、func58 random-AXI 三 seeds
   以及 Linux instrumented random-AXI 200 ms 三 seeds 均已通过。
5. 当前组合的 matching 100 MHz implementation 已以 setup `+0.041 ns`、hold `+0.050 ns`、
   DRC 0 error 和 bitstream success 闭合，整体成为新的 local milestone；首要路径转为 ROB
   staged wakeup 到 IQ ready/payload CE 的高扇出路径族。matching 团队板 perf20 已以
   总周期约 `-12.65%` 通过 20/20；本地长尾 `stringsearch` 按调度决定不再补跑；
   ROB/PRF 扩容仍要求 ROB-full exposed 和 hidden independent work 同时显著。
6. 下一组合已完成 observer 驱动的 W01、E02 独立软件 A/B：W01 19 项 `-0.102375%`，E02
   相对 W01 `-0.6045%`，组合相对 milestone `-0.7063%`；matching func58 三 seed 58/58。
   两项均保留；其 timing、Linux 和板测结论必须绑定后续 matching 组合 RTL，不能从
   milestone 继承。
7. L05、W02 的 simulation-only observer 均已通过启动门槛。L05 非 TLB translation 占
   `99.99990374%`；W02 P0--P2 waiting-consumer 占合格 completion 的 `83.0765%`。先做
   L05 独立 A/B，再做 W02 独立增量 A/B；机会计数只用于排序，不直接代表 speedup。

## 8. 单次 A/B 实验合同

每个实验目录建议放在 `logs/experiments/YYYYMMDD-<id>-<short-name>/`，包含：

```text
contract.md        hypothesis、唯一变量、预期信号、通过/否决条件
baseline.json      baseline manifest、counters、workload results
candidate.json     candidate manifest、counters、workload results
analysis.md        每 benchmark 差异、机制解释、异常项和结论
artifacts.sha256   保留日志、RTL、报告、DCP/bitstream/package 的 hash
```

必须遵守：

1. baseline 与 candidate 使用相同 ROI、软件 image、memory model 和 paired seeds。
2. 每个 benchmark 报 absolute cycles、相对变化、IPC/retired count 和关键事件差值。
3. 随机结果至少先做 paired comparison，再增加新 seeds 检查稳健性。
4. instrumented 与 uninstrumented 版本的 commit trace、DiffTest、终止状态和总周期一致。
5. 一个候选若同时改正确性和性能，先将正确性修复独立落地并重建 baseline。
6. RTL 变化后重新生成 Verilog；禁止手改 `rtl/mycpu_top.v`。
7. 不继承旧 DCP 的 WNS、资源、bitstream 或板测 verdict。

周期和频率的联合指标为：

```text
normalized_time_ratio = (candidate_cycles / baseline_cycles)
                      / (candidate_actual_mhz / baseline_actual_mhz)
```

`normalized_time_ratio < 1` 才表示执行时间改善。结果还必须逐 benchmark 展开；总和或几何
平均不能掩盖功能失败和明显单项回退。

## 9. P6：完整 SoC 接受门槛

只有通过 P1-P5 的候选才进入 Vivado 2023.2。至少保存：

```text
CPU commit / generated RTL hash / Chiplab commit
build_kind / software image / requested and actual clocks
strategy / directive / seed / constraints summary
post-synth and placed utilization
routed setup WNS/TNS and hold WHS/THS
top 10 setup/hold path families and logic/route split
route status / DRC / bus-skew / bitstream / routed DCP hashes
```

Perf 接受要求实际 CPU 时钟与记录一致、setup/hold 非负、TNS/THS 为 0、DRC 0 error、bitstream
完成。44 ps 旧余量不能作为候选预算。热开发候选若周期下降但 100 MHz 未闭合，保存该次
WNS/TNS、top-N path 和资源后可继续下一轮微架构/时序协同修改，不要求立即安排独立时序 pass
或频率 sweep。进入 milestone/release 选择时才对仍有价值的候选做 matching frequency 实验并
用联合指标决定；负 slack bitstream 始终只是诊断产物，不能上板或作为通过结果。

不要在本地并发运行两个 Vivado implementation。功能 build 和 performance build 是不同 SoC
配置，跨模式不能复用验收结论。

## 10. P7：里程碑与停止条件

候选满足以下全部条件后，才建议进入团队板或 official CI：

- P1 中相关正确性 gate 关闭，完整本地 gate 通过；
- 固定 workload 的配对仿真没有 mismatch，且计数器守恒；
- 至少一个目标 workload 有可解释的周期或联合收益，没有不可接受的单项回退；
- 完整 performance SoC setup/hold/DRC/bitstream 通过；
- Linux 相关修改有 privileged/MMU/cache/atomic 定向证据和 early-boot 日志；
- candidate commit、RTL、bitstream、软件和 package hash 完整。

出现下列任一情况时停止该实验并回到前一阶段：

- C01-C08 复现或新 correctness issue；
- DiffTest mismatch、守恒式失败、instrumentation 改变提交行为；
- 主要收益无法在 paired seeds/benchmark 中复现；
- 频率损失抵消周期收益；
- top-path、资源或拥塞变化与假设不符，且无法由保存的报告解释。

## 11. 第一批具体交付物

下一阶段先交付以下内容，不直接承诺任何微架构优化：

1. `C01-C08` 已关闭；后续 RTL 对受影响协议做 matching regression。
2. M0 retire-width、cycles/instructions/IPC 和互斥 zero-retire 分类（已完成当前 `9de3164` 基线）。
3. M1 `nscc-m01-v2` probe schema、四拍 source alignment、JSON writer、守恒式自动检查和无扰动
   对照（已完成当前 `9de3164` 基线）。
4. `5141e9b` 的 func_lab19、official func58、perf20 20/20 与 Linux random-AXI 200 ms
   三 seeds 已完成；DIV/Store/translation-lookahead 的 19 项配对归因已闭合。
5. E01 默认关闭；Store completion bypass 与 Store translation lookahead 保留。第二批 H01
   真 write-back 已完成完整 gates、19 项与 matching 实现；周期收益保留，负 setup slack
   作为后续时序输入。
6. 当前 R01/LSQ/H02 组合已完成逐项代表集归因、组合 19 项本地软件、完整 gates、func58、
   Linux 三种子门禁、matching 100 MHz timing closure 和团队板 perf20 20/20；板上总周期
   相对同 profile 基线下降约 `12.65%`。已在 official submission 的同名 `dev/ECHO` 分支
   推送 `0a8ef8c` 触发 CI；本地没有取得 pipeline ID，official CI verdict 尚无可记录证据，
   因此不能宣称通过。
7. Vivado 保持单实例，软件门禁通过的批次进入下一次 100 MHz 实现；实现期间继续准备和验证
   下一独立候选。每次最终 route 报告重新决定时序方向，不沿用历史 critical path 名称。
8. `nscc-m01-v5` 已把 E02 严格上界和 W01 affected conflict 闭合；W01/E02 的 19 项独立
   A/B、完整 gates 和 matching func58 三 seed 均通过并进入下一组合候选。Linux 与 matching
   implementation 仍按本文门禁补齐，不将轻量软件结果写成完整验收。
9. `nscc-m01-v6` 已关闭 L05/W02 observer：L05 分类四种翻译模式及 Load/Store owner，W02
   只接受 current-epoch、无异常且匹配有效 LQ 的 completion，并单列 P0--P2 保守子集。两项
   都达到 RTL 启动门槛，按 L05 后 W02 的顺序做独立 A/B。

下一组合以 W01+E02 为已经独立归因的周期候选；L05/W02 已完成测量，按顺序分别 A/B 后决定
是否成为新增独立候选。若加入时序候选，必须保持同拍语义、给出结构保留证明，并针对当前 ROB staged
wakeup 到 IQ 的 matching 路径族。候选 ID 的定义始终回到理论笔记的“5. 优化候选账本”；
具体选择写入新的 experiment contract，不重写账本历史。

首轮执行结果：L04 独立 experiment commit `c30fc470de4a` 的完整 LSQ suite 通过 32/32；19 项 paired
perf20 均通过，但总 cycles 只从 `71,588,939` 降到 `71,550,342`（约 `-0.054%`），且有
4 项退化，故停止在软件 A/B 阶段；`2765433e82e0` 移除实验实现后，published RTL 精确恢复
为冻结基线哈希 `04d6e4b2...`。D01 的严格下界 observer 在
`stream_copy/coremark/inner_product/quick_sort` 合计 `41,501,656` 个观测周期中只发现
`27` 个最老 Load 因 SDQ 单独阻塞的周期（约 `0.65 ppm`），因此停止 D01 RTL 化；不为该
候选增加 ready 组合逻辑或支付 Vivado implementation。随后 v5 observer 在四个代表 perf20
项目中测得 E02 严格上界 `17.2606%`、W01 affected conflict `1.1214%`；W01 与 E02 的 19 项
软件 A/B 分别取得 `-0.102375%` 和相对前者 `-0.6045%`，两项都保留到下一组合。
