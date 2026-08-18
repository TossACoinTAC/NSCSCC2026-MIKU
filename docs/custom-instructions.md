# MIKU 决赛自定义指令使用手册

本文说明如何在比赛现场把题面转换成一个编译期 profile，并验证生成的 RTL。正式题面公布前，
默认 profile 是 `disabled`，不会增加自定义指令硬件。

## 1. 比赛约束

2026 团体赛技术方案给出的约束如下：

- 自定义指令占总成绩的 10%；
- 测试程序是汇编程序，只检查功能正确性，不参与性能测试；
- 题目属于基础整数指令，分为计算、分支和访存三类；
- 具体编码和语义在决赛阶段公布。

因此，仓库使用编译期 profile。正常 CPU 构建保持 `CUSTOM_PROFILE=disabled`；只有比赛题面的
profile 会增加解码和执行逻辑。不要根据往届题目预先注册正式指令，也不要猜测今年的 opcode。
Makefile 会忽略 shell 中遗留的同名环境变量，只接受命令行显式的
`CUSTOM_PROFILE=<name>`。生成后的 `build/rtl/generation-manifest.json` 会记录所选 profile，
并在 experiment freeze 和 Vivado 归档时继续校验该身份。

## 2. 比赛当天只修改的文件

通常只修改：

```text
cpu/src/main/scala/miku/core/ContestCustomInstructionProfiles.scala
```

该文件注册一个或多个 `CustomInstructionProfile`。其余框架已经负责解码、寄存器重命名、
发射、执行、写回、退休、分支恢复、访存和异常处理。以下示例中的编码只是说明 API，必须
替换为正式题面给出的值。

```scala
package miku.core

import spinal.core._

object ContestCustomInstructionProfiles {
  private val IntegerExample = CustomInstructionSpec.compute(
    name = "final-integer",
    matchValue = BigInt("d0000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    source2 = CustomRegister.Rk,
    destination = CustomRegister.Rd,
    evaluator = CustomComputeEvaluator.from { (source1, source2, instruction) =>
      ((source1 ^ source2).asUInt + instruction(25 downto 15).asUInt).resize(32).asBits
    }
  )

  private val BranchExample = CustomInstructionSpec.branch(
    name = "final-branch",
    matchValue = BigInt("d4000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    source2 = CustomRegister.Rd,
    immediate = CustomImmediate.SignedI16Shift2,
    branchKind = CustomBranchKind.Equal
  )

  private val LoadExample = CustomInstructionSpec.load(
    name = "final-load",
    matchValue = BigInt("d8000000", 16),
    matchMask = BigInt("fc000000", 16),
    base = CustomRegister.Rj,
    destination = CustomRegister.Rd,
    immediate = CustomImmediate.SignedI12,
    memorySize = CustomMemorySize.Word
  )

  private val StoreExample = CustomInstructionSpec.store(
    name = "final-store",
    matchValue = BigInt("dc000000", 16),
    matchMask = BigInt("fc000000", 16),
    base = CustomRegister.Rj,
    data = CustomRegister.Rd,
    immediate = CustomImmediate.SignedI12,
    memorySize = CustomMemorySize.Word
  )

  private val Final2026 = CustomInstructionProfile(
    "final-2026",
    Vector(IntegerExample, BranchExample, LoadExample, StoreExample)
  )

  val Available: Vector[CustomInstructionProfile] = Vector(Final2026)

  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector(
    CustomInstructionVerificationCase.compute(
      Final2026,
      IntegerExample,
      instruction = BigInt("d0001483", 16),
      source1 = BigInt("12345678", 16),
      source2 = BigInt("89abcdef", 16),
      expectedResult = BigInt("9b9f9b97", 16)
    ),
    CustomInstructionVerificationCase.branch(
      Final2026,
      BranchExample,
      instruction = BigInt("d4000c85", 16),
      source1 = 7,
      source2 = 7,
      expectedTaken = true,
      expectedTarget = BigInt("1c00000c", 16)
    ),
    CustomInstructionVerificationCase.memory(
      Final2026,
      LoadExample,
      instruction = BigInt("d83ff083", 16),
      source1 = BigInt("00001004", 16),
      source2 = 0,
      expectedAddress = BigInt("00001000", 16),
      expectedByteMask = BigInt("f", 16)
    ),
    CustomInstructionVerificationCase.memory(
      Final2026,
      StoreExample,
      instruction = BigInt("dc001107", 16),
      source1 = BigInt("00002000", 16),
      source2 = BigInt("1122aabb", 16),
      expectedAddress = BigInt("00002004", 16),
      expectedByteMask = BigInt("f", 16),
      expectedWriteData = BigInt("1122aabb", 16)
    )
  )
}
```

`name` 同时用于错误信息和 `CUSTOM_PROFILE` 选择。名称必须匹配
`[a-z0-9][a-z0-9._-]*`，且正式 profile 不得使用保留名称 `disabled` 或 `off`。profile 中的每条指令会自动获得内部
operation 编号，不要手工分配 `decoded.operation`。每条已注册指令必须至少有一个
`VerificationCases` 用例；通用测试会实际生成该 profile，并验证计算结果、分支结果或访存请求。
题面给出的全部示例都应转换为用例，边界条件再另行增加。

## 3. 编码与操作数

`matchValue` 表示固定编码位的值，`matchMask` 表示哪些位固定。框架会拒绝以下配置：

- 没有固定全部六位 major opcode；
- 固定值在 mask 外仍有置位；
- 两条自定义指令的编码范围重叠；
- 固定位与寄存器或立即数字段重叠；
- 默认情况下使用现有 LA32R 指令的 major opcode。

只有正式题面明确要求复用标准 opcode 时，才设置 `allowStandardOpcode = true`。该选项会取消
标准编码冲突保护，因此必须逐位核对题面。

