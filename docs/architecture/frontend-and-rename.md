# MIKU 架构记录：前端、分支预测与 Rename

> 本文保留原架构长文中的历史讨论。文中的“当前”只指对应记录形成时的源码身份，
> 现在的验证范围以 [status.md](../status.md) 为准。

[返回架构总览](../architecture.md)

## 讨论记录

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
