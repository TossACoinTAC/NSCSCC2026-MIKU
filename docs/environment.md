# 本地环境与仓库布局

根 Makefile 是唯一公共入口。SpinalHDL 工程完整位于 `cpu/`：`build.sbt`、`project/`、
`src/main/scala`、`src/test/scala`、`tests/python`、`tests/rtl` 和 `reference/`。
根 `scripts/` 只负责跨仓库操作。

首次克隆后先运行 `git submodule update --init`。`chiplab/`、
`nscscc-linux-kernel/` 和 `fpga-lab-agent/` 由根仓库 gitlink 锁定；在子模块内
产生的分支或 dirty patch 仍属于对应仓库，根流程只报告，不自动切换或清理。

Docker 镜像 `nscscc-dev:ubuntu24.04-v1` 锁定 Java/Scala/SBT/SpinalHDL、Verilator、
Yosys、Python 和 LA32R GCC/QEMU/NEMU/picolibc。镜像通过 `make env-build` 构建；SBT
依赖缓存使用 Docker volume，不写入用户 home。Vivado 不放入镜像，默认从
`/opt/Xilinx/Vivado/2023.2/bin/vivado` 调用；Surfer 默认是 `/mnt/d/Surfer/surfer.exe`。

复制 `config/local.env.example` 为 `config/local.env` 后配置路径。该文件不应包含版本
锁、token 或板卡凭据。`make doctor` 只检查，不自动切换、重置或清理任何仓库。

Chiplab gitlink 锁定 `c398d274812f164d387146fa7d8f612a4a1296d9`。其平台源码和官方
toolchains 保留在子模块目录；CPU 生成 RTL 通过 `make chiplab-sync` 写入
`IP/myCPU/mycpu_top.v`。
CPU 当前不依赖 `ip/sram/`，也不复制平台 PLL/DDR/AXI/JTAG IP。

初赛 submission/team-ci 已退出决赛根仓库依赖图。现有同名克隆不由根 Makefile 管理，
不会被 `doctor`、`clean` 或仿真入口修改。
