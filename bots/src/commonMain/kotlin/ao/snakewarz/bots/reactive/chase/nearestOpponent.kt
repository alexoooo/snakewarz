package ao.snakewarz.bots.reactive.chase

import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * The living opponent whose head is fewest steps away, or [SnakeId.NONE] if none can be reached.
 *
 * This is legacy `PvpAi`'s reduction — the adapter that let a two-player algorithm play in a
 * four-player game. [paths] must already have been swept from [self]'s head.
 *
 * **The legacy version reliably chose the wrong snake.** `AStar.pathBetween` returned an *empty
 * list* for an unreachable target, `PvpAi` took its `size()` as the distance, and `0` beats every
 * real distance — so a walled-off opponent, the one snake that cannot possibly matter this turn,
 * was always the one picked. Here unreachable is [ShortestPaths.UNREACHABLE], which loses every
 * comparison instead of winning them all.
 *
 * The scan is over `0 until snakeCount` ascending and ties go to the lower slot: this is a
 * reduction, not a choice, so it draws no randomness. Liveness is read off the board rather than
 * from `BotSetup.opponents`, which is fixed at setup and cannot know who is still in the match.
 *
 * **And the reduction costs less than it looks like it should.** Throwing away every opponent but
 * one is the obvious thing to punish by adding a snake, and P7 of the 2026-07-29 agenda seated the
 * whole shipped ladder at three seats expecting exactly that. Measured over all 84 triples of the
 * nine bots on a 12x12, `chase` loses **3.5 points** of mean pairwise score to the third seat, where
 * `pressure` — which it hands off to at close range, and which has no reduction to be punished for —
 * loses **6.0** and `alphabeta` loses **9.1**. It keeps its rung over `pressure` and widens it, 57.2%
 * at two seats to 59.8% at three. So whatever the third seat costs a chaser, crossing the board to
 * reach *a* fight is not the part of this bot that suffers for it.
 */
internal fun nearestOpponent(board: BoardView, self: SnakeId, paths: ShortestPaths): SnakeId {
    var nearest = SnakeId.NONE
    var fewest = ShortestPaths.UNREACHABLE

    for (slot in 0 until board.snakeCount) {
        if (slot == self.index) {
            continue
        }

        val other = board.snake(SnakeId(slot))
        if (!other.alive) {
            continue
        }

        val distance = paths.distanceBeside(other.head)
        if (distance < fewest) {
            fewest = distance
            nearest = SnakeId(slot)
        }
    }

    return nearest
}
