# 决赛自定义指令单文件实现指南

本文面向比赛现场负责自定义指令的队员。目标是把正式题面转换成可测试的
SpinalHDL 实现，同时只修改一个源码文件：

```text
cpu/src/main/scala/miku/core/ContestCustomInstructionProfiles.scala
```

框架已经处理解码、寄存器重命名、发射、执行、写回、退休、分支恢复、访存和异常。
不要修改 `CustomInstruction.scala`、CPU 其他模块或生成的 Verilog，除非题目明确超出本文最后列出的能力范围。

本文中的 XOR 指令是人为构造的 API 示例，不是往届题目，也不能代替正式题面。

现场操作顺序如下：

1. 从题面整理编码、操作数、语义和样例。
2. 用本文模板替换比赛文件内容。
3. 为每条指令加入至少一个验证用例。
4. 先运行 `make custom-test`，根据错误信息修正单文件。
5. 最后运行完整检查并确认生成清单使用正式 profile。

## 1. 先把题面整理成实现清单

写代码前，先为每条指令填写以下信息：

| 项目 | 需要从题面确认的内容 |
| --- | --- |
| 名称 | 自己使用的小写名称，例如 `final-xor` |
| 类型 | Compute、Branch、Load 或 Store |
| 固定编码 | 哪些 bit 固定为 0 或 1 |
| 寄存器 | `rd`、`rj`、`rk` 中哪些是输入，哪个是输出 |
| 立即数 | bit 范围、是否有符号扩展、是否左移 |
| 语义 | 精确的 32 位计算、分支条件或访存行为 |
| 边界行为 | 溢出、移位、位域越界、未对齐地址等 |
| 官方样例 | 完整指令字、输入值和预期结果 |

题面中的编码图、文字语义和样例必须相互一致。存在矛盾时先向裁判确认，不要根据单个样例猜测。

根据语义选择构造函数：

| 题目行为 | 使用的构造函数 |
| --- | --- |
| 最多读取两个 GPR，写入一个 GPR 的单周期整数计算 | `CustomInstructionSpec.compute` |
| 条件跳转、无条件跳转或寄存器间接跳转 | `CustomInstructionSpec.branch` |
| 一次 Byte、Half 或 Word 读取 | `CustomInstructionSpec.load` |
| 一次 Byte、Half 或 Word 写入 | `CustomInstructionSpec.store` |

## 2. 正确填写编码

框架使用以下条件判断一条指令是否匹配：

```text
(instruction & matchMask) == matchValue
```

- `matchMask` 的 bit 为 `1`，表示该 bit 是题面规定的固定编码。
- `matchMask` 的 bit 为 `0`，表示该 bit 是寄存器、立即数或其他可变字段。
- `matchValue` 只填写固定为 `1` 的 bit。在 `matchMask` 为 `0` 的位置，`matchValue` 也必须为 `0`。
- `matchMask` 必须固定 `[31:26]` 的全部六个 major opcode bit。
- 默认禁止与现有 LA32R 指令编码冲突。只有正式题面明确要求复用标准 opcode 时，才能设置
  `allowStandardOpcode = true`。

LA32R 常用寄存器字段如下：

| Scala 名称 | 指令 bit | 含义 |
| --- | --- | --- |
| `CustomRegister.Rd` | `[4:0]` | `rd` 字段 |
| `CustomRegister.Rj` | `[9:5]` | `rj` 字段 |
| `CustomRegister.Rk` | `[14:10]` | `rk` 字段 |
| `CustomRegister.Fixed(n)` | 不占编码 bit | 固定使用第 `n` 个 GPR |
| `CustomRegister.Unused` | 不占编码 bit | 该操作数不存在 |

例如，题面规定 `[31:26] = 0b110100`，其他 bit 全部可变，则：

```scala
matchValue = BigInt("d0000000", 16)
matchMask = BigInt("fc000000", 16)
```

可以使用仓库工具生成测试指令字，并检查字段重叠和取值范围：

```bash
python3 scripts/cpu/custom_instruction_word.py \
  --base 0xd0000000 \
  --mask 0xfc000000 \
  --field rd:0:5:3 \
  --field rj:5:5:4 \
  --field rk:10:5:5 \
  --json
```

该示例应生成 `.word 0xd0001483`，小端字节序为 `831400d0`。

