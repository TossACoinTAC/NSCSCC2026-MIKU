package miku.core

/** Single source-of-truth for the elaboration-time custom-instruction profile. */
object CustomInstructionBuildConfig {
  /** Allowed values are `disable` and `enabled`; change this line for a build variant. */
  final val CUSTOM_PROFILE: String = "disable"

  private val normalized = CUSTOM_PROFILE.trim.toLowerCase

  val selectedProfile: CustomInstructionProfile = normalized match {
    case "disable" | "disabled" | "off" => CustomInstructionProfile.Disabled
    case "enabled"                         => ContestCustomInstructionProfiles.Enabled
    case other =>
      throw new IllegalArgumentException(
        s"CUSTOM_PROFILE must be disable or enabled, got '$other'"
      )
  }
}
