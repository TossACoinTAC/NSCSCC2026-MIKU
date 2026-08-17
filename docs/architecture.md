# MIKU 当前微架构学习与优化研讨记录

> 状态说明（2026-08-17）：本文保存了多个历史优化阶段的架构分析与
> 测量记录。文中的“当前”和“本轮”指各段记录形成时的源码身份，不指
> 仓库现在的 CPU 源码。现在的验证范围以 [status.md](status.md) 为准；未同时
> 匹配 commit 与生成 RTL SHA-256 时，本文的性能数字不能用于当前 CPU。

## 1. 目的与边界

本文是 `cpu/` 当前乱序核的持续学习记录。候选编号、状态、实测效果和下一轮优先级由
[optimization-candidates.md](optimization-candidates.md) 单独维护；本文只保留已经由 RTL 或
实现报告支持的事实、教学讨论采用的假设及机制推导。它不是 RTL 规格；当前可执行门禁、
测试影响清单和工具锁分别见
`Makefile`、`cpu/tests/manifest.yml` 与 `cpu/reference/manifest.lock`。历史验证状态已原样
保存在 `docs/archive/nscscc-cpu-final-docs/refactor/status.yml`，仅用于解释旧候选。

当前源码采用以下命名边界：`Ooo*` 只用于 `OooCore`、`OooFrontend`、
`OooBackend`、`OooExecutionCluster` 和提交适配器等乱序架构边界；ROB、IQ、LSQ、
cache、TLB、预测器及执行单元按实际职责命名。仍维持旧周期接口的局部兼容模块统一
使用 `Legacy*`，不再沿用来源项目名称。公开的 `core_top`、AXI、debug/commit 和
reset 接口名称不受这次内部命名规范化影响。

本轮讨论最初采用以下教学假设；本次 2nd pass 再用实际提交历史校正：

- 假设 `docs/archive/nscscc-cpu-final-docs/linux-system-gap-audit.md` 中列出的功能缺口均已正确实现；
- 研究优化时必须继续保持精确异常、内存顺序、cache 一致性语义和 Linux 行为；
- `d9bab16...` 之后的 `e7ae447...`、`1c33132...` 和 `6bbca9b...` 已实际实现并验证了
  DBAR/IBAR、CACOP、CPUCFG、LL/SC、定时器 reset、uncached AXI completion 等大部分
  gap 项；这使它们从教学假设变成当前 RTL 事实；
- 当前证据仍只到 Linux 5.14 early console/memory initialization，没有 shell，因此不能
  把局部语义闭环或无 DiffTest mismatch 扩大成 Linux 验收完成；
- 在 gap audit 之外发现的 `C01-C08` 已完成定向修复或协议证明；其长期回归责任见
  [optimization-candidates.md](optimization-candidates.md)。

本次 2nd pass 的语义审计区间为 CPU
`d9bab16ef46540eb3348b0781afc4d0949f28adc..6bbca9b330ba8d886c888e2804f70b95be18e4cd`，
终点明确取主要功能提交 `6bbca9b...`。其后的 `60fba481...`、`e26ccfa...`、
`d2971e4...` 和 `872bbd4...` 只改变 ignore、验证状态或说明文档，没有新的 Scala/生成 RTL
语义变化。文中涉及 WNS、资源、仿真、bitstream 和板测的后续证据仍绑定到 status 所记录的
source commit `60fba481...`、implementation commit `6bbca9b...` 及 RTL SHA-256
`137657aa...`，不把后续文档 HEAD 当成不同网表或 2nd-pass 终点。

比赛目标不是最大化单独的 IPC 或 Fmax，而是同时降低周期数并提高可闭合频率。
任何候选都应以近似执行时间

```text
T = cycle_count / f_cpu
```

为最终判断对象，并分别保留周期数和完整 SoC 时序数据。

## 2. 当前测量基线

### 2.1 候选身份与验证边界

