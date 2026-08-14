# MIKU 当前微架构学习与优化研讨记录

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
| 当前候选板测 | 板侧服务器暂不可用；当前 RTL 没有 matching 板测证据 |

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
| 团队板 | 板侧服务器暂不可用 | 本轮边界止于完整 SoC 实现 |

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

### 2026-08-04：`d9bab16..6bbca9b` 全文 2nd pass

这段历史共有 13 个提交、54 个变更文件，合计 `+13,269/-5,711`。数字中包含约束、
验证记录和重新生成的 `rtl/mycpu_top.v`，不能把代码行数当成微架构改造规模。按语义可分为：

| 提交组 | 实际作用 | 对本文的影响 |
| --- | --- | --- |
| `e7ae447...` | 引入 speculative/committed memory epoch；实现 DBAR/IBAR 的 older-store、cache/MSHR/L2/AXI drain 与精确 completion | 阶段 4、6、7、8、9 的 barrier 描述由设计意图升级为当前事实 |
| `7d35545...` | 去掉 queue/window 本地 ready/valid 上重复的 flush 组合门控；micro-TLB hit result 从动态宽 entry mux 改成 masked-field merge | 阶段 4 和 10 的 timing 结构改变，容量和架构语义未变 |
| `1c33132...` | reset 清完整 ESTAT/TCFG/TVAL 和 timer enable，并增加 level-pending timer 测试 | 阶段 10 的 reset/定时器缺口闭环 |
| `79c0045...`、`80fb1cb...` | 在退休边界报告 load/store PA、VA、mask/data，并保证 flush 同拍的 retiring memory event 不丢失 | 主要增强 DiffTest 可观测性；不提高执行性能，也不修复 C04/C06 |
| `6bbca9b...` | 完成 CACOP、CPUCFG、64-byte LL/SC、uncached Store 等待 AXI B、错误到精确 ADEM、response ID、cached/uncached response 仲裁等 Linux system semantics | 阶段 6--10 变化最大；perf20 只增加 53 cycles，说明修复在该 workload 的动态成本很低 |
| `c35571c...`、`2301dde...`、`8594150...` 及其验证提交 | 自动刷新/发布生成 RTL、记录 c398 兼容与板测证据 | 改变证据链，不应解释成新的微架构功能 |

审计终点之后的 `60fba481...`、`e26ccfa...`、`d2971e4...` 和 `872bbd4...` 不计入上表和
提交统计。它们提供 ignore、仿真/实现/板测状态与 gap audit 文档；当前 status 仍把实现归于
`6bbca9b...`，生成 RTL 内容归于 source `60fba481...` / SHA-256 `137657aa...`。

逐阶段复核后的结论如下：

| 阶段 | 2nd-pass 结论 |
| ---: | --- |
| 1. 全局模型 | 宽度、容量和顺序域模型未变；基线改为当前 100 MHz timing-pass 候选，Linux 仍未到 shell |
| 2. 前端 | predictor 和 frontend source 没有实质改造；当前 WNS 转为 F03，热翻译/取指 II 与 B02/B03 仍待计数器证明 |
| 3. Decode/Rename | CACOP 从 LSU 类改路由到 barrier/P0，增加 CPUCFG geometry；R01、FreeList 边界和 R02 判断不变 |
| 4. Allocation/Dispatch | memory epoch 进入 uop/分配原子条件；queue/window timing 门控简化；A01/D01/D02/Q01 仍未实现 |
| 5. Wakeup/Select | 该历史区间尚未关闭 C01；当前源码已改为 acceptance-qualified wakeup 并保留定向门禁 |
| 6. Execution/Completion | 该历史区间尚未完成 C02；当前活动 DIV/MOD 算术、flush/restart 与 exactly-once completion 已形成门禁，E01 默认关闭 |
| 7. Commit/Recovery | 该历史区间尚未关闭 C03/B03；当前 epoch/wraparound 已闭环，B03-full 已保留，B01/K01/K02 仍属开放性能方向 |
| 8. LSQ | 该历史区间尚未关闭 C04/C06；当前 PA alias/forwarding 与 MAT/SUC 顺序已有定向修复和回归 |
| 9. Cache/AXI | 该历史区间尚未关闭 C05；当前 refill/writeback error containment 已形成门禁 |
| 10. MMU/CSR | 该历史区间尚未关闭 C07/C08；当前 translation cancel 和 PS=21 拼接已修复并覆盖 |
| 11. FPGA | 新网表从旧候选 `-0.225 ns` 变为 `+0.044 ns`；WNS 转到 F03，slice occupancy 81.23%，频率余量仍很薄 |
| 12. 决策实验 | M01 合同仍适用；所有 baseline key 改为 source/RTL hash/Chiplab 三元组，第一轮实际工作仍是 M0/M1 采样而非扩容 |

因此，本轮开发不是“IPC 微架构已经大改、旧分析全部失效”。它首先把系统正确性从假设
推进到大量可执行证据，并更换了物理关键路径。前端吞吐、预测、选择性恢复、ROB/PRF、
wakeup/select、DIV、LSQ 并行度等性能方向大体保留；它们现在必须在 `60fba481...` 的
cycle/timing baseline 上重新测量。

### 2026-08-03：建立基线与第一层全局模型

- 确认核心是 fetch 4、decode/rename/dispatch 3、issue 4、writeback 5、commit 3 的
  四端口乱序设计，而不是简单加宽的五级流水线。
- 初稿把固定 c398 的 100 MHz perf 基线记录为 `8594150...`：WNS `-0.225 ns`、TNS
  `-12.096 ns`，最差路径为 L2 refill MSHR ID 到 L1I response predecode。2nd pass
  已用 `60fba481...` 的 `+0.044 ns` timing-pass 实现取代它；旧 c398 与 `68c20a5...`
  的 ROB/IQ 路径都只保留为历史结构参考。
- 确认资源压力高度集中于 LUT，且 ROB 是 standalone CPU 最大单一 LUT 模块。
- 暂不根据通用经验宣称 predictor、cache 或窗口扩容有收益；先在对应章节建立
  stall/occupancy/miss 的可证伪假设。

### 2026-08-03：选择性分支恢复评估

当前 ROB 在分支 execute/completion 时只记录 `branchMispredict`，直到该分支进入
commit group 才产生 `recoveryValid`。因此，在现有恢复时点，分支之前的动态指令
已经提交或正在同一 commit group 提交；把这一时点的全清空改成“保留 older”不会
保留额外有效工作，预期周期收益接近零。

真正有潜力的 B01 是把 redirect 前移到 branch execute/completion：保留分支及其
older 未提交指令，只 squash younger 指令，再允许正确路径在它们之后继续进入 ROB。
当误预测分支被更老的 cache miss、DIV 或其他长延迟指令挡在 ROB 中时，这可以避免
等待长延迟指令完成后才开始取正确路径，收益上限明显高于单纯缩短固定流水线罚时。

该方向不是局部修改。至少需要同时解决：

- ROB tail 回退到误预测分支之后，并让已完成/未完成的 older entries 继续有效；
- speculative RAT 恢复到该分支之后的映射，FreeList 精确回收 younger 分配；
- 四个年龄有序 IQ、operand pipeline、LDQ/STQ/SDQ 选择性删除 younger 状态；
- 保留 older cache/translation 请求，同时拒绝 younger 的延迟 completion/response；
- 当前全局 `recoveryEpoch` 在 partial recovery 后不能同时接受旧 epoch 的 older 响应
  并拒绝同 epoch 的 younger 响应，需要 per-entry generation、branch mask 或等价机制；
- predictor GHR/RAS 恢复到误预测分支之前并注入真实结果，而不是只恢复到已提交历史；
- 多个在途分支、异常优先级、同拍 completion/commit/allocate 和指针回绕的证明。

FPGA 风险较高：年龄比较、选择性 valid 更新和 branch kill 广播会跨 ROB/IQ/LSQ，
可能增加高扇出与路由延迟。当前最差路径虽已转为 frontend F03，但历史 ROB/IQ 路径
证明这种全局恢复网络很容易成为下一轮频率墙。因此候选应
采用寄存式恢复事件和局部状态更新，禁止把 execute 结果直接接入所有队列的当拍
wakeup/select 控制。

进入设计实验前先增加仿真侧统计，不要求立即增加可综合计数器：

1. 每次误预测从 `branchResolved` 到当前 `recoveryValid/redirect` 的周期数分布；
2. resolve 时该分支之前的 ROB 距离、未完成指令数及长延迟操作类型；
3. resolve 后至 redirect 期间分配、发射和完成的错误路径指令数；
4. 每个 benchmark 的 branch 数、误预测数/MPKI，以及上述等待周期总量；
5. 用 trace 驱动模型估计提前恢复的周期收益上限，再与预计 Fmax 损失比较。

暂定结论：**commit-time selective flush 否决；execute-time selective recovery 保留为
高潜力、高复杂度、测量前置的长期候选 B01**。Linux 功能闭合、T01/T02 时序问题和
B01 的收益统计完成之前，不建议把它作为近期 RTL 工作。

### 2026-08-03：前端结构与候选方向

当前前端以 16-byte 对齐 group 为取指单位，每组 4 条；Instruction Buffer 为 8 项，
向固定三宽 decoder 输出程序顺序前缀。它不是每拍启动一个 group 的完全流水前端：
预测器同步读取与地址翻译并行，但同一时刻只有一个 translation context、一个翻译后
请求槽和一个 L1I request context。`nextFetchPc` 在 cache request 被接受时推进，随后
才能启动下一 group 的 translation；相邻 group 可在 translation/cache/response 阶段
部分重叠，但并行度有限。

预测分三层修正：请求时 BTB/PHT/RAS 产生 early prediction；L1I response predecode
看到真实指令编码后执行 `FixBranch`，修正 cold/stale BTB 造成的下一组 PC；分支执行
阶段再根据真实操作数判断最终 misprediction。Response-side 修正只阻止后续错误 group，
不会替代 execute-time 条件分支解析。

当前 predictor 参数为每 lane 一个 128-entry BTB bank 和 1024-entry PHT bank、8-bit
speculative/architectural GHR、8-entry speculative/architectural RAS。PHT 索引实际由
GHR 低 5 bit 与 group PC 的 5 bit 拼成 10 bit；每拍最多处理一个精确 commit training
update。Standalone 层次报告中 predictor 约占 853 LUT、4 RAMB36、4 RAMB18。

`d9bab16..HEAD` 没有修改 predictor 或 `OooFrontend.scala` 的生产逻辑；新增 frontend
测试主要服务系统异常/集成合同。因此本节的 BTB/PHT/GHR/RAS、单 translation context、
单 translated slot 和 B03 结论仍适用。变化来自物理证据：当前最差路径正落在 L1I
response 到 frontend buffer enqueue，F03 的优先级高于初稿中的 F02。

前端优化按如下次序测量：

1. 统计 decoder 因 Instruction Buffer 为空而少于三条输出的周期，区分 translation、
   L1I hit/miss、buffer credit、redirect/FixBranch 和 uncached drain 原因；
2. 统计连续 L1I hit 时相邻 group 的 translation request、cache request 和 response
   启动间隔，判断 F01 的吞吐上限；
3. 统计 BTB hit、PHT direction、target、indirect、RAS 分类准确率和最终 MPKI；
4. 统计同拍提交多个 branch 导致只训练一个 branch 的频率，判断训练带宽是否重要；
5. 只有 F01 被证明确实限制 decode 供给时才增加请求上下文；只有 B02 的错误分类
   足够集中时才改变 predictor。单独扩大 8-entry buffer 不能提高上游平均供给率。

### 2026-08-03：BTB/PHT/GHR/RAS 细化

当前实际使用 `BankedFetchPredictor`。16-byte fetch group 的四个指令槽各有一组
BTB/PHT bank：BTB 每 bank 128 项，PHT 每 bank 1024 个 2-bit counter；总计 512 个
BTB 槽位和 4096 个 PHT counter。预测查询为同步读，四个 lane 并行返回。

- **BTB（Branch Target Buffer）**以分支 PC 查找，保存 valid、tag、分支类型、目标
  地址和 direction-trained 位。PC `[3:2]` 选择 lane bank，`[10:4]` 选择 128 行，
  `[31:11]` 作为 tag。tag 命中后才能认为该 lane 曾经出现过分支。BTB 主要回答
  “哪里有控制转移、属于哪一类、可能跳到哪里”。
- **PHT（Pattern History Table）**保存 2-bit 饱和计数器：`00/01` 预测不跳，
  `10/11` 预测跳转；实际 taken 加一、not-taken 减一并在两端饱和。当前索引为
  `{speculativeGhr[4:0], groupPc[8:4]}`，每个 lane 使用自己的 bank。首次训练直接写入
  weak-not-taken `01` 或 weak-taken `10`。条件分支的 BTB 尚未标记 direction-trained
  时采用 BTFNT，避免读取未初始化的 PHT 数据。
- **GHR（Global History Register）**是最近条件分支方向的移位历史，taken 移入 1，
  not-taken 移入 0。RTL 保存 8 bit speculative/architectural 两份状态，但 PHT 索引
  当前只使用低 5 bit。architectural GHR 随精确提交更新；speculative GHR 随预测请求
  前进，使后续尚未提交的分支也能利用最新控制流上下文。flush 时 speculative GHR
  恢复到 architectural GHR，并合并同拍提交分支的真实结果。
- **RAS（Return Address Stack）**保存嵌套函数调用的返回地址。预测 taken 的 call 将
  `callPc + 4` 压入 speculative RAS，预测 return 时弹栈并用栈顶覆盖 BTB target；提交
  同步维护 architectural RAS，flush 时用它恢复 speculative RAS。两份栈深均为 8，
  当前满栈 push 会被忽略，深于 8 层的调用是需要单独统计的准确率风险。

前端使用这些信息的顺序如下：

```text
nextFetchPc
  -> BTB: branch hit/type/target
  -> PHT + speculative GHR: conditional taken?
  -> RAS: return target override
  -> 选择 group 中最早的 predicted-taken lane
  -> 发起 translation/L1I，并投机更新 GHR/RAS
  -> L1I response predecode 检查真实 opcode/type/direct target
  -> execute 得到最终 direction/indirect target
  -> commit 精确训练 BTB/PHT/architectural GHR/RAS
```

当前还有三项值得用计数器验证的 B02 子问题：GHR 的 8 bit 中只有 5 bit 进入索引；
一个 group 内若出现多个预测 not-taken 条件分支，请求侧只向 GHR 移入一个结果；三宽
提交同拍出现多个 branch 时只训练其中一个。它们都可能造成别名或训练丢失，但在
branch 分类统计、MPKI 和同拍事件频率出现前，不推定其中任何一项是主导瓶颈。

### 2026-08-04：PHT/GHR 索引利用率

当前索引属于 5-bit history + 5-bit group-PC 的 `gselect`：每个 static branch 的 bank
与 PC 部分固定，只能随 32 种低五位 GHR 访问最多 32 个 counter。每 bank 的 1024 项
理论上都可达，但 PC `[8:4]` 每 512 bytes 重复，较大 Linux text 中不同 static branch
可能在相同 history 下互相污染。8-bit GHR 的高三位不影响任何 lookup，因此当前有效
global-history length 为 5。

B02 可按风险从低到高比较以下实验，先用同一份 committed-branch trace 离线 replay：

1. 保持 1024 项和 10-bit metadata，使用 folded-history 或 10-bit gshare hash，把全部
   8 个 GHR bit 与更多 PC bit 混入索引；BRAM 容量和后端 payload 宽度不变。
2. 让一个 fetch group 内的多个条件分支按程序顺序更新 speculative GHR，并让同拍
   commit 的多个 branch 都训练 PHT/architectural GHR；这修复 history 丢步和训练丢失。
3. 扩为每 bank 8192 x 2-bit，并使用 `{GHR[7:0], PC[8:4]}`。现有层次综合显示整个
   predictor 使用 4 RAMB36 + 4 RAMB18；从表形状推断，1024 x 2 PHT 很可能未吃满每
   bank 已占的 RAMB18，8192 x 2 可能保持 BRAM block 数不变，必须由 Vivado 重新确认。
   该方案会把 PHT index 从 10 扩到 13 bit；现有 16-bit metadata 已由 10-bit index、
   2-bit old state、valid 和 3-bit type 用满，扩宽会贯穿 frontend、uop 和 ROB payload。
4. 检查 direction-trained 启用条件。当前所有提交的 conditional branch 都更新 PHT，
   但 BTB 的 direction-trained 只在实际 taken 的精确更新中置位；此前即使已有
   not-taken PHT training，lookup 仍使用 BTFNT。可比较“任意精确 conditional commit
   后启用 PHT”，并保证 BTB target/type 同步有效。
5. 统计 prediction-time old PHT state 的陈旧程度。更新使用随 uop 保存的 old state，
   同一 index 有多个在途实例或 alias branch 时可能覆盖较新的训练；若频繁发生，再考虑
   commit-side shadow counter 或 write-forwarding，避免先增加表容量却丢失有效更新。

评价指标不使用单纯的“访问过多少表项”。应统计 distinct index coverage、同一
`bank/index` 对应的 static-PC 数量、相反方向更新冲突、各 history length 的离线 MPKI、
冷启动和饱和计数器分布，以及 RTL 后的 predictor lookup timing、完整 SoC WNS 和
per-benchmark cycle count。表项覆盖率低但方向准确时无需追求更高覆盖率。

### 2026-08-04：Decode 与 Rename

这一段流水把“32 位指令编码”转换成“可在乱序机器中独立跟踪的操作”。当前数据流为：

```text
Instruction Buffer（每个 fetch group 含四个 slot）
  -> 三个并行 La32rDecoder
  -> 一项、三路宽的 Decode/Rename elastic buffer
  -> RAT 查询 + FreeList/ROB/LSQ/dispatch 资源预分配
  -> RenamedMicroOp
```

#### Decode 具体产生什么

`WideDecode` 每拍最多解码前三条，fetch group 的第 4 条继续留在前端 IBUF，下一拍
再成为最老的待解码指令。每个 LA32R 指令当前产生一个 `DecodedMicroOp`，没有把复杂
指令拆成多个 micro-op。uop 至少携带四类信息：

1. 身份与恢复信息：PC、原始 instruction、fetch slot、预测方向/目标和 predictor
   metadata，以及取指/非法指令/权限异常。
2. 数据流信息：架构目标 `rd`、两个可能的架构源 `rs1/rs2`、立即数、源是否真实使用、
   源最终选择寄存器/PC/立即数/常数 4，以及是否写 GPR。
3. 执行路由信息：ALU、branch、multiply、divide、CSR、load/store、serial、barrier 等
   FU 类型及具体 operation。
4. 精确语义信息：load/store 大小与符号扩展、LL/SC、CSR、TLB、CACOP、DBAR/IBAR、
   ERTN 等标志。这些信息随 uop 进入 ROB，直到顺序提交时决定架构效果。

LoongArch 编码使源/目标字段不能只按固定位置照搬：

- `add.w rd, rj, rk` 使用 `rj`、`rk`，并写 `rd`；
- `addi.w rd, rj, si12` 只读取 `rj`，第二个执行操作数来自立即数；编码中的 `rk` 位
  此时属于立即数字段；
- `st.w rd, rj, si12` 用 `rj` 形成地址，并把 `rd` 的值作为 store data，因此 decoder
  把第二架构源选择成 `rd`；条件分支和 `SC.W` 也有类似的字段重解释；
- `BL` 的执行输入是 PC 和常数 4，架构目标固定为 `r1`；`RDCNTID.W` 的目标来自
  `rj` 字段。

中断只注入 lane 0。这样一拍三条中只在最老边界建立精确异常，避免 lane 1/2 先于
更老指令进入中断处理。fetch exception、非法指令和权限异常也先编码进 uop，不在
decode 当场改变架构状态。

`6bbca9b...` 对 decoder 的关键变化是把有效 CACOP 从 `loadStore/P3` 改路由为
`barrier/P0`。地址翻译和 cache maintenance 现在由 P0 的 ROB-head barrier 状态机统一
实施，不再把 CACOP 当普通 LSU request。CPUCFG 的 decode 类别没有变，变化在 execute
读取参数化 `CpuConfigEncoding`。这两项都没有消费 `source1Used/source2Used`，所以 R01 的
静态假依赖结论不变。

#### Decode/Rename buffer 的作用

边界上是一项、三路宽的弹性寄存器。只要旧项已被全部接受，它可以在同一拍送走旧
三条并装入新三条，稳定吞吐仍为 3 uop/cycle；它切断 IBUF 到 RAT/资源分配的长组合
路径。当前握手是整组式：现存有效 lane 中任何一条未被下游接受，整项保持，前端三路
`inputReady` 同时拉低。flush 会清除整项。

旧 standalone 层次报告中该 buffer 约为 2,771 LUT、727 FF。成本较大，因为每个
`DecodedMicroOp` 很宽，三份 payload 都用寄存器保存；若未来该路径或拥塞成为证据充分
的热点，可研究 hot/cold 字段拆分。在当前固定 c398 报告中，它并非已知最差路径。

#### Rename 要解决的三个名字相关

架构程序只有 `r0..r31`，乱序核内部使用 `p0..p63`。RAT（Register Alias Table）回答
“某个架构寄存器的最新值将位于哪个物理寄存器”。`p0` 固定为零；PRF 保存实际数值，
rename 阶段只传物理编号和 ready 状态，数值到 issue/operand-read 阶段才读取。

- RAW（Read After Write）是真数据依赖，必须保留。消费者的 `psrc` 指向最近生产者的
  `pdst`，等该物理寄存器完成后才能发射。
- WAR（Write After Read）是名字冲突。较老读取者保留旧 `psrc`，较年轻写者获得全新
  `pdst`，两者不再争用同一存储位置。
- WAW（Write After Write）也是名字冲突。两次写获得不同 `pdst`，可以乱序执行；ROB
  顺序提交决定哪一个最终成为架构映射。

当前 RAT 保存两份 32 项映射：`speculative` 随 rename 前进，`architectural` 随 commit
前进。另有 64 项 ready table；分配新 `pdst` 时清零，writeback 时置一。全局恢复时，
speculative map 复制 architectural map，丢弃错误路径映射。FreeList 以环形队列保存
可分配的 `p1..p63`，并保存 speculative/architectural 两个 head 快照，使全 flush 能
在 O(1) 时间回到已提交边界。

#### 三路同拍 rename 例子

假定拍前映射为 `r1->p8, r2->p5, r3->p12, r4->p14, r5->p9`，FreeList 接下来给出
`p20,p21,p22`：

| lane | 指令 | rename 结果 | 同拍关系 |
| --- | --- | --- | --- |
| 0 | `add.w r3,r1,r2` | `psrc={p8,p5}, pdst=p20, oldPdst=p12` | 产生新的 `r3` |
| 1 | `sub.w r4,r3,r5` | `psrc={p20,p9}, pdst=p21, oldPdst=p14` | RAW 指向 lane 0，`p20` 初始 not-ready |
| 2 | `addi.w r3,r4,1` | 真源为 `p21`，`pdst=p22, oldPdst=p20` | RAW 指向 lane 1；WAW 链接 lane 0 的映射 |

RAT 对 lane 0、1、2 按程序顺序做组合 bypass。lane 1 看到 lane 0 刚产生的 `p20`；
lane 2 看到 lane 1 刚产生的 `p21`，同时知道它覆盖的上一版 `r3` 是 `p20`，而不是拍前
的 `p12`。拍后 speculative RAT 为 `r3->p22, r4->p21`。

`oldPdst` 决定何时回收旧物理寄存器。lane 0 提交后可以释放 `p12`；lane 2 提交后才可
释放 `p20`。在 lane 2 提交之前，`p20` 仍可能被更老的消费者读取，提前回收会让新指令
覆盖尚在使用的值。这个例子也说明为什么“生产者”是产生某个物理目标值的动态指令，
“消费者”是把该物理目标编号作为源的动态指令。

#### “分配近似原子化”的准确含义

每组三条在进入乱序后端时，需要同步取得或确认以下对象：

- 每条有效 uop 的 ROB entry；
- 每条有效且写非零 GPR 的新 physical destination；
- load/store 对应的 LQ/STQ index；
- dispatch queue 容量；
- 当前 recovery epoch 和 memory epoch。

后端把 ROB、FreeList、LSQ allocator 和 dispatch queue 的 ready 汇总为一个
`resourcesReady`。只有整组需要的资源都满足时才产生 `acceptAll`，并让 RAT 更新、
FreeList head 前进、ROB/LSQ 分配和 dispatch enqueue 在同一时钟边沿发生；否则全部
保持。这就是这里的“近似原子化”：硬件不是数据库事务，但外部可观察到的多个队列
状态不会只成功一半。代价是 head-of-line blocking，例如 lane 2 是 store 而 STQ 已满，
lane 0/1 即使只需 ALU 资源也会一起停住。

64 个物理寄存器与 32-entry ROB 形成一个紧凑边界：除 `p0` 外有 63 个编号，稳定态最多
需要 31 个非零架构映射，再加最多 32 个在途 GPR 新版本。仅扩大 PRF/FreeList 未必有
收益；应先统计 FreeList 导致的 rename stall，若 ROB 本身仍只有 32 项，扩大空间很可能
长期闲置。

#### R01：未使用源造成的假依赖

静态检查发现 decoder 正确生成了 `source1Used/source2Used`，但这两个标志目前没有在
后端调度路径中使用。RAT 仍查询原始 `rs1/rs2`，dispatch 再按两个 `psrc` 的
`physicalReady` 判定；IQ 要求 source1 ready，除 store data 由 SDQ 解耦外也要求
source2 ready。于是 `addi.w` 的立即数字段、load
的 offset 字段、`BL` 的 PC/常数输入，都可能偶然编码成某个尚未完成的 GPR 编号，形成
不真实的 RAW 等待。执行级最终会选择立即数或 PC，所以等待到的寄存器值根本不会参与
计算。

候选 R01 是在 rename 时把未使用源规范化为 `p0` 并设为 ready。它还有机会减少
wakeup 比较器的无效匹配和翻转。实现前必须逐类审计 decoder 的 used 定义，尤其是
store data、条件分支第二源、CSR write/xchg、SC、CACOP 和 InvTLB；一个漏标就可能让
指令过早发射并读到零值。

先做以下测量，再决定是否进入 RTL 实验：

1. 每拍记录 `sourceUsed=false && physicalReady(psrc)=false` 的 uop，并按 opcode 分类；
2. 区分它只是存在假依赖，还是实际成为该 uop 最后的 issue 阻塞原因；
3. 比较规范化前后的 IQ occupancy、ready-to-issue 等待、perf20 分项 cycle count；
4. 运行立即数/PC/store/branch/CSR 等定向测试和多 seed DiffTest，再比较 LUT、完整 SoC
   WNS，避免周期收益换来调度路径退化。

#### FUS01：LA32R 指令融合方向评估

这里的“指令融合”特指 **macro-op fusion**：相邻的两条 LA32R 架构指令在 Decode 之后
共享一个后端执行项，或者进一步压成一个复合 ROB entry。程序中的两条 32-bit 指令、
两个 PC 和两次架构退休身份仍然存在。当前设计通常是一条 LA32R 指令对应一个 uop，
所以本项目现阶段没有“先把一条复杂指令拆成多个 micro-op，再做 micro-op fusion”的问题。

这一区分很重要。LA32R 使用固定 32-bit 编码，macro-op fusion 不会减少 I-cache 读取的
字节数、ITLB 翻译次数或进入 instruction buffer 的架构指令数。它可能减少的是 rename
之后的调度工作、IQ 占用、执行端口占用和依赖链等待；只有采用更激进的复合 ROB entry，
才会同时提高 ROB 以“架构指令数”衡量的有效容量。因此，若当前程序主要受前端供给
限制，后端融合即使命中很多，也未必能降低 cycle count。

当前 fetch/decode/rename/commit 的架构宽度都是 3。融合不会把理论架构 IPC 上限从 3
提高到更大值，也不会直接解释或修复“实测 IPC 低于 1”；它只能在 IQ、执行端口、依赖链
或 ROB 容量确实构成 exposed bottleneck 时，帮助实际 IPC 更接近既有上限。

##### 手册给出的语义边界

下表按《龙架构 32 位精简版参考手册》V1.04 的指令语义筛选候选。这里先讨论指令本身
不会产生同步异常的整数对；取指异常、非法指令标记或错误路径上的指令仍然不能参加融合。

| 手册事实 | 对融合的含义 |
| --- | --- |
| `LU12I.W rd, si20` 生成 `{si20, 12'b0}`；手册明确说明它可与 `ORI` 配合装载大于 12 bit 的立即数 | `LU12I.W rd,hi20; ORI rd,rd,lo12` 是 ISA 文档直接支持的高可信静态模式，最终值可一次算成 `{hi20,lo12}` |
| `ADDI.W` 不报告算术溢出，`ANDI/ORI/XORI` 和 `SLTI/SLTUI` 也只是整数运算/比较 | 这些立即数操作适合作为无同步例外的第一个操作，尤其适合把结果直接送给后一条条件分支 |
| LA32R 已提供 `BEQ/BNE/BLT/BGE/BLTU/BGEU`，后四条可直接比较两个 GPR | 编译器通常无需生成 `SLT reg,reg; BNE reg,r0`；通用“比较 + 分支”融合的空间比某些 ISA 小。LA32R 没有立即数比较分支和按位测试分支，因此立即数测试对仍有价值 |
| `PCADDU12I` 产生 `PC + (si20 << 12)`；`JIRL` 用自己的 PC 生成 link 值，并以 `GR[rj] + (offs16 << 2)` 为目标 | PC-relative 地址对可能常见，但复合项必须同时保留第一、第二条 PC；含 `JIRL` 时还要以第二条 PC 计算 link，并保留其预测/恢复身份 |
| load/store 包含地址计算、地址翻译和访存；非对齐等情况可产生同步异常 | `load + consumer` 不能只按普通 ALU 对处理。第一条 fault 时第二条不得退休；第一条成功而第二条 fault 时又需要可见的中间边界 |
| 异常硬件把触发指令的 PC 保存到 `ERA`；中断由硬件在指令流中选择一条指令作为处理边界 | 复合项必须能准确报告第一或第二条 PC。对两个无同步例外操作做原子退休，至多把异步中断推迟一条指令，但仍需结合中断时延、debug 和 DiffTest 合同证明，不能仅凭“指令逻辑等价”推断正确 |

##### 最有希望的模式

**第一类：无异常整数运算/测试后接与零比较的条件分支。** 典型例子是：

```asm
addi.w  r4, r4, -1
bne     r4, r0, loop

andi    r12, r11, 1
bne     r12, r0, odd

xori    r12, r11, 37
beq     r12, r0, equal

slti    r12, r11, 16
bne     r12, r0, less_than_16
```

融合后的执行项读取第一条的源寄存器，一次完成 ALU/比较结果、第一条的 GPR 写回和第二条
的 branch condition。它消除了“第一条完成 -> 广播/wakeup -> 第二条 select/issue”的真
依赖边，特别适合 decrement-and-branch、bit-test 和立即数范围判断。第一条仍是 GPR
生产者，分支仍以第二条 PC、offset 和 predictor metadata 作为控制流身份。

概念上还可覆盖 `ADD.W/SUB.W/AND/OR/XOR/shift -> BEQ/BNE result,r0`。其中立即数运算
优先统计，因为 LA32R 没有立即数比较分支或 bit-test branch；`SUB.W` 若结果还要写回，
即使 ISA 已有直接寄存器比较分支，融合也可能同时保留差值和零判断。单纯
`SLT reg,reg -> branch` 往往能由 `BLT/BGE/BLTU/BGEU` 直接表达，动态频率预计较低，
应让 trace 验证而不预设它常见。

这是当前最干净的首个候选，原因有三点：两条都没有数据访问和同步异常；分支不写 GPR，
所以整个 pair 只有一个最终 physical destination；P2 已同时具备整数 ALU 和 branch
能力。限制也很明确：当前恢复主要在 branch 到达提交边界后生效，提前一拍或数拍得到
分支结果，只有在它推进了 ROB head 或减少了暴露的 issue 等待时才会变成 cycle 收益。

FPGA 时序风险集中在 P2：若 fused item 在一拍内先做 ADD/logic/compare，再对结果做 branch
condition，分支判定的数据路径会比普通寄存器比较更深。branch target 可与条件计算并行，
但 condition-to-completion 路径仍可能降低 Fmax。把 fused op 内部分成两拍可以保留“不进
第二次 IQ/wakeup”的容量收益，却未必缩短分支完成延迟。因此原型必须同时比较单拍融合、
内部两拍融合和原设计，不能把少一个 uop 直接视为更快。静态绑定到 P2 还会改变 P0/P1/P2
压力分布，应把 P2 busy/HOL 和端口利用率放进 replay。

**第二类：`LU12I.W + ORI` 常量构造。** 例如：

```asm
lu12i.w r7, 0x12345
ori     r7, r7, 0x678
```

