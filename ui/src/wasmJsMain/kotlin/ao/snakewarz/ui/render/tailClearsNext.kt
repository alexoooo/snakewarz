package ao.snakewarz.ui.render

import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeView

/**
 * Whether [snake]'s oldest square opens up on its next move — the square whose tail tip advances,
 * said in a form a label can put into words.
 *
 * One predicate rather than two. A board that shortened a square while the label beside it said the
 * snake was still growing would be two accounts of one rule, and the reader would be right to
 * believe neither.
 *
 * Two rules out, and they are the same two the tail shape obeys. A trail that never retracts —
 * `growEveryNthMove = 1`, classic Tron — has no square about to clear, and a corpse is an obstacle
 * that is never giving anything back. A one-square snake is all head, and its head is not its tail.
 */
internal fun tailClearsNext(view: BoardView, snake: SnakeView): Boolean =
    snake.alive &&
        view.rules.growEveryNthMove >= 2 &&
        snake.length >= 2 &&
        !snake.growsOnNextMove
