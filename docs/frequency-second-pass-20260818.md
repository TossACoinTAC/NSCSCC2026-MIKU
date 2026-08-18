# 频率优先第二轮审计：BranchTrace v2 与 LSQ 结构复核

本文记录 2026-08-18 在 `dev/freq` 上完成的第二轮观察。目标是从全核源码和
20 个 `perf20` workload 的同一 ROI 中筛选“可能同时改善 WNS 与 IPC”的候选，
其次筛选几乎不增加时序压力的 IPC 候选。本轮只修改观察和文档，不引入性能 RTL。
trace 与 `2b71eeb` 的 observer baseline 身份匹配；随后 R22 的 RAS 时序候选改变了
source/RTL identity，因此下面结果用于候选排序，不能直接晋级为 R22 当前 RTL 的性能证据。

## 1. 证据身份与方法边界

本轮 branch observer 实现已在提交 `2b71eeb` 中固定；候选文档暂不在本 session
提交。仿真使用同一个 instrumented model、ideal memory、`SIM_LANES=2` 和 20 个
workload，每个 workload 的 ROI 由两个 `rdtimel.w` marker 的严格内部区间定义。

| 字段 | 值 |
| --- | --- |
| observer commit | `2b71eeb199b97327f3ec0665489169a3dbd0f67b` |
| CPU source-tree SHA-256 | `17665d58bd15b2c348f4c5ddbecabfe8a56848f71e27f5d85eeeb801a351eea9` |
| Chiplab commit | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| cached instrumented RTL SHA-256 | `af6805daf34d2f88804e53a9b97afda8a5c8f07c0737feb88e5b725709498ec8` |
| branch trace source SHA-256 | `fee8bf8ed8b34f2d8f77ad435af30a3c31e5670a2dd3f553fa97462c36164d41` |
| ROI matrix | [`build/reports/observations/R22-branch-trace-v2.json`](../build/reports/observations/R22-branch-trace-v2.json) |

该 run 的 20/20 workload 通过 trace/`m01-counters.json` 合同。ROI 内 branch event
数等于 `m01.branch.retired`，marker 之间的 cycle 数等于 `m01.cycles`；这证明 observer
和 ROI 解析器的合同成立。run 的 source-tree、instrumented RTL、model 和 trace hash
均属于 `2b71eeb` baseline；R22 随后的 `b074e7d..38c4a07` 不继承其 clean RTL 或 WNS，
但该 trace 仍是可追溯的 baseline 动态画像。

R22 初次合入 BPT03 时在
`cpu/src/main/scala/miku/predict/BankedFetchPredictor.scala:159-176` 复现了
`architecturalRasCountOneHot` 组合环；`38c4a07` 已用局部 `pushAccepted`/
`popAccepted` 值修复，并通过 predictor、L1I、集成 suite 及第二次完整 `cpu-check`。
因此该问题已从 current blocker 改为关闭的 correctness 记录。

Branch trace 没有携带 fetch 时的完整 BTB target/type hit 结果，也没有把
`dynamicPredictionHit` 直接送到退休端。因此用退休的 PHT metadata 重放得到的是
方向预测的估计或上界，不能写成当前硬件的精确 MPKI。任何分支候选都必须在后续 A/B
中同时验证 fetch prediction、target、redirect 和 recovery。

## 2. 当前动态画像

20 个缓存 ROI 合计（provisional）：

| 指标 | 数值 | 解释 |
| --- | ---: | --- |
| cycles | `3,910,143` | 每个 workload 的 ROI 周期之和 |
| retired instructions | `3,608,034` | 约 `0.923` IPC，属于 instrumented ROI |
| branch retired / resolved | `810,807 / 861,258` | resolved 可能包含未在 ROI 内退休的更老事件 |
| branch mispredicted | `44,316` | `m01` 的真实 recovery 归因 |
| recovery cycles | `31,077` | 约 `0.795%` ROI cycles |
| branch head completion opportunity | `141,053` | 已存在 staged head-bypass 机会的上界 |
| branch head mispredict opportunity | `13,450` | 其中需要精确恢复语义的子集上界 |
| dispatch valid / fire | `5,990,854 / 4,375,130` | 接受率约 `73.03%`，但没有 opcode/port 拒绝分类 |
| LDQ/STQ full cycles | `160,458 / 158,663` | 约 `4.10% / 4.06%` ROI cycles |
| DIV operand blocked | `4,984` | 约 `0.127%`，不支持复制 divider |
| FreeList capacity blocked | `6,197` | 约 `0.158%`，不支持默认扩 ROB/PRF |

LSQ observer 的高性价比事件如下。它们是周期上界，不等同于每个周期都能通过某个
局部改动消除：

