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
- 当前默认组合为 `CT05 @ 56f792c` 加 `B02-F @ 080381a/5a11fe0/7324ccb`。CT05 的
  isolated perf20 相对 R9-CT04-RF03 为 `4,167,970 -> 4,148,574`（`-0.465358%`），
  几何平均 `1.003287491x`，无 workload 退化。最终组合的 func58 random-AXI seeds
  `240/255/141` 均为 `58/58 pass`。
  B02-F 相对 CT05 为 `4,148,574 -> 3,845,728`（`-7.300002%`），几何平均
  `1.033887409x`；完整 cpu-check 为 40 suites/257 tests、93 项 Python contracts。R9 的
  matching direct full 在 route 中止，未形成完成的 implementation，不能继承任何 WNS、资源或
  板测结论。
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

当前阶段在固定窗口 Linux 通过后，以 matching RTL 运行完整 direct implementation。B02-F
的 16-bit GHR、4 x 4096 x 2-bit PHT 使用 deterministic nonblocking weak-NT background
initialization，初始化期间 fallback BTFNT，且 `tableUpdateReady` 仅受 BTB sweep 约束；它不使用
外部 `readmem` 或阻塞 sweep。select_sort `+30.7112%`、stringsearch `+10.46449%`、fireye_B2
`+7.85938%`、bitcount `+6.30378%`、stream_copy `+4.81811%` 是 B02-F 的明显回退，主要改善为
fireye_I2 `-46.95697%`、minmax_sequence `-22.87373%`、quick_sort `-11.20753%`。PR 组合收益、
板测和任何 post-route physopt 结果均不能归因给 CT05 或 B02-F，亦不能替代 matching direct full。
ExpandedWindow Yosys 相对 CT05 仅增加 `36` 个 predictor cells；全核 cells
`65,853 -> 65,889`（`+0.055%`）、word bits `443,241 -> 443,689`（`+0.101%`），
没有外部 `readmem` 初始化依赖或大规模可复位 PHT 阵列。
固定窗口 Linux random-AXI seed `5570815` 完成 `24,999,995` cycles，结束原因是
`linux-time-window-complete`，exit code 0，未发现 DiffTest 或 trace mismatch。

R9 `CT05+B02-F` 的 direct full 从 matching RTL 开始，但在 route Global Iteration 1 后主动
中止：route 记录的中间 WNS/TNS 为 `-2.602/-7,764.275 ns`、峰值 overlap `115,866`、最终
样本 overlap `4,986`，`complete=false`，没有 routed DCP、正式 WNS、DRC 或 bitstream。placed
DCP 的 CPU top-50 最差为 `-1.131 ns`，分布为 IQ 14 条（平均 route 82.29%）、ROB/CSR 16 条
（79.62%）、cache/L2 4 条（71.92%）、LSQ 2 条（80.89%）、predictor 1 条和 other CPU 13 条；
其中 multiplier 结果路径具有 15--16 层逻辑，不属于 B02-F 的预测器锥。synth 的资源为
`96,466 LUT/45,234 FF/92 BRAM tile/8 DSP`，placed 为 `100,574 LUT/54,801 FF/94.5 BRAM tile/8 DSP`。
这些均是同一组合的探索信息，不能拆分归因给 CT05 或 B02-F，也不能归档为正式产物。报告位于
`build/reports/timing/R9-CT05-B02F-placed-dcp/` 与
`build/reports/timing/R9-CT05-B02F-partial-route-health.json`。

下一轮先将 B02-F 的显著分项回退转为独立实验：以 baseline/B02-F 的稳定 `PerfObservationV1`
分支 resolved/mispredict 计数和 20 项 cycles 对照，比较 8/10/12/14/16-bit history 与同为
4 x 4096 的 index/fold 方案。metadata 目前恰好容纳 12-bit index、2-bit state 和 row-valid，
不先引入需要扩宽 metadata 的 tournament 预测器。物理候选只选择不改变可见拍数的局部结构：
`IQT01` 将 IQ 到执行端的 source-tag 选择本地化，目标是 placed top-50 的 `psrc -> multiplier/
issueOperandSource` 高 route 路径；`MT11` 将 L1D refill 的 byte-mask/write-owner 控制按 bank
局部化，目标是 partial route 中 256--729 fanout 的 refill/`lookupMshrId` 网络。每项先做
定向测试、完整门禁、Yosys 同配置对照和合并 perf20；出现严重回退时再按提交边界分解。

### R10 时序候选批次

R10 不以“最后一次 top-50”替代历史归因。R7 的 50 条 `scheduledLoad.robPointer -> scheduledLoad
payload` 路径、R8 expanded-window 的 `drainAfterFlush -> ROB entry CE` 路径，和 R9 placed 的 IQ/
ROB/cache/multiplier 路径共同定义候选池。目标是在其中实际引入至少 6 个 R7--B02-F 期间的
针对性时序优化，并额外引入至少 3 个全局独立优化；下列 11 项是起始池而不是上限：

| ID | 来源 | 周期不变量与预期结构变化 | 最低验证 |
| --- | --- | --- | --- |
| MT12 | R7 scheduled-load token | 将 load owner、排序资格和宽 payload 的更新域拆开，避免 `robPointer` 进入每个 scheduled payload CE；不改变 selected load 到 translation/request 的拍数 | LSQ scheduling/forwarding/C04/C06、完整门禁、perf20、Yosys LSQ 与 top-N |
| MT13 | R7 younger retry | 将 younger-retry 的资格和选中 owner 局部化，避免已注册 owner 再驱动全 LQ 选择；不改变 retry 的下一拍 order-check | LSQ retry/flush/epoch、完整门禁、perf20 |
| MT14 | R7 store-drain | 将 recovery store-drain 对 rename/commit 的广播改为窄状态与局部 gating，保持 committed store 在新 epoch 前完全排空 | recovery、uncached/store drain、C06、func58 |
| RT12 | R8 ROB flush visibility | flush 只清除 entry 可见性和窄 pointer/count，失效 payload 不再接受全局 flush CE；恢复拍数不变 | ROB wrap/epoch/C03、flush/exception、完整门禁 |
| RT13 | R8 completion destination | 把 completion target 资格预解码为 bank-local write mask，减少 completion pointer 对全 ROB state 的比较/CE；不改变 wakeup/commit 时刻 | ROB multi-write/flush/head-bypass、PRF、完整门禁 |
| RT14 | R8 commit read fanout | 将 candidate pointer 与 retirement metadata 的跨 bank 读取/选择局部化，避免 commit lane 广播扩大为 64-entry 控制网；三路 commit 可见性不变 | ROB commit/CSR/branch recovery、perf20、Yosys |
| DQ02 | R8 dispatch payload | 仅在命中本 bank 的 rename 写入时更新 dispatch payload，分离全局 dispatch/flush 控制与宽 slot payload CE | dispatch/IQ enqueue/flush、完整门禁 |
| IQT01 | R9 placed IQ | 局部化 source-tag 到 multiplier/issue operand 的扇出，不增加 issue 或 operand-read latency | IQ select/wakeup/flush、依赖链、perf20、top-N |
| MT11 | R9 partial cache | refill byte-mask/write-owner 按 beat/byte bank 局部化，保持 store priority 和 L1D response 周期 | L1D partial-store/refill/error/C05/C06、Yosys memory/mux |
| CT06 | R9 cache/L2 | 将 L2 lookup MSHR ID、write-state 与 maintenance 状态的控制更新分域，避免请求 owner 控制宽 payload CE | L2 dirty victim/error retry/maintenance、完整门禁 |
| MX01 | R9 placed multiplier | 审计 DSP/carry 结果树的局部寄存和 operand mapping；只接受吞吐、乘法 latency 与 wakeup 时刻都不变的重排 | multiplier random/依赖链、perf20、Yosys DSP/carry 与 top-N |

候选不以数量换取风险：有任何拍数、异常/epoch、存储顺序或 payload-owner 不变量无法证明时，
该项不计入批次并保留审计记录。每个后续 direct full 前至少需要 5 个经定向测试、完整门禁、
同配置 Yosys 和合并 perf20 验证的时序候选；若回退一个已验证候选，必须补入另一项，不能以
单变量综合替代批次验证。

