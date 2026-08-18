# 优化轮全局评价合同

本文定义性能与时序候选的统一比较口径。候选账本中的当前状态、候选机制和动态实验
结果仍以 [optimization-candidates.md](optimization-candidates.md) 为准；本文不替代
`verification-workflow.md` 的通用测试合同。

## 1. 身份边界

每一轮比较必须绑定同一 Chiplab commit、软件和工具身份，并保存 source tree、生成 RTL、
模型、软件、seed 和 implementation strategy 的哈希。候选不得继承旧 RTL 的 WNS、资源或
周期。Yosys 只用于结构预筛选和回归拒绝：它可以说明 generic cell、DFF、mux、memory bits、
逻辑深度和推断存储是否变化，不能证明 Vivado 的 LUT/FF/BRAM 映射、布局布线拥挤、路径迁移
或 WNS 改善。正式时序与 bitstream 证据只来自 matching RTL 的 direct full implementation；
post-route/phys-opt 仅用于探索。

## 2. 性能与频率指标

对 20 个 benchmark 使用逐项周期比较，并以几何平均定义 IPC 因子：

```text
IPC_factor = geomean(baseline_cycles_i / candidate_cycles_i)
```

`IPC_factor` 对 20 项等权，是与官方 perf20 评分一致的主指标。另记录
`TotalCycleFactor = sum(baseline_cycles_i) / sum(candidate_cycles_i)`，只用于描述当前
workload 组合的时间权重；它会被长 ROI 项主导，不能覆盖或替代 `IPC_factor`。按
[perf20-benchmark-patterns.md](perf20-benchmark-patterns.md) 汇总的控制流、规则吞吐、
load/store 和混合 kernel 簇只用于解释变化、发现一升一降的跷跷板，不构成候选准入分数。

在相同 100 MHz 约束下，同时记录 setup/hold WNS、setup/hold TNS、负端点数量、top200
路径中 slack 小于 0.1 ns 和 0.2 ns 的数量，以及每个路径族的 worst、median、P90 压力
slack、logic/route delay 比例。资源至少记录 LUT、FF、BRAM、DSP；实现报告可用时补充
CPU-only congestion、高扇出网和层级资源。

当前阶段使用同约束下的 headroom proxy，而不是把它当作正式 Fmax：

```text
Fproxy = 1000 / (10 - setup_WNS_ns) MHz
SystemScoreProxy = IPC_factor * Fproxy_candidate / Fproxy_baseline
```

候选平均性能回退小于 0.5% 时，只有在 `SystemScoreProxy > 1` 且 WNS/TNS/近临界端点
质量同步改善时才可保留；更大的回退必须有明确的系统收益和记录。周期透明候选要求
perf20 逐项精确相等。评价脚本输出 `schema_version`、输入路径、指标和 delta，作为实验
manifest 的引用证据。

## 3. 轮次门禁

单候选顺序为：影响分析、定向 Scala/合同测试、RTL 生成与 strict-zero lint、有限的 Yosys
结构检查、baseline/candidate perf20、必要的 func58 和 Linux smoke。Yosys 只在预期结构
没有实际形成，或出现明确的大规模组合/状态膨胀时拒绝候选；小幅 cell/bit/逻辑深度变化，
包括反直觉变化，不用于预测 WNS、排序候选或触发额外设计迭代。至少五个候选通过这些
静态与软件门禁后，再进行一次组合 direct full implementation。若某候选只让一个 top50
路径族退出，却使 top200 近临界质量、TNS、资源或跨模块布线恶化，应拒绝该候选。

100 MHz 里程碑要求 setup/hold WNS 非负、TNS 为零、DRC 0 error/critical warning、fully
routed 和 bitstream 成功。达到里程碑后才能以更高时钟验证真实 `Fmax × IPC`；板测是额外
功能/性能证据，不能替代 matching implementation。

## 4. 可复现入口

```text
make timing-analyze TIMING_REPORT=<top200.rpt> TIMING_OUT=<paths.json>
make optimization-evaluate \
  COMPARE_OUT=<perf-comparison.json> \
  BASE_TIMING_SUMMARY=<baseline/timing_summary.rpt> \
  CANDIDATE_TIMING_SUMMARY=<candidate/timing_summary.rpt> \
  BASE_TIMING_JSON=<baseline/top200.json> \
  CANDIDATE_TIMING_JSON=<candidate/top200.json> \
  BASE_UTILIZATION=<baseline/utilization.rpt> \
  CANDIDATE_UTILIZATION=<candidate/utilization.rpt> \
  OPTIMIZATION_METRICS_OUT=<scorecard.json>
```

输入缺失、身份不匹配、格式错误和 DUT 失败必须分别归类，不得通过放宽阈值或删除断言
掩盖问题。
