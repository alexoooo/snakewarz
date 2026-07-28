package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.tournament.Contestant

/**
 * A logged spec read back as a contestant, with everything it shares with the shipped bot dropped.
 *
 * The log writes every knob out in full so that a line keeps its meaning when a default moves. A
 * ladder wants the opposite: the shortest thing that still tells two entrants apart, because a
 * column heading reading `puct@1k/1.5/1.0/0.2/0.35/0.9` says nothing a reader can use.
 *
 * So this drops the knobs sitting at the value the registry declares **today**. That is the right
 * comparison rather than a lossy one: after a sweep promotes a constant, an old entrant that used to
 * be stock starts showing the knob it now differs on, which is exactly what somebody reading a
 * ladder that spans the change needs to see.
 *
 * A spec naming a bot nobody has registered any more comes back with its knobs intact, since there
 * is nothing to compare them against.
 */
internal fun entrantOf(spec: String, registry: BotRegistry): Contestant {
    val slug = spec.substringBefore(':')
    val entry = registry[BotId(slug)]

    var budget: Int? = null
    val params = LinkedHashMap<String, String>()

    for (pair in spec.substringAfter(':', "").split(',').filter { it.isNotBlank() }) {
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=').trim()

        if (name == BotKnob.Search.NAME) {
            budget = value.toIntOrNull()
            continue
        }

        val knob = entry?.params?.firstOrNull { it.name == name }
        if (knob == null || !knob.isDefault(value)) {
            params[name] = value
        }
    }

    return Contestant(BotId(slug), budget, BotParams(params))
}
