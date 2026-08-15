# 板卡编排边界

本目录只放根仓库调用独立 `fpga-lab-agent/` 的客户端编排脚本。LabAgent 源码、服务端
运行时和板卡驱动不复制进根仓库；本地仿真、Vivado bitstream 和远程板测证据必须分开
表述。

当前团队服务地址由 `config/local.env` 的 `LABAGENT_HOST` 配置，SSH key 由
`LABAGENT_SSH_KEY` 配置。服务端禁用交互式 shell，只接受 `boardctl` 子命令。只读查询
使用根 Makefile：

```sh
make board-queue
make board-status BOARD_JOB=20260815-114325-2bc00a63
make board-result BOARD_JOB=20260815-114325-2bc00a63
```

`scripts/board/boardctl` 也可直接传递受支持的远端子命令。它固定使用 `ssh -T` 和
`IdentitiesOnly=yes`，不尝试启动交互式 shell。上传会改变远端队列，仍要求显式组包、校验
哈希并通过该包装器调用 `upload`，根 Makefile 不提供隐式上传目标。

正式结论必须来自终态 `result`，并取回 `programming-summary.txt`、
`board-summary.txt`、对应的 `*_vio.csv`、双跑原始 CSV 和 `vivado-metrics.txt`，逐项
核对服务器声明的 SHA-256。队列、烧录或 testing 状态均不能提前写成 PASS。