本次实际落地 10 项，超过最低数量：MT12 (`c4ae9f8`)、MT13 (`69e0d73`)、MT14
(`b0ac56c`)、RT12 (`3dbab9b`)、RT13 (`5f36353`)、RT14 (`9157f9e`)、DQ02
(`33aca34`)、IQT01 (`3651e19`)、MT11 (`03f3bb1`) 和 CT06 (`3aa1c25`)。每项已完成
对应定向 suite，批次提交后完整 `cpu-check` 通过。Yosys 对照见
`build/reports/yosys/R10-expanded-vs-R9.json`：同为 expanded-window 的全核 cells
下降 `1.358%`、post-flatten cells 下降 `1.496%`、word bits 下降 `0.483%`，这是
综合前结构筛选信息，不能替代 Vivado WNS。R9 参考报告使用 `expanded-window`
（ROB64/PRF128），而根 Makefile 默认生成 `default`（ROB32/PRF64）；不同配置曾产生约
`-10.6%` 的伪结构差异，已排除。因此 perf20 和 Yosys 都必须按同一 `CPU_VARIANT` 形成 A/B，不能
混写两种配置的总周期。expanded-window 的完整 perf20 已与 R9 B02-F 基线逐项精确相等：
`3845728 -> 3845728`，几何平均 `1.000000000x`；比较为
`build/reports/comparisons/R10-expanded-vs-R9-B02F.json`。同一配置的 func58 random-AXI
seeds `240/255/141` 也均通过。default 矩阵仅作补充验证，不参与 expanded-window 的晋级归因。

### R11 时序候选批次

R11 从 R10 的组合 RTL 继续，只收录不改变外部可见拍数的局部拓扑重排。当前净保留 13 项：
R7--B02-F 压力期间的焦点项为 MT15、L2T01、MT17、L1T01、L1T02、L1T03、L2T02、BPT01 和
MT18；全局独立项为 IQT02、MX01、RFT01 和 CA01。数量按当前净保留项计，不按历史实现次数计。
所有保留项各自受影响的定向 suite 已通过，但这只证明候选的定向功能合同，不构成 R11 组合的
完整软件、周期或实现结论。

| 候选 | 提交 | 局部目标与保持的不变量 | 当前定向门禁 | 后续门禁 |
| --- | --- | --- | --- | --- |
| IQT02 | `147bf8e` | IQ source-tag 以 token 在本地捕获，缩短 issue source-tag 的跨域选择；issue、operand-read 与 wakeup 时刻不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado IQ top-N |
| MX01 | `eae011a` | 合并 signed/unsigned `33x33` 乘法数据通路；吞吐、乘法 latency 与 wakeup 时刻不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado multiplier top-N |
| MT15 | `f09a5f9` | 拆分 scheduled-load translation 状态；selected load 到 translation/request 的拍数不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado LSQ top-N |
| L2T01 | `dee2208` | L2 read request 以 one-hot grant 局部化；仲裁优先级、请求身份和响应周期不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado L2 top-N |
| MT17 | `5bf8208` | selected-load 以 one-hot grant 本地捕获；LSQ 选择、顺序检查与 request 周期不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado LSQ top-N |
| L1T01 | `73a6100` | line-match miss state 的 merge grant 局部化；merge 身份、优先级与 response 周期不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado L1D top-N |
| L1T02 | `f8dd050` | L1D read request grant 局部化；请求仲裁、身份与 response 周期不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado L1D top-N |
| L1T03 | `f3314d0` | L1D victim writeback grant 局部化；victim ownership、写回顺序与 backpressure 语义不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado L1D top-N |
| L2T02 | `7d82ea1` | L2 victim writeback grant 局部化；victim ownership、写回顺序与 backpressure 语义不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado L2 top-N |
| RFT01 | `f99f8d0` | 固定捕获 FreeList `head+0/1/2` 分配候选；rename 分配、恢复和释放顺序不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado rename/FreeList top-N |
| BPT01 | `9a37c21` | 共享 predictor table-update bank decoder；预测/训练可见性和恢复语义不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado predictor top-N |
| MT18 | `b3e43be` | 移除 younger-ready valid 广播；retry-ready 资格和 LSQ 选择语义不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado LSQ top-N |
| CA01 | `a4f25df` | 共享 selected serial-op decode；commit、异常和 serializing 语义不变 | 通过 | 组合 Yosys、完整 `cpu-check`、perf20、matching Vivado commit/ROB top-N |

同配置 Yosys 已完成：`build/reports/yosys/R11-thirteen-vs-R10-expanded.json` 的全核 cells 为
`64994 -> 64845`（`-0.229%`）、post-flat cells 为 `58915 -> 58766`（`-0.253%`）、word bits
`+0.120%`。局部变化为 CA01 `-141`、LSQ `-29`、L2 `-8`、predictor `-6`、L1D `+4`、FreeList
`+7`、前三个 IQ 各 `+8` cells；这些是同配置综合前结构筛选信息，不能替代 Vivado WNS。

RT15 (`41e8ef5`) 与 RT16 (`d9b399e`) 均曾完成定向验证，但分别因同配置 Yosys 使 ROB 增加
`1027` 与 `4108` cells，已由 `8e6f229` 与 `a18b4f8` 回退，不计入 R11 的 13 项净保留候选。
16-bit GHR 的 R11 组合完整 `cpu-check` 已通过（41 suites、261 tests；Python contracts 94/94），
完整 perf20 20/20 也与 R9 的 `3,845,728` cycles 逐项精确相等。随后 B02-F2 将默认 GHR 从
16-bit 收敛为 10-bit：在同一 8 workload 短扫中，8/10/12/14/16-bit 的总周期分别为
`921,351/904,190/913,675/926,110/928,346`；完整 10-bit perf20 为 `3,798,148` cycles，相对
16-bit 降 `1.237217%`、几何平均加速 `1.022277448x`。`fireye_D1`、`fireye_I2`、`quick_sort`
分别增加 `0.12434%`、`2.60670%`、`0.63021%`，其余项目改善；其中 `select_sort` 和
`stringsearch` 分别改善 `15.83875%` 和 `6.70686%`。逐项比较为
`build/reports/comparisons/R11-B02F-history10-vs-history16.json`。`b4935cb` 将宽 history 的高位
fold 固定为测试 fixture 合同，`e0b5626` 将 Makefile/OooCoreConfig 默认值改为 10-bit。

最终默认 10-bit 组合冻结于 `a3950fe`，matching RTL SHA-256 为
`c4e0ff15924e89593d4eba1244685a9d8154f644b9c668273c35ec2ab87d6e4b`。完整 `cpu-check` 已通过：
40 suites、259 tests、Python contracts 94/94，generation、strict lint、Yosys 与 docs contract 均通过。
func58 random-AXI seeds `240/255/141` 均为 58/58，matrix 为
`build/sim/runs/cpu_70e97d278882_chiplab_c398d274812f/clean-func58_model_baa572b05940_software_3fe689f227db/random/matrix_1892a80af7f5_func58.csv`。
最终 Yosys summary 为 `build/reports/yosys/R11-final-history10-expanded/summary.json`；相对 16-bit
R11 组合的 comparison 为 `build/reports/yosys/R11-history10-vs-history16.json`，全核 cells
`64845 -> 64844`、word bits `442080 -> 442002`、post-flat cells `58766 -> 58765`，predictor 减少
1 cell。这些是同配置综合前结构筛选信息，不构成 Vivado 资源或时序结论。

matching 100 MHz direct Vivado 尚待运行。因此没有可继承的 R11 WNS、资源、DRC、bitstream、Linux
或板测结论。R10 的 partial route 及其任何中间 WNS 只可继续用于历史路径审计；只有冻结最终 R11
源码、生成 RTL、软件、工具和 Chiplab 身份后的完整 direct implementation 才能产生正式实现结论。若
任一组合门禁失败或发生不能接受的回退，按提交边界拆分并重新形成 R11 候选集。

### R12 拥挤根因批次

