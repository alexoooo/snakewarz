package ao.snakewarz.lab.tune

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a knob could be set to next, given what it is set to now.
 *
 * The search space is read straight off the bot's own declaration and never listed here: a knob says
 * its own range and its own step, so a tuner works on a bot it has never heard of and a bot author
 * who adds a knob has already told the tuner everything it needs. Nothing in this file names a bot,
 * a knob or a value (CC-17).
 *
 * [stride] is how many declared steps a proposal moves. A search starts wide, because the declared
 * step is usually far finer than the difference worth a batch of games, and halves it each time a
 * pass finds nothing — so the same code does the coarse sweep and the final polish.
 */
internal object KnobSpace {
    /** Values worth trying for [knob], nearest first, excluding whatever it is set to now. */
    fun neighbours(knob: BotKnob.Param<*>, current: BotParams, stride: Int): List<String> {
        require(stride >= 1) { "a proposal has to move at least one step, was $stride" }

        return when (knob) {
            is BotKnob.Integer -> {
                val at = knob.read(current)
                listOf(at - stride * knob.step, at + stride * knob.step)
                    .filter { it in knob.min..knob.max && it != at }
                    .map { it.toString() }
            }

            is BotKnob.Decimal -> {
                val at = knob.read(current)
                listOf(at - stride * knob.step, at + stride * knob.step)
                    .map { it.coerceIn(knob.min, knob.max) }
                    .map { format(it, knob.step) }
                    .filter { it != format(at, knob.step) }
                    .distinct()
            }

            // A choice has no gradient to walk, so every other value is a neighbour and the stride
            // means nothing to it. Offered on the first pass only -- see TuneCommand.
            is BotKnob.Choice -> knob.values.filter { it != knob.read(current) }

            is BotKnob.Flag -> listOf((!knob.read(current)).toString())
        }
    }

    /** [params] with [knob] set to [value], leaving everything else where it was. */
    fun with(params: BotParams, knob: BotKnob.Param<*>, value: String): BotParams {
        val updated = LinkedHashMap<String, String>()
        for (name in params.names) {
            updated[name] = params.string(name, "")
        }
        updated[knob.name] = value
        return BotParams(updated)
    }

    /** Whether [knob] has anywhere to go at this [stride], so a pass can skip it rather than test it. */
    fun exhausted(knob: BotKnob.Param<*>, current: BotParams, stride: Int): Boolean =
        neighbours(knob, current, stride).isEmpty()

    /**
     * A decimal knob's value, written the way its own step implies.
     *
     * A raw `Double.toString` produces `0.30000000000000004` for a value that arrived by adding
     * `0.05` six times, and that string goes into a replay URL, a log line and a column heading. So
     * the value is snapped to its step and printed to the precision the step actually has.
     */
    private fun format(value: Double, step: Double): String {
        val snapped = (value / step).roundToLong() * step
        val text = String.format(Locale.ROOT, "%.${decimals(step)}f", snapped)
        return if ('.' in text) text.trimEnd('0').trimEnd('.').ifEmpty { "0" } else text
    }

    /** How many decimal places [step] needs before it stops changing anything. */
    private fun decimals(step: Double): Int {
        var places = 0
        var scaled = step
        while (places < MAX_DECIMALS && abs(scaled - scaled.roundToInt()) > TOLERANCE) {
            scaled *= 10.0
            places++
        }
        return places
    }

    /** Beyond this a step is finer than anything a batch of games could tell apart. */
    private const val MAX_DECIMALS = 6

    private const val TOLERANCE = 1e-9
}