| 事件 | 次数 | 当前判断 |
| --- | ---: | --- |
| `olderStorePending` | `1,802,758` | LSQ 年龄/顺序判断长期活跃，值得继续看路径局部化 |
| `cacheLoadCandidate` | `910,327` | 有真实的普通 cache-load 请求资格 |
| `alternatePendingLoadAddressReady` | `1,815,247` | 存在年轻 pending load 地址已就绪 |
| `blockedWithAlternate` | `425,678` | 最老 load 被阻塞但有替代项，支持 L02/FQ02 机制 |
| `oldestOrderBlockedAlternate` | `172,878` | 程序顺序域是重要子集 |
| `oldestLocalAliasBlockedAlternate` | `101,272` | 物理 alias/forwarding 检查是重要子集 |
| `multipleForwardingStoresBlock` | `5,047` | 多 Store forwarding 不是首要容量方向 |
| `unknownOlderStore` | `23,080` | 未知地址/翻译阻塞相对较少，但 correctness 风险高 |
| `olderUncachedStore` | `110` | 当前 ideal-memory perf20 不能支持复杂 SUC 快路 |

源码侧的结构与历史 Yosys LTP 结果相互印证：`LoadStoreQueue` 的最长静态锥从
`stores_7_robPointer` 经过 `unknownOlderStore -> olderUncachedStore -> loadOrderClear
-> cacheLoadCandidate -> requestCapture`，最终进入 pending-load 状态寄存器。该
报告属于旧生成 RTL 的结构定位，不能当作当前 Vivado slack；它只说明 FQ02/MT09
应优先等待 fresh matching top-N，而不是重新优化已经迁移掉的路径。

## 3. 分支观察结果

ROI 内共有 `552,382` 个 conditional valid branch，其中 `46,485` 个的 PHT state
为弱状态 `01/10`，占 `8.414%`。弱状态按 state 分布为：`01` 约 `27,950`，`10`
约 `18,535`。弱事件的方向重放结果为：

| 方案 | weak events 正确数 | 估计准确率 | 含义 |
| --- | ---: | ---: | --- |
| 现有 PHT state 方向 | `29,949 / 46,485` | `64.427%` | metadata-based inferred result |
| static BTFNT | `25,575 / 46,485` | `55.018%` | 仅作为已有 fallback 的对照 |
| 256-entry PC-local 2-bit replay | `34,616 / 46,485` | `74.467%` | 选择性 alternate 的 replay 上界 |
| 4096-entry PC-local 2-bit replay | `34,865 / 46,485` | `75.003%` | 更大表的 alias 上界，非硬件结果 |

256-entry replay 比 inferred PHT 多 `4,667` 个 weak-event 正确方向，4096-entry 多
`4,916` 个。256 项已有 `1,813` 个不同 weak PC，4096 项的 PHT index 实际观察到
`3,943/4,096` 个，说明“每 bank 4096 项大量没用上”没有得到支持；当前不建议
因为容量空闲而缩小或扩大 PHT。

一个更保守的无存储规则也值得记录：在弱 state `01` 且条件分支为回跳时，BTFNT
重放在 `4,548` 个事件中比 PHT 多 `548` 个正确方向。它只改动现有 direction mux，
不需要第二张表，但这仍是上界，因为 trace 没有记录 `dynamicPredictionHit` 的完整
BTB target/type 条件。

## 4. 新候选

### BP01：弱 PHT 状态的选择性 PC-local alternate

**机制。** 保留现有四 bank BTB/PHT 和 GHR；只有 conditional、PHT 有效且 state 为
`01/10` 时，查询一个小型 PC-indexed 2-bit local side table，选择 PHT 与 local
方向中较可信的一方。更新只发生在已退休的 conditional branch，flush 不修改 local
状态。第一版应比较 64/128/256 项，不把 4096 项直接带进前端。

**为什么值得观察。** 在缓存 RTL 的 provisional trace 中，弱状态占条件分支 `8.414%`，256-entry replay 从 inferred
PHT 的 `64.427%` 提高到 `74.467%`，且收益集中在 coremark、minmax_sequence、
select_sort、quick_sort 等 workload。它可能降低错误路径工作、ROB recovery 和
commit head 停顿，属于有明确 IPC 机制的候选。

**WNS 判断。** 这不是当前的 B02-F 级双赢结论。若 side table 与同步 PHT 同拍读取，
会增加前端 RAM 端口、direction select 和 metadata 反馈压力；只有把它放在现有
PHT response 的寄存边界之后，或证明使用 LUTRAM/BRAM 的局部实现不进入 current
top-N，才可能成为低时序压力候选。不能为了提高 replay accuracy 把 PHT lookup
再提前一拍。

**准入门槛。** 先做 exact fetch-side prediction observer 或用 RTL A/B 取得真实
`dynamicPredictionHit`、taken/target、mispredict 和 recovery；再比较 64/128/256
项。要求 perf20 几何平均改善、无单项显著回退，且 predictor/ATU top-N 的 setup/hold
不恶化。功能测试需覆盖 speculative update、flush、BTB miss、PHT invalidate、
恢复后 local state 和 table alias。

### BP02：弱不取回跳的 BTFNT 选择性覆盖

**机制。** 当 PHT 有效、state 为 `01`，且当前 conditional branch 的静态 BTFNT
为 taken（回跳）时，选择静态方向；其余 state 仍使用现有 PHT。该规则不新增 RAM，
只在现有 `dynamicTaken` mux 中增加一个窄条件。