| 项目 | 当前证据 |
| --- | --- |
| CPU checkout | `dev/ECHO @ ecd4786fcc87...` 的 WT01；matching evidence 冻结于 workspace `445b777d84dd...` |
| 当前 RTL 身份 | CPU source tree SHA-256 `232a0291e523...`；raw RTL `71cab587cb07...`；published RTL `f8aeca2dcfc5...` |
| 固定 Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9`（官方 `nscscc2026`） |
| 最新本地 perf 实现 | WT01 matching 100 MHz full implementation：setup `-0.824 ns`、hold `+0.056 ns`、DRC 0 error/critical warning、fully routed、bitstream 成功；setup 未闭合，因此不是里程碑 |
| 历史物理优化参考 | `627aca6... + c398d274...` 的同网表 post-route 曾得到 setup `+0.009 ns`、hold `+0.012 ns`、DRC 0 和探索用 bitstream；按当前合同只作路径参考，不是正式竞赛产物 |
| 更早参考实现 | `60fba481... + c398d274...` 的 setup `+0.044 ns` 及 `d9bab16... + 68c20a5...` 均为历史网表证据 |
| 器件 / 工具 | `xc7a200tfbg676-2` / Vivado 2023.2 |
| Linux 状态 | 历史候选已有 Linux 5.14 early console/memory initialization；当前 RTL 本轮未重跑 Linux，且仍没有 shell 验收 |
| 当前候选板测 | R5 `fbc9634` 的 matching 100 MHz direct-full bitstream 已在 LabAgent job `20260815-114325-2bc00a63` 完成 perf20 20/20；当前 R6 L13 尚无 matching implementation 或板测 |

### 2.2 宽度与容量

| 结构 | 配置 |
| --- | --- |
| Fetch / decode / rename / dispatch | 4 / 3 / 3 / 3 |
| Issue / writeback / commit | 4 / 5 / 3 |
| 物理寄存器 / ROB | 64 / 32 |
| Instruction buffer / dispatch queue | 16 / 8 |
| 每端口 IQ | 8，共 4 个独立队列 |
| LDQ / STQ / store-data queue | 8 / 8 / 8 |
| L1I / L1D | 各 16 KiB，2-way，128 sets，64 B line |
| 共享 L2 | 64 KiB，2-way，512 sets，64 B line |
| MSHR | 4 |
| TLB | 32 entries |

四个执行端口为：

| 端口 | 能力 | 备注 |
| --- | --- | --- |
| P0 | ALU / CSR / serial | 特权与串行操作入口 |
| P1 | ALU / divide | 可变延迟除法 |
| P2 | ALU / branch / multiply | 分支解析与乘法共享端口 |
| P3 | load / store | 唯一 LSU 端口，issue 输出已寄存 |

### 2.3 完整 SoC 时序与资源

最新一次 matching 100 MHz full implementation 对应 WT01。CPU、system、DDR 实际时钟为
100、100、200 MHz；bitstream 与 fully-routed DCP 均已产生，hold 和 DRC 通过，但 setup
仍未闭合，因此归类为 candidate。完整报告归档在
`Post_Impl_Bundles/cpu_445b777d84dd_chiplab_c398d274812f_perf_100mhz_20260814-203428/`。

| 指标 | `445b777` WT01 | `50f998c` BT04（已否决） | `888d4e6` BT03 对照 | `013db490` 历史 |
| --- | ---: | ---: | ---: | ---: |
| Setup WNS / TNS | `-0.824 ns` / `-470.006 ns` | `-1.442 ns` / `-1550.156 ns` | `-0.694 ns` / `-190.761 ns` | `-0.552 ns` / `-116.580 ns` |
| Setup failing endpoints | 1,644 | 3,195 | 756 | 890 |
| Hold WNS / TNS | `+0.056 ns` / `0 ns` | `+0.050 ns` / `0 ns` | `+0.053 ns` / `0 ns` | `+0.018 ns` / `0 ns` |
| Placed LUT / FF | 88,850 / 54,432 | 89,422 / 54,881 | 86,489 / 54,358 | 89,320 / 54,073 |
| Slice / BRAM tile / DSP | 27,681 / 54.5 / 8 | 27,745 / 54.5 / 8 | 26,920 / 56.5 / 8 | 27,218 / 56.5 / 8 |

BT04 的 top-50 全部属于 IQ。最差数据路径为 11.053 ns，其中逻辑 2.146 ns、布线
8.907 ns，共 14 级逻辑；路径从 recovery 状态穿过另一个 IQ 的 source-ready/direct-wakeup，
再经过目标 IQ 的 wakeup/select 和双槽复制输出 mux，到宽 issue payload 寄存器。top-50 平均
route 占比为 80.45%。相对 BT03，BT04 增加 2,933 LUT、523 FF、825 slice，却没有任何
perf20 周期收益，证明这条恢复方案同时恶化了 packing、拥塞和跨 IQ 级联。当前源码已恢复
BT03 token 输出，并以 WT01 从 direct-wakeup 候选锥中移除 recovery/flush。WT01 matching
route 中这条 recovery 到 IQ 的路径族已从 top-50 消失；剩余主导族为 instruction ATU、
frontend next-PC、predictor update 与 L1I response prediction。

较早的 `013db490` 最差 CPU setup 路径从 BTB bank BRAM 输出经过 tag hit、下一取指预测、
speculative RAS 选择与 instruction ATU 的 DMW/MAT 判定，到 `instructionResponse_uncached`
寄存器；数据路径 10.442 ns，其中逻辑 4.272 ns、布线 6.170 ns，共 15 级逻辑。其后的
top-N 同时包含 L1I tag BRAM 到 data BRAM enable、PHT 地址和前端 response/next-lookup，
说明该网表是前端命中回授路径簇。首轮同网表 `AggressiveExplore` post-route 把
setup WNS/TNS 改善到 `-0.125/-4.616 ns`；第二次有界 pass 从首轮 DCP 进一步得到
`-0.055/-1.050 ns`，hold 均保持正值。最终 top-N 包含 translation PC 到 PHT 地址、BTB
到 ATU、LSQ load 地址 CE、redirect 到 IQ CE，以及 L1I predecode 到 frontend enqueue，
形成跨前端、后端和访存的多路径宽墙。结果已分别归档为探索 candidate；post-route 即使
闭合也只用于路径分析，正式竞赛产物必须从 matching RTL 直接完成一次 full implementation。

历史 `60fba481 + c398` 实现的最差 setup 路径位于 `cpu_clk` 域，从已注册的 L1I
`responseValid` 经 response/prediction/prefix/tail 逻辑，到 frontend 8-entry buffer 的
动态写 clock-enable。最差 data path 为 9.550 ns，其中逻辑 2.164 ns、布线 7.386 ns；
CPU top 10 setup path 有 9 条属于这一族。44 ps 小于常见布局布线波动，证明的是这一次
100 MHz routed implementation 闭合，并不证明可稳定提高到 100.4 MHz。

同平台历史 `8594150...` 的最差路径才是从 L2 refill MSHR ID 到 L1I
`response_predecode_1_target_reg[29]`：数据路径 10.328 ns，逻辑 2.395 ns、布线
7.933 ns。其一阶 97.8 MHz 估计只适用于该旧网表，F02 因此降为“若新 top-N 再出现才
升高优先级”的路径候选。

旧平台参考的最差路径从 ROB `stagedPdst_1_reg[3]` 到 Issue Queue 3 的
`predictedTarget` 发射寄存器，数据路径 9.448 ns，逻辑 1.635 ns、布线 7.813 ns，
并有 108、72、445 的高扇出网络。它仍能证明调度广播和物理局部性值得研究，但
不能作为当前 100 MHz 闭合证据；`60fba481...` 的闭合结论也只属于其 matching 历史归档。

该历史资源显示 LUT 使用 66.49%，但 slice occupancy 已到 81.23%；FF/BRAM/DSP 余量仍较大。
余量适合寄存器复制、流水切级、存储映射和 DSP 化实验；资源占用率本身不决定 Fmax，
继续增加 LUT 还可能加重 packing 与 route 拥塞。

### 2.4 Standalone CPU 资源结构

Standalone 综合与完整 SoC place 不是同一种统计阶段，下表用于判断相对结构成本，
不能与完整 SoC 数字逐项相减。

| 层次 | LUT | 占 standalone CPU LUT |
| --- | ---: | ---: |
| `core_top` | 75,346 | 100% |
| OoO execution backend（含 ROB/IQ/rename 等） | 49,135 | 65.2% |
| ROB | 28,083 | 37.3% |
| Cache hierarchy | 13,104 | 17.4% |
| Frontend | 4,734 | 6.3% |
| TLB | 3,584 | 4.8% |
| Decode/rename buffer | 2,771 | 3.7% |
| 四个 IQ 合计 | 6,097 | 8.1% |

Standalone 最差路径是 P2 乘法输入到结果寄存器，数据路径 9.460 ns，其中逻辑
7.214 ns，经过 2 个 DSP48E1 和 8 个 CARRY4。它说明乘法数据通路本身也是潜在的
频率墙；当前完整 SoC 中更差的 frontend/cache 布线路径将它遮蔽了。

### 2.5 当前功能与周期证据

| 层级 | 当前候选证据 | 结论边界 |
| --- | --- | --- |
| 本地 gates | 当前 WT01 RTL：Scala/Verilator 39 suites / 219 tests；Python、port、lint、Yosys、publication 全通过 | 结构与现有定向合同通过，不证明 Linux shell |
| Chiplab func58 | WT01 matching random-AXI seeds `240/255/141` 全部通过 | 功能仿真不能跨 RTL 继承，也不用于 perf score |
| Chiplab perf20 | WT01 为 20/20、`5,057,854` cycles | 相对 BR01 20 项逐项精确相等；详细单项归因见候选总账 |
| Linux | 本轮未重跑 | 历史 Linux 无 mismatch 不能跨 RTL 继承 |
| 完整 SoC | WT01 matching direct full：setup/hold `-0.824/+0.056 ns`，DRC 0 error/critical warning、fully routed、bitstream 成功 | recovery 到 IQ 路径已移出 top-50，但 setup 未闭合；post-route 永远仅作物理探索，不具备竞赛产物资格 |
| 团队板 | R5 `fbc9634` matching bitstream：perf20 20/20，40 次双跑均通过，保守选中 CPU 总周期 `43,489,002` | job `20260815-114325-2bc00a63`；该证据不属于本表较早的 WT01 节点，也不能继承给 R6 |

## 3. 全局心智模型

与经典五级顺序流水线相比，这个核最重要的变化不是“流水级更多”，而是把一条
顺序指令流拆成三个不同的顺序域：

```text
预测的程序顺序
    -> Fetch 4 -> Decode/Rename/Dispatch 3
    -> 分布式 IQ：按操作数就绪和年龄乱序 Issue 4
    -> Execution / LSU -> Completion / Writeback 5
    -> ROB：重新按程序顺序 Commit 3
    -> 架构寄存器、CSR/TLB、内存副作用和外部观察
