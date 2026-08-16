# MIKU 成熟优化循环与测试合同

本文是当前根仓库的工作流说明，替代初赛提交阶段和旧 `nscscc-cpu` 内部日志；
它不锁定某个候选的周期或 WNS。候选证据必须引用生成清单中的实际 hash。

## 一、阶段顺序

1. **冻结基线**：先生成 matching RTL，再用 `make experiment-freeze` 记录根仓库 HEAD、
   `cpu/` 内容 hash、Chiplab HEAD、dirty patch hash、Docker image/tool hash，以及显式列出的
   软件仿真、比较和功能证据。冻结清单是归档输入，不靠目录名或短 hash 猜测证据。
2. **变更分类与候选设计**：标为性能参数、内部结构、公开接口、RTL 生成文本、仿真
   harness 或工具环境变化。每一类声明预期不变量和观测指标。性能候选在设计和实现时
   同步检查寄存边界、组合锥、宽 mux、跨模块控制和高扇出，优先选择周期收益相同而
   时序压力更小的结构；这些实现选择属于候选本身，不能在性能验证完成后再静默改变。
3. **测试适配**：根据 `cpu/tests/manifest.yml` 做影响分析；只修改 schema、fixture
   或解析器，不删除 DUT 断言。先用 baseline 验证测试工具能够拒绝一个错误输入，再
   运行候选。测试设施的修改必须面向稳定公开接口并对源码演进保持向前兼容，不能为某次
   内部重命名、层级调整或单个 workload 写特判。测试失败先归类为
   config/harness/artifact/DUT。
4. **廉价门禁**：单个 ScalaTest suite、全 ScalaTest、Python 黑盒合同、Spinal
   生成和公开 `core_top` 端口检查。失败时不启动长仿真或 Vivado。
5. **隔离仿真**：`sim-prepare` 按内容身份生成或复用只读平台、模型和软件缓存；每个
   workload/seed 使用独立 `ram.dat`、tmp、日志和 `sim-result.json`。func58 使用固定
   三个 AXI seed；perf20 一次跑完整 20 项，包含 `stringsearch`。
6. **性能归因**：保存每项 cycles、end reason、模型 hash 和 seed。对前端、后端、
   LSU/cache、memory/AXI、DIV 和 predictor 只使用同一 baseline 的 paired A/B；
   `make experiment-compare` 同时输出逐项变化、总周期和归一化几何平均。几何平均用于轮次
   晋级，逐项表负责暴露回退，两者都必须保留。
7. **实现验证**：性能候选按实验清单声明的目标频率执行 clean SoC full implementation；功能里程碑补做
   function implementation。保存 DRC、setup/hold WNS/TNS、top failing endpoints、
   LUT/FF/BRAM/DSP、requested/actual clock 和 bitstream hash。若 full route 已 fully routed、
   hold/DRC/bitstream 完整，只有 setup 小幅未闭合且路径以物理布线为主，才进入条件子阶段
   `make soc-postroute-opt`；它必须复用同一 DCP/RTL/时钟身份，并重新完成 timing、DRC、
   route status 和探索证据归档。post-route 不改变 DUT 周期证据，也不是每轮必跑阶段；
   它产生的 DCP/bitstream 只用于分析物理收敛空间，不能成为正式竞赛产物。
8. **板测交接**：只把本地 gates、仿真和 matching Vivado 产物交给团队板流；记录
   LabAgent job、UART/VIO、结果和 artifact hash。板卡队列冲突是基础设施结果，不能
   当作 DUT 通过或失败。

阶段发布采用分支分工：`dev/*` 保存候选级提交、失败尝试和 manifest 关联的完整溯源；
阶段性门禁通过后同步 `main` 可以使用 squash，`main` 只表示可复现的里程碑状态，不作为
候选级实验记录。squash 前必须确认开发分支上的 matching RTL、测试和实现证据已归档。

## 二、并行与流水线

模型缓存未命中时，模型编译、SBT 和 Vivado implementation 遵守独占约束。模型编译完成
后最多两个隔离
Verilator runtime；以 `free -h` 的 `available` 和实测 lane 峰值为依据，不能只看宿主机
任务管理器百分比。Vivado 运行期间可以做只读文档、manifest 计算和短 Python 合同测试，
但不启动另一个 Vivado、SBT 或长仿真。

推荐流水线如下：

