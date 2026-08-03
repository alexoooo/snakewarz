package ao.snakewarz.ui.chrome.demo

/**
 * What the demo board says about itself, as the turn it is on changes.
 *
 * The animation on its own shows two snakes moving and does not say what to want. These four lines
 * are the difference between watching a shape and learning a rule, and the third is the one the demo
 * is on the page for: playtesters read the rival as something to collect or something to escape, and
 * *room* is the word neither reading suggests.
 *
 * Keyed by the first turn each line holds from, so the arc is stated once here rather than being
 * spread across whatever advances the board. The turns belong to
 * [ao.snakewarz.match.demo.DemoReplay] and are only meaningful against it — a payload with a
 * different shape needs these moved with it, which is why the last line's turn is asserted against
 * the record rather than trusted.
 */
internal object DemoCaptions {
    /** The turn the closing line lands on, which is the turn the loser runs out of room. */
    const val LAST_FROM_TURN: Int = 30

    private val lines: List<Pair<Int, String>> = listOf(
        0 to "Both snakes move every turn, and the trail behind them is solid.",
        10 to "You can never stop, and you can never cross a trail — not even your own.",
        20 to "So you win by taking the other snake’s room away.",
        LAST_FROM_TURN to "Last snake moving wins.",
    )

    /** The line in force at [turnIndex]. Before the first turn and after the last both have one. */
    fun at(turnIndex: Int): String {
        var line = lines.first().second
        for ((from, text) in lines) {
            if (turnIndex < from) {
                break
            }
            line = text
        }
        return line
    }
}
