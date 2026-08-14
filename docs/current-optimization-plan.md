# MIKU 当前多轮优化计划

本文是动态执行清单，随候选和 matching implementation 结果更新。长期不变的测试、归档、
并发和发布合同见 [verification-workflow.md](verification-workflow.md)，候选机制、状态与实测
效果见 [optimization-candidates.md](optimization-candidates.md)。

## 当前基线与目标

- CPU 开发分支：`dev/ECHO`。
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`。
- perf20：当前 W01+FT06+MT05 RTL 为 `5,014,520` cycles，20/20 pass；W01 相对 WT02
  减少 42,348 cycles（`-0.837435%`），几何平均加速 `1.010598877x`；BR01 相对 L07
  `5,104,911` 为 `-0.921799%`，归一化几何平均 `1.009753745x`；相对本轮原始
  baseline `5,543,953` 累计 `-9.549738%`。
- func58：当前 W01+FT06+MT05 matching RTL 的 random-AXI seeds `240/255/141` 均为 58/58。
- W01+FT06+MT05 matching 100 MHz direct full implementation：setup `-0.395 ns`、hold
  `+0.053 ns`、setup TNS `-12.353 ns`、156 个失败 endpoint；DRC 0 error/critical warning、
  fully routed、bitstream 成功。相对 WT02 的 setup 改善 `0.246 ns`，但仍不是里程碑。
  placed utilization 为 86,810 LUT、53,760 FF、68.5 BRAM、8 DSP；routed hierarchy
  summary 为 86,935 LUT、53,822 FF。
- 最新 top-50 中 IQ 占 38 条，最差 `-0.395 ns`，平均 route 占比 `77.79%`；前六条均由
  ROB lane 0 的 `stagedPdst_0` 经 wakeup/select 驱动 LSU IQ 宽 issue payload。ROB/CSR
  占 4 条、cache/L2 占 7 条、predictor 占 1 条，frontend、ATU/LSQ 均未进入 top-50。
  FT06、MT05 的组合物理目标得到支持，但单次组合 route 不能拆分出各自收益。
- BT04 相对 MT03+BT03 的器件总 LUT `86,489 -> 89,422`、寄存器
  `54,358 -> 54,881`、slice `26,920 -> 27,745`，BRAM `56.5 -> 54.5`。其 top-50 全部为
  IQ，最差路径 `-1.442 ns`，由 recovery 经另一个 IQ 的 direct wakeup/select 级联到本地复制
  输出寄存器；平均 route 占比 `80.45%`。这证明恢复宽双槽输出既没有周期收益，也放大了
  面积、拥塞和跨 IQ 控制锥，不能作为时序修复保留。
- post-route `-0.055 ns` 只用于识别路径族，不是正式产物。

本阶段固定 100 MHz，不做升频探索。先让 direct full implementation 闭合，再把正 WNS
作为 IPC 候选的时序预算；核心性能指标是固定 100 MHz 下的 perf20 周期。

## R0：实验合同

- `experiment-freeze` 锁定源码、RTL、模型、软件、工具、Chiplab 和显式证据。
- `experiment-compare` 校验 A/B 身份并输出逐项、总周期和归一化几何平均。
- `timing-analyze` 将 top-50 归为 frontend、predictor、IQ、ROB/CSR、LSQ、cache/L2、
  platform 和 other CPU。
- `test-impact` 根据版本化路径映射给出最低定向测试集合。
- `soc-archive` 只接收 experiment manifest 明确引用且 hash 匹配的证据。
- `PerfObservationV1` 已用八个本地 owner 的 64-bit word 建立稳定仿真 ABI；外部 monitor
  不再访问普通 Verilator 内部层级。当前 Scala 门禁为 39 suites / 222 tests，Python
  合同为 56 项；clean/instrumented dhrystone A/B 的平台周期、退休数、计分周期和 UART hash
  精确一致，全部计数器守恒 invariant 通过。`perf20-sim` 与 `func58-sim` 可通过
  `SIM_PROFILE=instrumented` 使用同一公开入口。
- L07 前的 M01 v4 完整 perf20 matrix 为 score `5,299,059`、ROI `5,299,039`、退休
  `3,608,034`、IPC `0.680885`。ROB 非空零退休占 ROI `56.10%`，其中 head incomplete
  `54.38%`；按 head uop 分类，Load `22.87%`、Store `18.61%`、branch `8.19%`、other
  `4.70%`，ROB 空仅 `6.73%`。这使 Store/Load completion latency 进入首轮 IPC 候选，
  但这些比例是理论暴露上界，不能直接当作预期加速比。
- L07 matching M01 v5 的 score/ROI 为 `5,104,911/5,104,891`，退休指令仍为
  `3,608,034`，IPC 提高到 `0.706780`。Store head-incomplete 从 `986,333`
  降到 `764,452` cycles（占 ROI `18.61% -> 14.97%`），SQ 满周期从
  `11.13% -> 9.06%`；ROB 非空零退休同步减少 `198,145` cycles。Load
  head-incomplete 为 `1,224,728` cycles：相对占比升至 `23.99%`，但绝对值只比前一
  节点多 `12,819`，尚不能判断为 L07 引入的 Load 回归，也不能直接证明 L02 可回收。
  v5 进一步量出 branch completion 命中 ROB head `156,532` 次（`3.0663%` ROI）、普通
  Load 原始完成命中 head `354,260` 次（`6.9396%`）以及 oldest Load 阻塞但存在另一个
  地址已就绪 pending Load `364,380` 次（`7.1379%`）。三者都是一拍或调度机会的上界；
  只有 branch 项已具备不跨越 LSU 数据寄存边界的低侵入实现路径。

## R1：时序候选与周期验证

首批按 `BT01 -> MT01 -> MT02 -> FT02 -> FT03` 线性累积；首轮 route 暴露 IQ 宽 payload
CE 后，再以 `BT02` 作为同一 R1 的证据驱动增量。每个节点独立提交，并执行受影响 suite、
完整 `cpu-check`、完整 perf20 20/20 和相对前一节点的 A/B；每个待实现组合在启动 Vivado
前补齐 matching func58 三 seed。

理想结果是 perf20 20 项逐项相等。最终组合允许归一化几何平均性能回退小于 `0.5%`，但
必须记录全部分项，并与目标路径族、WNS、TNS 和资源变化交叉验证。周期改善的候选转为性能
候选记录；超过允许回退的候选退出 R1 组合，除非独立实现证据证明它是 100 MHz 闭合所必需。

R1 晋级要求是 func58 三 seed通过、direct full setup/hold WNS 均严格大于零、
DRC 0 error/critical warning、fully routed 和 bitstream 成功。正 WNS 不用于升频。

当前进度：

- `BT01 @ fc73f5b`：IssueQueue 定向测试和完整 `cpu-check` 通过；perf20 相对
  `5,306,558` 基线 20 项逐项精确相等，几何平均 `1.000000000x`。该节点作为
  周期透明的 R1 累积基线保留，matching implementation 效果待五项组合后统一验证。
- `MT01 @ 9838c9d`：LSQ 定向测试和完整 `cpu-check` 通过；perf20 相对 BT01
  20 项逐项精确相等，总周期 `5,306,558`、几何平均 `1.000000000x`。生成 RTL
  已确认 resident LQ payload CE 不再经过 completion 仲裁。
- `MT02 @ e0e503c`：L1D MSHR/backpressure、dirty writeback 和 refill error 定向
  测试及完整 `cpu-check` 通过；perf20 相对 MT01 20 项逐项精确相等，总周期
  `5,306,558`、几何平均 `1.000000000x`。每个 MSHR 的窄 pending 位现已独立
  资格化 L2 read request；cache/L2 路径效果待 R1 matching route。
- `FT02 @ bcce5fe`：OooFrontend 定向 23 项、完整 `cpu-check` 和 perf20 通过。turnover
  token 由匹配且成功的 translation response 资格化，下一次翻译仍保持同拍启动；相对
  MT02 总周期 `5,306,558 -> 5,299,059`（`-0.141316%`），几何平均加速 `1.006239125x`。
  该节点出现可归因的分项周期变化，已从“周期透明”转为 R1 性能候选；最终组合仍按
  平均性能门槛和 direct implementation 判定。
- `FT03 @ e14957a`：OooFrontend 定向 23 项和完整 `cpu-check` 通过；taken 后的年轻
  lane 只写入不可见槽位，`count/tail` 继续定义唯一可见前缀。perf20 相对 FT02 20 项
  逐项精确相等，总周期 `5,299,059`、几何平均 `1.000000000x`。
- R1 首批组合的 func58 seeds `240/255/141` 均为 58/58；direct full 为 setup
  `-0.466 ns`、hold `+0.050 ns`、fully routed、DRC 0 error/critical warning 且 bitstream
  成功。该结果比旧 direct full 改善 `0.086 ns`，但尚未满足 setup 门禁，不能晋级。
- `BT02 @ 26bfef9`：IQ resident payload 改为固定物理槽，仅压缩 3-bit 年龄索引。
  IssueQueue 10 项定向测试、完整 `cpu-check`（39 suites、213 tests）和 perf20 20/20 通过；
  相对 R1 首批组合 20 项逐项精确相等，总周期 `5,299,059`、几何平均
  `1.000000000x`。matching func58 seeds `240/255/141` 均通过，证据已冻结到
  `build/reports/experiments/R1-BT02/experiment-manifest.json`。matching direct full 的
  setup/hold 为 `-0.678/+0.053 ns`，DRC、route 和 bitstream 完整；旧 IQ payload CE
  路径族已经消失，并降低 4,062 LUT，但新主导族转为 22 条 LSQ 到 ATU 路径。BT02
  继续保留，R1 尚未闭合；下一增量优先局部化 ATU response payload 的资格控制。
- `MT03 @ 11da524`：ATU response slot 空闲时预填 direct/DMW bypass payload，accepted
  request 只设置窄 valid；TLB completion 和 mutation 保持更高覆盖优先级。ATU 8 项、Core
  4 项、系统集成、完整 `cpu-check`（39 suites、214 tests）和生成 RTL 结构检查均通过。
  perf20 相对 BT02 为 `5,299,059 -> 5,299,059`，20 项逐项精确相等、几何平均
  `1.000000000x`；func58 seeds `240/255/141` 均为 58/58。证据冻结在
  `build/reports/experiments/R1-MT03/experiment-manifest.json`，逐项比较见
  `build/reports/comparisons/R1-MT03.json`。为保持综合时间性价比，MT03 不单独启动
  Vivado。
- `BT03 @ b78a701`：普通执行端口不再复制完整 issue-address uop；IQ 为 8 个 resident
  entry 配置 9 个固定物理 payload slot，发射后仅以 4-bit token 保留被 backpressure 的
  payload 所有权。LSU 端口继续使用原有双槽注册输出。IssueQueue 10 项、Backend 17 项
  定向测试及完整 `cpu-check`（39 suites、215 tests）通过；新增测试证明 backpressure 下
  payload 稳定，并保持释放后的逐拍吞吐。perf20 相对 MT03 为
  `5,299,059 -> 5,299,059`，20 项逐项精确相等、几何平均 `1.000000000x`；func58 seeds
  `240/255/141` 均为 58/58。冻结证据见
  `build/reports/experiments/R1-BT03/experiment-manifest.json`，逐项比较见
  `build/reports/comparisons/R1-BT03.json`。组合 direct full 的 setup/hold 为
  `-0.694/+0.053 ns`，DRC、route 和 bitstream 完整。MT03 已将 LSQ 路径族移出 top-50；
  BT03 的新 token slot payload read 路径占 47/50，说明其宽 issue-address register 虽已移除，
  但物理目标未达成。当前实现保留作正确且周期透明的实验节点，下一次 RTL 迭代需切断
  recovery/wakeup/select 与 9-way payload read 的同拍组合锥，或从后续组合移除 BT03。

## IPC 第 1 至第 3 轮

R1 后至少完成三轮 IPC 优化。每轮从新的稳定 baseline 选 2 至 3 个相互较独立的候选，
每项获取完整 perf20 A/B，最终组合只验证一次，不枚举所有组合。轮次之间可按证据需要插入
小型 R0 harness 补强或 R1 类周期透明时序批次。

候选进入 RTL 前应有可回收周期占总周期至少 `1%` 的证据上界。默认至少取得 `0.5%` 的
归一化几何平均改善才保留；单项退化超过 `3%` 必须解释和记录，但不自动否决。组合破坏
100 MHz 时序时，先优化候选自身数据通路，仍不能闭合则移除最低有效收益项。

候选池按观测选择：前端热命中空泡优先 F01 多 context；branch resolve/redirect 暴露优先
K01 或 B01；Load/cache miss/AXI 串行优先 L02、H05 或新内存候选；IQ credit、端口匹配或
DIV head-of-line 有权重时优先 I01、D02 或 E03。新的正确性问题使用 `Cxx`，只阻断受影响
方向，其他独立方向继续推进。

当前 IPC-R1 首项为 `L07 @ 652631f`。普通 cached Store 不再进入 execution 的宽
completion mux，只以 ROB pointer 和 recovery epoch 的窄身份通道进入 ROB；异常、SC、
uncached 与冲突路径保持原宽 completion 语义。LSQ 32 项、ROB 14 项、完整 `cpu-check`
（39 suites / 216 tests）、perf20 20/20 和 func58 三 seed 均通过。perf20 为
`5,299,059 -> 5,104,911`，总周期 `-3.663820%`、几何平均 `1.038957091x`，20 项全部改善；
逐项证据见 `build/reports/comparisons/R2-L07.json`。本项改变 LSQ/ROB completion 时序，
在加入同轮其他独立候选后必须以 matching direct full 重新判断 100 MHz；此前不继承
L07 前 `-0.694 ns` 的时序结论。

L07 matching instrumented perf20 进一步证明收益来自目标阻塞族：Store head-incomplete
绝对减少 `221,881` cycles，SQ 满周期减少 `127,198`，ROB 非空零退休减少 `198,145`。
当前最大的剩余 head-incomplete 类别是 Load（`23.99%` ROI），其次为 Store（`14.97%`）
和 branch（`8.60%`）。下一增量先用稳定观测 ABI 细分 Load 的 translation、老 Store
顺序、cache request/response 与可跳过 oldest-load 机会，再决定 L02 或新的内存候选；
branch resolve-to-recovery 暴露仍有 `153,592` cycles（约 `3.01%` ROI），可与内存方向
并行形成同一综合批次的独立候选。

同轮第二个 IPC 候选 `BR01 @ 1851a3c` 已实现并保留：对当前 epoch、精确命中 ROB head、
无异常且 payload ready 的已解析 branch，在现有 ROB staging 边界保存 result、taken、target
与 mispredict，并允许下一拍退休。ROB 定向测试覆盖开关 A/B、旧 epoch、完成异常、predictor
FIFO 容量阻塞、link result 和误预测恢复；完整 `cpu-check` 为 39 suites / 218 tests。
clean perf20 为 `5,104,911 -> 5,057,854`，总周期 `-0.921799%`、几何平均
`1.009753745x`；19 项改善，`stream_copy` 仅增加 1 cycle（`+0.0088%`）。逐项证据见
`build/reports/comparisons/R2-BR01.json`，冻结证据见
`build/reports/experiments/R2-BR01/experiment-manifest.json`。它不做 execute-time squash，
不改变 speculative RAT/FreeList/LSQ，因此与 B01 的复杂选择性恢复不是同一机制。

同批时序候选 `BT04 @ 50f998c` 已实现后否决：退出 BT03 的 9-way token payload read，
恢复 BT02 已验证的每端口本地两槽注册 issue output；配置开关仍可选择 BT03 token 路径，
用于结构 A/B 回归。IssueQueue 10 项、Backend 17 项以及完整 `cpu-check`（39 suites / 218
tests）通过，测试覆盖两种输出结构在 backpressure 下的 payload 稳定性和连续逐拍发射。
clean perf20 相对 BR01 为 `5,057,854 -> 5,057,854`，20 项逐项精确相等、几何平均
`1.000000000x`；matching func58 seeds `240/255/141` 均为 58/58。逐项证据见
`build/reports/comparisons/R2-BT04.json`，冻结证据见
`build/reports/experiments/R2-BT04-local-issue-output/experiment-manifest.json`。matching direct full
为 setup/hold `-1.442/+0.050 ns`；top-50 全部为 IQ，且相对 BT03 增加 2,933 LUT、523 FF、
825 slice。`91be40f` 已恢复 BT03 token 输出为默认，BT04 仅保留作可复现实验分支。

下一时序候选 `WT01 @ ecd4786` 针对该实现暴露的共同根因：execution 的 direct-wakeup
注释原本声称不含 flush，但旧表达式通过 `fire = valid && ready` 把 redirect/flush 送入了
全局 wakeup/select 网络。WT01 显式拆分资源可接受性与架构 fire；completion 仍由真实 fire
资格化，恢复周期只允许产生由 IQ flush 优先级吞掉的不可见 wake 候选。Execution 定向 11 项、
C01 barrier 门禁、IQ flush 负向用例及完整 `cpu-check`（39 suites / 219 tests）通过；生成 RTL
已确认 direct-wakeup 表达式不含 `io_flush`，completion 仍含 `io_issueReady`。完整 perf20
为 `5,057,854 -> 5,057,854`，20 项逐项精确相等、几何平均 `1.000000000x`；func58
random-AXI seeds `240/255/141` 均为 58/58。逐项证据见
`build/reports/comparisons/R2-WT01.json`。matching direct full 的 setup/hold 为
`-0.824/+0.056 ns`，DRC、route 和 bitstream 完整；WT01 已把 BT03 中由 recovery 驱动的
IQ 路径从 top-50 的 47 条降为 0 条，证明物理目标达成。新的 top-50 分布为 frontend 19、
predictor 10、cache/L2 9、IQ 11、ATU/other CPU 1；最差路径是 instruction translation
response 的 virtual-address 到 uncached payload，随后是 frontend next-PC、BTB 更新和 L1I
response prediction 路径。下一时序增量应处理这些已暴露路径，R1 仍未满足 setup 门禁。

后续增量 `FT04 @ c90409e` 将 FixBranch 修正 PC 在 cache response 边界保存为窄 token，
并在原本就存在的 predictor recovery 周期安装。OooFrontend 23 项、完整 `cpu-check`
（39 suites / 219 tests）通过；测试明确锁定恢复后第一拍即发出 corrected translation request。
完整 perf20 为 `5,057,854 -> 5,057,854`，20 项逐项精确相等、几何平均
`1.000000000x`，证据见 `build/reports/comparisons/R2-FT04.json` 和
`build/reports/experiments/R2-FT04/experiment-manifest.json`。它尚未运行 matching func58
或 direct full，因此当前只算已验证候选提交，不改变 WT01 的物理基线结论。

`MT04 @ 11c760b` 将 instruction direct/DMW response 的 MAT 从已接受的 instruction owner
context 推导，移除 virtual address 到 response payload 的旁路预填组合依赖。ATU 9 项、完整
`cpu-check`（39 suites / 220 tests）通过；perf20 相对 FT04 为
`5,057,854 -> 5,057,854`，20 项逐项精确相等。逐项证据见
`build/reports/comparisons/R2-MT04.json`，冻结证据见
`build/reports/experiments/R2-MT04/experiment-manifest.json`。

`FT05 @ fd40007` 在已注册 L1I owner 活跃期间持续预填 pending response 的 PC 和 prediction
context，命中 handoff 只设置窄 valid。定向测试证明同拍安装年轻 owner 时，较老 response 的
预测 taken/target 仍保持配对；OooFrontend 24 项、完整 `cpu-check`（39 suites / 221 tests）
通过。perf20 相对 MT04 为 `5,057,854 -> 5,057,854`，20 项逐项精确相等；证据见
`build/reports/comparisons/R2-FT05.json` 和
`build/reports/experiments/R2-FT05/experiment-manifest.json`。

`WT02 @ 4435ae2` 对 direct-only execution port 屏蔽其 staged ROB completion 在 IQ 中的
重复 wake echo；原始 completion 仍进入 PRF、RenameMap、dispatch source-ready 和架构完成
路径，Multiply 继续使用独立 writeback lane。新增测试覆盖已驻留消费者及 direct event 后才
到达的消费者；完整 `cpu-check` 为 39 suites / 222 tests，Python 合同 55 项。生成 RTL 中
lane 2 registered wake 已为常量 `0`，`stagedPdst_2` 不再进入任何 IQ。perf20 为
`5,057,854 -> 5,056,868`（`-0.019494%`），几何平均 `1.000446233x`；7 项改善、12 项不变，
quick_sort 单项 `+9 cycles`（`+0.00331%`）。它仍按时序候选保留，这个极小净改善用于与
matching route 交叉验证，不按 IPC 候选的 `0.5%` 门槛宣称收益。证据见
`build/reports/comparisons/R2-WT02.json` 和
`build/reports/experiments/R2-WT02/experiment-manifest.json`；matching func58 seeds
`240/255/141` 均为 58/58。matching direct full 的 setup/hold 为
`-0.641/+0.050 ns`，DRC、route 和 bitstream 完整；相对 WT01 改善 `0.183 ns`，资源为
87,244 LUT、54,390 FF、56.5 BRAM、8 DSP。top-50 已全部转为 IQ，最差路径由
`stagedPdst_1` 驱动 LSU IQ 输出 payload，说明下一批应优先处理 divider 共享 lane 的重复
wake echo，同时允许有严格 owner/context 等价证明的非 top-N 周期透明候选一并验证。

下一批候选按线性节点验证，不枚举组合。`W01 @ 45512a6` 已重新启用并完成验证：首次
DIV、SC 和其他变长完成继续保留，完整 perf20 从 `5,056,868` 降至 `5,014,520`
（`-0.837435%`），18 项改善、2 项不变、无退化；其 matching 物理影响留给本批最终 direct
full 交叉验证。multiplier 独立 writeback lane 的重复 echo 候选 `WT03 @ 6cdaf17` 已被
workload 活性反例否决：coremark、dhrystone 和 fireye_B2 均在测试开始后停止架构进展，
`0704cd8` 已恢复默认 wake。前端 translation response VA 与已注册 translation owner PC、ATU instruction
response identity 与已注册 context 是两个不增加拍数即可去掉实时输入依赖的候选，即使未
进入当前 top-50，也按周期透明合同进入本批。`FT06 @ a2b29d5` 已让 response VA 只参与
owner comparator，接受后的 cache/predictor handoff 改用注册的 `translationPc`；完整门禁为
39 suites / 222 tests，perf20 20 项逐项精确相等。`MT05 @ 6a42541` 已让 ATU 可见的
instruction response VA 直接来自注册的 instruction context；paged/direct/DMW/cancel、mutation、
backpressure 和同拍 replacement 定向测试以及完整门禁通过，生成 RTL 的公开 response VA 已直接
连接 owner context，perf20 仍为 `5,014,520 -> 5,014,520`，20 项逐项精确相等。redirect drain
同拍接收新 translation 时的 owner 安装问题单列为性能候选，必须先用定向失败测试确认机会与
正确语义。本批最终组合的 func58 random-AXI seeds `240/255/141` 均为 58/58，已满足启动
matching direct full implementation 的软件门禁。matching direct full 已完成：setup/hold
为 `-0.395/+0.053 ns`，相对 WT02 改善 `0.246 ns`；DRC 0 error/critical warning、fully
routed、bitstream 成功，但 setup 仍未闭合。top-50 分类为 IQ 38、ROB/CSR 4、cache/L2 7、
predictor 1；最新主导族从 lane 1/3 转为 ROB lane 0 staged wake 到 LSU IQ 宽输出。

下一时序节点 `WT04` 计划把 LSU IQ 的“持久 source-ready 更新”和“同拍 select bypass”
拆成两个合同：所有已注册 completion wake 仍在原拍写入 resident/enqueue ready 状态，只有
ALU direct wake 与 load early wake 继续进入 LSU IQ 同拍 select。这样不丢失 CSR、DIV、SC、
Multiply 等首次 completion；它们的内存消费者最坏晚一拍被选择。该候选允许小于 `0.5%` 的
平均性能回退，但必须完整记录 20 项变化，并以 matching route 判断 staged ROB tag 是否退出
LSU IQ 宽 payload 路径。

## 系统、归档与发布

MMU、cache、AXI、异常或内存顺序发生变化的轮次运行 Linux；其他候选在每个时序闭合
里程碑运行 Linux。若若干候选已形成细粒度提交、完整软件证据和清楚的阶段结论，可在轮次
中途推送远端 `dev/ECHO` 备份阶段成果，不要求先闭合时序。`main` 只在 matching 100 MHz
direct full 的 setup/hold WNS 均严格大于零、DRC/route/bitstream 完整且 Linux 门禁通过后
fast-forward 到同一提交。板侧服务器恢复后补做板测；此前只标记本地稳定里程碑。
