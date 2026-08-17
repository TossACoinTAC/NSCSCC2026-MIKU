# 外部依赖与来源说明

本文区分“作为 submodule 或构建依赖使用”和“复制或改写实现”。只有能够从仓库元数据、
源码说明或提交历史核对的来源才写入确定结论。

## 锁定依赖

| 名称 | 来源 | 用途 | 锁定位置 |
| --- | --- | --- | --- |
| Chiplab | `https://gitee.com/loongson-edu/chiplab.git` | SoC、Verilator、Vivado 工程 | `config/repositories.lock` |
| Linux kernel | `https://github.com/Maotechh/nscscc-linux-kernel.git` | LA32R Linux kernel | `config/repositories.lock` |
| FPGA LabAgent | `https://github.com/negativegluon/fpga-lab-agent.git` | 远程 FPGA 服务 | `config/repositories.lock` |
| SpinalHDL | `com.github.spinalhdl` | CPU RTL 生成框架 | `cpu/reference/scala-dependencies.lock.json` |

这些项目作为依赖使用，不表示其实现已经复制到 `cpu/src/main/scala/miku/`。

## 复制或改写实现

当前根仓库尚未完成逐文件来源审计，因此本文不对 CPU 实现来源作未经核对的声明。后续确认
某段实现为复制或明显改写时，应增加以下信息：

- 本仓库文件和行级范围；
- 原项目、团队、URL 和 commit；
- 使用方式是复制、改写还是接口兼容；
- 原许可证和本仓库需要保留的声明；
- 对应提交。

仅用于学习、比较或独立重新实现的资料，不应写成“复制来源”。

## 已知研究资料

仓库文档曾比较公开的 NSCSCC、LoongArch、Buildroot、Linux 和其他团队实现。除非某个提交
能够证明存在复制或明显改写，否则这些资料只属于研究参考，不进入上一节。
