package miku.config

import miku.compat.CoreTopCompatConfig
import miku.core.{OooCacheGeometry, OooCoreConfig, OooCpuConfig}
import org.scalatest.funsuite.AnyFunSuite

class CoreConfigSpec extends AnyFunSuite {
  test("OoO CPUCFG values are derived from each cache geometry") {
    val default = OooCoreConfig.FourIssueThreeCommit
    assert(OooCpuConfig.value(default, 1) == BigInt("0001f1f5", 16))
    assert(OooCpuConfig.value(default, 2) == 0)
    assert(OooCpuConfig.value(default, 16) == BigInt("0000001d", 16))
    assert(OooCpuConfig.value(default, 17) == BigInt("06070001", 16))
    assert(OooCpuConfig.value(default, 18) == BigInt("06070001", 16))
    assert(OooCpuConfig.value(default, 19) == BigInt("06090001", 16))

    val fourWay = default.copy(
      instructionCache = OooCacheGeometry(ways = 4, sets = 32, lineBytes = 64),
      dataCache = OooCacheGeometry(ways = 4, sets = 128, lineBytes = 64),
      level2Cache = OooCacheGeometry(ways = 4, sets = 256, lineBytes = 64)
    )
    assert(OooCpuConfig.value(fourWay, 17) == BigInt("06050003", 16))
    assert(OooCpuConfig.value(fourWay, 18) == BigInt("06070003", 16))
    assert(OooCpuConfig.value(fourWay, 19) == BigInt("06080003", 16))

    val directMapped = default.copy(
      instructionCache = OooCacheGeometry(ways = 1, sets = 128, lineBytes = 64),
      dataCache = OooCacheGeometry(ways = 1, sets = 128, lineBytes = 64),
      level2Cache = OooCacheGeometry(ways = 1, sets = 1024, lineBytes = 64)
    )
    assert(OooCpuConfig.value(directMapped, 17) == BigInt("06070000", 16))
    assert(OooCpuConfig.value(directMapped, 19) == BigInt("060a0000", 16))
    assert(OooCpuConfig.value(default, 3) == 0)
    assert(OooCpuConfig.value(default, 20) == 0)
    assert(OooCpuConfig.value(default, 0x40000000) == 0)
  }

  test("locked configurations match the active golden core") {
    for (config <- CoreConfig.Supported) {
      assert(config.xlen == 32)
      assert(config.gprCount == 32)
      assert(config.resetVector == BigInt("1c000000", 16))
      assert(config.resetDelayCycles == 1)
      assert(config.tlbEntries == 32)
      assert(config.btbEntries == 32)
      assert(config.rasEntries == 16)
      assert(config.returnStackDepth == 8)
      assert(config.instructionCache.capacityBytes == 8192)
      assert(config.dataCache.capacityBytes == 8192)
      assert(config.instructionCache == CacheGeometry(2, 256, 16))
      assert(config.dataCache == CacheGeometry(2, 256, 16))
      assert(config.fetchToDecodeWidth == 109)
      assert(config.executeToMemoryWidth == 425)
      assert(config.memoryToWritebackWidth == 493)
    }

    assert(!CoreConfig.Locked.laccEnabled)
    assert(CoreConfig.Locked.decodeToExecuteWidth == 350)
    assert(CoreConfig.LockedWithLacc.laccEnabled)
    assert(CoreConfig.LockedWithLacc.laccOpWidth == 2)
    assert(CoreConfig.LockedWithLacc.decodeToExecuteWidth == 353)
    assert(
      CoreConfig.Supported.map(config => (config.laccEnabled, config.diffTestEnabled)).toSet ==
        Set((false, false), (true, false), (false, true), (true, true))
    )
    assert(CoreConfig.LockedWithDiffTest.diffTestEnabled)
    assert(CoreConfig.LockedWithLaccAndDiffTest.laccEnabled)
    assert(CoreConfig.LockedWithLaccAndDiffTest.diffTestEnabled)
    assert(CoreConfig.Supported.forall(_.debugEnabled))
    assert(CoreConfig.Supported.forall(_.isa == IsaFeatures()))

    assert(CoreTopCompatConfig().tlbEntries == 32)
  }

  test("unsupported configuration changes fail closed") {
    val invalidConfigs = Seq[() => Any](
      () => CoreConfig(xlen = 64),
      () => CoreConfig(gprCount = 16),
      () => CoreConfig(resetVector = 0),
      () => CoreConfig(resetDelayCycles = 0),
      () => CoreConfig(tlbEntries = 16),
      () => CoreConfig(btbEntries = 64),
      () => CoreConfig(rasEntries = 8),
      () => CoreConfig(returnStackDepth = 4),
      () => CoreConfig(instructionCache = CacheGeometry(2, 128, 16)),
      () => CoreConfig(dataCache = CacheGeometry(4, 256, 16)),
      () => CoreConfig(laccEnabled = true, laccOpWidth = 3),
      () => CoreConfig(debugEnabled = false),
      () => CoreConfig(isa = IsaFeatures(barriers = false)),
      () => CacheGeometry(1, 256, 16),
      () => CacheGeometry(2, 512, 16),
      () => CacheGeometry(2, 256, 32)
    )

    invalidConfigs.foreach(make => intercept[IllegalArgumentException](make()))
  }
}
