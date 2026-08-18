# perf20 Benchmark 模式参考

## 1. 用途与边界

本文把稳定版 perf20 的分项结果与测试程序的代码形态对应起来，作为后续微架构、时序和
性能实验的参考。这里记录的是可迁移的程序模式，例如规则数组循环、数据相关分支、串行
依赖和 load/store 配对，不把某个 benchmark 当成唯一优化目标，也不为某个输入构造专门
的硬件路径。

本文不是官方评分规则、RTL 规格或候选状态账本。候选的状态和门禁仍由
[optimization-candidates.md](optimization-candidates.md) 管理；测试执行合同见
[optimization-evaluation-contract.md](optimization-evaluation-contract.md)。

## 2. 稳定版证据

分析对象是
`Stable_Backup/cpu_10b988b_chiplab_c398d274812f_perf_100mhz_20260818-122325/`，证据身份见
[manifest.txt](../Stable_Backup/cpu_10b988b_chiplab_c398d274812f_perf_100mhz_20260818-122325/manifest.txt)
和
[experiment-manifest.json](../Stable_Backup/cpu_10b988b_chiplab_c398d274812f_perf_100mhz_20260818-122325/experiment-manifest.json)。

| 项目 | 稳定版证据 |
| --- | --- |
| CPU source commit | `5be1257ab976b849cf7bd8388f7badb7ab0da7aa` |
| CPU source tree | `a468f0903539382010e9476c95327aa6b6407ad37404fcf7e295cd081ce036d5` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| 频率 | current `100 MHz`; reference `32.726797 MHz` |
| 功能与实现 | perf20 `20/20`; fully routed; DRC 0 |
| 时序 | setup WNS `+0.113 ns`; hold WNS `+0.048 ns` |
| 运行策略 | 每项 2 次，均通过；两次都通过时选择 CPU 周期数较大的运行 |

原始分项数据见
[perf_score_details.csv](../Stable_Backup/cpu_10b988b_chiplab_c398d274812f_perf_100mhz_20260818-122325/T2026144230012607-perf/perf_score_details.csv)，
汇总口径见
[perf_score_summary.txt](../Stable_Backup/cpu_10b988b_chiplab_c398d274812f_perf_100mhz_20260818-122325/T2026144230012607-perf/perf_score_summary.txt)。

## 3. 计分字段如何阅读

CSV 中的 `ipc_ratio` 命名沿用了官方计分表，但其实际公式是：

```text
ipc_ratio = GEOMEAN(reference_cpu_cycles / current_cpu_cycles)
system_counter_ratio = GEOMEAN(reference_soc_count / current_soc_count)
```

因此本文将每一项的 `ipc_ratio` 称为 **周期比**：

- 大于 `1`：当前设计完成同一测试 ROI 所需的 CPU 周期更少；
- 小于 `1`：当前设计的周期数更多；
- 它不是硬件直接测得的 IPC，因为 CSV 没有 retired instruction、分支 miss 或 cache miss
  计数，也不保证参考实现与当前实现的指令流逐条相同。

系统计数器比还包含频率变化。当前频率因素为 `100 / 32.726797 = 3.055600x`，所以
`system_counter_ratio` 大致等于频率因素乘以周期比，剩余差异来自计数器边界和平台计数。
`cpu_to_soc_ratio` 的中位数为 `0.999647`，最小值为 `0.992668`，它是 CPU/SoC 计数器
一致性检查，不是“IPC 利用率”。

## 4. 分项模式映射

表中的“模式信号”只描述程序形态与结果之间的相关性。它不能单独证明某一条 RTL 路径是
瓶颈；因果归因需要额外的前端、后端、cache、LSQ 或时序 observer。

