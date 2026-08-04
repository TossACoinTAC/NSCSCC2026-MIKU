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

## 2. 滚动证据基线

RTL 在本阶段持续演进，因此不再用一个“当前”标签混合不同候选的证据。每条结论必须绑定
source commit 和 generated RTL hash：

| 层级 | CPU / RTL | 可使用的证据 | 明确不能继承的结论 |
| --- | --- | --- | --- |
| 最近完整里程碑 | `60fba481888a` / `137657aa...` | local gates；func/perf 仿真；100 MHz `WNS +0.044 ns`、`WHS +0.050 ns`；func58/perf20 团队板通过 | 不能证明 P1 修复后的 RTL 时序或板级正确性 |
| P1 + L1D 时序候选 | `dd469eaff61a` / `c655a887...` | Scala/Verilator 177、Python 364、locked gates；func 两套各 3 seeds；Linux clean 50 ms；random-AXI Linux 200 ms × 3 seeds | 100 MHz setup 失败，不能作为板卡候选或 timing-closed 基线 |
| 当前开发候选 | `9de316494fc0` / `00d37c5b...` | 完整 `make cpu-check`；func 两套各 3 seeds；Linux clean/instrumented 50 ms；M0/M1 无扰动和守恒合同 | 尚无 matching Vivado、bitstream 或板测证据 |

Chiplab 对三者均固定为 `c398d274812f164d387146fa7d8f612a4a1296d9`。当前工作树只保留
用户已有的 `D AGENTS.md`；它不进入 RTL，也不因实验被恢复或提交。

`dd469ea` 的 matching 100 MHz implementation 是当前最近的时序样本：setup `WNS -0.449 ns`、
`TNS -30.140 ns`、220 个失败端点；hold `WHS +0.047 ns`；DRC 0 error；bitstream 成功。资源为
89,689 LUT（67.03%）、53,573 FF（19.90%）、68.5 BRAM tile（18.77%）、8 DSP。最差路径已从
历史 `F03` 和 L1D 路径迁移到 LSQ：`loadHead_reg[1]_replica` 到
`completion_data_reg[11]`，data path 10.342 ns，其中 route 7.506 ns（72.6%）。归档位于
`Stable_Backup/cpu_dd469eaff61a_chiplab_c398d274812f_perf_100mhz_20260804-154312_candidate/`。

`e5212e3` 针对该路径在既有 `scheduledLoad` 拍捕获 PA、translationDone 和 uncached，预期移除
动态 `loadHead` 选择及跨 Store 比较前的宽 mux，未增加 load 流水拍数。这个预期只有源码和
gate 支持，尚未由 place/route 证实；下一次 implementation 必须重新读取 top-N path，不能
预设 LSQ 仍是最差路径。

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

### 3.2 2026-08-04 启动与 P1 closure manifest

本轮明确采用“先关闭全部 `C01-C08`，再修改性能 RTL”的执行策略。定向 Scala suite 使用
`make cpu-test CPU_TEST=<fully-qualified-suite>`；完整 verdict 仍只能由 `make cpu-check` 给出。