最终值可以直接形成 `0x12345678`，不需要真的让第二条从 PRF 读取第一条结果。这个模式
语义清晰、容易静态识别，而且手册明确给出配合关系。不过它连续两次写同一个架构寄存器：
正常 rename 会先产生中间 `pdst1`，再产生最终 `pdst2`。若只共享执行逻辑而保留两个 ROB
entry，可以保留两个 destination 并产生两个结果；若压成一个 ROB entry 和一个最终
physical register，则 commit/debug/DiffTest 仍需依次表示第一条的高 20-bit 结果和第二条
的最终结果。由此看，它的组合计算比 branch pair 更简单，rename/退休合同反而更复杂。

**第三类：PC-relative 地址和长控制转移。** `PCADDU12I + ADDI.W/ORI` 可以合成长地址，
`PCADDU12I + JIRL` 可能形成长跳转。它们值得在 Linux/benchmark 的动态 trace 中计数，
但暂不列为首个原型：两条指令的 PC 语义、`JIRL` link destination、分支预测训练和恢复
metadata 都会扩大复合 payload。只有编译器确实大量生成这些序列时，复杂度才合理。

**暂缓的模式**包括 `shift + add`、`MUL + ADD`、`load + ALU/branch` 和地址生成融合。
前两者可能涉及两个独立 destination、DSP pipeline 和更长组合路径；访存类还要处理 TLB/
cache 异常、回放、miss、转发及第一条成功而第二条未退休的状态。首轮应完全排除 store、
LL/SC、`DBAR/IBAR`、CSR、TLB、CACOP、syscall、break 以及其他 privileged/serializing
指令。move elimination 与这里相关，但它更适合当作独立的 rename 优化，不必塞进融合机制。

##### 研究层级与两种硬件表示

“命中一个 pair”并不自动决定 ROB 表示。可以先分成以下两个可实现层级：

| 层级 | 表示方式 | 能减少什么 | 主要代价 |
| --- | --- | --- | --- |
| F0：trace/replay | RTL 不融合，只记录正确路径上的相邻模式，离线模拟 disjoint pair 选择和理想完成时间 | 得到覆盖率与 cycle 上界 | 不能给出 LUT/Fmax，但可以低成本淘汰稀有或被隐藏的模式 |
| F1：两个 ROB entry、一个执行项 | rename/ROB 仍为两条架构指令分配两个连续 entry，dispatch/IQ/FU 只跟踪一个 fused item，完成时协调标记两项 | 减少 IQ/issue/FU 工作和依赖等待；保留现有两条顺序提交身份 | ROB 容量不增加；需要区分 architectural allocation count 与 scheduling-item count，并处理两个 completion |
| F2：一个 compound ROB entry、`archCount=2` | pair 只占一个 ROB entry，entry 携带两个 PC/instruction 及必要的中间/最终结果和 control metadata | 进一步降低 ROB 占用；32-entry ROB 可容纳更多架构指令 | commit prefix 变为可变条数；一项可能消耗两个退休 slot；恢复、FreeList、debug/DiffTest 和异常边界都要扩展 |

F1 更适合验证“少一次依赖 issue”是否有真实周期收益，特别是 ALU/test + branch。它没有
解决 ROB 满，也不应被计为 ROB 容量优化。F2 才可能与“ROB + PRF 协同扩容”形成替代或
互补关系：当融合覆盖率足够高时，每个 ROB entry 平均承载超过一条架构指令；代价是把
当前固定一项一退休身份的简单结构改成变长结构。

当前 3-wide commit 若支持 F2，不能把一个 compound entry 当作“退休一条”。例如一个
普通 entry 加一个 `archCount=2` entry 已经占满三个架构退休 slot；下一项必须留到下一拍。
commit adapter 仍要输出两条按程序顺序排列的 PC/instruction/写回事件，retired instruction
counter 也必须增加 2。ROB pointer 可以按 entry 前进，但 architectural commit count、
debug lanes 和 DiffTest lanes 需要按 `archCount` 做 prefix packing。

##### 首个原型应遵守的合同

若 trace/replay 支持进入 RTL 实验，第一版可有意收紧为：

1. 只接受同一动态顺序路径上、相邻且不重叠的两条指令，第一条不能是控制转移；
2. 两条都没有 decode/fetch exception，不访问内存和系统状态，也不具备 serializing 属性；
3. 整个 pair 最多保留一个最终 GPR physical destination，首选
   `ALU/test -> BEQ/BNE r0`；
4. branch target、taken、mispredict 和 predictor update 全部归属于第二条 PC；
5. flush/recovery 把 pair 视为不可拆的同 epoch 工作，但退休侧仍产生两个架构事件；
6. pair 之前或之后可以接受异步中断，首版不在 pair 内部插入中断边界；该选择需要中断
   latency、ERA、DiffTest 和 debug 定向证明；
7. 检测放在 IBUF 后的已寄存 Decode/Rename 边界，避免把 pair compare、lane compaction
   和 payload mux 加回 L1I response 的前端热路径；首版可以不支持 lane 2 与下一组 lane 0
   的跨组融合。

3-wide decode 中还需要定义不重叠选择。例如 `[A,B,C]` 同时命中 `A+B` 和 `B+C` 时，
可以固定最老优先只融合 `A+B`，让 `C` 保持普通项。跨组融合若要求暂存 lane 2，会增加
状态、flush 边界和前端 backpressure，只有统计证明它损失大量覆盖率后才值得加入。

##### 收益上界与测量方法

设正确路径退休了 `I` 条架构指令，贪心选择得到 `P` 个互不重叠的 eligible pair。理想情况：

```text
融合前后端执行项 = I
融合后后端执行项 = I - P
执行项减少比例   = P / I
被 pair 覆盖的指令比例 = 2P / I
```

这两个比例容易混淆。若 20% 的指令属于 pair，即 `2P/I=20%`，执行项只减少 10%。它也
没有把取指需求从 `I` 降到 `I-P`；前端仍然读取并识别全部 `I` 条指令。F1 的 ROB entry
数同样不变，只有 F2 才能把这部分 ROB 占用从 `2P` 降到 `P`。

动态 trace 至少应记录：

1. 每个精确 opcode/register 模式的 retired adjacent-pair 数量，并按 benchmark 分项；
2. 采用不重叠、最老优先规则后的 eligible pair 数，以及因 group boundary、exception、
   control boundary、`r0`/destination 约束而失去的数量；
3. 第一条完成到第二条 issue/resolve 的实际间隔，以及这段间隔是否延后 branch commit、
   redirect 或整个程序的关键依赖链；
4. pair 出现时 decode/dispatch/IQ/ROB 是否有 exposed stall，相关 occupancy 和 full 周期；
5. branch pair 的 misprediction、错误路径命中和训练事件，避免高命中但主要位于错误路径；
6. replay 后的每项 benchmark cycle 上界，以及 `cycles / Fmax` 综合目标；
7. 原型增加的 comparator、lane compaction、compound payload、completion/commit packing 的
   LUT/FF/route 代价和 matching complete-SoC WNS。

单看静态反汇编中有多少相邻模式还不够：已经被当前 out-of-order 调度完全隐藏的依赖，
融合后不会减少总周期。反过来，出现比例不算高的 loop decrement-and-branch 若处在热点
关键链上，也可能比大量冷路径常量装载更有价值。离线 replay 应给出“理想融合完成时间”
并重新传播到 branch commit/redirect 或依赖链终点，以估算严格 cycle 上界。

##### 阶段性判断

FUS01 的理论优先级定为 **中等、先 trace/replay**。候选内部排序为：

1. `ADDI.W/ANDI/XORI/SLTI[U] -> BEQ/BNE r0`；
2. `LU12I.W -> ORI`；
3. 经动态证据支持后的 `PCADDU12I` 地址/跳转对；
4. 其余整数对；访存和系统指令首轮不做。

它暂时低于 Linux 正确性 gate、已经由测量定位的 M01 类瓶颈和直接限制持续供给率的前端
问题。若后续前端吞吐改善，或者计数器显示 IQ/ROB/依赖链成为主要 exposed loss，融合的
相对价值会上升。一个实用的探索判据是：动态正确路径 pair 覆盖率可观，且 replay 预测
至少某些 benchmark 有约 1% 以上 cycle 改善，再投入 F1 原型；这里的 1% 是工程筛选
启发式，不是架构正确性门槛。任何 RTL 结论仍需用当时最新 CPU commit 的完整 SoC timing
重新建立，不能继承本笔记旧 bitstream 的 WNS 或资源数字。

#### Physical Register 数量评估

当前 `physicalRegs=64`、`robEntries=32`。容量关系可以直接写成：

```text
所需非零 physical register
  <= 已提交的非零架构映射 + ROB 内尚未提交的 GPR writer
  <= 31 + 32
  = 63
```

`p0` 固定为零，所以 `p1..p63` 恰好覆盖最坏情况。更一般地，若 ROB 深度为 `R`，保证
所有 ROB entry 都能写 GPR 的充分配置为 `physicalRegs >= 1 + 31 + R`。因此在当前
32-entry ROB 下，只要账本和回收正确，FreeList 不会因长期容量不足而比 ROB 更早限制
可容纳的动态指令数。单独扩为 128 项不能增加窗口深度，也不能让更多 uop 越过长延迟。

实现中仍可能出现一个短暂边界气泡。ROB commit 后立即降低 occupancy，而 `oldPdst`
经过一拍寄存后才送到 FreeList；FreeList 的 `allocateReady` 只检查当前 `freeCount`，没有
把当拍 `releaseCount` 作为可分配 credit。当 32 个 ROB entry 全为 writer、FreeList 已空
时，第一次 commit 先释放 ROB 槽，下一拍可能出现 `ROB ready && FreeList not ready`，再
下一拍才能使用回收项。扩大 PRF 能用额外余量掩盖该气泡，但更直接的研究方向是统计
它是否出现，再比较 registered-release bypass、小型 reserve 或等价回收优化。

扩容的 FPGA 成本不可忽略。当前 PRF 是 8 个异步读口、5 个写回口的 FF/mux 结构，旧
standalone 层次报告约为 4,352 LUT、2,016 FF；FreeList 约为 1,353 LUT。现配置要求
physical register 数为 2 的幂，因此下一档是 128，地址从 6 bit 变为 7 bit，并扩大每个
读口的选择网络和写回比较网络。它不能直接映射成一块普通双口 BRAM；多读多写映射需要
复制、banking 或其他 PRF 架构，成本和冲突都要单独设计。

如果测量证明 ROB 经常因较老 cache miss/DIV 填满，且窗口后部仍有可执行的独立指令，
则 R02 有价值。把 ROB 扩为 64 项时，理论最低 physical register 数为 96；当前参数和
FreeList 实现会因 2 次幂约束选择 128。该实验还必须同步扩展或重证 memory epoch、ROB
payload、IQ/LSQ 容量和恢复协议，否则 128-entry PRF 只会成为昂贵的空闲存储。

#### R02：ROB 与 PRF 协同扩容详细评估

BRAM 余量为该方向提供了实现空间，但它不是收益证据。旧平台完整 SoC placed report 为
87,266 Slice LUT（65.22%）、52,955 register（19.67%）、68.5/365 BRAM tile
（18.77%）和 8/740 DSP（1.08%）；Slice 本身已经使用 79.47%。因此该器件真正紧张的
仍是 LUT packing、slice 布局和 CPU 域布线，不能把剩余 BRAM 等价成“窗口可以免费
翻倍”。当时 `60fba481 + c398` 虽有 `+0.044 ns` WNS，但 slice occupancy 已升至
81.23%，任何扩容仍必须同时证明周期收益和完整 SoC 时序；44 ps 不能视为可消费的稳定
扩容预算。

R02 的周期收益只来自增加有效 instruction window。32-entry ROB 在三路 rename 下最多
容纳约 10.7 拍的连续供给，64-entry ROB 约为 21.3 拍。更深窗口可能在以下情形发挥作用：

- ROB head 被 L1D/L2/DDR miss 或 DIV 挡住，后面仍有与其独立的 ALU/branch 指令；
- 已完成的 younger uop 离开 IQ 后继续占 ROB，32 项很快填满，阻止前端寻找更远的
  independent work；
- 多个 cache miss 可以由 4 个 MSHR 和唯一 LSU 部分重叠，程序又具有足够 memory-level
  parallelism。

以下情形中，扩 ROB 的收益会很小：前端本身供给不足；dispatch 被某个端口 IQ 的队头
阻塞；8-entry LQ/STQ 或 4 MSHR 已满；大多数 younger 指令真实依赖 head miss；branch
误预测和 serializing 指令频繁。当前 branch mispredict 直到 commit 才全 flush，更深
窗口还会容纳更多错误路径工作，所以 R02 与 B02/B01 的恢复时点存在明显耦合。

##### ROB 32 -> 64 的物理成本

当前 ROB 的约 182-bit cold payload 已按 ROB index 低两位分成 4 个 bank。连续三条
allocate/commit 落在不同 bank，因此每 bank 只需一个写口和一个同步读口。每个 bank
宽度需要 3 个 RAMB36 并联，32-entry 时 bank depth 为 8，总计 12 RAMB36；扩为 64 后
depth 仅变为 16，按 RAMB36 宽度/深度推断大概率仍为 12 个，必须由 Vivado 确认。
这说明 ROB 扩深甚至可能不增加 BRAM，因为现有 block 的深度远未用满。

主要成本来自 `ReorderBufferState`。它以 32 份 FF/逻辑保存 valid、complete、pointer、result、
side-effect data、completion exception 和 branch resolution；5 路 completion 又与每个
entry 做 generation/pointer match。旧 standalone 报告中整个 ROB 已占 28,083 LUT、
5,723 FF 和 12 RAMB36。若结构不变，64-entry 的 entry state 和 `64 x 5` completion
匹配近似线性增长。把旧完整 SoC 的约 87--88k LUT 与 PRF/ROB 粗略线性增量相加，会落在
约 119--121k LUT、即器件 89--90% 的危险区间；考虑当前 81.23% slice occupancy 和
路由主导的关键路径，实际可布通性可能比 LUT 百分比更差。这个估计只用于否决“直接改
两个参数”的方案，不替代候选综合。

因此 64-entry 实验的前置条件是先在 32-entry 上完成 T02：

1. 保留 FF 中真正进入当拍 commit/recovery 判定的 hot bits，例如 valid、complete、
   payload-ready、exception-valid、serializing、generation 和必要的 branch status。
2. 将 result、side-effect data、exception payload、branch target 等宽 cold fields 按
   更新来源和读取时点拆分，研究 banked RAM 或 completion-side storage。
3. 避免让 5 路 completion 直接形成 `ROB entries x writeback lanes` 的宽字段更新网络；
   可以比较局部 one-hot mask、banked completion buffer 或延后一拍的 bank arbitration。
4. 在深度仍为 32 时先证明 cycle count 不变、ROB LUT 明显下降且 100 MHz 完整 SoC
   路径不退化，再把深度作为单一实验变量。

把所有 hot state 直接塞进 BRAM 也不可行：allocation 最多 3 写、completion 最多 5 个
任意 index 写、commit 最多 3 个 invalidation/read，同拍端口需求超过普通双口 BRAM。
状态必须按“当拍多写控制位”和“可流水的宽 payload”拆开。

##### 128-entry PRF 的两个现实方案

| 方案 | 结构 | 优点 | 主要风险 |
| --- | --- | --- | --- |
| P0：扩大现有 FF/mux PRF | 128 x 32 FF，保持 8 个执行读口、1 个 debug 读口和 5 个写回口 | 语义和 latency 最接近当前设计，适合作为第一份 cycle 对照 | 64 项版本已约 4,352 LUT/2,016 FF；读 mux、7-bit tag 比较和布线会扩大，可能损害 Fmax |
| P1：LVT + replicated BRAM | 以 5 个 writeback lane 作为 5 个 data bank；每个读口保留各 bank 的副本，`lastWriter[preg]` 选择最新 bank | 使用 BRAM 余量并避免 128:1 FF read mux；天然接收 5 个不同地址的同拍写回 | 结构复杂，需要同步读对齐、LVT 多写、same-cycle bypass、重复 pdst 优先级和 BRAM floorplan 验证 |

P1 的量级可以先算出来。128 x 32 数据可放入一个 RAMB18；5 个 write bank 乘以 9 个
并发读口约为 45 个 RAMB18，即 22.5 BRAM tile，再加一个 128 x 3-bit 左右的 LVT 和
每读口 5:1 数据 mux。若 commit 为移除 ROB result state 再增加 3 个 PRF 读口，则约为
`5 x 12 = 60` 个 RAMB18，即 30 tile。两个数都在器件容量内，但只是端口构造的一阶
估算；Vivado 的实际 RAMB18/RAMB36 packing、复制和布线必须单独确认。

当前普通执行端口已有 IQ-select -> issue-address register -> operand register 两级边界。
同步 BRAM PRF 可以让 IQ 选中的 `psrc` 在第一个边沿发起读，同时寄存 uop 和 LVT bank
tag，在下一边沿捕获 BRAM 数据，从而理论上不增加现有 issue-to-operand 周期。代价是
重新安排 PRF address 边界，并验证 early wakeup、MUL forward、5 路 write-to-read bypass
和 LSU/SDQ 共享读口。P1 值得作为 P0 遇到 LUT或时序问题后的第二方案，不应成为 R02
第一步。

按 physical bank 限制每拍读取、依靠 IQ select 避免 bank conflict 的低复制方案，会把
PRF 端口冲突加入调度条件；multi-pump 又引入 2x 时钟、相位和 CDC/时序证明。二者可能
节省 BRAM 副本，但会直接改变 IPC 或验证边界，暂不作为首轮候选。

##### 其他窗口结构是否必须同步翻倍

- IQ 不必先翻倍。completed-but-uncommitted uop 已离开 IQ，64 ROB 本身就可能容纳更多
  已完成结果；当前四个 8-entry IQ 合计已占约 6,097 LUT，直接改为 16 会扩大 5 路
  wakeup compare、oldest-ready select 和 compaction 网络，时序风险很高。
- Dispatch queue 先保持 8。router 只接受按程序顺序的 prefix；目标 IQ 满造成的端口
  head-of-line blocking 不能靠更深 ROB 根治，盲目扩大 dispatch queue 只会多缓存几条。
- LQ/STQ/MSHR 必须由 occupancy 决定。若 ROB-full 周期大多同时 LQ/STQ=8 或 MSHR=4，
  64 ROB 无法增加 memory-level parallelism，应先或同时研究 16-entry LQ/STQ、waiter
  数量及 LSU/cache 调度成本。
- ROB pointer 会由 6 bit 增为 7 bit，并贯穿 IQ、LSQ、cache/MSHR、completion 和恢复
  payload。当前 `robEntries <= 32` 还是 memory-epoch proof 的显式合同，必须先给出
  64-entry 年龄、pointer wrap、epoch half-range、stale response 和 recovery 的新证明。

##### 分阶段决策门

1. **只测量**：记录 ROB occupancy 直方图、ROB-only rename stall、head 阻塞原因/时长、
   head 未完成时已完成 younger 数、四个 IQ/LQ/STQ/MSHR occupancy、在途 GPR writer
   数、FreeList-only stall、误预测 squash 数。
2. **离线收益上界**：用 committed/dynamic trace replay 32/48/64 window，保持执行端口、
   cache latency 和分支结果相同；逐 benchmark 估计多出来的 32 项真正能减少多少周期。
3. **T02@32**：先缩小 ROB hot-state LUT/route cost，功能、cycle、完整 SoC timing 均不
   退化后才允许扩深。
4. **R02-P0**：64 ROB + 128 FF/mux PRF，IQ/dispatch/LQ/STQ 先保持原值，用计数器识别
   新瓶颈；这一步是最清楚的架构收益对照。
5. **按证据扩外围**：只扩大实际饱和的 LQ/STQ 或特定 port IQ；P0 若因 PRF 路径失败，
   再实现 P1 的 LVT/BRAM PRF。
6. **最终比较**：必须满足完整 SoC 100 MHz setup/hold closure 和 DRC，再比较
   `speedup = (cycles_32 / cycles_64) * (f_64 / f_32)`。例如 Fmax 下降 5% 时，cycle
   至少下降 5.3% 才刚好不亏；考虑设计风险，R02 应显示明显高于该平衡点的分项收益。

当前评估：R02 是一个**有理论价值但证据和结构重构前置的中期方向**。BRAM 余量使
banked ROB payload 和 LVT/replicated-PRF 可行，现有 28k-LUT ROB、commit-time branch
recovery、8-entry memory queues 和负 WNS 又使“直接 64/128”风险过高。优先顺序应为
M01 测量 -> T02@32 -> 64/128 P0 对照 -> 按瓶颈扩外围或切换 P1。

本阶段结论：三宽 rename 的基本 RAW/WAR/WAW 处理完整，整组分配也便于精确恢复；目前
没有证据支持直接扩到四宽或单独扩大 PRF。R01 是这里最值得先验证的小范围周期优化；
FreeList-only 气泡和整组资源阻塞原因应纳入 M01，R02 则等待窗口有效性测量。

### 2026-08-04：Allocation、Dispatch 与乱序窗口

这一级最容易因术语混用而失去整体感。一个动态 uop 从 rename 到 commit 会依次经历：

| 状态 | 已经拥有的资源 | 仍在等待什么 |
| --- | --- | --- |
| Allocated | ROB entry；需要时还有 `pdst`、LQ/STQ index | 进入 dispatch queue，随后找到目标执行端口的 IQ 空位 |
| Dispatched | 已进入一个端口专属 IQ；store 同时进入 SDQ | 源操作数 ready、执行端口和下游 pipeline ready |
| Issued | 从 IQ 移出，uop/PRF 操作数进入 operand pipeline | FU、LSU/cache 或其他可变延迟操作完成 |
| Completed | 结果写 PRF、ROB entry 标记 complete | 所有 older uop 顺序 commit |

“在 ROB 中”不代表“已经在 IQ 中”，“离开 IQ”也不代表“已经 commit”。乱序窗口的
有效容量由这些结构共同决定，而不是只看 ROB entry 数量。

#### Rename allocation 到底预留什么

当前每组三路的 `resourcesReady` 同时检查 dispatch queue、ROB、FreeList 和 LSQ
allocator。不同指令实际消耗的对象如下：

| 指令类别 | ROB | `pdst` | LQ/STQ | Dispatch entry | 额外状态 |
| --- | --- | --- | --- | --- | --- |
| 普通 GPR ALU/MUL/DIV | 1 | 写非零 `rd` 时 1 | 0 | 1 | recovery epoch |
| 条件 branch / `B` | 1 | 0 | 0 | 1 | predictor metadata |
| `BL`/写 GPR 的 branch | 1 | 1 | 0 | 1 | predictor metadata |
| load / LL | 1 | 1 | LQ 1 | 1 | memory epoch |
| 普通 store | 1 | 0 | STQ 1 | 1 | memory epoch；SDQ 稍后分配 |
| SC | 1 | 1 | STQ 1 | 1 | reservation/result 与 memory epoch |
| CSR/serial/barrier | 1 | 视指令而定 | 0 | 1 | 精确状态；barrier 推进 memory epoch |

ROB 为每条有效 uop 分配连续 pointer；FreeList 只按有效 GPR writer 的 prefix 分配物理
编号；LSQ allocator 分别统计这一组三条中的 load/store 数量并给出循环 index。barrier
之前和之后的 uop 获得不同 memory epoch，使 LSU 能阻止较新 epoch 越过尚未提交的
DBAR/IBAR/CACOP。

只有所有请求都能满足时才更新 RAT、ROB、FreeList、LSQ 和 dispatch queue。这里仍是
全组原子接受：若 lane 2 是 load 且 LQ 已满，lane 0/1 的 ALU 也不会单独前进。A01 的
目标是允许最老 prefix 被接受，但它需要 Decode/Rename buffer 保存并压缩未接受尾项，
还要重新证明三路同拍 RAW/WAW 和所有资源计数，复杂度明显高于普通 ready 优化。

#### Dispatch queue 与三项 window

8-entry `DispatchQueue` 将 rename allocation 与端口 IQ 的短期背压解耦。它是顺序
循环队列，可以每拍写入最多三条并送出最多三条。其后还有 3-entry
`DispatchWindow`：

```text
8-entry circular dispatch queue
  -> 3-entry compact registered window
  -> capability router
  -> four port-local IQs
```

window 的用途主要是时序。直接从循环队列的动态 head mux 穿过 router 再写 IQ，曾有
236 条 failing path 从 dispatch queue 起始，worst path 中 routing 占约 80.8%。window
把 router 输入变成直接寄存器输出，并可在同拍消耗最老 prefix、压缩 survivor、从 queue
补入新条目。它与 rename 边界不同：dispatch window 可以只送走 lane 0，或 lane 0/1；
lane 2 不能越过被阻塞的 lane 1。

`7d35545...` 又从 dispatch queue/window/SDQ 的本地 `ready/valid` 表达式中去掉重复的
`!flush` 组合门控，由拥有这些模块的顺序状态更新与上层 flush 优先级负责取消事务。这个
改动缩短 flush 到 router/credit 的控制锥，没有改变队列容量、prefix 规则或 flush 后状态；
后续修改这些 ready 链时应继续在模块边界证明“同拍输出虽可组合为 valid，flush 更新不会
产生实际 enqueue/dequeue side effect”。

uop 在 dispatch queue/window 中可能停留多拍，所以 rename 时的 ready bit 会变陈旧。
当前设计在 router 前重新读取 64-bit physical-ready table，并比较同拍 5 路 completion
wakeup，之后才把 `source1Ready/source2Ready` 写入 IQ。这保证“等待 dispatch 期间已经
完成的生产者”不会丢失唤醒。

旧 standalone 层次参考中 dispatch queue 为 1,820 LUT/1,920 FF，window 为 2,956
LUT/716 FF。window 的 LUT 成本来自三份宽 payload 的显式 survivor/append mux；它用
资源换掉了更差的跨模块组合路径。

#### Router：在 dispatch 时绑定执行端口

当前四个端口的 IQ 独立：

| 端口 | 可接受 FU | 每拍最多 dispatch/issue |
| --- | --- | ---: |
| P0 | ALU、CSR、serial/barrier | 1 |
| P1 | ALU、DIV | 1 |
| P2 | ALU、branch、MUL | 1 |
| P3 | load/store | 1 |

router 按 lane 0 -> 1 -> 2 的程序顺序工作，每个端口同拍只用一次。ALU 可以绑定
P0/P1/P2，其他操作通常只有一个合法端口。dispatch 宽度是 3，所以一拍最多向三个 IQ
各送一条；issue 宽度为 4，后续可以在四个 IQ 都有存量时一拍发射四条。这种 3-dispatch/
4-issue 非对称设计用队列积累 burst，不要求前端每拍产生四条。

当前端口选择是贪心的最低编号优先，可能错过一个可行的更长 prefix。例如三个 IQ 都
ready 时：

```text
lane 0: DIV   -> 只能 P1
lane 1: ALU   -> P0/P1/P2
lane 2: CSR   -> 只能 P0

当前：DIV->P1, ALU->P0, CSR 被挡住      （dispatch 2 条）
可行：DIV->P1, ALU->P2, CSR->P0          （dispatch 3 条）
```

D02 可以在三路四端口的小规模空间中计算 maximum-prefix matching，并用 IQ occupancy
或静态稀缺度决定 flexible ALU 的端口。它的实际频率可能很低，因为 CSR/DIV/MUL 不如
普通 ALU/branch 常见；同时更深的 matching 逻辑可能重新污染已经切断的 router 路径，
所以必须先统计 greedy prefix 小于最大可行 prefix 的事件。

#### IQ 如何形成真正的乱序窗口

每个端口有一个 8-entry compact age-ordered IQ。dispatch 时 uop 已绑定端口，之后只在
该 IQ 内等待。每个 entry 保存两个 `psrc` 和 ready bit；5 路 wakeup tag 与 8 个 entry
的两个源比较，形成 ready map。select 从 ready map 选择该端口最老的 ready uop；它可
以越过同一 IQ 中更老但源未就绪的 uop。serial/barrier 还要满足 `robPointer == ROB head`
才可 issue，保证精确系统状态。

issue 一个中间 entry 后，所有 younger payload 向前 compact。这样年龄顺序天然就是
数组顺序，省掉“年龄表再查物理 slot”的第二级选择；代价是宽 payload shift 和 5 路
wakeup 广播。四个 IQ 的旧参考合计约 6,097 LUT、7,342 FF。P3 IQ 之后还有两个已注册
输出 slot，用于隔离 LSU backpressure，所以它的 occupancy 和 FF 略高。

这种 distributed IQ 将一个大号多发射 select 问题拆成四个单发射问题，适合 FPGA；
代价是 early port binding。P0 的 uop 以后不能借用空闲 P1/P2，P2 的 branch/multiply
也会与分配到 P2 的 ALU 竞争。扩大 IQ 前应先区分“目标 IQ 满”和“该端口吞吐不足”，
因为更深队列不能增加端口数量。

Q01 的理论依据也来自这里：dispatch queue、window 和 IQ 保存完整
`RenamedMicroOp`，其中 PC、原始 instruction、predictor、exception、CSR、memory 等
字段并非每个端口都需要，许多 commit 信息已在 ROB 留存。建立 compact scheduling uop
或 port-specific payload 可能降低约 11k-LUT 调度结构的 FF、shift mux 和布线；必须逐
FU 列出 execution/completion 真正需要的字段，不能凭字段名称删除。

#### Store 为什么同时使用 STQ、P3 IQ 和 SDQ

考虑同拍 rename 的三条指令：

```text
I0: ld.w  r4, 0(r1)
I1: add.w r5, r4, r2
I2: st.w  r5, 4(r3)
```

allocation 时，三条都获得 ROB entry；I0 获得 `pdst` 和 LQ index，I1 获得 `pdst`，
I2 获得 STQ index。第一次 dispatch 时 I0 占 P3、I1 占一个 ALU port，I2 因同拍 P3
已使用而留在 window。下一拍 I2 必须同时进入 P3 IQ 和 SDQ：

- P3 IQ 跟踪 store address 源 `r3`。只要 base ready，AGU 就能先计算地址；
- SDQ 跟踪 store data 源，也就是 I1 为 `r5` 分配的 `pdst`。它等待 add 完成并被 wakeup，
  再发起 PRF read；
- 两条路径最终用相同 ROB pointer 和 STQ index 更新同一 store entry。STQ 只有在地址、
  data、提交/epoch 等条件都满足后，才允许对 cache/AXI 产生架构可见效果。

store dispatch 必须对 P3 IQ 和 SDQ 原子双写。当前通过 peer-ready gating 保证任一队列
不 ready 时两个都不 enqueue，避免同一 store 被重复或只写一半。

这里存在 D01 的明确假耦合：P3 的 scalar `portReady` 当前恒为
`lsuIqReady && sdqReady`，所以 SDQ 满时，连不使用 SDQ 的 load 也无法进入 P3 IQ。
候选改法是让 router 对每个 input lane 判断 `isStore`：load 只需 IQ credit，store 仍需
IQ+SDQ 两份 credit。它不改变 store 原子性，但需要修改 router ready 接口并验证没有
组合环。优先统计：P3 IQ 有空间、SDQ 无空间、dispatch 最老 P3 uop 恰为 load 的周期。

#### 本阶段优化判断

| 候选 | 理论收益 | 当前判断 |
| --- | --- | --- |
| D01：load/SDQ 解耦 | 去除明确的无关资源 backpressure，修改范围局部 | 最值得先测的 dispatch 候选 |
| D02：最大 prefix 端口匹配 | 减少 flexible ALU 抢占稀缺端口造成的 2/3-wide 损失 | 事件可能较少，且 router timing 风险较高 |
| A01：rename prefix allocation | younger memory/resource stall 时允许 older ALU 前进 | 收益上限较高，恢复与三路 bypass 重构复杂 |
| Q01：compact scheduling uop | 降低 dispatch/window/IQ LUT、FF 和 routed payload 宽度 | 需要 bit-level 使用清单和综合路径证据 |

测量顺序应从现有层次信号开始：记录每拍 rename stall 的唯一/组合原因、dispatch 实际
prefix 长度、理论最大匹配长度、四个 IQ occupancy、各端口 issue valid/ready、SDQ/LQ/
STQ occupancy。固定 c398 当前最差路径位于 L1I response -> frontend enqueue，旧实现又证明 dispatch/IQ 路径很
容易因宽 mux 和 ready 广播成为关键路径，因此任何周期优化都必须保留 window 的寄存
边界，并重新检查完整 SoC WNS。

### 2026-08-04：Wakeup、Select、PRF Operand Read 与 Issue

这一阶段解决的是一个非常具体的问题：生产者算出结果后，等待这个结果的消费者怎样
尽快进入执行，同时保证消费者读到的一定是新值。

以两条相关指令为例：

```text
I0: add.w r5, r1, r2       # 生产者，为 r5 分配 pdst=p37
I1: xor   r8, r5, r9       # 消费者，psrc1=p37
```

rename 后，I1 不再关心架构名 `r5`。它在 IQ 中保存 `psrc1=p37` 和
`source1Ready=0`。I0 完成时广播 `(valid=1, pdst=p37)`；所有 IQ 项把这个 tag 与自己的
两个 `psrc` 比较，匹配项就知道对应源已经 ready。广播的只有 6-bit 物理寄存器编号，
32-bit 结果数据并不穿过 IQ。消费者稍后在 PRF read/bypass 边界取得数据。

