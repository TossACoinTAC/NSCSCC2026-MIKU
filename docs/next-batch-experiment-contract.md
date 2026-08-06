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
| L05（软件 A/B 通过） | 四个代表项 `5,194,524` 次 D-side translation 中只有 5 次走 TLB；direct/DMW 往返事件数相当于观测周期的 `12.0803%` | `2894e04` 在已有 `scheduledLoad`/Store entry 寄存边界填入动态 PA/MAT/translationDone；不把 AGU 组合地址直接接入 L1D | ATU `6/6`、LSQ `32/32`、core `14/14`、integration `4/4`；19 项软件、组合完整 gates、func58 与 Linux 全过 | `71,083,365 -> 62,580,196`（`-11.962249%`）；保留并进入 matching 时序门禁 |
| W02（软件 A/B 通过） | `3,517,657` 次合格 Load completion 中，`2,922,347` 次在 P0-P2 已有等待 consumer，占 completion 的 `83.0765%` | `f2dfd1e` 注册 LSQ completion 资格，复用 P3 wakeup lane 与 PRF write-through；backend 检查 current epoch，不增加广播 lane | cache/forward、exception、flush/epoch、LQ reuse 和实际 operand data；LSQ `32/32`、backend `15/15`；组合完整 gates、func58 与 Linux 通过 | 相对 L05 `62,580,196 -> 61,817,068`（约 `-1.219%`）；18 项改善、1 项持平；关闭 W02 的 matching route 更差，排除“W02 直接导致当前退化” |
| F01 phase 1（软件 A/B 通过） | v7 中相邻 I-cache request 最短 interval 为 3 cycles，代表项 frontend empty 为 `24.32%--55.85%` | `0c6a5dd` 将已翻译 fetch response 直接旁路给 L1I，同时保留原 translated-request slot 作为 backpressure 缓冲 | focused frontend test；func58 random-AXI 三 seed；19 项 paired perf20；instrumented representative counters；Linux random-AXI 三 seed | 19 项 `61,817,068 -> 53,646,498`（`-13.217337%`），全部改善；请求最短 interval 从 3 降到 2 且 2-cycle 桶占主导，保留进入后续组合 |
| Q01（新 100 MHz 板测里程碑） | 四个固定端口的 IQ resident entry 保存完整 `OooRenamedUop`，大量冷字段被每 entry 复制并进入 compaction 布线 | `341280a` 改为按端口保存最小 decoded payload，issue 边界重建既有 uop 接口；不改变选择、wakeup、端口能力或对外接口；`03f7202` 再切断 matching route 暴露的 L1D-response/LSQ 与 barrier 路径 | 每端口字段定向测试、20,000-cycle 随机 scoreboard、所有 FU/flush/wakeup 回归、完整 gates、19 项 perf20、func58 三 seed、团队板 perf20 20/20 | 同一 `627aca6/6a4437a6...` RTL 在 100 MHz 达到 setup/hold `+0.009/+0.012 ns` 并通过板测；相对旧 milestone 总 CPU cycles `-26.910%`。Q01 本身周期语义中性，收益归属整个组合 |
| D01（已停止） | P3 `portReady` 当前对 Load/Store 都要求 IQ 与 SDQ ready；SDQ 满时 Load 可能被无关阻塞 | 只保留 observer 复核，不修改 router；Store 的 IQ+SDQ 原子接受语义不变 | 旧基线四代表项严格下界仅 `27 / 41,501,656`；F01 四代表项复核只有 `coremark=193`、其余为 0 | 暴露持续接近零，不进入 RTL，不增加 ready 组合逻辑 |

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
2. D01 严格下界 observer 只统计最老 dispatch lane 为 Load、IQ3 已 ready、但 P3 仅因 SDQ
   not-ready 被阻塞的周期。旧基线四代表项仅 27 cycles；F01 四代表项复核仅 `coremark=193`、
   其余为 0。暴露持续接近零，D01 已停止，不修改 production RTL。
3. v5 observer 已按 exact head/current epoch 精化 E02，并确认 W01 冲突中存在等待消费者。
   W01 与 E02 分别完成独立 RTL、定向测试和 19 项 paired perf20；二者均保留进入组合候选。
4. L05/W02 已按 observer 排序完成独立 RTL 与 paired A/B。L05 19 项全部改善并降低
   `11.962249%`；W02 在其上再降低约 `1.219%`，18 项改善、1 项持平。W02-on/off 两次 route
   都未闭合，但关闭 W02 后 WNS/TNS 更差，已排除“W02 直接导致当前退化”；时序数据只绑定
   各自 RTL 快照。
5. F01 phase 1 以 W02-on 为 paired baseline，19 项全部改善并降低 `13.217337%`；func58、
   Linux 三 seed 和 M01 守恒均通过。请求 interval 的结构变化与周期收益同向，F01 保留。
