package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob

/**
 * A fixed, complete look ahead between [ao.snakewarz.bots.reactive.policy.PolicyBot] and the full
 * searchers.
 *
 * This is deliberately not iterative deepening. [DEPTH] chooses one shape: every own move at depth
 * one, every actual next actor's reply at depth two, or three individual turns of paranoid
 * alpha-beta at depth three. Cartographer orders every node and supplies the fallback.
 *
 * The worst-case complete caps are **4, 16 and 64 evaluations** at depths one, two and three. Forced
 * moves and terminal leaves can make a position complete for less, but unused savings never carry
 * into another turn. If the allowance refuses even one required appraisal, the whole search is
 * discarded and Cartographer's live-board move is returned; this bot never adopts a partial root or
 * silently searches at a shallower depth.
 *
 * P4 measured all three configurations on six separate boards. Depth three beat Cartographer
 * directly by 80-0, 71-7, 49-31, 78-2, 69-11 and 78-2 on empty 8x8, arena, cross, rooms,
 * double-spiral and islands. Against UCT@600 it scored 24%, 33%, 16%, 38%, 90% and 48%: a genuine
 * map-general bridge with a corridor specialism, not a replacement for full search. Every accepted
 * Chrome turn fit the fixed 3.5 ms tiny lane; the largest raw turn was 1.4 ms, at depth three on
 * rooms.
 */
public class LookaheadBot(setup: BotSetup) : Bot {
    private val depth = DEPTH.read(setup.params)
    private val search = FixedDepthBot(setup, depth)

    override fun chooseMove(turn: Turn): Decision = search.chooseMove(turn)

    override fun toString(): String = "LookaheadBot(depth=$depth)"

    internal companion object {
        /**
         * The useful allowance range, from a complete depth-one tree to a complete depth-three one.
         * Intermediate values are valid but may fall back when they cannot pay for the configured
         * depth's whole tree.
         */
        val SEARCH = BotKnob.Search(min = 4, max = 64, step = 4)

        /**
         * Individual turns searched, with fixed complete caps of 4, 16 and 64 evaluations.
         *
         * A measured hyperparameter rather than a sidebar choice: higher is the stronger default,
         * and the allowance remains the player-facing speed/strength tradeoff.
         */
        val DEPTH = BotKnob.Integer(
            name = "depth",
            label = "Depth",
            help = "Individual turns searched; depths 1, 2 and 3 require up to 4, 16 and 64 evaluations.",
            default = 3,
            min = 1,
            max = 3,
        )

        val KNOBS: List<BotKnob> = listOf(SEARCH, DEPTH)
    }
}
