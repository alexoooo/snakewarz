package ao.snakewarz.lab.log

/**
 * Turns what somebody typed into the expanded spec the log actually holds.
 *
 * [expandedSpec] writes an entrant out in full — every declared knob at the value it played under,
 * in the order the bot declares them — which is what keeps a log line meaning the same thing after a
 * default moves, and which is unusable as something to type. So a name here is a **subset**: a slug,
 * plus however many `name=value` pairs it takes to say which one is meant, in any order.
 * `puct:eval=horizon` finds the entrant playing that evaluation whatever else it was set to.
 *
 * ### Why a subset rather than a prefix
 *
 * Matching a prefix of the expanded string looks equivalent and is not, in three ways that all bite.
 * It forces the *declaration order* on the reader, so a knob added to the end of a bot's list is the
 * only one that can be named on its own and every other subset matches nothing. It cuts inside a
 * value, so `budget=10` silently selects `budget=1000`. And it makes a knob's position in a source
 * file part of the tool's interface, which is a thing nobody would write down on purpose.
 *
 * Values compare as numbers where both sides parse as one, so `cpuct=1.5` finds `cpuct=1.50` and
 * `budget=1000` finds `budget=1000`. An exact whole spec still short-circuits, because pasting one
 * back from the log should never be re-parsed.
 *
 * ### Ambiguity is an error that names the candidates
 *
 * A bare slug across sibling entrants is the common case and the message is the useful part: being
 * shown the four things it could have meant is what lets the next attempt be typed, where being told
 * to paste an expanded spec is what makes people give up and read the raw file.
 */
internal fun resolveSpec(typed: String, specs: Set<String>): String {
    if (typed in specs) {
        return typed
    }

    val slug = typed.substringBefore(':')
    val wanted = settingsIn(typed.substringAfter(':', ""))
    var candidates = specs.filter { it.substringBefore(':') == slug }
    require(candidates.isNotEmpty()) {
        "nothing in the log is called '$slug'. It holds: " +
            specs.map { it.substringBefore(':') }.distinct().joinToString()
    }

    for ((name, value) in wanted) {
        val narrowed = candidates.filter { matches(settingsIn(it.substringAfter(':', ""))[name], value) }
        require(narrowed.isNotEmpty()) {
            val played = candidates.mapNotNull { settingsIn(it.substringAfter(':', ""))[name] }.distinct()
            "no '$slug' in the log has $name=$value. " +
                if (played.isEmpty()) "It declares no '$name'." else "It played $name at ${played.joinToString()}."
        }
        candidates = narrowed
    }

    return when (candidates.size) {
        1 -> candidates.single()
        else -> error("'$typed' could be any of:\n  " + candidates.joinToString("\n  "))
    }
}

/** The `name=value` pairs of a spec's tail, refusing anything that is not one. */
private fun settingsIn(tail: String): Map<String, String> {
    val settings = LinkedHashMap<String, String>()
    for (pair in tail.split(',').filter { it.isNotBlank() }) {
        require('=' in pair) { "expected name=value, was '$pair'" }
        settings[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
    }
    return settings
}

/**
 * Whether a logged value is the one that was asked for.
 *
 * Numerically where both are numbers, because the log writes a knob back through its own reader —
 * `1.0` for a weight somebody set as `1`, `1.5` for a `cpuct` somebody would type as `1.50` — and a
 * name that has to reproduce the reader's spelling is a name nobody can guess.
 */
private fun matches(logged: String?, wanted: String): Boolean {
    if (logged == null) {
        return false
    }

    val one = logged.toDoubleOrNull()
    val other = wanted.toDoubleOrNull()
    return if (one != null && other != null) one == other else logged == wanted
}
