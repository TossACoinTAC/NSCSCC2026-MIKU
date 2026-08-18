# 频率优先全核深入审计：1st pass 与 2nd pass 候选准入

本文承接 2026-08-18 的 Observer v9 容量重测和候选清理，进一步做全核源码与物理
路径审计。目标排序已经明确为：先提高可实现的 CPU 主频，再捡取不会扩大关键控制锥
的 IPC 收益。本文只修改文档，不改变 RTL，也不再为 Observer 增加无法回答 WNS 的
测量。

## 1. 先给结论

本轮最重要的结论来自与当前结构最接近的 `origin/main + B02-F` matching
implementation，而不是来自静态门数或 Observer 猜测：

| 项目 | 结果 |
| --- | --- |
| 时钟 | `cpu_clk`，100 MHz，10.000 ns period |
| setup WNS/TNS | `-0.047/-0.047 ns`，仅 1 个 setup failing endpoint |
| hold WNS | `+0.052 ns` |
| 最差路径 | `ROB candidatePointer` 到 `LSQ allocator loadOccupancy` |
| 数据路径延迟 | `9.858 ns`，logic `1.977 ns`，route `7.881 ns` |
| 路由占比 | `79.945%` |
| 逻辑级数 | 13 级，含 `MUXF7/MUXF8` |
| 资源 | 94,982 LUT、60,174 FF、56 RAMB36、21 RAMB18、8 DSP |

这条路径的含义是：当前真正值得先拆的是“提交候选如何形成 LSQ 释放资格，以及
该资格如何进入 allocator 的占用更新”，不是抽象地把整个 ROB 标成热路径。近似
实现的第二类路径还包括：

- `LoadStoreQueue` 的 `requestSent` 到 `scheduledLoad` 多位状态写使能，约
  `9.64--9.71 ns`，路由占比约 `75%`；
- LSQ `scheduledLoad` 的 ROB 指针/完成信息回到 ROB head-completion bypass，约
  `9.846 ns`，路由占比约 `72%`；
- 旧 baseline 中 L1I tag RAM 到 data RAM enable 约 `9.425 ns`，但在 B02-F
  matching run 中已迁移出最差路径，不能把旧路径直接当作当前路径。

因此 2nd pass 的首批顺序调整为：

1. LSQ allocator 的释放/占用更新边界；
2. LSQ pending-load 与 `requestSent` 状态写使能的局部化；
3. 只有 current matching top-N 再出现时，才恢复 L1I/L1D RAM enable 或 IQ
   wakeup/select 候选；
4. multiplier 先做独立的 FPGA 技术映射实验；PHT、TLB、AXI 和扩容类方案暂缓。

这不是对当前 HEAD 的 WNS 宣称。当前工作树没有与该实现完全匹配的 Vivado run；
下一轮真正的第一个物理实验仍必须是当前源码的 100 MHz direct full baseline。

## 2. 证据身份与边界

本轮使用的最近 matching 证据位于：

`build/worktrees/main-pick/Post_Impl_Bundles/cpu_1a03a6048ace_chiplab_c398d274812f_perf_100mhz_20260818-060736/`

其 manifest 给出的身份为：

| 身份字段 | 值 |
| --- | --- |
| CPU commit | `1a03a6048ace14f02f5158de589f12d768d45558` |
| CPU source-tree SHA-256 | `9d382ef83d9c10d06962691c405090b09b440e63df6535319a8ec29013d98cb0` |
| Chiplab commit | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| implementation | fully routed，DRC errors/critical warnings 为 0 |
| perf20 | 3,910,163 cycles，16 improve / 4 regress，相对稳定基线几何 speedup 约 2.126% |

这组结果属于历史/邻近版本，不能由当前源码自动继承。所有后续候选都必须保存自己的
源码树 hash、生成 RTL hash、软件 hash、seed、周期、频率、资源、setup/hold WNS/TNS
和 top-path 报告；不能将本表的 `-0.047 ns` 复制给新的 RTL。

## 3. 1st pass 的审计方法

每个模块按同一组问题检查，避免把“代码很多”误认为“时序关键”：

1. 当前寄存器边界在哪里？是否存在跨模块的组合 ready/valid、资格或 wide mux？
2. 该模块在最近 matching top-N 中是否出现，路径是 logic-dominated 还是
   route-dominated？