6. 只有具有独立、可复现正收益的候选才进入下一次合并 implementation；不为
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
matching instrumented Linux model hash 为 `04361999...`，M01 patch hash 为 `f3e92fcc...`；
random-AXI seeds `1/19557/5570815` 的 200 ms 窗口均通过，未见 DiffTest/trace error，每个窗口
为 `99,999,995` cycles，retired instructions 分别为
`48,574,350/48,542,696/48,575,829`，IPC 为 `0.485744/0.485427/0.485758`，M01 守恒检查
全部通过。矩阵摘要位于
`build/sim/runs/cpu_03a466a39d80_chiplab_c398d274812f/instrumented/random/matrix_702d500d09d1_summary.txt`。
matching 100 MHz performance implementation 已完成并生成 bitstream，DRC 为 0 error、hold
`WHS/THS +0.053 ns/0`，但 setup `WNS/TNS -0.601 ns/-85.196 ns`，因此只作为 diagnostic，
不能进入板测。placed full-SoC 资源为 LUT `90,797`、FF `53,735`、BRAM tile `68.5`、DSP
`8`。前六条 setup path 都从 LSQ Store physical-address/overlap/order cone 进入 IQ3 operand
capture，最差 data path `9.819 ns`，其中 route `7.354 ns`（`74.9%`）；路径经过 fanout 166
的 `loadStoreQueue_io_completionValid` 网络。其余前十路径从 ROB pointer 进入 privileged
redirect target。归档位于
`Stable_Backup/cpu_03a466a39d80_chiplab_c398d274812f_perf_100mhz_20260805-070729_candidate/`。

W02 随后做了同配置关闭消融：source commit `b4968c4`、publication commit `81187c0`，
generated RTL SHA-256 为
`531659122f7ac202722190e4f568fa5aa269fdc3c830a18bca5d0731052b3e65`。clean 100 MHz
performance implementation 同样生成 bitstream、DRC 0 error，hold `WHS/THS +0.050 ns/0`；
setup 则为 `WNS/TNS -0.778 ns/-633.874 ns`，比 W02-on 的 `-0.601/-85.196 ns` 更差。最差
路径从 LSQ `scheduledLoad` ROB pointer 到 IQ3 `issueOperandSource2`，route 占 data path 的
`74.4%`。因此现有 matching 消融排除了“W02 直接导致时序退化”的判断；它没有证明 W02
本身改善 Fmax，也不能消除 placement/routing 交互。两组 route 都来自仍在快速演进、尚未
阶段稳定的 RTL，只作为后续读取 matching top-N 的参考，不继承到新候选。

workspace `ad148e9` 的 `nscc-m01-v7` observer 在 `03a466a/50b460f...` 上新增前端请求
interval、IQ/dispatch/issue 和 branch resolve-to-recovery 统计。ideal-memory seed 0 的
`fireye_I2/coremark/quick_sort/inner_product` 全部通过，实际 retire IPC 分别为
`0.3569/0.3175/0.3221/0.4355`。相邻 I-cache request 没有 1--2 cycle interval，最短为
3 cycles，且 3-cycle 桶占各项 request interval 的 `81.69%/51.79%/68.41%/65.95%`；frontend
empty cycles 分别占 `53.95%/24.32%/55.85%/36.53%`。branch matching recovery 的平均
resolve-to-recovery latency 为 `1.89/3.59/2.31/6.63` cycles，累计 latency 分别相当于总
观测周期的 `2.28%/1.97%/3.23%/0.05%`。这些窗口包含共同启动段，约 `1.19M` 次 uncached
request 不能解释成 benchmark ROI；它们足以把 F01/H03 与 B01 的 ROI-aware 测量提升为下一轮
高优先级，但还不是可直接相加的 speedup 预测。结构化证据位于
`build/sim/runs/cpu_03a466a39d80_chiplab_c398d274812f/instrumented-perf20/ideal/`。

F01 phase 1 的 source commit 为 `0c6a5dd`，publication commit 为 `07b0fc0`，generated RTL
SHA-256 为 `30ed22c905b10e561068219846dc9cc0d6010db6e15bb6ed8f9001138d759529`。它把已经
完成的 instruction translation response 直接旁路到 L1I request，同时保留 translated-request
slot 处理 backpressure。以 W02-on 的 `61,817,068` cycles 为 paired baseline，相同 19 个短
perf20 项全部通过且逐项改善，总数降至 `53,646,498`，减少 `8,170,570`
（`-13.217337%`，speedup `1.152304x`）。matching clean func58 random-AXI seeds
`240/255/141` 均通过 `58/58`；四个 instrumented 代表项的 counters 和守恒式通过。M01 显示
相邻 translation request 的最短 interval 从 3 cycles 降到 2 cycles，且 2-cycle 桶成为主导，
直接证明该实现移除了一拍稳态前端启动 bubble。