寄存器字段可以选择 `Rd`、`Rj`、`Rk`、`Fixed(n)` 或 `Unused`。`Fixed(n)` 表示固定读取或
写入第 `n` 个 GPR，不占用指令编码位。需要读取旧目的寄存器并写回同一个寄存器时，可以写：

```scala
source1 = CustomRegister.Rd,
source2 = CustomRegister.Rj,
destination = CustomRegister.Rd
```

框架会自动推导 `writesGpr`。Compute 和 Load 写入目的寄存器；Store 不写 GPR；Branch 只有
设置 `destination` 时才写 GPR。Branch 的 `destination` 一旦存在，框架会把它作为 link
寄存器并写入 `PC + 4`。

现成立即数包括原始 I16、I5、带符号或无符号 I12、左移两位的带符号 I14/I16/I26。其他连续
字段可以使用 `CustomImmediate.Slice(lsb, width, signed, leftShift)`。若题面立即数由不连续
字段组成，需要在 `CustomImmediate` 中增加一个明确的 decode 实现，并增加定向测试。

## 4. 三类指令的实现方式

Compute 通过 `CustomComputeEvaluator` 定义组合计算。已有 helper 包括加减、异或、
`popCount`、前导零和后缀零计数、奇偶校验、循环右移、字节交换、位反转以及有符号或无符号
min/max。题面需要其他单周期逻辑时，可以在 catalog 中内联 evaluator。`instruction` 参数提供
完整 32 位编码，可用于读取特殊立即数或控制位。所有自定义 Compute 只使用第一个 ALU port，
避免同一语义复制到多个执行端口。

动态位域题可以组合 `CustomBitFieldHelpers` 中的 `clippedWidth`、`lowMask`、`extract`、
`insert`、`popCountWithin` 和 `rotateRightWithin`。这些 helper 采用确定的边界规则：位域越过
字末时截到字末，宽度为零时提取和计数结果为零，替换和旋转保持原值，旋转量按截取后的实际
宽度取模。传入的 base、width 和 rotate amount 对于 32 位数据最多使用 6 位。

Branch 可以使用 `CustomBranchKind` 中的恒跳、相等、不等、有符号比较、无符号比较和寄存器
间接跳转。特殊条件使用 `CustomBranchEvaluator`。框架会同时更新 fetch predecode 和 ROB 中的
预测器 metadata，错误预测仍使用现有恢复流程。

Load 和 Store 使用现有 LSU、cache、地址翻译及精确异常流程。支持 Byte、Half、Word，Load
还可以选择符号扩展。当前模型是一条指令计算一个地址并完成一次访存。

## 5. 当前能力边界

以下题目可以直接写 profile：

- 最多读取两个 GPR、写入一个 GPR 的单周期整数计算；
- 读取旧 `rd` 后再写回 `rd` 的 read-modify-write；
- 标准比较条件或一个自定义 predicate 的直接分支；
- 写入 `PC + 4` 的 branch link；
- 一个基址、一个立即数和一次 Byte/Half/Word 访问的 Load 或 Store。

以下情况需要独立开发分支，不能只扩展 catalog：

- 同时读取三个或更多 GPR；
- 多周期计算或需要新的执行单元握手；
- 一条指令访问两个或更多地址；
- vector、atomic、专用 CSR、新异常或其他架构 side effect；
- 需要修改公开 `core_top` 接口的题目。

题面文字、编码图和示例如果相互矛盾，应先向裁判确认。不能依据单个示例自行决定字段数量、
越界规则或未说明的异常行为。

## 6. 编码测试程序

工具可以生成汇编中的 `.word` 和小端字节序，并检查字段重叠、范围和标准 opcode 冲突：

```text
python3 scripts/cpu/custom_instruction_word.py \
  --base 0xd0000000 \
  --mask 0xfc000000 \
  --field rd:0:5:3 \
  --field rj:5:5:4 \
  --field payload:15:11:0x12 \
  --json
```

输出中的 `assembly` 可以直接放入汇编测试，`little_endian_hex` 用于核对内存内容。只有题面明确
复用标准 opcode 时，才给工具增加 `--allow-standard-opcode`。

## 7. 验证顺序

所有 Scala、Verilator 和 RTL 生成都必须通过根 Makefile，不直接运行系统 SBT：

```text
make custom-test
make custom-check CUSTOM_PROFILE=final-2026
make cpu-check CUSTOM_PROFILE=disabled
git diff --check
```

`custom-test` 验证 profile 约束、解码、重命名与旧目的寄存器依赖、执行结果、分支 predicate、
branch link、动态位域边界、Load/Store、predecode、ROB predictor metadata、实际 profile 的
`VerificationCases` 和 `.word` 工具。`custom-check` 还会为指定 profile 生成完整
`CoreTopCompat` RTL，并运行端口、Verilator lint 和 Yosys 结构检查。
生成的 `build/rtl/generation-manifest.json` 必须记录规范化后的 `custom_profile`，用于确认 RTL
实际包含所选 profile。

正式提交前还应执行题面提供的全部汇编样例和边界值。测试至少覆盖零寄存器、相同源和目的
寄存器、最大与最小立即数、位域宽度边界、分支 taken/not-taken、未对齐或越界访存，以及异常
发生时不产生架构副作用。

## 8. 性能与提交要求

`disabled` profile 应与没有自定义指令框架时生成相同功能的 RTL。正式 profile 会增加实际逻辑，
但不会进入比赛性能测试。仍需检查生成后的资源和时序，防止新增组合计算影响共享 ALU port 的
频率。

往届具体指令只允许在临时测试中验证框架能力。正式 profile catalog、提交历史中的最终源码和
发布 RTL 不应包含往届 mnemonic、编码或完整语义实现。