R12 不再只按上一轮 top-50 的表面模块名选项，而是联合 placed DCP、partial-route health、
层次拥挤和全局 Yosys LTP 做根因审计。历史 DCP 仍只用于选择候选，不给当前 RTL 继承 WNS。
审计得到的主要物理压力为：东侧 global level-4 区域 CLBLM 占用约 `132%`，其中 PRF/ROB
分别约占 `64%/33%`；北侧 short level-3 区域约 `110%`，backend/LSQ/ROB 分别约占
`86%/8%/5%`；另两个 short 热区中 ROB 分别约占 `78%/87%`。对应路径族包括
scheduled-load owner 资格、ROB completion 到 PRF/CSR/redirect、L2 hit MSHR 身份到 L1D，
以及 predictor RAS 动态选择。R12 将这些报告结论转成实际候选，而不是只保留文字建议。

本批次当前包含 13 项：

| 候选 | 提交 | 报告根因与结构动作 | 当前证据边界 |
| --- | --- | --- | --- |
| CT07 | `b74821f` | 去除 L1I speculative turnover 已拥有 array read 时重复的 request-fire lookup 写入 | L1I 定向与组合门禁通过 |
| IFT01 | `20878e9` | predecode 移到既有 L1I response register 之后，消除逐 way 的宽组合复制 | L1I 定向与组合门禁通过 |
| RF04 | `7956bd5` | PRF 在 completion 边界本地捕获 destination，缩短 ROB 到 PRF 的跨区资格网络 | backend/PRF 定向与组合门禁通过 |
| DEC01 | `97c16d6` | decoder instruction-valid 线性 OR 改为平衡归约 | frontend 定向与组合门禁通过 |
| AT02 | `cef4853` | 32 项 TLBSRCH index OR 改为平衡归约 | ATU 定向与组合门禁通过 |
| MT22 | `f617bc5` | Store data 与 recovery metadata 更新域分离，减少 LSQ 宽 payload 的全局 CE | LSQ 定向与组合门禁通过 |
| L1T04 | `00aae66` | pending Store owner 以局部 one-hot 更新 | L1D 定向与组合门禁通过 |
| HRT02 | `7ce70a2` | 在 shared refill router 建立 registered response boundary，切断 L2 MSHR ID 到 L1D 的跨区路径 | router 定向与组合门禁通过；增加一拍响应边界，须由周期与实现收益共同裁决 |
| MT19 | `9ad638d` | 在 LSQ 源头完成 load wakeup epoch 资格化，backend 只消费窄 valid/pDst | LSQ/backend 定向与组合门禁通过 |
| BPT02 | `4a7fd93` | 四 lane 共享 speculative RAS top 动态读取 | predictor 定向与组合门禁通过 |
| BPT03 | `5eeceb8`、`c0b2bd6` | architectural RAS 三宽更新改为局部 one-hot transition，并修复初版组合环 | predictor 定向与组合门禁通过 |
| L2T03 | `eadb3dc` | L2 hit response 在既有边界保持 one-hot grant，只在接口编码 MSHR ID | L2 定向与组合门禁通过 |
| RT17 | `27b4cef` | ROB completion result/control 分 bank，使 redirect/exception 不依赖 result payload | ROB 定向与组合门禁通过；ROB 局部 cells 增长，列为高风险物理权衡 |

测试 fixture 的时钟边界、CSR pin 和 recovery epoch 合同分别由 `333cf05`、`a8a68ff`、
`8356465` 修正；这些是面向公开时序语义的向前兼容修复，没有删除断言或放宽 DUT 合同。
当前 expanded-window 组合的完整 `cpu-check` 已通过：40 suites、262 tests，Python contracts
94/94，generation、strict lint、Yosys gate 和 docs contract 均通过；matching RTL SHA-256 为
`f46d711ef65bcb828ac7ec43abcbb4b455ac14e5bbc954456e8e1946bdba5fd1`。

同配置 Yosys 相对 R11 history10 baseline：全核 cells `64,844 -> 64,260`（`-0.901%`）、
word bits `442,002 -> 432,290`（`-2.197%`）、post-flat cells `58,765 -> 58,178`
（`-0.999%`）。局部主要减少来自 L1I `-818` cells 与 predictor `-129` cells；ROB
`+250` cells（`+2.23%`）及 `+230` word bits 是必须由 matching physical evidence 裁决的反向
信号。因为 ROB 本身位于最大拥挤区，不能用全核净减少替代局部判断；RT17 只有在 ROB/PRF、
commit/redirect top-N 和局部拥挤明确改善时才保留。

完整 perf20 20/20 为 `3,798,148 -> 3,798,085`，总周期改善 63 cycles；归一化几何平均为
`0.999128484x`，即约 `0.0872%` 性能回退，低于时序批次允许的 `0.5%`。最大分项退化为
fireye_A0 `+2.33517%`，最大改善为 loop_induction `-3.80876%`，没有项目超过 `3%` 退化门槛。
该组合结果不能拆分为 HRT02 或任一候选的单项收益；matching implementation 若没有明确切断
对应路径族，优先移除 HRT02 和 RT17。比较证据为
`build/reports/comparisons/R12-13cand-vs-R11.json`。func58、Linux 和 matching 100 MHz direct
full 仍须使用同一 RTL 身份完成；此前 DCP 和 WNS 不得继承。

### R13：扩容 ROB/PRF 的本地化访问批次

R12 的结构筛选表明，ROB 的 cells 从 `11,193` 增至 `11,443`，而历史 routed congestion 又将
PRF/ROB 指向最大的东侧与两个短程热点。因此 R13 不扩大任何容量，也不改变提交、完成、旁路或
恢复的架构拍数；它只将 expanded-window 的扁平地址/one-hot 网络拆为四个物理 bank 和 bank-local
row token。`RF05 @ fedb3a2` 将 128-entry PRF 的 8R5W 存储和写回译码分为 4 x 32；`RT18`
将 ROB 五路 completion 及 Store completion 的 64-bit one-hot target 变为 4 x 16 局部 token；
`RT19` 将 allocation 与 payload-ready 的动态写入局部化；`RT20` 将 commit invalidation
局部化；`RT21` 将 commit state read 改为 4 个 16:1 后再作 4:1 选择。RT21 的初版曾形成
commit-count 到 candidate state 的组合环，已在定向 ROB 测试中发现并改为使用既有的 registered
candidate pointer；该修复不改变预取边界。

最终组合的完整 `cpu-check` 为 41 suites、264 tests，Python contracts 94/94，generation、strict
lint、Yosys gate 与 docs contract 均通过。matching RTL SHA-256 为
`78596cd45e599abe706a482599761ac5d2e6c6119298945d5546b24ca83f096f`。相对 R12，Yosys 显示
全核 cells `64,260 -> 63,164`（`-1.706%`）、word bits `432,290 -> 420,628`（`-2.698%`）、
post-flat cells `58,178 -> 57,078`（`-1.891%`）；ROB 为 `11,443 -> 10,291` cells（`-10.067%`）
和 `41,718 -> 31,311` word bits（`-24.946%`），PRF word bits `37,256 -> 36,001`
（`-3.369%`），但 PRF cells 小幅 `+56`。这些数字支持对布局网络的针对性尝试，仍不是器件资源或
WNS 结论。

完整 clean perf20 20/20 相对 R12 逐项精确相等，总周期均为 `3,798,085`、几何平均
`1.000000000x`，比较为 `build/reports/comparisons/R13-capacity-local-vs-R12.json`。func58
random-AXI seeds `240/255/141` 均为 `58/58 pass`，matrix 为
`build/sim/runs/cpu_bbd36ac2be82_chiplab_c398d274812f/clean-func58_model_f960bd9f5f6a_software_3fe689f227db/random/matrix_2395d770132d_func58.csv`；
Linux random-AXI seed `5570815` 在 50 ms 窗口以 `linux-time-window-complete` 完成，运行
`24,999,995` cycles，结果为
`build/sim/runs/cpu_bbd36ac2be82_chiplab_c398d274812f/clean_model_706227055ff2_software_d3ce90aca67c/random/linux/seed_5570815/limit_50000000ns/sim-result.json`。

R13 matching direct full 已在 router 的 4.2 阶段停止：峰值/最终样本 node overlap 为 `135,525`，
中间 setup WNS/TNS 为 `-0.448/-62.807 ns`，没有 routed DCP、正式 WNS、DRC 或 bitstream。该结果
只说明 64 ROB / 128 PRF 的本地化组合仍处于不健康的拥挤区，不能把中间数当实现结论。