matching instrumented Linux random-AXI seeds `1/19557/5570815` 各运行约 100M cycles，均
通过 verdict、DiffTest 和 M01 invariants；IPC 分别约为 `0.551143/0.551120/0.550337`。
D01 在 `fireye_I2/coremark/quick_sort/inner_product` 上的严格下界分别为 `0/193/0/0`，继续
接近零，因此停止 D01。F01 的周期、功能和结构计数形成了相互独立的正向证据，但其资源、
100 MHz WNS 和板测仍必须由后续 matching candidate 重新建立。

## Q01 Compact IQ And FreeList Regression Closure

Q01 从改名后的开发起点 `42d9f36` 建立独立候选。`341280a` 仅压缩四个固定执行端口的
IQ resident payload：每个端口保存本端口会消费的 decoded 字段，issue 时重建原有
`OooRenamedUop` 接口；选择顺序、wakeup tag、ready 状态、ROB/epoch identity 和执行端口
能力保持不变。`afbca21` 增加 20,000-cycle 随机 scoreboard，覆盖 enqueue、任意 ready
entry 选择、compaction、wakeup、backpressure 和 flush 交错。

锁定 Yosys 在 exact parent 与 compact IQ 之间显示结构变化集中在调度存储及其 mux：完整
`core_top` cell 从 `67,173` 降到修复后的 `62,254`（`-4,919`，约 `-7.32%`），DFF
`7,047 -> 5,793`（`-17.79%`），`$eq 13,237 -> 12,337`，`$mux 32,735 -> 30,330`，
`$pmux 1,693 -> 1,547`；wire 从 `79,682` 降到 `74,633`，wire bits 从 `549,420`
降到 `528,331`。FreeList 修复只在 compact-only 结果上增加 4 个 Yosys cells，未抵消 Q01
的主要缩减。该层次统计只说明逻辑规模，不替代完整 SoC placed resource 或 routed timing。

首轮 Q01 软件仿真复现了此前 `88657fd` 团队板的相同 `18/20` 症状：`sha` 停在
426,124 条 retired instructions、PC `0x1c0100c0`，`lookup_table` 进入 PC
`0x1c000380` 的失败环。exact parent `42d9f36` 在相同软件、模型合同和 time limit 下得到
相同端点，因此该问题不由 IQ payload 压缩引入。回溯定位到 `6711a06` 删除了全局 rename
决定中的 FreeList capacity gate；当时的证明只从 63 个可分配 physical registers 中减去
32 个 ROB entries，却漏算最多 31 个长期保留的非零 architectural mappings。ROB 未满并不
保证仍有新 physical tag，继续分配会复用 live tag，表现为依赖死锁或数据/异常破坏。

`aaaeaa0` 恢复了安全分配条件：FreeList 保留按实际 writer 数计算的精确
`allocateReady`，另向全局三宽原子分配提供 `freeCount >= renameWidth` 的保守
`allocateCapacityReady`。这样只把 6-bit state compare 接入 global ready，避免重新引入
`rd/writesGpr/CountOne` decode cone；最后 1--2 个空闲 tag 可能保守停顿，但不会复用 live
tag。修复由 FreeList `7/7`、backend dispatch `16/16`、core integration `4/4`、system
integration `1/1` 及完整 `cpu-check` 覆盖；完整门禁为 Scala/Verilator `203/203`、Python
`364/364`，locked port/lint/Yosys/publication 全部通过。

修复源码、published RTL 与 gate metadata 分别为 `aaaeaa0`、`7df9e41` 和 `fbb1003`；
published RTL SHA-256 为
`4d2a946af51ff312802427a334d0525e1a84af0109bfc741f9c00cc086c24903`。相同 clean
perf20 model 的 19 个短项目全部通过，合计 `49,022,303` CPU cycles；`sha` 和
`lookup_table` 分别为 `2,758,492/1,802,270` cycles。与修复前 Q01 的 17 个可完成项目
相比，16 项逐周期相等，`coremark` 减少 3,936 cycles；这证明修复没有观察到性能损失，
但不是 Q01 相对未压缩设计的 cycle-speedup A/B。证据位于
`build/sim/runs/cpu_7df9e417aef9_chiplab_c398d274812f/clean-perf20/ideal/`。

matching clean func58 绑定 gate commit `fbb1003` 和同一 RTL hash；random-AXI seeds
`240/255/141` 均通过 `58/58`，返回 `0x3a00003a`、LED `1/1`，无 DiffTest mismatch，
cycles 分别为 `636023/637115/636808`。矩阵证据位于
`build/sim/runs/cpu_fbb1003f0eb3_chiplab_c398d274812f/clean-func58/random/`。