#### Wakeup 和 Select 分别做什么

Wakeup 是“把依赖边标记为满足”，select 是“从本端口所有可执行 uop 中选一条”。当前
每个端口有 8-entry compact age-ordered IQ，5 条 wakeup tag 同时比较每项的两个源：

```text
5 wakeup tags
      |  8 entries x 2 source tags
      v
source ready bits + same-cycle matches
      v
8-bit readyMap
      v
oldest-ready priority select
      v
one selected uop for this execution port
```

每个 IQ 的常驻项共有 `8 * 2 * 5 = 80` 个 6-bit tag 比较，四个 IQ 合计 320 个；新入队
uop 还各有同类比较，避免 completion 与 enqueue 同拍时丢失一次性 wakeup pulse。
`readyMap` 还要同时满足 entry 有效、两个真实源都 ready、执行语义允许：store data 已
由 SDQ 解耦，所以 P3 IQ 中的 store 只等地址源；serial/barrier 必须位于 ROB head。

select 在每个端口内部选择最老的 ready uop。假设一个 IQ 从老到新是 A、B、C：A 等
cache 结果，B 两个源都 ready，C 也 ready，那么 B 可以先发射；A 完成依赖后仍会优先于
C。四个 IQ 彼此独立，所以同拍最多各选一条，理论 issue 上限为 4。这里没有跨四端口的
全局 oldest 仲裁，uop 在 dispatch 时已经绑定到具体端口。

中间项被 issue 后，所有 younger 项向前 compact。同拍 wakeup 会一起折入搬移后的 ready
bit，避免 entry 移位时丢掉 tag pulse。这个结构曾把“年龄索引后再查物理 slot”的两级
选择缩成一次 payload lookup：旧 standalone 对照中 WNS 改善约 0.403 ns，但增加约
999 LUT。这是典型的 FPGA 取舍：多用一些逻辑，换更浅且更局部的路由。

#### 为什么还要区分 early wakeup 和 registered wakeup

“结果最终会产生”与“结果现在已经可安全读取”之间可能隔着流水级。当前有两类协议：

- **Early/direct wakeup**：单周期 ALU/branch/普通 CSR，以及固定延迟 MUL，在生产者被
  issue 时就广播 `pdst`。这是一项调度承诺，消费者可以提前离开 IQ；
- **Registered ROB wakeup**：execution/LSQ completion 先进入 ROB 的注册边界，经过
  recovery epoch qualification 后，下一拍同时更新 ready table、写 PRF 并唤醒 IQ；同一
  staged pointer 另行与 ROB entry 的 index/generation 匹配，以更新 entry complete 状态。
  DIV、load/store 等可变延迟操作使用这条保守路径。

对普通 ALU 依赖链，可按以下拍次理解。表中“第 E 拍”表示生产者已在 execution 输入：

| 拍次 | 生产者 | 消费者 |
| --- | --- | --- |
| E | ALU 组合结果送入 completion；direct tag 广播 | IQ 同拍 wakeup/select，边沿写入 address-uop 寄存器 |
| E+1 | ROB staged result 驱动 PRF write；PRF write-through 提供新值 | 用 `psrc` 异步读 PRF，边沿写入 operand 寄存器 |
| E+2 | 已完成 | 带正确 source data 进入 execution |

如果只等 registered wakeup，消费者要到 E+1 才能从 IQ 被选出，execution 相应推迟到
E+3。early wakeup 对真正卡在该 RAW 依赖上的消费者节省一拍；队列中本来就有其他独立
工作时，这一拍可能被乱序执行隐藏，因此不能把它直接换算为每条指令少一拍。

MUL 的数据对齐更特殊。P2 在 MUL 进入固定一拍乘法寄存器时就广播 tag；下一拍乘积从
独立的第 5 completion lane 出现。此时它还没经过 ROB staged writeback，赶不上普通 PRF
写口，所以 backend 用 `resultForwardPdst/Data` 在 operand-read 边界覆盖 PRF 旧值。再下
一拍消费者执行。这个旁路正是“先发 tag、后按约定时刻交付 data”的完整协议。

DIV 和 load 没有这样的固定交付时刻。当前 32-step divider 完成时才产生 completion，
且不直接驱动 IQ；load 也先在 LSQ 内注册 completion。它们再经过 ROB 的 staged
completion 后唤醒，消费者经历 select、PRF read 两级才执行。这个选择增加约一拍
completion-to-use 延迟，却切断了 variable-latency 结果到 IQ select 的长组合路径。历史
对照中 divider 切断使 `func_lab19` 慢 753 cycles（0.141%），同时完整 SoC WNS 从约
`-0.781 ns` 改善到 `-0.729 ns`、TNS 明显改善；它说明比赛应比较 cycles/Fmax 的乘积。

#### PRF 是怎样读写的

当前 PRF 是 64 x 32-bit 的寄存器阵列。`p0` 恒为零，实际形成 `63 * 32 = 2016` 个数据
FF，正好对应旧 standalone 报告中的 2,016 FF；整个 PRF 层次约 4,352 LUT。它提供：

- 四个执行端口各两个异步读口，共 8 个 execution read；
- 5 个 completion write port；
- 1 个 debug read；
- 每个读口上的同拍 write-to-read bypass，以及 MUL 的额外 result-forward 比较。

普通 P0/P1/P2 采用两个弹性寄存边界：`IQ select -> address uop -> PRF operand -> FU`。
后级 backpressure 时，address/operand 寄存器会保持内容，不会重复消费 IQ。P3 的 IQ 后
另有两个 registered output slot，然后直接捕获 PRF operand，以隔离 LSU/AGU 的复杂
背压。P3 第二个 PRF read 在 load 的第二源和 SDQ store-data read 之间共享；两者同时
请求会暂时阻止新的 LSU operand capture。

这里也解释了“BRAM 使用率低，为什么 PRF 不直接放 BRAM”。Artix-7 BRAM 原生端口少且
同步读，而当前 PRF 要求 8 个并行异步读、5 个同拍写、debug read 和写穿透。复制 BRAM
可增加读口，却仍要把 5 路写同步广播到每份副本；banking 又会引入源端口冲突和调度。
在 64 x 32 这个容量上，FF/LUT 实现符合当前低延迟目标。未来扩到 128 个 physical reg
时，多端口 mux、tag 位宽和布线会一起增长，不能只比较多出的 2 Kibit 数据容量。

#### 当前实现的两个关键观察

第一，同一 execution lane 的 registered wake 与 direct wake 共用一个 IQ tag lane，且
registered wake 优先。连续两拍在同一端口执行写 GPR 的 ALU 时，前一条在第二拍产生
registered 回声，可能覆盖后一条的 direct tag；后一条不会丢失，因为它下一拍还会经
ROB 唤醒，但 early-wakeup 收益被推迟。这并非罕见边角形态：连续 writer 本身就会持续
制造这种重叠。W01 应统计“冲突时确实有等待消费者”的次数，再比较以下方案：增加独立
tag lane、只抑制已成功广播的注册回声、或用有界 deferred-wakeup 存储。简单把 5 路
变成更多路会按比例增加 IQ 比较器和广播扇出，可能得不偿失。

第二，early wakeup 的正确性不只取决于 FU 类型，还必须满足生产者已经被执行端接受。
当前 execution 注释表达了这一约束，但 P0 的 direct-wakeup 表达式使用 `issueValid`，
没有显式合入受 barrier state 控制的 `issueReady`。若 barrier 正在 drain，而 younger P0
ALU 已停在 operand slot，理论上可能先广播 tag。这里先登记为正确性审计项：需要构造
“长时间 barrier backpressure + P0 producer + 跨端口 dependent consumer”的定向测试，
验证消费者不会用旧 PRF 值执行；确认前不能把 P0 early wake 视为已证明安全。该项记为
C01，测试优先级高于本记录中的全部性能优化与性能计数工作。

另一个与 R01 相连的细节是 `lsuNeedsSource2` 当前按 `psrc2 != 0` 判断。普通 load 的第二
操作数实际来自 immediate；如果未使用源仍保留了某个非零物理映射，它既会形成假 IQ
依赖，也会与 SDQ 争用 P3 第二读口。把 unused source 规范化为 `p0/ready` 因而可能同时
减少 wakeup 比较活动、load 假等待和 PRF shared-read conflict，价值高于单纯省一个 ready
bit。

#### Routed criticality 参考快照：非阶段性稳定版

以下数字来自 CPU `8594150feb65...` + Chiplab `c398d274...` 的 100 MHz perf
`candidate` routed DCP。该归档的 `timing_status=fail`，CPU 版本早于当前 source candidate，
也没有被选为阶段性稳定版。因此这些数据只用于理解路径结构，不能称为“当前 RTL 的
timing”，更不能在后续 RTL 变化后继续继承。新候选达到阶段性稳定点时，应重新从它的
routed DCP 执行同样的 top-N 和定向 endpoint 查询，并用新结果取代本小节的优化排序。

本次用 Vivado 2023.2 只读打开归档 DCP，对 `cpu_clk -> cpu_clk` 执行
`report_timing -max_paths 50 -nworst 1`，并对 IQ issue endpoint 另做定向查询。结果为：

| 单 endpoint 排名 / 路径族 | Setup slack | Data path | Logic / route | 解释 |
| --- | ---: | ---: | ---: | --- |
| #1：L2 MSHR ID -> L1I response predecode target | `-0.225 ns` | `10.328 ns` | `2.395 / 7.933 ns` | 全局 WNS，route 76.8% |
| #2：L2 refill valid -> L1D refill RAM input | `-0.214 ns` | `9.674 ns` | `1.521 / 8.153 ns` | 与 #1 的 slack 只差 0.011 ns |
| #3/#4：Decode/Rename payload -> ROB wide payload CE | `-0.155 ns` | `9.690 ns` | `1.588 / 8.102 ns` | 独立的 allocation/ROB 路径族 |
| #6/#7 起：ROB `stagedPdst` -> P3 IQ compact payload CE | `-0.139 ns` | `9.664 ns` | `1.534 / 8.130 ns` | 最差 wakeup/select 路径，route 84.1% |
| 定向 issue output：ROB `stagedPdst` -> `issueAddressUop` | `+0.244 ns` | `9.515 ns` | `1.506 / 8.009 ns` | select 到实际 issue-address 寄存器已闭合 |

因此“wakeup/select 比第二长路径长多少”的准确答案是：它没有比全局第二路径长。按
setup slack 排名，最差 wakeup/select/compaction path 比全局 #2 **宽松 0.075 ns**，比
全局 #1 宽松 0.086 ns；按 raw data-path delay，它比 #2 短 0.010 ns。全局 #1 与 #2 自身
只差 0.011 ns slack，说明当前 WNS 也不是由一个遥遥领先的孤立 endpoint 决定。raw data
delay 与 slack 排名不完全一致，原因是 source/destination clock insertion、skew、setup
和 CPR 不同，工程决策应以 slack 为主。

2nd pass 当时的 `60fba481...` routed top 10 中已经没有 wakeup/select：9 条是 F03，另
1 条是 L1D refill/response。所以上述“违例 0.139 ns、top 50 有 34 条”只能回答旧 bit
有多 critical，不能继续决定当前修改顺序。C01 是由 current RTL 静态谓词确认的正确性
问题，仍保持最高测试优先级；T01/W01/Q01 则降为计数与新 top-N 再触发的性能候选。

这个路径族仍然很 critical：它在 100 MHz 下违例 0.139 ns，对应固定布局附近约
`10.139 ns` 的所需周期或约 98.6 MHz；top 50 中有 34 条从 ROB `stagedPdst` 到 P3 compact
IQ 的同族路径。即使完全修复目前两个 cache path，Decode/Rename->ROB 与这 34 条调度
路径也会接着限制频率。若布局保持近似不变，105 MHz 要把该族改善约 0.615 ns，110 MHz
约需改善 1.048 ns；这些只是一阶目标值，不能代替新版本 place/route。

完整路径表明当前违例链为：

```text
ROB stagedPdst
  -> registered/direct wake 选择与 tag 广播（fanout 约 185/72）
  -> P3 IQ psrc match 与 readyMap
  -> oldest-ready select / queueDequeue
  -> count/compaction control（fanout 约 200）
  -> 宽 RenamedMicroOp payload 的 CE（局部 fanout 约 221）
```

所以这里的主要矛盾是 route-dominated 的 wakeup-select-compaction feedback，而非 ALU
数据计算，也不只是 priority encoder 的逻辑级数。

#### Wakeup/select 优化方向

按“先不增加 dependent-use latency”的原则，建议顺序如下：

1. **局部复制 registered wakeup tag。** 在 ROB staged boundary 为四个 IQ 形成物理上
   可独立放置的 tag/valid register replica，保持同一拍语义，把当前高扇出跨区 net 变成
   四组局部网络。先用综合/实现报告确认复制没有被合并，再判断是否需要局部
   `MAX_FANOUT`、phys-opt 或轻量 floorplan；这类改变消耗少量 FF，理论上不损失周期。
2. **拆开 hot scheduler 与 cold payload。** 当前 dequeue/compaction 会让 ready/select
   控制驱动 PC、exception、predictor、CSR、memory 等宽字段的 CE，最差 endpoint 正是
   `exception.badVAddr` 这类调度本身不使用的字段。Q01 应具体化为 compact hot entry
   （valid、psrc/ready、ROB age、必要 FU bits、payload index）加稳定 sidecar/port-specific
   payload；必须设计成 selected payload 取回不形成新的两级大 mux。此前“年龄索引再查
   物理 slot”的简单版本已出现更差路径，不能原样重试。
3. **先专门缩 P3 IQ。** 当前 top 50 中有 34 条同族路径落在 P3。store data 已由 SDQ 解耦，普通
   load/store 地址只需要 base `psrc1`；在逐 opcode 证明后，P3 scheduler 可删除无意义的
   `psrc2` wake compare，并只保留 AGU/LSQ 真正需要的 hot 字段。P3 已有两项注册输出，
   也适合独立尝试 2x4 bank 或更局部的 compaction，而不同时扰动三个 ALU IQ。
4. **减少 dequeue/compaction 控制扇出。** 可比较 2x4 bank、窄 metadata compaction、
   one-hot local shift enable 等实现。评价指标是 P3 path route、fanout、IQ LUT/FF 和
   occupancy/issue，不能只看 RTL 级逻辑数量。
5. **最后做物理引导。** 84.1% route 说明寄存器复制、模块相邻放置和 phys-opt 有机会，
   但 Pblock 应建立在结构缩窄之后，并用多个 implementation seed 检查稳定性。

如果上述零周期方案不足，再考虑明确的周期/Fmax 交换：让 registered wake 只在本拍写
ready bit，下一拍才参加 select；direct ALU/MUL 仍保留同拍 select。它可切断
`stagedPdst -> select -> compaction`，代价是 DIV/load 等 registered producer 的 consumer
再晚一拍。另一种激进方案是把 8-entry IQ 改成更浅或全流水 select。两者都必须比较
`cycle_count / f_cpu`，不应仅凭 WNS 采用。

当前不建议直接增加 wakeup lane 或扩大 IQ。额外 tag lane 会在四个 IQ 中成比例增加
比较器和广播扇出；更深 compact IQ 会同时扩大 match、select 和 payload shift。W01 应
优先研究“抑制已成功 direct-wakeup 的注册回声”或有界 deferred tag，避免以永久增加
广播宽度解决 collision；W02 的 load 提前唤醒也应等待当前调度网络局部化后再实验。

#### 本阶段优化判断与测量顺序

1. 先完成 C01 的 P0 accept-before-wakeup 定向测试；若失败，修复和完整回归后重建基线。
2. 再做 W01 统计：每 lane 的 registered/direct 冲突、被覆盖 direct tag 是否有 IQ
   consumer、消费者实际晚发射几拍。连续写吞吐高不代表冲突必然伤害 IPC，只有关键 RAW
   链上的冲突才计入周期收益上限。
3. 把 R01 的计数扩展到 P3 shared-read conflict，按 opcode 区分真实双源和 unused source。
4. 评估 W02：统计 load result 可用到第一条 dependent issue 的距离。若一拍边界经常
   暴露，尝试只从已经注册、epoch/ROB 身份充分校验的 completion 提前发 tag，不能把
   cache response 原始长路径直接接回 IQ。
5. 最后测 I01 的 IQ 满边界气泡。dispatch window 通常能吸收一拍 registered-ready 延迟，
   只有 count 6/7/8 附近反复阻塞 enqueue 时，额外 skid/credit 才有实际价值。

本阶段结论：当前 wakeup/data 分离协议很适合 FPGA，early ALU/MUL 能把关键 RAW consumer
提前一拍送入 operand pipeline，DIV/LSU 的注册边界则用少量周期换时序。最值得优先查的
并非扩大 IQ 或增加发射宽度，而是 W01 是否让已有 early wakeup 在常见连续单周期 writer 下失效，
以及 P0 是否严格满足 accept-before-wakeup。任何新增 tag lane 都必须和 320 个现有比较器、
约 6k IQ LUT、完整 SoC route-dominated WNS 一起评估。当前路径排序先做 F03/L1D；
wakeup 性能改造等 M01/W01 数据或新实现再次把它列入 top-N。

### 2026-08-04：Execution、Completion 与 Writeback

上一阶段结束在“uop 和两个操作数已经到达执行端口”。本阶段继续回答三个问题：执行单元
何时接受 uop、结果怎样穿过 completion 网络，以及一个推测结果经过哪些检查才能成为可
提交状态。这里先区分四个常被混用的事件：

| 事件 | 本项目中的含义 | 是否已经成为架构状态 |
| --- | --- | --- |
| Execute | FU 接受操作数并计算，或启动一个多拍操作 | 否 |
| Completion | FU 送出带 ROB 身份、epoch、结果/异常/分支信息的完成包 | 否 |
| Writeback / Wakeup | ROB 注册边界后的结果写入 PRF，并把 `pdst` 标为 ready | 否，PRF 仍是推测状态 |
| Commit / Retire | ROB 头按程序顺序确认指令，释放旧物理寄存器并实施允许的系统副作用 | 是 |

因此“ALU 已经算完”与“这条指令已经退休”之间至少还隔着身份校验和 ROB 顺序提交。
乱序核正是靠这条边界允许 younger 指令先使用结果，同时让异常、分支错误和中断仍表现
为顺序机的精确状态。

#### 四个执行端口为什么对应五条 completion lane

当前端口能力和完成通道可以画成：

```text
P0: ALU / CSR / privileged / barrier -----> completion lane 0
P1: ALU / iterative DIV ------------------> completion lane 1
P2: ALU / branch -------------------------> completion lane 2
              \
               `-- pipelined MUL ---------> completion lane 4
P3: AGU -> LSQ -> load/store completion --> completion lane 3
```

| 端口 | 当拍可接受的主要工作 | 结果延迟与 lane 所有权 |
| --- | --- | --- |
| P0 | 普通 ALU、CSR/计数器、串行系统操作、DBAR/IBAR/CACOP | direct 操作占 lane 0；barrier 状态机完成时也占 lane 0，状态机活跃期间阻止新 P0 操作 |
| P1 | 普通 ALU 或 DIV | direct ALU 占 lane 1；旧 DIV 返回同拍优先占 lane 1，ALU 被 backpressure 一拍 |
| P2 | 普通 ALU、branch 或 MUL | ALU/branch 当拍占 lane 2；MUL 进入独立流水并在下一拍占 lane 4 |
| P3 | load/store AGU | AGU 只把地址请求交给 LSQ，并不当拍 completion；稍后由 LSQ 在 lane 3 完成 |

第五条 lane 并不表示每拍能 issue 五条新指令。它解决的是时间上的重叠：P2 本拍可以执行
一条新的 ALU/branch，同时上一拍启动的 MUL 正好返回。如果 MUL 也复用 lane 2，就要么
停止 P2，要么增加仲裁/replay；当前独立 lane 4 让两者都不丢。类似地，某拍可能同时有
P0/P1/P2 三条 direct completion、一个较早 load 的 LSQ completion 和上一拍 MUL，最多
形成五个完成包。commit 仍只有三宽，超过三条的 burst 先由 ROB 吸收。

completion 接口没有 `ready`，所以“优先级 mux 覆盖另一个 valid”会直接丢结果。当前
几个共享点都显式避免了这种情况：P1 在 DIV 返回时不接受 direct ALU；P0 barrier 活跃时
不接受另一条 P0 uop；P3 的 AGU 本身不产生 direct completion；MUL 使用独立 lane 4。
现有 divider-return collision 定向测试也覆盖了 P1 的 backpressure。静态检查暂未发现
新的 completion 静默丢失路径。

#### 单周期 ALU、branch 和系统读取

`Alu` 是纯组合单元，没有内部寄存器或 ready/valid 状态。它覆盖 add/sub、
signed/unsigned compare、AND/NOR/OR/XOR、ANDN/ORN、LUI 和三类 shift：

- add、sub、SLT、SLTU 共享一个 33-bit adder。减法通过 `a + ~b + 1` 完成，signed
  compare 结合操作数符号和差值符号，unsigned compare 使用最高位 carry/borrow；
- shift amount 取第二操作数低 5 bit，所以 32-bit 指令的可变移位范围是 0--31；
- decoder 产生 14-bit operation mask，各候选结果按 mask 后 OR。正常指令是 one-hot，
  保留 masked-OR 形式也使该单元与原参考 ALU 的边界一致。

端口在 `issueValid && issueReady` 时真正接受 uop。普通 direct 操作在这一拍组合生成
`Completion`。源可以来自 PRF，也可以由 decoded control 改选 PC、立即数或常数 4。
branch 在 P2 同拍比较两个源，计算 taken/not-taken、实际 target，并把它与预测方向和
预测 target 比较；`branchResolved`、`branchMispredict`、`branchTarget` 随 completion
写进 ROB。当前 redirect 仍发生在 branch 到达 commit 时，所以执行时已经知道预测错，
前端却可能继续走错误路径；这正是长期候选 B01 的收益来源。

CSR/计数器读取也可以把读取值作为 GPR 结果 completion，但 CSR 写、TLB 操作等架构副作用
不会在 execute 当拍实施。所需的新值或操作数放在 `sideEffectData`，等指令在 ROB 头
无异常退休后再由 commit adapter 使用。decoder 将这类操作标为 serializing，IQ 只允许
它在 ROB head issue，从而避免 younger 系统操作观察到乱序的 CSR 状态。

#### MUL：一级结果寄存，吞吐一条/拍

乘法路径同时计算 32x32 signed 和 unsigned 的 64-bit product，再按指令选择低 32 bit
或高 32 bit。uop 和选中的结果进入一层寄存器，因此：

```text
M:   P2 接受 MUL，广播 fixed-latency wakeup tag
M+1: lane 4 给出 MUL completion 和 result-forward data
M+2: 依赖消费者最早到 FU 执行（经 IQ select 和 operand 边界）
```

只要 P2 前端能持续供给，乘法器每拍可接受一条，故 initiation interval（II）为 1；内部
结果 latency 为 1 拍。提前发 tag 是一份固定时刻承诺，M+1 的 result-forward 必须与它
同时成立，消费者才能在 PRF 正式 writeback 前拿到正确乘积。

旧 standalone 层次报告中 multiplier 约为 316 LUT、94 FF、4 DSP；其输入到结果寄存器
路径约 9.460 ns，包含 2 个 DSP48E1 和 8 级 CARRY4。这不等于当前完整 SoC 的 WNS，当前
timing-pass routed 参考的更差路径在 frontend 和 cache 网络。但这些路径改善后，
乘法器很可能成为下一层频率墙。T03 可尝试启用更合适的 DSP 输入/输出寄存、把 product
选择或符号处理移到另一拍。每增加一拍 MUL latency，都要同步推迟 early-wakeup 和
result-forward，并比较依赖 MUL 链增加的 cycles 与 Fmax 收益。

#### DIV：32 次迭代为何会长时间占用 P1

当前除法器采用逐位的 restoring 风格算法。可以用 4-bit 的 `13 / 3` 缩短理解：每一步
从 dividend/quotient 侧移入一位到 partial remainder，试减 divisor；够减就在新 quotient
末尾写 1，不够就在末尾写 0。重复 4 次得到 quotient 4、remainder 1。32-bit 硬件把这个
步骤重复 32 次，每拍只生成一个 quotient bit，组合逻辑因而不深，但总 latency 长。

启动时单元保存 uop、除数、被除数绝对值和符号信息；32 次迭代后做 quotient/remainder
符号修正。除数为 0 时结果定义为 quotient 全 1、remainder 等于原 dividend，但当前仍
走完整 32 次迭代。按 accepted edge 计数，结果在其后约 32 个迭代周期出现，新 DIV 在
完成脉冲后的下一接受边沿启动；不同 cycle 命名方式会把可见 latency/II 写成 32 或 33，
实验记录应固定“issue handshake edge 到 completion-valid edge”定义，避免差一拍争论。

DIV 不做 raw-completion early wake。它的 completion 经过 ROB 注册后才唤醒消费者。历史
实验表明，这个边界令 `func_lab19` 多 753 cycles（0.141%），同时把当时完整 SoC WNS
从约 `-0.781 ns` 改善为 `-0.729 ns`，TNS 从约 `-393.44 ns` 改善为 `-183.10 ns`；原来的
divider-tag -> IQ-select 路径被切断。这组数据来自旧候选，只说明 cycles/Fmax 的实际
取舍，不能继承为当前版本的时序结论。

E01 的低风险起点是 operand-class early-out：除数 0、`+/-1`、2 的幂，以及高位大量为零
时跳过无意义迭代。下一层是 radix-4，每拍产生两个 quotient bit，把主体迭代约减半，
代价是更复杂的 trial/select。完全流水化 divider 可把 II 降到 1，却会显著增加面积；在
没有 DIV 动态频率、P1 busy 和 ROB-head-blocked 数据前，它不是合理的首选。

当前实现没有 Fast Path Detection。`source1Magnitude/source2Magnitude` 只做 signed
绝对值预处理，`divideByZero` 也只是锁存一个标志，最终仍在第 32 次迭代后覆盖结果。
因此下列输入全部走满迭代：

| Fast path | 可直接得到的结果 | 当前是否提前完成 |
| --- | --- | --- |
| dividend = 0 | quotient = 0，remainder = 0 | 否 |
| divisor = 0 | quotient = all-ones，remainder = original dividend | 否 |
| divisor = `+1` | quotient = dividend，remainder = 0 | 否 |
| signed divisor = `-1` | quotient = `-dividend`，remainder = 0；`INT_MIN/-1` 按 32-bit 结果处理 | 否 |
| `abs(dividend) < abs(divisor)` | quotient = 0，remainder = original dividend | 否 |
| magnitudes equal | quotient 为 `+/-1`，remainder = 0 | 否 |
| divisor magnitude 是 2 的幂 | quotient 用 magnitude shift 后修正符号，remainder 取低位并保持 dividend 符号 | 否 |

Fast path 的每次命中最多可以省约 31 个迭代周期，所以 **E01 的测量优先级高，命中被证明
后实现优先级可升为中高**。它仍不能只因为“当前完全没有”就压过正确性、全局 WNS 或已
证明的高频 stall。可用以下一阶上界估算它对 CPI 的贡献：

```text
Delta CPI <= dynamic_DIV_fraction
             * fast_path_hit_rate
             * exposed_latency_fraction
             * saved_cycles_per_hit
