# CI Func 时序深入分析

本文专门分析 CI 功能配置（Func）与性能配置（Perf）在 Vivado P&R 中的
WNS 演进。这里的 Func 是软件功能验证配置，不应被误解为“只检查一个
约 33 MHz 的 CPU 时钟”。

## 结论先行

CI Func 最终 `setup WNS=-0.322 ns`，CI Perf 最终
`setup WNS=-0.124 ns`。Func 比 Perf 差 `0.198 ns`，但这不能归因于
CPU 核在 32.726797 MHz 下仍然无法运行。当前证据支持以下判断：

1. 设计同时约束 `cpu_clk=32.726797 MHz`、`sys_clk=100 MHz` 和
   `ddr_clk=200 MHz`。Vivado 汇报的是全设计最差 WNS；CPU 降频只会放宽
   CPU 域，不能放宽系统和 DDR 域。
2. Func 的第二次 route 中，hold 修复把 setup WNS 从 `-0.126 ns` 拉低到
   `-0.558 ns`，最终 route 只恢复到 `-0.322 ns`。这是已被日志直接证实的
   setup/hold 竞争，不是推测。
3. Func 的 timing summary 已确认最终最差路径属于 `sys_clk`，不是 CPU 域：
   `sys_clk WNS=-0.322 ns`，而 `cpu_clk WNS=+6.988 ns`、`ddr_clk WNS=+1.996 ns`。
   最差路径从 DDR AXI CDC 的 Gray pointer 到 JTAG AXI TX FIFO，route delay
   占数据路径 `81.803%`。
4. CI Func 与 CI Perf 使用同一 published RTL、同一 Chiplab、同一器件和
   同一 P&R 策略；本地 `origin/main` stable 又逐项复现了 CI Perf 的结果。
   因此主要变量是 Func/Perf 时钟 profile 及其引起的时钟树、布局和路由
   竞争，而不是源码或 runner 的输入不一致。

## 时钟约束的实际含义

`chiplab/fpga/nscscc-team/constraints/soc_lite.xdc` 创建了三个 generated
clock：`cpu_clk`、`sys_clk`、`ddr_clk`，并用 `set_clock_groups -asynchronous`
将三组时钟彼此隔离。这个约束的含义是：跨组数据路径不做普通 setup/hold
分析；每一组内部仍然独立检查，而且总 WNS 取所有被分析时钟组中的最差值。

两种 CI 配置的实际时钟为：

| 配置 | CPU | 系统 | DDR | 对时序的直接影响 |
| --- | ---: | ---: | ---: | --- |
| Func | 32.726797 MHz（周期约 30.555 ns） | 100 MHz（10 ns） | 200 MHz（5 ns） | 只放宽 CPU 域；系统/DDR 约束不变 |
| Perf | 100 MHz（10 ns） | 100 MHz（10 ns） | 200 MHz（5 ns） | CPU 域也成为 100 MHz 约束 |

Func timing summary 的 per-clock 结果为：`cpu_clk=+6.988 ns`、
`ddr_clk=+1.996 ns`、`sys_clk=-0.322 ns`（13 个失败 endpoint，TNS
`-1.527 ns`）。因此 Func 的聚合负 WNS 已经可以确定来自 `sys_clk`。

Perf 的 matching timing summary 则为：`cpu_clk=-0.124 ns`（14 个失败
endpoint）、`ddr_clk=+2.150 ns`、`sys_clk=+1.365 ns`。Perf 的总 WNS
确实由 CPU 域主导；Func 的总 WNS 则由系统域主导。

## Func 的 P&R 演进

Func 日志包含两次 route 过程。第一次完成后，增量布局触发第二次 route；
因此不能把第一次过程中的 `+0.978 ns` 当作最终闭合结果。

### 第一次 route

| 阶段 | WNS (ns) | TNS (ns) | 说明 |
| --- | ---: | ---: | --- |
| 初始 timing update | +0.978 | 0 | 尚未完成实际全局布线，且 hold 仍为大规模未修复状态 |
| global iteration 0 | -0.737 | -8.279 | 初始布线暴露真实 route delay |
| global iteration 1 | -0.685 | -6.569 | rip-up/reroute 开始恢复 |
| global iteration 2 | -0.640 | -6.236 | |
| global iteration 3 | -0.443 | -3.353 | |
| global iteration 4/5 | -0.405 | -2.565 | setup 收敛到局部稳定点 |
| delay cleanup | -0.340 | -1.720 | |
| hold fix | -0.340 | -1.720 | WHS `+0.007 ns`，hold 转正 |
| 第一次 route 完成 | -0.340 | -1.720 | 随后进入增量布局 |

第一次 route 的全局 routing utilization 只有垂直 `20.7133%`、水平
`24.0966%`，但局部 1x1 congestion 最大达到约 `94.12%`。这说明平均
利用率低并不等于关键局部没有绕线压力。

### 增量布局后的第二次 route

增量布局报告了 `Post Placement WNS=+0.978 ns`。这是 placement 阶段的
估计值，尚未经过第二次完整 route，不能与最终 `Post Routing WNS` 比较。

| 阶段 | WNS (ns) | TNS (ns) | WHS (ns) | 说明 |
| --- | ---: | ---: | ---: | --- |
| 第二次 route 初始 timing | +0.978 | 0 | -1.545 | hold 尚未修复 |
| global iteration 0 | -0.191 | -1.595 | - | route 重新暴露 setup 代价 |
| global iteration 1 | -0.191 | -1.595 | - | |
| delay cleanup | -0.126 | -0.792 | - | setup 暂时达到 Func 过程中的最好点 |
| 第一次 hold fix | -0.126 | -0.792 | -0.009 | hold 尚未完全闭合 |
| **additional hold fix** | **-0.558** | **-7.104** | **+0.050** | setup 明显被牺牲以修 hold |
| final route timing | -0.558 | -7.104 | - | 仍处于中间验证点 |
| **Post Routing** | **-0.322** | **-1.527** | **+0.007** | 最终 CI Func 结果 |

