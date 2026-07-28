package ao.snakewarz.botapi.knob

import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry

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
    /**
     * Whether this is a choice with no single best answer — the only kind a player is offered.
     *
     * A tradeoff has no optimum to find: many values are valid, each produces a visibly different
     * bot, and which one you want depends on what you are after. An allowance is the type case —
     * bigger is stronger and slower, and neither end is wrong.
     *
     * Everything else is a **hyperparameter**, and a hyperparameter search picks one better than a
     * person staring at a form can. Those stay declared — `:lab` sweeps them, a replay carries them,
     * a test pins them — and stay off the sidebar, where a number nobody can judge is worse than no
     * number at all. See [BotEntry.offered], which is what a form reads.
     *
     * Defaults to `false`, so offering a knob to a player is the case that has to be argued rather
     * than the case that happens by forgetting.
     */
    public val tradeoff: Boolean = false,
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
    public sealed class Param<T>(
        name: String,
        label: String,
        help: String,
        tradeoff: Boolean,
    ) : BotKnob(name, label, help, tradeoff) {
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
        tradeoff: Boolean = false,
    ) : Param<Int>(name, label, help, tradeoff) {
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
        tradeoff: Boolean = false,
    ) : Param<Double>(name, label, help, tradeoff) {
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

    /**
     * A knob whose value is one of a fixed set of names.
     *
     * The shape that was missing, and `UctBot.ROLLOUT_DEPTH` is the evidence: a number standing in
     * for a mode, because a number was what there was.
     *
     * **Names rather than ordinals**, and that is the whole design. A value travels in the replay URL
     * beside its knob name, so it is frozen by exactly the argument that freezes the name — an
     * [Integer] over `0..2` writes `eval=2`, and reordering the list it indexes silently changes what
     * every existing replay means, with nothing in the codec able to tell. `eval=territory` survives the
     * reordering, and a value that is dropped outright reads as the default rather than as its
     * neighbour.
     *
     * Every value is bounded by [MAX_VALUE_LENGTH] here rather than checked at the codec, so a
     * payload stays decodable by construction.
     */
    public class Choice(
        name: String,
        label: String,
        help: String,
        override val default: String,
        /** Every value this may take, in the order a form should offer them. */
        public val values: List<String>,
        tradeoff: Boolean = false,
    ) : Param<String>(name, label, help, tradeoff) {
        init {
            require(values.isNotEmpty()) { "knob '$name' offers no values" }
            require(values.distinct().size == values.size) { "knob '$name' offers a value twice: $values" }
            require(default in values) { "knob '$name' defaults to '$default', which it does not offer" }
            for (value in values) {
                require(value.isNotEmpty() && value.length <= MAX_VALUE_LENGTH) {
                    "knob '$name' offers '$value', which is not 1 to $MAX_VALUE_LENGTH characters"
                }
            }
        }

        override val defaultText: String get() = default

        override fun read(text: String): String = text.trim().takeIf { it in values } ?: default

        override fun reject(text: String): String? =
            if (text.trim() in values) null else "one of ${values.joinToString(", ")}"

        override fun toString(): String = "Choice($name=$default of ${values.size})"
    }

    public class Flag(
        name: String,
        label: String,
        help: String,
        override val default: Boolean,
        tradeoff: Boolean = false,
    ) : Param<Boolean>(name, label, help, tradeoff) {
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
     *
     * The one knob that is a [tradeoff] by default: bigger is stronger and slower, there is no value
     * that is simply right, and a player watching a match can see which end they want.
     */
    public class Search(
        public val min: Int,
        public val max: Int,
        public val step: Int,
        label: String = "Budget",
        help: String = "Evaluations — rollouts, appraisals — this bot may spend on one turn.",
        tradeoff: Boolean = true,
    ) : BotKnob(NAME, label, help, tradeoff) {
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

        /**
         * And the same again for how many one bot may declare.
         *
         * A bound on the *declaration* rather than on the form — [BotEntry.offered] is what a form
         * shows, and it is a handful — so this is really a bound on what a replay payload can be
         * made to carry, which is why the codec checks against it too.
         */
        public const val MAX_PER_BOT: Int = 16
    }
}
