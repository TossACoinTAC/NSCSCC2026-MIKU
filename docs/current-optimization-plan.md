# MIKU 当前多轮优化计划

本文是动态执行清单，随候选和 matching implementation 结果更新。长期不变的测试、归档、
并发和发布合同见 [verification-workflow.md](verification-workflow.md)，候选机制、状态与实测
效果见 [optimization-candidates.md](optimization-candidates.md)；本轮 top-50 之外的静态时序审计见
[timing-static-audit-r5.md](timing-static-audit-r5.md)。

## 当前基线与目标

- CPU 开发分支：`dev/ECHO`；最近稳定 100 MHz 里程碑的开发分支证据提交为
  `fbc9634`，已 squash 发布到 `main @ 6b559ec`。R6 `L11+L13` matching 源码身份为
  `6bbf9ed`；它已经完成新的 direct full implementation，但 setup 未闭合，因此仍是候选，
  不替代 R5 稳定里程碑。
- 当前 IPC 实验基线为 `R8 A01 @ 363abd6`：clean perf20 `4,215,442` cycles，20/20
  通过；相对 SD01 `4,246,698` 减少 `0.736007%`，几何平均加速 `1.004974278x`，
  18 项改善、2 项退化。A01 尚未执行 matching direct full，因此没有可继承的 WNS、
  资源或板测结论。
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`。
- 历史 R6 L13：clean RTL 为 `4,423,675` cycles，20/20 pass，相对 L11 `4,865,310`
  下降 `9.077222%`，几何平均加速 `1.085725368x`；20 项全部改善。L13 的 clean
  matrix 为 `build/sim/runs/cpu_00f0a4acce30_chiplab_c398d274812f/clean-perf20_model_4f54ae244f79_software_f6e7c20f71a4/ideal/matrix_65876ab77466_perf20.csv`。
- func58：L13 random-AXI seeds `240/255/141` 均为 58/58，摘要为
  `build/sim/runs/cpu_00f0a4acce30_chiplab_c398d274812f/clean-func58_model_403c6f68d060_software_3fe689f227db/random/matrix_1892a80af7f5_summary.txt`。
- R5 `RT02+MT08+CT03+RT03` matching 100 MHz direct full 已闭合：setup WNS
  `+0.028 ns`、hold WNS `+0.047 ns`、setup TNS `0`，fully routed、DRC 0
  error/critical warning、bitstream 成功。placed utilization 为 91,059 LUT、55,813 FF、
  66.5 BRAM、8 DSP；相对上一份 R5 full route，setup 改善 `0.215 ns`，LUT/FF 分别增加
  3,011/1,318，BRAM/DSP 不变。top-50 为 predictor 3、IQ 12、ROB/CSR 14、cache/L2 17，
  frontend 4，LSQ/platform 为 0；所有路径均为正 slack。稳定本地归档位于
  `Stable_Backup/cpu_fbc96342366c_chiplab_c398d274812f_perf_100mhz_20260815-112750/`。
- 同一 R5 direct-full bitstream 已在远程 LabAgent `10.19.75.72` 完成 matching perf20
  板测：job `20260815-114325-2bc00a63`、`nscscc-system-reset-v1`、20/20 通过，40 次
  原始双跑全部通过。保守选中结果合计 `cpu_count=43,489,002`、
  `soc_count=43,501,239`；相对上一份可比板测 `627aca6` 的 `50,772,461` CPU cycles，
  合计下降 `14.345294%`，逐项几何平均加速 `1.168457245x`。上传包 SHA-256 为
  `3687124f745a95398ffbf282897cec62b2b380454c0f99bbea269439b34d2ec7`，bitstream
  SHA-256 为 `4c9b4d0ccadd032e305f8acbdb03ec1a3538c7a2e5e65fce559a789d849448ba`；
  证据位于稳定归档的 `board/20260815-114325-2bc00a63/`。该结果只属于 R5，不能继承
  给 R6 L13 或后续 RTL。
- R6 `L11+L13` matching 100 MHz direct full 已完成：setup WNS `-0.057 ns`、setup
  TNS `-0.138 ns`、hold WNS `+0.048 ns`，fully routed、DRC 0 error/critical warning、
  bitstream 成功；placed utilization 为 91,002 LUT、55,869 FF、66.5 BRAM、8 DSP。
  该结果距 setup 门禁仍差 57 ps，故只归档为 candidate：
  `Post_Impl_Bundles/cpu_6bbf9edcdd36_chiplab_c398d274812f_perf_100mhz_20260815-205104/`。
  top-50 为 cache/L2 18、frontend 17、ROB/CSR 10、IQ 3、predictor 1、LSQ 1；最差
  cache/L2、frontend、ROB/CSR 分别为 `-0.057/-0.003/-0.017 ns`，LSQ 最差仍为
  `+0.094 ns`。这说明当前 setup 缺口主要是 cache/L2 的高布线占比路径，不能归为
  L11/L13 引入的 LSQ 面积膨胀。
- Linux clean random-AXI seed `5570815` 的固定 50 ms 窗口在 L13 通过，exit code 为 0；摘要为
  `build/sim/runs/cpu_00f0a4acce30_chiplab_c398d274812f/clean_model_7735f43e91c7_software_d3ce90aca67c/random/matrix_517ad2574f10_summary.txt`。
  该证据只表示固定窗口回归通过，不表示已进入用户态。
- 历史 R3 matching 100 MHz direct full implementation：setup `-0.440 ns`、hold `+0.009 ns`、
  setup TNS `-13.390 ns`、128 个失败 endpoint；DRC 0 error/critical warning、fully routed、
  bitstream 成功，但仍不是里程碑。placed utilization 为 88,048 LUT、54,595 FF、
  56.5 BRAM、8 DSP。相对 WT04，WNS 改善 `0.149 ns`、TNS 改善 `28.035 ns`、失败 endpoint
  减少 180 个，但 LUT/FF 分别增加 688/370。
- R4 `IT01+MT06+CT01+FT08` matching direct full 已完成 fresh route：setup WNS `-0.106 ns`、
  hold WNS `+0.051 ns`、setup TNS `-0.435 ns`、10 个失败 endpoint；fully routed、DRC 0
  error/critical warning、bitstream 成功。资源为 87,633 LUT、54,616 FF、56.5 BRAM、8 DSP，
  相对 R3 减少 415 LUT、增加 21 FF。R4 top-50 为 IQ 18、ROB/CSR 12、cache/L2 9、predictor 5、
  LSQ 6，前端为 0；最差路径是 `rob/stagedPdst -> issueQueues_0/ageOrder_0`，
  `-0.106 ns`，route 占比 80.17%，LSQ 最差已改善至 `+0.041 ns`。
- R3 将 WT04 top-50 中 predictor 的 `45/50` 降为 `2/50`，证明 PT01+AT01 组合切断了
  目标锥；ROB/CSR 在两次 route 中都为 0，不能从本次 route 单独量化 RT01。新的 top-50
  为 IQ 25、LSQ 16、cache/L2 7、predictor 2；最差路径是 L1I registered response valid
  到 frontend `cacheOutstanding`，setup `-0.440 ns`。其次为 LSQ completion `-0.380 ns`
  和 IQ issue output `-0.281 ns`，构成下一批时序候选的三个相对独立方向。
- BT04 相对 MT03+BT03 的器件总 LUT `86,489 -> 89,422`、寄存器
  `54,358 -> 54,881`、slice `26,920 -> 27,745`，BRAM `56.5 -> 54.5`。其 top-50 全部为
  IQ，最差路径 `-1.442 ns`，由 recovery 经另一个 IQ 的 direct wakeup/select 级联到本地复制
  输出寄存器；平均 route 占比 `80.45%`。这证明恢复宽双槽输出既没有周期收益，也放大了
  面积、拥塞和跨 IQ 控制锥，不能作为时序修复保留。
- post-route `-0.055 ns` 只用于识别路径族，不是正式产物。

本阶段固定 100 MHz，不做升频探索。R5 direct full 已闭合，当前 `+0.028 ns` setup WNS
作为下一轮 IPC 候选的初始时序预算；核心性能指标仍是固定 100 MHz 下的 perf20 周期。

## R0：实验合同

- `experiment-freeze` 锁定源码、RTL、模型、软件、工具、Chiplab 和显式证据。
- `experiment-compare` 校验 A/B 身份并输出逐项、总周期和归一化几何平均。
- `timing-analyze` 将 top-50 归为 frontend、predictor、IQ、ROB/CSR、LSQ、cache/L2、
  platform 和 other CPU。
- `test-impact` 根据版本化路径映射给出最低定向测试集合。
- `soc-archive` 只接收 experiment manifest 明确引用且 hash 匹配的证据。
- `PerfObservationV1` 已用八个本地 owner 的 64-bit word 建立稳定仿真 ABI；外部 monitor
  不再访问普通 Verilator 内部层级。当前 Scala 门禁为 39 suites / 225 tests，Python
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
- R5 matching instrumented perf20 为 `5,014,776` cycles，20 项与 clean matrix 逐项
  精确相等；ROI 为 `5,014,756` cycles、退休 `3,608,034`、IPC `0.719483`。ROB
  非空零退休占 `53.45%`，其中 head Load/Store 未完成分别占 `26.55%/15.40%`；ROB
  空和满分别占 `6.90%/3.75%`。普通 Load 原始完成命中 ROB head `382,202` 次
  （`7.6215%` ROI），构成 R6 `L10` 的一拍上界。oldest Load 被阻塞且存在另一个
  地址已就绪 Load 的 `340,689` 次只表示调度暴露，未知老 Load 仍可能解析为 SUC，
  因而不能直接视为 L02 的安全可回收周期。branch recovery 只占 `0.97%`，本轮不优先
  扩大分支恢复机制。汇总证据为 `build/reports/observations/R6-baseline.json`。

## R6：Load/SQ 归因与性能实验

首项 `L10` 使用 LSQ 原始 ordinary cached Load completion 产生窄身份 token；ROB 只在
当前 epoch、准确命中下一拍 head 时保存 token。Load 数据仍由现有 LSQ completion 寄存器
提供，下一拍 token 与该固定延迟数据配对，使 head Load 可与既有 completion 写回同拍退休，
避免把 cache response/forwarding 的组合数据路径直接拉入 ROB 提交控制。异常、LL、SUC、
translation completion、flush、旧 epoch 和非 head completion 全部保留原路径。

`L10 @ 518714a` 的动态上界为 `382,202/5,014,756 = 7.6215%`，但完整实验说明该上界
大部分不能转化为总周期收益。原始实现首先暴露出一项真实恢复边界：Load 已提前退休，随后
年轻分支 recovery 与延迟一拍的 PRF 写回重合时，普通 flush 抑制会丢失已成为架构状态的
写回；`43d8f41` 将恢复例外严格限定为同一拍实际退休的准确 Load 身份。修复后
ReorderBuffer 18/18、完整 `cpu-check` 39 suites/231 tests、RTL/Yosys/合同门禁和 perf20
20/20 均通过。

相对 R5，perf20 总周期为 `5,014,776 -> 5,015,136`（`+0.007179%`），归一化几何平均
加速仅 `1.000411877x`，远低于 `0.5%` 保留门槛；单项在 `coremark -1.27359%` 至
`fireye_I2 +1.16388%` 间重排，说明早退主要改变调度相位，未稳定回收整体瓶颈。
`4cdfd21`、`baca0c6` 已依次撤回修复与实现，当前默认 RTL 回到 R5。比较证据为
`build/reports/comparisons/R6-L10.json`。

`L11 @ fe4028c` 处理 matching observer 中占 ROI `9.1629%` 的 SQ-full 暴露：ordinary
cached Store 在退休且完整捕获到既有 request buffer 的边沿释放原 SQ 槽；buffer 随后作为
已提交架构状态，在 cache backpressure 和 recovery flush 下保持请求并参与 Store drain、
`olderStorePending` 与 barrier 排空。为避免把新的地址比较锥接回已闭合的 LSQ 时序路径，
buffer 占用期间暂时不发出年轻 Load；该限制不会阻止已被 buffer 占用的单一 cache request
端口，只会推迟原本可能由 SQ entry 直接完成的同址 forwarding。

定向测试覆盖提前 release、稳定 backpressure、年轻 Load 顺序、flush 后继续 drain 与槽位
复用；完整 `cpu-check` 为 39 suites/231 tests、58 项 Python 合同，发布 RTL hash 为
`057eb73a39c98e97b59b1fd7b0cfc28a0495ca622f7bc7fd4fa57b9e193d70bb`。clean perf20
20/20，总周期 `5,014,776 -> 4,865,310`（`-2.980512%`），归一化几何平均加速
`1.034213285x`；`stringsearch -16.38779%`、`fireye_D1 -13.61335%`、
`my_memcmp -10.27449%`，主要回退为 `bubble_sort +0.78006%`。func58 random-AXI
seeds `240/255/141` 均通过。逐项比较见 `build/reports/comparisons/R6-L11.json`；该候选
达到性能保留门槛。相同源码身份的 Linux random-AXI seed `5570815` 已完成固定 50 ms
窗口且 exit code 为 0。后续以 matching instrumented observer 判断剩余 SQ 压力和是否值得
扩展为多项退休 Store buffer，暂不直接把 STQ 从 8 扩到 16。

`L12 @ 35da264` 尝试在 cached Store request 被接受的同一边沿捕获下一项 Store。定向测试
通过，但相对 L11 的完整 perf20 为 `4,865,310 -> 4,865,462`（`+0.003124%`），几何
平均仅 `1.000059817x`，并且没有触及主要的 Load buffer 空泡；`3f80400` 已独立回退，
只保留比较证据，不进入后续组合。

`L13 @ 0c771bd` 将 Load 的所有权转移点从 cache handshake 前移到 request-buffer capture。
当 buffer 反压时，外部请求 payload 仍保持稳定且只存在一份；flush 会同时清除未被层次接受
的 buffer 请求和对应 speculative LQ entry。由于 `requestSent` 在 capture 边沿登记，调度器
可以在下一拍选择下一项 Load，不再反复选择 buffer 内的旧 Load。完整门禁为 39 suites/232
tests、59 项 Python 合同，LSQ 定向 34/34；clean perf20 为 `4,865,310 -> 4,423,675`
（`-9.077222%`，几何平均 `1.085725368x`），20 项全部改善，func58 三 seed 通过。
matching instrumented 观测显示 `load_candidate_buffer_busy` 从 `887,069` 降至 `3,561`，
ROI IPC 从 `0.741587` 升至 `0.815623`。观测汇总为
`build/reports/observations/R6-L13.json`，instrumented matrix 为
`build/sim/runs/cpu_00f0a4acce30_chiplab_c398d274812f/instrumented-perf20_model_73548b35df4a_software_f6e7c20f71a4/ideal/matrix_7fead17be770_perf20.csv`。
Linux 固定窗口已通过；这是当前 R6 的软件性能基线。matching direct full 已在
`6bbf9ed` 完成，结果为 setup/hold `-0.057/+0.048 ns`、setup TNS `-0.138 ns`，
fully routed、DRC 0 error/critical warning 且 bitstream 成功。由于 setup 仍为负，R6
保留为高性能候选，不晋级 100 MHz 里程碑，也不覆盖 R5 的 Stable_Backup。

随后两类局部实验均未进入 R6 组合。`L14 @ 28bbe94` 允许 request buffer 中已提交 Store
向年轻 Load 转发，以降低 L11 保守顺序边界的代价；完整 perf20 仅从 `4,423,675` 降到
`4,422,920`（`-0.017067%`，几何平均加速 `1.000096047x`），`2b6a697` 已回退。
predictor 容量实验中，`B02-B @ 2b428d6` 扩大 PHT 后总周期反而增加 `0.028800%`，
`8071454` 已回退；`B02-C @ efb6572` 将每 bank BTB 扩容后为 `4,423,675 ->
4,409,791`（`-0.313857%`，几何平均加速 `1.002553477x`），但低于 `0.5%` 保留
门槛且增加 predictor memory bits，`6eda207` 已回退。三项均无 matching Vivado 证据，
后续实现仍以 L13 的 `4,423,675` cycles 为 R6 baseline。

R6 observer 同时暴露出一项 harness 归因错误：当前硬件 `loadQueueEntries=8`，旧 monitor
却用 `load_occupancy >= 16` 推导 LDQ full，导致该字段恒为零；其他
`oldest blocked + alternate address ready` 计数也只是宽松上界，不能证明年轻 Load 已满足
alias、MAT、LL/SUC 和顺序条件。`M02 @ 6cce873` 将容量 full 和逐级资格直接放入版本化
observation word，monitor 不再猜测微架构容量。matching instrumented perf20 保持
`4,423,675` cycles、ROI IPC `0.815623`，确认 observer 修改周期透明；LDQ/SQ 满周期分别为
`805,264`（`18.2036%`）和 `141,232`（`3.1927%`），最老 Load 仅受本地 alias 条件阻塞且
存在地址已就绪替代项为 `120,185` cycles（ROI 的 `2.7178%`）。因此下一批同时进入
`L02` 受限 younger-ready Load bypass 和 `L03` LDQ-only 8-to-16 独立 A/B；L03 首次不扩大
STQ/SDQ，避免在低得多的 Store full 暴露量下无依据地放大 forwarding/order cone。

`L02 @ 2c586e5` 采用单次寄存 retry token：只有最老 Load 仅受已知本地 Store alias 条件
阻塞时，才在下一拍选择一个更年轻、已翻译、cacheable、非 LL 的 Load，并让它重新通过
原有的完整顺序和 forwarding 资格锥。LSQ 36/36、完整 `cpu-check` 和 perf20 20/20 通过；
相对 L13 baseline 为 `4,423,675 -> 4,373,845`（`-1.126439%`），几何平均加速
`1.007186175x`。最大单项收益是 `inner_product -6.379%`，没有超过 `0.03%` 的显著退化，
因此保留进入 R7 组合。比较证据为 `build/reports/comparisons/R7-L02.json`。

## R7：受证据驱动的 Load 并行度

`L03 @ 2ab264f` 只把 LDQ 从 8 扩为 16，STQ/SDQ 继续保持 8；L1D waiter 的 index
合同也用 index 15 的 miss/refill 定向测试覆盖。完整 `cpu-check`、发布 RTL、Yosys 和合同
门禁通过。为了避免把组合收益错误拆分，开发分支保留了 `fab80ad` 的 L03-only 实验节点和
随后恢复 L02 的显式 revert。L03 独立 perf20 为 `4,423,675 -> 4,320,785`
（`-2.325894%`），几何平均加速 `1.013960668x`；20/20 通过，最大收益
`fireye_A0 -17.29978%`，最大退化 `fireye_B2 +0.34980%`。

L02+L03 当前组合 perf20 为 `4,262,710`，相对 R6 baseline 减少 `160,965`
cycles（`-3.638717%`），几何平均加速 `1.022228468x`；L02 在 LDQ16 上仍提供约
`-1.344%` 的增量，因此两项均独立越过保留门槛。组合门禁的发布 RTL SHA-256 为
`d4abf008a36335fb5ea8d1995c2e2f143ff5de59754687a32a5cc8fe62f67fdd`。Observer ABI v8
的 matching instrumented perf20 已完成：20 项与 clean 的 verdict/cycles
逐项相等，总计均为 `4,262,710`；ROI IPC 为 `0.846422`，DUT 导出的 LDQ 容量为 16。
LDQ full 从扩容前的 `18.2036%` 降至 `3.7878%`，平均占用为 `6.16/16`；SQ full 为
`3.7618%`，ROB full 为 `6.3083%`。这不支持继续把 LDQ 扩到 32，也不支持在缺少独立
Store 证据时扩大 STQ/SDQ；后续 IPC 归因转向剩余的 Load latency、Store head stall 和
前端/分支机会。v8 汇总见 `build/reports/observations/R7-L02-L03-v8.json`。

R7 func58 random-AXI seeds `240/255/141` 均为 58/58；Linux random-AXI seed
`5570815` 的固定 50 ms 窗口 exit code 为 0，运行 `24,999,995` cycles、退休
`20,447,238` 条指令。matching 100 MHz direct full 的 setup/hold 为
`-1.112/+0.009 ns`，setup TNS `-105.363 ns`，fully routed、DRC 0 error/critical warning
且 bitstream 成功；top-50 全部属于 LSQ 的 younger-retry 选择锥，因此 R7 仍是软件性能
候选，不覆盖 R5 稳定里程碑。candidate 归档为
`Post_Impl_Bundles/cpu_54dc2e378983_chiplab_c398d274812f_perf_100mhz_20260815-224609/`。

`MT09 @ cef1047` 把 younger retry 的 16-entry 选择先编码为窄的注册 index，再在下一拍沿
原有完整顺序条件重新资格化，从 LSQ 热路径移除物理槽选择链。LSQ 36/36、完整门禁
39 suites/235 tests 和 RTL/Yosys/合同通过；perf20 为 `4,262,719`，仅 bitcount 增加
9 cycles，总周期回退 `0.000211%`，其余 19 项精确相等。该受控代价进入下一次 matching
implementation 交叉验证，不能仅凭静态 RTL 宣称时序已修复。

随后用 observer v9/v10 对下一批 IPC 方向做联合归因。M03 测得 SDQ 同拍多个 ready 的
周期为 `14,347`（ROI `0.3366%`），物理槽选择违反 ROB 年龄的周期仅 `1,720`
（ROI `0.0404%`），因此不实现 SDQ age-priority。M04 的 clean/instrumented 总周期均为
`4,262,719`，ROI `4,262,699`、IPC `0.846420`；条件历史 group 中有 `107,496`
个包含两个及以上条件分支，占 `11.6163%`，当前少折叠约 `108k` 个 GHR bit，B02-D
据此晋级。L1D 新 hit 压过更老 miss waiter 仅 81 cycles（ROI `0.0019%`），H08 关闭；
同拍多个 ready waiter 为 `83,442` cycles（ROI `1.9575%`），H09 进入可回退 A/B。

## R8：Rename oldest fallback

`A01 @ 363abd6` 针对 rename 整组资源阻塞：当完整三宽组无法同时获得 ROB、FreeList、
LSQ allocator 或 dispatch queue 资源时，只要 lane 0 可以接受，就接受最老 uop，并在
`DecodeRenameBuffer` 中压缩 lane 1/2、从空出的尾槽补入新 decode uop。ROB、FreeList、
LSQ allocator 和 dispatch queue 均使用 accepted mask 计数，FreeList 进一步按 GPR
destination 资格化，避免 Store/branch 等不写 PRF 的 uop 消耗物理寄存器。

实现过程中捕获并修正两项真实边界：FreeList 不能按 accepted uop 总数扣减，且 accepted
mask 为零时不能把“已接受但无 GPR 写回的 lane”误判为旧布尔接口的未接受状态。新增的
DecodeRenameBuffer、backend fallback 和 FreeList 回归覆盖了这两项语义；完整 `cpu-check`
通过，clean perf20 20/20 通过。

本轮相对 SD01 的总周期为 `4,246,698 -> 4,215,442`（`-0.736007%`），几何平均加速
`1.004974278x`，18 项改善、2 项退化。几何平均略低于常用的 `0.5%` 单候选保留门槛，
但总周期下降明确且改善分布并非单一长尾，因此先保留 A01，待与另外 1 至 2 个独立 IPC
候选累积后再做一次 matching direct full。当前没有新的 WNS 或资源数据；下一门禁仍是
受影响定向 suite、完整 `cpu-check`、完整 perf20，并在组合稳定后再运行 func58 和一次
100 MHz direct full。

### A02 结果

`A02 @ c56626e` 将 A01 的单路救援扩展为可接受两路 rename prefix。完整 `cpu-check`
为 40 suites/246 tests，perf20 相对 A01 为 `4,215,442 -> 4,217,187`，总周期增加
`0.041395%`、几何平均 `1.000151543x`；9 项改善、11 项退化，其中 `fireye_I2` 增加
`0.51301%`。这不足以换取额外 rename 控制复杂度，故 `157320b` 将配置恢复为默认关闭；
保留 opt-in 定向回归和 A/B 证据，不为 A02 启动 matching implementation。

### H09 结果

H09 根据 M04 的 multiple-ready waiter 机会，在同一 recovery epoch 内按 ROB 年龄选择
L1D refill waiter，并保留跨 epoch 的物理槽回退。完整门禁通过，完整 perf20 相对 A01
为 `4,215,442 -> 4,215,222`，减少 220 cycles（`-0.005219%`），几何平均加速
`1.000031642x`，2 项改善、18 项持平。实际收益远低于 M04 的 `83,442` cycles 机会
上界，也不足以证明年龄比较值得引入 response 选择路径，因此 H09 默认关闭，仅保留
配置开关和定向回归供后续新观测复核；本轮没有为 H09 启动 Vivado implementation。

### M05 v12：A01 后资源归因

当前默认组合（A01 启用、A02/H09 关闭）重新运行 instrumented perf20，clean 与
instrumented 逐项周期一致，总计 `4,215,422` ROI cycles，退休 `3,608,034` 条，IPC
`0.855913`。与 M05 v11 的 pre-A01 身份不同，v12 的资源计数不能与旧计数直接相减，
但边界变化清晰：ROB 平均占用 `22.87/32`、full `16.36%`，LDQ full `6.59%`，SQ
full `5.50%`；ROB 非退休头部仍以 Load/Store incomplete 为主（分别约 `17.89%`/
`12.78%` 的观测周期）。rename 的整组 blocked 计数为 `52.37%`，说明 A01 将部分
队头阻塞转化为更深的在途压力，而不是证明所有 blocked group 都可通过继续放宽 prefix
获益。A02 已有独立 A/B 退化，因此下一项优先审计 R02 的 ROB/PRF/epoch 协同扩容，
并保持默认组合不变。

### R02：expanded-window 可行性 A/B

`R02` 在不改变默认配置的前提下放宽了配置边界：`ROB 32 -> 64`、`PRF 64 -> 128`，
并把 ROB 指针从 6 bit 扩为 7 bit。epoch 资格化、年龄比较和回收逻辑继续使用原生指针
宽度；`PerfObservationV1` 为保持正式 ABI 仍只导出低 6 bit 指针字段，因此本实验只把
observer 用于周期和结果一致性，不将 expanded 结果用于指针级观测归因。

expanded 配置通过 `ReorderBufferSpec` 默认/expanded 两种完整指针绕回测试（17/17）和
`OooCoreSpec` 容量合同（15/15）。完整 clean perf20 20/20，A01 默认基线
`4,215,442 -> 4,167,970`，总周期下降 `1.126145%`，几何平均加速 `1.015314274x`。
改善 14 项、退化 6 项；最大退化为 `loop_induction +2.03898%`，最大改善为
`lookup_table -8.39206%`。逐项证据见
`build/reports/comparisons/R8-R02-expanded-window.json`。

为区分 ROB 与 PRF 的贡献，又以相同软件、seed 和仿真合同测试了 `ROB 64 / PRF 64`
对照点。该变体总周期为 `4,195,770`，相对 A01 仅下降 `0.466665%`，几何平均加速
`1.006604867x`；在此基础上把 PRF 扩到 128 后再下降 `0.662572%`，几何平均加速
`1.008652260x`。因此 `64/128` 的约 `1.13%` 不是 ROB 单独扩容的效果，额外 PRF
在更大 ROB 上具有可测的协同收益。`64/64` 保留为可复现实验变体
`CPU_VARIANT=expanded-rob`，主 R02 仍为 `CPU_VARIANT=expanded-window`。配对证据分别为
`build/reports/comparisons/R8-R02-expanded-rob.json` 和
`build/reports/comparisons/R8-R02-prf128-increment.json`。

expanded 发布 RTL 为 `build/rtl/package-expanded-r02/rtl/mycpu_top.v`，SHA-256
`712df1c5f6c177d2e45e1a48f5f2c53036883e3168dc28c9af1eff79364c2c3e`；Yosys
`core-top-yosys-check` 通过。generic cell 数约 `89,083`，当前默认 RTL 同门禁约
`72,059`，增加约 `23.6%`；`64/64` 对照点为 `84,603`，说明本次成本主要来自 ROB，
而 PRF 64 -> 128 的边际成本约 4,480 cells。该统计不等同 Vivado LUT/FF，也不能推断时序。expanded
实验因此保留为性能候选但默认关闭，下一门禁是与至少一个独立候选合并后的 matching
100 MHz direct implementation；在得到正 WNS 前不得进入稳定组合。
根入口使用 `make cpu-generate CPU_VARIANT=expanded-window` 显式生成该变体；省略参数时
始终生成 `default`，不依赖容器环境中的隐式开关。

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

时序节点 `WT04 @ 8457b6f` 已把 LSU IQ 的“持久 source-ready 更新”和“同拍 select bypass”
拆成两个合同：所有已注册 completion wake 仍在原拍写入 resident/enqueue ready 状态，只有
ALU direct wake 与 load early wake 进入 LSU IQ 同拍 select。IssueQueue/Backend 的开关 A/B
测试证明 fast wake 延迟不变、registered-only wake 持久化且最坏晚一拍选择；完整 `cpu-check`
为 39 suites / 224 tests，RTL、Yosys、Verilator strict-zero lint 和 56 项 Python 合同均通过。
完整 perf20 相对 W01+FT06+MT05 为 `5,014,520 -> 5,014,520`，20 项逐项精确相等、几何平均
`1.000000000x`；func58 random-AXI seeds `240/255/141` 均为 58/58。候选已冻结在
`build/reports/experiments/R2-WT04/experiment-manifest.json`；是否切断 staged ROB tag 到 LSU IQ
宽 payload 的物理路径，只由该身份的 matching direct full 判断。该实现现已完成：setup/hold
为 `-0.589/+0.053 ns`，setup TNS `-41.425 ns`，308 个失败 endpoint；DRC 0 error/critical
warning、fully routed、bitstream 成功。原先 38 条 ROB staged wake 到 LSU IQ 路径从 top-50
全部消失，说明结构目标达成；top-50 同时转为 predictor 45 条、IQ 5 条，当前最差路径是 BTB
bank 到 instruction ATU context。WT04 因软件周期透明且物理目标达成而继续保留，但本次未形成
时序里程碑。

R3 已积累三个时序候选并完成软件验证：`PT01 @ bc98e07` 将四 lane earliest-taken 选择改为
平衡树；`AT01 @ 11652b9` 将 instruction direct/DMW PA/MAT 选择延后到注册 owner；
`RT01 @ 5646510` 将 ROB 提交热 metadata 从 payload bank 移入 state。三者各自的定向测试、
完整门禁均通过，当前门禁为 39 suites / 225 tests；三次 perf20 A/B 都是 20/20、总周期
`5,014,520` 且逐项精确相等。AT01 暴露的 Main TLB 复位问题已由独立 `C09 @ 2bc5433`
修复并回归，不能把正确性修复收益归给 AT01。比较证据为 `R3-PT01.json`、`R3-AT01.json`
和 `R3-RT01.json`。R3 组合 func58 random-AXI seeds `240/255/141` 均为 58/58；
matching direct full 为 setup/hold `-0.440/+0.009 ns`、setup TNS `-13.390 ns`、128 个
失败 endpoint，DRC/route/bitstream 完整。相对 WT04 的组合物理结果明确改善，但尚未满足
正 WNS 里程碑；归档见
`Post_Impl_Bundles/cpu_434b34291ca7_chiplab_c398d274812f_perf_100mhz_20260815-031510/manifest.json`。

R4 不再把 RTL 文本变短或显式条件消失当作时序成功，而是按 R3 的详细 routed path 改变
寄存边界或选择拓扑。当前累计四项：

- `IT01 @ 71e36ee` 把 8-entry oldest-ready 串行覆盖链改为平衡归约树，目标是 R3 rank 6
  的 wake/source-ready 到 issue payload 路径。双配置测试、完整门禁及独立 perf20 通过，
  总周期保持 `5,014,520`，20 项逐项精确相等。
- `MT06 @ 6011bd5` 为八个 Store 分别寄存格式化 forwarded Load data，下一拍只用已注册
  owner 选择 bank，目标是 R3 rank 2/3/4/7 等 Store alias/年龄选择到 completion data 的
  13-level 路径。LSQ 双配置测试及完整门禁通过。
- `CT01 @ e7fb4ad` 在合法 hit turnover 候选存在时预读同步 L1I data array，让 tag hit 只
  决定接受与可见性，不再驱动 RAM enable。它只针对 R3 rank 30 的次级物理路径，不宣称
  解决 rank 1 的 frontend correction 锥。MT06+CT01 相对 IT01 的完整 perf20 为
  `5,014,520 -> 5,014,520`，20 项逐项精确相等；该矩阵只能证明二者组合周期透明。
- `FT08 @ c085441 + 5486380` 对 rank 1/5/8/10 的共同 16-level 路径做状态边界重构：prediction
  correction 当拍只捕获 corrected PC、translation drain 与 uncached drain 的窄 token，宽
  outstanding/drop/prediction cleanup 延后到既有 recovery 周期。cached owner 由同步 L1I
  kill 取消，uncached owner 保留 drain；新增负向测试覆盖 recovery 周期到达的最小延迟
  uncached response。首次完整 perf 进一步暴露并复现一个真实边界：hit-turnover 年轻响应
  已在 correction 边沿注册，next-cycle L1I kill 不能撤回其输出；旧接收条件会错误入队四条
  年轻指令，使 occupancy 从 2 增到 6。`5486380` 用同一个已注册 kill token 将该响应分类为
  drop，未把 predecode 接回状态锥。修复后 Frontend 25/25、完整 `cpu-check` 39 suites/229
  tests、RTL/Yosys/Verilator/Python 合同均通过；发布 RTL hash 为
  `b169f8139a794ed20a6b9a0e5db346957485ee7c71c5aff46fe4283e4bfa553e`。完整 perf20
  20/20，相对前三项组合为 `5,014,520 -> 5,014,546`（`+0.000518%`）；归一化几何
  平均性能回退 `0.005882%`，最大单项回退为 dhrystone 的 `0.05849%`，没有异常长尾。
  该微小而可归因的周期代价由 matching route 是否真正移除 frontend correction 路径来裁决。

R4 最终组合 func58 三 seed 均已通过；matching direct full 已完成但 setup 仍差 `0.106 ns`。
R5 已完成四个相互独立候选的组合 direct implementation：

- `WT05 @ 0e47e4b`：ordinary IQ 只用 direct/fast wake 做同拍 select bypass，ROB/LSQ 的 registered wake
  仍更新 resident `sourceReady`，但不再进入 age-order/select 的同拍宽路径。R4 最差路径正是
  `stagedPdst -> ageOrder`。定向 A/B 和完整 `cpu-check` 已通过；独立 perf20 为
  `5,014,546 -> 5,014,776`（`+0.004587%`），归一化几何平均性能回退
  `0.010913%`，明显低于时序批次 `0.5%` 的预算。它不是严格周期透明候选，但代价小且
  可归因，是否保留由 matching IQ/ROB top-N 与 WNS 裁决。
- `CT02 @ 8e58a9f`：L1I controller 的状态机保证 lookup 与 refill install 不会同拍发生，因此
  data RAM 的同步读使能只需 `lookupFire || maintenanceFire`，无需再经过 `!externalWrite`。
  该变化直接切断 R4 rank 2/8/10 的 tag/read context 到 data BRAM `ENARDEN` 控制锥；L1D/L2
  保持原语义。legacy、CT01 speculative pre-read 和 CT02 decoupled-enable 三配置定向测试均
  通过，组合完整 `cpu-check` 为 39 suites/230 tests，发布 RTL hash 为
  `36bb4a4514302411f72997a6cf965f86d601e7b88330bd3bc29fbad9fa1e95ac`。生成 RTL 已确认
  目标条件从 L1I data RAM 读使能中消失；完整 perf20 相对 WT05 20 项逐项精确相等，
  func58 random-AXI seeds `240/255/141` 均为 58/58。
- `CT03 + RT03 @ c47f7fb`：CT03 将 L1D/L2 data-array 的同步读使能与宽控制条件解耦，
  RT03 为 ROB 提交/恢复保存独立的注册 PC 状态。L1D 12/12、L2 8/8、ROB 17/17、
  commit adapter 1/1，完整 `cpu-check` 为 39 suites/230 tests；func58 三 seed 通过。
  CT03/RT03 组合 perf20 为 `5,014,776`，与 RT02+MT08 的匹配基线逐项精确相等，证据为
  `build/reports/comparisons/CT03-RT03-perf20.json`。四项组合 direct full 为 setup/hold
  `+0.028/+0.047 ns`，相对上一份 R5 full route 改善 `0.215 ns`；旧 ROB payload-address
  回路和 LSQ 负路径已退出 top-50。该物理结果只归属于组合，不能拆成 CT03 或 RT03
  的独立 WNS/资源收益。
- `MT07`：在 MT06 的 forwarding owner 边界前把 one-hot owner 编码为 3-bit registered index，
  completion 侧只做已注册 index 的局部 data select。只有新的 LSQ 路径重新进入 top-50 时才实施，
  避免为已变成正 slack 的路径增加寄存器。

R6 已以 L13 为软件 baseline 完成完整门禁、perf20、func58、Linux 固定窗口和 matching
observer；L14、PHT 扩容与 BTB 扩容均已完成 A/B 并退出组合。下一步冻结 L11+L13 身份并
启动一次 direct full implementation。旧稳定归档中 BTB prediction
到 instruction TLB request 的 `+0.028 ns`、ROB/CSR `+0.051 ns`、IQ `+0.099 ns`、
cache/L2 `+0.097 ns` 只属于旧 RTL 组合；L13 改变 LSQ 请求控制后必须重新读取 top-50，
不能把这些 WNS 当作当前预算。

## 系统、归档与发布

MMU、cache、AXI、异常或内存顺序发生变化的轮次运行 Linux；其他候选在每个时序闭合
里程碑运行 Linux。若若干候选已形成细粒度提交、完整软件证据和清楚的阶段结论，可在轮次
中途推送远端 `dev/ECHO` 备份阶段成果，不要求先闭合时序。`dev/ECHO` 保留候选级提交、
失败尝试和完整证据链；满足 matching 100 MHz direct full 的 setup/hold WNS 均严格大于零、
DRC/route/bitstream 完整且 Linux 门禁通过后，`main` 可将该阶段 squash 成一个里程碑提交，
不要求保留候选级提交拓扑。R5 稳定 bitstream 已完成 matching perf20 板测；后续每个新的
时序闭合里程碑仍须使用自己的 matching bitstream 重新板测，R6 当前不能继承该结果。
