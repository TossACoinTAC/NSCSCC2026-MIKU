# 决赛提交分支记录

本轮继续使用初赛官方提交仓库
`T2026144230012607`，在其 `submit` 分支发布稳定 RTL。提交仓库的 `submit` 分支已于
2026-08-18 推送，当前唯一发布提交为：

```text
3853d6bf246c2d1a72ce9e6bfa24e64b63c294e0
```

该提交使用根仓库 `origin/main@276e3095cef09503cce6cef7e90ae414173a3d20` 重新执行
Scala/SpinalHDL 生成，并以 `disabled` profile 打包：

| 项目 | 身份 |
| --- | --- |
| raw RTL SHA-256 | `b7d62d418c899f04e7d22ed78ff170fc8f968e40f823aed90838d71627c16df6` |
| published RTL SHA-256 | `6a08b0dc21d16f9b9b2c2aa6136bd6973ed28184f58345928dda5fd1a47326ec` |
| CPU source tree SHA-256 | `bec6f629ee667e09d1f4ab81de78aa4cb027e370c4614b46039197844402a5a0` |
| 顶层接口 | `core_top`，49 端口，`TLBNUM=32` |
| 性能时钟配置 | 100 MHz |
| Chiplab | `c398d274812f164d387146fa7d8f612a4a1296d9` |

提交仓库中的 `src/rtl-provenance.json`、`src/vivado_cannot/cpu/` 和设计文档均已绑定
上述身份。`make verify` 与 fresh package 字节比较已通过；宿主机未安装 `sbt`，Scala
生成在锁定 Docker 环境中完成。

推送已触发官方 GitLab CI。本文只记录“已触发/等待结果”，不继承其他 RTL 的 CI、Vivado、
Linux 或板测结论；CI 返回后应把 pipeline/job/artifact 身份追加到提交仓库和本记录。
