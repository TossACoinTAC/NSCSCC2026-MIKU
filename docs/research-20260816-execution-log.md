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
