# 自定义指令最简 Make 流程

本文只描述新增自定义指令后的最短验证、仿真、SoC 实现和归档流程。

## 使用前提

先在 `cpu/src/main/scala/miku/core/ContestCustomInstructionProfiles.scala` 注册正式
profile 和验证用例，再在根目录 `Makefile` 顶部把：

```make
CUSTOM_PROFILE := disabled
```

改为源码中实际注册的 profile 名称。这是 Make 流程唯一需要修改的 profile 选择入口。
profile 名称必须和源码 catalog 中的名称一致；Makefile 不包含指令编码和执行逻辑。

## 最短流程

```bash
make doctor
make custom-check
make func58-sim SIM_REBUILD=1
make func-release
```

各步骤含义如下：

- `doctor` 检查 Docker、工具路径和嵌套仓库状态。
- `custom-check` 运行自定义指令测试，并生成 RTL、执行端口检查、Verilator lint 和 Yosys 检查。
- 替换 Chiplab 或首次生成新的仿真模型时，第一次 `func58-sim` 使用 `SIM_REBUILD=1`；同一环境后续运行 `make func58-sim` 即可。
- `func-release` 自动完成 RTL 生成、experiment freeze、func SoC full implementation 和归档。

`EXPERIMENT_ID`、`EXPERIMENT_MANIFEST`、`SOC_EXPERIMENT_MANIFEST`、
`EXPERIMENT_EVIDENCE` 和 Chiplab 路径都有 Makefile 默认值。最短流程不需要手填这些变量，
也不需要运行 `perf20-sim`、`linux-sim` 或 `soc-impl`。

板卡上传由外部 LabAgent 完成；提交板测任务后只使用根 Makefile 查询：

```bash
make board-status BOARD_JOB=<id>
make board-result BOARD_JOB=<id>
```