3. 动态数据是否说明它限制了吞吐，还是只是容量存在？只保留总 cycles、IPC、三类
   阻塞上界和结构占用这类高性价比信息。
4. 若做候选，能否保持同周期握手、顺序、异常和 flush 语义？若不能，新增的一拍
   是否会被频率收益覆盖？
5. 影响分析能否由 `cpu/tests/impact-rules.json` 映射到有限的定向 suite，再用
   perf20、func58 和必要的 Linux 端到端合同收尾？

### 3.1 前端与分支预测

**结构。** `OooFrontend` 是 4-wide fetch、3-wide decode/rename，16 项 instruction
buffer。`BankedFetchPredictor` 是 4 bank 同步 BTB/PHT，默认每 bank 4096 个 2-bit
PHT state、GHR 10 bit、BTB 每 bank 128 项、RAS 8 项。当前已经存在 LUT-tree
equality、translation response bypass/turnover、cache-hit/history turnover、
balanced prediction select 和 speculative instruction-array read 等边界。

**已有证据。** frontend empty 约 `4.94%`，branch mispredict 约 `5.15%`，recovery
约 `0.795%` cycles，平均 resolve-to-recovery 约 `3.445` cycles。B02-F matching
top path 不在 predictor；更早的 baseline 才出现 L1I tag/data enable 和 BTB/ATU
路径。PHT “每 bank 1024 entry 没用上”的说法不成立：初始化后 entry 都可被访问，
真正可测的是 alias、地址 XOR、BRAM mapping 和 route。

**审计判断。** 不扩 fetch/decode width，不继续增加 PHT 细粒度计数器。固定 GHR=10
后可保留 1024/2048/4096 的几何 A/B，但它是 P1 条件候选；不能同时改变 history 和
容量后声称得到了容量收益。RAS 的延后一拍开关曾改变 return 可见时刻，正确性优先级
高于 predictor timing 小收益。

### 3.2 Decode、rename、dispatch window 与资源 ready

**结构。** `DecodeRenameBuffer` 是一个一组 3-wide elastic register；之后是 8 项
`DispatchQueue`、3 项 `DispatchWindow` 和 3-to-4 的 `DispatchRouter`。后端的
`resourcesReady` 同时合并 DispatchQueue、ROB、FreeList、LSQ allocator 的 capacity
资格，再生成 `acceptAll`。

**时序风险。** 这里的风险是多个资源资格汇合后的宽 fanout，以及 router 的 lane/port
选择，不是单个队列深度。已有 registered window 和 prefix count 已经切开了 instruction
buffer 到 rename 的路径。动态 dispatch 输入/接受约 `1.5321/1.1189 uop/cycle`，
接受率约 `73.03%`，但这不能区分哪一个资源拒绝了 younger lane；没有证据支持重写
3x4 maximum matching。

**审计判断。** 先保持 `resourcesReady` 的寄存边界。只有 current top-N 明确落到
rename/dispatch qualification 时，才考虑把 decoder exception/serial control 做成
窄 sidecar；A01、D02 暂不进入 RTL。

### 3.3 IQ、wakeup/select 与执行入口

**结构。** 4 个 8-entry IQ 分别服务 P0 ALU/CSR/serial、P1 ALU/DIV、P2 ALU/branch/MUL、
P3 LSU。每个 resident entry 只保存本端口需要的 payload；5 个 writeback tag 会对
`psrc1/psrc2` 做 wakeup compare，再由 age order 和 ready age 选择 oldest。当前已经
使用 balanced select、tokenized/registered output、ordinary registered wake select
decoupling 和 enqueue credit register。

**证据。** IQ 满率最高约 `1.19%`，operand slot 有效但 execution 未接受的差值仅
约 `0.01324 slot/cycle`，issue 约 `1.0231 uop/cycle`。这不支持增加 IQ 或复制 FU。
但 top-50 若出现跨区 wakeup tag 到 age-order CE，路由会比逻辑更快成为问题。

**审计判断。** 只保留一个条件候选：端口内 wake tag 的物理复制或两级 compare，不能
在 ordinary wakeup 上随意插入一拍。正确性测试必须覆盖 simultaneous wake、同周期 enqueue/
dequeue、flush、年龄顺序和 LSU registered output。

