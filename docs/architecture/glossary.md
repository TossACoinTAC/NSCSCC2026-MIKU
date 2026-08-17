# MIKU 架构记录：缩写与术语对照

> 本文保留原架构长文中的术语表，具体参数和当前验证结论以
> [status.md](../status.md) 和实际源码为准。

[返回架构总览](../architecture.md)

## 附录 A：缩写与术语对照

本表以本项目当前实现和本学习记录中的用法为准。`I$`、`D$` 是工程中常见的
cache 简写；`p` 前缀表示 physical（物理）而不是 architectural（架构）寄存器。

### A.1 前端与控制流

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| IBUF | Instruction Buffer | 暂存从取指侧送来的指令槽，向三路 decode 提供顺序指令。 |
| PC | Program Counter | 当前指令地址；预测、取指、异常和 branch redirect 的核心身份。 |
| BTB | Branch Target Buffer | 按 branch PC 记录目标、类型等信息的预测表。 |
| PHT | Pattern History Table | 保存 2-bit 饱和计数器的条件分支方向预测表。 |
| GHR | Global History Register | 最近条件分支方向的全局历史，用于 PHT 索引。 |
| RAS | Return Address Stack | 为 call/return 嵌套保存返回地址的小栈。 |
| BTFNT | Backward Taken, Forward Not Taken | 静态方向预测规则：向后 branch 预测跳转，向前 branch 预测不跳转。 |
| MPKI | Mispredictions Per Kilo Instructions | 每千条指令的预测错误数；这里通常指 branch MPKI。 |

### A.2 Rename、乱序窗口与执行

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| uop | Micro-operation | 后端跟踪的动态操作；当前一个 LA32R 指令通常对应一个 uop。 |
| Macro-op fusion | Macro-operation Fusion | 在不改变两条架构指令身份的前提下，让相邻指令共享后端执行项；若进一步共享 ROB entry，还需表示两次架构退休。 |
| Fused uop | Fused Micro-operation | 后端承载两条架构指令语义的复合执行项；本笔记的 FUS01 首先考虑两个 ROB entry 共用一个 fused uop。 |
| RAT | Register Alias Table | 架构寄存器到最新物理寄存器的映射表，也称 rename map。 |
| PRF | Physical Register File | 保存 `p0..p63` 实际数据值的物理寄存器文件。 |
| GPR | General-Purpose Register | LoongArch 的通用架构寄存器 `r0..r31`。 |
| `psrc` | Physical Source Register | uop 的物理源寄存器编号，如 `psrc1`、`psrc2`。 |
| `pdst` | Physical Destination Register | uop 新分配的物理目标寄存器编号。 |
| `oldPdst` | Old Physical Destination Register | 写目标前原有映射，在该写指令 commit 后才可归还 FreeList。 |
| ROB | Reorder Buffer | 按程序顺序保存 in-flight uop 的完成、异常和提交信息，提供精确状态。 |
| IQ | Issue Queue | 等待操作数 ready 和执行端口可用的发射队列。 |
| WB | Writeback | 执行结果经过完成校验后写入 PRF，并把物理寄存器标记为 ready。 |
| EX | Execution | uop 被功能单元接受并进行计算或启动多拍操作的执行阶段。 |
| CAM | Content-Addressable Memory | 按内容并行匹配的存储思想；IQ wakeup 的多 tag 对多 `psrc` 比较具有 CAM 式行为。 |
| FU | Functional Unit | 执行单元，如 ALU、branch、MUL/DIV、LSU、CSR 单元。 |
| ALU | Arithmetic Logic Unit | 整数算术、逻辑、比较和部分地址/目标计算单元。 |
| AGU | Address Generation Unit | 计算 load/store 虚拟地址的地址生成单元。 |
| MUL | Multiply | 整数乘法操作；当前使用可每拍接收、一级结果寄存的 DSP 路径。 |
| DIV | Divide | 整数除法/求余操作；当前使用逐位迭代除法器。 |
| II | Initiation Interval | 同一流水单元连续接受两项新工作的最小周期间隔。 |
| RAW | Read After Write | 真数据相关：消费者必须等生产者的结果。 |
| WAR | Write After Read | 反相关：rename 通过分配新 `pdst` 消除。 |
| WAW | Write After Write | 输出相关：rename 通过分配不同 `pdst` 消除。 |

