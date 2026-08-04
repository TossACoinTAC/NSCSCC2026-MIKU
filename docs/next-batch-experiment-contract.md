# Next Batch Experiment Contract

## Baseline Identity

所有 A/B 结果必须分别绑定明确身份；当前冻结基线为：

- CPU：`dev/ECHO @ 758181a01c5bb53156157bc7946bc37d6057f3ec`
- published RTL：`04d6e4b2a36371d0f65a4652e0ec93cd84405e52c20a830c0c13e3b77afa8cfc`
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`
- implementation archive：`Stable_Backup/cpu_758181a01c5b_chiplab_c398d274812f_perf_100mhz_20260805-021058_candidate/`

Linux instrumented random-AXI 的三个 200 ms seed 已通过，perf20 的 19 个短项目和
func58 三个固定 seed 已通过。本轮没有补跑 matching `stringsearch` 软件仿真，因此它不是
此 milestone 的本地 20/20 证据。

本轮 actual CPU/system/DDR clocks 为 `100/100/200 MHz`，setup WNS/TNS 为
`+0.041 ns/0`，hold WHS/THS 为 `+0.050 ns/0`，DRC 0 error，bitstream 成功。placed
full-SoC 资源为 LUT `89,972`、FF `53,724`、BRAM tile `68.5`、DSP `8`。最差两条不同
endpoint 同属 ROB `stagedPdst_2` 到 IQ0 queue 6 early-wakeup/ready clock-enable 路径族；
第一条不同路径族为 LSQ scheduled-load physical address 路径，slack `+0.164 ns`，与 WNS
只差 `0.123 ns`。这些是下一批的 matching baseline，RTL 变化后必须重新测量。

团队板 perf20 job `20260804-182327-8f1c8193` 在 matching 100 MHz performance
bitstream 上通过 20/20，总 SoC/CPU cycles 为 `69,476,960/69,466,027`。同一开发板、
同一 c398 platform 与 100 MHz profile 的基线 job `20260803-220447-d9b5b478` 为
`79,537,915/79,524,833`，因此总 SoC/CPU cycles 分别下降 `10,060,955/10,058,806`，
约 `12.65%`；20 项中 19 项改善，test 4 约退化 `0.14%`。该结果证明当前组合整体有实际
板上性能提升，不能用于拆分 R01、LSQ cut 和 cache 扩容的单项贡献。随后已把同一 CPU/RTL
提交到官方仓库同名分支 `dev/ECHO @ 0a8ef8caf08c`。官方父流水线 `2185` 与子流水线
`2192` 均成功：function `58/58`、三个 seed 全过；performance `20/20`，IPC ratio
`0.858661920`，100 MHz performance build setup/hold WNS 为 `+0.011/+0.050 ns`，function
build 为 `+0.978/+0.050 ns`，两种模式均为 0 DRC error 并成功生成 bitstream。该 official CI
证据只绑定 submission `0a8ef8c`、CPU/RTL milestone `758181a/04d6e4b...`，不能继承给
后续 L05/W02。原始 metadata、trace、artifact 与完整哈希归档在
`build/official-ci/pipeline_2185_child_2192_commit_0a8ef8ca/`。

作为不改变官方固定矩阵的额外软件置信证据，当前 prepared model 又以 seed
`17/65537/99991` 通过 func58 `58/58`，`num_data=0x3a00003a`、LED `1/1`，endpoint
`0x1c00020c`，整段 cycles 为 `642766/642331/642589`；三份 verdict 和 DiffTest 均正常。

## Candidate Cards

| ID | 假设与触发信号 | 首次实现边界 | 正确性门禁 | 进入合并实现的条件 |
| --- | --- | --- | --- | --- |
| L04（已停止） | Store translation 只在 `!loadNeedsTranslation` 时 lookahead；当前 Linux 三 seed 的 head Store translation 暴露约 `9.718M--9.765M` cycles，head request/response fire 各约 `4.66M--4.71M` | 只改变 Load/Store translation owner 的 age-aware 选择；保留单 owner、cancel、SC 和 exception 身份，不增加第二 walker | C04/C06 相关 LSQ/TLB/flush/uncached 回归；func58 固定三 seed并保留额外三 seed；Linux instrumented 三 seed；代表 perf20 | 被移出 head 的 Store translation 周期增加，且 paired cycles 不退化；被推迟 Load 的额外等待单独报告；新 route 不使当前关键路径恶化到不可接受 |
| W01（软件 A/B 通过） | v5 observer 在四个代表项的 `43,190,065` 个观测周期中发现 `484,314` 个冲突且存在等待消费者的周期，占 `1.1214%` | 对已经成功 direct wakeup 的同 lane completion 回声做有界抑制；保留 registered/direct 仲裁优先级和原有 wakeup lane 数 | producer acceptance、flush、exactly-once wakeup、长依赖链与完整后端回归；func58/Linux/代表 perf20 | 19 项 paired cycles 有稳定正收益且无功能失败；不得用新增 wakeup lane 放大全局比较网 |
| E02（软件 A/B 通过） | v5 observer 对四个代表项只统计 exact head/current-epoch staged completion，严格上界为 `7,454,851 / 43,190,065 = 17.2606%` | 仅允许普通 head 指令使用 matching staged completion 提前退休；年轻 lane 保持 `entry.complete` prefix，branch、exception、serializing 和 system operation 排除 | ROB flush/epoch/wrap、精确异常、三宽能力和所有排除边界；func58/Linux/代表 perf20 | head-only bubble 的 paired cycles 明确下降，且不增加 staged wakeup 扇出；完整 SoC 的 cycle×frequency 不劣于基线 |
| L05（软件 A/B 通过） | 四个代表项 `5,194,524` 次 D-side translation 中只有 5 次走 TLB；direct/DMW 往返事件数相当于观测周期的 `12.0803%` | `2894e04` 在已有 `scheduledLoad`/Store entry 寄存边界填入动态 PA/MAT/translationDone；不把 AGU 组合地址直接接入 L1D | ATU `6/6`、LSQ `32/32`、core `14/14`、integration `4/4`；19 项软件、组合完整 gates 与 func58 全过 | `71,083,365 -> 62,580,196`（`-11.962249%`）；保留并进入 Linux/时序门禁 |
| W02（软件 A/B 通过） | `3,517,657` 次合格 Load completion 中，`2,922,347` 次在 P0-P2 已有等待 consumer，占 completion 的 `83.0765%` | `f2dfd1e` 注册 LSQ completion 资格，复用 P3 wakeup lane 与 PRF write-through；backend 检查 current epoch，不增加广播 lane | cache/forward、exception、flush/epoch、LQ reuse 和实际 operand data；LSQ `32/32`、backend `15/15`；组合完整 gates 与 func58 通过 | 相对 L05 `62,580,196 -> 61,817,068`（约 `-1.219%`）；18 项改善、1 项持平，进入 Linux/时序门禁 |
| D01 | P3 `portReady` 当前对 Load/Store 都要求 IQ 与 SDQ ready；SDQ 满时 Load 可能被无关阻塞 | 第一阶段只增加 `SDQ-full && P3 load candidate` 计数，不改变 router；第二阶段再按 Load/Store 类型拆分 ready，Store 仍保持 IQ+SDQ 原子接受 | Router prefix、Store 双队列原子性、flush/backpressure、LSQ/SDQ 定向和随机 AXI；func58/Linux/代表 perf20 | 计数确认有可观暴露且 paired cycles 有收益；否则不进入 RTL。新 ready 网络不得形成组合环或显著恶化 route |

每项都先独立提交、独立运行最小相关测试和代表 perf20；通过后才允许把多项放入一个组合
candidate。组合 candidate 只做一次完整 gates、func58、Linux、短 perf20 和 matching Vivado。

## Measurement Contract

每个候选必须保存：CPU/RTL/Chiplab hash、seed、模型 hash、每项 CPU cycles、verdict、
DiffTest 状态、M01 counter hash，以及 implementation 的 requested/actual clocks、WNS/TNS、
WHS/THS、DRC、资源和 top-N path。计数器的 opportunity 是上界，不直接等价于可消除 cycles。

`E02` 不能把所有 `rob_staged_completion` 都算成收益：只统计 head-only、当前 epoch、exact
pointer 的周期，并区分 ALU、Load、Store、DIV、Branch 和 serializing。`L04` 不能把 Store
request/response fire 数相加作为收益：必须报告 Load 被推迟、ATU owner 占用和总 ROI cycles。
`D01` 的计数必须证明 Load candidate 确实因 SDQ 而不是 IQ、ROB、flush 或 FU 能力被阻塞。

## Route-Driven Order

1. L04 的可关闭 age-aware 仲裁完成 32/32 LSQ 定向测试与 19 项 paired perf20；总收益
   只有约 `0.054%` 且 4 项退化。实验实现已从当前源码移除，不进入后续门禁或
   implementation。
2. D01 已增加严格下界 observer：只统计最老 dispatch lane 为 Load、IQ3 已 ready、但 P3
   仅因 SDQ not-ready 被阻塞的周期，不改 production RTL。先在代表 perf20 上测暴露；不足时
   直接停止 D01。
3. v5 observer 已按 exact head/current epoch 精化 E02，并确认 W01 冲突中存在等待消费者。
   W01 与 E02 分别完成独立 RTL、定向测试和 19 项 paired perf20；二者均保留进入组合候选。
4. L05/W02 已按 observer 排序完成独立 RTL 与 paired A/B。L05 19 项全部改善并降低
   `11.962249%`；W02 在其上再降低约 `1.219%`，18 项改善、1 项持平。二者进入同一组合
   correctness/timing gate，归因仍由独立提交和两组 CSV 保留。
5. 只有具有独立、可复现正收益的候选才进入下一次合并 implementation；不为
   observer-only、已停止候选或负收益消融单独支付一次完整 Vivado。

## First Measurements

L04 实验保存于独立 CPU commit `c30fc470de4a`。完整
`OooLoadStoreQueueSpec` 通过 32/32，覆盖 legacy policy、Store 较老、Load 较老以及 ROB
pointer `63 -> 0` wrap-around。以 `758181a` 冻结的同 observer 模型为 paired baseline，
候选模型使用相同 perf20 软件、ideal memory 与 seed 0。19 个短项目均通过：7 项改善、8 项
不变、4 项退化，总 cycles 从 `71,588,939` 降到 `71,550,342`，减少 `38,597`，约
`0.054%`。主要收益集中在 `inner_product`（`-0.352%`），而 `lookup_table` 退化
`0.254%`；收益既小又不普遍。L04 因此停止在软件 A/B 阶段，不进入完整 gates、Linux 或
Vivado。commit `2765433e82e0` 已移除实验实现，重新生成的 published RTL 哈希精确恢复为
冻结基线 `04d6e4b2...`；实验内容仍可从 `c30fc47` 复现。

D01 observer 在 `stream_copy/coremark/inner_product/quick_sort` 的观测窗口分别为
`3,518,122/17,397,834/14,147,230/6,438,470` cycles，最老 Load 仅被 SDQ not-ready
阻塞的周期分别为 `0/5/0/22`。合计 `27 / 41,501,656`，约 `0.65 ppm`；即使全部消除，
周期收益上界也不可观。D01 因此停止在 observer 阶段，不修改 dispatch ready 网络，不进入
下一次 implementation。

M01 v5 observer 固定于 workspace commit `babeac9e4c63`，在冻结 RTL
`2765433e82e0` 上运行 `coremark/inner_product/lookup_table/quick_sort`。四项合计
`43,190,065` 个观测周期，所有 retire、source alignment、queue identity 和 sampling
守恒式通过。E02 exact-head staged completion 合计 `7,454,851` cycles，严格理论上界为
`17.2606%`；这是“一拍全部可消除”的上界，不是收益预测。W01 registered/direct tag 冲突且
IQ 中确有等待 direct tag 消费者的周期合计 `484,314`，占 `1.1214%`；该证据足以启动有界
回声抑制实验，但同样不等价于可直接消除的 cycles。

W01 源提交为 `b44eaffcefade`，published RTL 提交为 `15d06c98ef32`，RTL SHA-256 为
`afe3c5dfcff8cd3c795a2738e1aec6c6e7e89c7e2e42e10f4ee7321f760cf26d`。19 个短
perf20 项全部通过，冻结 milestone 的 `71,588,939` cycles 降到 `71,515,650`，减少
`73,289`（`-0.102375%`）；17 项改善、2 项退化。收益较小但分布广，且实现不增加 wakeup
lane，因此保留为组合候选。

E02 源提交为 `08fbf1120115`，published RTL 提交为 `71577a4fa0db`，RTL SHA-256 为
`f59c5273c94e6a70f7fb5f73ee0fc2097806385b0718fdd6460db5cbeeaaab93`。候选只旁路
普通 ROB head 的 matching staged completion，精确排除 exception、branch、serializing 和
system operation；年轻 commit lane 的顺序 prefix 不变。以 W01 为 paired baseline 的 19
个短 perf20 项全部通过，cycles 从 `71,515,650` 降到 `71,083,365`，减少 `432,285`
（`-0.6045%`）；17 项改善、2 项退化。W01+E02 相对冻结 milestone 合计减少 `505,574`
cycles（`-0.7063%`）。ROB 定向测试明确观察到 `commitValid=0b111`，证明三宽能力仍在；
短程序不再要求每个特定指令排列都必须出现三宽批次。test-contract 提交 `64bf153347fd`
后，matching 完整 `make cpu-check` 通过 Scala/Verilator `186/186`、Python `364/364` 和
locked port/lint/Yosys/publication；lint 为 876 条锁定 warning，signature `b021ae6a...`。
matching gate metadata 提交为 `572588e`。该结果仍不替代 func58/Linux 系统回归或 Vivado。

matching clean func58 随后以 random-AXI seeds `240/255/141` 全部通过 `58/58`，
`num_data=0x3a00003a`、LED `1/1`，无 DiffTest mismatch；总 cycles 分别为
`636390/636760/637596`。证据位于
`build/sim/runs/cpu_572588ee1774_chiplab_c398d274812f/clean-func58/random/`。
这补齐了 W01+E02 的轻量系统正确性置信来源；Linux、matching Vivado 和板测仍未由该结果覆盖。

L05/W02 observer 固定于 workspace commit `7e50f16`、schema `nscc-m01-v6`，绑定 CPU
`572588e` 和 RTL `f59c5273...`。四个代表项合计 `42,999,823` 个观测周期，benchmark
cycles 与 clean E02 逐项一致，所有守恒式通过。`5,194,524` 次 D-side translation request 中
DMW0 Load/Store 为 `2,822,126/1,560,994`，DMW1 为 `809,770/1,629`，TLB Load 只有 5 次；
非 TLB 比例 `99.99990374%`。direct/DMW Store translation 严格阻塞年轻 Load 共 `80,028`
cycles（观测周期的 `0.1861%`）。

W02 共识别 `3,517,657` 次 current-epoch、无异常、有效 LQ identity 的 Load completion；
`3,000,948` 次存在任意 IQ waiting consumer，`2,922,347` 次在 P0--P2 存在 waiting consumer，
分别占 Load completion 的 `85.3110%/83.0765%`。P0--P2 保守机会数相当于观测周期的
`6.7962%`。这些计数证明两项都值得进入独立 RTL A/B，但 translation 和 load-use 等待可被
乱序执行隐藏，不能把 `12.0803%` 与 `6.7962%` 相加成预期加速比。

L05 源提交 `2894e04`、published RTL `3531af7` 的 19 项短 perf20 从 W01+E02
`71,083,365` 降到 `62,580,196`，减少 `8,503,169`（`-11.962249%`），19 项全部改善。
W02 源提交 `f2dfd1e`、published RTL `7f12340` 在同一条件下进一步降到 `61,817,068`，
减少 `763,128`（约 `-1.219%`），18 项改善、`stream_copy` 持平。W02 不增加 wakeup lane；
LSQ 在 completion 入寄存器前锁定 identity/exception/write 资格，backend 检查 current epoch，
consumer 通过既有 PRF write-through 得到实际数据。两项合计相对 W01+E02 约 `-13.036%`；
这些周期结果本身不能替代 matching full gates、func58/Linux 或 100 MHz route。
matching `make cpu-check` 已通过 Scala/Verilator `189/189`、Python `364/364` 和 locked
port/lint/Yosys/publication；lint 仍为 876 条锁定 warning、signature `b021ae6a...`，gate
metadata 提交为 `03a466a`。matching clean func58 model hash 为 `5fef1d7c...`；random-AXI
seeds `240/255/141` 均通过 `58/58`，返回 `0x3a00003a`、LED `1/1`，无 DiffTest mismatch，
cycles 分别为 `635293/635562/635932`。证据位于
`build/sim/runs/cpu_03a466a39d80_chiplab_c398d274812f/clean-func58/random/matrix_1892a80af7f5_func58.csv`。
Linux 与 100 MHz route 仍需按组合 HEAD 重新建立。

## Scheduling

Vivado implementation 运行期间不启动 SBT、模型编译、另一 Vivado 或长 Verilator。可以并行
做文档、离线 JSON/报告分析和已编译模型上的短 runtime；任何改变 CPU HEAD、published RTL
或 prepared model 输入的操作必须在该次 implementation 结束后重新冻结身份。每次完成后保存
route/top-N/资源证据，再据此选择后续候选。