在 additional hold fix 后，Vivado 输出了以下警告：

```text
The router encountered 2 pins that are both setup-critical and hold-critical
and tried to fix hold violations at the expense of setup slack.
ddr3.u_axi_wrap_ddr/u_Axi_CDC/wFifo/popToPushGray_buffercc/
  pushCC_pushPtr[3]_i_3/I3
ddr3.u_axi_wrap_ddr/u_Axi_CDC/wFifo/popToPushGray_buffercc/
  pushCC_pushPtr[3]_i_4/I2
```

这条信息是 Func 恶化的最强直接证据：路由器为完成 hold 约束，改变了
DDR AXI CDC 附近的物理路径，setup 总 slack 随之下降 `0.432 ns`；后续
route 重新整理后只回收了 `0.236 ns`。最终 Func 的 hold 虽然为正，setup
却没有回到 additional hold fix 之前的 `-0.126 ns`。

## Func 最差路径的具体归属

Func timing summary 将最终 setup 违例明确归入 `sys_clk`：

```text
From Clock:  sys_clk
To Clock:    sys_clk
Slack:       -0.322 ns
Source:      ddr3.../u_Axi_CDC/wFifo/pushCC_pushPtrGray_reg[1]/C
Destination: u_jtag_axi_wrap/.../tx_fifo_i/.../ram_empty_fb_i_reg/D
Data Path Delay: 10.089 ns
Logic:           1.836 ns (18.197%)
Route:           8.253 ns (81.803%)
Logic Levels:    9
```

路径的物理含义是：`sys_clk` 下 DDR AXI CDC 的写侧 Gray pointer，经过
`pushCC_full`、AXI crossbar 的写就绪逻辑和 JTAG AXI TX FIFO 的 empty
反馈，最终到达同一 `sys_clk` 域的 FIFO 状态寄存器。它不是 CPU datapath，
也不是 DDR `ddr_clk` 域内部的 5 ns 路径；DDR 模块通过 CDC 逻辑把长距离
控制网络带入了 100 MHz 的系统域。

additional hold fix 中被点名的两个 setup/hold-critical pin 正好位于这条
CDC 控制锥的前段，所以“hold 修复造成 setup 恶化”与最终 `sys_clk` 违例
在结构上相互吻合。这里的证据已经足以完成 Func 的主要归因。

## Perf 的 P&R 演进

Perf 也经历了两次 route，但第二次 route 的 hold 修复没有出现 Func 那样的
setup 崩落：

| 阶段 | WNS (ns) | TNS (ns) | WHS (ns) | 说明 |
| --- | ---: | ---: | ---: | --- |
| 第一次 route 初始 timing | +0.195 | 0 | -1.594 | |
| 第一次 global iteration 0 | -0.840 | -93.731 | - | 高度拥挤的初始 route 状态 |
| 第一次 delay cleanup | -0.371 | -13.422 | - | |
| 第一次 hold fix | -0.371 | -13.358 | -0.022 | |
| 第一次 route 完成后 placement | -0.223 | - | - | |
| 第二次 route 初始 timing | -0.126 | -0.727 | -1.594 | |
| bus-skew update | -0.126 | -0.159 | - | |
| global iteration 0/1 | -0.171/-0.212 | -2.510/-4.631 | - | |
| delay cleanup | -0.126 | -0.771 | - | |
| hold fix | -0.126 | -0.771 | +0.050 | hold 一次性闭合，setup 未恶化 |
| **Post Routing** | **-0.124** | **-0.780** | **+0.050** | 最终 CI Perf 结果 |

Perf 最终 route 的全局 routing utilization 为垂直 `28.6818%`、水平
`32.8901%`，4x4/8x8 局部 congestion 最大约 `90.81%`。Func 的局部
1x1 峰值约 `94.12%` 与 Perf 的 4x4 峰值不能直接作数值排名，但两者都
说明“全局平均 utilization”不能替代局部拥挤和关键路径分析。

## Func 与 Perf 的归因边界

### 已确认

- **不是 RTL 身份差异。** CI Func/Perf 使用同一 published RTL；CI Perf
  与本地 `origin/main` stable 的 source tree、raw RTL、published RTL、
  Chiplab、Vivado、器件和 P&R 策略均匹配，最终 WNS/TNS/WHS/THS 也逐项
  相同。
- **不是“CPU 33 MHz 仍然超频”。** Func 的 CPU 域有 `+6.988 ns` setup
  裕量；负 WNS 来自 `sys_clk` 的 DDR AXI CDC/JTAG AXI 控制路径。
- **Func 存在实际的 hold/setup 竞争。** additional hold fix 前后 WNS
  从 `-0.126` 变为 `-0.558`，并且有 setup-critical/hold-critical 的
  DDR AXI CDC 引脚警告。

## 最终判断

CI Func 的 `-0.322 ns` 不是一个“33 MHz CPU 仍达不到”的简单结论。更准确
的描述是：Func 的 CPU 域 setup 裕量为 `+6.988 ns`，真正的最差路径属于
`sys_clk`，从 DDR AXI CDC 的 Gray pointer 跨过 AXI/JTAG FIFO 控制逻辑，
route delay 占 `81.803%`。第二次 route 的 additional hold fix 又将这条
平台控制路径所在的 setup 总 slack 拉低，最终形成 `-0.322 ns`。Perf 的
最差路径则在 `cpu_clk`，所以降 CPU 频率并不能改善 Func 的这条系统域路径。