### 3.4 ROB、commit、FreeList 与 PRF

**结构。** ROB 为 32 项，3-wide allocate/commit，5-wide completion；payload 使用 4 个
同步 bank，`candidatePointer` 已注册并预取下一组 commit payload。`systemOperation`、
PC 和 exception-valid 等退休控制字段已有 state-side 优化开关；FreeList 的 retired
physical register batch 也已经寄存，避免 ROB commit 直接驱动远端释放。

**新的路径解释。** B02-F 的最差起点是
`rob/candidatePointer_0_reg[1]_rep__13/C`，终点是
`lsqAllocator/loadOccupancy_reg[4]/D`。中间经过 `stagedPredictorRetireValid`、
commit qualification、候选指针和 LSQ 的 occupancy arithmetic，最终跨越 ROB、
idle/commit 适配和 LSQ allocator 的物理区域。它是“ROB commit qualification 到 LSQ
occupancy”的路径，不能再用“ROB PC 状态化”重复解释；PC/systemOperation state 化
已属于历史工作。

**动态约束。** ROB 平均占用约 `23.24/32`，满周期约 `6.58%`；FreeList capacity
blocked 上界仅 `0.1585%` cycles。扩 ROB/PRF 会扩大多端口状态、比较器和 route，不能
作为频率阶段的默认动作。

**审计判断。** 把 ROB 热点拆成四个可独立审计的 cone：

| cone | 当前用途 | 本轮判断 |
| --- | --- | --- |
| commit-to-LSQ release | load/store commit match、释放 mask、allocator occupancy | **P0，FQ01** |
| commit-to-privileged | serial PC、CSR、TLB、ERTN、exception | 已有窄 state，只有 top-N 重现才做 FQ01-R |
| commit-to-predictor | branch retire、GHR/RAS architectural update | 保持 staged batch，暂不与 LSQ 候选混做 |
| commit-to-FreeList | oldPdst reclaim | 已注册，K02 事件太低，不回接组合路径 |

### 3.5 PRF、RenameMap 与物理寄存器容量

`PhysicalRegisterFile` 为 64 个 physical registers，8 个读地址/数据口和 5 个写回口；
RenameMap 对同组 rename 和 writeback 做旁路。它的逻辑结构明确，但最近 top-N 没有落在
PRF read mux；历史静态代理的 PRF 逻辑级数约 9，明显低于 ROB/LSQ 和缓存路径。

当前 ROB 使用率说明窗口确实有价值，但扩到 128 physical registers 会扩大 tag compare、
读口 mux、FreeList pointer 和布线，不能由“BRAM 还有空闲”推出 Fmax 收益。只有新的
top-N 明确指向 PRF read/bypass，才进入物理局部化候选。

### 3.6 LSQ、Store Data Queue 与内存顺序

**结构。** 活动配置为 LDQ/STQ/SDQ `16/8/8`，单 LSU，LSQ 需要同时维护 load/store
年龄、translation、forwarding、requestSent、completion、commit 和 recovery epoch。
`LoadStoreQueueAllocator` 单独维护 `loadOccupancy/storeOccupancy`，通过 `CountOne`
统计 commit release mask，并在同一寄存器更新中完成 allocate 与 release 的加减。

**关键语义。** load release 来自 `commitValid && isLoad && loadEntry.valid &&
robPointer match`；cached store 可提前释放，uncached store 必须等 request sent、
completed、committed，flush 时还要保留已提交 store 的 drain 语义。因此不能简单把
commit valid 延后一拍，也不能让 allocator 仅凭 ROB 的 `isLoad` 位释放 entry。

源码中的组合方向是：ROB commit record 进入 `LoadStoreQueue`，LSQ 对 LQ entry 做
valid/pointer identity 检查并产生 3-bit `releaseLoadValid`，该 mask 再经
`OooBackendWithExecution` 返回 `OooBackend`，最后进入 allocator 的 `CountOne` 和
occupancy D。这个跨 sibling module 的往返链解释了为什么 endpoint 在 allocator，
startpoint 却仍是 ROB candidate pointer，也解释了为什么单改 allocator 加减法未必有效。