```

例如 DIV 占 0.5%、20% 命中 fast path、其中一半延迟无法被乱序窗口隐藏，按每次省 31 拍
估算，`Delta CPI <= 0.005 * 0.20 * 0.50 * 31 = 0.0155`。若 DIV 只占 0.05%，同样结构的
上界就降为 0.00155。编译器通常会把编译期已知的 `/1`、`/2^k` 优化成普通算术或移位，
所以不能假设这些 case 在 perf20 中很常见；运行时变量除数的实际分布必须从 trace 获取。

实现实验也应分层，避免第一版就把复杂 priority encoder 放到启动路径：

1. **Tier A：简单比较 fast path。** 先做 dividend 0、divisor 0、`+/-1`、magnitude
   less/equal。这些只需零检测、常量比较和一个 magnitude compare，收益最大且验证边界清楚。
2. **Tier B：power-of-two。** 用 `x != 0 && (x & (x - 1)) == 0` 检测，再用 trailing-zero
   count 生成 shift amount；signed quotient 必须向 0 截断，remainder 符号跟 dividend，
   不能直接用算术右移替代完整语义。
3. **Tier C：leading-zero skip 或 radix-4。** 它们能改善普通输入，命中范围更大，但控制、
   trial subtract 和启动逻辑更复杂，应在 Tier A/B 的真实收益后再决定。
4. **第二 divider / 全流水 divider。** 主要改善 throughput，不直接缩短单条关键 DIV 的
   latency，还要增加 completion 仲裁；除非 trace 显示成簇 DIV 导致持续排队，否则最低优先。

任何 fast path 都仍走 P1 divider completion，而不是伪装成 direct ALU：它可以提前拉高
`completionValid`，现有 lane-1 仲裁会把同拍 ALU backpressure。这样能复用 registered
wakeup/epoch/ROB 协议。进入 E01 实现前先完成 C02，锁定四种 DIV/MOD、除零、
`INT_MIN/-1`、signed remainder、flush 每个迭代点和 exactly-once completion。

2nd pass 重新检查了 `6bbca9b...` 新增的 448 行 execution-cluster 测试：新增覆盖集中在
DBAR/IBAR/CACOP、CPUCFG 和系统 completion；DIV 相关仍只有“divider return 对同 lane
direct ALU 施加 backpressure”的仲裁测试，没有活动 `DivideUnit` 的数学 differential
与逐迭代 flush。因此 C02 的表述和 E01 的前置 gate 保持不变。

#### 执行端口分配与执行单元数量是否值得改变

先区分 execution port、FU 和 completion lane：port 是一条从 IQ/PRF operand pipeline
送入某组 FU 的发射通道，一个 port 可以挂多个不会同时启动的 FU；MUL 又可以在离开 P2
后继续流水，并从独立 lane 4 返回。当前功能数量近似为：

| 结构 | 数量与能力 | 首要限制 |
| --- | --- | --- |
| General ALU | 3，分别位于 P0/P1/P2 | dispatch 绑定、IQ 依赖和端口不均衡，而非算术吞吐 |
| Branch resolve | 1，P2 | 与 P2 ALU/MUL 争新 issue slot；恢复仍等 commit |
| Multiplier | 1，P2 启动，II=1，独立 lane 4 返回 | 单条依赖 latency 和 DSP path；持续吞吐已经一条/拍 |
| Divider | 1，P1，非流水迭代 | 单条约 32 次迭代、连续 DIV 排队和 P1 HOL blocking |
| AGU/LSU issue | 1，P3 | LSQ/DTLB/cache/ordering 的端到端单入口 |
| CSR/serial/barrier | 1，P0 | ROB-head serialization 和 memory drain |

旧 standalone 层次报告中整个 execution cluster 约 843 LUT、406 FF、4 DSP；其中 divider
约 253 LUT/233 FF，multiplier 约 316 LUT/94 FF，扣除二者后的 execution/control 层次约
274 LUT/79 FF。层次归属和综合优化使它不能精确等同于“三个 ALU 的面积”，也不是当前
完整 SoC 资源结论，但足以表明普通 ALU/branch 不是约 75k-LUT CPU 的主要面积消费者。
相比之下，四个 IQ 约 6,097 LUT，PRF 约 4,352 LUT，ROB 约 28,083 LUT。增加 port 时，
昂贵的往往是 IQ、PRF 读口、wakeup、payload routing 和 completion，而不是 32-bit adder。

当前最值得先解决的是静态绑定质量。dispatch router 对每条指令选择“最低编号、capable、
enqueue-ready”的 port；它不知道 IQ occupancy、已排队的稀缺操作或 FU 将来的 busy 状态。
例如同组三条为：

```text
lane 0: ALU       可去 P0/P1/P2
lane 1: CSR       只能去 P0
lane 2: DIV       只能去 P1
```

贪心策略先把 lane 0 放到 P0，随后 lane 1 无处可去，整条可接受 prefix 在这里停止；实际
存在 `ALU -> P2, CSR -> P0, DIV -> P1` 的三路匹配。单条 ALU 连续出现时又总优先进入
P0，直到该 IQ 的 registered enqueue-ready 下降，可能让带未就绪依赖的 P0 队列积压而
P1/P2 较空。D02 的 maximum-prefix + scarce-first/occupancy-aware matching 因而比增加 FU
更接近根因，但组合匹配不能重新拉长 dispatch critical path。

P1 还有一层动态可用性问题。divider busy 时，P1 ALU 实际仍能每拍执行；但普通端口在
IQ 后还有 address 和 operand 两级。第一条 DIV 真正到 execution 并占用 divider 前，第二条
ready DIV 已可能提前进入流水。它随后停在 operand slot 等约 32 拍，挡住其后的 ready
ALU。简单把 `divider.ready` 接到 IQ readyMap 太晚，正确的 E03 需要在 IQ 选择第一条 DIV
时就消耗一个 reservation credit，直到 completion/flush 才归还；无 credit 的 DIV 留在
IQ，younger ALU 仍可被选择。另一种实现是独立的小 DIV wait queue，但成本和公平性更复杂。

各类结构修改的当前排序为：

| 修改方向 | 理论收益 | 当前判断 |
| --- | --- | --- |
| D02：occupancy/scarcity-aware 最大 prefix 匹配 | 消除已有三个 ALU port 的错误绑定和同组贪心损失 | **高优先级测量**，通常先于加 FU |
| E03：P1 DIV reservation / 可执行 uop select | DIV busy 时继续使用已有 P1 ALU，避免约 32 拍 HOL | **高优先级测量**；若存在连续 DIV+ALU，可能是低面积高收益 |
| E01：DIV Fast Path Detection | 命中时缩短单条 DIV 约 31 拍，直接减少 RAW/ROB-head 等待 | **高优先级测量，条件性中高实现优先级** |
| 给第二个 port 增加 branch capability | 减少 P2 IQ/同组多 branch 冲突 | branch 通常低于一条/拍；先测 P2 冲突和双 branch group |
| 第二 divider | 提高连续 DIV throughput | 不改善单条 latency，先做 E01/E03；还需 completion 仲裁 |
| 第二 multiplier | 提高 MUL+branch 同拍启动能力 | 当前 multiplier II=1，通常没有持续 MUL throughput 缺口 |
| P3 同时接受 ALU | 在 P0/P1/P2 受阻时借用第四通道 | 三宽 dispatch/commit 已有三个 ALU；会挤压唯一 LSU 和当前参考 critical P3 IQ |
| 第二 AGU/LSU port | memory-heavy workload 可多算一个地址 | 必须同步扩展 LSQ update、DTLB、cache request、ordering 和 PRF 端口，不能只复制 adder |
| 第五 execution port / 第四普通 ALU | 增加 ready burst 的峰值 | 前端持续上限约 2 IPC、commit 上限 3、实测 IPC 低于 1；当前缺少收益依据 |
| 专用 simple/complex ALU 划分 | simple ALU 去掉 barrel shifter 等逻辑 | direct execution 面积小，新增 capability 约束会加剧调度冲突；当前 WNS 也不在 ALU |

所以 E04 应先做 trace replay，而不是逐个综合大量拓扑。每拍记录三条 dispatch uop 的
capability mask、实际/最大匹配、四个 IQ occupancy/ready 数、FU busy、operand-slot head
类型和实际 issue。离线模拟“只改 router”“加 branch capability”“双 DIV”“双 AGU”等
结构，先得到理想周期收益上界；如果 replay 都几乎不减少 stall，RTL 面积实验没有价值。

#### LSU 和 barrier 为什么也是 variable-latency completion

P3 execute 只完成 AGU：`virtualAddress = source1 + immediate`，并形成 size、byte mask、
store data 等请求字段。load 要等待地址翻译、顺序检查、forward/cache/AXI response；
store 的地址、数据和提交许可也可能在不同时间到达。因此 load/store 的“执行地址完成”
与“这条内存指令可以完成”是两个事件，后者由 LSQ 仲裁后在 lane 3 送出。本阶段只确认
接口和完成带宽，具体 replay、forward、uncached 和 ordering 留到内存乱序执行阶段。

DBAR、IBAR、CACOP 则由 P0 的状态机完成。它在 ROB head 接受 token，等待 older store 和
memory subsystem quiescent；需要时发起地址翻译、I-cache/cache maintenance，再做一次
post-drain，最后才生成无 `pdst` 的 barrier completion。flush 发生在外部 response 尚未
返回时，状态机会进入 drop 状态吸收旧 response，不为错误 epoch 产生完成。这个过程说明
serializing 并不等于“decode 后立即全核停住”，它仍是一条进入 ROB、在 head 获得执行
许可、最终通过 completion/commit 精确结束的动态 uop。

#### Completion 包怎样通过 ROB 身份检查

五条 lane 上的 `Completion` 至少携带以下信息：

- `robPointer`：ROB index 加 generation 身份，用于区分环形队列同一物理槽的不同生命期；
- `recoveryEpoch`：区分 flush 前后的推测世界；
- `pdst/writesPdst/data`：PRF writeback 所需的 tag 和结果；
- `exception` 与 `sideEffectData`：精确异常和延迟到 commit 的系统副作用；
- branch resolved/taken/target/mispredict：提交时训练 predictor 和决定恢复。

completion 输入先按 lane 注册为 `stagedCompletion`。ROB entry 真正置 `complete` 时会检查：
completion valid、当前没有 flush、ROB index 相同、entry 仍 valid、entry 尚未 complete，
并且 pointer generation 相同。epoch 还在进入 staged boundary 时区分当前恢复代。这样，
flush 后迟到的 cache/DIV response 不会把新窗口中碰巧同 index 的 uop 标成完成。

PRF wakeup 的资格有意更窄：staged completion 属于 current epoch、写非零 `pdst` 即可，
没有再读取整个 live ROB entry 做匹配。这避免把 32-entry ROB identity compare 拉进 5 路
PRF/IQ 广播路径，但它建立了一个必须持续维护的协议前提：每个执行源对已接受 uop 只产生
一次 completion，flush 后不以旧 epoch 返回，且不会凭空产生同 epoch completion。现有
测试甚至明确覆盖“current-epoch completion 可产生 wakeup，而未分配 ROB entry 不会置
complete”的分层语义。当前检查尚未找到会违反该前提的源，所以这里不登记新的 `Cxx`；
未来新增 replay、cache response 或多拍 FU 时，必须把“exactly-once + epoch”作为功能
proof obligation，不能只验证 ROB entry 最终没有误置 complete。

#### 用逐周期例子理解 completion、writeback 和 commit

假设一条普通 `add.w` 已经是 ROB head，且它的结果写 `p37`：

| 周期 | ALU / completion | ROB / PRF / consumer | Commit |
| --- | --- | --- | --- |
| E | P1 接受并组合算出结果，lane 1 给出 completion；direct tag 可同拍唤醒依赖者 | completion 尚未经过 ROB 注册；依赖者可在边沿进入 operand-address 级 | 该 entry 仍不 complete |
| E+1 | 原 completion 位于 staged registers | `p37` 写 PRF，registered wake 作为可靠回声；exact pointer match 组合成立 | `entry.complete` 的旧值仍为 0 |
| E+2 | entry 已在上一边沿置 complete | younger 指令可以早已使用 `p37` | 若它仍是 head，最早在本拍成为 commit candidate |

这条固定流水让 direct producer 的消费者可以早于 producer commit 很多拍执行，也在 head
恰好等待结果时留下一个可观察的 E+1 空提交周期。E02 的设想是让经过注册且 exact-match
的 staged completion 直接补充 head 的 `complete/result/exception/branch`，使其在 E+1
参与 commit。理论收益最多是每次这种 head-only 等待一拍；实际实现会把五路匹配和宽
completion payload 拉进三路 prefix commit、exception/serializing/mispredict stop 链。
它只有在统计出大量可消除 head bubble，且 ROB/commit WNS 允许时才值得实验。

若 head 是误预测 branch，E02 只能去掉上述固定完成边界的一拍；B01 的 execute-time
恢复可以进一步去掉“等待所有 older 指令完成”的不定长部分，但需要完整的选择性恢复
机制。二者收益和复杂度不是一个量级，应分别计数，不能把 branch 的全部恢复损失归给
completion 流水。

#### 本阶段优化判断与测量顺序

1. `C01` 仍是全局 blocking gate。P0 barrier backpressure 时，direct wake 必须证明只来自
   已接受 producer；本阶段没有发现优先级更高的新正确性项。
2. 仿真侧先建立按 FU 的 `issue accepted -> completion -> ROB complete -> commit` 时间戳，
   统计每类 latency、II、lane 利用率、P1 collision、completion burst 和 ROB-head stall。
3. E01 从 operand 分类和理论节省周期开始。只有 DIV 延迟经常暴露到 ROB head 或关键 RAW
   链时，再做 early-out/radix 对照；若大多数 DIV 被独立工作隐藏，面积和时序更重要。
4. E02 先离线计算严格收益上限，并分别统计 ALU、load、DIV、branch、serializing head。
   任何 bypass 都必须重证精确异常、同拍三提交 stop 规则、flush 和 slot generation。
5. T03 在新的阶段性稳定 RTL 上重跑 standalone 与完整 c398 SoC。比较指标是各 benchmark
   的 `cycles / actual_frequency`，同时覆盖连续 MUL、dependent MUL、flush 交叠以及 lane 4
   result-forward，不能只看 DSP 数量或 standalone slack。
6. 当前不需要增加全局 completion 宽度。5 lanes 已覆盖四端口加 pipelined MUL 的最大
   结构性 burst；更可能的吞吐限制位于 LSQ 内部单 completion 仲裁、FU busy、IQ 供给或
   3-wide commit，需在对应阶段依据 occupancy/stall 数据判断。

本阶段结论：执行端口数量决定“本拍能启动什么”，completion 宽度决定“过去启动的不同
延迟工作本拍能同时回来多少”，commit 宽度决定“最终每拍能把多少顺序状态变成架构可见”。
当前 4/5/3 的组合具有明确的解耦意义。短期性能机会主要是测清 DIV 的外露延迟和 ROB
head 固定完成气泡；频率机会是 MUL DSP 流水边界。三者都排在 `C01` 定向验证之后。

### 2026-08-04：三宽顺序提交、精确状态与恢复

乱序核允许指令改变执行完成顺序，但最终必须按程序顺序确认架构效果。ROB 的 head
由此成为投机世界与架构世界的边界：head 之前已经提交的状态允许被软件观察，head
及之后仍可能因异常或分支错误而消失。

可以先把一条指令经历的三种“完成”分开：

| 状态 | 含义 | 能否立即成为架构状态 |
| --- | --- | --- |
| execute complete | 执行单元已经得到结果或异常信息 | 不能；更老指令仍可能异常 |
| ROB complete | completion 已精确匹配到对应 ROB entry | 不能；还要等它走到 ROB head |
| retired / committed | ROB 按序接受该指令的最终结果 | 可以；此后恢复必须保留其效果 |

例如，年轻的 `add.w` 先算出 `p37`，依赖者可以通过 wakeup/PRF 提前使用 `p37`。如果
更老的 load 随后触发页异常，这两条年轻指令都要被清除，`p37` 不能成为软件可见的
`r5`。这正是乱序执行仍然能够提供 precise exception（精确异常）的基础。

#### 当前 ROB 怎样支撑三宽提交

当前 ROB 有 32 个 entry，最多每拍提交 3 条。每个 entry 的信息大致分成两类：

- 频繁参与调度和提交判定的 hot state：`valid`、`complete`、`payloadReady`、pointer、
  result、completion exception、branch taken/target/mispredict 等；
- 位宽大但执行期间很少变化的 cold payload：PC、instruction、`rd/pdst/oldPdst`、
  load/store/branch 类型、预测器 metadata、LQ/STQ index、CSR/系统操作和 decode exception。

cold payload 放在 4 个同步读 `Mem` bank 中。连续三个 ROB pointer 的低两位不同，所以
三条 commit candidate 会自然落在不同 bank，可以每拍并行读取，而不必复制整份 payload
存储。hot state 仍用寄存器保存，避免 `valid/complete/mispredict` 等高扇出控制信号穿过
同步 RAM。这是很贴合 FPGA 的组织方式：大位宽数据用 BRAM/分布式 RAM，窄而关键的状态
留在寄存器中。

同步读带来一拍预取边界。ROB 用三个注册的 `candidatePointer` 提前读取下一组 payload；
本拍实际提交 `committedCount` 条后，下一拍的读基址取
`commitPointer + committedCount`。因此，正常的连续三提交不会因为 RAM 同步读而天然隔一拍。
新分配 entry 的 `payloadReady` 会延后置位，用来避开同地址读写语义，尤其是分配时便
complete 的 decode exception。提交条件必须同时看到 `valid && complete && payloadReady`。

#### Prefix commit：为什么 lane 2 完成也可能不能提交

三个 candidate 必须形成从 ROB head 开始的连续前缀：

```text
canCommit[0] = lane0.valid && lane0.complete && lane0.payloadReady
canCommit[i] = lane[i] ready
             && canCommit[i-1]
             && !stopAfter[i-1]

stopAfter = exception || serializing || branchMispredict
```

这条规则同时解决顺序性和同拍多个事件的优先关系。

例 1，lane 0 已完成，lane 1 等待 cache miss，lane 2 已完成：

```text
ROB head -> [lane0 ready] [lane1 incomplete] [lane2 ready]
commit         yes              no               no
```

本拍只提交 lane 0。lane 2 的完成结果可以继续留在 ROB，但不能越过 lane 1。

例 2，lane 0 是普通 ALU，lane 1 是正确预测的普通 branch，lane 2 是 ALU：三条都可提交。
正确预测 branch 不需要停止提交前缀，因为它不会改变当前控制流。

例 3，lane 1 是误预测 branch：lane 0 与 lane 1 可以提交，lane 2 被挡住。branch 自身的
链接寄存器结果等属于正确路径状态，所以 branch 正常 retire；恢复只删除 branch 之后的
投机状态。

例 4，lane 1 是 serializing 指令：允许 lane 0 与 serializing 指令一起提交，lane 2 停止。
CSR/TLB/barrier/refetch 等副作用随后由 commit adapter 和 CoreSystem 按已经确定的 lane
处理，不依赖“系统指令总在 lane 0”的假设。

因此三宽 commit 的含义是“一拍最多确认三个连续、可确认的 ROB entry”，它不保证每拍
三条。实际 IPC 取决于 head 是否 ready、前端供给、IQ/执行端口、cache miss、序列化事件
和恢复频率。

#### `commitValid` 与 `retired` 为什么要分开

当前接口区分：

- `commitValid`：本拍消费并移走这个 ROB slot；
- `retired`：该指令正常退休，其架构效果应该生效。

普通指令和误预测 branch 都是 `commitValid=1, retired=1`。触发精确异常的 entry 是
`commitValid=1, retired=0`：ROB 必须移走故障 entry 并启动恢复，但它不能写架构寄存器，
也不能产生正常的 CSR、TLB、cache 或 LL/SC 副作用。

考虑下面一组：

```text
lane0: 已完成 store
lane1: load page fault
lane2: 已完成 add.w
```

lane 0 正常 retire；lane 1 被消费但不 retire，并报告异常；lane 2 因 prefix stop 留在
投机窗口，随后被 flush。异常处理程序看到 lane 0 之前的所有效果，看不到故障 load 和
lane 2 的效果，这就是 precise exception。

`committedCount` 统计的是 `commitValid`，所以异常 entry 也会推动 ROB head。恢复随后把
整个剩余窗口清空，并令新的取指从异常入口开始，不会反复提交同一个异常 entry。

#### 提交时物理寄存器到底发生什么

rename 后，一条写 `r5` 的指令携带：

```text
rd       = r5
pdst     = 新映射，例如 p37
oldPdst  = 被替换的旧映射，例如 p12
```

它退休时，architectural RAT 把 `r5 -> p37` 固化；`p12` 此时才真正没有架构引用，允许
回到 FreeList。提前释放 `p12` 会有风险：若更老指令异常，恢复仍需要旧映射。

同拍三条退休也可能连续写同一个架构寄存器：

```text
lane0: r5 <- ..., pdst=p37, oldPdst=p12
lane1: r5 <- ..., pdst=p41, oldPdst=p37
lane2: r5 <- ..., pdst=p46, oldPdst=p41
```

architectural RAT 最终应得到 `r5 -> p46`；三个 retired writer 对应的旧版本 `p12/p37/p41`
都按其生命周期释放。当前多 lane 更新用年轻 lane 覆盖年老 lane，符合程序顺序。

为切断 ROB commit 到 FreeList 的历史关键路径，当前 `oldPdst` 释放批次先经过寄存器，
FreeList 下一拍再接收。FreeList 同时维护 speculative head/count 和 architectural
snapshot；flush 时恢复到包含这批已提交释放的 snapshot。这里的一拍延迟是明确的时序
边界，不应直接删掉。

它可能产生一个很窄的性能现象 `K02`：若 rename 本拍看到 `freeCount=0`，而 commit 恰好
释放物理寄存器，rename 仍可能多停一拍。64 个物理寄存器、32-entry ROB 下这个边界可以
到达，但出现频率尚无数据。先计数
`freeCount == 0 && 本拍退休GPR writer`；只有它形成可见 stall，才考虑本地 credit、
小 skid buffer 或专门的 exhaustion bypass，同时保留长路径上的寄存器隔离。

#### 从 branch/exception 到前端 redirect 的逐周期恢复

当前恢复是 commit-time recovery。以 ROB head 的误预测 branch 为例：

| 周期 | ROB / backend | 架构与 speculative 状态 | 前端 |
| --- | --- | --- | --- |
| N | branch 作为提交前缀最后一条 retire，产生 `recoveryValid` | 更老及 branch 的退休生效；年轻指令仍在物理结构中 | 仍可能沿旧路径活动 |
| N+1 | `recoveryPending` 形成 backend/global flush | speculative RAT 恢复为 architectural RAT；FreeList 恢复 snapshot；IQ/ROB/LSQ 清理 | redirect 到 resolved target |
| N+2 起 | 新路径重新 rename/dispatch | 新 uop 使用新的 recovery epoch | 重新填充前端和后端 |

异常与此相似，只是故障指令 `retired=0`，redirect 目标选择普通 exception entry 或 TLB
refill entry。对 CSR/TLB/ERTN/refetch 类指令，commit adapter 先注册已退休副作用，
CoreSystem 在 flush/redirect 边沿应用状态更新。这样可以确保新路径看到更新后的 CSR/TLB，
也把宽 commit 判定与远端控制网络分隔开。

当前恢复会清空全部未提交窗口，看上去比“只删除 branch 之后的指令”更激进。由于恢复
发生在 branch 到达 ROB head 并退休时，所有比 branch 更老的 ROB 指令已经退休，窗口中
剩余的本来就全是 branch 之后的年轻指令。因此这里不需要逐 branch checkpoint 也能正确
恢复；代价是 branch 必须等到 ROB head，期间错误路径会继续占用 rename、IQ、LSQ 和执行
资源。此前记录的 `B01`（execute-time 选择性恢复）针对的正是这段等待。

`K01` 是一个范围更小的候选：把“commit 发现恢复 -> 下一拍 redirect”缩短一拍。它每次
恢复最多直接节省一拍，无法消除 branch 从 execute 等到 ROB head 的时间。其实现仍会
同时触及 registered physical free、预测器训练、CSR/TLB 副作用、LSQ 清理和全局 flush
扇出；收益应先用 `recoveries / total_cycles` 算上限。历史上这些寄存边界曾显著切断
commit 到 FreeList/RAS 的路径，所以 `K01` 只能作为有数据支持的时序实验。

#### 已退休 Store 为什么不能随 flush 消失

store 的“ROB 退休”和“写入 cache/AXI”可能相隔多拍。当前 LSQ 在 store 退休时把对应
SQ entry 标为 committed，等 memory hierarchy 真正接受后才释放。恢复时：

- 清除年轻、未提交的 speculative store；
- 保留已经退休但尚未排空的 store；
- 保留已经发起、不可逆的 uncached write，并先排空旧 epoch 工作；
- 清除被杀死路径的 load。

前面的 `store; faulting load` 例子因此能够成立。若 flush 简单清空整个 SQ，已退休 store
会静默丢失；若错误路径 store 被误标 committed，则会形成不可撤销的内存破坏。store
commit 标记、AXI backpressure、uncached write 与异常恢复的交叠必须纳入 Linux 正确性
验证，不能只观察 GPR commit trace。

`79c0045...` 把这一点接入了官方 DiffTest 可观测边界：每条 retiring load/store 现在报告
instruction mask、PA、VA，Store 还报告 data 与 byte mask。`80fb1cb...` 再保证 flush 同拍
已经退休的 memory event 不会因 LSQ 状态清理而丢失。这两项修复让 NEMU 能核对已提交
内存效果；它们不改变 Store 真正进入 cache/AXI 的时机，也不修复 C04/C06 的执行前顺序。

#### 三提交而单路训练：`B03`

ROB 每拍可退休三个 branch，但当前 Core 只形成一路 predictor update。选择逻辑保留本组
最老的一条 committed branch。常见情形不会立即暴露问题；下面这组值得关注：

```text
lane0: older branch，预测正确
lane1: younger branch，预测错误
lane2: 因 lane1 stop 而不提交
```

lane 0 和 lane 1 都 retire，恢复 target 来自 lane 1，预测器训练却可能只收到 lane 0。
于是造成恢复的 branch 本身没有更新 BTB/PHT，重复执行时可能继续误预测。这是已经由
B03-min/B03-full 软件 A/B 证实有高权重收益的性能缺口；收益在各 benchmark 间并不均匀，
仍需用 matching route 决定是否值得承担 predictor 周围的额外物理成本。

最低成本修补可以优先选择 mispredict branch，保证肇事 branch 被训练，但仍会丢掉同组
其他 branch 的训练。完整方案可以把最多三条 retirement update 写入一个小 FIFO，再按
程序顺序训练 BTB/PHT。GHR 和 RAS 更敏感：它们代表按程序顺序演进的历史，redirect 后
第一次 lookup 前必须已经折叠本组所有已退休 branch 的真实 outcome/call/return 行为，
否则仅仅排队延后写会使用过期历史。后续评估应分别统计：

- 每拍退休 0/1/2/3 条 branch 的分布；
- 一组中退休多条 branch 的次数；
- older-correct + younger-mispredict 的次数；
- 被选择与被丢弃 update 的类型；
- 修复后的 MPKI、周期数、predictor update 路径 WNS 和资源。

实际实验分成两步。B03-min 只在同拍退休组中优先训练 recovery branch，相对 F01 的 19 项
perf20 从 `53,646,498` 降到 `47,378,949`（`-11.683053%`）；它证明了缺口，但仍丢同拍
其他 branch。B03-full source `0aa5a82`、publication `491cb8f` 增加深度 8 的更新 FIFO，
每拍最多按程序顺序写入三项、每拍向单写口 BTB/PHT 排出一项，同时让 architectural
history/RAS 在退休批次当拍完整折叠。修复三宽 debug endpoint 后，standalone 19 项全部
通过，总周期为 `47,272,002`，相对 B03-min 再降 `106,947`（`-0.225727%`）：11 项改善、
6 项轻微退化、2 项相同。最大新增改善是 `quick_sort -51,335` cycles；最大回退是
`fireye_I2 +10,668`（`+0.167465%`）。因此最终保留 full 版本，不用 min 的剩余丢训练行为
换取这一级很小的资源节省。

与 F01+H03-II 合并后，B03-full 候选 `9960820/73077349...` 的 19 项总周期为
`44,286,720`，相对无 B03 的同组合 `50,645,040` 减少 `12.554675%`；相对 B03-min
组合的 `44,402,393` 再降 `0.260511%`。后一个数字还包含 retirement-state timing staging，
不能当作纯 B03-full 单变量结论。该 staging 先寄存 conditional-history、call/return 和
`pc+4`，再驱动三路 GHR/RAS fold，切断 ROB payload 到 predictor 状态的静态长路径；
H03-II 的 L1I array/tag 到 frontend 组合路径和 B03 FIFO 的 capacity-to-commit 反馈仍需
matching 100 MHz route 排序。

#### Completion slot 重用的正确性审计：`C03`

每条 completion 携带 ROB pointer。当前 staged completion 的接受条件包含 entry valid、
pointer 相等、entry 尚未 complete 等检查；它没有显式比较 `recoveryEpoch`。flush 后 ROB
保留 allocate pointer 并清空 occupancy，通常足以让旧 completion 失配。现有执行/LSQ
协议也应在 flush 时丢弃旧事务。

需要审计的极端情况是：某个旧 epoch completion 异常延迟，在重新分配 64 个 pointer
generation 后，与新的 valid、incomplete entry 得到完全相同的 pointer。若源端没有
保证旧事务早已消失，它可能错误完成新指令。现在没有动态证据表明该序列可达，所以
`C03` 记录为潜在正确性风险，不能称为已确认 bug。

验证应直接构造：发出长延迟事务、flush、持续分配直到 pointer 完整回绕，再注入旧
completion，断言新 entry 的 complete/result/exception 均不变化。还要分别覆盖 DIV、
cacheable load、uncached load/store response 和可能跨 flush 的 AXI backpressure。若证明
任何 producer 可以跨完整 pointer 回绕，解决方向是 completion 比较 epoch，或在源端给出
可证明的 flush kill/response quarantine；单纯扩大 pointer 只能降低概率，不能建立契约。

#### 本阶段的性能判断

1. 三宽 commit 与三宽 rename/dispatch 匹配。L1I-only ceiling 约 2 IPC，但包含 I-side ATU
   与 translated slot 的当前整链静态上限约 1 IPC；clean Linux 窗口约 0.557 IPC。继续加宽
   commit 暂时没有收益依据。它会增加 payload bank、prefix
   stop、architectural RAT、FreeList 和 side-effect 网络成本。
2. 先建立 commit 直方图：每拍 0/1/2/3 条；空提交按 head invalid、incomplete、
   `payloadReady=0`、exception、serializing、mispredict、flush 分类。若 3-wide 很少饱和，
   优化方向应落在造成 head stall 的阶段。
3. `B03` 可能让同一 branch 重复误预测，且修改可局限在退休训练通路，测量优先级高于
   commit 加宽。先用计数器确定 update 丢失量，再选择 mispredict priority 或多 update
   顺序折叠方案。
4. `K01` 每次恢复上限一拍，`K02` 每次 FreeList exhaustion 上限约一拍。二者都可以用
   事件计数精确估出理论收益，不应先拆历史时序寄存器再观察结果。
5. `E02` 的 staged completion -> head commit bypass 与本阶段直接相连：它可消除 head
   等待固定一拍，却会把 completion match/result/exception 拉进三路 prefix 链。需在新的
   稳定 bit 上结合完整 SoC WNS 决策。
6. 正确性验证优先级高于以上性能实验。先关闭 `C01/C02/C03`，尤其覆盖 flush、pointer
   wrap、backpressure、已提交 store 和 serializing 副作用，再比较 cycle/frequency 乘积。

本阶段结论：当前 ROB 的三宽前缀提交、hot/cold 分离、四 bank payload、architectural
RAT snapshot 与 LSQ committed-store 保留，共同构成了精确状态边界。设计的主要吞吐问题
目前看不在 commit 宽度；更值得先测的是 head stall 来源和 `B03` 的 branch update 丢失。
恢复延迟方面，`B01` 面向 branch 等待 ROB head 的大头，`K01` 只覆盖 commit 后的一拍，
二者应分开核算。`C03` 属于低概率但高破坏性的 correctness audit，测试优先级高于性能
优化。历史 critical-path 数字只说明这些寄存边界曾经重要；2nd pass 已有
`60fba481...` 的 matching 数据，当前 top-10 不含 ROB/commit 路径，但 44 ps 余量仍要求
每个相关候选重新实现。

### 2026-08-04：内存乱序执行、LSQ 与 Store-to-Load Forwarding

寄存器依赖在 rename 后可以用 `psrc/pdst` 精确表示。内存依赖更难：两条访存指令在
地址算出来之前，硬件并不知道它们是否访问同一个位置；Store 的地址和数据还可能在
不同周期就绪。LSQ 的任务就是在尽量并行的同时回答三个问题：

1. 这条 Load 前面有没有可能写同一字节的 Store？
2. 若有，最新值应该来自哪个 Store，还是应该等待 cache？
3. 发生异常、误预测或 barrier 时，哪些请求可以撤销，哪些副作用必须保留？

当前设计选择了偏保守的 memory disambiguation：Load 可以越过地址已知且确认不重叠的
老 Store，但不能越过地址未知的老 Store；也没有“先猜不相关，猜错后 replay”的机制。
这减少了恢复协议的复杂度，代价是某些独立 Load 会等待。

#### 一条 Load/Store 同时存在于哪些结构

当前 LDQ、STQ、SDQ 均为 8 entries，P3 是唯一 LSU/AGU 端口。

| 动态阶段 | Load | Store |
| --- | --- | --- |
| rename/allocate | 分配 ROB + LDQ index + `pdst` | 分配 ROB + STQ index；普通 Store 无 `pdst`，SC 有结果 `pdst` |
| dispatch | 进入 P3 IQ | 地址部分进入 P3 IQ，数据部分同时进入 SDQ |
| issue | P3 读取 base，AGU 算虚拟地址 | P3 用 base 算地址；SDQ 独立等 data producer 并读 PRF |
| LSQ | 翻译、顺序检查、转发或发 L1D/uncached 请求 | 汇合地址、数据、翻译、完成和 committed 状态 |
| ROB commit | 释放 LDQ；LL 可更新 reservation | 标为 committed；稍后才真正写 cache/AXI |

LDQ/STQ index 在 rename 时就被固定，并与 ROB pointer 一起成为 entry 身份。整组三路 rename
只有在 ROB、FreeList、dispatch queue 和 LSQ allocator 都有容量时才接受，所以不会出现
ROB 分配成功而 LDQ/STQ 没有槽位的半完成状态。

#### 为什么 Store 要拆成地址与数据两条路径

考虑：

```text
I0: add.w  r5, r6, r7
I1: st.w   r5, r3, 0
```

Store 地址只依赖 `r3`，数据依赖 I0 产生的 `r5`。如果把两个源一起放在 P3 IQ，地址也要
等 I0 完成，白白延后 TLB 翻译和后续冲突判断。当前实现将其拆开：

```text
P3 IQ: 等 base -> AGU -> STQ.addressReady / virtualAddress
SDQ:   等 store-data pdst wakeup -> PRF read -> STQ.dataReady / writeData
```

两条路径用相同的 ROB pointer 与 STQ index 汇合。LSQ 还会再次检查目标 entry valid、
pointer 相等且对应字段尚未写入，避免 flush 后的旧地址或旧数据污染复用 slot。

普通 cached Store 在地址已翻译、数据已到达后就可以向 ROB 报 complete；此时没有写 cache。
它到 ROB head 后先 retire，STQ entry 变为 committed，随后才按 `storeHead` 顺序送给 L1D。
因此 Store 执行完成不会造成投机内存副作用。

SDQ 自身还有注册的 PRF-read output，若下游暂时不接收会稳定保持 request。它与 Load 的
第二 PRF read 共用 P3 读口，所以此前记录的 `D01` 和 unused-source `R01` 也会影响访存：
SDQ 满可能阻塞 Load dispatch，无用的 Load source2 还可能制造读口冲突。

#### Load scheduler 如何找到下一条工作

LDQ entry 在 ROB commit 前一直占用。scheduler 从 `loadBase` 开始，用旋转优先选择找到
程序顺序最老的 pending Load：

```text
pending = valid && !requestSent && !completed
```

选中的 index 和宽 payload 会先寄存为 `ScheduledLoad`。这个 timing cut 让后续 8 个
STQ 的年龄/地址比较不再直接读取一个大范围动态 mux；AGU 与 scheduler 同拍命中新 entry
时有专门旁路，所以常见的 address-to-translation 路径没有额外再停一拍。

“最老 pending”与“最老未完成”有重要区别。Load A 的 cache request 一旦被接受，
`requestSent=1`，scheduler 就可以选择 Load B，而无需等 A 的 miss response。L1D 有 4 个
MSHR 与 8 个 load waiter，response 根据完整 ROB pointer 和 recovery epoch 回到对应 LDQ
entry。因此不同 cache miss 可以并发并且乱序返回。

如果 A 还没有发出，只是被某个 Store 顺序条件挡住，它仍然是最老 pending，B 也不能被
选择。这形成 `L02` 的 head-of-line blocking：

```text
S0: 老 Store，地址未知
L1: Load A，被 S0 阻塞
L2: Load B，实际访问完全独立的地址
```

当前先等 S0 地址明确，再处理 A，然后才轮到 B。更激进的 ready-load scheduler 可以检查
B 对所有 older Store 的安全性并先发 B，但会从“1 个 Load x 8 STQ 比较”扩大为“多个
LDQ candidate x 8 STQ 比较 + 年龄选择”，很容易重新成为 FPGA 关键路径。

#### 当前 Store/Load 顺序判定的四种情况

对 scheduled Load，当前并行检查 8 个 STQ entry。`older` 由 ROB generation pointer 的
环形年龄关系确定；地址和 byte mask 目前使用虚拟地址 word 比较。

| 老 Store 状态 | 当前动作 | 原因 |
| --- | --- | --- |
| 地址未知 | 阻塞 Load | 它可能写 Load 的任意字节 |
| 地址已知、word 或 byte mask 不重叠 | 允许继续 | 已知没有 RAW memory dependency |
| 同 word 且重叠，但 data 未就绪 | 阻塞 | 正确值还没有产生 |
| 一个 Store 的 mask 完整覆盖 Load，data ready | 直接 forwarding | Load 应看到老 Store 的新值 |
| Store 只覆盖 Load 的部分 byte | 阻塞 | 当前没有 cache bytes + Store bytes 合并 |
| 多个 Store 都完整覆盖 | 阻塞 | 当前要求 `forwardingCount == 1`，没有选最年轻 Store |

Load 真正允许前进的条件可概括为：

```text
loadOrderClear = no unknown older store
              && no partial overlap
              && no overlapping store with pending data

