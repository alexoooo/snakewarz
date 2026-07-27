package ao.snakewarz.core

/**
 * A bot's search allowance for one turn, counted in **evaluations and never in milliseconds**.
 *
 * Counting work rather than time is what makes a match reproducible: a clock would make the number
 * of MCTS iterations depend on the machine, and the recorded move stream with it. It is also why no
 * module below `:ui` can reach a clock at all.
 *
 * ### An evaluation, not a simulated move
 *
 * One unit buys one *judgement of a position* — a rollout played to the end, a static appraisal, one
 * iteration of a tree search. That is the unit because it is the one every search bot has in common
 * and the one that scales strength: doubling the evaluations roughly doubles the search whatever the
 * bot does inside them, whereas doubling the *simulated moves* buys a rollout bot twice the search
 * and a bot that never simulates nothing at all.
 *
 * Two evaluations of different kinds do not cost the same wall clock, and nothing here pretends they
 * do. The exchange rate is the charge each bot passes to `Scratch.playout` — `bots/search`'s
 * `EvaluationCost` is where the figures live and where calibrating them happens. They are all `1`
 * today, which is a starting point rather than a measurement.
 *
 * A budget bounds iteration, which is where essentially all of a search bot's time goes. It cannot
 * preempt a bot spinning in a loop that consumes nothing — single-threaded wasm has no way to do
 * that, and pretending otherwise would be worse than stating it. The mitigations are the shared bot
 * contract suite in CI and the frame-time guard in the renderer.
 */
public class Budget(public val limit: Int) {
    init {
        require(limit >= 0) { "limit must not be negative, was $limit" }
    }

    public var consumed: Int = 0
        private set

    public val remaining: Int get() = limit - consumed

    public val exhausted: Boolean get() = consumed >= limit

    /**
     * Charges [units] against the budget, returning `false` — and charging nothing — once there is
     * not enough left. Search loops spin on this rather than on a counter of their own.
     */
    public fun tryConsume(units: Int = 1): Boolean {
        require(units >= 0) { "units must not be negative, was $units" }

        if (units > remaining) {
            return false
        }
        consumed += units
        return true
    }

    /** Restores the full allowance. Called by the driver between turns, never by a bot. */
    public fun reset() {
        consumed = 0
    }

    override fun toString(): String = "Budget($consumed/$limit)"
}
