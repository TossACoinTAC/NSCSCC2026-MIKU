# 提交仓库精简说明

## 目的与边界

本文定义 `T2026144230012607/` 的正式提交包边界。目标是让提交仓库只保留：

1. 官方 GitLab CI 实际消费的 CPU 输入；
2. 使用 SpinalHDL 时必须随提交提供的完整源码和可复现生成入口；
3. 能够解释当前候选版本、接口、工具和哈希的最小证据；
4. 比赛要求的根目录文件。

开发日志、失败尝试、Vivado 工作目录、临时仿真产物和远端板测缓存属于工作区或稳定归档，
不属于正式提交包。精简操作必须先完成稳定归档，再从提交分支移除历史材料。当前清理批次已按本
文档执行：归档文件位于根工作区被忽略的 `Stable_Backup/` 下，归档 SHA-256 记录在本批次的
提交说明中。

### 2026-08-05 清理批次

本批次以提交仓库 `dev/ECHO @ 0a8ef8c` 为清理起点，未修改 `.gitlab-ci.yml`、比赛 Tcl、
CPU/package 命名、生成 RTL 或 Xilinx IP。执行前归档如下：

| 归档 | 内容 | SHA-256 |
| --- | --- | --- |
| `Stable_Backup/submission-history-dev-ECHO-0a8ef8c-20260805/historical-material.tar.gz` | `logs/`、嵌套 `AGENTS.md`、`baseline.txt` | `d61f721df49c4cbe1261b2fe26f7598cb21c8db57109d56fbfcbc20c2fcad34b` |
| `Stable_Backup/submission-history-dev-ECHO-0a8ef8c-20260805/design-compile-log.tar.gz` | 根目录 `design-compile.log` | `f75df5d30fe4b659a6da095af47b680be90ecfce45b881815393ff40139ed65c` |
| `Stable_Backup/submission-history-dev-ECHO-0a8ef8c-20260805/repository.bundle` | 清理前 `HEAD=0a8ef8c` 的完整 Git 历史 | `edc473d67731fd925134e6d75dbb75ee04c0506d4beb3ef0500f7ba5160e7183` |

归档目录被根 `.gitignore` 排除，不属于提交内容；它用于本地恢复，Git 历史仍是远端可追溯
来源。提交仓库删除 737 个历史/生成文件，并更新引用这些路径的源码包说明。

## 官方消费路径

官方提交模板的实际输入边界如下：

| 路径 | 保留理由 | CI 是否直接消费 |
| --- | --- | --- |
| `src/mycpu/mycpu_top.v` | Vivado 可综合的生成 RTL | 是 |
| `src/mycpu/mycpu.h` | Chiplab CPU 顶层兼容头文件 | 是 |
| `src/mycpu/xilinx_ip/*/*.xcix` | CPU 使用的 Xilinx IP 定制文件 | 是 |
| `src/perf_clock.json` | 性能模式 CPU 时钟请求 | 是 |
| `.gitlab-ci.yml` | 官方模板入口，禁止私改 | 是 |
| `design.pdf` | 必交设计文档 | 由赛事要求消费 |
| `bit/`、`show/`、`score.xlsx` | 模板规定的提交结构和展示/成绩文件 | 部分由赛事流程消费 |

官方 CI 会清空 Chiplab 的 `IP/myCPU`，只复制 `src/mycpu/`；不会从
`src/vivado_cannot/` 生成 CPU，也不会把其中的历史日志当作验证证据。CI 触发条件主要关注
`.gitlab-ci.yml`、`src/mycpu/**/*` 和 `src/perf_clock.json` 的变化。

## `openla500` 命名与后续源码重构

当前 `nscscc-cpu/spinal/src/main/scala/openla500/` 及对应测试目录沿袭的是官方
OpenLA500 baseline 的 Scala package 名称。它是历史来源和兼容边界的标识，不是当前团队
微架构的设计名称。当前实际生成的主体是团队自建的 LA32R 多发射乱序核；目录名相同不应
被解读为“仍在实现官方 baseline”。

