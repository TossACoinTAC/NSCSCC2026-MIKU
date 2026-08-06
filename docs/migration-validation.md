# 根仓库迁移验证

## 验证基线

- CPU 导入快照：`70e009e6b79386916e72680e29485e175bf9bbd2`
- Chiplab：`c398d274812f164d387146fa7d8f612a4a1296d9`
- Docker 镜像：`nscscc-dev:ubuntu24.04-v1`
- Java 17.0.19、SBT 1.10.11、SpinalHDL 1.14.2
- Verilator 5.020、Yosys 0.33、Python 3.12.3

## 迁移结果

`make cpu-check` 已通过完整 ScalaTest、SpinalHDL 生成、49 端口公开合同、Verilator
lint、Yosys connectivity/check 和 14 个 Python 黑盒合同。生成结果具有以下固定身份：

- 原始 `core_top.v` SHA-256：
  `560e6cc0285df4785ecc1e60b1b994aa1611b6ef3115a4981f971fc23676f45c`
- 发布 `mycpu_top.v` SHA-256：
  `4968c5131a7fdf937286b0d7eacfe41a9d9d42e25bed89b89bf2e45f9d770839`

完整 perf20 使用 clean、ideal-memory、seed 0 和两个隔离 lane，通过 20/20：

| benchmark | cycles | benchmark | cycles |
| --- | ---: | --- | ---: |
| bitcount | 27826 | bubble_sort | 236335 |
| coremark | 481215 | crc32 | 219378 |
| dhrystone | 5887 | quick_sort | 289652 |
| select_sort | 99438 | sha | 247460 |
| stream_copy | 12653 | stringsearch | 120947 |
| fireye_A0 | 676020 | fireye_B2 | 46815 |
| fireye_C0 | 280955 | fireye_D1 | 305757 |
| fireye_I2 | 544194 | inner_product | 783101 |
| lookup_table | 181128 | loop_induction | 448962 |
| my_memcmp | 203894 | minmax_sequence | 332336 |

这些周期用于确认迁移前后仿真路径可工作，不替代 matching Vivado 实现、时序或板测
证据。可再生的模型和运行日志在验证后由 `make clean` 删除；后续候选应重新生成带自身
源码、RTL、模型和软件哈希的结果。