`fbb1003/4d2a946a...` 的 matching 100 MHz performance implementation 已生成 bitstream，
DRC 为 0 error，hold `WHS/THS +0.044 ns/0`，但 setup 为
`WNS/TNS -0.225 ns/-1.846 ns`、37 个 failing endpoints，故仍是 diagnostic candidate。
placed full-SoC 资源为 LUT `86,486`（`64.64%`）、FF `51,981`（`19.31%`）、
slice `26,419/33,450`（`78.98%`）、BRAM tile `68.5`、DSP `8`。最差五条路径均从
L1D deferred response valid 进入 LSQ load completion data，最差 data path `10.099 ns`，
其中 route `8.340 ns`；另一条 `-0.084 ns` 路径从 Store translation completion 进入
barrier state。归档位于
`Stable_Backup/cpu_fbb1003f0eb3_chiplab_c398d274812f_perf_100mhz_20260806-024635_candidate/`。

针对上述 top-N，source commit `03f7202` 给内部 cache request/response 携带既有 LQ owner，
LSQ response 直接索引 entry 后仍校验 valid、requestSent、ROB pointer 和 recovery epoch，移除
response-to-completion 路径上的 16-entry associative ROB/epoch search 与 `OHToUInt`。同时把
memory-subsystem quiescence 预注册后再送入已有的连续两周期 barrier 判定，保守地增加至多一拍
串行化等待，切断 translation-to-barrier 组合路径。LSQ、shared-cache、AXI bridge 和 execution
focused suites 分别通过 `32/32`、`6/6`、`8/8`、`10/10`；matching 完整 `cpu-check` 通过
Scala/Verilator `205/205`、Python `364/364` 及 locked port/lint/Yosys/publication。gate commit
为 `627aca6`，published RTL SHA-256 为
`6a4437a6adcd9f13afbb8e5561e660f6881855d84614e363ee222018af402d3c`；Yosys cell 从
`62,254` 小幅增至 `62,311`。

该 RTL 的 matching clean 100 MHz route 已验证两条 targeted cut 生效：此前 top-N 的
L1D-response/LSQ 与 translation-to-barrier 路径族均退出前十。原始 route 仍为 setup
`WNS/TNS -0.454 ns/-143.682 ns`、1,196 个 failing endpoints，hold `+0.012 ns/0`，新前十
集中在 `idleController.enterPending` 经 redirect/wakeup/ROB/IQ select 与 compaction 到 IQ
resident payload clock-enable，最差 data path `10.007 ns`，其中 route `8.314 ns`（`83%`）。
这说明 RTL cut 改善了指定路径，但布局布线扰动把剩余高扇出控制路径推成新的全局瓶颈，不能把
原始 route 直接认定为闭合。

在完全相同的 `627aca6/6a4437a6...`、c398 platform 和 `100/100/200 MHz` 时钟上，从该
routed DCP 执行 `phys_opt_design -directive AggressiveExplore` 后再以
`route_design -directive AggressiveExplore` 重布线，最终 setup `WNS/TNS +0.009 ns/0`、hold
`WHS/THS +0.012 ns/0`，0 unrouted nets、DRC 0 error 且 bitstream 成功。最终最差路径仍为
`idleController.enterPending` 到 IQ resident payload CE，data path 降为 `9.540 ns`，其中
route `7.952 ns`（`83.35%`）；因此闭合来自同一网表的物理优化与重布线，不是继承旧 RTL 的
时序。post-route 资源为 LUT `86,174`（`64.41%`）、register `52,535`（`19.52%`）、BRAM
tile `56.5`、DSP `8`。完整 bitstream、DCP、前后 timing、DRC、route status、脚本、日志与
hash 归档在
`Stable_Backup/cpu_627aca6a565a_chiplab_c398d274812f_postroute_100mhz_20260806-034619_stable/`。
matching 团队板 perf20 job `20260805-195251-eff27bf6` 最终 verdict 为 `passed`，20/20 均在
`nscscc-system-reset-v1` 下双跑并选择较慢有效值。总 SoC/CPU cycles 为
`50,784,604/50,772,461`；相对上一 matching 100 MHz milestone job
`20260804-182327-8f1c8193` 的 `69,476,960/69,466,027`，分别减少
`18,692,356/18,693,566`，即 `-26.904395%/-26.910372%`。20 项中 19 项改善；唯一退化的
`stringsearch` 从 `799,866` 增至 `1,248,551` CPU cycles（`+56.095021%`），后续前端/分支
优化仍需把它作为独立反例，而不能只看总和。该 A/B 衡量从旧 milestone 到当前组合的总体收益，
不能把 `-26.91%` 单独归因给周期语义中性的 Q01。