| benchmark | 当前 CPU cycles | 周期比 | 系统计数器比 | 源码形态与模式信号 |
| --- | ---: | ---: | ---: | --- |
| [bitcount](../chiplab/software/examples/nscscc_perf/bench/bitcount/bitcnts.c) | 240,726 | 1.427515 | 4.357527 | 7 种位计数算法、掩码、移位和短循环；位运算规则，分支大多局部可预测 |
| [bubble_sort](../chiplab/software/examples/nscscc_perf/bench/bubble_sort/bubble_sort.c) | 1,669,232 | 0.958078 | 2.927601 | `N=200` 的嵌套比较/交换；控制规则，但 swap 带来 load/store 和提交压力 |
| [coremark](../chiplab/software/examples/nscscc_perf/bench/coremark/shell3.c) | 3,961,090 | 1.042841 | 3.186926 | list、matrix、state 三类整数 kernel 的混合；结果不代表单一执行单元 |
| [crc32](../chiplab/software/examples/nscscc_perf/bench/crc32/crc32.c) | 1,783,202 | 1.448682 | 4.435848 | 256 项、32-bit CRC 表的逐字节更新；表查找与整数逻辑交替 |
| [dhrystone](../chiplab/software/examples/nscscc_perf/bench/dhrystone/dhry_1.c) | 40,074 | 1.162874 | 3.580456 | 过程调用、指针/结构体、枚举和字符串控制；ROI 很短，计数器边界影响相对更大 |
| [quick_sort](../chiplab/software/examples/nscscc_perf/bench/quick_sort/quick_sort.c) | 2,307,843 | 0.853409 | 2.608371 | `N=1000` 的 partition、递归和数据相关双向扫描；分支方向、递归深度和访问位置变化 |
| [select_sort](../chiplab/software/examples/nscscc_perf/bench/select_sort/select_sort.c) | 718,277 | 1.449310 | 4.427440 | `N=200` 的规则最小值扫描，每轮一次交换；比 quick sort 更容易形成稳定控制流 |
| [sha](../chiplab/software/examples/nscscc_perf/bench/sha/sha.c) | 2,069,791 | 1.058307 | 3.245620 | SHA-1 80 轮 `A/B/C/D/E` 递推；算术量大，但跨轮串行依赖限制 ILP |
| [stream_copy](../chiplab/software/examples/nscscc_perf/bench/stream_copy/stream_copy.c) | 114,619 | 1.139837 | 3.492353 | 单纯 `c[j] = a[j]` 顺序复制；load/store 配对和唯一 LSU 端口的吞吐更直接 |
| [stringsearch](../chiplab/software/examples/nscscc_perf/bench/stringsearch/bmhsrch.c) | 736,555 | 0.789854 | 2.438886 | Boyer-Moore-Horspool 可变步长、字符表索引和候选串回退；不规则控制与访存结合 |
| [fireye_A0](../chiplab/software/examples/fireye/A0/A0.c) | 561,005 | 7.559200 | 23.048459 | 对 `10000` 范围内稀疏位置翻转后反复扫描；极端异常值，且官方 loop 只有 1 |
| [fireye_B2](../chiplab/software/examples/fireye/B2/B2.c) | 391,020 | 1.394890 | 4.262832 | 稀疏表构建与二分搜索；表访问规则部分较强，搜索方向依赖数据 |
| [fireye_C0](../chiplab/software/examples/fireye/C0/C0.c) | 1,012,230 | 0.668346 | 2.043813 | 两组 50 个双字符词、trie 构建和 DFS；指针/表项/分支都随输入变化 |
| [fireye_D1](../chiplab/software/examples/fireye/D1/D1.c) | 1,834,733 | 3.291272 | 10.056215 | 四个数组清零、492 个数据更新和顺序扫描；规则数组访问与整数比较占主导 |
| [fireye_I2](../chiplab/software/examples/fireye/I2/I2.c) | 3,067,756 | 1.273416 | 3.891076 | `32x19` 网格、模式/矩形搜索和提前退出；嵌套循环中包含数据相关边界 |
| [inner_product](../chiplab/software/examples/nscscc_perf/bench/inner_product/shell16.c) | 4,868,044 | 3.334550 | 10.188295 | `8000` 元素的 int8/uint8/int16/uint16 内积；规则乘加和归约，独立操作较多 |
| [lookup_table](../chiplab/software/examples/nscscc_perf/bench/lookup_table/shell17.c) | 1,443,416 | 2.049321 | 6.261637 | `SIZE=1000` 输入，256 项 u8 表和 8192 项 u16 表，含 inplace/out-of-place；主要反映索引、宽度和局部性 |
| [loop_induction](../chiplab/software/examples/nscscc_perf/bench/loop_induction/shell18.c) | 3,820,048 | 2.901732 | 8.866853 | `3200` 元素 int32 copy，ROI 还包括随机填充与 `memcmp` 校验；不是纯计数循环 |
| [my_memcmp](../chiplab/software/examples/nscscc_perf/bench/my_memcmp/shell19.c) | 1,186,324 | 2.861917 | 8.744044 | 4 KiB/8 KiB 等长比较，常见路径直到末字节才不同；连续 load、比较和循环退出 |
| [minmax_sequence](../chiplab/software/examples/nscscc_perf/bench/minmax_sequence/shell20.c) | 2,011,933 | 1.959720 | 5.987669 | 多种整数宽度的 min/max 与位置查找；顺序扫描、比较和条件更新反复出现 |