随后以相同源码、软件、B02-F history 配置完成 `CPU_VARIANT=default` 的 100 MHz direct full 对照。
它 fully routed、bitstream 成功、DRC 0 error，route 全程回到 zero overlap；routed setup/hold WNS
为 `-0.527/+0.051 ns`，setup TNS 为 `-108.677 ns`。归档为候选
`Post_Impl_Bundles/cpu_31982c053b0b_chiplab_c398d274812f_perf_100mhz_20260817-122604/`，
不能作为 100 MHz 里程碑。default 相对 64/128 的 perf20 代价为 `+2.104349%`，但它把“扩容导致
路由失控”与“所有配置都存在的 setup 路径”明确分开。

RF06 已完成（`5727016`）：OooBackendDispatch 25/25、RegisterStructures 13/13、完整 cpu-check
41 suites/264 tests、Python 95/95 都通过；Yosys 的 cells/word bits/post-flat 逐项不变，default
perf20 20/20 逐项精确等于 `3,879,728` cycles。它是纯跨区连线重定位，尚无 matching physical
证据，不能把 default route 的 WNS 归给它。

RF07（`a7b588a`，随后由 `2edc9d6` 回退）曾将 PRF 同拍 bypass 的比较和 data mux 下推到
bank-local row token。14/14 RegisterStructures 定向测试通过，但 fresh RTL 的 Yosys 对照显示
PRF cells `1,314 -> 1,449`、全核 word bits `389,771 -> 396,341`、PRF raw LTP `9 -> 12`；动态
row-bit 选择被映射为 `shiftx/procmux` 深链，不能作为物理优化保留。比较为
`build/reports/yosys/RF07-bank-local-bypass-fresh-vs-RF06-default-local-tag.json`；它未进入
perf20 或 Vivado。

随后在 default 32 ROB / 64 PRF 上形成五项周期透明批次：RF06、RT22、RT23、RT24 和 RT26。
RT22（`b5d1b65`）以目标 pointer 读取局部 resident state 后再资格化 completion，移除每个 producer
对 32 项完整状态的扫描；RT23（`9678a7d`）保留紧凑 completion-source 编码，在 commit 读侧使用
平衡选择；RT24（`1732ef5`）将三宽提交与 head-bypass 资格改为平衡 AND；RT26（`e04edc8`）把五路
head completion/branch bypass payload 的串行覆盖改为保持最高 lane 优先级的平衡树。RT23 的初版
one-hot state（`485a900`）已由紧凑版本替代；exception-valid OR 的等价表达实验（`109b873`）在
Yosys 中与 RT24 完全相同，已由 `58c3b48` 回退，不计入净候选。

最终批次源码为 `e04edc8`，source tree SHA-256 为
`20201323aab549087c36b42fad6f11ed763aec53ce22231496164b4bf48f26b9`，发布 RTL SHA-256 为
`4739957500dd83a03b7a95a57cc5444cf0d3deb63ab9394fdf62fd606248dfc3`。完整 `cpu-check` 为
40 suites / 263 tests，Python contracts 95/95；perf20 20/20 逐项精确等于 `3,879,728`
cycles，matrix 为
`build/sim/runs/cpu_20201323aab5_chiplab_c398d274812f/clean-perf20_model_d53c6fe04216_software_f6e7c20f71a4/ideal/matrix_52d9676ce812_perf20.csv`；
func58 random-AXI seeds `240/255/141` 均为 58/58，matrix 为
`build/sim/runs/cpu_20201323aab5_chiplab_c398d274812f/clean-func58_model_a66cdd41b7d7_software_3fe689f227db/random/matrix_2395d770132d_func58.csv`。

同配置 Yosys 相对 RF06 baseline 显示全核 cells `57,660 -> 57,205`（`-0.789%`）、word bits
`389,771 -> 391,210`（`+0.369%`）、post-flat cells `51,638 -> 51,183`（`-0.881%`），ROB
cells `6,067 -> 5,612`（`-7.50%`）。ROB raw LTP 从 40 降至 32；其中 RT22 贡献主要规模下降，
RT23/RT24/RT26 依次将 completion-source、commit 资格与 head-bypass 选择的逻辑深度压缩。
matching 100 MHz direct full 已完成，归档为
`Post_Impl_Bundles/cpu_5454bd3336b8_chiplab_c398d274812f_perf_100mhz_20260817-143710/`。同一
source tree、RTL 和实验 manifest 下，routed setup/hold WNS 为 `-0.816/+0.049 ns`，setup TNS
为 `-167.447 ns`，fully routed、DRC 为 0 error / 0 critical warning，bitstream 成功；因此它是
完整的 candidate evidence，仍不是 100 MHz milestone。路由最终没有残留 overlap，峰值为 42,344，
但 top-50 已转为 IQ 20 条（最差 `-0.816 ns`，平均 route 79.90%）、ROB/CSR 16 条（最差
`-0.587 ns`）和 LSQ 4 条（最差 `-0.639 ns`）。相对此前 default direct 的
`-0.527/+0.051 ns`，本批没有带来总体 setup 改善；这不能否定 RT22 的 ROB 局部 cell 减少，
但证明继续压缩该小范围 completion/commit 逻辑不足以闭合全核。

后续优先处理 IQ source-tag 到 queue payload 的跨区网络、LSQ completed-to-scheduled-load
网络和 frontend/translation 回授；ROB/PRF 则从 RT25 异常 sidecar、completion storage 组织与
多读端 PRF 的微架构级重构重新评估，不再默认追加局部布尔/选择树改写。`64 ROB / 64 PRF` 仍是
待验证的中间点，不预设为默认或回退；任何容量配置必须重新完成 matching software、Yosys 与
direct full。

### R14：main 里程碑选择性引入

`origin/main @ 8f33144` 已完成按当前实现边界的审计。当前分支已有 L02、L03、per-MSHR
victim ownership 和 system-operation 预解码；main 的 16-bit predictor 在现有 matched A/B 中
劣于当前 10-bit 配置，旧 ROB bypass 已由 RT26 覆盖，post-route `AggressiveExplore` 也不属于
正式 direct full 流程。因此不 merge 整个 main，只适配三项互相可隔离的 RTL 时序候选：

1. `EQ01 @ 03e8e25`：LUT-tree identity equality，覆盖前端、cache/L1I 与 predictor；
2. `MT20 @ 9f06f44`：scheduled Load 只在 ownership 变化时重捕获宽 payload；
3. `FT12 @ c9af20c`：在已有 response 边界注册 correction decision 与窄 drain 资格。

三项累积节点均完成受影响定向测试、完整 `cpu-check` 和 perf20；最终为 40 suites / 263 tests、
Python contracts 95/95，perf20 20/20 逐项精确等于 `3,879,728`，func58 random-AXI seeds
`240/255/141` 均为 58/58。最终发布 RTL SHA-256 为
`26009f15961b1f350baa639589353cb5ae851f5fc8bfda49a8b415dad0223ac8`。

Yosys 不给 EQ01/MT20 的物理收益背书：EQ01 全核 cells 增加 30，MT20 再增加 6；FT12 相对
MT20 将 OooFrontend 和全核 cells 各减少 8。它们的目标分别是 FPGA equality mapping、宽 CE/
fanout 和 correction 跨区控制，保留决策由 matching 100 MHz direct full 交叉验证。

该 direct full 已完成并归档为
`Post_Impl_Bundles/cpu_a8cd1c560d87_chiplab_c398d274812f_perf_100mhz_20260817-173219/`。
setup/hold WNS 为 `-0.673/+0.018 ns`、setup TNS 为 `-107.284 ns`，fully routed、DRC 0 error、
bitstream 成功；相对 `e04edc8` direct 的 setup WNS 改善 `0.143 ns`，TNS 改善约 `60.2 ns`。
Slice LUT `85,010 -> 85,775`，但实际 slice `26,800 -> 26,139`，最大局部拥挤
`97.0588% -> 91.4414%`，peak overlap `42,344 -> 44,182` 后归零。top-50 从 IQ 20、
ROB/CSR 16、cache/L2 6、frontend 3、predictor 1、LSQ 4，变为 IQ 35、predictor 9、LSQ 4、
cache/L2 2；对应最差路径中 LSQ `-0.639 -> -0.397 ns`、predictor `-0.544 -> -0.376 ns`、
cache/L2 `-0.686 -> -0.344 ns`，frontend 与 ROB/CSR 均退出 top-50。

