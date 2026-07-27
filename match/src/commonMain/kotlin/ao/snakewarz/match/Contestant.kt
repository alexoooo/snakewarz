package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotParams

/**
 * A bot as a tournament enters it: which bot, and what was changed about it.
 *
 * Identity is the **whole configuration** rather than the [bot] alone, and that is the point. "Is a
 * bigger allowance worth anything?" is the first question a testbed of search bots should be able to
 * answer and the one a list of ids cannot even express — `uct` against `uct` is a duplicate, so
 * until now the batch runner refused the most interesting experiment it had.
 *
 * Two *identically* configured entries are still a duplicate, and [TournamentConfig] still says so.
 * Playing a bot against a copy of itself measures the seating and the seeds, and nothing else.
 */
public class Contestant(
    public val bot: BotId,
    /**
     * What this one is allowed to spend, or `null` to take whatever the batch grants.
     *
     * Absent rather than pre-filled with the default, so that [TournamentConfig.budgetPerTurn] still
     * means something: a contestant that filled it in would override the batch's own figure every
     * time and leave that setting with no way to take effect.
     */
    public val budgetPerTurn: Int? = null,
    public val params: BotParams = BotParams.EMPTY,
) {
    init {
        require(budgetPerTurn == null || budgetPerTurn >= 0) { "budgetPerTurn must not be negative" }
    }

    /** Whether anything about this departs from the bot as the batch would otherwise run it. */
    public val configured: Boolean
        get() = budgetPerTurn != null || !params.isEmpty

    /** What this one may spend in a batch granting [fallback] by default. */
    public fun budgetIn(fallback: Int): Int = budgetPerTurn ?: fallback

    /**
     * What makes this different from the bot as the batch would otherwise run it: `@4k` for an
     * allowance of its own, `*` for anything tuned, and empty for a stock entry.
     *
     * Split out of [label] because a scoreboard has the bot's *display name* already and needs only
     * the part that tells two seats apart. Keeping one copy of the formatting is what makes the
     * sidebar and the matrix agree by construction rather than by somebody remembering to.
     */
    public val suffix: String = buildString {
        if (budgetPerTurn != null) {
            append('@').append(compact(budgetPerTurn))
        }
        if (!params.isEmpty) {
            append('*')
        }
    }

    /**
     * What a matrix row is called: the slug, plus what makes it different when the slug is not
     * enough.
     *
     * Kept short because the table is fixed-width text in a narrow panel — `uct` and `uct@4k` sit
     * beside each other legibly, and the settings themselves go in a legend under the grid rather
     * than into the column headings. Integer arithmetic only, like the rest of that table.
     */
    public val label: String = bot.slug + suffix

    /** The configuration spelled out, for the legend under a matrix. Empty when there is none. */
    public val summary: String = buildString {
        if (budgetPerTurn != null) {
            append("budget=").append(budgetPerTurn)
        }
        for (name in params.names) {
            if (isNotEmpty()) {
                append(' ')
            }
            append(name).append('=').append(params.string(name, ""))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contestant) return false
        return bot == other.bot && budgetPerTurn == other.budgetPerTurn && params == other.params
    }

    override fun hashCode(): Int {
        var result = bot.hashCode()
        result = 31 * result + (budgetPerTurn ?: 0)
        result = 31 * result + params.hashCode()
        return result
    }

    override fun toString(): String = if (summary.isEmpty()) label else "$label ($summary)"

    private companion object {
        /** `40000` as `40k`, and anything that does not divide evenly as itself. */
        fun compact(value: Int): String = if (value >= 1_000 && value % 1_000 == 0) "${value / 1_000}k" else "$value"
    }
}
