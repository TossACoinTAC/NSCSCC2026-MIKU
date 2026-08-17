# MIKU

<img width="418" height="235" alt="MIKU project mark" src="docs/assets/miku-project-mark.png" />

MIKU（MIKU IS KINDA UNORDERED）是面向龙芯杯决赛 Linux 目标的 LA32R
乱序 SoC CPU。CPU 使用 SpinalHDL 实现，SoC、仿真和 FPGA 工程来自锁定的
Chiplab 快照。

> 当前 CPU 源码基线为 `2650922`，默认 `CUSTOM_PROFILE=disabled`。
> 本地功能测试和 RTL 静态检查已经通过，并有仓库内验证摘要；但该源码身份尚无与之匹配
> （matching）的 Vivado
> implementation 或板上 perf20 结果。最近一次完整硬件实测仍属于
> `c60dadd`／`8f33144`。详细状态见 [docs/status.md](docs/status.md)。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| CPU 源码 | SpinalHDL，四取指、三译码／重命名／派发／提交，四执行端口 |
| 默认自定义指令 profile | `disabled`，不生成自定义指令电路 |
| Scala／Verilator | 42 suites，256 tests passed，0 skipped |
| Python contract | 82 passed，0 skipped |
| 端口、Verilator lint、Yosys | passed |
| `vivado_implementation` | `not_run` |
| `fpga_func` | `not_run` |
| `fpga_perf20` | `not_run` |
| `linux_release` | `not_run_for_this_cpu_source` |
| `performance_claim` | `none` |
| 最近硬件实测参考 | 100 MHz，setup WNS `+0.018 ns`，perf20 `33,393,947` CPU cycles |

机器可读状态保存在 [evidence/index.json](evidence/index.json)，本次本地检查的逐项摘要见
[evidence/current/local-verification.json](evidence/current/local-verification.json)。原始 Scala
XML、生成 RTL 和 gate 输出保持忽略并可重新生成。表中的最后一项是 `summary_only` 历史
matching 结果，其 Vivado 与 LabAgent 原始产物状态均为 `not_in_repository`，不能自动作为
当前 CPU 源码的性能结论。

## 证据身份

当前本地检查对应以下 CPU 源码和生成 RTL：

| 字段 | 值 |
| --- | --- |
| CPU 功能提交 | `2650922a7245ec6dcf97209b3f92720403671f42` |
| CPU source tree SHA256 | `bec6f629ee667e09d1f4ab81de78aa4cb027e370c4614b46039197844402a5a0` |
| raw RTL SHA256 | `b7d62d418c899f04e7d22ed78ff170fc8f968e40f823aed90838d71627c16df6` |
| published RTL SHA256 | `6a08b0dc21d16f9b9b2c2aa6136bd6973ed28184f58345928dda5fd1a47326ec` |
| RTL generation | passed |
| Verilator lint | passed，0 warnings |

验证摘要还保存 42 个 Scala report 的逐文件 SHA256、确定性的 Python contract 日志、
generation manifest SHA256，以及 port、lint、Yosys summary 的原始文件 SHA256 和可移植
关键字段。这些记录支持本地检查结论，不支持 Vivado、FPGA 或 Linux release 结论。

最近一次完整硬件实测对应另一份 CPU 源码和 RTL。该记录的证据级别为 `summary_only`，
Vivado 与 LabAgent 原始产物均为 `not_in_repository`：

| 字段 | 值 |
| --- | --- |
| 仓库提交 | `8f33144de808d28bb287cd358afeef02f24c8336` |
| CPU 源码提交 | `c60dadd4fdc1c2ae3f7eb61a345a515e907ba93a` |
| published RTL SHA256 | `e81fd3aa33da3c1987d0b1b23f9da2cc4d7813c675b6735a7dd722038556ed34` |
| Chiplab commit | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| Vivado／part／profile | `2023.2`／`xc7a200tfbg676-2`／`perf` |
| Setup／Hold WNS | `+0.018 ns`／`+0.050 ns` |
| DRC | 0 Error |
| perf20 | 20／20 passed |
| Selected CPU／SoC cycles | `33,393,947`／`33,404,961` |
| LabAgent job | `20260817-030305-5a6c7f1c` |
| Package SHA256 | `79e5498ac53d47fdf653e6db597429dc99ef5b6fa9b3f153770fd1b57705809a` |

## 快速开始

```text
git submodule update --init
make doctor
make env-build
make cpu-check
```

所有 Scala、Verilator、Yosys 和软件工具都应通过根 Makefile 调用，不直接使用系统工具
替代锁定环境。

## 常用任务

| 目标 | 命令 |
| --- | --- |
| 检查环境和子仓库 | `make doctor`、`make status` |
| 运行 CPU 完整本地检查 | `make cpu-check` |
| 运行自定义指令测试 | `make custom-test` |
| 生成指定自定义指令 profile | `make custom-check CUSTOM_PROFILE=<name>` |
| 运行 func58 软件仿真 | `make func58-sim` |
| 运行 perf20 软件仿真 | `make perf20-sim` |
| 运行 Linux 软件仿真 | `make linux-sim` |
| 运行完整 SoC implementation | `make soc-impl` |
| 查询远程 FPGA 队列 | `make board-queue` |
| 检查文档和证据入口 | `make docs-check` |
| 汇总最近一次本地检查 | `make local-evidence` |
| 更新仓库内本地验证记录 | `make evidence-current` |

运行 `make help` 可以查看全部受支持入口。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `cpu/` | SpinalHDL CPU 源码、测试和锁定工具信息 |
| `scripts/` | 环境、仿真、Vivado、实验和远程板卡脚本 |
| `docs/` | 当前状态、架构、验证、性能、Linux 和比赛说明 |
| `config/` | 本机配置示例和子仓库锁定信息 |
| `chiplab/` | 锁定的 SoC、Verilator 和 Vivado submodule |
| `nscscc-linux-kernel/` | 团队 LA32R Linux kernel submodule |
| `fpga-lab-agent/` | 远程 FPGA 服务 submodule |
| `build/`、`cpu/target/` | 可重新生成的本地输出，不进入 Git |

## 文档

从 [docs/README.md](docs/README.md) 开始阅读。主要入口包括：

- [当前验证状态](docs/status.md)
- [微架构说明](docs/architecture.md)
- [验证流程](docs/verification-workflow.md)
- [性能候选记录](docs/optimization-candidates.md)
- [Linux 系统要求](docs/linux-system-requirements.md)
- [自定义指令使用手册](docs/custom-instructions.md)
- [release 检查清单](docs/release-checklist.md)
- [外部依赖与来源说明](docs/third-party-sources.md)

## 证据要求

性能数字必须同时绑定 CPU 源码身份、生成 RTL SHA256、Chiplab commit、工具版本、
频率和测试结果。源码测试通过、RTL 生成成功、Vivado implementation 和 FPGA 板上通过
是四种不同结论，不能互相替代。当前机器可读状态不导入新的硬件通过结论，三个硬件阶段
保持 `not_run`；后续原始 artifact 格式经过单独评审后再扩展。具体要求见
[CONTRIBUTING.md](CONTRIBUTING.md) 和
[docs/release-checklist.md](docs/release-checklist.md)。
