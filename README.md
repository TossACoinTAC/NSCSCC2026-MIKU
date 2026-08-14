# MIKU

MIKU（MIKU IS KINDA UNORDERED）是面向龙芯杯决赛 Linux 目标的 LA32R 乱序 SoC CPU。
源码使用 SpinalHDL，平台来自锁定的 Chiplab c398 快照。

先运行：

```text
git submodule update --init
make doctor
make env-build
make cpu-check
```

随后按需运行 `make perf20-sim`、`make func58-sim`、`make soc-impl`。完整的微架构教学和
缩写表见 [`docs/architecture.md`](docs/architecture.md)，性能候选账本、状态和实测效果见
[`docs/optimization-candidates.md`](docs/optimization-candidates.md)；成熟优化循环
与并行调度见 [`docs/verification-workflow.md`](docs/verification-workflow.md)，当前动态轮次见
[`docs/current-optimization-plan.md`](docs/current-optimization-plan.md)，本次目录和
工具链迁移的验证结果见 [`docs/migration-validation.md`](docs/migration-validation.md)。
