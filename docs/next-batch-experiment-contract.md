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
提交到官方仓库同名分支 `dev/ECHO @ 0a8ef8caf08c` 触发 CI。本地没有取得 pipeline ID，
official CI verdict 无可记录证据，因此不得记为通过。

作为不改变官方固定矩阵的额外软件置信证据，当前 prepared model 又以 seed
`17/65537/99991` 通过 func58 `58/58`，`num_data=0x3a00003a`、LED `1/1`，endpoint
`0x1c00020c`，整段 cycles 为 `642766/642331/642589`；三份 verdict 和 DiffTest 均正常。

## Candidate Cards

| ID | 假设与触发信号 | 首次实现边界 | 正确性门禁 | 进入合并实现的条件 |
| --- | --- | --- | --- | --- |
| L04（已停止） | Store translation 只在 `!loadNeedsTranslation` 时 lookahead；当前 Linux 三 seed 的 head Store translation 暴露约 `9.718M--9.765M` cycles，head request/response fire 各约 `4.66M--4.71M` | 只改变 Load/Store translation owner 的 age-aware 选择；保留单 owner、cancel、SC 和 exception 身份，不增加第二 walker | C04/C06 相关 LSQ/TLB/flush/uncached 回归；func58 固定三 seed并保留额外三 seed；Linux instrumented 三 seed；代表 perf20 | 被移出 head 的 Store translation 周期增加，且 paired cycles 不退化；被推迟 Load 的额外等待单独报告；新 route 不使当前关键路径恶化到不可接受 |
| E02 | 当前 seed 1 有 `4,612,723` 个零退休周期为 head staged completion，理论最多省一拍；新 WNS 已落在 `stagedPdst -> IQ early-wakeup` 广播族 | 暂不进入首轮 RTL；先按 completion source 精化 head-only opportunity。若实现，只旁路 ROB head 的 exact pointer/epoch，不旁路年轻 commit lane | ROB flush/epoch/wrap、精确异常、三宽 stop/recovery、所有 completion source；func58/Linux/代表 perf20 | head-only bubble 的 paired cycles 明确下降，且不增加 staged wakeup 扇出；完整 SoC 的 cycle×frequency 不劣于基线 |
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
3. E02 保留为独立候选，但当前不与 L04 合并。新 WNS 已位于 staged wakeup 广播族，先把
   `rob_staged_completion` 按 source/head-only 精化，再设计不会增加该网络扇出的 bypass。
4. 只有后续具有独立、可复现正收益的候选才进入下一次合并 implementation；不为
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

## Scheduling

Vivado implementation 运行期间不启动 SBT、模型编译、另一 Vivado 或长 Verilator。可以并行
做文档、离线 JSON/报告分析和已编译模型上的短 runtime；任何改变 CPU HEAD、published RTL
或 prepared model 输入的操作必须在该次 implementation 结束后重新冻结身份。每次完成后保存
route/top-N/资源证据，再据此选择后续候选。