### A.3 访存与系统状态

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| LSU | Load-Store Unit | 负责 load/store 地址、访存请求、转发和完成的执行路径。 |
| LSQ | Load-Store Queue | load/store 的统一乱序跟踪结构；本项目由 LQ、STQ 等状态构成。 |
| LQ / LDQ | Load Queue | 跟踪在途 load 的年龄、地址、完成和顺序约束。 |
| STQ | Store Queue | 跟踪在途 store 的地址、字节使能、提交可见性等状态。 |
| SDQ | Store Data Queue | 独立保存等待生产者结果的 store data，允许地址和数据就绪解耦。 |
| VA / PA | Virtual Address / Physical Address | MMU 翻译前后的地址；LSQ alias 判断最终必须与物理内存位置一致。 |
| VPN / VPPN | Virtual Page Number / Virtual Pair Page Number | 虚页号/虚双页号；VPPN 省略用于选择奇偶 half 的那一位。 |
| PPN | Physical Page Number | TLB half 中的物理页号，与 VA 的页内 offset 拼接成 PA。 |
| ASID | Address Space Identifier | 地址空间标识，允许不同进程的相同 VA 在 TLB 中并存；global entry 忽略它。 |
| MAT | Memory Access Type | CRMD、DMW 或页表/TLB 中的存储访问类型；LA32R 精简版中 0 为 SUC、1 为 CC、2/3 保留。 |
| SUC | Strongly-ordered UnCached | 强序非缓存访问；不可推测，按程序次序直接访问最终存储对象，完成前不能开始下一访存。 |
| CC | Coherent Cached | 一致可缓存访问；允许由维护一致性的 cache 服务。 |
| MMIO | Memory-Mapped Input/Output | 用普通 load/store 地址访问设备寄存器；通常映射为 SUC。 |
| DMA | Direct Memory Access | 由设备 master 在内存与外设间批量搬运数据；非一致 DMA 需要软件 cache 维护。 |
| PIPT | Physically Indexed, Physically Tagged | cache 的 index 和 tag 都取自物理地址；当前 L1I/L1D/L2 属于这一类。 |
| MSHR | Miss Status Holding Register | 合并并跟踪同一 cache miss 的未完成请求及 refill 状态。 |
| MLP | Memory-Level Parallelism | 同时在途且可重叠服务的独立访存数量。 |
| AMAT | Average Memory Access Time | 由各级 hit time、miss rate 和 miss penalty 加权得到的平均访存时间。 |
| CWF | Critical-Word-First | refill burst 优先返回 demand 所需 word/beat，再完成其余 line。 |
| Early Restart | Early Restart | 关键数据到达后立即恢复 CPU，不等待整条 cache line 安装完成。 |
| WB（cache policy） | Write-Back | Store 先更新 cache dirty line，通常在该 line 被替换或维护时才写下一级；与流水线 Writeback 缩写同名。 |
| WT | Write-Through | 更新某级 cache 时同时把数据写向下一级；当前 L2 接收 L1D eviction 使用该策略。 |
| I$ / L1I | Instruction Cache / Level-1 Instruction Cache | 一级指令 cache。 |
| D$ / L1D | Data Cache / Level-1 Data Cache | 一级数据 cache。 |
| L2 | Level-2 Cache | 片上二级 cache，本项目承担 L1 miss 的后续服务。 |
| AXI | Advanced eXtensible Interface | SoC 内 CPU、cache、DDR 等之间使用的 AMBA 总线协议。 |
| BRESP | Write Response | AXI B 通道返回的写事务响应；当前 uncached Store 等它返回后才 completion。 |
| Posted Write | Posted Write | 写请求进入不可撤销缓冲后先向 CPU 宣告完成、再在后台到达设备；会改变错误归因与 drain 合同。 |
| MMU | Memory Management Unit | 处理分页、地址翻译、权限和相关异常的单元。 |
| ATU | Address Translation Unit | 本项目接受 I/D VA 请求，选择 direct/DMW/TLB 路径并产生 PA、MAT 与翻译异常。 |
| TLB | Translation Lookaside Buffer | 缓存虚拟页到物理页翻译结果的小型表。 |
| uTLB / micro-TLB | Micro Translation Lookaside Buffer | I/D 各自的 4-entry 热翻译缓存；miss 后由共享 Main TLB walker 填充。 |
| DMW | Direct Mapping Window | 映射地址模式下优先于 TLB 的直接映射窗口，配置 VSEG/PSEG、PLV 许可和 MAT。 |
| PLV | Privilege Level | LoongArch 特权等级；本项目实现 PLV0 内核态与 PLV3 用户态。 |
| CSR | Control and Status Register | 控制、异常、特权、计时器和 MMU 状态寄存器。 |
| ECODE / ESUBCODE | Exception Code / Exception Subcode | ESTAT 中记录的异常主码/子码，用于区分中断、页错误、对齐、非法指令等。 |
| TLBR | TLB Refill Exception | Main TLB 无匹配项时的重填异常，跳转到独立 TLBRENTRY。 |
| PIL / PIS / PIF | Page Invalid for Load / Store / Fetch | 命中 TLB entry 但对应 half 的 V=0 时，按访问类型产生的页无效异常。 |
| PME / PPI | Page Modification Exception / Page Privilege Illegal | store 页不可写、或当前 PLV 权限不足时的翻译异常。 |
| LL/SC | Load-Linked / Store-Conditional | 原子读-条件写指令对；SC 是否成功依赖 reservation。 |
| DBAR / IBAR | Data Barrier / Instruction Barrier | 数据/指令屏障，约束访存或取指可见性与完成顺序。 |
| CACOP | Cache Operation | 指定 cache 层、Store Tag/Index/Hit 模式和目标地址的架构 cache 维护指令。 |
| INVTLB | Invalidate TLB | 按 op 0~6 的 global/ASID/VA 条件失效主 TLB，并清除派生 micro state。 |

