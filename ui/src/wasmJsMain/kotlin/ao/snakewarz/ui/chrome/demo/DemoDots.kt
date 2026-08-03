package ao.snakewarz.ui.chrome.demo

import ao.snakewarz.ui.chrome.elementById
import org.w3c.dom.HTMLButtonElement

/**
 * The row of dots under the demo board: which line is in force, and the way straight to another one.
 *
 * The loop holds each line for a whole lap, which is most of why this exists at all — but a reader
 * who wants the third rule again should not have to sit through three laps to get it, and a reader
 * who has understood the first two should not have to sit through them. So the dots are pagination in
 * the ordinary sense: how many rules there are, which one is showing, and the one press that goes to
 * any of them.
 *
 * **A press changes the caption and never the board.** The animation is the half of this card that
 * was always working; it plays the same unbroken way whichever rule is over it, and `select` is
 * wired to nothing that could restart, reseek or interrupt it.
 *
 * **The label of a dot is its rule.** They are the only part of the demo a screen reader is offered —
 * the `<figure>` is `aria-hidden` and the caption inside it rewrites itself forever — so naming them
 * "rule 2 of 4" would put four unreachable controls beside a picture nobody can see. Named this way
 * the row is the lesson in order, and the `title` makes the same text a tooltip for a pointer.
 *
 * `index.html` holds exactly [DemoCaptions.count] dots and [elementById] fails on a missing id, so a
 * fifth line added without a fifth dot stops the boot rather than quietly dropping a rule.
 */
internal class DemoDots(select: (Int) -> Unit) {
    private val buttons: List<HTMLButtonElement> = List(DemoCaptions.count) { index ->
        elementById<HTMLButtonElement>("demo-dot-$index").also { button ->
            button.setAttribute("aria-label", DemoCaptions.text(index))
            button.title = DemoCaptions.text(index)
            button.addEventListener("click") { select(index) }
        }
    }

    /** Marks [index] as the line showing. `aria-current` is also what styles.css colours it by. */
    fun render(index: Int) {
        for (position in buttons.indices) {
            val button = buttons[position]
            if (position == index) {
                button.setAttribute("aria-current", "true")
            } else {
                button.removeAttribute("aria-current")
            }
        }
    }

    override fun toString(): String = "DemoDots(${buttons.size})"
}
