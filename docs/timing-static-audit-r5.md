# R5 全局时序静态审计

最后审计：2026-08-15。

本文记录一次不以当前 direct-full top-50 为边界的静态审计。目标是从源码、生成 RTL
结构和既有实现证据中寻找可能具有较高性价比的时序优化。本文不把静态观察直接当作
Vivado 结果；候选必须经过向前兼容的定向测试、完整 `cpu-check`、perf20/func58 和
matching direct implementation 才能改变默认组合。

## 审计身份与边界

- 上一个已提交的 R5 源码身份：`bb4fe5d`。
- 参照生成 RTL：R5 组合生成物，发布哈希见
  `build/sim/runs/cpu_8cc74e1d4e58_chiplab_c398d274812f/clean-perf20_model_408602150177_software_f6e7c20f71a4/ideal/matrix_52d9676ce812_perf20.csv`
  与 R5 experiment manifest。
- 参照实现：
  `Post_Impl_Bundles/cpu_bb4fe5d9b4c3_chiplab_c398d274812f_perf_100mhz_20260815-081448/`。
- R5 direct full 的正式结果是 setup WNS `-0.187 ns`、hold WNS `+0.050 ns`；该次
  route 已完成、DRC 为 0、bitstream 成功，但不是时序闭合里程碑。
- 当前开发分支包含 RT02、MT08 和暂停中的 PT02 源码实验；本审计将它们与 R5
  matching implementation 分开记录，不把尚未重新实现的 RTL 或实现结果归给这些候选。

## 方法

审计同时看三类证据：

1. SpinalHDL 源码中的寄存器边界、状态机互斥条件、宽字段复制和跨模块控制。
2. R5 生成 RTL 中的信号扇出近似和 RAM/寄存器连接方式。这里的出现次数只用于
   定位结构，不等同于 Vivado `report_high_fanout_nets`。
3. 已有 Vivado full-route 报告中与候选结构相邻的路径，作为风险交叉验证，而不是
   把当前 top-50 当作候选准入条件。

## 优先候选

| 编号 | 方向 | 静态观察 | 预期收益 | 主要风险 | 决策 |
| --- | --- | --- | --- | --- | --- |
| MT08 | 数据侧 TLB probe key 预载 | `HierarchicalTlb` 的 instruction 端在 `ready` 时预载 `VPPN/odd/ASID`，`fire` 只置 `instructionProbePending`；data 端仍在 `io.dataRequest.fire` 中同时写入 `dataVppn/dataOddPage/dataAsid`。这会让 LSQ 的 `valid` 经 `translationRequest.valid` 直接进入 19+1 位 TLB key 寄存器 CE。 | 很可能不增加周期：接受边沿前 key 已由 idle/ready 预载，fire 后只改变 pending。可移除 LSQ valid 到 TLB key CE 的宽控制锥，并改善 route-heavy 的数据翻译路径。 | 必须证明 ready 期间输入变化不会被当作请求；只有 `dataRequest.fire` 才能置 pending。mutation、data walk、negative hit、cancel 和 backpressure 不能被预载逻辑污染。 | **P1，优先做定向 A/B**。这是本次审计最强的新候选。 |
| CT03 | L1D/L2 data RAM read-enable 解耦 | `CacheArray` 已支持 `decoupleDataReadEnable`，但只有 L1I 使用；L1D 的 lookup response/store hit、refill install 与新 lookup 由状态机互斥，L2 也用 `!lookupResponse` 互斥 install/write。静态上可以像 CT02 一样让 data RAM 读使能只由 lookup/maintenance fire 决定。 | 去掉每个 way 的 `externalWrite` 资格进入 data BRAM EN 的路径和扇出；不改变 tag 读、hit 判断或响应周期。对数据缓存和 L2 的时序/布线有潜在全局收益。 | 互斥必须由源码合同和定向测试证明，尤其是 store hit、L2 write hit、refill install、maintenance hit/invalidate 的边界；不能把“通常不同时发生”当成证明。 | **P1，先补互斥合同，再做单候选软件/RTL A/B**。 |
| RT03 | ROB commit PC 状态化 | 当前 ROB 的 `pc` 仍只在 169-bit payload bank 中；`OooCommitAdapter` 的 serial/exception/idle 路径都从 `io.commit(lane).pc` 取得它。R5 报告中 payload bank 输出到 `privilegedRedirectTarget` 的多条路径属于该宽 payload 读取锥。 | 在 ROB state 中复制 32-bit PC，可让 serial redirect、异常 PC 和 idle enter PC 读取窄状态寄存器，切断一组 payload BRAM 输出到系统控制的宽路径，且不新增流水拍。 | 增加约 `32 x 32` 个状态位；必须保持 payload/state 同一 entry 的 wrap、flush、payloadReady 和同步读对齐。第一阶段先复制，不立即删除 payload 字段。 | **P1/P2，适合与 RT02 同轮验证**。 |
| RT04 | ROB serial CSR 热字段状态化 | `csrAddress/csrMask/csrWrite` 只在 serializing/system 操作提交时使用，却仍随 payload bank 每拍读出。可与 `systemOperation`/PC 形成窄 serial token。 | 进一步减少 payload bank 到 CSR/redirect 的冷路径和跨模块宽 mux。 | 需要保持 CSR 写数据、异常和 ERTN 的同拍优先级；增加状态位但收益只出现在低频 serial 路径。 | **P2，先做结构复用分析，不立即实现**。 |

## 已有工作区候选的审计结论

### RT02：systemOperation state 化

当前未提交修改把 `systemOperation` 放入 `ReorderBufferState`，commit、head bypass 和
observer 通过配置选择 state 或 legacy payload。该字段在 payload packing 中紧邻 bit 82；
R5 原始路径明确显示：

