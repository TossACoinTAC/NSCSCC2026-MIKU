package miku.core

/** Official contest instructions are registered here after their statement is known. */
object ContestCustomInstructionProfiles {
  // The switch is useful before the official instruction catalog exists. Tests and downstream
  // branches can construct non-empty profiles with the public descriptors directly.
  val Enabled: CustomInstructionProfile = CustomInstructionProfile("enabled", Vector.empty)
  val Available: Vector[CustomInstructionProfile] = Vector(Enabled)
  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector.empty
}
