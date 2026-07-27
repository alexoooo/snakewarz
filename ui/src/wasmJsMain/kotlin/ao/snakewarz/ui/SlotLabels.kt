package ao.snakewarz.ui

import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.core.SnakeId
import ao.snakewarz.match.Contestant
import ao.snakewarz.match.MatchSetup

/**
 * What each seat of a match is called, said precisely enough to tell two of them apart.
 *
 * The bot's display name on its own is not enough, because a seat is a *configured* bot: two slots
 * can hold `uct` at two allowances, which is the first question this testbed exists to ask. So a
 * label is the name plus [Contestant.suffix] — `UCT @4k`, `UCT *` — and where even that leaves two
 * seats identical, every one of them is numbered.
 *
 * The suffix comes from `:match` rather than being formatted here, so the sidebar and the tournament
 * matrix cannot start disagreeing about what `@4k` means. The numbering deliberately does *not*:
 * `TournamentTable` leaves the first of a repeated heading bare, which is right for a column that has
 * a legend under it, while a list of four rows reads better as `Random ·1` and `Random ·2` than as
 * `Random` and `Random ·2`.
 *
 * Built once per [MatchSetup] rather than once per frame — a label changes when the match does and
 * at no other time.
 */
internal class SlotLabels(setup: MatchSetup, registry: BotRegistry) {
    private val labels: List<String> = number(describe(setup, registry))

    operator fun get(slot: Int): String = labels.getOrElse(slot) { "" }

    /** As [get], but says who [SnakeId.NONE] is — a drawn match has a winner of nobody. */
    fun of(id: SnakeId): String = if (id.isNone) NOBODY else get(id.index)

    override fun toString(): String = labels.toString()

    private companion object {
        const val NOBODY = "nobody"

        /** The name, plus whatever this seat had done to it. Usually just the name. */
        fun describe(setup: MatchSetup, registry: BotRegistry): List<String> =
            List(setup.slotCount) { slot ->
                val bot = setup.slots[slot]
                val name = registry[bot]?.displayName ?: bot.slug
                // Against the match's own allowance, not the shipped default: in a match where every
                // slot was given 4k, none of them is the odd one out and none of them says so.
                val suffix = Contestant(
                    bot = bot,
                    budgetPerTurn = setup.budgetFor(slot).takeIf { it != setup.budgetPerTurn },
                    params = setup.paramsFor(slot),
                ).suffix

                if (suffix.isEmpty()) name else "$name $suffix"
            }

        /** Numbers each group of identical labels `·1`, `·2`, …, and leaves the unique ones alone. */
        fun number(described: List<String>): List<String> {
            val counts = LinkedHashMap<String, Int>()
            for (label in described) {
                counts[label] = (counts[label] ?: 0) + 1
            }
            if (counts.size == described.size) {
                return described
            }

            val numbered = LinkedHashMap<String, Int>()
            return described.map { label ->
                if (counts[label] == 1) {
                    label
                } else {
                    val ordinal = (numbered[label] ?: 0) + 1
                    numbered[label] = ordinal
                    "$label ·$ordinal"
                }
            }
        }
    }
}
