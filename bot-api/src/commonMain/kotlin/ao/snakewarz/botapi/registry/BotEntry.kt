package ao.snakewarz.botapi.registry

import ao.snakewarz.botapi.knob.BotKnob

/**
 * One bot as the rest of the program sees it: a permanent [id], a name for humans, a way to make an
 * instance, and whatever it lets you tune.
 *
 * [displayName] is free to change — it is never persisted. [id] is not; see [BotId]. Neither are the
 * [knobs]' names, for the same reason: a configured match carries them in its replay URL.
 */
public class BotEntry(
    public val id: BotId,
    public val displayName: String,
    public val factory: BotFactory,
    /**
     * Everything this bot lets you tune, in the order a form would show it. Usually nothing.
     *
     * All of it, including what no player is meant to see — [offered] is the subset a form reads.
     */
    public val knobs: List<BotKnob> = emptyList(),
) {
    init {
        require(displayName.isNotBlank()) { "bot '$id' needs a display name" }
        require(knobs.size <= BotKnob.MAX_PER_BOT) {
            "bot '$id' declares ${knobs.size} knobs, and at most ${BotKnob.MAX_PER_BOT} fit on a form"
        }
        require(knobs.distinctBy { it.name }.size == knobs.size) {
            "bot '$id' declares a knob name twice: ${knobs.map { it.name }}"
        }
        require(knobs.count { it is BotKnob.Search } <= 1) {
            "bot '$id' declares more than one search allowance"
        }
        require(knobs.none { it is BotKnob.Param<*> && it.name == BotKnob.Search.NAME }) {
            "bot '$id' names a parameter '${BotKnob.Search.NAME}', which is reserved for the allowance"
        }
    }

    /**
     * The allowance this bot searches under, or `null` if it never spends any.
     *
     * Which is the honest way to ask "does this bot search?" — most shipped bots answer with a flood
     * fill and consume nothing, so offering them an allowance would be offering a control that
     * changes no move they ever play. Deliberately not a count: one would go stale the next time a
     * bot lands, and this is a file bot authors read first.
     */
    public val search: BotKnob.Search? = knobs.firstNotNullOfOrNull { it as? BotKnob.Search }

    /** The tunables, without the allowance, which is granted rather than chosen. */
    public val params: List<BotKnob.Param<*>> = knobs.filterIsInstance<BotKnob.Param<*>>()

    /**
     * What a form should offer: the knobs that are a [BotKnob.tradeoff], and nothing else.
     *
     * The split this pair makes is between two audiences rather than two kinds of value. [params] is
     * everything the bot can be handed — what `:lab` sweeps, what a replay carries, what a test pins
     * — and it is complete on purpose. This is the subset a player has any way to judge, which is
     * usually the allowance and at most one more. A bot with a dozen weights still shows two rows.
     */
    public val offered: List<BotKnob> = knobs.filter { it.tradeoff }

    override fun toString(): String = "BotEntry($id, \"$displayName\")"
}
