# CPU 合同和版本锁

- `manifest.lock` 锁定 Scala/SBT/SpinalHDL、Verilator、Yosys、Java、Python、
  LA32R 软件工具链，以及本次源码迁移来源。`migration_*` 字段是历史导入证据；
  当前候选 RTL 身份以 `build/rtl/generation-manifest.json` 为准。
- `core-top.ports.json` 定义 `core_top` 的 49 个端口和 `TLBNUM=32`。
- `cache-memory.contract.json` 列出 cache、AXI 和 TLB 必须覆盖的外部负向场景。
- `scala-dependencies.lock.json` 保存 Docker Scala 缓存种子的内容锁；它允许包含已验证但
  当前构建未直接引用的缓存项，实际工程依赖由 `build.sbt` 的锁定版本决定。
- `migration/` 保存从旧 CPU 独立仓库提取源码时的来源和未提交文档补丁。

本目录不保存旧 leaf RTL、内部模块名、warning 行号或基于历史提交的 DUT golden。
算术、ROB/IQ/LSQ/cache/TLB/CSR 状态机语义由 `src/test/scala` 验证；Python 只验证
公开接口、结构化 manifest 和工具行为。