**观察依据。** 缓存 trace replay 在 `4,548` 个弱事件中得到 `+548` 个方向正确数的
上界。收益很小，且在 workload 间并不稳定；它更适合作为 BP01 的低风险对照，而
不是独立的强力优化。

**风险与准入。** 需要确认回跳位来自同一条 predecode 指令，且 BTB target/type 不
命中时现有代码已经使用 BTFNT，不会重复覆盖。候选必须先做 conditional branch、
BTB miss/target mismatch、flush/redirect 的逐周期 A/B；只要新增 mux 出现在前端
关键路径或任何 workload 回退超过门槛，就放弃。

### FQ02-R：LSQ oldest selector 与 order-clear 的两级局部化

这不是新的编号，而是对现有 `FQ02` 的第二轮具体化。当前 `pendingLoads` 的旋转
oldest select、8 个 Store 的物理 alias/SUC/byte-mask 检查和 `loadOrderClear` 都在
同一个请求资格链上。第一版只把 16-entry oldest 选择拆为 2x8 bank-local select，
保持 `loadHead/scheduledLoad/retry-token` 的寄存边界；第二版再把 `requestSent` 的
宽 CE 分解到局部 bank。不要同时改变 forwarding priority 或可见周期。

**动态理由（provisional）。** `blockedWithAlternate=425,678`、`oldestOrderBlockedAlternate=172,878`
和 `oldestLocalAliasBlockedAlternate=101,272` 表明该路径既有 IPC 机会，也不是只
存在于冷门异常；`multipleForwardingStoresBlock=5,047` 则说明不应先复制 forwarding
CAM。若 fresh top-N 仍落在 LSQ selector/CE，这一方向是当前最接近 B02-F 级别的双赢
候选；若路径已迁移，立即停止，不为旧路径继续加逻辑。

## 5. 阻塞项、负向结论与优先级修正

### C10：RAS transition 组合环（已关闭）

首次候选生成已实际复现 Spinal `PhaseCheckCombinationalLoops`，
错误链位于 `architecturalRasCountOneHot -> architecturalRasPushAccepted -> when ->
architecturalRasCountOneHot`。这不是 observer 的副作用。`38c4a07` 已修复环路，并由
BranchPredictorSpec 3/3、L1InstructionCacheSpec 11/11、OooCoreIntegrationSpec 4/4
及第二次完整门禁关闭；BP01/BP02/FQ02-R 的准入仍取决于各自 RTL A/B，而不再被 C10 阻断。

1. **PHT 容量不扩不缩（provisional）。** 缓存 trace 观察到 `3,943/4,096` 个 PHT index，不能以“有
   空间”推断容量没用上；容量类候选继续保持 P1 条件状态。
2. **DIV 不进入首批。** DIV operand blocked 只有 `0.127%`，新增 divider 或 fast
   path 需要独立证明 signedness、除零、flush 和吞吐，当前 IPC 上界不足以抵消风险。
3. **ROB/PRF 不扩容。** FreeList blocked `0.158%`，且当前 top-N 没有 PRF read/bypass
   路径；BRAM 空闲不能转化为寄存器文件端口和布线的免费收益。
4. **IQ 不复制、执行端口不加宽。** IQ full 仍低，issue 端口也没有显示全核饱和；先
   读 fresh top-N，再决定是否做端口内 wakeup 复制。
5. **DispatchRouter 保持观察。** `73.03%` 的 dispatch acceptance 只说明存在拒绝，
   没有说明是 capability conflict、ROB/FreeList/LSQ capacity 还是 lane prefix；在
   没有拒绝分类前不重写 3-to-4 greedy matching。
6. **FQ01 仍是条件性 P0。** R21 的 `+0.160 ns` 是含 FT09/MT09 的 dirty aggregate，
   不能单独归因；只有新的 clean B02-F+LDQ16 matching top-N 仍出现 ROB-to-LSQ release/
   occupancy 时，才做 FQ01 单变量 A/B。

## 6. 后续最小实验集

按时间性价比只保留三组实验：

1. 已关闭 C10 的 R22 matching clean direct-full 得到 setup/hold `-0.271/+0.057 ns`；
   top-50 为 LSQ 48、IQ 1、ROB/CSR 1，frontend/predictor/cache/ATU 均为 0。Observer v2
   继续只作为 `dev/freq` 开发证据，不阻塞时序候选。
2. 在 matching trace harness 中补齐或由 RTL A/B 取得真实 `dynamicPredictionHit`，然后只比较
   BP02 和 64/128/256 项 BP01；不再为每个 PHT index 增加计数器。
3. R22 前 47 条路径已经从 `loads_10_completed` 指向 `scheduledLoad_*`，因此立即做
   FQ02-R 的周期透明 bank-local selector/payload capture；BP01/BP02 暂缓，直到 fresh
   top-N 再迁回 frontend/predictor，且仍须用 `cycles x actual Fmax` 评估。

所有候选仍必须绑定源码树 hash、生成 RTL hash、软件 hash、seed、cycles、资源和
setup/hold WNS/TNS。正确性失败优先于任何 IPC 或 WNS 结果。
