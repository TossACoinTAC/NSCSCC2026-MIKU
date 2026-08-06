# Function/Performance 构建合同

Chiplab 在综合时通过 `chip/soc_demo/nscscc-team/soc_config.vh` 选择测试宏，
所以运行时只替换 `program.bin` 不会把 SoC 变成另一种官方测试配置。

| 项目 | Function | Performance |
| --- | --- | --- |
| 宏 | `RUN_FUNC_TEST` | `RUN_PERF_TEST` |
| CPU 时钟 | 平台默认，当前 c398 测得 32.726797 MHz | `perf_clock.json` 生成，默认 100 MHz |
| AXI/RAM | function 随机 backpressure 和地址映射 | performance delay/address 配置 |
| 软件 | `nscscc_func/obj/main.bin` | `nscscc_perf/obj/allbench/inst_data.bin` |
| 主要证据 | func58 正确性、异常和 Linux 鲁棒性 | perf20 周期、频率、资源和时序 |

两种 bitstream 的 wrapper、PLL、约束、布局布线和内存时延都不同。跨模式运行只能用来
定位 hang 或观察指令路径，不能用来比较周期、频率、得分或替代匹配模式板测。

本地策略是：性能候选优先做 100 MHz performance SoC；Linux、cache/AXI、reset 和
发布里程碑补做 clean function SoC。两种 Vivado implementation 不并行；DCP 不能跨
function/performance 使用。只有 setup/hold 非负、DRC 0 error、bitstream 成功的 matching
构建才可作为发布候选。
