package ao.snakewarz.ui.model

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId

/**
 * One filled seat of the new-match form: who is playing, and how they were set up.
 *
 * The bot and its settings leave the DOM together, in one object, and that is what makes the empty
 * seats safe to drop. [MatchOptions.slots] is compacted — a match with slots 1, 2 and 4 filled is a
 * three-slot match — so a per-seat side channel keyed on picker index would have to be re-aligned by
 * hand at every step downstream. There is no index here to get wrong.
 *
 * [params] holds only what departs from the bot's declared defaults, so a seat nobody touched is
 * empty and the match it starts encodes exactly as it always did.
 */
internal class SlotOptions(
    val bot: BotId,
    val budgetPerTurn: Int,
    val params: BotParams,
) {
    override fun toString(): String = "SlotOptions($bot, budget=$budgetPerTurn, $params)"
}