**证据。** B02-F top path 直接落到 allocator occupancy；top-50 中还出现一组
`loads_7_requestSent_reg` 到 `scheduledLoad_*` 的近临界 CE/D 路径。动态上 head
incomplete 的 Load/Store 分类约占总周期 `16.49%/14.84%`，但 L01 多 Store forwarding
阻塞只有 `0.1291%` 上界，说明扩大 forwarding CAM 不是频率优先方向。

**审计判断。** LSQ 是本轮最值得深入的模块。候选要先切开“释放资格生成”和“allocator
占用更新”这两个边界，再考虑 pending-load oldest 选择；任何改变 release 时刻的方案
都必须把精确异常、flush、uncached store、SC 失败和 wraparound 作为高优先级正确性
合同。

### 3.7 ALU、MUL、DIV 与四个执行端口

端口配置是 P0 `ALU/CSR/Serial`、P1 `ALU/DIV`、P2 `ALU/Branch/MUL`、P3 `LSU`，普通
ALU 的逻辑规模很小。`MultiplyPipeline` 使用一个 32x32 signed/unsigned product，
结果寄存一拍；SoC 近似实现中只有 8 个 DSP48E1。独立 `RegisteredMultiplier` 也仍是
signed/unsigned product 后选择，没有手工拆分的 DSP 级结构。DIV 是迭代式 32-cycle
单元，`enableDivideFastPath=false`，E03 DIV reservation blocked 上界只有 `0.1275%`
cycles。

**审计判断。** DIV fast path、第二个 divider、复制 FU 都不进入频率首批。MUL 进入
`FQ04`，但先做 standalone mapping：比较 Vivado DSP inference、输入寄存、输出寄存、
signed correction 和 latency/throughput；没有 standalone 路径或资源证据就不改全核
latency。

### 3.8 L1I、L1D、L2、MSHR 与 CacheArray

L1I/L1D 各为 2-way x 128 set x 64 B（16 KiB），L2 为 2-way x 512 set x 64 B（64 KiB），
MSHR 为 4。full-line 数据为 512 bit，最近 matching implementation 使用 56 RAMB36、
21 RAMB18；BRAM 总 tile 使用率约 18.22%，DSP 约 1.08%，所以“器件有空闲”是事实，
但空闲资源只有在改变物理局部性或 RAM 映射时才可能转成 Fmax。

旧 baseline 的 L1I tag-read 到 data-read-enable 路径为 `9.425 ns`，逻辑 `3.490 ns`、
route `5.935 ns`；B02-F 中该路径不再是最差路径。当前已有 instruction/data array
read-enable decoupling、turnover、LUT-tree equality 和 refill/install 互斥状态，不能
仅凭旧报告重新实现相同方向。

**审计判断。** `FQ03` 只在 current top-N 再出现 cache data/read-enable，且 RAM hierarchy
报告同时显示宽 line route 问题时进入。窄 bank + line buffer 必须同时证明 hit II、miss
refill、victim gather、CACOP、L2 write-back 和 BRAM/LUT/route，不能只看 BRAM 数量。

### 3.9 TLB、地址翻译、CSR 与异常恢复

I/D micro-TLB 各 4 项，Main TLB 32 项，共享 walker；direct DMW/pretranslation 默认开启，
perf20 ideal memory 大多绕过主 TLB。`AddressTranslationUnit`、`HierarchicalTlb`、
CSR/ERTN/TLB mutation 属于 Linux 正确性关键路径，但当前 benchmark 不能给出有代表性的
容量收益。

**审计判断。** 不扩 TLB、不做双 walker、不把 TLB compare 拉回 allocator 或 IQ。若 Linux
工作集或 current top-N 直接指向翻译路径，先做 key/result 的窄寄存和物理局部化；任何
候选的正确性优先级高于一般 IPC 候选。

### 3.10 AXI bridge、uncached path 与平台 IP 边界

`AxiLineBridge` 负责 cached read、line write、uncached ordering、barrier 和 response
backpressure。CPU-local bridge 可以做 skid/credit 局部化，但平台 PLL、DDR、AXI、JTAG
和 Chiplab 板卡 IP 不在本项目可替换范围内。CI 中 `sys_clk` 的 DDR/JTAG CDC 负 WNS
不能归因于 CPU Fmax。