```text
payloadBanks_0_reg_1/DOADO[10]
 -> committedErtnValid / candidatePointer / commitPointer
 -> payloadBanks_0_reg_0/ADDRARDADDR[8]
```

因此 RT02 不是抽象的“减少一个字段读取”，而是正对一条 payload RAM 输出到下一次
payload RAM 地址的结构回路，也解释了为什么它值得先于一般 ROB 扩容尝试。它仍需
完整 ROB wrap、serial/ERTN、异常和 perf20 验证。

### PT02：RAS 更新 token 化的时序语义风险

当前未提交修改在 `BankedFetchPredictor` 中把 `speculativeRasPush/pop/returnAddress`
先寄存，再驱动 RAS 数组更新。该结构可能切断 BTB/PHT 到 RAS CE 的组合锥，但它也会
把 speculative RAS 状态更新推迟一个时钟边沿。`enableFrontendHistoryTurnover` 路径中，
更新 token 来自 translation response acceptance；必须先证明“当前接受的 call/return
对下一次 lookup 的可见时刻”与原实现完全一致。BranchPredictor 单测通过不足以证明
连续 call/return、turnover、redirect、RAS flush 的周期语义。

结论：PT02 暂不作为周期透明候选，先补一个逐周期 RAS trace 合同；若无法保持可见
时刻，应改成带 pending top/return bypass 的设计，而不是简单地把整个更新再延后一拍。

本轮 `make cpu-test CPU_TEST=miku.frontend.OooFrontendSpec` 已给出直接反证：25 项中
23 项通过，`trained call and return turn over the speculative RAS` 与
`the delayed speculative RAS preserves a lane-three return address` 两项失败，观测到的
return target 为 `0` 而不是期望的 `0x1c000000`/`0x1c000010`。这不是 baseline DUT
正确性回归，而是 PT02 工作区实现改变了 RAS 可见时刻。该开关已恢复为默认关闭，代码
仅保留给后续 A/B；因此 PT02 在修复前不得进入
任何组合验证或综合批次。

## 全局结构审计

### Wakeup/select 与全局 flush

生成 RTL 的结构计数显示 `io_wakeupValid`、`io_selectWakeupValid` 和 `io_flush` 都被
大量模块引用；这说明全局广播仍是潜在布线压力来源。但仅凭文本出现次数不能证明
它们是当前最差路径：

- wakeup tag 必须广播到多个 IQ，盲目复制比较器会增加 LUT，不能只看 fanout 降低。
- flush 具有恢复优先级，寄存它通常会改变取消边界；应优先做局部资格化或物理复制，
  不能统一插入一级寄存器。
- WT05 已移除 ordinary registered wake 的同拍 select 参与；后续应先读取新 matching
  route，再决定是否做 WT06 端口局部 wake mask。

结论：保留为 **P2 观察项**，在没有高扇出 net 报告和局部路径证据前不进入下一次
实现批次。

### PRF/RenameMap

PRF 每个读口都对 5 个写回口做同拍 tag compare 和数据 bypass，RenameMap 也对每个
rename lane 比较全部写回 tag。它们是宽度固定、结构清楚的网络，但当前没有证据表明
多级 bypass 是 100 MHz 的主导路径。将写回寄存或拆分 bank 会增加至少一拍或改变
write-through 语义，当前性价比低于 MT08/CT03/RT03。

### 派发与 MSHR 优先选择

DispatchRouter、L1D/L2 MSHR 和 StoreDataQueue 仍使用小规模 `selectLowest`。这些树的
规模分别是 4 路、最多 4 个 MSHR 或 8 个队列项，静态深度有限；在没有路径报告证明
其成为全局瓶颈前，平衡树的收益不足以优先于数据 TLB 和 RAM enable 解耦。

### AXI/平台边界

AXI bridge 的宽数据寄存器和 ready/valid mux 可能造成平台路径压力，但 R5 top-50 中
没有平台类别路径，且平台接口属于稳定边界。当前不建议为 AXI 端口增加 speculative
寄存或重排；只在后续实现报告出现平台路径时再单独审计。

## Matching 实现复核

RT02、MT08、CT03、RT03 的组合 direct full 已完成：setup/hold WNS 为
`+0.028/+0.047 ns`、setup TNS 为 0，fully routed、DRC 0 error/critical warning、
bitstream 成功。相对本审计参照的 R5 full route，setup WNS 改善 `0.215 ns`，代价为
3,011 LUT 和 1,318 FF，BRAM/DSP 不变。该变化只能作为四项组合结果。

新的 top-50 为 predictor 3、IQ 12、ROB/CSR 14、cache/L2 17、frontend 4，LSQ/platform
为 0。旧 payload-bank 地址回路、RAS 写使能墙和数据侧 TLB/LSQ 负路径均未再出现；新的
最差路径是 BTB prediction 到 instruction TLB request，slack `+0.028 ns`。这说明静态审计
找到了有用方向，也说明当前正裕量很小，下一轮 IPC 修改仍需把时序影响纳入设计本身。

CT03 的正确性前提仍由 L1D/L2 controller 的 `!lookupResponse` 安装互斥和现有
store-hit/refill/maintenance 定向 suite 保护；后续若引入并行 install 或 hit-under-miss，
必须重新建立显式冲突合同。PT02 继续默认关闭，逐周期 RAS trace 合同通过前不得进入组合。

本审计没有把表面布尔改写或综合器提示当成成功证据。下一轮先读取版本化
`PerfObservationV1` 的当前周期权重，再选择 IPC 候选；新的 route 只作为候选实现时的
时序边界，不单独驱动一轮无性能目标的微调。