测试程序的重复次数也属于模式的一部分：`nscscc_perf/machine.h` 设置一般项
`LOOPTIMES=10`，而 `fireye_A0` 固定为 1、`fireye_C0` 固定为 4。Fireye 其余程序还在
各自源文件中定义了算法级 `LOOP`。因此短 ROI 或低重复次数的项目不能和长循环项目用同一
种统计稳定性解释。

## 5. 可迁移的模式簇

### 5.1 规则整数、顺序数组和归约

代表项是 `bitcount`、`crc32`、`select_sort`、`fireye_D1`、`inner_product`、
`loop_induction`、`my_memcmp` 和 `minmax_sequence`。它们共同包含较多的：

- 连续地址递增或固定步长访问；
- 可以提前进入窗口的整数操作；
- 容易学习的循环回跳；
- 较少的长距离控制依赖。

这一簇的高周期比说明当前 OOO 后端、整数执行流水线和常见缓存命中路径能够把独立操作
重叠起来。`inner_product=3.335`、`loop_induction=2.902` 和 `my_memcmp=2.862` 是
最清楚的高吞吐样本；它们适合用来检查调度、乘法延迟、load-use 和窗口利用率是否在后续
候选中保持。

这不等于“所有内存程序都很快”。`stream_copy` 只有 `1.140`，因为每个元素同时需要
load 和 store，且当前配置要求唯一 LSU 端口处理这类工作。它是存储路径的保守基准，不能
用高分的内积或 memcmp 替代。

### 5.2 表索引和局部性

`crc32` 与 `lookup_table` 都使用查表，但它们暴露的硬件行为不同：CRC32 是逐字节更新的
单一状态递推，lookup_table 则包含 u8/u16、in-place/out-of-place、短表和长表。当前
lookup_table 的 u16 表为 8192 项，约 16 KiB，输入数组为 1000 项；它更适合观察
L1 容量/相联、地址生成和 byte/halfword load，而不是外部 DRAM 带宽。

因此一个“表访问优化”若只改善 CRC32，不能直接推广到所有表算法；至少要在两种表大小、
不同元素宽度和是否覆盖写的组合上验证。

### 5.3 数据相关分支、搜索和恢复

`bubble_sort`、`quick_sort`、`stringsearch`、`fireye_B2`、`fireye_C0` 和 `fireye_I2`
组成一个控制流较复杂的观察簇。它们的周期比从 `0.668` 到 `1.395`，整体明显弱于规则
数组簇。

这些程序会产生不同形式的前端压力：

- sort 的比较结果改变下一次循环或 partition 的路径；
- stringsearch 的 skip 表改变下一次候选位置；
- B2 的二分搜索改变左右边界；
- C0 的 trie/DFS 同时改变分支方向和表项地址；
- I2 的提前退出让内层循环长度变化。

它们适合回答“预测错误、redirect/recovery、load-to-use、IQ/ROB 等待是否占主导”，但
汇总周期本身不能区分这些原因。一个分支预测器候选应至少同时观察 `C0`、stringsearch
和一种规则回跳程序，避免把单个搜索程序的输入特征误认为普遍收益。