这些组合变化与三项候选的目标一致，支持把 R14 作为后续软件稳定、物理表现更优的候选 baseline；
单次组合实现仍不能精确拆出每项的独立 WNS。setup 尚未闭合，所以 R14 不是 100 MHz 物理里程碑，
不得晋级正式产物。下一轮优先解决 IQ 35/50 的 source-tag 到 payload 跨区路径，同时保留三个
提交边界供后续隔离。

### R15：R14 top-50 定向时序批次

R15 沿用 default `32 ROB / 64 PRF` 和 R14 的软件周期基线，不恢复已经证明会放大拥挤的扩容
配置。本批根据 R14 direct full 的 top-50 一次纳入五项不增加可观察拍数的定向候选：

1. `IQT03 @ a211500`：只把 fixed/multiply 端口当拍接受的窄 `{valid, pDst}` token 用于
   select fast path，持久 source-ready 仍由 epoch-qualified completion wakeup 驱动；
2. `ICT02 @ 15e1ff3`：在 L1I controller 已保证 lookup/install 互斥的前提下，将 tag read CE
   与 install write enable 解耦；
3. `BPT04 @ 94d8317`：在既有 predictor response 边界注册 speculative RAS 操作，并对 pending
   push 提供 top bypass，避免把 BTB BRAM 输出直接送入 RAS payload CE；
4. `FT09 @ 51d8ed7`：复用已经形成的 canonical predicted successor，消除到 ATU 的重复
   taken/fallthrough 32-bit mux；
5. `MT21 @ fc91801`：用二进制 head 索引读取 scheduled Load payload，替代 16 项 one-hot
   bundle priority selection；legacy 路径仍由配置保留，便于隔离回归。

五项均有各自定向测试，最终完整 `cpu-check` 为 40 suites / 265 tests，Python contracts 95/95；
perf20 20/20 总周期为 `3,879,728`，20 项均与 R14 逐项精确相等；func58 random-AXI seeds
`240/255/141` 均为 58/58。source tree SHA-256 为
`8aae07fe6815f082d9e73a2faccf2b44aace14a62f1fea40f8c4b6449251ccf7`，发布 RTL SHA-256 为
`7773665e522dce787ea6bcaceeb4ba265e1c8a19211b10d7c9fef6e2cfc53d15`。实验身份冻结在
`build/reports/experiments/R15-five-timing-candidates/experiment-manifest.json`，perf A/B 证据为
`build/reports/comparisons/R14-vs-R15-five-timing.json`。

Yosys 相对 R14 的全核 cells 为 `57,233 -> 57,275`（`+0.073%`）、word bits 为
`391,587 -> 391,004`（`-0.149%`）、post-flat cells 为 `51,211 -> 51,253`
（`+0.082%`）；L1I local LTP 从 28 降至 27。该结果证明本批没有明显规模膨胀，但不能替代
跨区控制、RAM CE 和物理拥挤目标的 Vivado 判断。比较证据为
`build/reports/yosys/R14-vs-R15-five-timing-candidates.json`。

matching 100 MHz direct full 已完成并归档为
`Post_Impl_Bundles/cpu_fc91801aab52_chiplab_c398d274812f_perf_100mhz_20260817-190437/`。
setup/hold WNS 为 `-0.372/+0.050 ns`、setup TNS 为 `-39.042 ns`，fully routed、DRC
0 error / 0 critical warning、bitstream 成功。相对 R14，setup WNS 改善 `0.301 ns`、TNS 改善
约 `68.2 ns`；Slice LUT `85,919 -> 84,521`，FF `52,317 -> 52,335`。top-50 从 IQ 35、
predictor 9、LSQ 4、cache/L2 2，变为 IQ 6、predictor 0、LSQ 24、L1I-response/frontend 20；
IQ 最差 slack 从 `-0.673 -> -0.276 ns`，predictor 退出 top-50。该变化支持保留 R15 作为新的
开发 baseline，但不能把组合结果精确拆给单个候选。

route 最终无 failed net 或 overlap，peak overlap `43,599`，但方向性最大局部拥挤从 R14 的
`91.4414%` 升至 `99.0991%`。routed DCP 的 design-analysis 进一步显示两个短线热点分别由
LSQ 占 72% 和 58%，另一个前端热点由 frontend/L1I 占 82%/17%；ROB 仍占全局热点 37%，
却已退出 top-50。下一批因此优先重构 scheduled-load 捕获与 L1I response 到 frontend 的
预解码/学习链，并把 ROB 仅作为全局拥挤的结构背景处理。R15 setup 仍为负，不是 100 MHz
里程碑，也不晋级 `main`。

### R16：LSQ、L1I、frontend 与 ROB 的周期透明收敛批次

R16 保持 default `32 ROB / 64 PRF`，在 R15 的 routed DCP 上一次累积六项不改变可观察拍数的
候选：`LST01 @ 4e82e7c` 将 16 项旋转 pending-Load 选择改为 4x4 平衡树；`LST02 @ d9d55be`
把 pending map 注册为窄 sidecar；`ICT03 @ b31023c` 在既有 L1I response 边界捕获 predecode；
`FT13 @ 146d2da` 平衡 response lane count；`FT14 @ 451e2ba` 用局部最低索引选择
taken/learn lane；`RBT01 @ 591292e` 用 one-hot next-free state 局部化 ROB 分配状态。每项的定向
suite 通过；最终 `cpu-check` 为 40 suites / 265 tests，Python contracts 为 95/95。

clean ideal perf20 20/20 的总周期仍为 `3,879,728`，相对 R15 每一项精确相等；func58 random-AXI
seeds `240/255/141` 均通过。冻结身份为 source commit `591292e`、source tree SHA-256
`59cc0cdb47f31f26dbad8e4eee7b35c76bcb9a4c2eadf30a3b5c5cfb3dcb2285`、发布 RTL SHA-256
`39c840141836a8c50f6dd1c6c234730b01dd5b1970c6e3cf54047c24e44642c7`，实验清单为
`build/reports/experiments/R16-six-timing-candidates/experiment-manifest.json`。

Yosys 相对 R15 的全核 cells 为 `57,275 -> 57,882`（`+1.060%`）、word bits 为
`391,004 -> 397,735`（`+1.721%`）、post-flat cells 为 `51,253 -> 51,860`（`+1.184%`）。其中
ROB cells `-77`、LSQ `+49`、frontend `+27`，但 registered predecode 使 L1I `+608`；四个
目标模块的模块内 LTP 没有下降。因此 Yosys 只能说明没有 ROB/LSQ 的规模失控，不能证明该批
一定降低跨区路由压力。

matching 100 MHz direct full 已完成，归档为
`Post_Impl_Bundles/cpu_591292e46b08_chiplab_c398d274812f_perf_100mhz_20260817-202317/`。它是
从 matching RTL 直接完整 implementation 产生的 bitstream，fully routed，DRC 为 0 errors /
0 critical warnings，但 setup/hold WNS 为 `-0.334/+0.051 ns`、setup TNS 为 `-14.756 ns`，所以
只归档为 candidate，`competition_eligible=false`。相对 R15，setup WNS 改善 `0.038 ns`、TNS
改善 `24.286 ns`，不足以作为 100 MHz 闭合里程碑，也不进入 `main`。

R16 的 top-50 从 R15 的 LSQ 24、frontend/L1I 20 变为 IQ 28、ROB/CSR 11、cache/L2 10、LSQ 1；
frontend/predictor 均为 0。最差六条均为 `LSQ bankedForwardCompletionValid -> IQ issue payload`
（route `79.8%` 至 `82.0%`，logic 约 2 ns），L1D `waiterBeatReady -> response_data` 的 route 为
约 `83%` 至 `85%`，ROB `candidatePointer -> privilegedRedirect{Target,Pending}` 为约 `72%` 至 `85%`。
路由 peak overlap 为 `38,792`、最终 0；DCP 的 East global congestion 为 119%，其中 ROB 87%、
TLB 8%。R17 因而优先选择 IQ/LSQ forwarding 边界局部化、ROB redirect payload 局部化、L1D
response data/control 局部化、shared-MSHR response identity/ADDRD 局部化，以及 ROB 的
allocation/completion high-fanout 结构性拆分。以上均是候选，不把工具未能自动复制的 net 当作
应由 `max_fanout` 约束解决的问题。