one covering store -> forward
zero covering store -> access cache
two or more covering stores -> wait
```

这里的“转发 producer”必须是程序顺序上距离 Load 最近、且覆盖对应 byte 的 Store。例如：

```text
S0: st.w  [A] = 0x11111111
S1: st.w  [A] = 0x22222222
L2: ld.w  [A]
```

L2 应读到 S1。当前看到两个 covering Store 后选择等待，直到老 Store按序进入 cache，
然后再读取。这是正确但保守的行为。

更一般的逐字节例子是：

```text
S0: st.b [A+0] = 0x11
S1: st.b [A+1] = 0x22
L2: ld.hu [A]              -> 应得到 0x2211
```

当前两个 Store 都是 partial overlap，L2 等待。`L01` 可为 Load mask 的每个 byte 从所有
older Store 中选择最近 producer；没有 Store producer 的 byte 由 cache 提供，最后合并。
它能减少 byte/halfword、栈对象和连续小 Store 的等待，也会引入 8 STQ x 4 byte 的年龄
优先网络和多路数据 mux。历史实现中 LSQ 的 8-Store overlap cone 曾经达到完整 SoC
10.148 ns 数据路径并成为 WNS；后来通过 scheduled payload、request buffer 和注册释放
切断。这个数字只作历史结构证据，不能代表当前 bit 的时序。

#### `C04`：虚拟地址不等价于物理地址

当前 overlap/forwarding 比较采用：

```text
store.virtualAddress[31:2] == load.virtualAddress[31:2]
```

L1D 的 lookup、tag、MSHR line identity 实际全部使用 physical address。若操作系统把同一
物理页映射到两个不同虚拟页，就可能出现：

```text
VA1 = 0x4000 -> PA = 0x10000
VA2 = 0x8000 -> PA = 0x10000

S0: st.w [VA1] = 7
L1: ld.w [VA2]
```

LSQ 按 VA 判断“不相同”，L1 便可能在 S0 commit/写入 L1D 前读取同一 PA 的旧值。当前
没有在 S0 的物理地址得到后检测 younger Load violation，也没有对应 replay/squash 通路。
Linux 的共享页、重复 `mmap`、COW 前后映射等场景使 synonym 不能作为不可达状态忽略。

这项 `C04` 来自静态 RTL 审计，尚未运行动态复现，因此先标为最高优先级待定向测试。
最小测试应让两组不同 VPN、相同 page offset 翻译到同一 PPN，并验证 Store->Load 的结果，
同时覆盖 cache hit/miss、byte mask、Store 地址先/后就绪和 TLB/DMW。

可能的修复层级为：

1. 利用最小页大小内 page offset 在 VA/PA 中不变；offset 不同可立即判定无别名。
2. offset 相同而 VPN 不同时，保守等待相关老 Store 取得物理地址并做 PA 比较。
3. 为多个 Store 更早、甚至乱序进行翻译，减少上述等待；translation identity 和异常顺序
   仍由 ROB 保证。
4. 最激进的是 memory-dependence speculation：Load 先发，老 Store PA 随后命中时 replay
   Load 及其消费者。当前没有 selective replay/checkpoint，复杂度接近新的恢复机制。

在正确性证明完成前，不应先实现 `L01/L02`；它们扩大旁路范围后会增加 alias 错误的暴露面。

#### Cacheable 与 Uncached Store 为什么流程不同

cached Store 的地址翻译异常在执行阶段即可进入 ROB；正常 cached Store 则先完成并退休，
再向 L1D 发请求。L1D 接受写请求后，LSQ 用注册的 `acceptedStoreIndex` 在下一拍释放 STQ
slot，这个 sidecar 是为了切断 cache ready/MSHR arbitration 到动态 entry clear 的长路径。

uncached Store 不能依靠 cache 吸收，也可能从总线返回错误。当前只允许它在自身位于
ROB head 时发出：

```text
uncached && !completed && !requestSent && robPointer == robHeadPointer
```

它等待 B response 后才向 ROB 报 complete，若总线错误则形成精确 ADEM；随后才能 commit。
请求一旦被 hierarchy 接受，外部写已经不可随意取消。若此时发生 flush，LSQ 将它视为
irreversible，保留 entry、继续消费 response，但不会把旧 epoch completion 注入新窗口。
还没被 hierarchy 接受的 buffered uncached write 仍是 speculative，可以随 flush 丢弃。

这组规则保证 MMIO 写不会从错误路径泄漏，也保证设备写错误在 Store 退休前可见。性能上，
SUC 访问必须串行，不能用 cacheable Load 的多 MSHR 方法扩大外部并发；ROB 是否一定要等到
B response 才退休，则取决于是否保留精确总线错误、以及退休后能否继续禁止年轻访存。这个
问题需要把“允许 CPU 继续做纯计算”和“允许下一笔 MMIO/内存请求发出”分开讨论。

#### 回访：Uncached Store 为什么阻塞 commit，以及能怎样优化

LA32R r1p04 的存储访问类型只有：

| MAT | 类型 | 核心约束 |
| ---: | --- | --- |
| 0 | SUC，Strongly-ordered UnCached | 直接访问最终存储对象；不可推测；访存严格按程序次序；当前访存彻底完成前不能开始下一访存 |
| 1 | CC，Coherent Cached | 可以由一致 cache 服务 |
| 2/3 | 保留 | 软件不应使用，不能当作 WUC 优化空间 |

因此当前翻译单元用 `memoryAttribute === 0` 产生 `uncached` 是正确的编码判断。手册提到
weakly-ordered uncached 是为解释二者差异，精简版并未给 WUC 分配 MAT 编码。

当前一条 uncached Store 的关键时间线是：

```text
AGU / store data / translation 可提前完成
        |
等待自己成为 ROB head                  <- 防止错误路径设备副作用
        |
hierarchy 接收并保存一个请求 token
        |
L1D exact writeback-invalidate
        |
L2 exact writeback-invalidate           <- 保住可能存在的 cached dirty alias
        |
等待 cached AXI 事务排空到 busIdle
        |
AXI AW -> W -> 等 B response
        |
LSQ completion；B error 当前映射为精确 ADEM
        |
ROB complete -> commit
```

可将单条 Store 对总周期的暴露损失近似记为：

```text
S_exposed = L_L1_alias + L_L2_alias + L_bus_drain + L_AW/W/B + L_completion
            - 可与年轻独立执行重叠的周期

Delta_CPI ~= uncached_store_per_instruction * S_exposed
```

所以它可能很严重，也可能几乎不影响 perf20。Linux early console、轮询 UART 或逐寄存器
设备驱动会高频触发；纯计算 benchmark 的 hot loop 通常很少访问 MMIO。`IPC < 1` 不能直接
归因给 uncached Store，必须统计 ROB head stall 原因和地址分类。

##### U01：先消除不必要的 alias maintenance

当前所有 uncached Store 都依次查 L1D 和 L2，即使地址是固定 UART/中断控制器等设备 PA，
这些 PA 正常情况下从未以 CC 类型进入 cache。若平台地址图和 Linux 映射能形成可执行合同，
可将请求分成：

```text
device PA, cache alias impossible -> 跳过 L1D/L2 maintenance
uncached normal memory / alias possible -> 保留 writeback-invalidate
```

这是最可能同时降低单次延迟和 hierarchy 全局停顿的方向。但当前
`SharedCacheHierarchySpec` 已把“uncached Store 必须写回并失效 cached alias”固定成测试，
所以只能添加经过证明的设备范围快路径，不能全局删除维护。

##### U02：posted retirement 能隐藏什么

可以在 Store 到达 ROB head、翻译和所有异常都已确定后，把它转入一个不可撤销的 SUC token，
让 ROB 先退休这条 Store。token 收到 B 前仍阻止所有后续 Load/Store、DBAR/IBAR 和需要访存
的异常处理；独立 ALU/branch 可以继续提交。这样保留外部 SUC 顺序，又可用后续纯计算覆盖
一部分 B latency。

SUC 下一个 token 已足够：上一笔未彻底完成时，下一笔访存本来就不能开始。做成 4-entry
MMIO store queue 不会提高合法的连续 Store 发射率。典型轮询代码下一步马上读取设备状态，
也几乎没有可隐藏工作；只有 Store 后有一段独立计算时收益明显。

最大的合同问题是 BRESP error。当前实现和测试要求错误成为该 Store 的精确 ADEM；Store
先退休后就无法回到它的 PC 精确报错。官方 r1p04 并未规定把 AXI BRESP 错误映射为 ADEM，
新版手册甚至不再要求执行阶段访存产生地址错例外，因此可以审计固定 SoC 对合法/非法地址
的 response 合同，再决定以下二者：

1. 保留精确 B error：继续等 B，U02 不成立；
2. 合法设备窗口保证不返回 error：只对这些窗口 posted，故障改由 SoC fatal/异步机制处理。

第二种不能凭“板上通常不报错”采用，必须由固定平台 RTL、官方测试和 Linux fault 预期共同
证明。flush、interrupt、reset 和关机也必须保留或排空 token。

##### U03：缩短到达 AXI 的排队时间

`AxiLineBridge` 只在 `busIdle` 时更新 `uncachedWait`。如果 SUC 请求在四个 cached read ID
仍活跃时出现，新的 cached refill 仍可能继续被接受并延长排空。可以在看到 pending SUC 时
立即锁存 drain 请求，停止接受新的 demand refill，允许既有事务和该 Store 的 dirty-alias
writeback 完成，然后把第一个空闲时隙交给 SUC。

它不会让 SUC 与年轻 read 并发；价值在于减少无年龄信息的年轻 cached 请求插队。设计必须
区分“前置 maintenance 必须完成的 writeback”和“可以暂缓的新 demand”，否则会自己封死
uncached Store。

##### 为什么 DMA 不是这里的直接答案

DMA 适合把一段 DDR 数据与网卡、存储控制器等设备成批搬运。CPU 仍要通过 uncached MMIO
写 descriptor/doorbell、读 completion，并在非一致 DMA 下执行 cache maintenance。因此：

- 对大包网络、块设备或 NAND，DMA 可把每个 word 的 CPU MMIO 循环变成少量控制访问；
- 对普通 `st.w`、UART 单字符寄存器写、页表和 cacheable memory Store，DMA 无法替代 LSU；
- 小传输还会被 descriptor、cache clean/invalidate、doorbell 和 interrupt/polling 开销抵消。

Chiplab 确实包含 `IP/DMA/dma.v`，但固定 `nscscc-team/soc_top.v` 将 APB DMA 控制和请求/应答
端口绑为常量，没有把这颗通用 DMA 作为当前官方目标的可用 master。平台文件又是固定输入，
所以不能把“Chiplab 目录中存在 DMA RTL”当作本项目已有 DMA。若以后依赖 MAC 自带 DMA，
它属于完整 SoC/驱动/非一致 cache 合同，应按 Linux 网络吞吐单独评估，不是 CPU commit
路径的通用优化。

##### 新增的 C06 顺序审计

回看 LSQ 后还发现一个必须先验证的风险：older uncached Store 在成为 ROB head 前不能发出，
但年轻 Load 对“地址已知且不重叠”的 Store 可以绕过；如果该 Store 是 SUC，年轻 Load 可能
先到 bridge。bridge 只能保持请求到达次序，无法恢复原程序次序。

`6bbca9b...` 已把 uncached Store 改为 ROB-head 才发出、等待匹配 B response 才 complete，
并补了 pre/post-accept flush 与 error 测试；它没有改变上面的 Load order predicate。该
predicate 仍在 Store 翻译结果/MAT 未知时按 VA word/byte overlap 放行不重叠的年轻 Load，
所以这些新测试关闭的是“单条 uncached Store 的精确完成”，没有关闭 C06。

最小测试序列是：

```text
I0: 一个延迟较长、暂时占住 ROB head 的操作
I1: st.w [SUC_A] = value
I2: ld.w rd, [SUC_B]          // A、B 不同地址
```

观察 I2 是否在 I1 的 AW/W/B 前进入 AXI。若复现，先按 C06 修复：Store 的 MAT 未确定时不能
被年轻访存乐观越过；确认是 SUC 后，年轻访存必须等它彻底完成。更复杂的性能恢复要建立在
明确的 memory-type/age gate 上，不能由 posted write 绕开。

推荐先增加仿真采样，不改数据通路：按 PA 记录 uncached Store 数量，以及
`ROB-head -> L1 maintenance -> L2 maintenance -> bridge accepted -> AW -> W -> B ->
completion -> commit` 各时间戳；同时记录 head blocked 总周期、其后独立非访存数和 pending
后新接收的 cached AR。
这些数据可分别给出 U01、U02、U03 的严格收益上界。

#### Recovery epoch 与 Memory epoch 解决不同问题

两者都是 8 bit，但用途不同：

| 字段 | 何时变化 | 防止的问题 |
| --- | --- | --- |
| recovery epoch | 每次 branch/exception/global flush | 被杀死请求的迟到 response 命中复用后的 ROB/LDQ slot |
| memory epoch | 接受 DBAR/IBAR/CACOP 时 speculative 增加，退休时 committed 增加 | barrier 后的访存越过尚未完成的 barrier |

cache request/response、L1D MSHR waiter 和 uncached read context 都携带 recovery epoch。
LSQ 只有在 ROB pointer 与 epoch 同时匹配时才接受 Load response。这项机制曾修复真实 DDR
延迟下 quick_sort 暴露的旧 response slot-reuse 错误。

memory epoch 则建立 barrier 两侧的门：同一 rename group 中，barrier 之后的 Load/Store
直接取得递增后的 speculative epoch；LSQ 只允许 `entry.memoryEpoch == committedMemoryEpoch`
的 entry 产生真正的 memory request。当前 selected Load 的翻译也在这个门后，head Store
的地址翻译可以提前，因为翻译本身没有内存副作用，最终写请求仍受 epoch 门控。barrier
到 ROB head 后等待所有 older Store 与 L1D/MSHR/L2/AXI 安静；DBAR 完成顺序点，IBAR
还执行 L1D/L2 writeback-invalidate、L1I invalidate 和最终 drain。barrier retire 后
committed epoch 前进，年轻访存才获得通行。

flush 时 speculative memory epoch 恢复为包含同拍已退休 barrier 的 committed 值，避免
错误路径 barrier 永久封锁或放开访存。这里是“按顺序提交 barrier + 用 epoch 门控请求”的
实现，不需要在每个 Load 上动态遍历全部 barrier。

#### LL/SC 在 LSQ 中的额外约束

LL 必须真正访问 cache/内存，当前禁止从 STQ forwarding，以便得到对应物理 cache line。
Load response 将物理 line identity 放进 completion side-effect，只有 LL 正常退休时才建立
reservation。

SC 在地址翻译后按物理 cache line 检查 reservation，并且 uncached SC 直接失败：

```text
reservation valid && same physical cache line && cacheable -> SC success
otherwise                                             -> SC failure
```

失败 SC 写回 0，不产生内存请求；成功 SC 写回 1，并像 committed Store 一样在退休后写入
cache。`6bbca9b...` 把 reservation identity 从历史的 16-byte 粒度改为与当前 L1D 相同的
64-byte line，并固定了 uncached LL 不建立 reservation、uncached SC 失败且不写内存、SC
退休清 reservation 的行为。当前项目将这组语义标为“single-core noncoherent satisfied”；
平台不支持外部 coherent invalidation，因此它不是可直接外推到多核/一致 DMA 的 LL/SC
合同，Linux atomic 仍应作为系统级验收的一部分。

#### Flush 时 LSQ 哪些状态保留

恢复边沿的处理可以总结为：

| 状态 | flush 后动作 |
| --- | --- |
| 所有 speculative Load | 删除；迟到 response 由 recovery epoch 拒绝 |
| 未提交普通 Store | 删除 |
| committed cached Store | 保留并按 storeHead 排空 |
| 已被接受的 uncached write | 保留为 irreversible，等 response 后释放 |
| 尚未接受的 uncached write | 删除 |
| outstanding translation | 保留 cancel token，消费迟到 response，避免共享 translator 卡死 |

只要存在保留 Store，`storeDrainBusy` 会暂停新的 rename；allocator 可以清零并在 drain 完成
后从空队列重新开始，不会让新 epoch 分配覆盖保留 entry。这个全暂停简单可靠，但恢复后
若有很多 committed Store 尚未排空，会增加前端重启损失。它的优化空间应通过
`drainAfterFlush` 周期数衡量，而不能允许新 speculative Store 与未知保留布局直接混用。

#### 当前测试覆盖与缺口

已有 LSQ 定向测试覆盖：单一 covering Store forwarding、unknown older Store 阻塞、多个
outstanding Load 按 ROB tag 返回、memory/recovery epoch、committed Store 跨 flush、
translation cancellation、cache/uncached error、backpressure、misaligned、LL/SC 和
irreversible uncached write。新增测试还覆盖 uncached Store 必须等匹配 B、B error 到精确
ADEM、accept 前 flush 可取消、accept 后 flush 只 drain 不产生 stale completion，以及
uncached LL/SC 不建立/消费 reservation。SDQ 测试覆盖等待 wakeup 和稳定保持 PRF request。

本阶段没有找到以下直接测试：

- 不同 VA 映射同一 PA 的 Store->Load alias；
- older Store 的 MAT/translation 尚未确定、后来成为 SUC 时，年轻访存不会先到 AXI；
- 两个 covering Store 应选择最年轻值；
- 多个部分 Store逐字节组成一个 Load；
- 最老 Load 被 Store 挡住时，更年轻 safe Load 是否可先发；
- 把这些情况与 flush、miss、随机 backpressure 组合的 reference-memory differential。

前两项分别是 `C04`、`C06` 正确性 gate；multiple/partial forwarding 当前行为虽然保守，
仍应增加测试，防止未来
优化误把“等待”改成错误转发。推荐建立一个小型逐字节 reference memory + ordered Store
buffer 模型，随机生成地址就绪顺序、data 就绪顺序、byte/half/word、alias 翻译、response
乱序与 flush，比只增加几个固定波形例子更容易覆盖组合边界。

#### 本阶段性能判断与测量顺序

1. 先完成 `C04` 和 `C06`。两者最保守修复的 cycle cost 都要测，但正确性不能由低 alias
   或低 MMIO 频率豁免。
2. 对每个 Load 记录 `selected -> translation -> request/forward -> completion -> commit`；
   blocked reason 分为 unknown address、pending data、partial overlap、multiple covering、
   translation busy、request buffer/cache backpressure、memory epoch 和 completion arbitration。
3. `L01` 的离线收益上限等于 multiple/partial-overlap blocking 中本可由逐字节最新 Store
   完整构造结果的周期。先用软件模型重放，不立即把 32 路 byte select 接进 LSQ。
4. `L02` 只统计“oldest blocked 且至少一个 younger safe、MSHR/translation/cache 有 credit”
   的周期。若 MSHR 已满或 younger 也冲突，跳选没有收益。
5. `L04` 比较最老待翻译 Load/Store 的 ROB pointer，并单独统计 age-aware 选择若推迟 Load
   会增加多少 load-use 等待。只计算移出 ROB-head 的 Store 翻译拍数会高估收益，因为被推迟
   Load、ATU 带宽和乱序隐藏量也会变化。
6. `L03` 由容量事件决定。BRAM 余量不能直接支持扩容，因为 LDQ/STQ 的 hot state 和
   associative compare 主要消耗 FF/LUT；从 8 到 16 还会加重地址年龄比较和动态 mux。
7. 同时统计 P3 issue、AGU accept、translation request、L1D request、Load response、
   Store drain 的每拍利用率。第二 AGU 只有在 P3 经常饱和且下游也有双入口能力时才有意义。
8. 最新已完成的 H01 route 最差路径重新落在 LSQ Store/completion 到 ROB epoch qualification，
   78% 为 routing；当前组合的 matching route 仍在运行。L01/L02/L03/L04 都必须按新结果复核，
   不能沿用更早的 F03 或 LSQ 路径名预判。

2026-08-05 的当前组合（CPU `758181a`、RTL `04d6e4b2...`）在三个 200 ms random-AXI
Linux seed 中呈现稳定信号：每个约一亿周期窗口有 `9.718M--9.765M` 个零退休周期归因为
Store translation，其中 `4.657M--4.679M` 位于 head translation request fire，
`4.685M--4.706M` 位于 response fire；`active_other_store` 只有 `302--316` 周期。源码对应关系
是：`headStoreNeedsTranslation` 无条件优先，lookahead 只有在 `!headStoreNeedsTranslation &&
!loadNeedsTranslation` 时才选 Store。持续的 Load translation 因此会饿住 lookahead，直到
Store 到 head。这个证据把 `L04` 提升为 age-aware A/B，而不等于可直接消除全部约 9.7M
周期；实验必须同时报告被推迟 Load 的数量和总周期变化。

本阶段结论：当前 LSQ 已具备真实的 nonblocking Load、4-MSHR 并发、地址/数据解耦 Store、
精确 uncached、双 epoch 和 committed-Store recovery，远超简单顺序 LSU。它的性能取舍偏向
“先证明安全，再允许越过”：单 Store完整覆盖可转发，未知/部分/多 Store关系会等待，
blocked oldest Load 还会挡住年轻 Load。`L01/L02` 因此有明确理论价值，但第一优先级是
`C04` 的物理别名正确性和 `C06` 的 SUC 程序顺序。当前正确性门禁闭合后，`L04` 比扩容
LSQ 或增加第二 LSU 更适合先做：它复用现有单 translation owner，且已有稳定的 head-stall
证据。LSQ 扩容与双 LSU 暂无足够测量支持，仍可能以更差的路由和 Fmax 换来用不到的容量。

### 2026-08-04：L1I/L1D/L2、MSHR、Critical Return 与 AXI

本项目的 cached memory hierarchy 是：

```text
Frontend ----> 8 KiB 2-way L1I --\
                                   > 4-entry global read-ID router
LSQ ---------> 8 KiB 2-way L1D --/          |
                                              v
                                      64 KiB 2-way shared L2
                                              |
                                      AXI3 -> DDR controller
```

L1I、L1D、L2 都是 PIPT，line size 都是 64 B。一次内部 refill beat 为 64 bit，所以一条
line 有 8 beats；外部 AXI data width 为 32 bit，一条 line 对应 16 次 AXI R/W transfer。

| 层级 | Ways x Sets x Line | 容量 | Offset / Index / Tag |
| --- | --- | ---: | --- |
| L1I | 2 x 64 x 64 B | 8 KiB | 6 / 6 / 20 bits |
| L1D | 2 x 64 x 64 B | 8 KiB | 6 / 6 / 20 bits |
| L2 | 2 x 512 x 64 B | 64 KiB | 6 / 9 / 17 bits |

地址翻译先给出 PA，cache 用 PA 的 offset 选择 line 内 byte/word，用 index 读 set，再比较
tag 判断哪个 way 命中。PIPT 避免 cache synonym 本身的问题；上一阶段 `C04` 位于 cache
之前的 LSQ 顺序判断，因此 L1D 正确使用 PA 并不能自动修复 LSQ 的 VA alias。

#### 四个需要分开的 cache 性能量

学习 cache 时，至少要分别看：

| 指标 | 问题 | 当前主要决定因素 |
| --- | --- | --- |
| hit latency | 一个已接受的 hit 多久返回 | 同步 BRAM、tag compare、way select、response register |
| hit initiation interval | 连续 hit 能否每拍启动 | controller 是否允许 lookup pipeline |
| miss exposed latency | demand 从 miss 到关键数据返回多久 | L2 hit/miss、AXI、critical return、ROB/MLP 能否隐藏 |
| miss concurrency | 能同时保留多少独立 miss | L1 context/MSHR、global ID、L2 MSHR、AXI ID 和 waiter |

当前 L1 使用同步 `Mem` 保存 tag 和 512-bit line data。请求在一个边沿发起同步读，下一阶段
比较 tag、选择 way，再把响应注册给 frontend/LSQ。L1I controller 只有 `idle/lookup` 一个
lookup context；L1D 也用 `lookupPending` 串行新的 tag lookup。普通 hit 请求的可持续启动
间隔因此约为两拍，即使 hit latency 的某些逻辑已经并行化。

这解释了 `H03`：把 lookup context 做成流水或加一个 skid/context slot，可以争取每拍接受
一个 hit。它在两个方向上的前置条件不同：

- I-side 还受 frontend 单 translation/request context 限制，需与 `F01` 联合修改；
- D-side 还受单 P3、LSQ scheduler 和 request buffer 限制，只有连续 L1D hit 请求确实到达
  cache 门口时，提高 cache lookup II 才能转化为周期收益。

#### L1I：单 miss context，但已经支持 critical-group early response

L1I 一次返回 16 B，也就是四条指令。cache hit 时，它为两个 way 并行选择 fetch group 并
执行 branch predecode，tag compare 结果只选择已经形成的候选。这项结构避免串联：

```text
tag BRAM -> compare -> 512-bit way mux -> group select -> branch target add
```

现有结构把每个 way 的 group select/predecode 与 tag compare 并行，曾有效缩短 hit 路径。

L1I miss 时只有一个 request/refill context：

1. 保存 request 和 victim way；
2. 向 global router 请求 64 B line；
3. 收集 8 个 64-bit beat；
4. 请求的 16 B group 对应两个 beat，这两个 beat 到齐便立即响应 frontend；
5. 其余 beat 继续到达，完整 line 最后安装进 cache。

因此当前已经有 early restart，不需要等全部 64 B 才重新取指。若 redirect 杀死这次 fetch，
L1I 不再发送旧 response，但允许 refill 完成并安装；之后若正确路径又回到同一 line，可以
直接 hit。这个选择避免无法取消的下层事务浪费，也要求 kill 只杀 response owner，不能
破坏 line-fill identity。

requested group 返回以后，L1I 还能接受同一正在 refill 的 line 内另一个 fetch group；若
对应 beat 已经到达，就通过 replay slot 返回。不同 line 的新 miss仍需等待本次 install，
所以它不是多 MSHR I-cache。

#### I-side 为什么固定从 beat 0 refill

L1I 向下层发送的 `criticalBeat` 当前固定为 0。L1D 会把 Load 所在 64-bit beat 作为
critical beat，L1I 则没有让 AXI WRAP burst 从 demand group 开始。

历史 A/B 在同一个 `func_lab19` 上得到：

| 方案 | 周期数 |
| --- | ---: |
| I/D 都从 beat 0；L1D 仍可在目标 beat 到达时 early response | 533,744 |
| 只启用 I-side critical-first | 536,336 |
| I/D 都 critical-first | 536,336 |
| 只启用 D-side critical-first | 533,744 |

I-side critical-first 在该 workload 退化 2,592 cycles，约 0.486%，所以当前保留 beat 0。
一种合理推断是：取指通常顺序消费整条 line，从中间开始 WRAP 虽让当前 group 更早，却改变
后续 group 到达顺序，并影响同-line replay；这个推断还缺按 group 的 miss trace 证明。
结论只适用于该历史 workload，后续若重审，应记录 miss 时 demand group、redirect、每组
first-use 时间和完整 perf20 分项，不能把“critical-first”当作天然更快。

#### L1D：四 MSHR、八 waiter 与 hit-under-miss

L1D 有四个 local MSHR 和八个 Load waiter。一次新 request 会先比较：

- 是否命中某个 active MSHR 的同一 physical line；
- 是否与 active miss 落在同一个 set；
- 是否有 free MSHR、free waiter 和 cache-array lookup credit。

对应行为为：

| 情形 | 行为 |
| --- | --- |
| 同一 line 的 Load | merge 到已有 MSHR，新增 waiter |
| 同一 line 的 committed Store | 把 byte mask/data overlay 到 refill buffer |
| 不同 line、同一 set | 等待 active owner install，避免两个 miss 选择同一 victim way |
| 不同 set | 可继续 lookup；已在途 miss 不阻塞该 set 的 hit |

这已经是真正的 nonblocking cache。四个不同 set 的 miss 可以同时等待，下一个独立 hit 也可
在 miss 下方执行。若 miss 需要替换 dirty L1D line，MSHR 先把完整 victim 写到 L2，再发
line read。

L1D 把 demand word 所在 64-bit beat 传为 critical beat。AXI 从该位置开始 WRAP，L2 把
返回 beat 流式传回；L1D 的 waiter 只检查目标 beat 的 `refillMask`。目标 beat 一到，Load
便取得其中低/高 32-bit word，不等其他七个 beat，也不等 cache install。这是
critical-word-first + early restart 的完整 data-side 路径。

同一 refill line 上若有 committed Store，L1D 为每个 byte 维护 overlay mask。Store data
可能在 refill beat 之前、之后或同拍到达；同拍时 Store byte 必须覆盖 DDR 的旧 byte。
历史 `my_memcmp` 曾暴露 byte mask 被重复移位的错误，现有测试专门覆盖不同 word lane 和
Store/refill 同拍，说明这里的 byte-level contract 是功能路径，不只是性能细节。

#### 四个 local MSHR 不等于四个纯 Data miss credit

L1I 与 L1D 先经过 `SharedReadMshrRouter`。它维护四个 hierarchy-global ID，记录 owner
是 instruction 还是 data，以及原 local ID。I/D 同拍请求按轮转公平选择，前面还有两项
注册 request queue，用来切断 L1-ready 到 L2-ready 的组合路径。

四个 global ID 是整个 cached-read hierarchy 的共同上限：

```text
1 个 L1I miss + 3 个 L1D miss = 4 个 global IDs
```

即使 L1D 本地有四个 free MSHR，此时第四个 Data line request 仍要等待。global entry 直到
最后一个返回 beat被所属 L1 接受才释放。增加 local MSHR 而不增加 router/L2/AXI identity
不会提高系统 MLP。

router 的两项 request queue 还能吸收连续 I/D miss，但它只保存待 L2 接受的请求，不增加
已经分配的四个 global identity。评估 MSHR 扩容必须同时观察：

- L1D local MSHR full；
- global router activeCount=4；
- L2 MSHR/set conflict；
- AXI lineActive IDs；
- 八个 Load waiter是否先耗尽。

#### L2：四 miss slots、同 set 串行、refill 流式下传

L2 是 64 KiB、2-way、四 MSHR。每个 read 使用 global ID 作为 L2 slot identity：

- L2 hit：捕获完整 hit line 到按 beat banking 的浅存储，从 critical beat 开始向 L1 连续
  返回 8 beats；
- L2 miss：必要时先写回 victim，再向 AXI 发 line read；AXI beat 一到就注册并流式下传，
  不等 L2 line install；
- 不同 set 的 lookup/refill 可以并发；同 set request 等 active owner install；
- response 输出有本地寄存边界，可持续每拍一个 64-bit beat，并切断 AXI/L2 状态到 L1
  的一部分长路径。

L2 仍只有一个 cache-array lookup port，read 与 L1D dirty line write竞争；dirty L1D write
优先。hit response stream 与 miss refill共享下行 beat port，外部 refill优先，L1 通过
ready 施加对应 owner 的 backpressure。

这里的并发主要隐藏 DDR latency，不能提供多个 L1 response port。若同拍多个 critical
Load beat ready，最终仍通过单一 L1D response和单一 LSQ completion path顺序返回。

#### 当前 L2 实际采用 write-through 接收 L1D eviction

L1D dirty eviction 以完整 64 B line write 进入 L2。当前 L2 的处理顺序是：

```text
L2 lookup
  -> 若 L2 victim dirty，先 victim writeback
  -> 无论本次 L1D line 在 L2 hit/miss，都把 64 B 写到 DDR
  -> 在 L2 安装/更新为 clean line
