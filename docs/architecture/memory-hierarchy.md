# MIKU 架构记录：LSQ、Cache 层次与 AXI

> 本文保留原架构长文中的历史讨论。文中的“当前”只指对应记录形成时的源码身份，
> 现在的验证范围以 [status.md](../status.md) 为准。

[返回架构总览](../architecture.md)

## 讨论记录

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