perf20 的 ideal memory 只适合 CPU 内部控制和周期回归；func58 的 random AXI 只证明协议
合同，不代表 DDR 吞吐。`U01/U02` uncached 快路、DMA 或 store drain 只有在 Linux/平台
语义固定后才有资格进入候选。

### 3.11 Observer 与容量审计

Observer 当前保留的总量足够做本轮取舍：总 cycles/IPC、ROB/LDQ/STQ/IQ 占用、dispatch/
issue、ROB head incomplete、branch recovery，以及 DIV/FreeList/L01/L02 的有限上界。
它不能回答 WNS、route 拥塞、BRAM 深度利用率或 Vivado primitive 的具体摆放；因此本轮
不继续增加 counter。容量问题使用生成 RTL、Vivado utilization 和 timing top-N 回答。

## 4. 1st pass 结论矩阵

| 模块/路径族 | 最近物理证据 | 动态约束 | 1st pass 结论 |
| --- | --- | --- | --- |
| ROB candidate -> LSQ occupancy | B02-F 最差，9.858 ns，route 79.9% | ROB 在用，LSQ 释放语义复杂 | **P0，先做 FQ01** |
| LSQ requestSent -> scheduledLoad CE | 多条 9.64--9.71 ns，route 约 75% | Load/Store head incomplete 占比较高 | **P0，和 FQ02 同一审计批次** |
| LSQ -> ROB completion bypass | 9.846 ns，route 72% | issue/exec 不是全局饱和 | P1，先观察与 FQ01 的物理耦合 |
| L1I tag -> data enable | 旧 baseline 9.425 ns | B02-F 已迁移 | P1 条件，不能重复旧优化 |
| IQ wakeup/select | 历史出现过，当前 top-N 未确认 | IQ 满率最高 1.19% | P1 条件，保持 cycle-neutral |
| predictor/PHT/RAS | 当前 top-N 未确认 | frontend empty 4.94%，recovery 0.795% | P2，固定 GHR 的几何 sweep |
| multiplier DSP mapping | SoC 仅 8 DSP，独立乘法器现有 `$mul` | MUL 依赖链可能敏感 | P2，先 standalone |
| TLB/CSR/AXI | 当前 benchmark 不代表 | Linux/平台合同关键 | P2/P3，功能优先 |
| ROB/PRF/IQ/port 扩容 | 无物理支持，增加宽度和 route | IPC 上界缺乏证据 | 暂停 |

## 5. 2nd pass 候选准入卡

候选卡描述的是“可以引入的最小实验”，不是要求立即照抄的 RTL 方案。每张卡都要
先完成 baseline matching，再允许一次只改变一个结构变量的 A/B。

### FQ01：ROB-to-LSQ release/occupancy 局部化（P0）

**目标路径。** `candidatePointer`/commit qualification -> `releaseLoadValid` ->
`CountOne`/occupancy arithmetic -> `lsqAllocator.loadOccupancy`。

**候选形状。** 优先比较以下两种不改变架构语义的实现：

- `FQ01-A`：保持 release mask 的周期和含义不变，在 LSQ/allocator 边界复制窄的
  release token、CountOne 和 occupancy update，避免宽 commit qualification 穿越多个
  层次；
- `FQ01-B`：由 ROB state 产生带 `robPointer + LQ/SQ index + isLoad/isStore` 的窄退休
  memory token，LSQ 在本地做 entry identity/epoch 检查，allocator 只接受经过本地合同
  认证的 release count。允许一拍寄存的 token 只有在 lookahead credit 证明不会制造
  capacity bubble 时才可尝试。

**禁止事项。** 不能只把 `commitValid` 延后一拍；不能根据 ROB `isLoad` 直接释放；不能
把 uncached store 的 drain、SC 失败或 flush 中已提交 store 清除掉。

**最高优先级正确性。** allocator occupancy 不得 underflow/overflow；同拍 allocate+
release 的净值必须一致；ROB/LSQ pointer wrap、flush、精确异常、uncached store、SC 失败、
barrier 和 commit observation 必须逐周期一致。任一失败先标为 correctness blocker，
优先级高于所有 IPC 候选。