远端 result、双跑 CSV、programming/board summaries 与原始 metrics 的 hash 已全部匹配，归档在
上述 stable 目录的 `board_jobs/20260805-195251-eff27bf6/`。因此 `627aca6/6a4437a6...` 现可作为
新的 100 MHz performance milestone：完整本地 gates、func58 三 seed、matching full-SoC
setup/hold、DRC、bitstream 与团队板 perf20 20/20 均成立；它仍不等价于 Linux 板上启动证据。

## 627 Milestone Frontend Attribution And H03-III/IV Contract

在 `627aca6/6a4437a6...` 上以 ideal memory、seed 0 重跑 M01 后，
`coremark/fireye_I2/quick_sort/inner_product` 均通过 DiffTest 与 observer 守恒式。软件报告的
CPU cycles 分别为 `4,884,950/6,574,849/2,732,066/7,800,209`；observer 的实际窗口为
`15,921,319/9,931,936/6,060,400/11,232,298` cycles，retired instructions 为
`5,157,165/3,819,837/1,947,850/5,165,016`，对应 IPC
`0.323916/0.384601/0.321406/0.459836`。两种 cycle 口径不可混用：前者用于相同软件合同下的
A/B，后者用于 observer 内部比例与守恒检查。

frontend empty 占实际观测窗口的 `20.2425%/43.3060%/48.6415%/29.4684%`，平均 frontend
occupancy 为 `3.7917/1.9610/1.9260/3.1750`。相邻 instruction request interval 不大于
2 cycles 的比例为 `33.66%/65.98%/46.81%/55.68%`，3-cycle 桶为
`21.49%/17.10%/27.91%/6.54%`，至少 4 cycles 的长尾仍有
`44.85%/16.92%/25.28%/37.78%`。ROB 平均占用为
`18.968/14.868/12.572/13.736`，ROB full 比例仅
`0.922%/1.423%/0.596%/0.0089%`；零 retire 且 ROB empty 的比例为
`3.448%/8.316%/9.333%/0.560%`。branch resolve-to-recovery 平均延迟为
`4.546/2.516/4.350/9.095` cycles，但累计暴露只占窗口
`2.281%/3.243%/6.080%/0.0728%`。

因此本轮归因将前端持续供给列为第一高权重瓶颈，ROB 容量不列为当前首要瓶颈；B01 仍是第二
候选，尤其应以 `quick_sort` 验证。该判断来自四个代表 workload，不外推为 perf20 全体的精确
speedup。原始 JSON 与日志位于
`build/sim-current-milestone/runs/cpu_627aca6a565a_chiplab_c398d274812f/instrumented-perf20/ideal/`。

H03-III 从 `627aca6` 建立独立候选。`172161c/975c3aa` 保留 L1I instruction data 与
predecode 的寄存器边界，只向前端传递一位 hit-response-pending token，并为旧 response 保存一份
PC/predictor owner context；同时恢复已有的 translation turnover。初版 published RTL SHA-256
为 `f0283e74685bd1f63372054bf224ce401d92c4442cda323801704439b469a704`，`core_top`、AXI、
debug 与 commit 接口不变。仅加入 hit token、保持 translation turnover 关闭时，首个 warm-hit
request interval 仍为 2 cycles；两项同时启用后，定向测试观测到 1-cycle 连续 warm-hit request
interval，因此二者是一个耦合的吞吐候选。

初版代表矩阵暴露了真实 correctness bug：`quick_sort/fireye_I2/inner_product` 通过，但
`coremark` 进入官方失败环。原因是 8-entry IBUF 在旧 registered response、当前 hit handoff 和
下一 translated group 同拍并存时，实际需要为三个 4-instruction group 保留 12 个 slot，而旧
credit 只保留 8 个。`07546ca/a3a96f8` 将 capacity qualification 修为 triple-overlap 预留 12、
double-overlap 预留 8，并补上显式驱动 hit token 的 8/16-entry 对照测试。修复后的 published RTL
SHA-256 为 `51ddbab54633869673de55bba1371de095fc4384a598ee089be0a2b6bc0737d1`；frontend
`17/17` 与 core integration `4/4` 通过。失败环 trace 保存在
`build/sim-h03iii-debug/runs/cpu_975c3aae2f78_chiplab_c398d274812f/clean-perf20/ideal/`，不得作为
候选性能数据。

为解除上述协议的结构性 credit 限制，`99a1ec6/f2139ba` 把 IBUF 从 8 扩为 16 entries；published
RTL SHA-256 为 `564ee665f60a746a77c9f475ed71c650fbf92d94b7edab7a182a0e31cdc77163`。同一 clean
perf19、ideal memory、seed 0 的精确 A/B 为：`627aca6=49,022,303` cycles，容量修复后的
8-entry H03-III 为 `48,926,633`（`-0.1952%`），16-entry 为 `46,862,959`
（相对 627 `-4.4048%`，相对 8-entry `-4.2179%`）。16-entry 相对 627 有 17 项改善，只有
`fireye_C0 +0.1322%` 与 `inner_product +0.0348%`；`crc32 -35.2845%`、`sha -10.9176%`
证明 16 entries 的主要作用是让已实现的三组并存协议真正持续工作，不应解释为一般队列扩容收益。

