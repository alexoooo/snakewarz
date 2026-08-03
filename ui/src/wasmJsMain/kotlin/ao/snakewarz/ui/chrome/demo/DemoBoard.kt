package ao.snakewarz.ui.chrome.demo

import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.UiModel
import org.w3c.dom.HTMLElement

/**
 * The demo on the menu: [DemoLoop]'s board, with a rule written under it and a way to pick one.
 *
 * The board itself is not here — it is the same loop the objective card plays, and everything about
 * how it moves belongs to that class. What is here is the half the menu adds: one line per lap, four
 * dots, and the rule that a press moves the words and never the snakes.
 *
 * **One line per lap, and the board never stops for it.** The caption used to be keyed to a turn,
 * which also paced it by the match: thirty turns at six a second is under two seconds a rule, long
 * enough to notice a sentence and not long enough to finish it. Stopping the board to fix that only
 * traded the problem for a worse one — the motion is the part that was already working, and a demo
 * that pauses every ten turns reads as a stutter rather than as a beat. So the match plays through at
 * one unbroken speed exactly as it always did, and the *line* is what slows down: [DemoCaptions] holds
 * one for a whole lap, and it changes where the board resets, which is the one moment a change of text
 * costs the eye nothing. Each rule gets about seven seconds instead of under two.
 *
 * That makes a full cycle four laps long, which is what [DemoDots] is for: one press puts any rule up
 * rather than waiting three laps for it to come round. **A press moves the caption and nothing else.**
 * [show] writes two DOM nodes and touches no clock, so the recording plays the same unbroken way
 * whichever rule is over it.
 *
 * A reduced-motion reader gets the finished position instead of a loop, so the line that belongs over
 * it is the one about the snake wedged in the corner rather than the one about setting off. That
 * reader gets no rotation at all; the dots are how they reach the other three.
 */
internal class DemoBoard {
    private val caption: HTMLElement = elementById("demo-caption")
    private val dots = DemoDots(::show)

    /** The line this lap is carrying, which is the whole of what the caption and the dots read. */
    private var line: Int = FIRST_LINE

    /**
     * Set by a dot press, so the next reset keeps [line] rather than rotating past it.
     *
     * Without it a press landing in the two seconds the finished board is held would put a rule up
     * and take it away again before it had been read, which is the complaint this whole card is
     * answering. Asking for a rule buys the lap after this one as well.
     */
    private var held: Boolean = false

    private val loop = DemoLoop(
        boardId = "demo-board",
        overlayId = "demo-overlay",
        onEnter = { still ->
            // Arriving at the menu: the first line, or for a reduced-motion reader the closing one,
            // because the board that reader is given is the ending rather than the setting off.
            line = if (still) DemoCaptions.count - 1 else FIRST_LINE
            held = false
        },
        onLap = {
            if (held) {
                held = false
            } else {
                line = DemoCaptions.after(line)
            }
        },
        onFit = ::say,
    )

    fun render(model: UiModel) {
        if (model.screen != Screen.HOME) {
            loop.hide()
            return
        }
        loop.show(model.theme)
    }

    override fun toString(): String = "DemoBoard(line $line, $loop)"

    // -- internals

    /**
     * Puts [index] up. It writes two DOM nodes and touches nothing else.
     *
     * The board and the text are two separate things, and this is the seam: the recording plays the
     * same unbroken way whichever rule is over it, so a dot press changes the caption and never what
     * the snakes are doing. It claims the lap after this one through [held], which is about reading
     * time and still not about the board.
     */
    private fun show(index: Int) {
        line = index
        held = true
        say()
    }

    private fun say() {
        caption.textContent = DemoCaptions.text(line)
        dots.render(line)
    }

    private companion object {
        const val FIRST_LINE: Int = 0
    }
}