仓库中仍有一部分 `OpenLa500*` 类名、生成入口和 `openla500_*` manifest 字段需要暂时保留，
原因是它们分别被以下合同读取或检查：

- `Makefile`、Spinal `runMain` 入口和各独立 leaf gate；
- `tools/*_gate.py` 的生成器白名单、模块名和测试报告名检查；
- `reference/component-contracts/`、`reference/manifest.lock` 中的上游来源与哈希锁；
- 官方 `core_top` 适配、历史 oracle 以及提交包中的可复现 provenance。

因此“只把文件夹改名、package 仍写 `openla500`”会制造更严重的目录/命名不一致；直接做全局
替换又会同时改变 Scala 全限定类名、测试报告路径、生成 Verilog 层级、RTL 哈希和门禁台账。
这类变化不属于提交仓库清理，不能在当前冻结版上顺手进行。

后续若要消除 baseline 命名，建议单独建立源码重构提交，采用团队自有且中性的根 namespace
（暂定候选为 `nscscccpu`，最终名称需在设计文档中锁定），将活动 OoO 包迁移到该 namespace，
并把仍需兼容官方输入的生成器、leaf 和模块名集中放入明确的 `compat` 边界。迁移必须按
“源码移动与 package 更新 → SBT 编译/测试 → 生成 RTL → 端口、lint、Yosys、publication
checks → Chiplab 仿真 → 完整 SoC 实现”的顺序完成；所有新哈希和证据重新绑定后，才能进入
新的发布候选。

本说明不执行目录、package、类名或生成入口改动。当前清理批次不改名；命名重构只能在后续独立
开发分支中进行，不能混入该冻结版或用它覆盖既有证据。

### `src/main/scala` 这一层是否可以压平

不建议把 `spinal/src/main/scala/` 删除或改成自定义的 `spinal/src/`。这里的 `src/main/scala`
是 SBT/Scala 的标准 source root：`main` 表示生产源码，`scala` 表示 Scala 编译源集；测试
对应的 `src/test/scala` 也依赖同一约定。IDE、SBT 默认设置、测试发现、生成门禁和提交包中的
路径证据都可以直接使用它，不需要额外的 source-directory 配置。

因此后续合理的目录形态仍应是：

```text
spinal/
├── src/main/scala/<team-namespace>/   # 活动 CPU/SpinalHDL 源码
└── src/test/scala/<team-namespace>/   # ScalaTest 与生成合同测试
```

真正需要消除的是 `src/main/scala/` 下的 baseline namespace `openla500/`，而不是语言层级
`scala/`。只有在明确需要自定义多模块构建、并愿意同步修改 SBT、IDE、门禁和所有路径证据时，
才有理由压平这一层；对当前提交包精简没有收益，反而会扩大验证范围。

### 各层命名的当前决策

| 层级 | 实际语义 | 当前决策 |
| --- | --- | --- |
| `src/vivado_cannot/` | 官方提交模板为非 Verilog HDL 提供的可选容器 | 保留；这是提交布局，不是 CPU package |
| `nscscc-cpu-main/` | 团队选择的源码包内层目录 | 可在未来打包清理时缩短，但不是 CI 关键字，当前不改 |
| `spinal/` | 一个 SBT/SpinalHDL 构建模块的根目录 | 保留；名称准确表达实现技术。若未来有多个生成模块，可另开机械重构将其改为 `rtl-generator/`，但需同步 Makefile、README 和全部路径证据 |
| `src/main/`、`src/test/` | SBT 的生产/测试 source set | 保留 |
| `scala/` | Scala source root 的语言标识 | 保留 |
| `openla500/` | 沿袭官方 baseline 的 package/目录 namespace | 后续源码重构的主要目标 |

这张表的核心判断是：目前只需要规划替换 `openla500/`，没有必要为了“目录更短”同时改动
`spinal/src/main/scala`。层级减少并不会让 CPU 更自研，清晰的 namespace 边界才会。