| 字段 | 冻结值 |
| --- | --- |
| Experiment | `20260804-next-stage-p0-baseline`；`role=baseline` |
| 启动 CPU repository HEAD | `872bbd4e9f16ecdde8b0915316bd0f21976fc5ac`，branch `dev/ECHO` |
| 当前 CPU repository HEAD | `9de316494fc03d746b597afb1f4f271e9762114f`，branch `dev/ECHO` |
| Functional implementation | `6bbca9b330ba8d886c888e2804f70b95be18e4cd` |
| Dirty state | 仅保留用户已有的 `D AGENTS.md`；binary diff SHA-256 `4fb5b8c92a389a56a89bd3d5adf5137ea25418806048ade3c157a41df13a86f3` |
| 当前 generated RTL | `00d37c5bc78fe0052cabf7e9d3ae665f31e0a7a0c238ea181ab935957b4c40c1` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9`；现有生成物/测试补丁不属于 clean baseline |
| Tools | SBT `1.10.11`；Java `21.0.11`；Verilator `5.020`；Vivado `2023.2` |
| 当前 reproduced gates | Scala/Verilator 177 tests；Python 364；locked port/lint/Yosys/publication 全通过；lint 856 warnings，signature `49ee79a...` |
| 当前 software/model hashes | 由 `9de3164` 的新 `sim-prepare` manifest 补入；不得沿用 `dd469ea` 模型 |

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
`build/sim/prepared/cpu_<commit>_chiplab_<commit>/<profile>/`，再编译模型与软件。`clean`
直接使用官方 testbench；`instrumented` 只应用
`tools/sim-patches/difftest-interrupt-memory-order.patch` 和 M0/M1 只读 monitor patch，并先校验
各自 SHA-256 lock。monitor 从 testbench C++ 读取 Verilator 层次信号，不驱动 DUT。
prepared manifest 保存 CPU/Chiplab/RTL/model/software、dirty patch 和 profile patch hash。

`make sim-matrix` 不重新生成或编译模型。它复核当前 HEAD、RTL 和 prepared model hash，随后为
每个 workload/seed/time-limit 创建独立的 `ram.dat`、日志与运行 manifest。非 Linux workload
必须到达 `Reached test end PC`；Linux 固定窗口必须无 DiffTest/trace error 并到达预期 time
limit。Chiplab 仿真进程即使超时也返回 0，因此 OS exit status 不能单独作为 verdict。

默认 `SIM_LANES=2`。`SIM_LANES=3` 还要求显式设置 `SIM_ALLOW_THREE=1`、提供实测
`SIM_LANE_PEAK_MB<2560`，且启动时 `MemAvailable>6144 MiB`。2026-08-04 的两个 clean
`func_lab19` 短窗口 lane 各约 8 MiB RSS，隔离运行成立。早期 1.3 ms 失败来自未设置动态
`end_pc` 的旧入口，已由 `sim-matrix` 从 ELF `test_finish+0x30` 解析修复；不得继续引用为当前
功能失败。

### 3.4 RTL 演进与预期同步

每次 RTL 改动后按下表滚动，不把候选名称当作稳定微架构：

| 变更 | 已观察 | 下一步预期 | 使预期成立的证据 |
| --- | --- | --- | --- |
| P1 `C01-C08` | 定向和完整 gate、matching 系统仿真通过 | 作为性能实验正确性底座 | 当前 candidate matching DiffTest |
| `2c56740` L1D refill readiness | 原 L1D 路径退出 top path | 不再优先优化旧 L1D mux | `dd469ea` routed top-N |
| `e5212e3` LSQ selected-load translation cut | source/gate 通过 | 移除 `loadHead` PA mux；周期不变 | 下一次 matching routed top-N + paired cycles |
| M0/M1 harness | simulation-only；clean/instrumented commit trace、UART、cycles/instructions 一致；v2 source alignment 和 JSON 守恒通过 | 细分 Linux Store translation/completion 暴露原因 | M2 microbenchmark + 最大损失短 trace |
| 首批性能 RTL | 尚未选择 | 暂把 Store translation/completion 细分列为主线，DIV fast path 列为独立条件候选；不预设 ROB/PRF 扩容 | 各候选独立开关的 paired workloads/seeds |

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
- perf20 每个 benchmark 的独立 cycles，不只保存总和；c398 `run_prog` 不支持把 perf20 当作
  普通 Verilator workload，本地 M2 先用支持的 C/microbenchmark 代理负载，perf20 分项保留
  到 matching performance SoC/团队板或官方 flow，二者不得混称；
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
2. `func_lab19` 与 Linux 50 ms 的 v2 M2 已完成；继续补 perf20 分项或本地 C 代理负载，并用
   短 trace 拆开 Linux Store translation 与 completion-to-ROB 两个最大桶。
3. Store 路径细分后，从 LSQ/TLB/cache/AXI 中选择周期上界最高且风险可控的一项建立单变量
   experiment card；DIV fast path 可并行建立独立 microbenchmark card。当前不预先选择 `F03`，
   因为它已不是最近 routed top path，也不因 ROB full 便直接扩容 ROB/PRF。
4. 候选通过 paired cycles 后，把 LSQ timing cut 与该性能改动合并做一次 100 MHz full-SoC
   implementation；分别用源码边界和 counters 解释两个变量，时序只给合并候选 verdict。
5. 只有 ROB-full exposed 和 hidden independent work 同时显著，才进入 ROB/PRF 协同扩容；
   fast DIV 类别的动态次数足够大时，才把 DIV fast path 提升为高优先级。

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
4. `func_lab19` 与 Linux clean 50 ms 的 M2 已完成；perf20 分项或本地代理负载及固定
   random-AXI M2 尚待补齐。
5. 当前最大 exposed loss 已定位到 Linux Store translation/completion；尚需短 FST 拆分和
   第一张候选 experiment card。
6. 多个独立开关候选先分别通过软件 A/B，再合并做单次 100 MHz 实现，用于验证 LSQ cut、
   重新识别 top path，并给出有效联合指标。

完成前五项后，由测量数据选择 `F01/H03/H07`、DIV、branch、queue/cache 或其他排名第一的单变量 RTL
实验。该选择应写入新的 experiment contract，而不是直接修改本计划中的优先级历史。
