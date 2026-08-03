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
3. 让每次 RTL 实验只有一个主要变量，并用相同 workload/seed 做配对比较。
4. 用 matching RTL 的完整 SoC 周期和 Fmax 联合判断，而不是单独追求 IPC 或 WNS。

本文是实验合同，不是验证结果。当前候选、哈希、仿真、实现和板测结果仍以
[`refactor/status.yml`](../nscscc-cpu/docs/refactor/status.yml) 为唯一状态源。每个实验还必须保存自己的不可变
manifest，不能因为 status 后来更新而改变历史实验身份。

## 2. 起点证据

编写本计划时，可复用的功能基线证据如下：

| 项目 | 基线 |
| --- | --- |
| Implementation change | `6bbca9b330ba8d886c888e2804f70b95be18e4cd` |
| 已归档 source | `60fba481888a8f7e5a2f0ba0b76c91422a117309`；相对 `6bbca9b` 没有 RTL 语义变化 |
| Generated RTL SHA-256 | `137657aa0c594334568cc386571d13aa9cdc828c8fc45c56ed421be15912c209` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| Local gates | Scala 38 suites / 161 tests；Python 364；port/lint/Yosys/publication 通过 |
| Chiplab simulation | `func_lab19`、`func_advance` 各 3 seeds；perf20 20/20 |
| Linux Verilator | clean 24,999,995 cycles / 13,924,596 instructions，约 0.556984 IPC，无 mismatch |
| Perf implementation | 100 MHz；setup `+0.044 ns`、hold `+0.050 ns`；DRC 0 error |
| Placed resources | 88,967 LUT、53,697 FF、65.5 BRAM tile、8 DSP；slice occupancy 81.23% |
| Team board | func58 58/58；perf20 20/20；Linux shell 未满足 |

这些数字只描述该 RTL。开始首个实验前必须重新读取 status，确认基线 source、RTL hash、
Chiplab 和软件 image；如果 CPU 侧已有新的 RTL 提交，应把它作为独立候选重新过 gate，
不能把上表的时序或板测结论迁移给它。

当前 100 MHz setup 余量只有 44 ps。现有最差路径族是 L1I response 到 frontend 8-entry
buffer 的动态 enqueue clock-enable（`F03`），紧随其后的是 L1D refill/response 宽 mux。
因此任何增加广播、队列容量或宽 mux 的实验都必须重跑完整 SoC implementation。

## 3. 总体执行顺序

```text
P0  冻结候选身份并复现最低成本 gate
 -> P1 关闭 C01-C08 正确性 gate
 -> P2 建立 M0/M1 非侵入观测与守恒检查
 -> P3 固定 workload/seed，采集 M2 基线
 -> P4 用短 trace 校验最大瓶颈的计数定义
 -> P5 对排名第一的候选做单变量 A/B
 -> P6 matching RTL 的完整 SoC 时序/资源验证
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

### 3.2 2026-08-04 启动 manifest

本轮明确采用“先关闭全部 `C01-C08`，再修改性能 RTL”的执行策略。定向 Scala suite 使用
`make cpu-test CPU_TEST=<fully-qualified-suite>`；完整 verdict 仍只能由 `make cpu-check` 给出。

| 字段 | 冻结值 |
| --- | --- |
| Experiment | `20260804-next-stage-p0-baseline`；`role=baseline` |
| CPU repository HEAD | `872bbd4e9f16ecdde8b0915316bd0f21976fc5ac`，branch `dev/ECHO` |
| Functional implementation | `6bbca9b330ba8d886c888e2804f70b95be18e4cd` |
| Dirty state | 仅保留用户已有的 `D AGENTS.md`；binary diff SHA-256 `4fb5b8c92a389a56a89bd3d5adf5137ea25418806048ade3c157a41df13a86f3` |
| Generated RTL | `137657aa0c594334568cc386571d13aa9cdc828c8fc45c56ed421be15912c209` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9`；现有生成物/测试补丁不属于 clean baseline |
| Tools | SBT `1.10.11`；Java `21.0.11`；Verilator `5.020`；Vivado `2023.2` |
| Reproduced gates | Scala 38 suites / 161 tests；Python 364；locked port/lint/Yosys/publication 全通过 |
| Software hashes | 尚未生成；必须由隔离的 `sim-prepare` 产物补入，不能沿用工作树临时文件 |

P1 的活动状态如下；`closed` 只能在定向测试、完整 gate 和对应系统回归均通过后填写：

| ID | 状态 | CPU commit | 定向证据 | 系统回归 |
| --- | --- | --- | --- | --- |
| C01 | open | - | - | - |
| C02 | open | - | - | - |
| C03 | open | - | - | - |
| C04 | open | - | - | - |
| C05 | open | - | - | - |
| C06 | open | - | - | - |
| C07 | open | - | - | - |
| C08 | open | - | - | - |

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
- perf20 每个 benchmark 的独立 cycles，不只保存总和；
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
| 当前 top-N 稳定为 L1I response -> frontend enqueue | `F03`、`P02` | 固定 RTL 多 strategy/seed 确认路径族；先尝试局部 valid/rotating buffer，再评估加拍 |
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

### 7.1 建议的首轮实验序列

除非 M2 数据强烈反驳，按以下顺序推进：

1. **P02 物理重复性对照**：同一 RTL 做少量固定 strategy/seed，实现结果只用于估计 44 ps
   的波动和 top-path 稳定性，不把最佳 seed 当成架构收益。
2. **F03 小步 A/B**：拆分 L1I response 的 learn/correction、slot prefix 和动态 destination
   控制锥；先尝试不增加架构拍数的局部化。
3. **F01/H03/H07 前端供给**：若 M2 证明热路径 accept interval 接近静态分析的 4 拍，
   依次加入小 FIFO、流水化 micro-TLB accept、VA-index early lookup；每步单独比较。
4. **最大 exposed cycle 候选**：从 branch、DIV、ROB/IQ、LSQ/cache/AXI 中选择 M2 排名最高
   且正确性依赖已关闭的一项，不预先假定一定是扩容。
5. **R02 最后进入结构扩容**：只有 ROB-full exposed 和 hidden independent work 同时显著，
   且 T02 已降低 ROB LUT/布线成本时，才比较 ROB32/PRF64 与 ROB64/PRF128。

F03 与 F01 允许分别形成 frequency candidate 和 cycle candidate；不要在第一版同时修改，
否则无法判断收益来源。

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
完成。44 ps 旧余量不能作为候选预算。若周期下降但 100 MHz 不再闭合，应继续做匹配 RTL 的
频率 sweep，并用联合指标决定；不能把负 slack bitstream 作为通过结果。

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

1. `C01/C02/C03/C08` 的定向测试及结果；随后补齐 `C04-C07`。
2. M0 retire-width、cycles/instructions/IPC 和最小 zero-retire 分类。
3. M1 版本化 probe schema、JSON/CSV writer、守恒式自动检查和无扰动对照。
4. perf20 分项、Linux clean 窗口和固定 random-AXI seeds 的 M2 基线数据。
5. 最大三类 exposed loss、对应短 FST 和第一张候选 experiment card。
6. 同 RTL 的少量 P02 实现对照，用于确认 `F03` 路径族与 44 ps 波动范围。

完成这六项后，再由测量数据选择 `F03`、`F01/H03/H07` 或其他排名第一的单变量 RTL
实验。该选择应写入新的 experiment contract，而不是直接修改本计划中的优先级历史。