本轮也重新审计了 `origin/main`：`06ca492` 的 registered correction decision 已由本分支
`c9af20c` 覆盖，`c60dadd` 的四处 LUT-tree equality 已由 `03e8e25` 覆盖；最新 `35c7f5d` 是
custom-instruction 框架，不是独立时序候选且会扩大 ISA/验证面，故不引入。主线 `06ca492` 的
正 WNS 使用了 route 后 `AggressiveExplore phys_opt`，仅可作探索信息，不取代本仓库的 direct
full 里程碑。

### R17：R16 路径定向与 main 候选筛选批次

R17 继续使用 default `32 ROB / 64 PRF`。对 `origin/main @ 67d5cfb` 的 RTL 提交逐项复核后，
L02/L03、per-MSHR victim ownership、system-operation 预解码、scheduled-load recapture、
registered correction 和 LUT-tree equality 均已被当前实现覆盖；旧 16-bit predictor/reset sweep
弱于当前 B02-F 10-bit row-valid 方案，custom-instruction 框架也不属于本轮时序目标。main 的
`12c3597` 包含一个 RT26 尚未覆盖的有效增量：保留当前 balanced highest-lane payload select，
但让 branch-head bypass 的宽 payload 每拍更新，只以 valid 资格化可见性，从而移除 head/lane
比较器到宽寄存器 CE 的路径。该增量已选择性适配为 `RT28 @ f8e3a31`；没有整分支 merge。

最终进入实现的五项候选为：`IQT04 @ 1afc5b0` 延迟 banked Store-forward Load 的 IQ fast wake，
`L1DT04 @ 04869ad` 在 L1D 内注册 refill-waiter response，`RT27 @ e403cc9` 禁止该 banked forwarding
completion 进入宽 ROB-head same-cycle bypass，`RT28 @ f8e3a31` 去除 branch-head bypass payload
CE，以及 `RF08 @ 2d19091` 删除 PRF 中重复的 per-bank write-data gate。两项尝试没有保留：
`RBT02 @ 1fc46fa` 实际多加了一拍 privileged redirect，在 func58 稳定产生 `0x3a00003c/LED2`，
由 `03a0d90` 撤回；`RF09 @ 10b7e36` 把 PRF bank storage 改为 async-read memory 后也产生确定性
func58 错误，且多写/读写冲突语义不足以由原测试证明，由 `a7059b4` 撤回。撤回后的 seed 240
复核通过，最终组合的完整 `cpu-check` 为 40 suites / 265 tests，Python contracts 95/95；
func58 random-AXI seeds `240/255/141` 均退出码 0。

最终 clean ideal perf20 为 20/20，`3,879,728 -> 3,896,626` cycles，总周期回退
`0.435546%`，几何平均回退 `0.223161%`，仍处在时序轮允许的平均 `<0.5%` 边界内。主要回退为
`coremark +2.494%`、`loop_induction +1.524%` 和 `bitcount +1.297%`；改善为
`stream_copy -2.176%`、`minmax_sequence -0.619%`。该代价必须与 matching WNS 交叉判断，
不能包装成周期透明。比较证据为
`build/reports/comparisons/R16-vs-R17-five-timing.json`。

R17 source commit 为 `2d19091`，source tree SHA-256 为
`6a21a0d787cc63ac8cbeb75e431c65cc4a8cfc124cd44106ca414eceb1c4a062`，发布 RTL SHA-256 为
`36890b1f77853df90c9b890333cc0691c3932a61dc31cbceae66261d8c5b954d`。Yosys 相对 R16 的
全核 cells 为 `57,882 -> 57,850`（`-0.055%`）、word bits 为 `397,735 -> 397,114`
（`-0.156%`）、post-flat cells 为 `51,860 -> 51,823`（`-0.071%`）；PRF `-20`、L1D
`-14`、ROB `+5` cells。结构代理排除了明显规模膨胀，但是否足以换回至少 `0.435546%` 的周期
代价，只能由本身份的 100 MHz matching direct full 判断；R16 的 WNS 不继承。

R17 matching direct full 已完成并归档为
`Post_Impl_Bundles/cpu_7d54739e354c_chiplab_c398d274812f_perf_100mhz_20260817-232122/`。
该 run fully routed、failed/unrouted nets 为 0、最终 overlap 为 0、DRC errors 为 0、critical
warnings 为 0，并成功写出 bitstream；DRC 另有 20 条平台/工具 warning。正式 clock validation
为 setup/hold WNS `-0.230/+0.023 ns`、setup TNS `-12.471 ns`，所以 R17 仍是 candidate，
不满足正 WNS milestone，也不进入 `main`。相对 R16 的 setup `-0.334 ns`，改善约 `0.104 ns`；
该改善不能单独归因给五项中的任何一项。

R17 top-50 统计为 LSQ 37、ROB/CSR 9、IQ 2、predictor 2，frontend 和 cache/L2 均为 0；
最差路径来自 LSQ `registeredPendingLoads -> scheduledLoadPayload_pdst` 的宽 CE 控制，
最高扇出约 48,209，路径 slack 约 `-0.230 ns`。route health 的最大方向性拥挤为 North
`92.7928%`（East `86.1673%`、South `88.7387%`、West `88.6948%`），比 R16 观察到的
局部 99% 峰值下降，但 LSQ CE 仍是下一轮首要结构性目标。top-50 与 route health 分别见
`build/reports/timing/R17-five-timing-direct-top50.json`、归档内 `route-health.json` 和
`timing_summary.rpt`。

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
实验因此保留为性能候选但默认关闭。本轮已与周期透明的 `RT04/RT05/RT06`
结构优化合并；在 matching 100 MHz direct implementation 得到正 WNS 前不得进入稳定组合。
根入口使用 `make cpu-generate CPU_VARIANT=expanded-window` 显式生成该变体；省略参数时
始终生成 `default`，不依赖容器环境中的隐式开关。

### L15：Store 容量独立 A/B

M05 v12 中 `stream_copy`、`stringsearch`、`bubble_sort` 和 `dhrystone` 的 SQ-full 比例较高，
因此用 opt-in `CPU_VARIANT=expanded-stores` 将 STQ/SDQ 从 8 项同时扩到 16 项；默认配置
保持不变。定向容量合同、完整 `cpu-check`（40 suites/247 tests、82 项 Python 合同）与
clean perf20 20/20 均通过。

相对 A01，周期仅从 `4,215,442` 降到 `4,211,623`，总周期改善 `0.090595%`，几何平均
加速 `1.002099817x`。Yosys generic cells 从 `72,059` 增至 `76,637`（`+6.353%`），
新增部分精确集中在 `LoadStoreQueue +3,041` 和 `StoreDataQueue +1,537`；SDQ 的 raw LTP
还从 23 增至 31。该收益/压力比明显不足，L15 被否决，不进入组合 perf20 或 Vivado；
opt-in 变体只用于复现实验。周期比较见 `build/reports/comparisons/R8-L15-expanded-stores.json`，
结构比较见 `build/reports/yosys/R8-default-vs-L15-expanded-stores-v1.json`。

### R8 Yosys 结构分析基线

新增的 Yosys harness 直接读取显式冻结 RTL，不依赖 `cpu-generate`，约 43--50 秒完成一份
层次实例加权 generic-cell、按操作位宽归一化的 word-bits 与关键模块/全核 raw LTP 报告。
raw cell 对象数保留用于识别结构展开方式，word-bits 用于避免把一个 128-bit 向量寄存器
误记成比 128 个 1-bit 寄存器小 128 倍；两者仍都不是 LUT/FF。default、R02 和 L15 的真实
三点校准分别复现了 `72,059`、`89,083`、`76,637` cells；R02 的增量被拆到 ROB、
RenameMap、PRF 和 FreeList，L15 的增量被拆到 LSQ/SDQ。三者全核 raw LTP 都为 104，
说明结构规模、局部组合深度和器件实现时序必须分别判断。

完整 `synth_xilinx` 校准在 5 分 39 秒时仍处于资源共享/techmap，峰值约 4.0 GiB，且日志
超过 400 MiB，因此不进入常规门禁。日常 Yosys 报告用于候选实现前的压力定位和候选间
配对比较；100 MHz 晋级仍只接受 matching Vivado direct full implementation。

