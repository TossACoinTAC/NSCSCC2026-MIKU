# MIKU 架构记录：Backend 执行与提交

> 本文保留原架构长文中的历史讨论。文中的“当前”只指对应记录形成时的源码身份，
> 现在的验证范围以 [status.md](../status.md) 为准。

[返回架构总览](../architecture.md)

## 讨论记录

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
