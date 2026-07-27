package ao.snakewarz.botapi

/**
 * A bot's tuning knobs, as named strings with typed readers.
 *
 * Strings rather than a per-bot config type because these have to survive a round trip through a URL
 * and a registry that knows nothing about any particular bot. A missing name falls back to the
 * default; a name that is present but unparseable **throws**, because a silently ignored typo in a
 * search constant is the kind of bug that costs an afternoon.
 *
 * Backed by a `LinkedHashMap`, so [names] iterates in insertion order rather than in hash order.
 *
 * A bot should not usually reach for these readers directly. Declare a [BotKnob.Param] and call its
 * `read` instead: the declaration is what a form needs in order to offer the knob at all, and it is
 * *total* where these are strict — see [BotKnob.Param.read] for why the two differ.
 */
public class BotParams(values: Map<String, String> = emptyMap()) {
    private val values: Map<String, String> = LinkedHashMap(values)

    public val names: Set<String> get() = values.keys

    public val isEmpty: Boolean get() = values.isEmpty()

    public fun string(name: String, default: String): String = values[name] ?: default

    public fun int(name: String, default: Int): Int {
        val raw = values[name] ?: return default
        return raw.toIntOrNull() ?: throw IllegalArgumentException("parameter '$name' is not an integer: '$raw'")
    }

    public fun double(name: String, default: Double): Double {
        val raw = values[name] ?: return default
        return raw.toDoubleOrNull() ?: throw IllegalArgumentException("parameter '$name' is not a number: '$raw'")
    }

    public fun boolean(name: String, default: Boolean): Boolean {
        val raw = values[name] ?: return default
        return raw.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("parameter '$name' is not true or false: '$raw'")
    }

    /**
     * By what is set, not by identity — which `MatchSetup.equals` needs, because a per-slot
     * configuration is part of what makes two setups the same match.
     *
     * `Map` equality ignores insertion order, and that is right: two seats configured identically in
     * a different order are the same seat.
     */
    override fun equals(other: Any?): Boolean = other is BotParams && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    public companion object {
        public val EMPTY: BotParams = BotParams()
    }
}