### RT04：ROB 不可变退休 metadata 分 bank

R02 的结构增量主要集中于 ROB。RT04 将只在 allocate 时写入的退休 metadata 从逐 entry
寄存阵列迁入四个窄同步 bank，并复用既有 commit prefetch 指针；三路连续 allocate/commit
指针占用不同低两位 bank，因此不增加读写端口或提交拍数。实现同时删除 payload 中重复的
PC/system-operation，未保留会产生未驱动副本的生产 RTL A/B 路径；旧 baseline 已由冻结 RTL
和 Yosys 报告承担对照。

`ecfcf66` 的 ROB 17 项与完整 `cpu-check` 通过，门禁为 40 suites / 247 tests、Verilator
strict-zero lint 和 87 项 Python 合同。发布 RTL SHA-256 为
`1741aff8ec796a3923245ae472e974a145935a393e37a78d01f464e842dd785a`。完整 perf20 相对 A01
为 `4,215,442 -> 4,215,442`，20 项逐项精确相等。Yosys generic cells 为
`72,059 -> 69,148`（`-4.040%`），ROB 贡献从 `13,605` 降到 `10,694`；memory bits 只增加
448。ROB/full-core raw LTP 仍为 `38/104`，所以当前只确认实现压力下降，时序收益留给后续
与 R02 及其他独立候选组合的 matching direct full 判断。周期与结构证据分别见
`build/reports/comparisons/R8-RT04-vs-A01.json` 和
`build/reports/yosys/R8-default-vs-RT04-default-v2.json`。

### RT05：RenameMap physical-ready 掩码化

RT05 将每个 physical-ready bit 上重复的三路 allocation compare 和五路 writeback compare
改为每个输入端口一次 one-hot decode，再用平衡 OR 树形成两个宽 mask。状态更新保持
`(ready | completed) & ~allocated`，因此 allocation 在同拍碰撞时仍优先，flush 仍恢复全部
ready，p0 始终为 ready；该结构不增加 rename/source-ready 的架构拍数。

`c9a2104` 的默认与 128-PRF 定向测试、完整 `cpu-check` 均通过，门禁为 40 suites / 249
tests、Verilator strict-zero lint 和 88 项 Python 合同；发布 RTL SHA-256 为
`a1359c2771276739c30b3aa9932bdb5fd1f594e927fe80c811dc9db41cf29626`。相对 RT04 的完整
perf20 为 `4,215,442 -> 4,215,442`，20 项逐项精确相等。位宽归一化后，default 全核
word-bits `479,449 -> 474,011`（`-1.134%`），RenameMap `13,723 -> 8,285`
（`-39.627%`）；R02 配置下全核 `596,607 -> 583,088`（`-2.266%`），RenameMap
`23,878 -> 11,094`（`-53.539%`）。RenameMap raw LTP `14 -> 13`，全核仍为 104。
周期与结构证据分别见 `build/reports/comparisons/R8-RT05-vs-RT04.json`、
`build/reports/yosys/R8-RT04-vs-RT05-default-word-v1.json` 和
`build/reports/yosys/R8-RT04-vs-RT05-R02-expanded-word-v1.json`。

### RT06：FreeList 四 bank 存储

RT06 利用三宽连续分配与回收不会在同拍命中相同低两位 bank 的性质，把 64/128 项循环
FreeList 拆成四个单读单写寄存器 bank。allocate 输出仍是组合可见，release、pointer wrap、
architectural snapshot 和 flush 均保持原边沿；默认与 expanded-window 测试各运行两倍物理
寄存器数的 sparse mask、同拍 recycle 和多次 wrap，并继续复用原有 flush/commit 合同。

`563633f` 的 RegisterStructures suite 12/12、完整 `cpu-check`、strict-zero lint 和合同门禁
通过；发布 RTL SHA-256 为
`6b8c6bbdd52f2e791240a1186bbc13ff83dfe114b8ca26ff96a91f26aa3e4f80`。相对 RT05 的
perf20 为 `4,215,442 -> 4,215,442`，20 项逐项精确相等。default FreeList word-bits
`4,739 -> 2,399`（`-49.378%`），全核 `474,011 -> 471,671`（`-0.494%`）；R02
配置下 FreeList `10,595 -> 4,954`（`-53.242%`），全核 `583,088 -> 577,447`
（`-0.967%`）。局部 raw LTP 因 bank write 选择从 10 增至 12，全核仍为 104；因此该项
不能仅凭面积代理晋级，必须在 R02 组合 matching direct full 中检查 FreeList/rename 路径。
证据见 `build/reports/comparisons/R8-RT06-vs-RT05.json`、
`build/reports/yosys/R8-RT05-vs-RT06-default-word-v1.json` 和
`build/reports/yosys/R8-RT05-vs-RT06-R02-expanded-word-v1.json`。

### R02 + RT04/RT05/RT06 组合软件门禁

`RT04/RT05/RT06` 在 `CPU_VARIANT=expanded-window` 下相对原 R02 的完整 perf20
20 项逐项精确相等，总周期仍为 `4,167,970`；相对当前 default 组合仍为
`4,215,442 -> 4,167,970`（`-1.126145%`），归一化几何平均加速
`1.015314274x`。这证明三个结构候选没有改变 R02 的软件周期收益。配对证据分别为
`build/reports/comparisons/R8-R02-RT04-RT05-RT06-vs-R02.json` 和
`build/reports/comparisons/R8-R02-RT04-RT05-RT06-vs-default.json`。

matching func58 random-AXI seeds `240/255/141` 均为 `58/58`。matrix 为
`build/sim/runs/cpu_4b1a4e012e39_chiplab_c398d274812f/clean-func58_model_77c1eb518231_software_3fe689f227db/random/matrix_2395d770132d_func58.csv`；
组合 perf20 matrix 为
`build/sim/runs/cpu_4b1a4e012e39_chiplab_c398d274812f/clean-perf20_model_a8739c977aba_software_f6e7c20f71a4/ideal/matrix_52d9676ce812_perf20.csv`。
expanded-window locked gates 与实验冻结均通过。matching 100 MHz direct full 已完成：
fully routed、bitstream 成功、DRC 0 error/critical warning，setup/hold WNS 为
`-0.355/+0.046 ns`，因此归档为 candidate，尚未形成里程碑。placed utilization 为
`112,618 LUT`（`84.17%`）、`62,689 FF`、`64.5 BRAM tile`、`8 DSP`；其中 CPU 为
`100,221 LUT`，ROB 单模块占 `38,098 LUT/8,974 FF/8 BRAM`，PRF 占 `8,800 LUT/4,064 FF`。

top-50 中 `48/50` 属于 ROB/CSR，平均 route delay 占 `82.99%`、最差 slack
`-0.355 ns`；其源端集中于 LSQ `drainAfterFlush`，终点集中于 ROB entry 的
`sideEffectData` CE、完整 pointer CE 和 completion-exception 状态。IQ 与 L1D 各占一条。
这证明当前主要矛盾是 64-entry ROB 宽多写状态造成的布局布线压力，不是 RT06 FreeList
局部 LTP。下一批按 `RT07 -> RT08 -> RT09` 线性验证：压缩 resident generation tag、共享
completion one-hot decode，并把冷 completion payload 迁入按生产端口组织的同步存储。
实现归档为
`Post_Impl_Bundles/cpu_04d25fdef318_chiplab_c398d274812f_perf_100mhz_20260816-171551/manifest.json`，
路径分类为 `build/reports/timing/R8-R02-RT04-RT05-RT06-direct-top50.json`。

### R8 第二批结构降压：RT07/RT08/RT09/DT01/RF01

第二批不再为每个周期透明候选重复完整 perf20；定向测试与 Yosys 配对保持逐提交，完整
`cpu-check`、perf20、func58 和 direct full 只对最终组合执行。若组合出现明显周期退化，
再按提交边界二分归因。当前五项均不增加架构拍数：

- `RT07 @ f6d705d` 用物理 entry index 隐含 ROB index，resident state 只保存一位 wrap
  generation。expanded-window 全核 Yosys cells `77,557 -> 77,365`，word-bits
  `577,447 -> 573,568`；ROB wrap/epoch 定向测试通过。