```text
CPU 修改
  -> 时序感知的候选实现 + 测试适配 + baseline
  -> Scala/Python/RTL gates
  -> 一次 sim-prepare
       |-> func58 seeds
       |-> 完整 perf20 seeds
       `-> Linux/随机 AXI 窗口
  -> 结果汇总与候选比较
  -> 一次 matching Vivado full implementation
       |-> full RTL-to-bit 时序/DRC/资源签核与自动归档 -> 板测交接
       `-> [条件] 同网表 post-route physical exploration
            `-> 路径信息反馈给下一轮 RTL/实现策略
```

同一轮可以累积多个高优先级、相互独立的候选，再做一次综合；但每项仍保留独立
`unique perf pairs`，不能因为组合收益好就掩盖某项正确性或时序退化。若综合未收敛，
先保存失败路径作为下一轮信息，不把负 WNS 候选标成稳定版本。

若一项时序优化能够脱离性能候选独立启用、独立 A/B 和独立保留，就把它作为普通候选
走同一条完整验证链。已有通用时序候选继续使用 `Txx`；新候选可按子系统采用短领域
前缀，例如前端 `FTxx`、后端 `BTxx`、访存 `MTxx`。时序结果若促成新的 RTL 修改，
该修改属于下一候选版本，必须重新运行受影响的定向测试、完整门禁和 perf20，不能直接
沿用修改前的周期或 RTL hash。

### 实验身份入口

```text
make test-impact TEST_BASE=<baseline-commit>
make experiment-compare BASE_MATRIX=<baseline.csv> CANDIDATE_MATRIX=<candidate.csv> \
  COMPARE_ID=<candidate-id>
make experiment-freeze EXPERIMENT_ID=<round-id> \
  EXPERIMENT_EVIDENCE="<perf20.csv> <comparison.json> <func58.csv>"
make yosys-analyze YOSYS_RTL=<frozen-mycpu_top.v> YOSYS_LABEL=<candidate-id>
make yosys-compare YOSYS_BASE_REPORT=<baseline-summary.json> \
  YOSYS_CANDIDATE_REPORT=<candidate-summary.json>