H03-IV 在此基础上消除预测分支组的剩余 turnover bubble。`b3f4801/9d18632` 将本组 predicted
GHR/RAS update 直接送入 predictor 已有的同拍 history lookup bypass，使 conditional、call 和
return group 也能在 translation response 进入 L1I 的同拍启动目标 translation；published RTL
SHA-256 为 `31b74bd8f434b879dc47a4a0b964124ec5feae1be03ebd268cfd412f32ed5854`。frontend
`19/19` 和 core integration `4/4` 已通过，其中 conditional 定向测试继续到下一 fetch group，
验证其 PHT index 确实包含刚旁路的 taken history；call/return 定向测试则验证 call request 同拍
push 的 speculative RAS 能让下一组 return lookup 直接 turnover 到 `callPc + 4`。测试提交
`109af32` 也把 `OooCoreSpec` 的 IBUF 配置合同从旧 8 entries 同步为 16。第一次完整门禁因此在
`206/207` 时只失败于该过期静态断言，并非运行时回归；修正后的 focused core `14/14` 以及完整
Scala/Verilator `208/208`、Python `364/364`、锁定端口/lint/Yosys/publication 门禁全部通过。
`85834ed` 将组件替换和 lint waiver 的发布哈希同步锁定到上述 RTL；该提交不改变 RTL SHA-256。
perf19 全部通过，总周期为 `46,240,121`：相对 IBUF16 再降 `1.3291%`，相对 627 净降
`5.6753%`；18 项改善，仅 `inner_product +0.0313%`。matching clean func58 random-AXI seeds
`240/255/141` 也均为 `58/58 pass`，CSV 位于
`build/sim-h03iv-func/runs/cpu_9d18632eaa69_chiplab_c398d274812f/clean-func58/random/matrix_1892a80af7f5_func58.csv`。
该 func58 prepared model 的 CPU key 早于纯测试/元数据提交，但其 RTL SHA-256 与 `85834ed` 精确
相同，因此可作为同一硬件候选的功能证据；后续 Linux 与 Vivado 证据统一以 `85834ed` 冻结身份。

`85834ed` 的 clean Linux ideal-memory 50 ms 窗口通过：seed 1 跑满 `24,999,995` CPU cycles，
提交 `16,186,862` 条指令，无 DiffTest mismatch，以预期 time-limit 结束。首次 instrumented
random-AXI 200 ms 三 seed 也都跑满 `99,999,995` cycles、无 mismatch，所有 RTL/架构级 observer
invariants 为真，但旧 `nscc-m01-v7` 将 frontend occupancy 硬编码为 9 桶，只覆盖 8-entry IBUF；
16-entry 候选的 occupancy `9..15` 被丢弃、`16` 又被 4-bit mask 截成 0，因此三条 verdict 均为
`counter-invariant-error`。这是 observer coverage bug，不能作为候选 correctness 失败或通过证据。
workspace observer 随后升级到 `nscc-m01-v8`：采样覆盖 `0..16`、输出 17 桶，同时检查器保留历史
v6/v7 兼容；source hash lock、历史 v7 实例、17 桶守恒正例和 16 桶拒绝例均通过。最终三 seed
Linux 已用重编译 model `7b6fb161...` 重新产生，三条 verdict 和 M01 check 均为 pass；retired
instructions 分别为 `49,558,927/49,557,802/49,775,416`，trace signature 分别为
`465e6e2431be1ab5/f974d379c6016c36/6bd37bcacc74a60d`。每条直方图都精确守恒
`99,999,995` cycles；occupancy `>=9` 占 `51.1407%/51.1572%/51.1001%`，平均 occupancy 为
`7.0081/7.0097/7.0057`，16-entry full 仅占 `1.5196%/1.5176%/1.5063%`。这既证明 8-entry
版本无法表示大量真实前端在途状态，也说明 16 entries 尚未成为常态容量上限。

