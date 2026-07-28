package ao.snakewarz.ui.model

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.Contestant

/**
 * What each seat of a match is called, said precisely enough to tell two of them apart.
 *
 * The bot's display name on its own is not enough, because a seat is a *configured* bot: two slots
 * can hold `puct` at two allowances or two value functions, which is the question this testbed
 * exists to ask. So a label is the name plus [Contestant.suffix] — `UCT - 4k`,
 * `PUCT - 1k/rollout` — and where even that leaves two seats identical, every one of them is
 * numbered.
 *
 * **A search bot names its allowance whether or not it is unusual**, and a bot with a mode names the
 * mode. Those are what a seat *is* rather than what was changed about it, so a scoreboard that
 * showed them only when they departed from the default would hide them in exactly the match where
 * every seat is at the default and they are the question — see [settings]. A bot that declares no
 * [BotKnob.Search] is granted an allowance anyway and spends none of it, so saying so would be
 * noise, which is why this asks the registry rather than the setup.
 *
 * The suffix comes from `:match` rather than being formatted here, so the sidebar and the tournament
 * matrix cannot start disagreeing about what `4k` means. The numbering deliberately does *not*:
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

        /** The name, plus the settings this seat is playing under. */
        fun describe(setup: MatchSetup, registry: BotRegistry): List<String> =
            List(setup.slotCount) { slot ->
                val entry = registry[setup.slots[slot]]
                val name = entry?.displayName ?: setup.slots[slot].slug
                val suffix = Contestant(
                    bot = setup.slots[slot],
                    budgetPerTurn = setup.budgetFor(slot).takeIf { entry?.search != null },
                    params = settings(entry, setup.paramsFor(slot)),
                ).suffix

                if (suffix.isEmpty()) name else "$name - $suffix"
            }

        /**
         * The knob values worth naming: what this seat *is*, plus what was changed about it.
         *
         * A [BotKnob.Choice] a player is offered names a mode rather than a value — `territory`
         * against `survival` is two different bots wearing one slug — so it is named whether or not
         * it is the default, for the same reason the allowance is. Two seats of `puct` at two
         * evaluations is the experiment this bot exists for, and a scoreboard reading `PUCT - 1k`
         * beside `PUCT - 1k/survival` makes you remember which the bare one was.
         *
         * Everything else is named only when it has been moved, and *moved* is asked of the knob
         * rather than of the map — `exploration=5.0` arriving from a `#r=` fragment is the default
         * spelled out, not a departure, and a label that could not tell would read differently for
         * two seats playing identically. A number is not an identity: every `uct` in existence runs
         * at an exploration of 5.0, so putting it in every label would cost the width of the panel to
         * say nothing.
         *
         * Declaration order rather than the order somebody happened to set them in, so two seats of
         * one bot always read left to right the same way.
         */
        fun settings(entry: BotEntry?, set: BotParams): BotParams {
            // A bot the registry has never heard of has no declaration to read, and whatever the
            // replay carried is still the truth about the seat.
            val declared = entry?.params ?: return set

            val named = LinkedHashMap<String, String>()
            for (knob in declared) {
                val value = set.string(knob.name, knob.defaultText)
                if ((knob is BotKnob.Choice && knob.tradeoff) || !knob.isDefault(value)) {
                    named[knob.name] = value
                }
            }
            return BotParams(named)
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
