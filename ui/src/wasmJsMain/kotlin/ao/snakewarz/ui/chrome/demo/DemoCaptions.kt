package ao.snakewarz.ui.chrome.demo

/**
 * What the demo board says about itself, one line per lap of the recording.
 *
 * The animation on its own shows two snakes moving and does not say what to want. These four lines
 * are the difference between watching a shape and learning a rule, and the third is the one the demo
 * is on the page for: playtesters read the rival as something to collect or something to escape, and
 * *room* is the word neither reading suggests.
 *
 * **A line is not keyed to a turn, and that is the whole of its pacing.** Keying one to a turn also
 * paces it by the match, and thirty turns at six a second is under two seconds a rule — long enough
 * to notice a sentence and not long enough to finish it. Every line here is true of the whole
 * recording rather than of a moment in it, so [DemoBoard] holds one for an entire lap and changes it
 * where the board resets. The match plays at one speed and never stops; the text changes once every
 * seven seconds instead of four times in five.
 *
 * Four lines therefore means four dots in `index.html`, and [count] is what that markup is checked
 * against at boot.
 */
internal object DemoCaptions {
    private val lines: List<String> = listOf(
        "Both snakes move every turn, and the trail behind them is solid.",
        "You can never stop, and you can never cross a trail — not even your own.",
        "So you win by taking the other snake’s room away.",
        "Last snake moving wins.",
    )

    /** How many lines there are, which is how many dots the page draws under the board. */
    val count: Int get() = lines.size

    /** The text of line [index]. */
    fun text(index: Int): String = lines[index]

    /** The line the lap after [index] carries, wrapping so the demo keeps offering all four. */
    fun after(index: Int): Int = (index + 1) % lines.size
}