```

因此普通路径中的 L2 dirty bit基本不会被置位；dirty victim 状态机和 maintenance writeback
虽已存在，L1D writeback 接收仍按 write-through 工作。这样做让 DDR 始终拥有 L1D eviction
后的新值，协议简单，但 Store-heavy working set 只要在 8 KiB L1D 中发生 dirty eviction，
就产生一次 16-beat AXI line write，即使该 line 在 64 KiB L2 中仍有空间或已经命中。

`H01` 将这条路径改为真正 L2 write-back：L1D eviction 更新 L2 并置 dirty，只有 L2 自身
替换或 writeback-invalidate 时写 DDR。理论上可节省：

```text
saved DDR writes ~= L1D dirty evictions - dirty L2 evictions/maintenance writes
```

它是本阶段较有价值的周期候选，因为已有 L2 dirty metadata、victim writeback 和
writeback-invalidate 状态机，新增机制比新建一层 cache 小。仍需重证：

- uncached Store 写同一 PA 前，L1D/L2 dirty alias 必须先 writeback-invalidate；
- IBAR 自修改代码路径必须把 dirty data一路推到内存并清 I-cache；
- CACOP Index/Hit 模式不能丢 dirty line；
- AXI write error 后不能提前清 dirty ownership；
- 若平台存在 non-coherent DMA/外设观察，软件必须通过相应 maintenance 建立可见性。

#### AXI bridge：四路读并发，但 line write 与读全局串行

cached read 使用 AXI ID 4--7。每个 global MSHR ID 独立保存：active、半个 64-bit beat、
当前 beat index、beat count 和 error。AR 发出 16 个 32-bit transfer 的 WRAP burst，起始
地址为 critical 64-bit beat；两个连续 AXI word 合成一个内部 64-bit beat。不同 ID 的
R channel response可以交错，bridge 按 ID 重组。

其他 ID 为：

| AXI ID | 用途 |
| ---: | --- |
| 1 | cached 64 B line writeback |
| 2 | uncached instruction read |
| 3 | uncached data read/write |
| 4--7 | 四个 cached line read |

读侧已经具备四 outstanding contexts，AR 还有一级 staging。写侧只有一个 context，且
`memoryWriteReady` 要求整个 bus idle；line write 开始后也禁止新 cached read。虽然 AXI 的
R/W channel物理上独立，当前策略使以下情况串行：

```text
active read misses ----等全部结束----> line write ----等 B----> new reads
```

`H05` 可以增加 line-write buffer并允许 cached R/W 并发，或者至少让 pending write 不阻止
新的 read AR。收益取决于 `read blocked by write` 周期；若先采用 `H01`，line write 数本身
大幅下降，H05 的收益也会变化，因此实验顺序应先 H01、再 H05。

uncached traffic 继续保持全局有序。bridge 用 registered wait bit 在 cached 与 uncached
之间公平切换，uncached 只能在 bus idle 时启动。MMIO 语义优先于并发吞吐，不应直接复用
cached 多 ID 机制。

`6bbca9b...` 还关闭了一个与带宽无关但很关键的 response 仲裁问题：cached L1D response
与 uncached data response 同拍到达时，hierarchy 现在用一项 deferred-data-response slot
保存落选者，并核对 uncached AXI response ID。现有测试覆盖 collision、backpressure 与
错误 response ID。这证明“两个返回源同拍不丢 completion”，但没有增加第二个 LSQ
completion port，所以它不能解释为 data response 吞吐翻倍。

#### `C05`：Refill error 之后的 cache line 命运

三个 cache 都会累计 line-read error，但 install 时仍设置：

```text
writeEntryValid = true
```

L1D/L1I 的首次 demand response可以携带 error，L2 也会把 error 随 refill beat传给 L1；
完整 line 仍可能作为 valid cache entry留下，后续 hit固定返回 `error=false`。cached line
write 的 B error则只在 simulation assertion 中检查，综合硬件会结束 write context，原 dirty
数据已可能被释放。

这形成 `C05`。它的正确修复取决于平台错误合同：AXI RRESP 是逐 beat 的，若 demand word
本身成功而同一 line 另一 beat失败，已经 early-return 的 Load未必需要失败；但该残缺 line
不能作为完整 valid line缓存。合理的最低契约通常包括：

- 任一 refill beat error -> 不安装完整 line；
- 请求涉及的 beat error -> 对该 demand 报 precise fetch/load exception；
- 其他 waiter按其目标 beat及事务错误策略处理；
- cached write B error -> 保留 dirty ownership并重试，或进入明确的 fatal/machine-error
  路径，不能静默释放。

现有 cache 定向测试没有注入 cached line-read error，也没有验证 synthesized write-error
处理。官方 DDR 正常测试可能永远给 OKAY，所以这项审计不等价于已经复现比赛 workload
错误；它仍然优先于所有 cache 性能修改。

#### AMAT 还不够：乱序核关心 exposed miss penalty

经典平均访问时间可写成：

```text
AMAT = L1 hit time
     + L1 miss rate * (L2 hit penalty + L2 miss rate * DDR penalty)
```

乱序核最终损失的周期更接近：

```text
exposed penalty = miss latency
                - independent work overlap
                - overlap with other misses
                - critical-return saved tail
```

所以一次 100-cycle DDR miss 不一定让 CPU 停 100 拍。如果 ROB/IQ 中有独立工作、四个 MSHR
都在服务、critical word提前回来，部分延迟可以隐藏。反过来，Load正好位于 ROB head，或
其结果是长依赖链 producer，几乎全部 latency 都会暴露。

Cache 计数器至少需要：

- I/D request、hit、miss、same-line merge、same-set conflict；
- L2 hit/miss、dirty eviction、L1D writeback hit/miss；
- local/global/L2 MSHR occupancy 和 full cycles；
- critical beat arrival 到 full-line install 的尾部；
- miss 时 ROB head 是否等待该 Load/fetch 是否 empty；
- AXI AR/R/AW/W/B 利用率、bytes 和各类 backpressure。

只有 miss rate 而没有 exposed stall，容易高估 cache 容量；只有 IPC 又无法区分前端、数据
依赖和 DDR。

#### 容量、相联度与 FPGA BRAM 映射

standalone 层次资源报告提供了一个有意思的结构信号：

| 模块 | LUT | FF | RAMB36 | RAMB18 |
| --- | ---: | ---: | ---: | ---: |
| L1I 8 KiB | 2,831 | 1,003 | 14 | 4 |
| L1D 8 KiB | 5,342 | 2,068 | 14 | 4 |
| L2 64 KiB | 4,828 | 3,699 | 14 | 4 |

这不是完整 SoC placed resource，也不能继承到新 RTL；它说明当前 `Mem(512 bits, sets)` 的
映射受 BRAM 最大端口宽度支配。8 KiB L1 深度只有 64 sets，但为一次读出完整 512-bit line，
横向并联了许多 BRAM，深度大量空闲；64 KiB L2 的 512 sets 更充分利用相同 primitive 深度。

由此产生两个不同方向：

- `H02` 先 sweep L1 sets，例如 64/128/256/512，综合后确认某些容量点是否几乎不增加
  RAMB primitive；set 增加保持 2-way，不扩大 hit-way mux，通常比先做 4-way 更适合 FPGA；
- `H04` 把 data array改成较窄 word/fetch-group/beat banks，普通 hit只读需要的数据；dirty
  victim和 maintenance 用多拍 gather，refill用多拍 scatter。它可提高 BRAM 深度利用率，
  也为更大 cache 或其他结构释放资源。

`H04` 不一定直接提高性能：一次 dirty eviction可能多读若干拍，控制状态更多；它的价值是
缩窄 hit 数据路由和重分配 BRAM。当前器件 BRAM 总量仍有余量，所以优先做无需 RTL 的
H02 参数综合与 miss trace，再决定是否值得重构 array。

replacement 当前是按 set 的简单 round-robin/next-way，在 cache write/install 时更新，read
hit 不更新真正 LRU。2-way 下增加 hit-aware pseudo-LRU 逻辑不大，但只有 conflict-miss trace
表明相同少数 set反复淘汰热点 line 时才值得单独实验；扩大 sets往往更直接，也更少增加
way-select 时序。

#### Prefetch 应放在哪一层

`H06` 最保守的起点是 instruction next-line prefetch，因为顺序取指的地址模式清楚。当前
L1I 只有一个 refill context，直接向 L1I 注入 prefetch可能挡住 demand；更合适的首个实验
是利用 global ID空闲时预取到 L2，demand 到达后成为 L2 hit。需要 branch redirect 后可丢弃、
demand priority 和 MSHR/AXI 节流。

data prefetch只有在 perf trace出现稳定 stride/stream、DDR latency又实际暴露时再设计。
在 2-way 8 KiB L1D 上，错误 prefetch很容易挤出有用 line；准确率高也不代表及时，太晚的
prefetch仍与 demand争带宽。记录 accuracy、coverage、timeliness、pollution 和
`demand blocked by prefetch`，缺一项都无法判断收益。

#### 历史 miss-refill 时序路径：F02

同平台历史 `8594150...` 的 100 MHz perf 实现 setup WNS 为 `-0.225 ns`。最差路径从 L2 refill
输出的 MSHR/beat选择经过 I-side router、L1I requested-group选择和 branch predecode，终点为
`response_predecode_1_target_reg[29]`：数据路径 10.328 ns，其中 route 7.933 ns，占 76.8%。

hit-side 已有 per-way parallel predecode；这条路径属于 miss refill response。`F02` 可以在
L1I refill ingress 或 critical-group assembler后增加窄的 request/group register，再做
predecode；代价主要是 I-miss early response增加一拍，而普通 hit不变。另一个方向是对四个
fetch group预先并行 predecode，再在近端选择，但 LUT和布线会增加。

该旧网表只差约 0.225 ns 加必要 margin，局部寄存边界可能比大改 cache hierarchy更合适；
route 占比很高，具体收益仍依赖 place/route。当时 `60fba481...` 已完成新的 100 MHz
implementation，F02 不在当前 top-10；当前 cache 相关次差路径是 L1D refill-mask 到
response-data 的宽 mux，而全局 WNS 是 F03。F02 因此只作路径回归监控。

#### 本阶段验证与实验顺序

1. 明确 `C05`：先查询/固定 SoC 对 cached RRESP/BRESP error 的合同，再做 L1I/L1D/L2
   每个 beat error和 cached write B error定向测试。修复后重新建立功能与 timing baseline。
2. 当前 timing-pass bit 先处理 F03，并跟踪 L1D refill/response 路径；只有后续 top-N
   再出现 refill-to-L1I，才做 `F02` miss-only register A/B。比较 WNS/TNS、I-miss额外周期
   和总执行时间。
3. 建立 cache/AXI 计数矩阵。已有测试证明 4-MSHR interleave和 early response正确，尚未
   证明 perf20 经常填满四个 ID；因此暂不扩 MSHR。
4. 优先估算 `H01`：统计 dirty L1D eviction及其中 L2 reuse，直接计算可省的 AXI write
   bytes和因 write serialization损失的 read cycles，再做 L2 dirty A/B。
5. `H02` 用参数 sweep + trace共同判断。分别运行 8/16/32 KiB L1，保留相同 line/ways，
   记录每 benchmark cycles、miss和实际综合/实现资源；不要只看总几何平均。
6. `H03` 先量连续 hit arrival和 request-ready stall。I-side 与 F01联动，D-side 与单 P3/LSQ
   联动；没有上游请求时，cache每拍 ready也没有价值。
7. H05 等 H01 后重新测总线，H04 等 H02/BRAM mapping后再立项，H06 最后用离线 miss trace
   评估。所有候选都要同时检查 DBAR/IBAR/CACOP、uncached alias、dirty data与随机 AXI背压。

本阶段结论：当前存储层次并非简单 blocking cache。Data side 已经有四 global MSHR、
same-line merge、hit-under-miss、critical-beat WRAP 和 early Load response；Instruction side
已有 critical-group early response和 refill中同-line replay，但只有一个 local miss context，
hit lookup也约两拍启动一次。周期方向最值得先测 `H01` 的 L2 write-back 和 `H02/H03`
的容量/命中吞吐。2nd pass 后近期频率主线改为全局 F03 与 cache 次差的 L1D
refill/response 宽 mux，F02 只在路径回归时启用。
MSHR 扩容、4-way、
prefetch和 AXI R/W并发都需要事件数据。`C05` 的 error containment 是新增正确性 gate，
优先级高于这些性能实验。

### 2026-08-04：系统状态、MMU/TLB、CSR、异常/中断与 CACOP

这一阶段把前面各条推测执行路径重新收束到软件可见状态。Linux 可以容忍 cache miss
慢、分支预测不准，不能容忍某个用户虚地址偶尔翻译到另一物理页，也不能容忍一次异常
带着 younger CSR 写或 cache 维护副作用离开流水线。可以先把相关部件分成三层：

```text
地址属性与权限：CRMD / DMW / ASID / TLB entry
             |
             v
地址翻译与检查：I-side ATU + D-side ATU + hierarchical TLB
             |
             v
精确状态边界：ROB 顺序提交 -> CSR/exception/TLB/CACOP side effect -> redirect/refetch
```

ATU（Address Translation Unit）回答“这次访问去哪里、能不能去、是否 cacheable”；ROB 与
CSR 路径回答“这次访问所属指令是否已经成为最老且不可被更老异常推翻”。两个问题缺一
不可：翻译结果正确但由错误路径实施维护仍会破坏系统，精确提交正确但翻译位拼错同样会
破坏页隔离。

#### 三种地址翻译路径

当前 ATU 对取指和 data 各保留一个 context，并按以下优先级选择路径：

| 模式 | 条件 | PA 形成 | MAT 来源 | 是否查 TLB |
| --- | --- | --- | --- | --- |
| 直接地址 | `CRMD.DA=1, PG=0` | 默认 `PA=VA` | `CRMD.DATF/DATM` | 否 |
| DMW0/DMW1 | `DA=0, PG=1`，VA 高 3 位、当前 PLV 命中窗口 | `{DMW.PSEG, VA[28:0]}` | 对应 DMW.MAT | 否 |
| 页表映射 | `DA=0, PG=1`，两个 DMW 都未命中 | TLB 的 PPN 与页内 offset 拼接 | TLB half 的 MAT | 是 |

复位后 CRMD 为 `0x8`，即直接地址模式、PLV0、关闭全局中断。这样复位向量和 TLB refill
handler 不依赖尚未建立的页表。进入 Linux 分页后，常见内核高地址可由 DMW 直接映射，
普通用户地址和未落入窗口的内核地址走 TLB。

例如 VA `0x80001234` 命中一个 `VSEG=100`、`PSEG=000` 的 DMW 时，PA 高 3 位替换成
`000`，低 29 位保持。DMW 同时检查当前 PLV 是否被窗口允许；同一个窗口可以只给 PLV0，
也可以允许 PLV3。DMW0 优先于 DMW1，因此软件不应建立语义冲突的重叠窗口。

MAT 决定后续走 cache 还是 uncached 路径。r1p04 在本实现边界中使用 `MAT=0` 表示 SUC、
`MAT=1` 表示 CC；全局 `DisableCache` 还能把结果强制为 uncached。这个位适合 bring-up，
不应成为绕开 cache 正确性问题的发布配置。

#### perf20“裸机、不需要 TLB”的准确含义

这个说法在比赛性能测试的通常访问上成立，但“裸机”不等于软件始终停留在
`CRMD.DA=1, PG=0`。官方 `nscscc_perf/start.S` 的实际顺序是：

1. 写 DMW0 为 `0x00000009`，写 DMW1 为 `0xa0000009`；
2. 用 `csrxchg` 把 CRMD 的 DA/PG 两位改为 `DA=0, PG=1`；
3. 初始化 data/BSS 后再把 DMW0 改为 `0x00000019`，令普通 benchmark 窗口使用 CC；
4. 代码和数据分别链接到 `0x1c000000`、`0x1c080000`，高地址 MMIO 则命中另一 DMW。

因此 perf20 运行在映射地址模式，但普通代码/数据 VA 命中 DMW0，PA/MAT 由窗口直接形成，
不会发起 micro-TLB 或 Main TLB 查询。高地址设备访问通常命中 DMW1 并保持 SUC。这个条件
覆盖的是当前官方软件配置，不应扩大成“所有裸机程序都不会使用 TLB”；功能测试本身就会
主动切换直接、DMW 和页表模式。

当前 ATU 已经在语义上绕过 TLB：`dataTranslate = pagingMode && !dataDmw0 && !dataDmw1`。
但 direct/DMW request 仍在 request fire 后设置注册的 `dataResponseValid`，LSQ 随后通过共享
translation owner 接收 response，再把 `physicalAddress/uncached/translationDone` 写回 LQ/
STQ 和 `scheduledLoad`。所以“不查 TLB”只省掉了表项查询；request/response 协议和寄存边界
仍然存在。

对常见 cached hit，静态结构可以概括成：

```text
IQ/P3 -> operand -> AGU(VA)
      -> scheduled Load/Store
      -> ATU request -> registered direct/DMW response
      -> LSQ PA/MAT + ordering
      -> request buffer -> L1D lookup/response
      -> LSQ completion -> ROB registered wakeup
      -> dependent consumer issue
```

图中建议其实包含两个相互独立的优化点：

| 候选 | 去掉的等待 | 适用范围 | 阶段性判断 |
| --- | --- | --- | --- |
| `L05` direct/DMW 预翻译 | AGU 后仍经过 ATU 注册 request/response 的空转；理想上让下一拍已注册的 LSQ payload 带有 PA/MAT | perf20 的普通访存几乎都可能命中；Linux 的 direct/DMW 内核访问也能受益，用户页仍走 TLB | 中高潜力，但必须做 perf20 trace 和完整 SoC A/B |
| `W02` Load completion 早唤醒 | LSQ 已有 load data/completion 后，还要等 ROB completion staging 才广播 tag | 与是否使用 TLB 无关，所有 cache hit、miss return 和 forwarding Load 都可能受益 | 若 consumer 正在等待，可缩短一拍；是否暴露必须计数 |

`L05` 最稳妥的边界不是把 AGU 的组合地址直接送进 L1D。此前 routed top path 已多次落在
LSQ 的动态 load-head、PA/order compare 和 completion 路由；当前源码又刚把 selected-load
translation 捕获到 `scheduledLoad`，目的正是保留 timing cut。候选应在这个已有寄存器上
提前填入 direct/DMW 的 PA、MAT 与 `translationDone`，或者先实验 ATU 的 fall-through bypass，
继续让 Store-order compare 和 cache request 从寄存 payload 启动。

而且快路径必须同时考虑 Load 和 Store。只让 Load 提前得到 PA 时，任何地址已算出但
`translationDone=false` 的 older Store 仍会触发 `unknownOlderStore`，把年轻 Load 挡住；
perf20 中的 Store 也继续占用 translation owner。对 direct/DMW 的 Load/Store 在 AGU 后共同
预翻译，才能同时释放 owner、提前物理别名判断并兑现大部分理论收益。

这个优化应由运行时架构状态判定：

```text
eligible = direct-address mode
        || legal DMW0 hit
        || legal DMW1 hit
```

不得用 `RUN_PERF_TEST`、benchmark PC、固定数组地址或软件名称控制 CPU 语义。动态 fast path
属于正常的 direct/DMW 硬件优化，既保持同一 CPU 对 Linux 的完整支持，也避免形成只识别
测试程序的特例。它仍要逐项复用 ATU 的 PA 形成、PLV 许可、MAT、`DisableCache` 和 DMW0
优先级规则；CRMD/DMW 写提交、TLB mutation、flush/epoch 交叠必须看到一致的状态快照。
DMW1 的 SUC 访问可以提前知道 PA/MAT，却不能借此放宽 uncached ordering 或提前退休。

`W02` 也不能简单把 `dataResponseValid` 接到全局 wakeup。当前 LSU completion 属于可变延迟
通路，只在 ROB 注册校验后唤醒 IQ。若从 LSQ 已校验的 completion 直接广播 tag，普通 P0--P2
consumer 的两级 issue/operand 边界可能正好等到下一拍 PRF write；P3 consumer 的 operand
边界更短，可能需要同拍 load-data bypass。还要处理 cache response、Store forwarding、
uncached response、exception、flush/current epoch 和 completion collision，确保早播 tag 不丢、
不重复，也不会让 consumer 永久读取旧 PRF 值。新增广播还可能再次扩大 wakeup/select 网络。

所以“把 load 唤醒延迟降下来，IPC 应该差不多了”只能视为待证假设。它很可能帮助
CoreMark、排序、字符串搜索等具有 load-use 链的项目，却不能消除 branch recovery、L1 hit
II、cache miss、Store 顺序、前端供给或 ROB head 等其他损失。Linux M01 中 Store translation
占主导的计数也不能直接支持或否定它，因为那个 workload、地址模式和软件行为都不是
perf20。下一轮专属测量至少需要：

1. 每个 perf20 项目的 direct/DMW/TLB、CC/SUC Load/Store 动态数量；
2. `AGU accept -> translation request/response -> L1D request/response -> LSQ completion ->
   IQ wakeup -> first dependent issue` 的时间戳分布；
3. L1 hit Load 中存在等待 consumer 的比例，以及提前一拍是否推进 commit 或关键依赖链；
4. older Store 未翻译导致的 Load 阻塞和 translation-owner busy，估算只做 Load 与 Load/Store
   共同预翻译的差异；
5. 每项 perf20 cycles，而不是只看合计 IPC；
6. matching RTL 的完整 SoC setup/hold WNS、top-N path、LUT/route，以及 func/MMU/Linux 回归。

优先级上，先做纯观测 trace；若 direct/DMW 往返在 L1-hit 路径上稳定暴露，先比较保留
`scheduledLoad` timing cut 的 `L05`。若大量 cache response 后紧跟 dependent consumer，
再独立比较 `W02`。两者一起打开只能作为后续组合候选，不能用于第一次 A/B，否则无法判断
cycle 收益来自 translation 前半段还是 wakeup 后半段。未完成的 place/route 或 perf20 过程
不进入本文证据，只有绑定明确 CPU/RTL 身份的完成结果才更新结论。

#### 一条 TLB entry 为什么包含两个物理转换

32 项主 TLB 的每项由一份比较信息和两份转换信息组成：

| 比较部分 | 作用 |
| --- | --- |
| `E` | 这项是否存在、能否参加匹配；不同于 half 内的 `V` |
| `VPPN` | virtual pair page number，虚拟双页号 |
| `ASID` | 区分不同进程的相同 VA |
| `G` | global 项；为 1 时忽略 ASID |
| `PS` | page size 指数，当前合同使用 12 或 21 |

每个 `TLBELO0/1` half 各有 `PPN/V/D/MAT/PLV`。`V=0` 表示该 half 不可访问，`D=0`
会让 store 触发 PME，`PLV` 给出允许访问的最低权限，`MAT` 给出 cache 属性。双页结构让
一个共同 VPPN 同时描述相邻的偶数/奇数虚页，但两个 PPN 在架构上是独立值。

4 KiB 的例子：

```text
match key       = VA[31:13] + ASID/global
half select     = VA[12]          // 0 选 TLBELO0，1 选 TLBELO1
physical address= {selected PPN[19:0], VA[11:0]}
```

这里每个 half 是 4 KiB，一条 TLB entry 共覆盖相邻 8 KiB 虚拟范围。

`PS=21` 的名称容易混淆。手册称其为 4 MiB 页大小，因为一条双页 entry 覆盖 4 MiB；
Linux 填入时把透明大页等分成两个 2 MiB half：

```text
match key       = VA[31:22] + ASID/global
half select     = VA[21]
physical address= {selected PPN[19:9], VA[20:0]}
```

因此“支持 2 MiB half-page”与“PS=21 entry 覆盖 4 MiB”可以同时成立。讨论实现时应写出
位域，单说“大页是 2 MiB/4 MiB”很容易让拼接错误漏过审查。

#### 当前 hierarchical TLB 的实际组织

当前设计没有让 I-side 和 D-side 各自驱动一个 32 项全相联 CAM。它使用下列层次：

| 层次 | I-side | D-side | 备注 |
| --- | ---: | ---: | --- |
| positive micro-TLB | 4 项 | 4 项 | 全相联，round-robin 填充 |
| negative entry | 1 项 | 1 项 | 缓存一次精确 `(VPPN, ASID)` 主表 miss |
| architectural Main TLB | 32 项共享 | 32 项共享 | 物理为 4 bank x 8 row |
| Main walker | 共享 | 共享 | 每拍看 4 项，最多 8 个 slice，I/D 公平仲裁 |

positive micro-TLB 命中会直接给出整项，再按 VA 奇偶位选 half。negative entry 命中则
迅速重复报告 not-found，避免同一个缺页请求在异常被接收前反复扫描主表。主表命中后把
entry 填回请求侧 micro-TLB；主表未命中则更新该侧 negative entry。

主表扫描的“4 项/拍”来自 bank 布局。TLB index 的低 2 位选 bank，高 3 位选 row；同一
row 的四个 bank 并行比较。一个请求依次看 row 0 到 row 7，遇到命中提前结束。I/D 同时
micro miss 时只启动一个 walker，另一侧等待；`preferData` 在竞争时翻转，避免固定饿死。

需要区分两种完全不同的 miss：

1. **micro-TLB miss、Main TLB hit**：硬件 walker 完成，现有测试要求冷路径至少约 10 拍；
2. **Main TLB miss**：ATU 产生 `TLBR(0x3f)`，软件进入 `TLBRENTRY`、查内存页表、写
   `TLBFILL/TLBWR`、再 `ERTN`。这通常是几十到数百拍，还可能包含 cache miss。

“Main TLB miss 依赖软件”不等于“处理器必须运行操作系统”。复位后的直接地址模式完全
绕过 TLB，普通裸机程序可以一直在该模式运行；也可以使用 DMW 覆盖固定地址窗口。只有
软件主动设置 `DA=0, PG=1` 开启分页、访问又没有命中 DMW/Main TLB 时，才必须提供运行在
PLV0 的 refill handler。这个 handler 可以来自 Linux，也可以是 bootloader、监控程序或
几十至数百行的裸机 runtime；硬件只规定 TLBR 现场、入口和 TLB 管理指令合同。

TLBSRCH 是另一条管理路径。它在提交边界组合比较全部 32 项，直接更新 TLBIDX，没有走
共享 walker。这保留了指令的提交时序，却也意味着管理搜索仍有 32 路比较网络；它很少
执行，不能把其长组合逻辑接回普通每拍翻译路径。

#### 与 `d9bab16` 版本的关系

`d9bab16ef46540eb3348b0781afc4d0949f28adc` 已经采用上述完整层次：32 项 banked main
TLB、I/D 各 4 项 micro-TLB、两项 negative cache 和单个 4-entry/cycle walker。当前
`AddressTranslationUnit.scala` 相对该提交无 diff。

之后只有 `7d35545` 直接改过 `HierarchicalTlb.scala`。旧实现先把 4-bit match 编码成
micro index，再用动态 index 选择一整条宽 entry；当前实现让四个 entry 的 PPN、PLV、
MAT、V/D 等字段分别被 match mask 门控后 OR 合并，再在 micro-result 与 registered
walk-result 之间选择。它减少了动态宽 mux 的组合深度，`+73/-25` 行，容量、替换、walker、
异常和请求延迟语义保持不变。

`6bbca9b...` 增加的是 ATU 周边的架构语义测试和 CSR/cache-maintenance 接线，没有修订
`translatedPhysicalAddress` 或 mutation cancellation。因而 C07/C08 不能因为本轮 TLB
fault tests、Linux early console 或 CACOP tests 通过而关闭；二者所指的代码在 current
source 中仍与初稿审计相同。

更早的层次化改造本身曾让 standalone LUT 减少 538、FF 增加 850，并消除完整 SoC 中
Main-TLB-to-exception 最差路径；随后给 walk result 增加寄存器又把该路径移出 top path。
这说明当前 TLB 形态主要由 FPGA routing/timing 驱动，不是近期才从 flat CAM 大幅改成
hierarchy。当前 standalone 统计中 TLB 仍约为 3,584 LUT（CPU LUT 的 4.8%）。

#### 热 uTLB 命中仍可能限制前端启动吞吐

“uTLB hit latency 一拍”只描述 request 到 TLB Flow 的响应时间，不能直接推出“前端每拍
可发一个 group”。当前 I-side 只有一个 ATU context，frontend 又只有一个 translated
request slot。按寄存边界做最乐观逐拍推导：

| 边沿 | 事件 |
| --- | --- |
| N | frontend 的 16 B group translation request 被 ATU 接受 |
| N+1 | micro-TLB quick result 被 ATU 捕获为 registered response |
| N+2 | frontend 消费 response，写入 translated-request slot |
| N+3 | translated slot 向 L1I 发 request 并被接受 |
| N+4 | slot 已清空，下一组 translation request 才能再次握手 |

所以当前结构的最短相邻 I-translation accept 间隔约为 4 拍。若每组四条都有效，静态
启动上限约为 `4/4 = 1 inst/cycle`；taken branch、非对齐 group 和 buffer backpressure
还会降低它。这个结果比此前只看 L1I 两拍 hit II 得出的约 2 IPC 上限更紧，也与“实测
IPC 不到 1”处在同一数量级。它不是对任一 workload IPC 的直接解释，cache miss、分支、
ROB head 和 LSQ 仍会继续损失周期。

F01 因此值得升到高潜力候选。第一步应给 translation accept、ATU response、L1I accept、
L1I response 各打时间戳，确认实际 II；第二步用 2~4 项 context FIFO 保存 PC、prediction、
PA/MAT 和 generation；第三步才考虑把 micro-TLB probe 改成可每拍接受的流水结构。只增加
micro-TLB 容量不会改变这个热命中 II，单独优化 L1I 也会继续被翻译串行化限制。

#### 当前没有 VIPT，但 L1 几何恰好适合 VIPT

VIPT（Virtually Indexed, Physically Tagged）是 cache lookup 与 TLB lookup 的并行化方法，
不是 TLB entry 本身的一种组织。当前 L1I/L1D 都在 ATU 先给出 PA 后才发 cache request，
cache 的 set index 和 tag 也都取 physical address，所以实现属于 PIPT，翻译与 L1 array read
串行。

下述简单 VIPT 推导基于已验证候选的 2-way、64-set、64 B line：line offset 需要 6 bit，set index 也需要
6 bit，合计正好是 VA/PA `[11:0]`。最小 4 KiB 页翻译不会改变这 12 bit，因此可以在同一拍：

```text
VA[11:6] --------------------------> 提前读取 L1 的两个 way
VA + ASID -------------------------> uTLB 查询
uTLB 返回 PA tag/MAT/权限 ----------> physical-tag compare，选择命中 way
```

物理 tag 仍保证不同 VA alias 到同一 PA 时按物理身份判定；`sets * lineBytes = 4 KiB` 也满足
经典 VIPT 的 per-way-size 不超过最小页大小条件。对当前 8 KiB、2-way L1，这是非常自然的
低容量几何点。若 H02 把 cache 直接扩成 2-way、128 sets，index 会使用 VA bit 12；该位会被
4 KiB 页翻译改变，VIPT synonym 便可能落到不同 set。保持 64 sets 并增至 4-way，或增加
page coloring/alias maintenance，才可继续维持安全合同。

2026-08-05 只读核对时，实验 HEAD `71577a4` 的默认 L1I/L1D 已是 2-way、128 sets；该源码
尚无与本段绑定的稳定 timing/perf 结论，但它已使上述“直接使用 VA `[11:6]`”方案不再适用。
若 128-set 容量候选最终保留，H07 必须改变 associativity/geometry 或增加 alias 方案；若它
被实验回退，则再按最终稳定配置恢复评估，不能跨配置继承 VIPT 结论。

实现 H07 需要把 cache 请求拆成 early virtual-index context 和 late physical-tag/permission
context。异常或 SUC 翻译只丢弃投机 RAM read，Store 不能在 D/PLV 检查前更新 array；redirect
还要用 generation 丢弃旧 lookup。它有机会减少 hit latency，却不会单独解决当前 L1
controller 约 2 拍 II 或前端单 context 问题，所以应与 F01、H03 联合设计和分步测量。

#### TLB 指令怎样修改架构状态

五类 TLB 指令都被标成 serializing，只在 ROB 头执行/提交：

| 指令 | 当前作用 |
| --- | --- |
| `TLBSRCH` | 用 `TLBEHI.VPPN + ASID` 全表查找，更新 `TLBIDX.Index/NE` |
| `TLBRD` | 按 `TLBIDX.Index` 回读 entry；空项清零相关 CSR 并置 `NE` |
| `TLBWR` | 按指定 index 写 `TLBEHI/TLBELO/ASID/PS/E` |
| `TLBFILL` | 用 `timer64[4:0]` 作为 32 项填充 index |
| `INVTLB` | 实现 op 0~6 的 all/global/non-global/ASID/VA 组合失效 |

写、填充和失效都会清空两侧 positive/negative micro state 和在途主表 walk。提交后通过
`PC+4` privileged redirect 重新取指，使新请求观察更新后的翻译状态。TLBSRCH/TLBRD 也
重取，避免 younger 指令携带旧 CSR/TLB 快照继续前进。

主 TLB entry storage 按架构约定不做逐项 reset。复位处于直接地址模式，系统软件在开启
分页前负责建立/失效 TLB；不能把未初始化 E 位当成可用 entry。若要提高仿真确定性，应
用软件初始化序列或验证约束，不能擅自改变架构 reset 语义再继承旧 DiffTest 结果。

#### `C07`：mutation 丢 response 与上游 drain 合同冲突

当前三个上游 owner 都把 request/response 当成“已握手后必有一个 response”的事务：

- frontend 在 redirect 时清掉 `translationOutstanding`，但置
  `translationDropPending`，等待旧 response 后才允许新 fetch translation；
- LSQ 在 flush 时置 `translationCancelPending`，测试也明确要求旧 response 被消费后才
  重用 slot；
- data translation arbiter 的 `translationOwnerValid` 只在 response fire 时清除。

TLB mutation 的同拍行为相反：hierarchical TLB 清空 probe/walk/response pending，ATU 也
清空 instruction/data search pending 和 registered response，不产生 cancellation
response。TLB side effect 与 privileged redirect 都由 commit 信号 `RegNext`，所以它们
会在同一拍到达 ATU、frontend 和 backend。

一个可达时序如下：

```text
N:   younger load 或下一 fetch group 的 translation request 已握手
N:   ROB 头的 INVTLB/TLBFILL 提交，side effect/redirect 被寄存
N+1: core flush；owner 保存 drop/cancel token
N+1: ATU mutation；在途 request/result 被直接清空
N+2+: owner 等待已经不可能出现的 response，后续 translation 被永久阻止
```

已有 ATU 测试覆盖 mutation 后 stale positive/negative entry 被清除，但 mutation 发生在
先前 response 完成之后；已有 LSQ 测试覆盖 flush 后 translator **仍返回**旧 response。
两项各自通过不能证明组合系统正确，正好缺少上述交叠场景。

推荐先写系统级定向测试复现，再选择统一协议。较清晰的方案是给翻译事务附带 generation，
mutation 对每个已接受 owner 返回一项 `cancelled` completion；owner 消费 token 后丢弃，
新 generation 重新请求。另一方案是把 mutation 明确广播到所有 owner，同拍清除它们的
drop/cancel/owner state。后者面积更小，但必须证明 frontend、LSQ、CACOP 三类 owner 都
收到且没有已不可撤销的翻译副作用。

#### `C08`：PS=21 的 one-bit 拼接错误

当前大页 match 使用 `VPPN[18:9]`，对应比较 VA[31:22]；half select 使用 `VPPN(8)`，
对应 VA[21]。这两处与 r1p04 一致。错误位于最终 PA：

```text
架构：PA = {selected_PPN[19:9],  VA[20:0]}
当前：PA = {selected_PPN[19:10], VA[21:0]}
```

若 `PPN0.bit9=0`、`PPN1.bit9=1`，且两个 half 的其余 PPN 高位相同，当前结果恰好正确，
因为 VA[21] 正好复现连续物理 half 的 bit 9。这是 Linux 常见的对齐透明大页形状，也最
容易让启动测试掩盖问题。架构允许两个 selected PPN 独立：例如偶 half 指向 bit9=1 的
物理 2 MiB，奇 half 指向 bit9=0 的另一块区域，当前实现会把两者的 bit 9 都翻转成由 VA
决定的值。

定向测试应把 PPN0/PPN1 设成非连续且 bit9 与 VA half 相反，分别翻译 VA[21]=0/1；再
覆盖 TLBRD -> CSR -> TLBWR round-trip、global/ASID、V/D/PLV/MAT 和 INVTLB op5/6。
`C08` 修复前，任何“4 MiB huge page 已启动”的证据只能覆盖常见映射，不能证明大页
通用语义。

#### 翻译异常如何精确分类

I-side ATU 的检查顺序是 PC 对齐、TLB found、half valid、PLV；D-side 的自然对齐由 AGU/
LSQ 先处理，翻译侧检查 found、valid、PLV，store 再检查 dirty。对应关系为：

| 访问与条件 | Ecode | 名称 |
| --- | ---: | --- |
| 取指 PC 非 4 B 对齐 | `0x08` | ADEF |
| 任一分页访问 Main TLB 不命中 | `0x3f` | TLBR |
| load/store/fetch 命中 entry 但 half `V=0` | `0x01/0x02/0x03` | PIL/PIS/PIF |
| 当前 PLV 数值大于 entry.PLV | `0x07` | PPI |
| store 命中有效且权限合规、但 `D=0` | `0x04` | PME |
| data 地址不满足自然对齐 | `0x09` | ALE（AGU/LSQ） |

异常先作为 uop/completion metadata 进入 ROB，不在翻译返回时直接修改 CSR。只有它到达
ROB 头，commit prefix 才停止，`ERA/BADV/ESTAT/TLBEHI` 更新，更年轻 uop 被 flush。
TLBR 使用独立 `TLBRENTRY`；其他异常跳 `EENTRY`。

TLB refill 进入时 CSR 自动保存原 PLV/IE 到 PRMD、设置 PLV0/IE0，并把 CRMD 改为
`DA=1, PG=0`。这样 refill handler 直接访问代码和页表。`ERTN` 从 PRMD 恢复 PLV/IE；若
ESTAT 记录的前一异常是 TLBR，还把 CRMD 恢复为 `DA=0, PG=1`，再从 ERA 重取。

#### CSR、异常和中断的提交时间线

CSR 大致分为五组：

| 组 | 关键 CSR | 用途 |
| --- | --- | --- |
| 模式/异常 | CRMD、PRMD、ECFG、ESTAT、ERA、BADV、EENTRY | PLV/IE、pending mask、异常现场/入口 |
| TLB/页表 | TLBIDX、TLBEHI、TLBELO0/1、ASID、PGDL/H、PGD、TLBRENTRY | 软件管理翻译 |
| 定时器 | TID、TCFG、TVAL、CNTC、TICLR | 稳定计数与 level timer interrupt |
| 原子 | LLBCTL、内部 LLBit/LLAddr | LL/SC reservation 与 ERTN 清除规则 |
| 映射/能力 | DMW0/1、CPUCFG、DisableCache | 窗口、硬件能力和调试控制 |

所有 CSR/CPUCFG/RDCNT uop 都是 serializing，并且 P0 issue queue 只有在该 uop 位于 ROB head
时才允许它执行。读操作在 execute 捕获旧 CSR/计数值写入 ROB；CSRWR/CSRXCHG 的新值只在
retire 后通过 commit adapter 生效。CSR write 会触发 refetch，避免 younger 指令继续使用
旧 ASID、PLV、DA/PG、DMW 或 interrupt mask。

系统边界把 CSR/TLB/cache side-effect 再寄存一拍，目的是切断 ROB commit 到宽状态更新
网络。异常路径略有不同：faulting uop 到 ROB 头时，CSR 在 exception edge 保存 ERA、
BADV、ECODE/ESUBCODE 和 PRMD；core 的统一 recovery 下一拍 redirect。翻译上下文也是
CSR forwarded output 的 `RegNext`，因此重定向后的 fetch 看到已经更新的模式快照。

中断不是在任意执行级粗暴清空流水线。`has_int` 由
`(ECFG.LIE & ESTAT.IS).orR && CRMD.IE` 产生，decoder 只在三路组的 lane 0 注入一个
`ECODE=0` exception uop。它像普通精确异常一样穿过 ROB：更老指令先退休，faulting 边界
之后的指令被 flush。代价是中断响应会等待更老 cache miss、DIV 或 committed store drain；
这是精确状态的必要延迟，也是应该测量而不应随意旁路的延迟。

定时器中断是 level-pending：TCFG 启动倒计时，TVAL 到 0 后置 ESTAT.IS[11]；周期模式
重新装载，软件写 `TICLR.TI=1` 清 pending。外部 8 路硬中断直接采样到 IS[9:2]，软件中断
由 ESTAT.IS[1:0] 写入。IDLE 保存 `PC+4` 并停止有效取指；已有 pending interrupt 或之后
到来的 interrupt 会唤醒到下一条指令，再由精确中断注入路径进入 handler。

`1c33132...` 修复了这里的 reset 边界：reset 现在清完整 ESTAT、TCFG、TVAL 和 timer enable，
定向测试覆盖“定时器触发 -> reset -> pending/TVAL/TCFG 清零 -> 再次触发 -> TICLR 清除”。
所以“reset 后遗留 timer interrupt”不再是当前开放缺口。

`6bbca9b...` 还把 CPUCFG 与参数化 cache geometry 绑定。当前已验证值为 index 16
`0x0000001d`，L1I/L1D 的 index 17/18 均为 `0x06060001`，L2 的 index 19 为
`0x06090001`。这比静态 decode 到 CPUCFG 指令更强：软件读到的 ways、set-index bits 和
line-size bits 与当前 2-way、64/64/512 sets、64-byte line 一致。

#### CACOP 是“翻译 + 全层次静止 + 精确维护”事务

CACOP 的 `code[2:0]` 选择 L1I、L1D、共享 L2，`code[4:3]` 选择 Store Tag、Index、Hit；
自定义 mode 3 当前作为无副作用指令。Store Tag/Index 用 VA 的 way/index 直接选行；Hit
把 VA 当普通 load 做地址翻译，命中对应 PA cache line 才操作，因此可产生 TLBR、PIL、
PPI 等翻译异常，且不做普通 load 的自然对齐检查。Hit 类允许 PLV3 使用，其他已定义
CACOP 只允许 PLV0。

当前 barrier state machine 的流程是：

```text
P0/ROB-head 接受 CACOP
  -> drain older store + cache/MSHR/L2/AXI，连续确认 quiescent
  -> Hit mode 请求 D-side ATU 翻译（Index/Store Tag 跳过）
  -> 向指定 L1I/L1D/L2 发 exact maintenance token
  -> Data/Unified Index/Hit 遇 dirty line 先 writeback，再 invalidate
  -> I-cache invalidate；Store Tag 把所选 tag/valid 清零
  -> post-drain，生成带 ROB pointer/epoch 的 completion
  -> 顺序提交并 refetch PC+4
