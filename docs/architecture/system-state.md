# MIKU 架构记录：MMU、TLB、CSR 与系统状态

> 本文保留原架构长文中的历史讨论。文中的“当前”只指对应记录形成时的源码身份，
> 现在的验证范围以 [status.md](../status.md) 为准。

[返回架构总览](../architecture.md)

## 讨论记录

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