### 5.4 串行依赖和混合 kernel

`sha`、`dhrystone` 和 `coremark` 的周期比接近 1，说明窗口增大或发射端口增加并不会
自动转化成等比例收益：

- SHA-1 的每轮状态强依赖上一轮结果；
- Dhrystone 频繁调用过程、访问结构体和处理小段控制流；
- CoreMark 将 list、matrix、state 三类行为混在一起，单项结果会掩盖子测试差异。

这一簇适合做综合回归和平均退化守门，不适合据此断言某个单一执行单元或单一预测器的
收益已经被充分利用。

### 5.5 A0 异常值

`fireye_A0=7.559` 是当前结果中最突出的离群点。它只占当前总 CPU 周期约 `1.66%`，
却使 CPU 周期几何平均从不含 A0 的约 `1.454x` 提升到 `1.579x`。A0 因此具有很高的
官方分数敏感度，但不应被当作一般循环、一般扫描或一般分支预测能力的代表。

后续对 A0 的正确用法是固定源码、输入、ROI 和编译产物做复现，并把它作为回归项；不能
围绕 A0 单独添加专用识别或改变通用路径优先级。

## 6. 对优化实验的使用方式

下表是模式级的测试映射，不是 benchmark 特判清单。

| 候选问题 | 首选观察簇 | 必须同时保护的对照簇 | 需要补充的证据 |
| --- | --- | --- | --- |
| 分支预测、redirect、恢复 | C0、stringsearch、quick_sort | bitcount、stream_copy、coremark | fetch-side prediction、mispredict/recovery、分支类别和周期区间 |
| LSU、store、load-use、提交头 | stream_copy、my_memcmp、loop_induction | inner_product、C0、sha | load/store 接受、forwarding、LSQ occupancy、ROB-head blocked |
| 乘法、wakeup/select、执行吞吐 | inner_product、minmax_sequence、crc32 | sha、dhrystone、quick_sort | producer-consumer 间隔、端口利用率、依赖链周期、WNS |
| cache、表索引和局部性 | lookup_table、crc32、fireye_D1 | stringsearch、stream_copy | L1/L2 hit/miss、MSHR overlap、元素宽度和地址分布 |
| 前端供给和循环回跳 | bitcount、select_sort、D1 | C0、quick_sort、SHA | fetch group、taken prefix、redirect 次数和前端空泡 |
| 频率优化 | 以上各簇的代表项 | 完整 20 项 | matching RTL 的 top-N 路径、WNS/TNS、资源和周期 |

实验报告应写“该候选改善了哪一种模式，以及哪些相邻模式没有退化”，而不是写成
“针对 `C0` 的优化”或“针对 `A0` 的优化”。所有候选仍需回到完整 perf20、功能门禁和
matching implementation；ideal-memory perf20 可以用于周期回归，不能代替 Linux、随机
AXI 或真实平台证据。

## 7. 解释限制

1. 当前结果是相对 OpenLA500 参考的周期比较，不是同频率、同指令数的严格 IPC 测量。
2. perf20 使用 workload-specific ROI；初始化、校验和内部重复次数可能包含在计数范围内。
3. `cpu_to_soc_ratio` 只能验证计数器大体一致，不能解释前端、后端或内存停顿。
4. 当前 CSV 没有退休指令数、PHT/BTB 命中、cache miss、MSHR、IQ、LSQ 和 ROB 状态，
   所有微架构归因都应保留为“模式假设”，直到 observer 或 trace/replay 证实。
5. 20 个 benchmark 的官方几何平均等权；真实程序时间则由当前周期占比决定。当前总周期
   约 `33.84M`，`inner_product`、`coremark`、`loop_induction`、`fireye_I2` 四项约占
   `46.5%`，这两个排序目标不能混为一谈。

## 8. 维护规则

当 CPU、Chiplab、软件或 ROI 发生变化时，先更新证据身份，再更新表格。旧版周期比、WNS、
资源或软件形态不能自动继承到新 RTL。新增 benchmark 先标注其模式，再决定是否加入某个
解释簇；不要为了让某个候选显得有效而临时重分类。

