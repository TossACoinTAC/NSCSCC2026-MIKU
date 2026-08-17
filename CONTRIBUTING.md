# MIKU 协作说明

## 分支与提交

- CPU、工具、文档和实验改动使用独立 `dev/*` 分支。
- 不在同一个提交中混合 CPU 行为变化、文档重排和性能参数调整。
- 不重写其他成员的未提交修改。
- `main` 只接收已经满足对应验证级别的提交。

## 修改前

```text
git status --short --branch
make doctor
make test-impact TEST_BASE=origin/main
```

先确认目标文件、当前 CPU source tree、生成 RTL 和已有证据身份。历史性能数字不能用于新的
RTL，除非能够证明 matching 身份。

## 最低检查

纯文档修改：

```text
make docs-check
git diff --check
```

GitHub Actions 会使用完整 Git 历史在 Python 3.9 下重复运行
`make docs-check`，但远程结果不代替提交前的本地检查。

脚本或合同修改：

```text
make cpu-contract-test
make docs-check
git diff --check
```

CPU 或生成器修改：

```text
make cpu-test-all
make cpu-contract-test
make cpu-locked-gates CUSTOM_PROFILE=disabled
make evidence-current
git diff --check
```

`make evidence-current` 会根据已经存在且通过的 Scala XML、RTL generation manifest、三项
gate summary 和确定性 Python contract 日志更新仓库内摘要。不要手工填写测试数量或复制旧
CPU identity。大型原始产物继续保存在被忽略的 `build/` 和 `cpu/target/`。

实际需要运行的定向测试由 `make test-impact` 确定。性能或 release 提交还必须遵循
[docs/release-checklist.md](docs/release-checklist.md)。

## 性能结论

性能报告必须写明：

- CPU commit 和 source tree SHA256；
- raw RTL 与 published RTL SHA256；
- Chiplab commit 和软件身份；
- 工具版本、seed、频率和 profile；
- perf20 分项、总周期、资源、setup WNS 和 hold WNS；
- FPGA job ID 和产物 SHA256（如适用）。

没有 matching Vivado 结果时，只能报告本地功能和 RTL 检查。没有 matching FPGA 结果时，
不能报告板上功能或 perf20 通过。

当前机器可读记录只接受三个硬件阶段均为 `not_run`。以后导入新硬件结果时，先单独评审
原始 artifact 格式和身份关系，再扩展 schema；不能只填写结果数字或复用历史摘要。

## 自定义指令

正式题面公布前保持 `ContestCustomInstructionProfiles.Available` 为空。比赛当天新增 profile 时，
每条指令必须有定向 VerificationCase，并使用独立 profile 生成 RTL。往届具体指令不进入最终
catalog。
