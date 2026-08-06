# MIKU 的 Linux 系统差距审计

> 功能验收合同以根工作区 `docs/linux-system-requirements.md` 为准。本文件只描述当前
> 提交候选，不继承其他 RTL hash 的时序、仿真或板测结论。

## 当前候选

| 项目 | 值 |
| --- | --- |
| CPU 源码 | `dev/ECHO @ 42d9f36` |
| 等价 RTL 逻辑提交 | `2c11ca6730a94b26e2de74eb33a4a1568ab9ad92` |
| 生成 RTL SHA-256 | `0625976bcfc430c7084f9e4dcb7bb7bf391714b474150b994aa29201526d986a` |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9` |
| 本地门禁 | 完整 `make cpu-check` 通过：39 个 Scala suites / 202 tests、364 个 Python tests、port/lint/Yosys/publication gates |
| Linux 总体验收 | **未满足**：尚无该 RTL 在真实板上进入交互 shell 并完成规定系统操作的证据 |

`42d9f36` 只把活动 Scala package 从 `openla500` 改为 `miku`；`core_top`、AXI、
debug/commit、`TLBNUM=32` 和生成 Verilog 逻辑保持不变。实现与板测必须绑定上述 RTL hash。

## 已实现的体系结构基础

- LA32R 整数、乘除、CSR、TLB、异常/中断、ERTN、IDLE、LL/SC、CACOP、DBAR/IBAR
  已进入译码、执行和精确提交路径。
- ROB 保证顺序提交和精确异常；RAT/PRF、IQ、LDQ/STQ/SDQ 的投机状态由统一 recovery
  epoch 和 flush 边界撤销。
- 32 项 TLB、直接地址/DMW/分页转换、权限和 dirty/MAT 检查覆盖取指与数据访问。
- DBAR/IBAR 等待旧 store、Cache 与 AXI 工作排空；CACOP 支持 Store Tag、Index 和 Hit
  语义，脏行在失效前写回。
- LL/SC reservation 使用 64 B cache-line 粒度；当前平台合同限定为单核、非一致性 DMA。
- uncached/MMIO store 以 AXI B 响应完成，错误响应形成精确异常；cached refill/writeback
  错误不允许静默安装或丢弃脏数据。
- CPUCFG 从固定微架构配置报告 ISA、TLB 和 Cache 能力；对外接口保持官方 Chiplab 合同。

这些条目表示 RTL 与定向合同已经存在，并不等于 Linux 已经启动到 shell。

## 当前证据边界

本轮最终时序候选的资源接受、ROB 槽位复用、L1I kill/turnover 和 IQ flush/payload
碰撞均有定向测试，并已进入完整本地门禁。按最终调度决定，本候选跳过 standalone
perf20；旧候选的 func58、perf20、Linux random-AXI、100 MHz WNS 和板测记录只作历史参考。

当前候选只有在匹配的完整 SoC 实现同时满足以下条件后，才能称为 100 MHz bitstream 候选：

1. 实际 CPU/sys/DDR 时钟与平台合同一致；
2. setup 与 hold slack 均非负；
3. DRC 0 Error、无 failed/unrouted/partial net；
4. bitstream 成功，并记录 CPU/RTL/bitstream hash。

## 剩余 Linux 验收

1. 在真实 FPGA 记录复位、DDR、UART、定时器/中断、bootloader、内核解包与入口。
2. 观察 MMU 和用户态切换，启动到 Buildroot/等价交互 shell。
3. 在 shell 中完成文件、fork/exec、页错误、原子、定时器及 I/O 操作。
4. 保存 UART、板测 Job ID、软件镜像、bitstream、CPU/RTL 与平台 hash。

只有上述端到端证据完成后，`linux_acceptance` 才能从 `not_satisfied` 改为通过。
