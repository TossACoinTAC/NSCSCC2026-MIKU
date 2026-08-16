# 2026-08-16 四项研究成果落地记录

范围：只处理 LSQ 转发、TLB 时序、Cache MSHR、分支预测；其他方向不启动新实验。

## 已落地

### LSQ 转发
- 在 `cpu/src/main/scala/miku/backend/LoadStoreQueue.scala` 实现 byte-lane
  multi-store forwarding：
  - 每个 byte lane 选择最年轻的 older ready store；
  - 多个 byte/half store 完全覆盖 load mask 时直接拼接转发；
  - 单 store 完整覆盖仍保留原 per-store banked fast path；
  - 部分覆盖 + 另一 store 完整覆盖时按 byte lane 合并，避免错误选择单 store 数据；
  - 保持 physical-address alias 判断、translationDone、非 uncached、非 LL 约束。
- 新增定向测试：
  - 4 个 byte store 拼成一个 word load，banked/legacy 两配置；
  - 年轻 partial store 覆盖年老 full-cover store 的一个 byte。

### TLB 时序
- 研究结论 V3 的 probe-key 预载和 ATU owner/response 预载在当前 MIKU 树已经存在：
  - `HierarchicalTlb(preloadDataProbeKey = config.enableDataTranslationProbePreload)`；
  - `OooCoreConfig.enableDataTranslationProbePreload = true`；
  - `AddressTranslationUnit` 已有 instruction/data owner slot 预载与 response-valid 窄资格化。
- 本轮不改 TLB RTL。

### Cache MSHR
- L1D 与 L2 的 dirty victim data 由单一全局 512-bit 寄存器改为每 MSHR 独立
  victim buffer：
  - `L1DataCache.scala`：`missVictimData(lookupMshrId)` 捕获、`missVictimData(writebackId)` 发送；
  - `L2Cache.scala`：同样按 owning MSHR 保存 read-miss victim；
  - 修复多个 dirty victim 等待共享写口时，后到 victim 可能覆盖未发送前者的风险。
- MIKU 现有 L15 已允许 victim writeback 期间其他 set 发起 lookup，不再重复实现。

## 未落地 / 明确待办

- **LSQ 转发**：cache 数据 + store byte merge 尚未实现；部分覆盖且 cache 数据可读时仍保守等待。
- **TLB 时序**：V4 主 TLB walker 8 项/拍尚未实现；当前仍 4 项/拍。主要收益在 Linux/TLB miss 路径，perf20 预期很小。
- **Cache MSHR**：XiangShan/HPDcache 式 Request Buffer/Replay Table、L1D/L2 lookup 流水化、MSHR 4 到 8、AXI cached read ID 扩到 8..15、公平仲裁都未实现。研究结论也指出单加 MSHR 深度收益有限，必须与 request buffer/写回解耦配套。
- **分支预测**：先落地 16 位 GHR + 4x4096x2b banked gshare（候选 A）。4x16384（候选 B）、loop predictor、tournament/TAGE 未实现，待 A 的完整 perf20/板测后再决定。

## 验证状态（macOS 本地）

- sbt test：39 suites / 235 tests passed（在 MIKU cpu 工作树）。
- 定向：
  - LoadStoreQueueSpec：36/36（含两个新 forwarding 测试）。
  - BranchPredictorSpec + OooFrontendSpec：27/27。
  - L1DataCacheSpec + L2CacheSpec：20/20。
- GenerateCoreTopCompat 本地生成成功，顶层 49 端口不变，PHT 为 4 x 4096 x 2b。
- 尚未在锁定 WSL/Docker 环境跑完整 cpu-check、func58/perf20/Linux 仿真、Vivado direct full 和真实板测；这些门禁必须回到 WSL 后执行。

## 2026-08-16 首次真实板测（perf20，100 MHz）

- EPYC2 锁定容器（sbt 1.10.11 / Verilator 5.020 / Yosys 0.33）运行
  `cpu-generate` 与 `cpu-locked-gates` 通过。
