# MIKU 团队开发契约

## 目标与边界

本仓库面向龙芯杯决赛要求，功能终点是软浮点 LA32R Linux 启动并完成
`docs/linux-system-requirements.md` 中的操作。正确性、完整 SoC 实现和时序
收敛优先于性能；性能以 benchmark 周期数和可实现 CPU 频率的乘积衡量。

本地环境是 WSL2 Ubuntu。SpinalHDL、ScalaTest、Verilator、Yosys、Python
和 LA32R 软件工具链在锁定的 Docker 镜像中运行。Vivado 2023.2 在 WSL
运行，Surfer 在 Windows 宿主机运行，路径由 `config/local.env` 配置。
本机仿真、综合和生成 bitstream 都不等同于板测；板测只能使用团队远程
LabAgent 并保存可追溯结果。

## 目录所有权

- `cpu/` 是唯一的 SpinalHDL CPU 源码边界，入口是 `cpu/build.sbt`。
- `scripts/` 只做跨仓库编排，按环境、CPU、仿真、Chiplab、Vivado 和板卡
  分组；不放 RTL。
- `config/` 保存仓库锁和本机路径示例；真实 `local.env` 不入库。
- `cpu/target/` 保存标准 SBT 编译、测试和 SpinalSim 输出；`build/` 只保存
  跨模块可再生输出，分为 `rtl/`、`gates/`、`sim/`、`chiplab/`、
  `vivado/`、`reports/`、`tmp/`。
- `Post_Impl_Bundles/` 与 `Stable_Backup/` 是受保护的历史证据，默认清理不碰。
- `chiplab/`、`nscscc-linux-kernel/`、`fpga-lab-agent/` 是 Git submodule；
  根仓库只锁定 gitlink，不接管其分支、dirty patch、清理或源码历史。
- 初赛提交仓库及 team-ci 不再是决赛开发依赖。若目录仍存在，只作为用户
  自行管理的独立克隆，根 Makefile 不引用它们，也不注册为 submodule。

## 变更纪律

修改前先检查根仓库和目标仓库的 branch、HEAD、dirty 状态。保留用户已有
改动，不用 reset、checkout 或宽范围删除来“整理”工作树。每个性能候选
都要记录源码树 hash、生成 RTL hash、软件 hash、seed、周期、频率、资源和
setup/hold WNS/TNS；不能把旧 RTL 的时序或性能证据带到新 RTL。后布线物理
优化只用于探索路径与物理收敛空间，其 DCP 和 bitstream 不具备正式竞赛产物资格；
正式 bitstream 必须由 matching RTL 从头执行一次完整 implementation，并由该次 run
自身满足功能、时钟、setup/hold、DRC 和 fully-routed 门禁。

SpinalHDL 是 RTL 唯一来源，禁止手改生成的 `build/rtl/mycpu_top.v`。公开
`core_top`、AXI、debug/commit、reset 和 `TLBNUM=32` 接口保持兼容。Vivado
或 Chiplab 的 IP 只能作为 CPU 本地、可重建且支持 Verilator 的依赖；不得
替换平台 PLL、DDR、AXI、JTAG 和板卡 IP。MIKU 当前不需要 CPU-local SRAM
XCI，Chiplab 的平台 XCI 保持原位。

## 测试与优化循环

测试是黑盒合同，不依赖内部 Spinal 模块名、源码行号、绝对路径、生成日期、
精确 warning 行数或 Git 历史。失败先归类为 `config`、`harness`、`artifact`
或 `DUT`。源码变更后必须经历：变更分类、根据 `cpu/tests/manifest.yml`
做影响分析、适配 schema/fixture/解析器、先跑 baseline、再跑候选、最后保存
哈希证据。不得通过删除断言、放宽超时或屏蔽 warning 修复测试。

测试设施修改必须面向稳定的公开接口、结构化结果和硬件外部输入语义，并对后续
源码演进保持向前兼容；不得为某次源码重命名、某个内部层级或单个 benchmark 添加特判。
适配完成后必须先证明旧 baseline 仍通过，再用真实端到端 workload 验证候选；源码
文本匹配不能替代运行时合同。

便宜检查顺序是 Scala/合同测试、RTL 生成和静态门禁、Verilator 定向和多 seed、
func58/perf20/Linux 软件仿真、Vivado SoC 实现、必要时后仿真，最后才是远程
板卡。Scala/SBT 同一工作树只运行一个实例；Vivado 实现独占主要资源；隔离
Verilator runtime 默认最多两个，只有 `free -h` 的 `available` 和实测峰值满足
合同才升到三个。长任务，比如等待 Vivado约三分钟轮询。

## 常用入口

使用根 `Makefile`：`doctor`、`status`、`ide-setup`、`cpu-test`、`cpu-check`、
`cpu-generate`、`sim-prepare`、`sim-matrix`、`func58-sim`、`perf20-sim`、
`linux-sim`、`soc-impl`、`soc-postroute-opt`、`wave` 和分模块 `clean-*`。
`soc-postroute-opt` 仅生成探索证据。禁止直接调用系统 SBT、
Verilator 或 ad hoc 工具路径绕过 Docker 锁。
