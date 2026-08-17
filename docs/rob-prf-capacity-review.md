# ROB/PRF 容量与物理实现复盘

最后同步：2026-08-17。本文记录 R13 后对 reorder buffer（ROB）和 physical register file
（PRF）的容量收益、物理代价与后续结构选择。它是 [optimization-candidates.md](optimization-candidates.md)
的设计论证附录；候选状态和实测效果仍以账本为准。

## 结论

当前 64-entry ROB / 128-entry PRF（`expanded-window`）不能作为 100 MHz 的默认实现配置。
它在同一 RTL 上确有 `-2.104349%` 的 perf20 周期收益，但 R13 matching direct full 在 router
中达到 `135,525` 个 node overlaps 后中止，未产生 routed DCP、正式时序或 bitstream。相反，
同一源码的 32/64 `default` 配置可完全布线，且全程没有残留 node overlap；其 routed setup
WNS 是 `-0.527 ns`、hold WNS 是 `+0.051 ns`。因此扩容是拥挤失控的主要放大因素，但不是
default 仍有约 0.5 ns setup 缺口的唯一原因。

下一轮不直接删除容量路线，也不把 expanded-window 当作默认路线。工作分为两条：

1. 在不改变架构拍数的前提下，继续削减 ROB completion/tag 和 PRF read/bypass 的跨区网络；
   每项必须显示出局部结构变化，再进入 matching implementation。
2. 将 `64 ROB / 64 PRF` 保留为受控中间点。它已获得独立软件收益，但尚无 matching physical
   证据；只有在本地化候选不能把 64/128 拉回健康布线区时，才把它纳入下一次容量实现 A/B。

## 同源码容量 A/B

三个矩阵均来自 `dev/ECHO @ 31982c0`、10-bit B02-F、同一 Chiplab/software 身份，采用
ideal perf20 20/20。周期少于前者为改善。

| 配置 | 总周期 | 相对前一配置 | 几何平均加速 | 主要解释 |
| --- | ---: | ---: | ---: | --- |
| default，32 ROB / 64 PRF | 3,879,728 | 基线 | 1.000000x | 当前可实现的低容量对照 |
| expanded-rob，64 ROB / 64 PRF | 3,845,519 | -0.881737% | 1.009237x | ROB 容量有真实但有限的收益 |
| expanded-window，64 ROB / 128 PRF | 3,798,085 | -1.233488% | 1.012787x | PRF 余量带来额外收益；相对 default 累计 -2.104349% |

代表性收益集中在 `lookup`、`loop`、`minmax_sequence`、`bitcount` 等存在更长 rename/retire
窗口或更多并行临时值的 workload；这说明容量不是无效方向。它也不足以证明 64/128 的实现代价
合理，物理实现仍是硬门槛。比较证据是
`build/reports/comparisons/R13-default-vs-expanded-rob.json`、
`build/reports/comparisons/R13-expanded-rob-vs-expanded-window.json` 和
`build/reports/comparisons/R13-default-vs-expanded-window.json`。

## Yosys 与 Vivado 交叉解释

Yosys 只用于在同一 RTL 风格下筛选结构成本，不能替代器件资源、布局或 WNS。其当前结果如下：

| 配置 | 全核 cells | 全核 word bits | ROB cells / memory bits | PRF cells / word bits |
| --- | ---: | ---: | ---: | ---: |
| default | 57,660 | 389,771 | 6,067 / 19,200 | 1,314 / 19,549 |
| 64 ROB / 64 PRF | 61,884 | 396,454 | 10,291 / 38,400 | 1,314 / 19,549 |
| 64 ROB / 128 PRF | 63,164 | 420,628 | 10,291 / 38,528 | 2,274 / 36,001 |

64-entry ROB 首先增加约 4,224 generic cells；128-entry PRF 再增加 960 cells 和 16,452
word bits。这个趋势与 R13 placed DCP 的拥挤归属一致：东侧 global 区由 PRF（46%）、ROB（25%）
和 backend（17%）主导，北侧 short 区则由 PRF（84%）和 ROB（14%）主导。但它不能推导“每个
新增 cell 都造成 overlap”，因此所有后续保留决策仍以 matching direct implementation 为准。

R13 的 `RF05/RT18-RT21` 已使 expanded-window 的 Yosys 总 cells、ROB cells 和 word bits 降低，
却仍未获得健康布线；这说明仅把平坦译码拆成 bank-local logic 不足，跨模块的 completion/wakeup
与多读端 PRF 网络仍是更高优先级的对象。

## 已确认的路径族

- ROB `stagedPdst` 到 IQ 的 registered wakeup/select：tag 进入多个 IQ source compare 和 age/select
  锥，属于高 route-ratio 的跨区广播。
- IQ source tag 到 PRF 8R5W async read/bypass：每个读端读取四个 bank 后选择，再与五个 writeback
  tag 做 compare/forward；这是扩容 PRF 后最难布局的网络之一。
- ROB completion control 到 commit/head bypass：这是 default 仍存在的独立 setup 路径，不能归咎于
  容量。
- L1D/L2/LSQ 的 MSHR、refill 和 owner 网络也在 top-N 出现；R13 容量结论不应掩盖这些方向。

## 后续候选的边界