## 3. 使用完整文件模板

以下内容可以作为
`ContestCustomInstructionProfiles.scala` 的完整初始版本。正式比赛时替换名称、编码、操作数和语义，
不要保留示例指令。

```scala
package miku.core

import spinal.core._

object ContestCustomInstructionProfiles {
  private val FinalXor = CustomInstructionSpec.compute(
    name = "final-xor",
    matchValue = BigInt("d0000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    source2 = CustomRegister.Rk,
    destination = CustomRegister.Rd,
    evaluator = CustomComputeEvaluators.xor
  )

  private val Final2026 = CustomInstructionProfile(
    "final-2026",
    Vector(FinalXor)
  )

  val Available: Vector[CustomInstructionProfile] = Vector(Final2026)

  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector(
    CustomInstructionVerificationCase.compute(
      Final2026,
      FinalXor,
      instruction = BigInt("d0001483", 16),
      source1 = BigInt("12345678", 16),
      source2 = BigInt("89abcdef", 16),
      expectedResult = BigInt("9b9f9b97", 16)
    )
  )
}
```

`Final2026` 是 profile。`make custom-check` 通过名称 `final-2026` 选择它。
`Available` 必须包含正式 profile，否则生成器无法找到它。

每条注册指令必须至少对应一个 `VerificationCases` 用例。建议把题面给出的全部样例都写成用例，
再增加零值、全 1、最大和最小有符号数、相同源和目的寄存器等边界值。

验证用例中的 `source1` 和 `source2` 是执行时的寄存器内容，不是寄存器编号。寄存器编号已经编码在
`instruction` 中。

## 4. Compute 指令写法

默认的 Compute 指令读取 `rj` 和 `rk`，写入 `rd`。常用计算已经提供：

```scala
CustomComputeEvaluators.add
CustomComputeEvaluators.subtract
CustomComputeEvaluators.xor
CustomComputeEvaluators.popCount
CustomComputeEvaluators.countLeadingZeros
CustomComputeEvaluators.countTrailingZeros
CustomComputeEvaluators.parity
CustomComputeEvaluators.rotateRight
CustomComputeEvaluators.byteSwap
CustomComputeEvaluators.bitReverse
CustomComputeEvaluators.signedMin
CustomComputeEvaluators.signedMax
CustomComputeEvaluators.unsignedMin
CustomComputeEvaluators.unsignedMax
```

单源指令必须把未使用的第二个输入写为 `Unused`：

```scala
source1 = CustomRegister.Rj,
source2 = CustomRegister.Unused,
destination = CustomRegister.Rd,
evaluator = CustomComputeEvaluators.popCount
```

需要读取旧 `rd` 并把结果写回 `rd` 时：

```scala
source1 = CustomRegister.Rd,
source2 = CustomRegister.Rj,
destination = CustomRegister.Rd
```

题目需要自定义组合计算时，直接在本文件写 evaluator：

```scala
evaluator = CustomComputeEvaluator.from { (source1, source2, instruction) =>
  ((source1 ^ source2).asUInt + instruction(25 downto 15).asUInt).resize(32).asBits
}
```

这里的三个参数都是 32 位硬件值。需要注意：

- 硬件相等比较使用 `===`，不使用 Scala 的 `==`。
- 有符号计算使用 `.asSInt`，无符号计算使用 `.asUInt`。
- 返回值必须是 32 位 `Bits`，不确定时写 `.resize(32).asBits`。
- evaluator 是组合逻辑，不适合除法器等多周期操作。

立即数 Compute 可以让框架直接把解码后的立即数作为 `source2`：

```scala
source1 = CustomRegister.Rj,
source2 = CustomRegister.Unused,
destination = CustomRegister.Rd,
immediate = CustomImmediate.SignedI12,
source2IsImmediate = true,
evaluator = CustomComputeEvaluators.add
```

如果第一操作数是 PC，使用 `source1 = CustomRegister.Unused` 和 `source1IsPc = true`。
如果第二操作数固定为 4，使用 `source2 = CustomRegister.Unused` 和 `source2IsFour = true`。

## 5. 立即数写法

现有立即数定义如下：

