# MIKU 当前验证状态

本文只记录能够由提交、生成文件或测试结果核对的结论。机器可读版本保存在
[../evidence/index.json](../evidence/index.json)，当前本地检查摘要保存在
[../evidence/current/local-verification.json](../evidence/current/local-verification.json)，完整文档目录见
[README.md](README.md)。

## 当前 CPU 源码基线

| 字段 | 值 |
| --- | --- |
| CPU 功能提交 | `2650922a7245ec6dcf97209b3f92720403671f42` |
| 默认 profile | `disabled` |
| CPU source tree SHA256 | `bec6f629ee667e09d1f4ab81de78aa4cb027e370c4614b46039197844402a5a0` |
| raw RTL SHA256 | `b7d62d418c899f04e7d22ed78ff170fc8f968e40f823aed90838d71627c16df6` |
| published RTL SHA256 | `6a08b0dc21d16f9b9b2c2aa6136bd6973ed28184f58345928dda5fd1a47326ec` |

文档或工具提交可以在不改变 CPU source tree 的情况下继续前进，因此“当前仓库 HEAD”和
“当前 CPU 功能提交”不一定相同。

## 已经运行的检查

| 级别 | 结果 | 可以支持的结论 |
| --- | --- | --- |
| Scala／Verilator | 42 suites，256 tests passed，0 skipped | 已覆盖的 CPU 单元和集成行为通过 |
| Python contract | 82 passed，0 skipped | 已覆盖的配置、清单和脚本合同通过 |
| RTL 生成 | passed | `disabled` profile 可以生成完整 `core_top` |
| 公开端口 | passed | `core_top` 端口和 `TLBNUM=32` 符合锁定合同 |
| Verilator lint | passed，0 warnings | 发布 RTL 通过锁定 lint 规则 |
| Yosys | passed | 发布 RTL 可以被锁定 Yosys 读取和处理 |

这些结果不能证明 Vivado 时序、FPGA 功能、perf20 或 Linux release 已经通过。

仓库内摘要保存 Python contract 的 82 项确定性日志、42 个 Scala XML 的逐文件身份、
generation manifest 和三项 gate summary 的原始文件 SHA256，以及可跨工作目录比较的 CPU、
RTL、工具和 contract 字段。原始 `build/` 与 `cpu/target/` 文件仍被 Git 忽略并可重新生成。

## 当前尚未运行

| 状态字段 | 值 |
| --- | --- |
| `vivado_implementation` | `not_run` |
| `fpga_func` | `not_run` |
| `fpga_perf20` | `not_run` |
| `linux_release` | `not_run_for_this_cpu_source` |
| `performance_claim` | `none` |

`2650922` 没有以下 matching 证据：

- Vivado 2023.2 完整 implementation、资源、setup WNS 和 hold WNS；
- 远程 FPGA func 或 perf20 任务；
- 绑定该 CPU 源码身份的 Linux release 验证；
- 形式等价证明。

当前机器可读 schema 只允许三个硬件状态保持 `not_run`，并以 commit、CPU source tree、
published RTL 和本地运行输入哈希区分实现。新的 Vivado 或 FPGA 原始 artifact 导入方式需要
单独评审，不能只填写汇总数字。

## 最近一次完整硬件实测参考

该记录的证据级别为 `summary_only`。Vivado 和 LabAgent 原始文件均为
`not_in_repository`，因此下面内容是绑定 commit 与哈希的历史汇总，不是 fresh clone 可独立
重算的原始硬件证据。

以下数字属于 `c60dadd4fdc1c2ae3f7eb61a345a515e907ba93a` 及其发布 RTL
`e81fd3aa33da3c1987d0b1b23f9da2cc4d7813c675b6735a7dd722038556ed34`：

| 指标 | 结果 |
| --- | ---: |
| 仓库提交 | `8f33144de808d28bb287cd358afeef02f24c8336` |
| CPU 源码提交 | `c60dadd4fdc1c2ae3f7eb61a345a515e907ba93a` |
| published RTL SHA256 | `e81fd3aa33da3c1987d0b1b23f9da2cc4d7813c675b6735a7dd722038556ed34` |
| Chiplab commit | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| Vivado | `2023.2` |
| FPGA part | `xc7a200tfbg676-2` |
| implementation profile | `perf` |
| CPU frequency | 100 MHz |
| Setup WNS | `+0.018 ns` |
| Hold WNS | `+0.050 ns` |
| DRC | 0 Error |
| perf20 | 20／20 passed |
| Selected CPU cycles | `33,393,947` |
| Selected SoC cycles | `33,404,961` |
| LabAgent job | `20260817-030305-5a6c7f1c` |
| Package SHA256 | `79e5498ac53d47fdf653e6db597429dc99ef5b6fa9b3f153770fd1b57705809a` |

该 implementation 从 matching RTL 开始完整运行。`AggressiveExplore` 是同一次 full
implementation 内的 post-route 步骤，不是对旧 DCP 运行的独立
`soc-postroute-opt` 探索任务。

原始说明见 [research-20260816-execution-log.md](research-20260816-execution-log.md)。
这些数字只能作为比较参考，不能直接写成当前 CPU 源码的实测性能。

## 更新规则

1. 每次 CPU 源码变化后重新生成 RTL，并记录 source tree、raw RTL 和 published RTL SHA256。
2. 本地测试、Vivado、FPGA、perf20 和 Linux 分别记录，不把一个级别的结果扩大为另一个级别。
3. 性能数字只有在 commit、RTL、Chiplab、工具版本、频率和测试结果全部匹配时才更新。
4. 新的结果写入 `evidence/index.json`，随后更新本文和 README。
5. 完整本地检查通过后运行 `make evidence-current`，提交新的本地摘要和确定性 Python 日志。