| ID | 动作 | 为什么可能改善物理实现 | 主要风险与门禁 |
| --- | --- | --- | --- |
| RF06 | 在已有本地 completion capture 边界内，让 dispatch、registered IQ wake、Store fallback 与 RenameMap 消费本地 pdst；ROB 仍唯一产生 valid/epoch/flush 资格 | 移除 ROB `stagedPdst` 到 backend/IQ 的 tag 广播，且不增加拍数 | poison completion tuple、stale epoch、flush、direct/registered wake 优先级、PRF bypass；完整 cpu-check、perf20 exact 与 matching top-N |
| RF07 | 尝试以 PRF bank/row 作为同拍 bypass 的物理边界 | 已否决：动态 row-bit 选择映射为 `shiftx/procmux`，PRF cells `+135`、raw LTP `9 -> 12`；未进入 perf20/Vivado | 保留 `a7b588a` 后由 `2edc9d6` 回退；证据为 `build/reports/yosys/RF07-bank-local-bypass-fresh-vs-RF06-default-local-tag.json` |
| RT22 | completion 只读取目标 pointer 的 resident state，再由既有 target token 写回 | ROB cells `6,067 -> 5,548`，移除 producer 对全 entry state 的扫描资格，不增加 completion/wakeup/commit 拍数 | `b5d1b65`；ROB 19/19、完整组合门禁通过；matching completion/commit top-N |
| RT23 | completion source 保持 3-bit 紧凑状态，commit 读侧用平衡 mask 选择 producer payload | ROB raw LTP `40 -> 39`；初版 one-hot state 的 word-bit 代价已由紧凑版本收回 | `9678a7d`；ROB 19/19；matching source/control top-N |
| RT24 | 三宽 commit 和 head-bypass 资格使用平衡 AND 树 | cell/word-bit 精确不变，ROB raw LTP `39 -> 33` | `1732ef5`；ROB 19/19；matching commit/head-bypass top-N |
| RT26 | 五路 head completion/branch payload 保持最高 lane 优先级的平衡选择 | ROB raw LTP `33 -> 32`；全核 cells `+40`、word bits `+478`，以小规模代价替换串行 mux 链 | `e04edc8`；ROB 20/20，新增双 lane payload/branch metadata 优先级合同；matching bypass top-N |
| RT25 | 把完整 exception metadata 移到“最老已知异常”sidecar，正常 ROB entry 仅保留必要 hot state | 降低 completion/commit 异常 payload mux 与每 entry 冷字段负担 | 精确异常、BADV、TLB refill、同拍多异常的 oldest 选择、branch recovery；这是高风险微架构改造，先设计/测试后实现 |

RF06+RT22+RT23+RT24+RT26 的最终 default 批次为 `e04edc8`。完整 `cpu-check` 为
40 suites / 263 tests、Python contracts 95/95；perf20 20/20 逐项精确等于 `3,879,728`
cycles，func58 seeds `240/255/141` 均为 58/58。相对 RF06 的同配置 Yosys 为：全核 cells
`57,660 -> 57,205`、word bits `389,771 -> 391,210`、post-flat cells `51,638 -> 51,183`、ROB
cells `6,067 -> 5,612`，ROB raw LTP `40 -> 32`。比较见
`build/reports/yosys/RT26-final-vs-RF06-default-local-tag.json`。这些结果证明 RTL 结构和软件门禁
均可接受，但是否保留仍取决于 matching direct route 的 ROB/PRF、commit 和 IQ 路径。

RF07 已由 Yosys 否决，不进入实现批次。RT25 sidecar 仍是下一轮高风险研究项，不与当前轻量候选
混写；若本批次仍无法让 default 在 100 MHz 闭合，再并行评估 RT25 与 `64 ROB / 64 PRF`，而不是
继续给 64/128 的集中式状态网络叠加局部补丁。

## 设计依据

BOOM 的 ROB 以 dispatch/commit 宽度组织 row，并只为最老已知异常保留完整 exception state；这说明
RT25 属于已验证的设计模式，但 MIKU 的三宽提交、四宽取指和现有 payload bank 不允许机械移植。
BOOM 同时采用显式 PRF 和静态 provisioned read ports；其文档也指出动态 port 调度需要额外阶段或
kill/reissue，因此 RF07 不能为了减少端口而静默增加依赖链 latency。

更一般地，Palacharla、Jouppi 与 Smith 指出 issue wakeup/select、旁路与多端口寄存器文件会共同成为
高频 OoO 的时钟瓶颈。这里的策略因此不是继续扩大集中式阵列，而是保留当前 ISA/提交语义，在既有
寄存边界上缩窄跨区 tag/data/control 网络。

- [BOOM Reorder Buffer](https://docs.boom-core.org/en/latest/sections/reorder-buffer.html)
- [BOOM Register File and Bypass Network](https://docs.boom-core.org/en/latest/sections/reg-file-bypass-network.html)
- [BOOM Rename Stage](https://docs.boom-core.org/en/latest/sections/rename-stage.html)
- [Palacharla, Jouppi, Smith: Complexity-Effective Superscalar Processors](https://people.eecs.berkeley.edu/~kubitron/courses/cs252-S07/handouts/papers/p206-palacharla.pdf)