| 名称 | 编码字段 | 解码结果 |
| --- | --- | --- |
| `CustomImmediate.UnsignedI5` | `[14:10]` | 零扩展 |
| `CustomImmediate.RawI16` | `[25:10]` | 零扩展 |
| `CustomImmediate.SignedI12` | `[21:10]` | 符号扩展 |
| `CustomImmediate.UnsignedI12` | `[21:10]` | 零扩展 |
| `CustomImmediate.SignedI14Shift2` | `[23:10]` | 符号扩展后左移 2 |
| `CustomImmediate.SignedI16Shift2` | `[25:10]` | 符号扩展后左移 2 |
| `CustomImmediate.SignedI26Shift2` | 高 10 位来自 `[9:0]`，低 16 位来自 `[25:10]` | 按 `instruction[9:0] ## instruction[25:10]` 拼接、符号扩展后左移 2 |

其他连续字段使用：

```scala
CustomImmediate.Slice(lsb = 15, width = 5, signed = false, leftShift = 0)
```

不连续的自定义立即数字段不能仅修改比赛文件完成，应由熟悉 CPU 框架的队员扩展
`CustomImmediate` 并增加专门测试。

## 6. Branch 指令写法

相等分支示例：

```scala
private val FinalBranch = CustomInstructionSpec.branch(
  name = "final-branch",
  matchValue = BigInt("d4000000", 16),
  matchMask = BigInt("fc000000", 16),
  source1 = CustomRegister.Rj,
  source2 = CustomRegister.Rd,
  immediate = CustomImmediate.SignedI16Shift2,
  branchKind = CustomBranchKind.Equal
)
```

可用的标准条件包括 `Always`、`Equal`、`NotEqual`、`SignedLess`、
`SignedGreaterOrEqual`、`UnsignedLess`、`UnsignedGreaterOrEqual` 和
`RegisterIndirect`。

特殊条件使用自定义 evaluator：

```scala
evaluator = Some(
  CustomBranchEvaluator.from { (source1, source2, instruction) =>
    source1 === 0
  }
)
```

直接分支目标是 `PC + immediate`，寄存器间接分支目标是 `source1 + immediate`。
测试中的 PC 固定为 `0x1c000000`。设置 `destination` 后，该分支会向目标寄存器写入
`PC + 4`，用于 branch link。

Branch 验证用例写法：

```scala
CustomInstructionVerificationCase.branch(
  Final2026,
  FinalBranch,
  instruction = BigInt("题面样例的完整指令字", 16),
  source1 = BigInt("第一个输入值", 16),
  source2 = BigInt("第二个输入值", 16),
  expectedTaken = true,
  expectedTarget = BigInt("预期目标地址", 16)
)
```

每条条件分支至少包含一个 taken 和一个 not-taken 用例；无条件分支只需要 taken 用例。
taken 用例的 `expectedTarget` 是实际跳转地址；not-taken 用例的 `expectedTarget` 必须填写
顺序执行地址 `PC + 4`，不是分支立即数计算出的地址。

## 7. Load 和 Store 指令写法

Word Load 示例：

```scala
private val FinalLoad = CustomInstructionSpec.load(
  name = "final-load",
  matchValue = BigInt("d8000000", 16),
  matchMask = BigInt("fc000000", 16),
  base = CustomRegister.Rj,
  destination = CustomRegister.Rd,
  immediate = CustomImmediate.SignedI12,
  memorySize = CustomMemorySize.Word
)
```

Half Store 示例：

```scala
private val FinalStore = CustomInstructionSpec.store(
  name = "final-store",
  matchValue = BigInt("dc000000", 16),
  matchMask = BigInt("fc000000", 16),
  base = CustomRegister.Rj,
  data = CustomRegister.Rd,
  immediate = CustomImmediate.SignedI12,
  memorySize = CustomMemorySize.Half
)
```

Load 的 `signExtend = true` 表示 Byte 或 Half Load 执行符号扩展。地址始终是
`base + immediate`。Store 的写数据和 byte mask 会按照地址低两位排列。

Memory 验证用例写法：

```scala
CustomInstructionVerificationCase.memory(
  Final2026,
  FinalStore,
  instruction = BigInt("题面样例的完整指令字", 16),
  source1 = BigInt("基址寄存器内容", 16),
  source2 = BigInt("Store 数据寄存器内容", 16),
  expectedAddress = BigInt("预期地址", 16),
  expectedByteMask = BigInt("预期四位 byte mask", 16),
  expectedWriteData = BigInt("按照地址排列后的写数据", 16)
)
```