clean perf19 证据分别位于 `build/sim-627-clean/`、`build/sim-h03iii-fixed8/`、
`build/sim-h03iii-ibuf16/` 和 `build/sim-h03iv-history-turnover/`。`tools/sim-prepare` 同步改为先
校验并 staging perf20/func58 workload，再编译模型；无效 workload 因此在模型编译前失败，完整
perf20 prepare 已验证正向路径。H03-III/IV 刻意避免 H03-II 的 L1I BRAM data/predecode 组合
旁路，但 16-entry 动态 buffer 读写 mux 和 predictor BRAM-response-to-next-PHT-address 是新的 timing
风险。锁定 Yosys 结构报告相对同一 `6a4437a6...` 基线从 `62,311` 增至 `63,890` generic cells，
其中 `$dff 5,810 -> 5,921`、`$mux 30,400 -> 31,569`、`$eq 12,347 -> 12,612`；这些数字只用于
定位 16-entry IBUF/context mux 带来的结构增量，不能替代 Vivado LUT/FF/BRAM 利用率。当前 milestone
setup 裕量只有 `+0.009 ns`，所以 matching 100 MHz full-SoC timing 仍是
保留候选的硬门禁；`stringsearch` 继续只在 matching 板测运行。

`85834ed/31b74bd8...` 的 matching clean 100 MHz implementation 已生成 bitstream，DRC 为
0 error、hold `WHS/THS +0.054 ns/0`，但 setup 为
`WNS/TNS -2.066 ns/-9507.783 ns`，因此不能作为板测候选。综合利用率为 Slice LUT
`85,498`（`63.52%`）、register `44,238`（`16.43%`）、BRAM tile `64`、DSP `8`；这些是
synthesis 数字，不与 627 的 post-route 数字混用。归档位于
`Stable_Backup/cpu_85834ed12f90_chiplab_c398d274812f_perf_100mhz_20260806-064506_candidate/`。
从该 routed DCP 再运行 `phys_opt_design/route_design -directive AggressiveExplore` 后，setup
反而为 `-2.182 ns`，hold 仍为 `+0.054 ns`；Vivado 也明确提示原始 `-2.066 ns` 已远超通常适合
post-route 修补的约 `-0.5 ns` 范围，故必须修改 RTL 结构。

matching top-N 将问题定位为同一个 response control cone。最差路径从 L1I 寄存的
`responseValid` 经过 response predecode、prediction correction 与同拍 speculative history
选择进入 PHT BRAM `ADDRBWRADDR`，最差 post-route slack `-2.182 ns`、21 levels、data path
`11.538 ns`；同源路径还进入 RAS clock enable（约 `-2.008 ns`）和多个 IBUF entry clock enable
（约 `-2.054/-2.001 ns`）。因此违例并非旧 IQ wakeup/select 路径，也不能靠关闭整个 H03-IV
来粗粒度处理。

`9c1a3d2` 对该共同锥做了两项协议保持的切割。第一，已接受 request 的 speculative GHR/RAS
更新只由 `requestFire` 限定，不再让当拍 response correction 回穿预测器写使能；若该 request
随后确认走错，现有 `predictionCorrectionFlushPending` 会在 corrected lookup 前阻塞并于下一拍
恢复 architectural history。第二，IBUF response 的有效 lane 按协议必然是从 `firstSlot` 开始、
在第一个 predicted-taken lane 截止的连续后缀，因此写入位置可由 `lane-firstSlot` 直接确定，无需
让 prediction mask 的动态 prefix count 进入 16-entry 写选择。新增定向测试覆盖第二个 fetch group
从 lane 2 开始并在 lane 3 taken 的非对齐压缩场景，逐项检查 PC、instruction、taken 与 target。

source/test、published RTL 和 gate metadata commits 分别为 `9c1a3d2/8ca1d3a/182c260`，新
published RTL SHA-256 为
`d93e80131e5a87ef5188637aec69bc91ef474d538ed82323b0a690d5498e7eed`。完整门禁通过
Scala/Verilator `209/209`、Python `364/364` 及 locked port/lint/Yosys/publication；lint 仍精确
匹配既有 `1073` 条 `CMPCONST/UNUSEDSIGNAL` 签名。重新编译的 clean perf19 也全部通过，19 项
逐项周期与修复前完全相等，总计仍为 `46,240,121`。证据位于
`build/sim-h03iv-timing-cut/runs/cpu_182c260bd1c0_chiplab_c398d274812f/clean-perf20/ideal/`。
这证明 RTL cut 保留已测周期行为，但最终保留候选仍取决于当前在途 matching clean 100 MHz
full-SoC route；不能继承前一 RTL 的 WNS、DCP 或 bitstream。

## H03-IV history turnover：本轮停止点与证据

本轮最终按“完成当前综合/实现后停止”的要求收束。需要区分两个 CPU 身份：

- `1a9591d496cee178f67a6e99ed4d660eff112fbb` 是已完成软件性能和完整 SoC 实现的
  history-turnover 候选，published RTL SHA-256 为
  `c4f60a56ba21be7bde9dde809075ae077ae1073e195074f9e983b608f4fb03d3`。
