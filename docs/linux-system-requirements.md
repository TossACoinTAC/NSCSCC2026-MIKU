# 非浮点 LA32R Linux/完整系统实现要求

## 1. 范围和结论

本文定义本项目面向 2026 年 CPU 设计赛（龙芯杯）团体赛、Linux 和完整系统运行时的 CPU/SoC 工程边界。

本文明确排除 FPU、浮点寄存器、浮点 CSR 和浮点上下文切换。软件使用不宣告 FPU 的 CPU 配置和 soft-float ABI。

目标边界为：

> 完整实现 LA32R r1p04 的 67 条非浮点指令，并增加软件探测所需的 `CPUCFG`，合计 68 条。

赛事“LoongArch 基准指令集”只覆盖其中 55 条主体指令；Linux/完整系统需要额外的原子、栅障、Cache、TLB、IDLE 和能力探测指令。`NOP`、`MOVE`、`LI.W`、`LA` 等伪指令不单独计数。

## 2. 依据

- [2026 年团体赛技术方案](https://www.nscscc.com/?p=837)：基准集不含浮点和 TLB/MMU；系统测试分别评价 bootloader、教学操作系统、Linux、Linux 完成指定操作。
- [龙架构 32 位精简版参考手册 r1p04](https://www.loongson.cn/uploads/images/2025032109211238668.%E9%BE%99%E6%9E%B6%E6%9E%8432%E4%BD%8D%E7%B2%BE%E7%AE%80%E7%89%88%E5%8F%82%E8%80%83%E6%89%8B%E5%86%8C_r1p04.pdf)：指令、特权级、异常、CSR、TLB 和存储管理依据。龙芯官网下载异常时，可使用 [Loongson 官方公开文档](https://github.com/loongson/LoongArch-Documentation) 核对通用 LoongArch 定义。
- [Chiplab Linux 运行指引](https://gitee.com/loongson-edu/chiplab/blob/chiplab_diff/docs/FPGA_run_linux/linux_run.md)：典型链路是 SPI Flash 中的 PMON/U-Boot、DDR3、UART、以太网/TFTP 加载 `vmlinux`。
- [官方 U-Boot](https://gitee.com/loongson-edu/la32r-uboot)、[Linux](https://gitee.com/loongson-edu/la32r-Linux)、[Buildroot](https://gitee.com/loongson-edu/la32r-buildroot) 和 [soft-float 工具链](https://gitee.com/loongson-edu/la32r-toolchains/releases)。

后续正式通知和决赛自定义指令说明优先于本文。

## 3. 68 条指令

### 3.1 基础整数、分支和访存：46 条

| 类别 | 指令 |
| --- | --- |
| 算术和立即数 | `ADD.W`、`SUB.W`、`ADDI.W`、`LU12I.W`、`PCADDU12I` |
| 比较 | `SLT`、`SLTU`、`SLTI`、`SLTUI` |
| 逻辑 | `AND`、`OR`、`NOR`、`XOR`、`ANDI`、`ORI`、`XORI` |
| 乘除 | `MUL.W`、`MULH.W`、`MULH.WU`、`DIV.W`、`DIV.WU`、`MOD.W`、`MOD.WU` |
| 移位 | `SLL.W`、`SRL.W`、`SRA.W`、`SLLI.W`、`SRLI.W`、`SRAI.W` |
| 分支跳转 | `BEQ`、`BNE`、`BLT`、`BLTU`、`BGE`、`BGEU`、`B`、`BL`、`JIRL` |
| 普通访存 | `LD.B`、`LD.BU`、`LD.H`、`LD.HU`、`LD.W`、`ST.B`、`ST.H`、`ST.W` |

### 3.2 例外、计数器和 CSR：9 条

| 类别 | 指令 |
| --- | --- |
| 主动例外 | `SYSCALL`、`BREAK` |
| 稳定计数器 | `RDCNTVL.W`、`RDCNTVH.W`、`RDCNTID` |
| CSR | `CSRRD`、`CSRWR`、`CSRXCHG` |
| 例外返回 | `ERTN` |

### 3.3 Linux/完整系统扩展：13 条

| 类别 | 指令 |
| --- | --- |
| 预取 | `PRELD` |
| 原子访存 | `LL.W`、`SC.W` |
| 栅障 | `DBAR`、`IBAR` |
| Cache 维护 | `CACOP` |
| TLB 维护 | `TLBSRCH`、`TLBRD`、`TLBWR`、`TLBFILL`、`INVTLB` |
| 等待 | `IDLE` |
| 能力探测 | `CPUCFG` |

## 4. 关键系统语义

### 4.1 `PRELD`

允许实现为无访存、无异常、无副作用的提示性空操作。无论是否预取，都不得产生 MMU、地址或对齐例外。

### 4.2 `DBAR/IBAR`

- `DBAR 0` 等待此前全部 load/store 完成，并阻止后续 load/store 提前开始；其他未专门支持的 hint 至少按 hint 0 处理。
- `IBAR 0` 保证此前 store 的结果可被后续取指观察。
- 完成条件必须覆盖 store buffer、未决 miss/refill/writeback、AXI 事务、I-cache 失效和前端重取。
- 只在 ROB 头提交或只 flush 流水线不等价于栅障完成。

### 4.3 `LL.W/SC.W`

- `LL.W` 建立 LLBit 和保留地址；`SC.W` 仅在保留有效且地址满足约束时写内存。
- `SC.W` 成功返回 1、失败返回 0，执行后清除保留。
- `ERTN` 按 `LLBCTL.KLO` 规则处理 LLBit，`LLBCTL.WCLLB` 可清除 LLBit。
- 其他核或一致性 I/O master 对保留粒度内地址完成 store 时使保留失效。
- uncached 地址上的 LL/SC 不作为可靠原子操作。

### 4.4 `CACOP`

- `code[2:0]` 区分 L1I、L1D、共享 L2，`code[4:3]` 区分 Store Tag、Index、Hit 操作。
- 数据或混合 Cache 的一致性操作在需要时先写回脏行再失效；I-cache 至少完成失效。
- Hit 类进行地址翻译并能产生 TLB 例外；Index 类按 VA 的 way/index 定位。
- 若用全 Cache 操作代替逐行操作，不得丢失无关脏数据，不得改变异常和完成语义。
- 维护完成前，重定向后的访存/取指不得绕过维护。

### 4.5 TLB 指令

- `TLBSRCH` 用 `ASID` 和 `TLBEHI.VPPN` 搜索并更新 `TLBIDX`。
- `TLBRD` 按索引回读完整表项，无效项按手册设置 `NE` 并清零相关字段。
- `TLBWR` 按指定索引写入，`TLBFILL` 选择合法索引填入。
- `INVTLB` 实现操作数 0 至 6；其他操作数产生非法指令例外。
- 修改主 TLB 后清除 iTLB/dTLB、负项缓存和所有旧翻译请求状态。

### 4.6 `IDLE` 和 `CPUCFG`

- `IDLE` 停止取指直到中断或复位，唤醒位置是其下一条指令。
- `CPUCFG` 对不支持索引读 0；报告的地址宽度、分页、计数器和 Cache 结构必须等于实际硬件。
- 未实现的 FPU、LSX/LASX 等能力必须报告为 0。

## 5. CSR、特权级和 MMU

| 类别 | CSR |
| --- | --- |
| 模式与异常 | `CRMD`、`PRMD`、`EUEN`、`ECFG`、`ESTAT`、`ERA`、`BADV`、`EENTRY` |
| TLB | `TLBIDX`、`TLBEHI`、`TLBELO0/1`、`ASID`、`TLBRENTRY` |
| 页表 | `PGDL`、`PGDH`、只读 `PGD` |
| 保存与编号 | `CPUID`、`SAVE0~3` |
| 定时器 | `TID`、`TCFG`、`TVAL`、`TICLR` |
| 原子 | `LLBCTL` 和内部 LLBit/保留地址 |
| Cache/映射 | `CTAG`、`DMW0/1` |

未实现或未定义 CSR 读 0、写忽略；非 FPU 实现的 `EUEN.FPE` 固定为 0。

必须实现：

- PLV0 内核态、PLV3 用户态和特权指令检查；
- 直接地址模式 `DA=1, PG=0`、分页模式 `DA=0, PG=1` 和两个 DMW 窗口；
- 32 项 TLB，4 KiB/2 MiB 页、双页表项、ASID、G/V/D/PLV/MAT；
- 分别检查取指/load/store 的地址合规、对齐、权限、有效位、脏位；
- TLB 重填进入时自动设置 `DA=1, PG=0` 并跳到 `TLBRENTRY`，`ERTN` 恢复分页；
- TLB 异常更新 `BADV` 和 `TLBEHI.VPPN`。

## 6. 异常和中断

| Ecode | 名称 | 含义 |
| --- | --- | --- |
| `0x00` | `INT` | 中断 |
| `0x01/02/03` | `PIL/PIS/PIF` | load/store/取指页无效 |
| `0x04` | `PME` | 页修改 |
| `0x07` | `PPI` | 页特权不合规 |
| `0x08` | `ADEF/ADEM` | 取指/访存地址错误 |
| `0x09` | `ALE` | 地址非对齐 |
| `0x0b` | `SYS` | 系统调用 |
| `0x0c` | `BRK` | 断点 |
| `0x0d` | `INE` | 指令不存在 |
| `0x0e` | `IPE` | 指令特权错误 |
| `0x3f` | `TLBR` | TLB 重填 |

异常/中断必须精确：更老指令完成，更年轻指令及其 GPR/CSR/TLB/Cache/AXI 副作用全部取消。实现 2 个软件中断、8 个硬件中断、定时器中断及 `ECFG/ESTAT/CRMD.IE` 屏蔽逻辑。

## 7. 存储系统和完整 SoC

- load/store 对齐、符号扩展和 byte enable 正确。
- uncached MMIO 不被合并或越过栅障；已退休 store 在异常恢复中不丢失。
- Cache refill、eviction、writeback、invalidate 不丢脏数据。
- AXI 在合法 backpressure、响应延迟和 ID 交错下保持归属正确。
- 非一致性 DMA/以太网由软件 Cache 维护；宣告一致性 I/O 时硬件参与 Cache 和 LL/SC 失效。
- 从 SPI Flash 进入 PMON/U-Boot，访问 128 MiB DDR3，通过 UART、定时器和外部中断工作。
- 通过以太网/TFTP 加载 ELF `vmlinux`，完成段复制、BSS 清零、Cache 同步和跳转。
- Linux 挂载 Buildroot/initramfs，进入可交互 shell，并完成文件、进程、内存、原子、定时器、串口、网络或存储操作。

外设属于 SoC 要求，不计入 68 条 CPU 指令，但直接决定系统测试结果。

## 8. 验收标准

只有同时满足以下各层，才能声明达到要求：

1. 68 条指令、必需 CSR、异常码和状态静态覆盖。
2. 基础功能测试满分，并通过 CSR、MMU/TLB、LL/SC、栅障、Cache、异常和中断定向测试。
3. 通过包含异常、TLB、Cache 和 AXI backpressure 的多 seed 体系结构差分测试。
4. 真实 FPGA 从 Flash 进入 PMON/U-Boot，UART、DDR3、计时器和网络正常。
5. 真实 FPGA 启动 soft-float Linux，进入用户态 shell。
6. 通过进程、系统调用、页错误、内存压力、原子、定时器、文件和网络测试。
7. 保存 RTL commit、bitstream、bootloader、kernel、rootfs、日志、命令和结果哈希。

性能测试、模块单测、elaboration、lint、综合、时序或 Linux 早期输出均不能单独构成完整验收。