Word 对齐访问的 byte mask 是 `0xf`。Half 在地址偏移 0 或 2 时分别是 `0x3` 或 `0xc`。
Byte 在地址偏移 0 至 3 时分别是 `0x1`、`0x2`、`0x4`、`0x8`。

## 8. 多条指令如何注册

每条指令先定义一个 `CustomInstructionSpec`，然后全部加入同一个 profile：

```scala
private val Final2026 = CustomInstructionProfile(
  "final-2026",
  Vector(FinalCompute, FinalBranch, FinalLoad, FinalStore)
)
```

每条指令至少加入一个对应类型的验证用例。两条指令的编码范围不能重叠，指令名称也不能重复。
内部 operation 编号由框架自动分配，不要自己填写。

## 9. 必须执行的检查

在仓库根目录依次运行：

```bash
make custom-test
make cpu-locked-gates CUSTOM_PROFILE=disabled
make custom-check CUSTOM_PROFILE=final-2026
python3 -m json.tool build/rtl/generation-manifest.json | rg custom_profile
git diff --check
```

- `make custom-test` 会检查编码、操作数字段、验证用例覆盖，并用 Verilator 执行注册指令的定向用例。
- `make custom-check` 会使用正式 profile 生成完整 RTL，并执行端口、Verilator lint 和 Yosys 检查。
- `make cpu-locked-gates CUSTOM_PROFILE=disabled` 会生成 disabled profile，并执行同一组 RTL 端口、lint 和 Yosys 检查，
  不要求先更新仓库证据。
- 不要直接运行系统 SBT。根 Makefile 会使用仓库锁定的 Docker 环境。

最后一条 RTL 生成命令必须是正式 profile 的 `custom-check`，因为其他生成命令会替换
`build/rtl/generation-manifest.json`。清单检查结果必须包含：

```text
"custom_profile": "final-2026"
```

还需要执行题面提供的全部汇编测试。生成成功只能证明 RTL 可以构建，不能代替题目功能测试。
`make cpu-check CUSTOM_PROFILE=disabled` 是发布检查，包含完整 Scala 测试、本地证据和文档检查；
它应在提交源码并更新对应证据后执行，不作为比赛现场的第一条命令。

## 10. 常见错误

| 错误信息关键部分 | 通常原因 | 修改方法 |
| --- | --- | --- |
| `must fix all six opcode bits` | `matchMask` 没有固定 `[31:26]` | 按题面补全六个 opcode bit |
| `outside the fixed mask` | `matchValue` 在可变位置包含 1 | 清除 mask 之外的固定值 |
| `uses standard opcode` | 编码与标准 LA32R opcode 冲突 | 核对题面；只有题面明确要求时才允许复用 |
| `fixes bits used by an operand field` | mask 把寄存器或立即数字段设成固定 bit | 按编码图修正 mask 或操作数字段 |
| `encodings overlap` | 两条指令的匹配范围相交 | 增加题面规定的功能码固定 bit |
| `has no verification case` | 指令已注册，但没有测试用例 | 为该指令增加对应类型的用例 |
| `unknown custom instruction profile` | 命令中的名称与 profile 不一致 | 核对 `Final2026` 的名称和 `Available` |
| `result ... != ...` | 硬件表达式或预期值错误 | 使用题面样例逐 bit 核对符号、位宽和溢出规则 |

## 11. 单文件接口的能力范围

只修改比赛文件可以实现：

- 最多两个 GPR 输入和一个 GPR 输出的单周期整数计算。
- 读取旧 `rd` 后再写回 `rd`。
- 直接或间接分支、单周期自定义条件和 branch link。
- 一次 Byte、Half 或 Word Load／Store。

出现以下要求时立即交给熟悉 CPU 主体的队员，不要继续尝试只修改本文件：

- 同时读取三个或更多 GPR。
- 多周期计算或新的执行单元握手。
- 一条指令访问两个或更多地址。
- Vector、atomic、专用 CSR、新异常或其他架构副作用。
- 修改公开 `core_top` 接口。

更完整的 API 说明、动态位域 helper 和验证原理见
[自定义指令使用手册](custom-instructions.md)。
