package ao.snakewarz.botapi

/**
 * A tunable a bot declares, so that something which has never heard of that bot can offer it.
 *
 * The registry is the only thing every layer can see — `:ui` may not depend on `:bots`, and `:match`
 * resolves a slot through [BotRegistry] rather than through a class — so a list of these hanging off
 * a [BotEntry] is the only way a form could ever learn that `uct` has an exploration constant. That
 * is why the declaration lives here and not beside the bot that reads it.
 *
 * **A [Param] is its own reader.** A bot writes `EXPLORATION.read(setup.params)` and never repeats
 * the literal, so the default a form shows and the default a constructor falls back on cannot drift
 * apart: there is only one of them, and it is this object.
 *
 * Declaring a knob is optional. A bot with no `knobs` is configured by nobody, which is the seven
 * shipped bots that have nothing to tune.
 */
public sealed class BotKnob(
    /**
     * The key this travels under.
     *
     * Frozen once released for the same reason a [BotId] is, and with the same narrow charset: it
     * goes into the replay URL of every match somebody configured.
     */
    public val name: String,
    /** What a form calls it. Never persisted, so free to change. */
    public val label: String,
    /** One sentence, shown as the control's tooltip. */
    public val help: String,
) {
    init {
        require(name.isNotEmpty()) { "a knob name must not be empty" }
        require(name.length <= MAX_NAME_LENGTH) {
            "a knob name must be at most $MAX_NAME_LENGTH characters, was '$name'"
        }
        require(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            "a knob name must be letters and digits, was '$name'"
        }
        require(label.isNotBlank()) { "knob '$name' needs a label" }
    }

    /**
     * A knob whose value travels in [BotParams]: declared, defaulted, validated and read here.
     *
     * [read] is **total** — an unparseable or out-of-range value falls back on [default] rather than
     * throwing, which is a deliberate departure from [BotParams]' own typed readers. The reason is
     * where a bot gets built: `Match` constructs every slot in a field initializer, outside the
     * `try` that guards `chooseMove`, and one of the routes into it is an arbitrary `#r=` fragment
     * somebody pasted. A throw there has nothing to catch it and takes the page down. The strict
     * reading is still available, and is what a form should use: see [reject].
     */
    public sealed class Param<T>(name: String, label: String, help: String) : BotKnob(name, label, help) {
        public abstract val default: T

        /** [default] as a form field holds it. `read(BotParams(name to defaultText)) == default`. */
        public abstract val defaultText: String

        /** [text] parsed and range-checked, or [default] if it is neither. Never throws. */
        public abstract fun read(text: String): T

        public fun read(params: BotParams): T = read(params.string(name, defaultText))

        /** What is wrong with [text] in a few words, or `null` if nothing is. */
        public abstract fun reject(text: String): String?

        /** Whether [text] means [default], comparing values rather than spelling: `5` is `5.0`. */
        public fun isDefault(text: String): Boolean = reject(text) == null && read(text) == default
    }

    public class Integer(
        name: String,
        label: String,
        help: String,
        override val default: Int,
        public val min: Int,
        public val max: Int,
        public val step: Int = 1,
    ) : Param<Int>(name, label, help) {
        init {
            require(min <= max) { "knob '$name' has a range of $min..$max" }
            require(default in min..max) { "knob '$name' defaults to $default, outside $min..$max" }
            require(step > 0) { "knob '$name' has a step of $step" }
        }

        override val defaultText: String get() = default.toString()

        override fun read(text: String): Int = text.trim().toIntOrNull()?.coerceIn(min, max) ?: default

        override fun reject(text: String): String? {
            val value = text.trim().toIntOrNull() ?: return "a whole number"
            return if (value in min..max) null else "$min to $max"
        }

        override fun toString(): String = "Integer($name=$default)"
    }

    public class Decimal(
        name: String,
        label: String,
        help: String,
        override val default: Double,
        public val min: Double,
        public val max: Double,
        public val step: Double,
    ) : Param<Double>(name, label, help) {
        init {
            require(min <= max) { "knob '$name' has a range of $min..$max" }
            require(default in min..max) { "knob '$name' defaults to $default, outside $min..$max" }
            require(step > 0.0) { "knob '$name' has a step of $step" }
        }

        override val defaultText: String get() = default.toString()

        override fun read(text: String): Double = text.trim().toDoubleOrNull()?.coerceIn(min, max) ?: default

        override fun reject(text: String): String? {
            val value = text.trim().toDoubleOrNull() ?: return "a number"
            return if (value in min..max) null else "$min to $max"
        }

        override fun toString(): String = "Decimal($name=$default)"
    }

    public class Flag(
        name: String,
        label: String,
        help: String,
        override val default: Boolean,
    ) : Param<Boolean>(name, label, help) {
        override val defaultText: String get() = default.toString()

        override fun read(text: String): Boolean = text.trim().toBooleanStrictOrNull() ?: default

        override fun reject(text: String): String? =
            if (text.trim().toBooleanStrictOrNull() == null) "true or false" else null

        override fun toString(): String = "Flag($name=$default)"
    }

    /**
     * The turn allowance this bot searches under.
     *
     * Deliberately **not** a [Param]. An allowance is granted by the engine rather than chosen by
     * the bot, and it is counted in the header of a replay rather than in a map of strings — so it
     * never travels in [BotParams] and a bot never reads one. It is declared alongside the params
     * anyway because this list is the only place that knows a bot *searches at all*: a bot that
     * never touches `Turn.scratch` declares none, and a form then offers it no allowance field,
     * which is right, because a slider that changes nothing is worse than no slider.
     *
     * There is no default here either, for the same reason: how much a match grants is the match's
     * policy — `MatchSetup.DEFAULT_BUDGET_PER_TURN` — and a bot has no business naming it. What a
     * bot can usefully say is the range over which it is worth moving.
     */
    public class Search(
        public val min: Int,
        public val max: Int,
        public val step: Int,
        label: String = "Budget",
        help: String = "Simulated moves this bot may spend on one turn.",
    ) : BotKnob(NAME, label, help) {
        init {
            require(min >= 0) { "a search allowance must not be negative, was $min" }
            require(min <= max) { "a search allowance has a range of $min..$max" }
            require(step > 0) { "a search allowance has a step of $step" }
        }

        override fun toString(): String = "Search($min..$max)"

        public companion object {
            /** Reserved: no [Param] may take this name, because the two would collide in a form. */
            public const val NAME: String = "budget"
        }
    }

    public companion object {
        /** Bounded so a decoder can reject a corrupt payload before allocating from it. */
        public const val MAX_NAME_LENGTH: Int = 32

        /** The same, for the value beside the name. Nothing tunable needs more. */
        public const val MAX_VALUE_LENGTH: Int = 32

        /** And the same again for how many one bot may declare. A form has to fit on the page. */
        public const val MAX_PER_BOT: Int = 16
    }
}
