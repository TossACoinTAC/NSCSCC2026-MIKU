# MIKU 架构记录：性能、FPGA 实现与测量方法

> 本文保留原架构长文中的历史讨论。文中的“当前”只指对应记录形成时的源码身份，
> 现在的验证范围以 [status.md](../status.md) 为准。

[返回架构总览](../architecture.md)

## 讨论记录

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
