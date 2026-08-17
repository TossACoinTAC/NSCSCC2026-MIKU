# MIKU Release 检查清单

本文用于准备 `main` 里程碑、FPGA 产物或比赛提交。不同级别的结果必须分别记录。

## 1. 源码身份

- 工作区没有未提交修改。
- CPU commit 为完整 40 位 SHA。
- Chiplab、Linux 和 LabAgent submodule 与 `config/repositories.lock` 一致。
- `cpu/reference/manifest.lock` 中的工具版本已经核对。
- 当前 `CUSTOM_PROFILE` 已写入 generation manifest。

## 2. 本地功能检查

```text
make cpu-test-all
make cpu-locked-gates CUSTOM_PROFILE=disabled
make cpu-contract-test
make evidence-current
make docs-check
```

`make evidence-current` 只在前面的完整检查通过后执行。它更新小型摘要和确定性 Python
日志；`build/`、`cpu/target/` 及其大型原始产物继续保持忽略。

自定义指令 profile 还必须运行：

```text
make custom-test
make custom-check CUSTOM_PROFILE=<profile>
```

## 3. 软件仿真

- 根据 `make test-impact` 运行要求的定向测试。
- func58 使用规定 seeds 完成。
- perf20 完成全部 20 项，不遗漏 stringsearch。
- Linux 使用匹配的软件和 CPU 身份完成要求的验证范围。
- baseline 与 candidate 的软件、seed、模型和工具身份一致。

## 4. Vivado implementation

- 使用 Vivado 2023.2 和锁定器件。
- 从 matching published RTL 执行完整 implementation。
- fully routed、DRC 0 Error。
- setup WNS、hold WNS、TNS、资源和频率写入证据。
- post-route 探索结果不能替代完整 implementation。
- 当前 evidence schema 不接收新的硬件通过状态；原始 artifact 导入方式需要单独评审。

## 5. FPGA 验证

- 使用由 matching commit 构建的 `.fpgajob`。
- 保存 package SHA256、bitstream SHA256、job ID、终态结果和下载产物 SHA256。
- func 与 perf 结论分开记录。
- perf20 保存 20 项全部结果和双跑选择规则。
- 板上通过不能自动证明 Vivado 时序满足要求。
- 在导入规则完成前，`vivado_implementation`、`fpga_func` 和 `fpga_perf20` 保持 `not_run`。

## 6. 状态更新

- 更新 `evidence/index.json`。
- 更新 `docs/status.md`。
- 更新 README 当前状态表。
- 确认所有性能数字仍绑定正确的 commit 和 RTL。
- 历史记录只有汇总而没有原始文件时，明确标记 `summary_only` 和 `not_in_repository`。
- `git diff --check` 和 `make docs-check` 通过。

只有上述相关项目均有 matching 证据时，才能把源码称为对应级别的 release。
