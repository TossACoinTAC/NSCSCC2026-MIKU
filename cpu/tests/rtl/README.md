# RTL 集成测试

此目录保留给只依赖公开 `core_top`/AXI/debug 接口的 RTL testbench 和 fixture。
内部状态机行为放在 `cpu/src/test/scala`；跨 SoC 软件行为由根目录 `scripts/sim/`
和锁定 Chiplab 模型验证。
