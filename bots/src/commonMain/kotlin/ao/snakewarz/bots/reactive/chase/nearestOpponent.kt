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
