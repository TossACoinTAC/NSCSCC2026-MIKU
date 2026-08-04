# Next Batch Experiment Contract

状态：候选合同，等待当前组合的 matching 100 MHz implementation 完成。

## Identity

所有结果必须绑定同一身份：

- CPU：`dev/ECHO @ 758181a01c5bb53156157bc7946bc37d6057f3ec`
- published RTL：`04d6e4b2a36371d0f65a4652e0ec93cd84405e52c20a830c0c13e3b77afa8cfc`
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`
- current implementation：`make soc-impl`, `PERF_CPU_MHZ=100`, `build_kind=perf`

Linux instrumented random-AXI 的三个 200 ms seed 已通过，perf20 的 19 个短项目和
func58 三个固定 seed 已通过；`stringsearch` 留给组合 correctness/timing milestone。
当前 route 结果未产生前，任何候选都不继承历史 WNS、资源或 top path。

## Candidate Cards

| ID | 假设与触发信号 | 首次实现边界 | 正确性门禁 | 进入合并实现的条件 |
| --- | --- | --- | --- | --- |
| L04 | Store translation 只在 `!loadNeedsTranslation` 时 lookahead；当前 Linux 三 seed 的 head Store translation 暴露约 `9.718M--9.765M` cycles，head request/response fire 各约 `4.66M--4.71M` | 只改变 Load/Store translation owner 的 age-aware 选择；保留单 owner、cancel、SC 和 exception 身份，不增加第二 walker | C04/C06 相关 LSQ/TLB/flush/uncached 回归；func58 三 seed；Linux instrumented 三 seed；代表 perf20 | 被移出 head 的 Store translation 周期增加，且 paired cycles 不退化；被推迟 Load 的额外等待单独报告；新 route 不使当前关键路径恶化到不可接受 |
| E02 | 当前 seed 1 有 `4,612,723` 个零退休周期为 head staged completion，理论最多省一拍 | 只研究 ROB head lane 的 exact pointer/epoch staged-completion bypass；不旁路年轻 commit lane，不改 completion source | ROB flush/epoch/wrap、精确异常、三宽 stop/recovery、所有 completion source；func58/Linux/代表 perf20 | head-only bubble 的 paired cycles 明确下降，且完整 SoC 的 ROB/commit 路径和 cycle×frequency 不劣于组合基线 |
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

## Scheduling

Vivado implementation 运行期间不启动 SBT、模型编译、另一 Vivado 或长 Verilator。可以并行
做文档、离线 JSON/报告分析和已编译模型上的短 runtime；任何改变 CPU HEAD、published RTL
或 prepared model 输入的操作必须等 implementation 结束并先重新冻结身份。Vivado 完成后，
先保存 route/top-N/资源证据，再决定三张 candidate card 的实现顺序。