- 发布 RTL SHA-256：`623d6c658eb208c7e8dbd40d606998e42779b65c6d57c4832d5e250aae5b4c48`；
  raw RTL SHA-256：`646f09d326792905e1bc5589cdb09a54efdc5194204298deefd66c06c0587868`。
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`，Vivado 2023.2，
  part `xc7a200tfbg676-2`，perf 100 MHz。
- Vivado 实现/bitstream 完成，DRC 0 Error、26 Warning、fully routed；
  **setup WNS -0.506 ns，hold WNS +0.024 ns**：板测通过但不构成 100 MHz
  时序闭合声明。
- LabAgent `10.19.75.72` 任务：
  - job：`20260816-085628-96443f0d`；
  - package SHA-256：`f3d9eaac1f46598ecc9c352d1de85296108614a6e1bb1c3bacab71c5f45d5e9f`；
  - verdict：`passed`，`nscscc-system-reset-v1`，20/20 全部通过；
  - selected 双跑合计：`soc_count=33,384,117`、`cpu_count=33,373,331`，
    `cpu/soc=0.99968`，实际 CPU 100.000000 MHz；
  - 相对 R5 板测 `cpu_count=43,489,002` 下降 `10,115,671` cycles，
    `-23.26%`。
- 取回证据哈希全部匹配服务端声明：
  - `programming-summary.txt`：`780e41c2...`
  - `board-summary.txt`：`6f51bdaa...`
  - `perf_vio.csv`：`de7d99df...`
  - `perf_vio_runs.csv`：`9408fdf4...`
  - `vivado-metrics.txt`：`e7a04719...`
- 本地证据目录：
  `.codex-tmp-linux/board-research-20260816-perf20/`。

## 本轮尚未完成

- func58 真板测试未跑；应补一个 func profile 实现 + LabAgent 任务。
- 100 MHz setup 未闭合，下一步按 WNS 路径修时序后重做 direct full。
- perf20 只完成一次正式任务；按仓库门禁还需要重复板测取最低值。
- EPYC2 根分区曾 100% 满，已删除 6 个已停止 Docker 容器释放约 9.25 GB；
  当前仍只有约 8.4 GB 可用，后续大构建前需要继续清理旧 build 目录或 Docker
  volume（需与协作者确认）。

## 2026-08-16 时序修复轮（dev/research-20260816-four-fix）

首轮拼合 R6 L03/L02/cache/MSHR 与 LSQ 转发后，perf20 板测 `passed` 但
Vivado setup WNS `-0.506 ns`、hold `+0.024 ns`。`cpu_setup_top50.rpt` 显示两条
主导路径族：

1. `rob/candidatePointer_0_reg[1]_rep__10`（fanout 120）经提交状态系统操作译码、
   payload bank 地址、head-bypass 资格化，落到 `stagedHeadCompletionBypassValid`、
   LSQ/registerMap/CSR 的 CE/D；route 占比约 82%。
2. `loadStoreQueue/scheduledLoad_physicalAddress_reg[17]` 经 byte-lane 转发比较、
   `bankedForwardCompletion`、storeHead 释放，落到 lsqAllocator occupancy 更新；
   16 级逻辑、route 占比约 74%。

修复组合：

- 采纳协作者 `4ee3909`（`dev/L03-commit-timing`）：把
  `systemOperationIsNone` / `systemOperationIsMemoryBarrier` 在 ROB allocation
  时预解码进 state，从 commit/head-bypass 路径移除 5-bit 系统操作译码和 barrier
  比较。
- 采纳协作者 `ab820dd`（`dev/L03-predictor-cache-flags`）：移除 byte-lane 多 store
  LSQ 转发，保留 16 位 GHR 4x4096 PHT 与 per-MSR victim buffer。

本地状态：

- macOS `sbt test`：39 suites / 233 tests pass（删除 2 个 byte-lane 特定测试）。
- EPYC2 锁定容器 `cpu-generate cpu-locked-gates` 全部 pass。
- 完整 SoC perf 100 MHz Vivado direct full 正在 /dev/shm 重新实现，结果待记录。
