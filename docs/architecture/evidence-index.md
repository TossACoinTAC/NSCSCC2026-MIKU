# MIKU 架构记录：证据索引

> 本文保留原架构长文中的证据路径和历史身份。当前验证结论见
> [status.md](../status.md)。

[返回架构总览](../architecture.md)

## 7. 证据索引

- 2nd-pass 语义审计区间：`cpu/` 的
  `d9bab16ef46540eb3348b0781afc4d0949f28adc..6bbca9b330ba8d886c888e2804f70b95be18e4cd`；
  后续验证证据的 source 为 `60fba481...`、implementation 为 `6bbca9b...`，不要用
  docs-only HEAD 替代 source identity 或扩大审计终点
- 指令融合的 ISA 语义：[龙架构 32 位精简版参考手册 r1p04](https://www.loongson.cn/uploads/images/2025032109211238668.%E9%BE%99%E6%9E%B6%E6%9E%8432%E4%BD%8D%E7%B2%BE%E7%AE%80%E7%89%88%E5%8F%82%E8%80%83%E6%89%8B%E5%86%8C_r1p04.pdf)
  第 2.1.4、2.2.1.2--2.2.1.8、2.2.3、2.2.4、6.1 和 6.2.3 节；FUS01 结构判断另核对
  `OooUops.scala`、`ReorderBuffer.scala`、`OooBackend.scala` 与 `OooCommitAdapter.scala`。CPU 侧
  正在并行开发，本项只记录结构合同，不把读取时的 HEAD 或旧 timing 数字当作稳定基线
- 当前参数：`cpu/src/main/scala/miku/core/OooCoreConfig.scala`
- perf20 地址模式：`chiplab/software/examples/nscscc_perf/start.S` 与
  `chiplab/software/bsp/env/separate.lds`；direct/DMW 快路径结构核对
  `cpu/src/main/scala/miku/privileged/AddressTranslationUnit.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala` 和
  `cpu/src/main/scala/miku/backend/OooBackend.scala`
- 顶层数据流：`cpu/src/main/scala/miku/core/OooCore.scala`
- 历史验证状态：`docs/archive/nscscc-cpu-final-docs/refactor/status.yml`；当前结果以候选清单为准。
- 当前优化与测试合同：`docs/verification-workflow.md`
- 当前候选状态与效果：`docs/optimization-candidates.md`
- 本轮实现记录已随旧 CPU 仓库归档；迁移来源见 `docs/archive/migration-provenance.md`。
- 同平台历史 timing-fail 产物已在稳定版确认后清理，不作为保留证据。
- 当前稳定归档目录：`Stable_Backup/`（其中的具体候选以各自 `manifest.txt` 为准）。
- Standalone 层次资源属于可再生输出，保存于 `build/vivado/`，不作为当前设计的固定证据。
- 历史参考框图：`chiplab/IP/myCPU/doc/picture/框图.svg`；当前结构以本文和 Scala 源码为准
- Decode：`cpu/src/main/scala/miku/frontend/WideDecode.scala`、
  `cpu/src/main/scala/miku/frontend/La32rDecoder.scala`
- Rename 边界与 uop：`cpu/src/main/scala/miku/backend/DecodeRenameBuffer.scala`、
  `cpu/src/main/scala/miku/backend/OooUops.scala`
- RAT/PRF/FreeList：`cpu/src/main/scala/miku/backend/RegisterStructures.scala`
- 后端整组分配：`cpu/src/main/scala/miku/backend/OooBackend.scala`
- ROB hot/cold payload：`cpu/src/main/scala/miku/backend/ReorderBuffer.scala`
- Dispatch/IQ/LSQ 容量：`cpu/src/main/scala/miku/backend/DispatchQueue.scala`、
  `cpu/src/main/scala/miku/backend/IssueQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueueAllocator.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`、
  `cpu/src/main/scala/miku/backend/StoreDataQueue.scala`
- Dispatch timing cut 记录：`docs/archive/refactor/20260728-dispatch-window-timing/iteration.md`
- Compact IQ 记录：`docs/archive/refactor/20260727-compact-issue-queue/iteration.md`
- Wakeup/PRF/执行路径：`cpu/src/main/scala/miku/backend/OooBackend.scala`、
  `cpu/src/main/scala/miku/backend/RegisterStructures.scala`、
  `cpu/src/main/scala/miku/execute/OooExecutionCluster.scala`
- ALU 操作与组合边界：`cpu/src/main/scala/miku/execute/Alu.scala`
- Completion/ROB complete/commit 边界：
  `cpu/src/main/scala/miku/backend/OooUops.scala`、
  `cpu/src/main/scala/miku/backend/ReorderBuffer.scala`
- Commit、恢复与系统副作用：
  `cpu/src/main/scala/miku/backend/OooBackend.scala`、
  `cpu/src/main/scala/miku/backend/OooCommitAdapter.scala`、
  `cpu/src/main/scala/miku/core/OooCore.scala`、
  `cpu/src/main/scala/miku/core/OooCoreSystem.scala`
- Commit/恢复定向测试：
  `cpu/src/test/scala/miku/backend/ReorderBufferSpec.scala`、
  `cpu/src/test/scala/miku/backend/OooCommitAdapterSpec.scala`、
  `cpu/src/test/scala/miku/backend/LoadStoreQueueSpec.scala`、
  `cpu/src/test/scala/miku/core/OooCoreSpec.scala`
- Retirement 状态时序切分记录：
  `docs/archive/refactor/20260727-1735-retirement-state-timing/iteration.md`
- Store data 解耦与 LSQ：
  `cpu/src/main/scala/miku/backend/StoreDataQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueueAllocator.scala`
- LSQ/SDQ 定向测试：
  `cpu/src/test/scala/miku/backend/LoadStoreQueueSpec.scala`、
  `cpu/src/test/scala/miku/backend/StoreDataQueueSpec.scala`
- Nonblocking MSHR、response epoch 与 LSQ 时序记录：
  `docs/archive/refactor/20260725-nonblocking-mshr/iteration.md`、
  `docs/archive/refactor/20260728-cache-response-epoch/iteration.md`、
  `docs/archive/refactor/20260725-rob-completion-timing/iteration.md`
- DBAR/IBAR memory epoch：
  `docs/archive/refactor/20260803-dbar-ibar-linux-semantics/iteration.md`
- SUC/MAT 架构合同：
  [龙架构 32 位精简版参考手册 r1p04](https://www.loongson.cn/uploads/images/2025032109211238668.%E9%BE%99%E6%9E%B6%E6%9E%8432%E4%BD%8D%E7%B2%BE%E7%AE%80%E7%89%88%E5%8F%82%E8%80%83%E6%89%8B%E5%86%8C_r1p04.pdf) 第 2.1.7、
  2.1.9、5.3 节
- 固定 nscscc-team 通用 DMA 接线：
  `chiplab/chip/soc_demo/nscscc-team/soc_top.v`、`chiplab/IP/DMA/dma.v`
- Cache array 与三级 controller：
  `cpu/src/main/scala/miku/memory/CacheArray.scala`、
  `cpu/src/main/scala/miku/memory/L1InstructionCache.scala`、
  `cpu/src/main/scala/miku/memory/L1DataCache.scala`、
  `cpu/src/main/scala/miku/memory/L2Cache.scala`
- Shared MSHR 与 AXI bridge：
  `cpu/src/main/scala/miku/memory/SharedReadMshrRouter.scala`、
  `cpu/src/main/scala/miku/memory/SharedCacheHierarchy.scala`、
  `cpu/src/main/scala/miku/memory/AxiLineBridge.scala`
- Cache/AXI 定向测试：
  `cpu/src/test/scala/miku/memory/L1InstructionCacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/L1DataCacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/L2CacheSpec.scala`、
  `cpu/src/test/scala/miku/memory/SharedCacheHierarchySpec.scala`、
  `cpu/src/test/scala/miku/memory/AxiLineBridgeSpec.scala`
- MMU/TLB/CSR 实现：
  `cpu/src/main/scala/miku/privileged/AddressTranslationUnit.scala`、
  `cpu/src/main/scala/miku/privileged/HierarchicalTlb.scala`、
  `cpu/src/main/scala/miku/privileged/CsrFile.scala`、
  `cpu/src/main/scala/miku/core/OooCoreSystem.scala`
- 翻译 owner 与 flush/drain 协议：
  `cpu/src/main/scala/miku/frontend/OooFrontend.scala`、
  `cpu/src/main/scala/miku/backend/OooBackendWithExecution.scala`、
  `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala`
- TLB/CSR/异常定向测试：
  `cpu/src/test/scala/miku/privileged/AddressTranslationUnitSpec.scala`、
  `cpu/src/test/scala/miku/privileged/CsrFileSpec.scala`、
  `cpu/src/test/scala/miku/core/OooCoreIntegrationSpec.scala`
- Hierarchical TLB 历史设计与时序记录：
  `docs/archive/refactor/20260725-ooo-hierarchical-tlb/iteration.md`、
  `docs/archive/refactor/20260727-tlb-walk-result/iteration.md`；`d9bab16...` 到当前仅
  `7d35545` 直接修改 TLB，实现 micro-result masked merge
- TLB/CACOP 架构定义：
  [龙架构 32 位精简版参考手册 r1p04](https://www.loongson.cn/uploads/images/2025032109211238668.%E9%BE%99%E6%9E%B6%E6%9E%8432%E4%BD%8D%E7%B2%BE%E7%AE%80%E7%89%88%E5%8F%82%E8%80%83%E6%89%8B%E5%86%8C_r1p04.pdf) 第 4.2.2、
  4.2.3、5.2、5.4、6、7.4、7.5 节
- Critical return 与 I-side timing 记录：
  `docs/archive/refactor/20260723-ooo-l1i-critical-group/iteration.md`、
  `docs/archive/refactor/20260726-wide-backend-critical-load/iteration.md`、
  `docs/archive/refactor/20260728-l1i-parallel-predecode-timing/iteration.md`
- 执行单元完成仲裁测试：
  `cpu/src/test/scala/miku/execute/OooExecutionClusterSpec.scala`
- Divider wakeup 取舍记录：`docs/archive/refactor/20260726-divider-wakeup-timing/iteration.md`
- 历史 Vivado placed 资源仅在对应 `Stable_Backup/` 候选目录中保留。