- `RT08 @ 82bbd93` 将五路 completion 与窄 Store completion 的 ROB index 各解码一次，
  entry 侧只做 one-hot 资格化。全核 cells `77,365 -> 76,231`，word-bits
  `573,568 -> 571,270`，ROB cells `20,038 -> 18,904`。
- `RT09 @ 58fdd42` 把 `result/sideEffect/exception/branch` 冷 completion union 迁入按
  producer lane 组织、按三路 commit 复制的同步存储；精确 applied-completion 旁路定义
  RAM 同址读写。ROB 19/19，新增测试覆盖三 producer 同拍三退休和完整异常 payload。
  全核 cells `76,231 -> 68,557`（`-10.067%`）、word-bits
  `571,270 -> 473,298`（`-17.150%`）；ROB cells `18,904 -> 11,230`
  （`-40.595%`）、word-bits `136,918 -> 38,946`（`-71.555%`），memory bits
  `11,840 -> 91,520`。全核 raw LTP 保持 104，ROB 局部为 `38 -> 39`。
- `DT01 @ 8943b2e` 将 8-entry DispatchQueue payload 按低两位拆成四个 bank，连续
  三宽读写同拍不会 bank collision。ring-wrap 定向测试通过；DispatchQueue cells
  `5,918 -> 4,027`、compare `1,121 -> 404`，全核 cells 再降 `2.758%`。
- `RF01 @ ab68ee1` 对五个 PRF 写地址各做一次 one-hot decode，再局部更新 128 项寄存器。
  RegisterStructures 12/12；PRF cells `2,819 -> 2,178`、mux `1,344 -> 703`，局部 raw
  LTP `12 -> 8`，全核 cells 再降 `0.962%`、word-bits 降 `4.324%`。

组合完整门禁已通过：`40 suites / 253 tests`、88 项 Python 合同、expanded-window
strict-zero Verilator/Yosys gate。clean perf20 20/20，相对第一批 R02+RT04/05/06 为
`4,167,970 -> 4,167,970`，20 项逐项精确相等；func58 random-AXI seeds
`240/255/141` 均为 `58/58`。组合周期比较见
`build/reports/comparisons/R8-RT07-RT08-RT09-DT01-RF01-vs-R02-RT04-RT05-RT06.json`，
matching perf20/func58 matrix 分别位于
`build/sim/runs/cpu_d67deb53cfbe_chiplab_c398d274812f/clean-perf20_model_1b1679e77d0f_software_f6e7c20f71a4/ideal/matrix_52d9676ce812_perf20.csv`
和
`build/sim/runs/cpu_d67deb53cfbe_chiplab_c398d274812f/clean-func58_model_6036030ffdff_software_3fe689f227db/random/matrix_2395d770132d_func58.csv`。

升级后的 Yosys v2 同时记录 hierarchy 与整核 flatten/`opt_clean` 后统计，并在局部 LTP
前展开目标模块，避免子层级端口反馈产生假组合环；每个 LTP 原始文件有 8 MiB 硬上限。
相对第一批，hierarchy cells `77,557 -> 66,025`（`-14.869%`）、word-bits
`577,447 -> 443,205`（`-23.248%`）；post-flatten cells `70,744 -> 59,948`
（`-15.261%`）、word-bits `545,122 -> 414,215`（`-24.014%`）。ROB、DispatchQueue、
PRF 的 hierarchy cells 分别减少 `9,000/1,891/641`；全核 LTP 仍为 104，PRF 为
`12 -> 8`，ROB 为 `38 -> 39`。报告见
`build/reports/yosys/R8-R02-RT04-RT05-RT06-v2/`、
`build/reports/yosys/R8-RT07-RT08-RT09-DT01-RF01-v2/` 和
`build/reports/yosys/R8-second-timing-batch-v2.json`。这些数字仍是结构代理，不能替代
matching Vivado。

第二批 matching 100 MHz direct full 已在 `a3f03ca` 完成。实现 fully routed、bitstream
成功、DRC 0 error/critical warning，setup/hold WNS 为 `-1.850/+0.050 ns`，因此只归档为
candidate，未形成里程碑。placed utilization 为 `94,023 LUT/55,372 FF/84 BRAM tile/8 DSP`；
相对第一批减少 `18,595 LUT`（`-16.51%`）和 `7,317 FF`（`-11.67%`），但增加
`19.5 BRAM tile`。这证明 Yosys 结构降压确实进入了 Vivado 网表，同时也证明资源类型和
连接拓扑发生了不健康的迁移，不能把总 cell 下降直接等同于物理收益。

route 阶段峰值为 `117,377` 个 overlap，出现 2 次 Route 35-447 拥塞警告和 7 个含
overlap 的 Global Iteration；`route_design` 用时 `6,429 s`（`1h47m09s`）。最终 net
全部布通、overlap 为 0，但方向热点峰值达到 South `94.6509%`、West `91.9060%`、
East `91.2684%`。top-50 为 ROB/CSR `45`、IQ `4`、LSQ `1`；最差路径是
`ROB stagedResult -> PRF register D`，slack `-1.850 ns`、route delay 占 `95.008%`，
部分 write-data net fanout 为 `138`。旧 LSQ-drain 到 ROB 宽 entry payload CE 路径已消失，
但新 PRF 数据广播和 completion 存储拓扑成为更严重的物理墙。

该结果的归因边界是五项组合：不能把全部退化独立归给 RT09 或 RF01。RT07/RT08/DT01
的目标路径未在新 top-50 重现，可继续保留；RT09 的 `15 x (64 x 83)` 同步存储使 BRAM
增加，RF01 的全表 one-hot 写法让每路 result 广播到 128 项 PRF，二者进入下一批替代设计。
`RT10` 将每个 producer 的 completion payload 按 ROB pointer 低两位拆为四个 16-entry
bank，三路连续 commit 天然访问不同 bank，以 4 个浅 bank 代替 3 个全深度副本；`RF02`
按 PRF bank 先局部资格化 write data，使原始数据只驱动少量 bank gate，再由 bank-local
net 更新 32 项寄存器；`MT10` 对 LQ/SQ rename allocation 只做一次 one-hot target decode，
并以四份 bank-local payload 替代全队列动态写广播。三项均保持原有可见拍数，先做定向/
完整门禁、Yosys 和组合 perf20，再与本轮 `route-health.json` 做 matching direct
full A/B。实现归档、路径和拥塞证据分别见
`Post_Impl_Bundles/cpu_a3f03ca9c5b6_chiplab_c398d274812f_perf_100mhz_20260816-202318/manifest.json`、
`build/reports/timing/R8-second-timing-batch-direct-top50.json` 和
`build/reports/timing/R8-second-timing-batch-route-health.json`。

第三批结构降压的 matching RTL 身份冻结在 `4890747`，发布 RTL SHA256 为
`8a6a74e1608a06582eae7928d641800e41b9bd8f71edc495c8901548c03db066`。`RT10` 将
ROB completion 存储从 15 个全深度副本改为每 producer 四个浅 bank，使 completion
memory bits 约 `79,680 -> 26,560`；`RF02` 将五路 PRF write data 各自限制在四个 bank gate
和 32-entry bank-local net；`MT10` 进一步让 LSQ cells `8,031 -> 7,470`（`-6.985%`）。
完整门禁为 41 suites/256 tests、Python 合同 92/92；clean perf20 相对第二批 20 项逐项
精确相等，均为 `4,167,970` cycles，func58 random-AXI seeds `240/255/141` 均为 58/58。
证据冻结在
`build/reports/experiments/R8-RT10-RF02-MT10-direct-100mhz/experiment-manifest.json`。

本轮物理比较不能只看 WNS。第二批的 `117,377` peak overlaps、2 次拥塞警告、7 个含
overlap 的 Global Iteration、`6,429 s` route 用时和 `94.6509%` 方向最大拥塞共同构成
健康基线。第三批必须同时报告这些指标；即使 setup 改善，overlap、拥塞警告或 route 用时
没有显著下降，也只能说明关键路径被转移，不能证明全局布局布线压力已经解决。若第三批仍
严重拥塞，下一轮优先从 matching high-fanout 与跨区 net 抽取新的 bank-local/owner-local
RTL 拓扑候选，不使用 `max_fanout`、Pblock 等启发式指令掩盖结构性广播。

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
