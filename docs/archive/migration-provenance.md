# 迁移来源记录

2026-08-06，根仓库从 `nscscc-cpu` 的 `dev/ECHO` 工作树导入 SpinalHDL CPU。
源码快照固定为 `70e009e6b79386916e72680e29485e175bf9bbd2`，导入范围是旧仓库的
`spinal/build.sbt`、`spinal/project` 和 `spinal/src`，现在对应根目录 `cpu/`。

导入后的当前源文件树哈希、构建入口哈希和未提交文档补丁保存在
`cpu/reference/migration/`。ScalaTest 的 workspace 已适配到根 `build/`，因此当前源树
哈希与原提交的 archive 哈希有意不同。旧仓库中
绑定初赛提交仓库、精确 warning 行号、golden RTL leaf 文件和历史 overlay 的门禁没有迁入
持续门禁；有价值的公开端口、生成清单和行为合同已改为黑盒形式。

`T2026144230012607/` 与 `nscscc-team-ci/` 是初赛遗留克隆。初赛结束后它们不再是根仓库
的构建、doctor、仿真或锁文件依赖，因此保留在工作区但不由根流程管理。

决赛仍需要的 `chiplab/`、`nscscc-linux-kernel/` 和 `fpga-lab-agent/` 已登记为
Git submodule。根提交锁定其 gitlink；已有 Chiplab harness/config dirty patch 没有被
清理或写入子模块历史。