**影响测试。** `LoadStoreQueueSpec`、`ReorderBufferSpec`、`OooBackendDispatchSpec`、
`OooBackendWithDataCacheSpec`、`OooCoreIntegrationSpec`；Python cache/core/generation
contracts；perf20 20/20、func58 三 seed；涉及异常/翻译时增加 Linux smoke。

**物理准入。** 只接受 `cpu_clk` top-N 中 release/occupancy 路径族退出或明显缩短、
setup/hold 不出现新失败、DRC/fully-routed 通过，且 cycles 不退化的版本。

### FQ02：LSQ pending-load 与 requestSent CE 局部化（P0）

**目标路径。** 16 项 load 的 oldest-ready/translation/requestSent 状态选择，以及
`requestSent` 同时驱动多个 `scheduledLoad_*` 字段写使能的宽 fanout。

**候选形状。** 先做两级 oldest 归约或 2x8 bank 的局部选择，保留原有 `loadHead`、
`scheduledLoad` 和 retry-token 寄存边界；随后再比较把同一 `requestSent` 资格拆成 bank-local
CE。不得一开始改变 pending load 的可见周期或 forwarding priority。

**正确性重点。** load 年龄、C04 alias、L02 retry-token、C06 store-order/SUC、translation
backpressure、response owner、multiple forwarding store、flush/epoch 和 load wakeup
都必须保持。该卡和 FQ01 共享 LSQ 合同，但每次 A/B 只能改变一个变量。

**影响测试。** `LoadStoreQueueSpec`、`OooBackendWithDataCacheSpec`、`OooCoreIntegrationSpec`、
`L1DataCacheSpec`/`DataCacheHierarchySpec`，再跑 perf20/func58；若改变内存 latency，增加
Linux memory smoke。

**物理准入。** 目标 path family 的 worst/median top-N 同时改善，且不能把延迟转移为新的
LSQ-to-ROB 或 cache path。只减少 LUT 不算通过。

### FQ03：CacheArray/RAM data path（P1 条件）

只在 current matching top-N 再出现 tag-to-data enable 或 full-line data route 时开放。
候选顺序是 data-read enable 局部化、窄 bank + line buffer，最后才考虑几何变化。固定
hit II 与 miss refill 时序，检查 BRAM mapping、victim gather、CACOP、L2 write-back、
L1I/L1D/L2 全部合同。影响 suite 为 cache hierarchy 全套和 backend integration，不能
仅跑 perf20。

### FQ04：Multiplier standalone DSP mapping（P1）

先使用 `RegisteredMultiplierSpec` 和 `GenerateRegisteredMultiplier` 生成独立 netlist，
对比当前 `$mul`、Vivado DSP inference、输入/输出寄存和可能的 signed correction。记录
DSP/LUT/FF、standalone WNS、latency/throughput，并用 `OooExecutionClusterSpec` 验证 MUL、
MULH、MULHU 的依赖链和 flush。只有独立证据确认第二路径墙或资源异常，才做 matching SoC；
不能为了一个可能的 DSP 数下降而增加全核依赖一拍。

### FQ05：端口内 wakeup/select 物理复制（P2 条件）

只在 current top-N 出现 IQ wakeup tag、age order 或 ready CE 时开放。候选限于端口内
复制/分层 compare，保持同周期 direct wake 语义；不增加 IQ 深度、不增加 FU 数、不把
全局 flush/FreeList/commit credit 接回 select。测试 `IssueQueueSpec`、dispatch、execution
和 integration；任何同周期 wake/flush/年龄错误立即否决。

### FQ06：PHT geometry 与前端控制（P2 条件）

固定 GHR=10，只比较 PHT 1024/2048/4096 entry geometry 或物理 RAM mapping。记录 predictor
定向命中行为、perf20 cycles、frontend timing 和资源；禁止以同时改变 history width 的
结果作为容量结论。RAS 注册更新在逐周期 trace 合同通过前不开放。

### FQ07：decode/serial sidecar（P3 条件）

只有 current top-N 回到 decoder exception/serial control 时，才复制少量窄资格字段，
不扩 wide `DecodedMicroOp`、不改变异常/CSR/ERTN 的 commit 时刻。跑 frontend、ROB、commit
adapter、CSR、idle、TLB 相关 suite 和 Linux smoke。

### FQ08：CPU-local AXI skid/credit（P3 条件）