## `vivado_cannot` 的最小职责

`src/vivado_cannot/` 是模板提供的可选容器。它用于满足“使用 Vivado 不能直接综合的语言时，
提交完整源码和编译说明”的要求；`nscscc-cpu-main` 只是团队选择的内层目录名，不是 GitLab
CI 的特殊关键字。

### 应保留

以下内容构成最小可复现源码包：

- `README.md`：源码入口、生成命令和 Vivado 说明；
- `spinal/`：SpinalHDL 源码、测试和 SBT 配置；
- `tools/`：生成器、端口/发布检查和必要的本地门禁脚本；
- `tests/`：与上述门禁直接对应的 Python 测试；
- `docs/ooo-core.md`、当前 Linux/接口合同：架构和生成边界说明；
- `reference/manifest.lock`、`core-top.ports.json`、当前 RTL 哈希和必要的 lint/replacement
  合同；
- 当前仍被生成或测试流程读取的 `sw/`、`xilinx_ip/` 文件；若不再被任何命令读取，则应移出
  正式包。

### 可缩减或移出

这些内容不参与官方 CI/Vivado 生成，可以保存到根工作区的稳定归档或独立实验归档：

- `logs/refactor/` 的逐轮实验、失败尝试、模型输出和审阅原文；
- 已被后续候选取代的旧 board/perf 记录；
- 旧标量核、旧 overlay、已删除组件的合同和 source-audit；
- `AGENTS.md`、`baseline.txt` 等团队内部开发材料；
- 与 `src/mycpu/` 重复的生成 `rtl/mycpu_top.v` 镜像；
- 与 `src/mycpu/xilinx_ip/` 重复且不被源码生成流程读取的 `.xcix`；
- 本地软件镜像、临时 seed 输出和仅用于历史比较的 benchmark 结果。

这类文件不是全部“错误文件”。它们只是开发证据，不应与当前候选的源码、锁文件和验收结论
混在同一提交包中。

### 明确禁止进入正式包

- `build/`、`target/`、`.Xil/`、Vivado runs、`.dcp`、`.jou`、`.log`；
- `.fpgajob`、远端结果缓存、原始波形和临时仿真目录；
- Scala/SBT/Verilator/Java 工具缓存和二进制安装包；
- 平台所有的 clock、DDR、AXI、JTAG 或板级 IP，尤其是平台 `clk_pll.xci`。

## 当前提交包审计结果

当前 `T2026144230012607` 的根目录结构已经覆盖模板规定的主要路径，
`src/mycpu/xilinx_ip/` 中目前只有 `.xcix`，没有发现明显的路径级违规。

但它还不能称为“最新且最简明的正式包”，原因如下：

1. `src/vivado_cannot/nscscc-cpu-main/logs/` 在清理前有 734 个历史文件（10,685,639 B），
   对官方 CI 没有作用；已归档并从提交分支移除。
2. `reference/manifest.lock` 仍锁定历史 `chiplab_diff @ a2e11b...`，应与正式平台
   `nscscc2026 @ c398d274812f164d387146fa7d8f612a4a1296d9` 对齐，或者明确标注为历史参考。
3. `docs/refactor/status.yml`、`docs/ooo-core.md` 和 `docs/linux-system-gap-audit.md` 中仍有
   旧 source commit、旧 RTL hash、旧 WNS 和旧 perf20 周期。它们不是 CI 输入，却会误导人工审阅
   和源码复现。
4. 当前提交包中的 `src/mycpu/mycpu_top.v` 与嵌套 `rtl/mycpu_top.v` hash 相同，说明该快照
   内部一致；但外部活动 `nscscc-cpu` 已出现更新提交和 dirty RTL，提交包并未自动跟随最新开发树。
5. `show/` 目前主要是占位文件。初赛 CI 不依赖完整展示内容，但决赛提交前必须按模板要求
   将展示用 `myCPU` 与 `src/` 的最终输入保持一致。

因此应区分两个结论：

