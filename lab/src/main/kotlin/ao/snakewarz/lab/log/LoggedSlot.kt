package ao.snakewarz.lab.log

/** One seat of one match, as the log holds it — see [LoggedMatch]. */
internal class LoggedSlot(
    val seat: Int,
    /** Which entrant of the run this was, so a seat traces back to a column of the run's matrix. */
    val contestant: Int,
    /** The entrant in full — see [expandedSpec]. The identity every analysis groups by. */
    val spec: String,
    val budget: Int,
    val length: Int,
    val movesMade: Int,
    val alive: Boolean,
    /** `TRAPPED`, `SUICIDE`, `RESIGNED`, `FORFEIT`, or empty while it was still in the match. */
    val fate: String,
    val winner: Boolean,
) {
    override fun toString(): String = "LoggedSlot($seat, $spec, moves=$movesMade${if (alive) "" else ", $fate"})"
}
