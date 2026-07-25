package ao.snakewarz.match

/**
 * The result of [MatchRecord.verify]: whether re-running the real bots reproduced the recording, and
 * if not, the first place it stopped doing so.
 */
public class ReplayVerification internal constructor(
    public val matches: Boolean,
    /** Index into the move stream where the two first differ, or `-1` if they differ elsewhere. */
    public val divergedAtMove: Int,
    public val detail: String,
) {
    override fun toString(): String =
        if (matches) "ReplayVerification(ok)" else "ReplayVerification(diverged: $detail)"

    internal companion object {
        val OK: ReplayVerification =
            ReplayVerification(true, -1, "the recorded move stream reproduces exactly")
    }
}