- **结构结论**：最小 CI 结构基本满足模板；
- **发布结论**：当前包仍需完成候选冻结、锁文件/状态文档同步和历史材料精简，才能作为
  清晰的正式发布包。

## 精简后的目标结构

```text
T2026144230012607/
├── .gitlab-ci.yml
├── Readme.md
├── design.pdf
├── design.tex                  # 若保留，应与 design.pdf 同步
├── score.xlsx
├── src/
│   ├── mycpu/
│   │   ├── LICENSE
│   │   ├── mycpu.h
│   │   ├── mycpu_top.v         # 当前候选生成 RTL
│   │   └── xilinx_ip/
│   │       ├── data_bank_sram/data_bank_sram.xcix
│   │       └── tagv_sram/tagv_sram.xcix
│   ├── perf_clock.json
│   └── vivado_cannot/
│       └── nscscc-cpu-main/
│           ├── README.md
│           ├── Makefile
│           ├── spinal/
│           ├── tools/
│           ├── tests/
│           ├── docs/             # 当前说明和合同
│           └── reference/        # 当前锁和发布哈希
├── bit/                          # 赛事需要时放置 bitstream；否则保留占位
└── show/                         # 决赛展示包，必须与最终 src 输入一致
```

`rtl/mycpu_top.v` 和嵌套 `xilinx_ip/` 是否保留，取决于源码生成命令是否明确依赖它们。若只
用于镜像或历史验证，应删除重复副本，并在 `README.md` 中说明正式输入始终是 `src/mycpu/`。

## 发布前同步合同

精简或重新打包前，必须先冻结一个 CPU 候选，并完成以下同步：

1. 记录 CPU commit、分支、dirty 状态和 Chiplab `c398d274...`；
2. 从 SpinalHDL 源码重新生成 RTL，禁止手改生成 Verilog；
3. 让 `src/mycpu/mycpu_top.v`、源码包中的 RTL 镜像和 replacement ledger 使用同一 SHA-256；
4. 更新 `reference/manifest.lock`、`docs/ooo-core.md` 和当前状态文件，使 commit、RTL hash、
   Chiplab、Vivado 和时序数据属于同一候选；
5. 删除或移出历史日志前，把完整日志保存到根工作区的稳定归档，不能依靠提交仓库中的旧日志
   作为唯一证据；
6. 检查 `src/mycpu/xilinx_ip/` 每个 IP 目录只包含 `.xci`/`.xcix` 定制文件；
7. 检查 `src/perf_clock.json` 的数值和设计文档中的目标频率一致；
8. 重新运行 CPU 本地门禁和生成/发布检查；
9. 用一个干净的 Chiplab `c398d274...` 快照执行匹配的 Verilator、功能 SoC 和性能 SoC 流程；
10. 只有完整 SoC setup/hold 非负、DRC 通过、bitstream 成功后，才把该包称为可提交候选。

推荐的静态检查包括：

```bash
sha256sum src/mycpu/mycpu_top.v \
  src/vivado_cannot/nscscc-cpu-main/rtl/mycpu_top.v

find src/mycpu/xilinx_ip -type f \
  ! \( -name '*.xci' -o -name '*.xcix' \) -print

git diff --check
git status --short --branch
```

## 版本与证据原则

提交包只保留一个“当前候选”叙事。旧候选、旧时序、旧板测和旧 perf20 可以留在外部
`Stable_Backup/` 或开发仓库的实验归档，但不得继续出现在当前状态文件的“当前”字段中。

官方 CI 的通过结论只能来自官方 pipeline；本地仿真、Vivado 实现和团队板测应分别标注为
对应证据，不能因为文件被复制进提交仓库就升级为官方通过。

本说明描述的是精简目标和验收边界。本批次已归档并移除 `logs/`、嵌套开发用 `AGENTS.md`
和历史 `baseline.txt`，同时保留 `docs/refactor/status.yml`、生成 RTL、CPU-local IP、
官方 `.gitlab-ci.yml` 及比赛 Tcl。提交仓库未进行目录/package/类名改动。