make timing-analyze TIMING_REPORT=<cpu_setup_top50.rpt>
make route-analyze ROUTE_LOG=<implementation.log>
make soc-impl SOC_EXPERIMENT_MANIFEST=<experiment-manifest.json>
```

`test-impact` 由 `cpu/tests/manifest.yml` 路由到版本化 path-to-suite 映射；它给出最低定向
集合，不替代完整 `cpu-check`。比较器要求两组矩阵均为完整 20/20 pass，且 Chiplab、profile、
suite、memory mode、software key 与 workload/seed 集合一致。模型和 CPU 身份允许不同，
因为这正是 A/B 的变量。

`yosys-analyze` 不依赖 `cpu-generate`，只读取调用方显式指定的冻结 RTL，并把输入快照、
RTL hash、锁定 Yosys 身份、分析脚本、层次统计和 LTP 原始输出封装到不可覆盖的报告目录。
它按真实实例数同时汇总保留归属关系的 hierarchy generic cells 和 `stat -width` word-bits，
再给出经过整核 flatten/`opt_clean` 后的全局统计，用来区分局部结构迁移与真正被剪枝后的整核
压力。关键模块在计算 LTP 前会单独展开其子层级，避免把子模块端口反馈误报成超长组合环；原始
LTP 文件因此保持在可审计的小尺寸。cell 对象数用于观察结构展开，word-bits 用于避免向量
打包造成虚假的面积下降；`yosys-compare` 只接受
Yosys 工具身份和分析配置完全相同的两份报告，避免把工具变化误记为候选变化。
每个 LTP 原始文件还有默认 8 MiB 的硬上限；若 Yosys 因新层级反馈产生异常规模的假环诊断，
harness 会终止分析并删除超限文件，不能生成可用于候选决策的半成品报告。

这些指标用于综合前发现容量扩张、宽 mux、局部深链和压力集中的模块。hierarchy 统计可能包含
后续会被剪枝的逻辑，post-flatten 统计也仍未做 FPGA 器件映射；generic cells 和 word-bits 都
不是 Vivado LUT/FF，LTP 不包含器件映射、布线和时钟约束，均不能替代 matching direct
full implementation 的 WNS/TNS、资源、DRC 和 fully-routed 门禁。完整 `synth_xilinx`
包含昂贵的资源共享、memory/techmap 与器件映射，不进入日常 harness；只有明确的深度
研究需要时才单独运行并限制日志与资源。

### 仿真缓存合同

`build/sim/cache/` 下的三层缓存分别承担不同身份：

- `platforms/` 是按 Chiplab 锁定提交导出的持久只读平台树，本地 Chiplab dirty patch
  不进入模型输入，也不再为每次仿真创建临时快照；
- `models/` 由平台 key、生成 RTL hash、harness patch、配置参数和 Verilator 工具身份
  共同寻址，只在这些编译输入变化时重新翻译 Verilog、编译 C++ 并链接模型；
- `software/` 由 suite、workload 集、软件输入 hash 和 staging 工具寻址。官方 func58 和
  perf20 使用 Chiplab 已锁定的 object，不会因重复执行 `make perf20-sim` 重新编译；
- `prepared/` 只保存本次请求到三层缓存的不可变引用及（若工作树意外 dirty）状态证据；
  正常干净 Chiplab 的 patch 文件为空。`runs/` 再按 model/software/workload/seed/memory
  mode 隔离运行输出。

`linux-sim` 默认固定运行 50 ms、单 lane；正常跑满窗口的结构化结束原因为
`linux-time-window-complete`。普通 smoke 使用的短 `TIME_LIMIT` 不能作为 Linux 里程碑证据。

缓存命中前必须复核 manifest、模型 hash、软件文件 hash 和当前 RTL hash。任何不一致都
归为 `artifact`，不得启动仿真。`SIM_REBUILD=1 make sim-prepare ...` 可显式重建当前身份的
平台、模型和软件项；`make clean-sim` 才会删除整套仿真缓存。cache key 只包含实际编译或
运行输入，文档提交和 Git 提交号仅作 provenance，因此无关提交不会强制重编模型。

### 测试设施向前兼容合同

测试与 CPU 内部实现之间只允许通过公开 `core_top`/AXI/debug 合同、软件镜像、仿真
参数和结构化结果通信。重命名内部类、拆分模块或改变生成层级时，既有测试不应需要
同步搜索替换。解析器只读取 `sim-result.json`、结果 JSON 和明确的公开日志字段，不以
Scala/Verilog/C++ 内部符号、源码行号或构造顺序作为断言。

适配验证至少包含三步：旧 baseline 和默认参数继续通过、一个错误输入能被拒绝、一个
真实端到端 workload 能到达公开结束点并生成完整结果。静态补丁可应用性检查只能证明
harness 可构建，不能替代运行验证；超时首先与软件启动成本和端点策略比对，不直接
归为 DUT 回归。

## 三、失败分类与证据

| 类别 | 例子 | 处理 |
| --- | --- | --- |
| `config` | 路径、seed、模式宏或工具缺失 | 修正配置并重跑 baseline |
| `harness` | 解析器、fixture、结果 schema 错误 | 先修测试并提交适配说明 |
| `artifact` | 模型/RTL/软件 hash 不匹配、结果缺失 | 丢弃该次证据，重新生成 |
| `DUT` | DiffTest mismatch、合同负向用例未拒绝、异常语义错误 | 提升正确性优先级，定向修复和回归 |

每个 `sim-result.json` 至少包含 workload、seed、cycles、status、end_reason 和
model hash。每个候选 manifest 至少包含 CPU source、RTL、software、clock、功能结果、
实现报告和对应 hash。旧实现或旧 Chiplab 的 WNS 只能作为历史参考。

完整 SoC 实现成功返回后由 `make soc-archive` 校验并归档。调用方必须传入
`SOC_EXPERIMENT_MANIFEST`；归档器只复制该清单逐项列出且 hash 仍匹配的证据，不再扫描
同一短 hash 目录并模糊收集矩阵。显式证据若是 perf20 矩阵，矩阵每行的安全相对
`result_path` 视为该矩阵的结构化组成，只补录后续 A/B 所需的 `run-manifest.txt` 和
`perf20-result.json`；归档后的 CSV 因此仍可直接交给 `experiment-compare`，不会连带复制
未被矩阵引用的其他 run。默认 `auto` 分类只允许从当前
RTL 直接执行一次完整 implementation（`implementation_stage=full`），并同时满足 setup/hold
WNS 均严格大于零、routed DRC 0 error/critical warning、fully routed 和 bitstream 完整的实现进入
`Stable_Backup/`；其余已产生完整报告的实现进入 `Post_Impl_Bundles/`。归档身份来自
`build/rtl/generation-manifest.json`，并再次核对根发布 RTL 与 Vivado staging RTL 的哈希，
不能用归档时的文档 HEAD 冒充生成 RTL 的源码提交。实验清单中的源码树、raw/published
RTL、generation manifest 和 Chiplab 身份还会再次与当前 implementation 输入核对。归档采用临时目录加原子改名；同一
实现再次归档时，先核对 RTL 与 bitstream hash，再幂等补录新增的 top-N、route status 或
资源报告，不覆盖身份不同的证据。

归档器同时从完整 Vivado log 生成 `route-health.json`，并把 `physical_health` 写入 manifest。
至少记录 route 用时、拥塞警告次数、Global Iteration 的 overlap 轨迹、峰值与最终 overlap、
方向最大拥塞和 post-route setup/hold。fully routed 只能证明最终可布通；高 overlap、反复
全局迭代或 85% 以上局部拥塞仍需作为下一轮 RTL 拓扑信息。不同 RTL 的物理健康比较必须与
资源、top-N、WNS/TNS 和周期同时解释，不能仅以总 cell 数或最终 overlap 为零判断优化成功。

`make soc-postroute-opt` 复用现有 fully-routed DCP 执行 `AggressiveExplore`，不重新生成 RTL、
不改变周期结果。它把输出写入独立的 `build/vivado/postroute-*`，重新生成 setup/hold、限定
`cpu_clk` 的 top-50、DRC、route status、资源、DCP 和探索用 bitstream。归档器无条件将
`implementation_stage=postroute` 标为 `competition_eligible=false` 并放入
`Post_Impl_Bundles/`；即使 slack 非负，也不能进入 `Stable_Backup/` 或作为竞赛 sign-off。
post-route 结果只用于识别物理随机性、路径簇和下一轮 RTL/实现策略；正式竞赛 bitstream
必须由对应 RTL 从头执行一次 full implementation，且该次 full run 自身满足全部门禁。

## 四、候选选择规则

候选编号、当前状态、默认开关和可归因效果以
[optimization-candidates.md](optimization-candidates.md) 为唯一总账；本文不维护第二份候选状态。

Linux 正确性和已稳定的 cache/TLB/AXI 语义是硬门槛。发现新的取消/完成、epoch、
forwarding、dirty writeback 或 uncached ordering 问题时，即使只来自一个定向测试，也
先记录、缩小并回归；单个孤例不足以否定性能候选，但在证据闭合前不能进入综合。

性能方面优先选择能解释 IPC 或执行时间的候选：前端供给和预测命中、wakeup/select
拥塞、LSQ/内存服务、DIV latency/throughput、ROB/PRF 容量和 cache critical return。
最终以 `cycle_count / actual_cpu_mhz` 判断，IPC 只是归因指标。

direct full implementation 的 top-N 路径用于给时序候选排序和验证物理效果，不是候选
准入的必要条件。若静态结构能够证明一条实时输入或宽控制依赖与已注册 owner/context
语义等价，且替换后不增加流水级、握手间隔或恢复延迟，即使它尚未出现在 top-N 中，也可
作为周期透明候选进入验证链。此类候选仍必须覆盖 owner 复用、backpressure、flush/cancel
等边界，检查生成 RTL 的目标依赖确已移除，完成 perf20 A/B，并由后续 matching direct full
判断物理价值；不能仅凭 RTL 直觉宣称时序收益。

## 五、清理规则

`make clean` 只删 `build/` 可再生输出、CPU 编译输出、仿真输出和 Vivado 临时工程；
不删 `.vscode/`、`.bsp/`、`.metals/`、`.scala-build/`、`Post_Impl_Bundles/`、
`Stable_Backup/`、Chiplab toolchains 或任何嵌套仓库源码。`make clean-ide-state` 是
显式 IDE 状态清理；根 Makefile 不清理或同步 Chiplab 子模块。需要改变子模块内容时
必须在其仓库内显式操作，并单独记录 gitlink、分支和 dirty 状态。

SBT 的普通编译、测试报告和 SpinalSim 工作区统一位于 `cpu/target/`；生成并发布的
RTL 仍位于 `build/rtl/`。`cpu/project/target/` 是 SBT 读取 `build.properties` 时产生的
一级元构建输出，属于正常结构。当前工程没有 SBT 插件，因此 `cpu/project/` 只保留
`build.properties`，不会再形成容易误解的 `cpu/project/project/`。上述两个 `target/`
均被忽略，并由 `make clean-cpu` 清理；根目录不再使用 `build/cpu/`。
