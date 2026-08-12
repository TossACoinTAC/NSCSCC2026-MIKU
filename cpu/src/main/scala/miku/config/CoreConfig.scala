package miku.config

/** Immutable configuration for the locked MIKU contest implementation.
  *
  * This contract deliberately fails closed. The active feature set is fixed; only the observed LACC
  * compatibility mode and the observation-only DiffTest interface vary in the supported elaboration
  * matrix. Any other configuration requires separately reviewed regression evidence.
  */
final case class CacheGeometry(ways: Int = 2, sets: Int = 256, lineBytes: Int = 16) {
  require(ways == 2, "only the locked two-way cache geometry is supported")
  require(sets == 256, "only the locked 256-set cache geometry is supported")
  require(lineBytes == 16, "only the locked 16-byte cache line is supported")

  val capacityBytes: Int = ways * sets * lineBytes
  val indexWidth: Int = 8
  val offsetWidth: Int = 4
  val tagWidth: Int = 20
}

final case class IsaFeatures(
    integer: Boolean = true,
    mulDiv: Boolean = true,
    privileged: Boolean = true,
    tlbAndDmw: Boolean = true,
    cachesAndCacheOps: Boolean = true,
    llSc: Boolean = true,
    barriers: Boolean = true,
    preload: Boolean = true,
    branchPrediction: Boolean = true,
    performanceCounters: Boolean = true
) {
  require(
    Seq(
      integer,
      mulDiv,
      privileged,
      tlbAndDmw,
      cachesAndCacheOps,
      llSc,
      barriers,
      preload,
      branchPrediction,
      performanceCounters
    )
      .forall(identity),
    "the current refactor only supports the complete locked active ISA feature set"
  )
}

final case class CoreConfig(
    xlen: Int = 32,
    gprCount: Int = 32,
    resetVector: BigInt = BigInt("1c000000", 16),
    resetDelayCycles: Int = 1,
    tlbEntries: Int = 32,
    btbEntries: Int = 32,
    rasEntries: Int = 16,
    returnStackDepth: Int = 8,
    instructionCache: CacheGeometry = CacheGeometry(),
    dataCache: CacheGeometry = CacheGeometry(),
    laccEnabled: Boolean = false,
    laccOpWidth: Int = 2,
    diffTestEnabled: Boolean = false,
    debugEnabled: Boolean = true,
    isa: IsaFeatures = IsaFeatures()
) {
  require(xlen == 32, "only the locked LA32R XLEN=32 configuration is supported")
  require(gprCount == 32, "only the locked 32-register GPR file is supported")
  require(
    resetVector == BigInt("1c000000", 16),
    "only the locked reset vector 0x1c000000 is supported"
  )
  require(resetDelayCycles == 1, "the golden core registers reset for exactly one cycle")
  require(tlbEntries == 32, "only the locked 32-entry TLB is supported")
  require(btbEntries == 32, "only the official 32-entry BTB is supported")
  require(rasEntries == 16, "only the official 16-entry return-site matcher is supported")
  require(returnStackDepth == 8, "only the golden eight-entry return stack is supported")
  require(instructionCache == CacheGeometry(), "unsupported instruction-cache geometry")
  require(dataCache == CacheGeometry(), "unsupported data-cache geometry")
  require(laccOpWidth == 2, "the locked LACC command is two bits wide")
  require(debugEnabled, "the official debug boundary is mandatory in the locked configuration")
  require(isa == IsaFeatures(), "unsupported active ISA feature configuration")

  val fetchToDecodeWidth: Int = 109
  val decodeToExecuteWidth: Int = if (laccEnabled) 353 else 350
  val executeToMemoryWidth: Int = 425
  val memoryToWritebackWidth: Int = 493
}

object CoreConfig {
  val Locked: CoreConfig = CoreConfig()
  val LockedWithLacc: CoreConfig = CoreConfig(laccEnabled = true)
  val LockedWithDiffTest: CoreConfig = CoreConfig(diffTestEnabled = true)
  val LockedWithLaccAndDiffTest: CoreConfig =
    CoreConfig(laccEnabled = true, diffTestEnabled = true)

  val Supported: Vector[CoreConfig] =
    Vector(Locked, LockedWithLacc, LockedWithDiffTest, LockedWithLaccAndDiffTest)
}
