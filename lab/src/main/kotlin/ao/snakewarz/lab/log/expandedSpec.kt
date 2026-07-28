package ao.snakewarz.lab.log

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.tournament.Contestant

/**
 * A contestant written out in full: every declared knob at the value it actually played under.
 *
 * `uct` and `uct:exploration=5.0` are the same bot **today**, and the whole point of the tuning loop
 * is that one day they will not be. A log that recorded the short form would silently change meaning
 * the moment a default moved, pooling two different bots under one name and averaging away the very
 * improvement that moved it. So nothing is left implicit: the allowance is resolved against the
 * batch's own figure, and every knob the registry declares is spelled out even when nobody set it.
 *
 * The result is a valid entrant spec, so a line of the log can be pasted straight back onto the
 * command line — which is what makes "play that again" a copy rather than a reconstruction.
 *
 * Knobs come out in the order the bot declares them, so the same configuration always writes the
 * same string and two log lines can be compared as text.
 */
internal fun expandedSpec(contestant: Contestant, registry: BotRegistry, budgetPerTurn: Int): String {
    val entry = registry.entryOf(contestant.bot)

    return buildString {
        append(contestant.bot.slug)
        append(':')
        append(BotKnob.Search.NAME).append('=').append(contestant.budgetIn(budgetPerTurn))
        for (knob in entry.params) {
            append(',').append(knob.name).append('=').append(knob.read(contestant.params).toString())
        }
    }
}
