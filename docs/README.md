# MIKU 文档目录

本文档目录区分当前状态、设计说明、执行流程和历史研究记录。需要判断代码是否已经完成
Vivado 或 FPGA 验证时，应先阅读 [status.md](status.md)，不要从历史研究文件推断。

## 当前状态与入门

- [status.md](status.md)：当前 CPU 源码身份、已经运行的检查和仍未运行的硬件验证。
- [environment.md](environment.md)：本机、Docker、Vivado 和工具路径要求。
- [migration-validation.md](migration-validation.md)：仓库迁移后的验证记录。
- [release-checklist.md](release-checklist.md)：进入 `main` 或发布 FPGA 产物前的检查项目。
- [../CONTRIBUTING.md](../CONTRIBUTING.md)：分支、测试、证据和提交要求。

## CPU 与 SoC 设计

- [architecture.md](architecture.md)：微架构总览与分卷索引。历史讨论已按前端、Backend、
  存储、系统状态和 FPGA 验证分开；当前状态仍以 [status.md](status.md) 为准。
- [func-perf-build-contract.md](func-perf-build-contract.md)：func 和 perf 构建合同。
- [linux-system-requirements.md](linux-system-requirements.md)：Linux 功能目标和验收条件。
- [custom-instructions.md](custom-instructions.md)：决赛自定义指令 profile、编码和验证方法。

## 验证与性能研究

- [verification-workflow.md](verification-workflow.md)：测试、实验身份和硬件验证流程。
- [optimization-candidates.md](optimization-candidates.md)：候选编号、状态和已测结果。
- [timing-static-audit-r5.md](timing-static-audit-r5.md)：R5 静态时序分析。
- [current-optimization-plan.md](current-optimization-plan.md)：历史多轮执行计划，目前不作为
  最新状态来源。
- [research-20260816-execution-log.md](research-20260816-execution-log.md)：
  `c60dadd` 的 implementation 与 perf20 实测记录。

## 来源与历史

- [third-party-sources.md](third-party-sources.md)：外部依赖和已经核对的来源信息。
- [archive/](archive/)：迁移资料和不再作为当前状态使用的历史文件。

## 阅读顺序

1. 使用 [status.md](status.md) 确认当前验证范围。
2. 使用 [environment.md](environment.md) 和根 README 完成本地环境检查。
3. 使用 [architecture.md](architecture.md) 理解 CPU 设计。
4. 修改代码前阅读 [verification-workflow.md](verification-workflow.md) 和
   [../CONTRIBUTING.md](../CONTRIBUTING.md)。
5. 需要生成竞赛产物时执行 [release-checklist.md](release-checklist.md)。