```

全层次前后 drain 很保守，确保重定向后的取指/访存不能越过维护，也保护 self-modifying
code 和非一致 DMA 软件同步。它会让 CACOP 延迟较长，但 benchmark 热路径通常很少执行；
在 Linux 正确性稳定前，不应为了降低 CACOP 周期移除 drain。更有价值的性能工作仍是普通
fetch/load 的翻译 II 和 cache hit II。

已有 L1I/L1D/L2 测试覆盖 exact Index/Hit、Hit miss 无副作用、无关行保留以及 dirty data
写回；共享层次测试覆盖 IBAR 把修改代码写回、失效 L2/L1I 后取到新指令。仍需把这些单元
证明与 C07 的 ATU mutation/flush、随机 AXI backpressure、多 seed DiffTest 和 Linux
页表/cache 维护序列连接起来。

当前 status 已把 CACOP 的 Store Tag、Index writeback-invalidate、Hit
writeback-invalidate，及 L1I/L1D/L2 三层覆盖标为 locally satisfied；Hit translation fault
也有 precise metadata 测试。这里保留的工作是跨模块/长程序证明和 C07，而不是重新实现
基础 CACOP mode。

#### 本阶段性能与验证顺序

1. **先处理 C07/C08。** C07 做 TLBWR/TLBFILL/INVTLB 与 I/D/CACOP 在途翻译每个拍点
   的交叠；C08 用非连续 PS=21 PPN half 做 differential。两项通过/修复前，不开展 TLB
   性能 RTL 实验。
2. 为 F01 建立逐 group trace：translation request/response、L1I request/response、IBUF
   enqueue/dequeue。先确认热 uTLB+L1I hit 的实际 4 拍 translation accept II 和 frontend
   empty 占比，再设计 context FIFO。
3. F01 与 H03 联合分级：先把 PC/prediction/translation 解耦成小 FIFO，再让 micro-TLB
   可流水接受，最后让 L1I hit lookup 达到 II=1。每一步分别记录 IPC、cycles、LUT/FF、
   完整 SoC WNS，避免形成 predictor->TLB->L1I 的长 ready 链。
4. 测量 V01：分别统计 I/D micro hit、negative hit、main hit、main miss、fill-pointer eviction
   后短期重访。4 项在 `PS=12` 下最多覆盖每侧 4 个相邻双页区间，即两个 half 都有效时
   为 32 KiB；8/16 项有理论价值，但必须由工作集 VPPN pair 数和 miss 数证明。
5. 只有 shared-walker queueing 明显暴露时研究 V02。历史上 Main TLB 已经成为过完整 SoC
   最差路径；扩大比较宽度或复制 walker 必须同时看 route delay，不能只比较扫描拍数。
6. 当前 checkout 已有 matching RTL 的 100 MHz complete-SoC timing：setup `+0.044 ns`、
   hold `+0.050 ns`。3,584-LUT TLB 仍是 standalone 层次参考；下一项 TLB RTL 改动必须
   重新实现，不能继承这 44 ps。

本阶段结论：当前 TLB 的层次结构相对 `d9bab16` 基本不变，后续修改主要压缩 micro-hit
结果 mux。真正值得优先研究的性能问题是翻译、frontend slot 与 L1I 请求的串行启动，
它可能把分页模式的满组供给压到约 1 inst/cycle；micro-TLB 从 4 项扩容解决的是另一类
main-walk miss。`C07` 的 mutation 取消合同和 `C08` 的 PS=21 PA 拼接是本阶段新增的 P0
正确性 gate，优先级高于 F01、V01、V02 以及所有 CACOP 延迟优化。

### 2026-08-03：IPC 空间与 Fmax 粗估

三宽 commit 给出 `IPC <= 3` 的硬上限。当前 L1I 正常 hit 路径以 `idle -> lookup -> idle`
运行，cache array 为同步读；在现有握手下通常每两拍接受一个新 16-byte group。因此，
若单独观察 L1I，连续命中且每组四条均有效时的一阶上限约为 `4 / 2 = 2 IPC`。
本记录后续对 MMU/前端握手的逐拍审计给出了更紧的当前整链上限：单 I-side ATU context、
frontend translated slot 与 L1I handoff 使相邻热 translation accept 的静态最短间隔约为
4 拍，即约 `4 / 4 = 1 IPC`。这里的 2 IPC 只保留为 **cache-only ceiling**，不能再当作
当前完整前端 ceiling。taken 分支位于组内较早 lane 时还会丢弃它之后的槽，实际供给继续
下降；F01/H03 需要一起解除 translation 与 L1I 两层 II 限制。

2nd pass 后的 clean official-equivalent Linux early-console 受限运行观测为
13,924,596 instructions / 24,999,995 cycles，约 0.556984 IPC。patched random-AXI 三个
seed 在 99,999,995 cycles 时约为 0.416926--0.416960 IPC；后者受 harness patch、随机背压
和更长启动区间影响，不能与 clean IPC 直接做设计 A/B。两组都包含启动、cache/TLB/内存
及系统代码行为，也不等价于 perf20 IPC；perf20 当前只有 cycles、没有 retirement count，
不能从 83,234,731 cycles 反推出 IPC。clean 窗口与前端约 1 IPC 的当前整链静态启动上限
相比仍有明显损失，也说明三宽后端在当前前端
合同下不可能持续吃满。优化判断应记录
每周期 commit 0/1/2/3 条的直方图，并把空提交周期归因到 frontend empty、分支恢复、
ROB head 未完成、IQ/端口冲突、LSQ/cache miss、serializing 等原因。

可用下式建立近似 CPI 账本：

```text
CPI = base-throughput CPI
    + branch recovery loss
    + uncovered I-cache/D-cache/TLB miss loss
    + dependency and execution-port loss
    + queue-full, serialization and ordering loss
```

Fmax 分三层估计：

1. 当时固定 `60fba481 + c398` 网表在一次 100 MHz Explore run 中得到 `+0.044 ns`；若只把这
   44 ps 从 10 ns 周期中扣除，一阶等价约 100.4 MHz，但这个结果小于常见实现波动，不能
   当作已证明的超频空间。`8594150 + c398` 的 `-0.225 ns` 与一阶 97.8 MHz 只保留为
   同平台历史对照。
2. 保持总体流水深度，做关键路径切分、局部复制和布局优化：105--110 MHz 是适合
   立项验证的近期区间，但需要同时处理 L1I 路径、调度路径和乘法等多个路径族。
3. 使用更多 FF/BRAM/DSP，并允许 cache response、wakeup/select、MUL 等位置增加流水
   边界：120--140 MHz 可作为激进研究区间；150 MHz 属于低置信度 stretch target，
   需要大范围重定时并重新评估增加的 miss、branch 和 dependent-use 周期。

这些区间是工程规划值，并非器件保证。旧参考约 66% LUT、20% FF、19% BRAM、1% DSP
说明资源重分配有空间；Fmax 由最差寄存器间路径、时钟和物理布线决定，无法从“还剩
多少 LUT”直接外推。每个频率候选最终比较 `cycle_count / f_cpu`，避免流水加深后频率
上升而总执行时间反而恶化。

### 2026-08-04：FPGA 物理实现、资源映射、布线与时序闭合

这一阶段的核心问题是：RTL 中的“逻辑并行”能否在 `xc7a200tfbg676-2` 上被映射成可邻近
放置、可布线、满足 setup/hold 的物理结构。LUT、FF、BRAM、DSP 的剩余百分比只能说明
资源总量，不能直接说明还有多少时钟周期余量；当前设计的瓶颈已经明确表现为 slice packing
和 route delay。

#### 先区分四种证据

Vivado 报告的可信度顺序为：

```text
synthesis utilization
  -> placed utilization + post-placement estimate
  -> phys_opt estimate
  -> routed timing/DRC/route status
  -> bitstream + matching software/board evidence
```

综合阶段可以回答“会推断出多少 LUT/BRAM/DSP”，但没有真实的长线、时钟树和拥塞。placed
阶段已经有 cell 坐标，却仍可能改变大量 route。只有 routed report 才能作为完整 SoC 的
setup/hold 结论；bitstream 生成成功也不能替代 timing/DRC 检查。

#### 当前 100 MHz run 的现场记录

本次运行使用 CPU source commit `60fba481888a8f7e5a2f0ba0b76c91422a117309`、generated
RTL SHA-256 `137657aa...` 和 Chiplab `c398d274812f164d387146fa7d8f612a4a1296d9`，目标
`perf/100 MHz`。本次证据快照观察到的 `872bbd4...` 在 `6bbca9b...` 之后只更新 ignore、
验证状态和说明文档，所以仍与这份 RTL 证据相容；不能把 HEAD 字符串本身写成
implementation source。CPU RTL 已同步到
`chiplab/IP/myCPU/mycpu_top.v`，Chiplab 工作树同时保留了 performance PLL/VIO 等运行
生成物；这份本地归档后来记录为 stable，并已有 matching 的团队板 func58/perf20 证据，
但它仍不是 official CI 或 Linux shell 板测证据。

本次现场 trace 已完成到 bitstream 与候选归档：

| 时间/阶段 | 观察结果 | 证据级别 |
| --- | --- | --- |
| 05:29:17 synthesis done | `Slice LUT 84,184 (62.54%)`、FF `44,400 (16.49%)`、BRAM tile `63 (17.26%)`、DSP `8 (1.08%)`；F7/F8 分别 `7,495/1,380` | 当前综合资源；OOC IP 仍以 black box 计，不能当最终 placed 总量 |
| 05:30:53 placement start | opt_design 完成，0 error；进入 `place_design -directive Explore` | 当前 run |
| placement 中间 | 初始 post-placement estimate `WNS=-1.605 ns, TNS=-2130.683 ns` | 中间值，不是 signoff |
| 05:34:55 placement done | `LUT 88,967 (66.49%)`、FF `53,697 (19.95%)`、BRAM tile `65.5 (17.95%)`、DSP `8`；slice `81.23%`；post-placement estimate `WNS=-0.358 ns, TNS=-173.796 ns` | 当前 placed 资源/估计 timing |
| phys_opt 中 | 先到 `-0.289/-106.011`、`-0.273/-69.934`，随后 `-0.191/-17.821`、`-0.103/-3.385`、`-0.079/-1.270` | 当前 phys_opt 过程 |
| 05:37:52 route start | phys_opt estimate `WNS=+0.017 ns, TNS=0`，使用 `route_design -directive Explore` | 仍非最终 timing |
| route 初始化 | setup intermediate `WNS=+0.141 ns`；hold 临时 `WHS=-1.596 ns, THS=-1883.533 ns`，当时仍有 `112,848` 条 unrouted net和 1 条 partially routed net | router 中间值，不能据此判定 hold 失败 |
| 第一轮全局 route | setup 依次为 `-0.620/-171.410`、`-0.440/-12.988`、`-0.275/-5.549`、`-0.190/-6.062`、`-0.145/-5.690`、`-0.260/-3.534`，delay cleanup 后为 `-0.138/-4.903` | `Explore` 的中间结果；尚未完成增量重放置 |
| 第二轮局部重放置/route | 第一轮验证后将一组关键单元增量重放置并局部重布线；post-router 为 setup `WNS=+0.044 ns, TNS=0`、hold `WHS=+0.050 ns, THS=0` | 最终 routed 全设计结果；CPU clock 自身 hold WNS 为 `+0.056 ns` |
| 05:48:57 bitstream done | 0 unrouted、0 partially routed、route verification success；DRC `0 error / 27 warning`；`soc_top.bit` 写出成功 | 完整 SoC 实现和 bitstream 成功，不等于仿真或上板通过 |
| 05:49:33 candidate archive | actual CPU/sys `100 MHz`、DDR `200 MHz`，`timing_status=pass`；bitstream SHA-256 `6302203c...` | 可追溯本地 performance 实现证据 |

27 条 routed DRC warning 中有 19 条是 multiplier DSP 的 input/MREG/PREG pipeline 建议，
其余主要来自 DDR/debug/platform IP 的 input buffer、配置电压、时钟 buffer、LUT pin 和无
routable load。12 组 bus-skew constraint 全部通过。methodology report 另有 95 条
warning/advisory；其中 `TIMING-18` 明确列出 17 个 input、42 个 output 没有 I/O delay。
报告同时确认 0 个 unconstrained internal maximum-delay endpoint。因此这里可以声称“按当前
官方平台约束，完整 SoC 内部 setup/hold 与 bus-skew 通过”，但不能把它扩大成所有板外接口
均已独立完成 I/O timing signoff；该限制也不等同于仿真或开发板功能验证。

本次归档中的 `timing_summary.rpt` 每个 clock group 只保存一条 max/min path；用于下面
top-10 分析的 `soc_top_timing_summary_routed.rpt` 仍在 Vivado run 目录，没有进入 hash-locked
候选。下一次实现可能覆盖该目录，所以后续归档流应保存完整 `-max_paths 10` 报告，或记录
从 matching routed DCP 重生它的 Tcl、Vivado 版本和 hash；本笔记的路径摘要不能替代原报告。

旧的可追溯 `8594150 + c398` 完整 perf candidate 在最终 routed report 中是 `100 MHz`、
setup `WNS=-0.225 ns`、`TNS=-12.096 ns`、190 个 failing endpoints、hold `WHS=+0.053 ns`。
其最差路径是 L2 refill 到 L1I response/predecode，数据路径 `10.328 ns`，其中逻辑
`2.395 ns`、route `7.933 ns (76.810%)`。这组数字只能作为旧参考，不能跨 RTL 继承到当前
run。当前 run 已经闭合，但 setup 余量只有 `44 ps`，路径族也已经改变，因此仍需同 RTL
多 seed/strategy 复现和 clean CI 实现，不能把单次结果描述为稳定的 100 MHz 裕量。

#### 怎样读资源数字

当前 placed 资源与 `d9bab16 + 68c20a5` 旧 candidate 的差异是：LUT 增加约 `1,701`，FF
增加约 `742`，BRAM tile 反而减少约 `3`，DSP 不变，而 slice occupancy 从 `79.47%`
升到 `81.23%`。这说明约束来自
“逻辑单元能否被放进相邻 slice 并完成布线”，不是 BRAM/DSP 总数耗尽。

当前 standalone 层次参考还显示：ROB 约 `28,083 LUT`，PRF 约 `4,352 LUT`，四个 IQ 合计
约 `6,097 LUT`，cache hierarchy 约 `13,104 LUT`，TLB 约 `3,584 LUT`。这些层次数用于
定位面积和路由源头，不能与当前 full-SoC placed 数字混用；尤其 ROB/PRF 的宽 mux、IQ 的
比较广播和 cache 的宽数据总线会制造大量局部拥塞。

资源映射有几个基本规律：

- ROB、PRF、IQ 的窄状态和多端口比较大多落到 LUT/FF；增加 BRAM 余量不会自动降低它们的
  LUT 消耗，因为 BRAM 端口数和同步读延迟不匹配。
- L1/L2 的 512-bit line array 会并联很多 RAMB primitive。L1 深度较浅，BRAM 深度利用率
  不高，但宽数据读出和 way mux 仍会占 route。
- MUL 可以映射 DSP48，但 DSP 不满不代表乘法路径便宜。旧 DRC 还指出 DSP 输入/输出没有
  完整 pipeline；当前 phys_opt 只是在实现阶段把某个 multiplier cell 的 15 个寄存器
  推出，并不等于 RTL 已经增加了架构流水级。
- F7/F8 mux 数量很高，说明大宽度选择网络仍是主要物理负担；把逻辑“压缩”为更少的 RTL
  行数未必减少物理 mux。

#### WNS、TNS 和 route delay 的直观含义

100 MHz 的时钟周期是 10 ns。setup slack 近似为：

```text
slack = required clock time - data arrival time - clock uncertainty/skew effects
```

`WNS` 只看最差 endpoint，`TNS` 把所有负 slack 相加。旧 candidate 的 `-0.225 ns`
并非只有一个坏 endpoint，而是 190 个 endpoint 总计 `-12.096 ns`；只修一个 endpoint
可能让下一个同路径族 endpoint 成为新的 WNS。本次 run 从 placed `TNS=-2130.683 ns`
收敛到最终 `TNS=0`，同时也说明中间 timing 只反映优化过程，不能替代 routed report。

旧最差路径的物理形状是：

```text
L2 refillOutput_mshrId  (约 SLICE_X41Y111)
  -> refill mask / read-MSHR / L1I refill beats
  -> instruction response / predecode / branch target carry
  -> L1I response_predecode target (约 SLICE_X46Y28)
```

端点横向名字相近并不代表物理位置相近；该路径纵向跨越约 83 个 slice row。对这种路径，
最有价值的动作通常是：在 L2/refill 与 L1I response 之间加入局部 register 或 narrow
group buffer，把预译码搬到 L1I 附近；其次才是复制少量控制状态或做 placement constraint。

当前最差路径的物理形状已经变为：

```text
L1I responseValid register Q  (SLICE_X42Y88，fanout 118)
  -> responseFire / slot-valid / group prefix
  -> prediction match、taken 截断、tail + prefix 动态目的项选择
  -> frontend entries_0_pc[*] clock-enable
     (最差 endpoint SLICE_X17Y131)
```

最差一条 data path 为 `9.550 ns`：逻辑 `2.164 ns (22.659%)`，route `7.386 ns
(77.341%)`，11 级逻辑（2 个 CARRY4、6 个 LUT6 等），另有 `-0.164 ns` clock skew 和
`0.074 ns` uncertainty。报告中的 `responseLearnPending_*` 是综合合并后的 net 名；源码中
同一 response cone 同时计算 branch learn/correction、四槽有效前缀、动态 buffer destination
和 entry write enable，不能只凭 net 名认定“BTB learn 本身”占满路径。

CPU top 10 setup path 中有 9 条属于这一 L1I-response-to-frontend 家族，slack 为
`0.044--0.084 ns`；唯一另一族由 `l1d/misses_0_refillMask_reg[2]` 到
`l1d/response_data_reg[22]`，slack `0.056 ns`，data path `9.855 ns`，route
`7.722 ns (78.356%)`，并经过 LUT、F7/F8 mux 和 distributed RAM。换言之，即使 F03
完全解决，L1D refill/response 也会立即成为下一面频率墙；当前 top 10 中没有
wakeup/select，先前对此网络的 critical 程度继续只作旧 bit 参考。

#### 物理优化方向的优先级

1. **F03：拆分 L1I response → frontend enqueue 控制锥。** 第一组 A/B 不必立即增加整拍：
   先测 response-valid 的逻辑扇出和各子锥，尝试将 8-entry buffer 做成按 fetch lane
   banked/rotating 的写结构，缩短 `tail + prefix -> dynamic Vec CE`；再比较局部 valid 复制、
   correction/learn 与 enqueue 逻辑隔离。若仍不足，才增加 response ingress register，并把
   新增一拍与更高 Fmax、frontend empty 周期一起计入 `cycles/frequency`。
2. **H04/F02：处理紧随其后的 cache 路径族。** H04 先针对当前 L1D refill-mask 到
   response-data 的 F7/F8/宽数据 mux；F02 保留为旧版 L2→L1I miss-only 路径候选，只有它
   在新实现 top-N 重新出现时才升回第一优先级。两者都要重证 critical return、dirty victim、
   CACOP 和 error 传播。
3. **P01：局部复制与数据流靠近消费者。** 复制应围绕真实 top-path 的 consumer cluster，
   例如 L1D refill state、L2 MSHR、IQ/ROB 局部区域；全局 broadcast 上强行复制会增加 LUT、
   FF 和拥塞。当前 phys_opt 日志列出的高扇出网可作为候选集合，不是自动修改清单。
4. **T03：重新定义 DSP pipeline 边界。** 本次 DRC 仍有 5 条 DSP input、8 条 PREG output、
   6 条 MREG output pipeline warning，但 MUL 未进入当前 CPU top 10。它仍值得 A/B，优先级
   暂低于 F03 和当前 L1D 路径；增加 MUL latency 时必须同步修改 wakeup/completion。
5. **P02：多 seed/strategy 用于判断 44 ps 结果的物理随机性。** 固定 RTL、软件、器件、时钟和约束，
   比较 Explore/Performance、不同 seed 的 WNS/TNS、top-path 族、congestion、runtime；
   不能拿一次幸运 seed 的 slack 作为架构收益。
6. **Pblock/floorplan 放在最后。** 当前 slice occupancy 已超过 81%，过早切 Pblock 可能把
   跨模块路径硬挤到局部拥塞区。只有确认 top-path consumer cluster 稳定，才进行小范围
   clock-region 实验，并保留无 floorplan 对照。

#### 本阶段的实验合同

每个物理实现候选都必须保存：

```text
CPU commit / generated RTL SHA-256 / Chiplab commit
build_kind=perf 或 func / requested 与 actual CPU MHz
Vivado 版本、strategy、directive、seed、约束摘要
post-synth、placed、phys_opt、routed utilization
timing_summary、route_status、DRC、top 10 setup/hold paths
软件 image、bitstream、DCP 和所有报告 hash
cycle count、IPC、实际运行时间 = cycle_count / actual_cpu_mhz
```

RTL 改动后的旧 WNS、资源和 bitstream 不可继续作为当前候选证据。实现阶段要保持单个
Vivado run，避免两个 implementation 同时占用当前主机资源；先完成正确性 gate，再按
“功能 build + 性能 build”分别形成可比结果。

本阶段结论：当前本地 performance run 已在实际 100 MHz 下完成 bitstream，setup/hold 和
route/DRC 基本门槛均通过；`+0.044 ns` 只说明这一次实现刚好闭合，不代表稳定余量或板级
通过。当前首要结构路径是 F03 的 L1I response 到 frontend dynamic enqueue CE，其后紧跟
L1D refill/response 宽 mux。P02 应先判断这两个路径族跨 seed 是否稳定，再做 F03/H04 的
RTL A/B。BRAM/DSP 仍有大量余量，但 81.23% slice occupancy、宽 mux 与约 77% route delay
决定了继续提高频率不能靠简单增加单元；扩大 ROB/PRF、cache 或 walker 前必须重新测量
它们对 packing 和 top-N path 的影响。

### 2026-08-04：优化决策——计数器定义与实验合同

这一讲只定义 M01 的测量合同，不增加 RTL/harness probe，也不运行新仿真。它解决的核心
问题是：观察到 clean Linux 窗口约 `0.557 IPC`、100 MHz 只有 44 ps setup 余量以后，
怎样把“周期损失”和
“频率损失”分别归因，再用同一套规则选择 F01、F03、B01、E01、R02、H01 等候选。

#### 先明确计数器回答什么

一个计数器应描述四件事：**事件、暴露条件、分母、作用区间**。例如“IQ 满了 10,000
拍”没有直接性能含义；只有当 dispatch 本拍确实要向该 IQ 投递、又因 full/credit 被拒绝
时，才形成 exposed stall。相同原则适用于 ROB full、MSHR full、AXI backpressure 和
FreeList empty。

最基本的退休账本为：

```text
Hretire[k] = ROI 内每拍退休 k 条指令的周期数，k in 0..3
cycles      = Hretire[0] + Hretire[1] + Hretire[2] + Hretire[3]
instructions = 1*Hretire[1] + 2*Hretire[2] + 3*Hretire[3]
IPC          = instructions / cycles
unused_commit_slots = 3*cycles - instructions
```

`Hretire[0]` 用来分析完全没有架构进展的拍；`unused_commit_slots` 还包含只退休 1/2 条的
部分带宽损失。两者都需要，因为只统计零提交拍会掩盖长期“两宽化”的行为。

#### 三类观测实现

| 层级 | 用途 | 初始实现约束 |
| --- | --- | --- |
| R0：现有可见信号 | commit、recovery、frontend occupancy，以及模块内部已有的 dispatch/LSU/SDQ occupancy 等 | harness 直接采样；不改变生产 RTL |
| R1：仿真专用 probe | valid/ready/fire、queue full、ROB head 状态、cache/TLB/MSHR 状态和具体阻塞原因 | 只观察，绝不能反馈到 DUT ready/valid、flush、选择或状态更新 |
| R2：带身份的事件 trace | 配对 branch、DIV、load、cache miss、AXI transaction 的开始/结束，计算 latency histogram | 使用 ROB pointer+generation/epoch、MSHR ID 或 request generation，不能只靠数组 index |
| H：硬件计数器 | 将少量已证明高价值的指标带到远程开发板 | 后置工作；需重新综合实现并计入 LUT/FF/WNS，不作为第一版基线工具 |

第一版优先生成仿真结束时的结构化 JSON/CSV。长时间仿真不依赖全波形；波形只截取由计数器
定位到的短窗口。instrumented simulation 必须和无 probe 版本比较 commit trace、DiffTest
结果、终止 PC/退出状态与总周期，证明观察逻辑没有改变 DUT 行为。

#### ROI 与可重复性合同

ROI（Region of Interest）决定哪些周期进入分母。每个结果至少记录：

```text
cpu_source_commit / cpu_repository_head / generated_rtl_sha256 / chiplab_commit
simulation_build_kind / software_image_sha256 / benchmark_name
ROI start/end marker、起止 PC/commit sequence、总周期与总指令
Verilator/Java/SBT 版本、memory-delay 配置、random seed
timeout、DiffTest 状态、terminal PC/syscall、UART 摘要
counter schema version / probe build hash / result file sha256
```

性能程序以固定的 workload start/end 为 ROI；Linux 启动则按 reset、kernel entry、用户态
入口、shell 等阶段分别统计，不能把不同启动里程碑的总 IPC 混合比较。冷启动、cache/TLB
初始状态和随机 memory delay 必须固定或显式记录。若 ROI 起止时仍有在途请求，配对型计数
要报告 `started/completed/outstanding/cancelled`，不能把截断 transaction 当作无限 latency。

本地 Verilator cycles 是诊断证据。只有仿真 profile、软件和 memory model 与目标 performance
profile 匹配时，才适合做候选间 cycle 比较；它仍不能替代完整 SoC 实现或开发板结果。

#### 计数器矩阵

计数命名建议使用稳定的分层 key；每个 `_stall` 都必须写清楚 exposure predicate。

| 域 | 必需计数/直方图 | 回答的问题 |
| --- | --- | --- |
| Global/retire | `cycle.roi`、`retire.inst`、`retire.width[0..3]`、commit slot-loss reason、异常/ERTN/idle | IPC 低来自零提交，还是长期只提交 1/2 条？ |
| Stage flow | fetch group、decode、rename、dispatch、每端口 issue、每 lane completion、commit 的 offered/accepted slots | 最早在哪一级开始丢带宽？三宽各级实际利用率是多少？ |
| Frontend | translation request/response fire、相邻 accept interval、uTLB hit/miss、main-walk queue/latency、L1I request/hit/miss/critical return、IBUF occupancy `[0..8]`、decode-starved cycles | F01/H03/H07 是真实约束，还是后端不消费造成的表象？ |
| Branch | retired branch 按 conditional/direct/indirect/call/return 分类、BTB/PHT/RAS hit、mispredict、同拍丢 training、resolve→commit→redirect→first-fetch→first-commit latency | B01/B02/B03/K01 各自最多能省多少拍？ |
| Rename/allocation | accepted prefix `[0..3]`、ROB/FreeList/LQ/STQ/dispatchQ raw-full 与 exposed-block、`freeCount`/ROB occupancy histogram | R02、K02、A01、L03 是否有窗口或 credit 证据？ |
| Dispatch/IQ | dispatch window 和四个 IQ occupancy、ready-entry count、enqueue/dequeue、ready-but-not-selected、selected-but-FU-blocked、operand-not-ready、W01 tag conflict | issue 损失来自依赖、IQ credit、贪心 routing，还是 FU capability？ |
| Execution | ALU/branch/MUL/DIV/LSU 动态数；issue→completion latency；每端口 busy/idle；completion arbitration；DIV operand 分类 | E01/E03/E04/T03 的动态收益上界有多大？ |
| ROB/commit | ROB occupancy、head age、head incomplete FU class、complete-but-side-effect-blocked、commit stop lane/reason、recovery cause | 哪种 producer 真正在阻塞架构进展？扩大 ROB 是否能隐藏它？ |
| LSQ | LDQ/STQ/SDQ occupancy/full，Load 等 translation、unknown older Store、overlap-data、partial coverage、cache request、uncached ordering，Store 等 data/head/maintenance/B response；另记 older Store MAT-unknown 时年轻访存是否先 fire | 先验证 C04/C06；再回答 L01/L02/L03/U01-U03 分别对应多少 exposed cycles |
| Cache/TLB | I/D uTLB 与 main TLB、L1I/L1D/L2 hit/miss/merge、dirty victim、critical beat、MSHR occupancy `[0..4]`/full-block、refill latency | 容量、命中吞吐、MSHR 数和 critical return 谁更重要？ |
| AXI/memory | AR/AW/W/R/B fire 与 `valid&&!ready`、active read IDs、read/write bytes、read-blocked-by-write、write-wait-bus-idle、BRESP/RRESP | H01/H05 与 memory-delay 模型造成了多少可见等待？ |
| Serial/uncached | DBAR/IBAR/CACOP、SUC load/store 次数；wait-head、alias maintenance、drain、request handshake、response 的分段 latency | 低频 MMIO 是否真的贡献了足够周期，DMA 类方向是否有意义？ |
| Physical | actual MHz、WNS/TNS/WHS/THS、top-N path family、logic/route delay、LUT/FF/Slice/BRAM/DSP、strategy/seed | cycle 优化是否以频率、packing 或 route 为代价？ |

Raw occupancy 和 raw-full 可以重叠，用来理解结构状态；决策时优先看 exposed-block。例如：

```text
rob_full_exposed = renameValid.orR && rename 因 ROB credit 不足没有接受
mshr_full_exposed = demand miss 等待 allocation && 没有可分配 MSHR
iq_full_exposed[p] = dispatch head 需要 port p && 对应 IQ credit 阻止接受
axi_ar_backpressure = ARVALID && !ARREADY
```

#### 两套对账：原始事件与互斥主因

很多事件会同拍出现：frontend 可以为空，ROB head 同时等 Load，AXI 又在 backpressure。
因此结果文件同时保留：

1. **raw event counters**：允许重叠，描述每个机制出现了多少次。
2. **exclusive loss stack**：每个待解释周期/slot 只归一个主因，保证可以与总数对账。

零提交周期的第一版互斥分类按离架构进展最近的阻塞点判断：

```text
if outside ROI or reset:
  excluded