```

可以把整机理解成两份状态：

- **投机状态**：PC、GHR/RAS、RAT、ROB/IQ/LSQ 条目、未提交物理寄存器和 cache
  请求上下文。它允许超前执行，也必须能在误预测或异常时撤销；
- **架构状态**：提交后的寄存器映射、CSR/TLB、LL/SC reservation、可见 store
  和精确 PC。只有 ROB 头部按序提交才能改变它。

因此，ROB 同时处在三条关键链路上：容量窗口、精确状态边界、completion 到
wakeup/commit 的广播网络。它占旧参考 standalone CPU LUT 的 37.3%，并曾是完整
SoC 最差路径的源头；历史 `60fba481 + c398` 的首要时序问题曾转到 L1I response 到
frontend dynamic enqueue，ROB/IQ 网络保留为可能再次浮现的路径族，而非当前 WNS。

## 4. 分阶段学习路线

1. **全局模型与性能方程**：顺序域、投机域、架构域，以及周期数/Fmax 的共同目标。
2. **前端**：取指分组、跨组对齐、BTB/PHT/GHR/RAS、预解码、L1I 和 redirect。
3. **Decode 与 Rename**：LA32R 解码、RAT、FreeList、物理寄存器如何消除 WAR/WAW。
4. **分配与窗口**：ROB、dispatch queue/window、四个 IQ、LSQ/SDQ 的原子分配条件。
5. **Wakeup/Select/Issue**：年龄选择、早唤醒、PRF 读、旁路及四端口能力不对称。
6. **执行与完成**：ALU、branch、MUL/DIV、五路 writeback、completion 与 replay 风险。
7. **顺序提交与恢复**：三提交、精确异常、branch recovery、CSR/串行指令和状态回滚。
8. **内存乱序执行**：LDQ/STQ/SDQ、转发、违例约束、uncached、DBAR/IBAR、LL/SC。
9. **存储层次**：L1I/L1D/L2、MSHR、critical-word-first、AXI ID/WRAP burst 与背压。
10. **系统状态**：MMU/TLB、CSR、异常/中断、CACOP 与 Linux 所需的精确系统语义。
11. **FPGA 物理实现**：LUT/BRAM/DSP 映射、高扇出、布线、拥塞和完整 SoC timing。
12. **优化决策**：用计数器与实验把前端、后端、内存、频率瓶颈分开归因。

## 5. 优化候选与实验状态

候选编号、完整账本、当前状态、已测效果和下一轮优先级已迁移到
[optimization-candidates.md](optimization-candidates.md)。本文件保留十二阶段微架构教学、
机制推导和实例；候选状态只在总账维护，避免教学正文与实验进度形成两份冲突来源。

## 6. 讨论记录

本章按记录日期保留理论推导和当时证据，文中的“当前”只指对应记录节点。候选在现行
RTL 中的状态、默认开关和实测效果统一以 [optimization-candidates.md](optimization-candidates.md)
为准；历史讨论不再承担状态总账职责。

讨论记录按部件和验证主题分为以下文件：

1. [前端、分支预测与 Rename](architecture/frontend-and-rename.md)
2. [Allocation、Issue、执行、完成与提交](architecture/backend-execution-and-commit.md)
3. [LSQ、Cache 层次与 AXI](architecture/memory-hierarchy.md)
4. [MMU、TLB、CSR 与系统状态](architecture/system-state.md)
5. [性能上限、FPGA 实现与测量方法](architecture/performance-and-fpga.md)

## 7. 证据与术语

- [证据索引](architecture/evidence-index.md)
- [缩写与术语对照](architecture/glossary.md)