- `8e020f5` 是在该候选基础上继续收缩 translation owner 选择器的 source commit，生成 RTL 与
  gate metadata 发布于 `70e009e`。`make cpu-check
  CPU_DIR=build/experiments/cpu-h03iv-history-turnover` 已通过，发布后的
  RTL SHA-256 为
  `4968c5131a7fdf937286b0d7eacfe41a9d9d42e25bed89b89bf2e45f9d770839`。本轮没有为它重新跑
  perf20 或完整 SoC implementation，因此不得把下述旧候选的周期和 WNS 归给 `8e020f5`。

### 软件和门禁

`1a9591d` 的 clean ideal-memory perf19（官方 20 项中排除只在板上运行的 `stringsearch`）
为 `19/19 pass`，总周期 `47,084,627`。相对稳定基线 `49,022,303` 为 `-3.952642%`，
相对上一版 H03-IV 的 `46,240,121` 为 `+1.826349%`；逐 benchmark CSV 与日志保存在
`build/sim-h03iv-history-turnover-corrected/runs/cpu_1a9591d496ce_chiplab_c398d274812f/clean-perf20/ideal/`。
该结果是 `1a9591d` 的可追溯性能证据，不是 `8e020f5` 的性能结论。

新 source 的 focused frontend 测试 `22/22` 通过；随后完整 `make cpu-check` 通过：Scala/Verilator
完整套件、Python `364/364`，以及锁定 port/lint/Yosys/publication gates 均通过。发布 RTL 的
warning 数仍为 `1073`，签名为
`4034b71b42683a792c92a85d3955687ddc24a84b6b06ed3fad77fe5d1ae079ed`。生成物和替换/waiver
哈希已在 CPU 仓库单独提交；未把 ignored build 输出或其他工作树修改带入该提交。

### 完整 SoC 100 MHz 实现

对 `1a9591d`、Chiplab `c398d274812f164d387146fa7d8f612a4a1296d9` 的 clean performance
build 完成了综合、实现和 bitstream 生成。PLL 实际时钟为 CPU/SYS/DDR `100/100/200 MHz`；综合
资源为 Slice LUT `86,096 (63.96%)`、register `44,428 (16.50%)`、BRAM tile `62 (16.99%)`、
DSP `8 (1.08%)`。正常 route 的 setup `WNS/TNS=-0.919/-1217.716 ns`，hold
`WHS/THS=+0.059/0 ns`，DRC `0` error；bitstream 成功生成，但 setup 未收敛，归档类别为
`candidate`，不能用于板测或 release acceptance。归档目录为
`Stable_Backup/cpu_1a9591d496ce_chiplab_c398d274812f_perf_100mhz_20260806-103810_candidate/`。

从该 routed DCP 做的 `phys_opt_design/route_design -directive AggressiveExplore` 重试在
`general.maxThreads=4` 下完整结束：route `114712` 条 fully routed、DRC `0` error，bitstream
也成功生成，但 setup 仅改善到 `WNS=-0.697 ns`，hold 仍为 `+0.059 ns`。最差路径起点为
`translationUsesTurnoverPc_reg/C`，终点为 BTB bank 寄存器，data delay `10.631 ns`，其中
route `8.343 ns (78.478%)`；后续主要路径仍落在 BTB、L1I tag 到 predecode，以及 privileged
redirect 到 issue-queue enable。该 post-route 结果仍是诊断证据，不是时序通过。

第一次使用 8 个线程的 post-route 尝试在 Vivado `librdi_route.so` 的
`rt::HARTRouteDeposit::addElementPin` 中以 signal 11 退出；随后降低到 4 个线程后成功完成，故
不能把第一次崩溃解释为 RTL 或功能失败。崩溃报告已保存在 ignored 证据
`build/post-route-1a9591d-hs_err_pid43.log`；重试报告、validation、top-N、DCP 和 bitstream
均在 `build/post-route-1a9591d-aggressive/`，其中 validation SHA-256 为
`1d44a4dfdf6011fe10c19694af36099a8803fc639eb19dd1df620164b3fd5b88`。

### 收束判定

本轮不再启动新的软件仿真、Vivado implementation、板测或 CI。`8e020f5` 目前只具备完整门禁
证据，尚未具备自己的 perf20、完整 SoC WNS 或板测证据；下一轮若继续，应以其 published RTL
`4968c513...` 重新冻结 manifest，再独立测性能和时序。旧候选的负 WNS、DCP、bitstream 和
周期数据均只作历史参考，不能跨 RTL 提交复用。

## Scheduling

Vivado implementation 运行期间不启动 SBT、模型编译、另一 Vivado 或长 Verilator。可以并行
做文档、离线 JSON/报告分析和已编译模型上的短 runtime；任何改变 CPU HEAD、published RTL
或 prepared model 输入的操作必须在该次 implementation 结束后重新冻结身份。每次完成后保存
route/top-N/资源证据，再据此选择后续候选。