else if retired_count > 0:
  progress[retired_count]
else if redirect/recovery/exception transition:
  recovery
else if ROB empty:
  backend_starved
    -> frontend translation / L1I / IBUF / decode-rename / dispatch 子分类
else if ROB head incomplete:
  head_wait
    -> ALU / MUL / DIV / load-translation / load-order / cache-TLB / AXI 子分类
else if ROB head complete but cannot retire:
  commit_side_effect
    -> store / uncached / DBAR-IBAR / CACOP / CSR-serial / interrupt 子分类
else:
  unclassified
```

这是统一的统计约定，不声称同拍只有一个物理问题。`unclassified` 必须长期接近 0；若它
显著存在，先修分类，不能把它分摊给预期中的“最大瓶颈”。对每拍未使用的 3-wide commit
slot 另建 `commit_slot_loss`：记录最老未退休 lane 是不存在、incomplete、serializing、
recovery stop，还是 commit width 已满。这样 1/2-wide 的损失也能对账。

#### Latency histogram 与事件配对

均值会掩盖长尾，第一版使用对数区间，例如 `1, 2, 3-4, 5-8, 9-16, 17-32, 33-64,
65-128, 129-256, >256`，同时保存 count/sum/max。至少配对：

- translation request→response、L1I/L1D request→response、miss→critical return→full install；
- branch resolve→ROB head→redirect→correct-path first fetch/commit；
- MUL/DIV issue→completion，DIV 再按 0、`+/-1`、2 的幂、普通数分类；
- Load allocate→address ready→translation→cache request→completion→commit；
- Store allocate→address/data ready→ROB head→maintenance→AXI B→commit；
- AXI AR→first/last R、AW→last W→B。

flush/cancel 是显式 outcome。ROB index、LQ/STQ index 或 MSHR ID 会重用，配对键必须加入
generation/epoch；否则一次旧 response 可能被错误计到新事务，既污染性能数据，也掩盖
C03/C07 这类正确性问题。

#### 必须自动检查的守恒式

每份结果至少验证以下 invariant，失败时整份性能数据无效：

```text
sum(retire.width histogram) == ROI cycles
sum(k * retire.width[k]) == retired instructions
exclusive zero-retire causes == retire.width[0]
sum(commit slot-loss causes) == 3*cycles - retired instructions
accepted slots <= stage width * ROI cycles
cache hit + miss-allocate + miss-merge + exception/drop == classified accepted requests（加 ROI 边界 outstanding）
transaction start == complete + cancel + outstanding_at_end - outstanding_at_start
queue occupancy_next == occupancy + accepted_enqueue - accepted_dequeue（逐队列检查）
```

计数器用 64 bit 或显式饱和检测；溢出不能静默回绕。错误路径 uop 可以进入 fetch/issue
raw counter，但绝不能进入 retired instruction。每个 counter schema 都要有版本，避免字段
同名而 predicate 已变化。

#### 从基线到候选的实验阶梯

| 阶段 | 工作 | 通过条件 |
| --- | --- | --- |
| M0 | 用现有 commit/occupancy 信号形成最小基线 | 总周期、指令数、IPC 与现有日志一致 |
| M1 | 加仿真专用 R1 probe 和守恒检查 | elaboration/compile、定向测试、DiffTest、commit trace 与无 probe 基线一致 |
| M2 | 固定软件和 seed 跑各 benchmark/Linux 分段 | 每项保存 JSON/CSV、日志、hash；无 unclassified/overflow/invariant failure |
| M3 | 对最大 raw/exclusive 事件截取短 trace/FST | 波形验证 counter predicate 与真实握手一致 |
| M4 | 一次只做一个候选 A/B | 同 ROI、软件、seed；报告每 benchmark cycles 和原因计数变化 |
| M5 | 候选通过正确性后做 standalone/full-SoC Vivado | 使用新 RTL 的资源、WNS/TNS、top paths，不继承旧 44 ps |
| M6 | 里程碑候选进入 function/performance SoC 与远程板流程 | build/profile/software 匹配，保留 bitstream、运行日志和 hash |

随机 AXI delay/backpressure 的实验至少保留相同 seeds 做 paired comparison，再增加新 seeds
检查稳健性。性能平均不能掩盖单项明显回退；每个 benchmark 同时报告 absolute cycles、
相对变化、IPC、事件差值和退出状态。

#### 如何从计数器做优化决策

候选的理论周期收益上界先由事件数约束：

```text
opportunity_cycles = event_count * 每次最多可消除的 exposed latency
cycle_speedup_upper = C0 / (C0 - opportunity_cycles)
```

例如 DIV fast path 即使每次省 31 拍，动态 DIV 很少时总收益仍小；ROB full 很常见但 rename
没有因此停顿时，R02 也没有直接收益证据。上界很低的候选不进入高风险 RTL 实验。

最终比较周期和频率的联合结果：

```text
normalized_time_ratio = (C1 / C0) / (f1 / f0)
```

小于 1 才表示执行时间改善。频率数据来自 matching RTL 的完整 SoC implementation；
Verilator counter 无法证明 Fmax。正确性 gate 永远先于这个比值：C01-C08 任一失败时，先
修复并重建 baseline，不能用更好 IPC 接受错误行为。

计数结果到候选的第一版路由如下：

| 主要证据 | 优先候选 |
| --- | --- |
| 热 uTLB/L1I hit 仍有长 accept interval，IBUF 经常为 0 | F01、H03、H07；同时观察 F03 timing |
| mispredict MPKI 或 resolve→redirect exposed cycles 高 | B01、B02、B03、K01 |
| DIV head-wait 或 P1 HOL 显著 | E01、E03 |
| ROB full exposed 高，且 head blocked 时存在大量 younger-ready uop | T02、R02 |
| IQ ready-but-not-issued/dispatch matching loss 高 | W01、E04、D01、D02、Q01 |
| Store alias/partial forwarding或 oldest Load HOL 高 | L01、L02；容量 exposed 后才考虑 L03 |
| dirty write、read-blocked-by-write、AXI idle gap 高 | H01、H05 |
| uncached 的 maintenance/drain/B-response 占比高 | U01、U02、U03，先满足平台与错误合同 |
| cycles 已改善但 front/cache route path 恶化 | F03、H04、P01/P02 的物理 A/B，按联合比值取舍 |

本讲结论：M01 的产物首先是一份可对账、可复现、不会反馈进 DUT 的测量合同。实际工作的
第一步是 M0/M1，而不是直接扩 ROB、PRF、cache 或执行端口。它应先证明 `IPC < 1` 的损失
到底落在 frontend starvation、ROB head、branch recovery、LSQ/cache/AXI 还是 serializing；
随后才让最大 exposed opportunity 驱动一个独立 A/B。频率侧继续以 matching RTL 的完整
SoC routed report 为准，与仿真 cycle 通过 `cycles/frequency` 合并决策。

上述正确性 gate、M0-M6 阶梯、候选路由和完整 SoC 接受条件已经提取到
`docs/verification-workflow.md`。本笔记继续保留理论推导；候选状态和效果只在
`docs/optimization-candidates.md` 维护。

## 7. 证据索引

- 2nd-pass 语义审计区间：`cpu/` 的
  `d9bab16ef46540eb3348b0781afc4d0949f28adc..6bbca9b330ba8d886c888e2804f70b95be18e4cd`；
  后续验证证据的 source 为 `60fba481...`、implementation 为 `6bbca9b...`，不要用
  docs-only HEAD 替代 source identity 或扩大审计终点
- 指令融合的 ISA 语义：`docs/References/2025032109211238668.龙架构32位精简版参考手册_r1p04.pdf`
  第 2.1.4、2.2.1.2--2.2.1.8、2.2.3、2.2.4、6.1 和 6.2.3 节；FUS01 结构判断另核对
  `OooUops.scala`、`ReorderBuffer.scala`、`OooBackend.scala` 与 `OooCommitAdapter.scala`。CPU 侧
  正在并行开发，本项只记录结构合同，不把读取时的 HEAD 或旧 timing 数字当作稳定基线
- 当前参数：`cpu/src/main/scala/miku/core/OooCoreConfig.scala`
- perf20 地址模式：`chiplab/software/examples/nscscc_perf/start.S` 与
  `chiplab/software/bsp/env/separate.lds`；direct/DMW 快路径结构核对
  `cpu/src/main/scala/miku/privileged/AddressTranslationUnit.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala` 和
  `cpu/src/main/scala/miku/backend/OooBackend.scala`
- 顶层数据流：`cpu/src/main/scala/miku/core/OooCore.scala`
- 历史验证状态：`docs/archive/nscscc-cpu-final-docs/refactor/status.yml`；当前结果以候选清单为准。
- 当前优化与测试合同：`docs/verification-workflow.md`
- 当前候选状态与效果：`docs/optimization-candidates.md`
- 本轮实现记录已随旧 CPU 仓库归档；迁移来源见 `docs/archive/migration-provenance.md`。
- 同平台历史 timing-fail 产物已在稳定版确认后清理，不作为保留证据。
- 当前稳定归档目录：`Stable_Backup/`（其中的具体候选以各自 `manifest.txt` 为准）。
- Standalone 层次资源属于可再生输出，保存于 `build/vivado/`，不作为当前设计的固定证据。
- 历史参考框图：`chiplab/IP/myCPU/doc/picture/框图.svg`；当前结构以本文和 Scala 源码为准
- Decode：`cpu/src/main/scala/miku/frontend/WideDecode.scala`、
  `cpu/src/main/scala/miku/frontend/La32rDecoder.scala`
- Rename 边界与 uop：`cpu/src/main/scala/miku/backend/DecodeRenameBuffer.scala`、
  `cpu/src/main/scala/miku/backend/OooUops.scala`
- RAT/PRF/FreeList：`cpu/src/main/scala/miku/backend/RegisterStructures.scala`
- 后端整组分配：`cpu/src/main/scala/miku/backend/OooBackend.scala`
- ROB hot/cold payload：`cpu/src/main/scala/miku/backend/ReorderBuffer.scala`
- Dispatch/IQ/LSQ 容量：`cpu/src/main/scala/miku/backend/DispatchQueue.scala`、
  `cpu/src/main/scala/miku/backend/IssueQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueueAllocator.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`、
  `cpu/src/main/scala/miku/backend/StoreDataQueue.scala`
- Dispatch timing cut 记录：`docs/archive/refactor/20260728-dispatch-window-timing/iteration.md`
- Compact IQ 记录：`docs/archive/refactor/20260727-compact-issue-queue/iteration.md`
- Wakeup/PRF/执行路径：`cpu/src/main/scala/miku/backend/OooBackend.scala`、
  `cpu/src/main/scala/miku/backend/RegisterStructures.scala`、
  `cpu/src/main/scala/miku/execute/OooExecutionCluster.scala`
- ALU 操作与组合边界：`cpu/src/main/scala/miku/execute/Alu.scala`
- Completion/ROB complete/commit 边界：
  `cpu/src/main/scala/miku/backend/OooUops.scala`、
  `cpu/src/main/scala/miku/backend/ReorderBuffer.scala`
- Commit、恢复与系统副作用：
  `cpu/src/main/scala/miku/backend/OooBackend.scala`、
  `cpu/src/main/scala/miku/backend/OooCommitAdapter.scala`、
  `cpu/src/main/scala/miku/core/OooCore.scala`、
  `cpu/src/main/scala/miku/core/OooCoreSystem.scala`
- Commit/恢复定向测试：
  `cpu/src/test/scala/miku/backend/ReorderBufferSpec.scala`、
  `cpu/src/test/scala/miku/backend/OooCommitAdapterSpec.scala`、
  `cpu/src/test/scala/miku/backend/LoadStoreQueueSpec.scala`、
  `cpu/src/test/scala/miku/core/OooCoreSpec.scala`
- Retirement 状态时序切分记录：
  `docs/archive/refactor/20260727-1735-retirement-state-timing/iteration.md`
- Store data 解耦与 LSQ：
  `cpu/src/main/scala/miku/backend/StoreDataQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueueAllocator.scala`
- LSQ/SDQ 定向测试：
  `cpu/src/test/scala/miku/backend/LoadStoreQueueSpec.scala`、
  `cpu/src/test/scala/miku/backend/StoreDataQueueSpec.scala`
- Nonblocking MSHR、response epoch 与 LSQ 时序记录：
  `docs/archive/refactor/20260725-nonblocking-mshr/iteration.md`、
  `docs/archive/refactor/20260728-cache-response-epoch/iteration.md`、
  `docs/archive/refactor/20260725-rob-completion-timing/iteration.md`
- DBAR/IBAR memory epoch：
  `docs/archive/refactor/20260803-dbar-ibar-linux-semantics/iteration.md`
- SUC/MAT 架构合同：
  `docs/References/2025032109211238668.龙架构32位精简版参考手册_r1p04.pdf` 第 2.1.7、
  2.1.9、5.3 节
- 固定 nscscc-team 通用 DMA 接线：
  `chiplab/chip/soc_demo/nscscc-team/soc_top.v`、`chiplab/IP/DMA/dma.v`
- Cache array 与三级 controller：
  `cpu/src/main/scala/miku/memory/CacheArray.scala`、
  `cpu/src/main/scala/miku/memory/L1InstructionCache.scala`、
  `cpu/src/main/scala/miku/memory/L1DataCache.scala`、
  `cpu/src/main/scala/miku/memory/L2Cache.scala`
- Shared MSHR 与 AXI bridge：
  `cpu/src/main/scala/miku/memory/SharedReadMshrRouter.scala`、
  `cpu/src/main/scala/miku/memory/SharedCacheHierarchy.scala`、
  `cpu/src/main/scala/miku/memory/AxiLineBridge.scala`
- Cache/AXI 定向测试：
  `cpu/src/test/scala/miku/memory/L1InstructionCacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/L1DataCacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/L2CacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/SharedCacheHierarchySpec.scala`、
  `cpu/src/test/scala/miku/memory/AxiLineBridgeSpec.scala`
- MMU/TLB/CSR 实现：
  `cpu/src/main/scala/miku/privileged/AddressTranslationUnit.scala`、
  `cpu/src/main/scala/miku/privileged/HierarchicalTlb.scala`、
  `cpu/src/main/scala/miku/privileged/CsrFile.scala`、
  `cpu/src/main/scala/miku/core/OooCoreSystem.scala`
- 翻译 owner 与 flush/drain 协议：
  `cpu/src/main/scala/miku/frontend/OooFrontend.scala`、
  `cpu/src/main/scala/miku/backend/OooBackendWithExecution.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`
- TLB/CSR/异常定向测试：
  `cpu/src/test/scala/miku/privileged/AddressTranslationUnitSpec.scala`、
  `cpu/src/test/scala/miku/privileged/CsrFileSpec.scala`、
  `cpu/src/test/scala/miku/core/OooCoreIntegrationSpec.scala`
- Hierarchical TLB 历史设计与时序记录：
  `docs/archive/refactor/20260725-ooo-hierarchical-tlb/iteration.md`、
  `docs/archive/refactor/20260727-tlb-walk-result/iteration.md`；`d9bab16...` 到当前仅
  `7d35545` 直接修改 TLB，实现 micro-result masked merge
- TLB/CACOP 架构定义：
  `docs/References/2025032109211238668.龙架构32位精简版参考手册_r1p04.pdf` 第 4.2.2、
  4.2.3、5.2、5.4、6、7.4、7.5 节
- Critical return 与 I-side timing 记录：
  `docs/archive/refactor/20260723-ooo-l1i-critical-group/iteration.md`、
  `docs/archive/refactor/20260726-wide-backend-critical-load/iteration.md`、
  `docs/archive/refactor/20260728-l1i-parallel-predecode-timing/iteration.md`
- 执行单元完成仲裁测试：
  `cpu/src/test/scala/miku/execute/OooExecutionClusterSpec.scala`
- Divider wakeup 取舍记录：`docs/archive/refactor/20260726-divider-wakeup-timing/iteration.md`
- 历史 Vivado placed 资源仅在对应 `Stable_Backup/` 候选目录中保留。

## 附录 A：缩写与术语对照

本表以本项目当前实现和本学习记录中的用法为准。`I$`、`D$` 是工程中常见的
cache 简写；`p` 前缀表示 physical（物理）而不是 architectural（架构）寄存器。

### A.1 前端与控制流

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| IBUF | Instruction Buffer | 暂存从取指侧送来的指令槽，向三路 decode 提供顺序指令。 |
| PC | Program Counter | 当前指令地址；预测、取指、异常和 branch redirect 的核心身份。 |
| BTB | Branch Target Buffer | 按 branch PC 记录目标、类型等信息的预测表。 |
| PHT | Pattern History Table | 保存 2-bit 饱和计数器的条件分支方向预测表。 |
| GHR | Global History Register | 最近条件分支方向的全局历史，用于 PHT 索引。 |
| RAS | Return Address Stack | 为 call/return 嵌套保存返回地址的小栈。 |
| BTFNT | Backward Taken, Forward Not Taken | 静态方向预测规则：向后 branch 预测跳转，向前 branch 预测不跳转。 |
| MPKI | Mispredictions Per Kilo Instructions | 每千条指令的预测错误数；这里通常指 branch MPKI。 |

### A.2 Rename、乱序窗口与执行

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| uop | Micro-operation | 后端跟踪的动态操作；当前一个 LA32R 指令通常对应一个 uop。 |
| Macro-op fusion | Macro-operation Fusion | 在不改变两条架构指令身份的前提下，让相邻指令共享后端执行项；若进一步共享 ROB entry，还需表示两次架构退休。 |
| Fused uop | Fused Micro-operation | 后端承载两条架构指令语义的复合执行项；本笔记的 FUS01 首先考虑两个 ROB entry 共用一个 fused uop。 |
| RAT | Register Alias Table | 架构寄存器到最新物理寄存器的映射表，也称 rename map。 |
| PRF | Physical Register File | 保存 `p0..p63` 实际数据值的物理寄存器文件。 |
| GPR | General-Purpose Register | LoongArch 的通用架构寄存器 `r0..r31`。 |
| `psrc` | Physical Source Register | uop 的物理源寄存器编号，如 `psrc1`、`psrc2`。 |
| `pdst` | Physical Destination Register | uop 新分配的物理目标寄存器编号。 |
| `oldPdst` | Old Physical Destination Register | 写目标前原有映射，在该写指令 commit 后才可归还 FreeList。 |
| ROB | Reorder Buffer | 按程序顺序保存 in-flight uop 的完成、异常和提交信息，提供精确状态。 |
| IQ | Issue Queue | 等待操作数 ready 和执行端口可用的发射队列。 |
| WB | Writeback | 执行结果经过完成校验后写入 PRF，并把物理寄存器标记为 ready。 |
| EX | Execution | uop 被功能单元接受并进行计算或启动多拍操作的执行阶段。 |
| CAM | Content-Addressable Memory | 按内容并行匹配的存储思想；IQ wakeup 的多 tag 对多 `psrc` 比较具有 CAM 式行为。 |
| FU | Functional Unit | 执行单元，如 ALU、branch、MUL/DIV、LSU、CSR 单元。 |
| ALU | Arithmetic Logic Unit | 整数算术、逻辑、比较和部分地址/目标计算单元。 |
| AGU | Address Generation Unit | 计算 load/store 虚拟地址的地址生成单元。 |
| MUL | Multiply | 整数乘法操作；当前使用可每拍接收、一级结果寄存的 DSP 路径。 |
| DIV | Divide | 整数除法/求余操作；当前使用逐位迭代除法器。 |
| II | Initiation Interval | 同一流水单元连续接受两项新工作的最小周期间隔。 |
| RAW | Read After Write | 真数据相关：消费者必须等生产者的结果。 |
| WAR | Write After Read | 反相关：rename 通过分配新 `pdst` 消除。 |
| WAW | Write After Write | 输出相关：rename 通过分配不同 `pdst` 消除。 |

### A.3 访存与系统状态

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| LSU | Load-Store Unit | 负责 load/store 地址、访存请求、转发和完成的执行路径。 |
| LSQ | Load-Store Queue | load/store 的统一乱序跟踪结构；本项目由 LQ、STQ 等状态构成。 |
| LQ / LDQ | Load Queue | 跟踪在途 load 的年龄、地址、完成和顺序约束。 |
| STQ | Store Queue | 跟踪在途 store 的地址、字节使能、提交可见性等状态。 |
| SDQ | Store Data Queue | 独立保存等待生产者结果的 store data，允许地址和数据就绪解耦。 |
| VA / PA | Virtual Address / Physical Address | MMU 翻译前后的地址；LSQ alias 判断最终必须与物理内存位置一致。 |
| VPN / VPPN | Virtual Page Number / Virtual Pair Page Number | 虚页号/虚双页号；VPPN 省略用于选择奇偶 half 的那一位。 |
| PPN | Physical Page Number | TLB half 中的物理页号，与 VA 的页内 offset 拼接成 PA。 |
| ASID | Address Space Identifier | 地址空间标识，允许不同进程的相同 VA 在 TLB 中并存；global entry 忽略它。 |
| MAT | Memory Access Type | CRMD、DMW 或页表/TLB 中的存储访问类型；LA32R 精简版中 0 为 SUC、1 为 CC、2/3 保留。 |
| SUC | Strongly-ordered UnCached | 强序非缓存访问；不可推测，按程序次序直接访问最终存储对象，完成前不能开始下一访存。 |
| CC | Coherent Cached | 一致可缓存访问；允许由维护一致性的 cache 服务。 |
| MMIO | Memory-Mapped Input/Output | 用普通 load/store 地址访问设备寄存器；通常映射为 SUC。 |
| DMA | Direct Memory Access | 由设备 master 在内存与外设间批量搬运数据；非一致 DMA 需要软件 cache 维护。 |
| PIPT | Physically Indexed, Physically Tagged | cache 的 index 和 tag 都取自物理地址；当前 L1I/L1D/L2 属于这一类。 |
| MSHR | Miss Status Holding Register | 合并并跟踪同一 cache miss 的未完成请求及 refill 状态。 |
| MLP | Memory-Level Parallelism | 同时在途且可重叠服务的独立访存数量。 |
| AMAT | Average Memory Access Time | 由各级 hit time、miss rate 和 miss penalty 加权得到的平均访存时间。 |
| CWF | Critical-Word-First | refill burst 优先返回 demand 所需 word/beat，再完成其余 line。 |
| Early Restart | Early Restart | 关键数据到达后立即恢复 CPU，不等待整条 cache line 安装完成。 |
| WB（cache policy） | Write-Back | Store 先更新 cache dirty line，通常在该 line 被替换或维护时才写下一级；与流水线 Writeback 缩写同名。 |
| WT | Write-Through | 更新某级 cache 时同时把数据写向下一级；当前 L2 接收 L1D eviction 使用该策略。 |
| I$ / L1I | Instruction Cache / Level-1 Instruction Cache | 一级指令 cache。 |
| D$ / L1D | Data Cache / Level-1 Data Cache | 一级数据 cache。 |
| L2 | Level-2 Cache | 片上二级 cache，本项目承担 L1 miss 的后续服务。 |
| AXI | Advanced eXtensible Interface | SoC 内 CPU、cache、DDR 等之间使用的 AMBA 总线协议。 |
| BRESP | Write Response | AXI B 通道返回的写事务响应；当前 uncached Store 等它返回后才 completion。 |
| Posted Write | Posted Write | 写请求进入不可撤销缓冲后先向 CPU 宣告完成、再在后台到达设备；会改变错误归因与 drain 合同。 |
| MMU | Memory Management Unit | 处理分页、地址翻译、权限和相关异常的单元。 |
| ATU | Address Translation Unit | 本项目接受 I/D VA 请求，选择 direct/DMW/TLB 路径并产生 PA、MAT 与翻译异常。 |
| TLB | Translation Lookaside Buffer | 缓存虚拟页到物理页翻译结果的小型表。 |
| uTLB / micro-TLB | Micro Translation Lookaside Buffer | I/D 各自的 4-entry 热翻译缓存；miss 后由共享 Main TLB walker 填充。 |
| DMW | Direct Mapping Window | 映射地址模式下优先于 TLB 的直接映射窗口，配置 VSEG/PSEG、PLV 许可和 MAT。 |
| PLV | Privilege Level | LoongArch 特权等级；本项目实现 PLV0 内核态与 PLV3 用户态。 |
| CSR | Control and Status Register | 控制、异常、特权、计时器和 MMU 状态寄存器。 |
| ECODE / ESUBCODE | Exception Code / Exception Subcode | ESTAT 中记录的异常主码/子码，用于区分中断、页错误、对齐、非法指令等。 |
| TLBR | TLB Refill Exception | Main TLB 无匹配项时的重填异常，跳转到独立 TLBRENTRY。 |
| PIL / PIS / PIF | Page Invalid for Load / Store / Fetch | 命中 TLB entry 但对应 half 的 V=0 时，按访问类型产生的页无效异常。 |
| PME / PPI | Page Modification Exception / Page Privilege Illegal | store 页不可写、或当前 PLV 权限不足时的翻译异常。 |
| LL/SC | Load-Linked / Store-Conditional | 原子读-条件写指令对；SC 是否成功依赖 reservation。 |
| DBAR / IBAR | Data Barrier / Instruction Barrier | 数据/指令屏障，约束访存或取指可见性与完成顺序。 |
| CACOP | Cache Operation | 指定 cache 层、Store Tag/Index/Hit 模式和目标地址的架构 cache 维护指令。 |
| INVTLB | Invalidate TLB | 按 op 0~6 的 global/ASID/VA 条件失效主 TLB，并清除派生 micro state。 |

### A.4 性能、验证与 FPGA 实现

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| IPC | Instructions Per Cycle | 每周期提交的指令数；本核理论提交上限为 3。 |
| CPI | Cycles Per Instruction | 每条指令平均周期数，近似等于 IPC 的倒数。 |
| ROI | Region of Interest | 一次实验中纳入周期、指令和事件统计的明确区间；起止 milestone 必须固定。 |
| DUT | Design Under Test | 被仿真或验证的处理器/SoC 设计；probe 只能观察 DUT，不能反馈控制。 |
| Fmax | Maximum Frequency | 时序约束下可达到的最高稳定时钟频率。 |
| WNS | Worst Negative Slack | 所有 setup/hold 路径中最差的时序余量；负值表示未收敛。 |
| TNS | Total Negative Slack | 所有负 slack 的总和，用于衡量整体时序违例规模。 |
| LUT | Look-Up Table | FPGA 的组合逻辑基本资源。 |
| FF | Flip-Flop | FPGA 的时序存储基本资源。 |
| BRAM | Block RAM | FPGA 的片上块存储资源。 |
| DSP | Digital Signal Processing Slice | FPGA 的乘加等专用算术资源。 |
| DRC | Design Rule Check | Vivado 对实现、时钟、IO 等设计规则的检查。 |
| DCP | Design Checkpoint | Vivado 保存的设计检查点，可用于实现或增量实现参考。 |
| RTL | Register-Transfer Level | 描述寄存器与组合逻辑间时序行为的硬件设计层次。 |
| SoC | System on Chip | CPU、cache、DDR、外设与互连整合后的完整芯片系统。 |
| CDC | Clock Domain Crossing | 跨时钟域信号传递；需要专门的同步或异步 FIFO 设计。 |
