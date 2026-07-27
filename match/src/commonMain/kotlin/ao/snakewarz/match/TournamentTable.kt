package ao.snakewarz.match

/**
 * The win-rate matrix: who beat whom, how often.
 *
 * A [Tournament] fills one of these in as it plays, and hands out the same instance every time it is
 * asked — so a panel can read it once a frame while the batch is still running, without allocating a
 * copy per frame. [copy] is there for when a result needs to outlive the run that produced it.
 *
 * Draws are counted separately rather than folded into either side. They are not rare here: two
 * snakes can fill a board and be eliminated on the same turn, and a cautious pair can run the turn
 * limit out. Calling one a half-win each is a reasonable *summary* — which is what [scoreRate] does,
 * and it is the only place the two are mixed.
 */
public class TournamentTable internal constructor(
    public val contestants: List<Contestant>,
) {
    public val size: Int = contestants.size

    /** `winCounts[winner * size + loser]`. Losses are the same array read the other way round. */
    private val winCounts = IntArray(size * size)

    /** Symmetric: a draw is recorded in both directions, so every query below reads one cell. */
    private val drawCounts = IntArray(size * size)

    /** The index [contestant] plays under, or `-1` if it is not in this tournament. */
    public fun indexOf(contestant: Contestant): Int = contestants.indexOf(contestant)

    public fun wins(winner: Int, loser: Int): Int = winCounts[winner * size + loser]

    public fun draws(one: Int, other: Int): Int = drawCounts[one * size + other]

    public fun played(one: Int, other: Int): Int =
        wins(one, other) + wins(other, one) + draws(one, other)

    public fun wins(contestant: Int): Int = total(winCounts, contestant)

    public fun losses(contestant: Int): Int {
        var sum = 0
        for (other in 0 until size) {
            sum += wins(other, contestant)
        }
        return sum
    }

    public fun draws(contestant: Int): Int = total(drawCounts, contestant)

    public fun played(contestant: Int): Int = wins(contestant) + losses(contestant) + draws(contestant)

    /**
     * Matches won plus half of those drawn, in `0.0..1.0`, and `0.0` for a contestant yet to play.
     *
     * Half a point for a draw is the chess convention, and it is the right one here for the same
     * reason: a bot that survives to the turn limit against a stronger opponent has done something,
     * and a table that scored it zero would rank it below one that walked into a wall.
     */
    public fun scoreRate(contestant: Int): Double {
        val played = played(contestant)
        if (played == 0) {
            return 0.0
        }
        return (wins(contestant) + draws(contestant) / 2.0) / played
    }

    /**
     * Contestant indices strongest first.
     *
     * Ties break by wins and then by entry order, so the ranking is a function of the results and
     * never of iteration order — the same rule the rest of the project follows for determinism.
     */
    public fun ranking(): List<Int> =
        (0 until size).sortedWith(
            compareByDescending<Int> { scoreRate(it) }.thenByDescending { wins(it) },
        )

    /** An independent copy, for keeping a result after the tournament that produced it moves on. */
    public fun copy(): TournamentTable {
        val clone = TournamentTable(contestants)
        winCounts.copyInto(clone.winCounts)
        drawCounts.copyInto(clone.drawCounts)
        return clone
    }

    /**
     * The matrix as text, rows beating columns — the shape of the ladder tables in `docs/`.
     *
     * Integer arithmetic only, and no `Double` ever reaches a string: number formatting is a
     * surprisingly large thing to drag into a wasm bundle, and a percentage rounded here reads the
     * same as one formatted properly.
     *
     * Anything configured is named in a legend under the grid rather than in its column heading. One
     * bot may enter twice at two allowances, and `uct` beside `uct@4k` stays readable in a narrow
     * panel where `uct budget=4000 exploration=1.4` would not.
     */
    override fun toString(): String {
        val names = headings()
        val width = maxOf(names.maxOf { it.length }, MIN_COLUMN)

        return buildString {
            append("".padEnd(width))
            names.forEach { append(" | ").append(it.padStart(width)) }
            append(" | ").append("score".padStart(width)).appendLine()

            for (row in 0 until size) {
                append(names[row].padEnd(width))
                for (column in 0 until size) {
                    val cell = if (row == column) "-" else wins(row, column).toString()
                    append(" | ").append(cell.padStart(width))
                }
                append(" | ").append("${percent(scoreRate(row))}%".padStart(width)).appendLine()
            }

            val configured = (0 until size).filter { contestants[it].configured }
            if (configured.isNotEmpty()) {
                appendLine()
                for (row in configured) {
                    append(names[row].padEnd(width)).append("   ").appendLine(contestants[row].summary)
                }
            }
        }
    }

    /**
     * Column headings, distinct even when two contestants describe themselves the same way.
     *
     * [Contestant.label] is short by design and so cannot promise uniqueness — two seats differing
     * only in an exploration constant are both `uct*`. A repeat gets `·2`, `·3`, and the legend says
     * which is which. Numbered by position, so the same field always reads the same way.
     */
    private fun headings(): List<String> {
        val seen = LinkedHashMap<String, Int>()
        return contestants.map { contestant ->
            val count = (seen[contestant.label] ?: 0) + 1
            seen[contestant.label] = count
            if (count == 1) contestant.label else "${contestant.label}·$count"
        }
    }

    // -- internals

    internal fun recordWin(winner: Int, loser: Int) {
        winCounts[winner * size + loser]++
    }

    internal fun recordDraw(one: Int, other: Int) {
        drawCounts[one * size + other]++
        drawCounts[other * size + one]++
    }

    private fun total(counts: IntArray, contestant: Int): Int {
        var sum = 0
        val base = contestant * size
        for (other in 0 until size) {
            sum += counts[base + other]
        }
        return sum
    }

    private companion object {
        const val MIN_COLUMN = 5

        /** Rounded half-up without touching `kotlin.math`, which is more than this needs. */
        fun percent(rate: Double): Int = ((rate * 1000).toInt() + 5) / 10
    }
}
