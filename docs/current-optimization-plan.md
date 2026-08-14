# MIKU 当前多轮优化计划

本文是动态执行清单，随候选和 matching implementation 结果更新。长期不变的测试、归档、
并发和发布合同见 [verification-workflow.md](verification-workflow.md)，候选机制、状态与实测
效果见 [optimization-candidates.md](optimization-candidates.md)。

## 当前基线与目标

- CPU 开发分支：`dev/ECHO`。
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`。
- perf20：`5,306,558` cycles，20/20 pass。
- func58：random-AXI seeds `240/255/141` 通过。
- 100 MHz direct full implementation：setup `-0.552 ns`、hold `+0.018 ns`、DRC
  0 error/critical warning、fully routed、bitstream 成功。
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

## R1：周期透明时序候选

按 `BT01 -> MT01 -> MT02 -> FT02 -> FT03` 线性累积。每个节点独立提交，并执行受影响
suite、完整 `cpu-check`、完整 perf20 20/20 和相对前一节点的 A/B。五项结束后运行 func58
三 seed，再执行一次 100 MHz direct full implementation。

理想结果是 perf20 20 项逐项相等。最终组合允许归一化几何平均性能回退小于 `0.5%`，但
必须记录全部分项，并与目标路径族、WNS、TNS 和资源变化交叉验证。周期改善的候选转为性能
候选记录；超过允许回退的候选退出 R1 组合，除非独立实现证据证明它是 100 MHz 闭合所必需。

R1 晋级要求是 func58 三 seed通过、direct full setup/hold 非负、DRC 0 error/critical warning、
fully routed 和 bitstream 成功。正 WNS 不用于升频。

当前进度：

- `BT01 @ fc73f5b`：IssueQueue 定向测试和完整 `cpu-check` 通过；perf20 相对
  `5,306,558` 基线 20 项逐项精确相等，几何平均 `1.000000000x`。该节点作为
  周期透明的 R1 累积基线保留，matching implementation 效果待五项组合后统一验证。
- `MT01`：下一节点，尚未形成候选 RTL。

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

## 系统、归档与发布

MMU、cache、AXI、异常或内存顺序发生变化的轮次运行 Linux；其他候选在每个时序闭合
里程碑运行 Linux。多轮期间只保存和推送 `dev/ECHO`。至少三轮 IPC 完成、最终组合通过
100 MHz direct full implementation 和 Linux 后，才将 `main` fast-forward 到相同提交。
板侧服务器恢复后补做板测；此前只标记本地稳定里程碑。