只有 CPU `cpu_clk` top-N 落在 bridge ready/valid 链时开放。平台 AXI/DDR/JTAG IP 不变，
只允许 CPU-local 的 skid/credit 边界。必须跑 AxiLineBridge、compat、random AXI func58
和 Linux uncached/ordering smoke；不能用 ideal-memory perf20 代替。

## 6. 统一实验合同

为了控制时间成本，所有候选共用下面的粗粒度指标，不为每个 entry 或 opcode 增加新
计数器。

### 6.1 固定顺序

1. 归档当前源码、生成 RTL、Chiplab、Vivado 工具和 seed；完成当前源码 100 MHz direct
   full baseline。
2. 用 `cpu_clk` top-N、clock summary 和 utilization 确定 path family；如果路径迁移，
   取消原候选，不为“已经不在 top-N 的路径”做优化。
3. 只选择一张候选卡，按 impact manifest 跑受影响 Scala/合同测试、RTL generation、
   静态门禁，再跑 perf20 20/20 和 func58 三 seed。
4. 通过功能门禁后，使用完全匹配的 RTL 做 100 MHz implementation；若闭合，再以 5 MHz
   粗粒度提高目标频率，直到出现 setup/hold 或 DRC 门禁失败，记录最后一个合法频点。
5. 只有 finalist 才做第二个 P&R seed，评估 route 方差；不跨 RTL、跨 seed 借用 slack。

### 6.2 最小指标集

| 类别 | 保留指标 | 作用 |
| --- | --- | --- |
| 功能 | Scala/合同 pass、perf20 全通过、func58 三 seed、必要 Linux smoke | 证明语义 |
| 性能 | 总 cycles、IPC、perf20 几何平均、最多三类阻塞上界 | 判断 IPC 是否值得 |
| 时序 | `cpu_clk` setup/hold WNS/TNS、failing endpoints、top-10 path family | 判断 Fmax |
| 资源 | LUT/FF、RAMB36/18、DSP、层次资源和拥塞摘要 | 判断资源换频率是否划算 |
| 交付 | fully routed、DRC、matching RTL/source/software hash | 保证证据身份 |

### 6.3 通过与否

- 所有功能和合同测试必须通过；正确性失败的候选直接停止，不用 IPC 或 WNS 抵扣。
- cycle-neutral 候选要求 perf20 总 cycles 不退化；若增加了流水延迟，必须用
  `cycles / actual_cpu_frequency` 证明至少有清晰的总体收益，不能只看 WNS。
- 100 MHz 结果只有在 setup/hold WNS、TNS、DRC 和 fully-routed 全通过时才算闭合；
  `-0.047 ns` 只能算“接近闭合”，不能称为 100 MHz 已实现。
- 主频提升必须在更短 period 的 matching implementation 中重新满足所有门禁；不能由
  `1/(period-WNS)` 对旧结果外推极限频率。
- 物理候选若只改善一个 path，却使新的 worst path 更差或 TNS 大幅增加，判为路径转移
  而不是通过。

## 7. 开发顺序与优先级

### 正确性优先级

1. FQ01 LSQ release/occupancy：精确异常、uncached store、SC、flush、wraparound。
2. FQ02 pending-load/requestSent：年龄、forwarding、translation owner、retry、wakeup。
3. FQ03 cache：refill、victim、CACOP、write-back 和 hit II。
4. FQ04 multiplier：signedness、MULH/MULHU、依赖和 flush。
5. FQ05--FQ08：按各自合同执行，不能以 aggregate Observer 代替逐周期行为。

### 频率优先级

1. 先取 current matching top-N；在此之前不扩 ROB/PRF/IQ/FU。
2. 先做 FQ01，再做 FQ02；两者都属于 route-dominated 的 LSQ/commit 物理区域。
3. cache、IQ、predictor 和 multiplier 只在路径证据触发后进入对应实验。
4. 所谓“唾手可得的 IPC”目前只有现有 L02 retry-token 的开关回归和 B02-F predictor
   baseline 可确认；没有新的低风险 IPC 候选值得牺牲时序边界。

本轮 1st pass 到此完成；下一轮的 2nd pass 不是同时改八个模块，而是用当前实现的
top-N 选择一张候选卡，完成一条 matching、可复现、可回退的证据链。