### A.4 性能、验证与 FPGA 实现

| 缩写 | 英文全称 | 本项目中的含义 |
| --- | --- | --- |
| IPC | Instructions Per Cycle | 每周期提交的指令数；本核理论提交上限为 3。 |
| CPI | Cycles Per Instruction | 每条指令平均周期数，近似等于 IPC 的倒数。 |
| ROI | Region of Interest | 一次实验中纳入周期、指令和事件统计的明确区间；起止 milestone 必须固定。 |
| DUT | Design Under Test | 被仿真或验证的处理器/SoC 设计；probe 只能观察 DUT，不能反馈控制。 |
| Fmax | Maximum Frequency | 时序约束下可达到的最高稳定时钟频率。 |
| WNS | Worst Negative Slack | 所有 setup/hold 路径中最差的时序余量；负值表示未收敛。 |
| TNS | Total Negative Slack | 所有负 slack 的总和，用于衡量整体时序违例规模。 |
| LUT | Look-Up Table | FPGA 的组合逻辑基本资源。 |
| FF | Flip-Flop | FPGA 的时序存储基本资源。 |
| BRAM | Block RAM | FPGA 的片上块存储资源。 |
| DSP | Digital Signal Processing Slice | FPGA 的乘加等专用算术资源。 |
| DRC | Design Rule Check | Vivado 对实现、时钟、IO 等设计规则的检查。 |
| DCP | Design Checkpoint | Vivado 保存的设计检查点，可用于实现或增量实现参考。 |
| RTL | Register-Transfer Level | 描述寄存器与组合逻辑间时序行为的硬件设计层次。 |
| SoC | System on Chip | CPU、cache、DDR、外设与互连整合后的完整芯片系统。 |
| CDC | Clock Domain Crossing | 跨时钟域信号传递；需要专门的同步或异步 FIFO 设计。 |
