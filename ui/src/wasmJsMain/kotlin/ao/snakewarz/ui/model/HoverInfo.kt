package ao.snakewarz.ui.model

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.ui.render.tailClearsNext

/** What the label over the board says about the square under the pointer. */
internal class HoverInfo(val who: String, val detail: String)

/**
 * What [cell] is, worded for a person, or `null` when there is nothing to say about it.
 *
 * Beside the type rather than in a file of its own because the two are one idea, and because a
 * `hoverInfo.kt` and a `HoverInfo.kt` are the same file on a case-insensitive filesystem.
 *
 * **Three answers and not two.** A wall and an open square are one answer from [BoardView.ownerOf],
 * so a reader that went straight to the owner would have the board call a square nobody can ever
 * enter empty. Empty board really is worth no label: what is on it is the whole of what this says.
 */
internal fun hoverInfo(view: BoardView, cell: Cell, labels: SlotLabels): HoverInfo? {
    if (!view.grid.isPlayable(cell)) {
        return null
    }
    if (view.isWall(cell)) {
        return HoverInfo(who = WALL, detail = "")
    }

    val owner = view.ownerOf(cell)
    if (owner.isNone) {
        return null
    }

    val snake = view.snake(owner)
    return HoverInfo(
        who = labels.of(owner),
        detail = buildString {
            append(snake.length).append(if (snake.length == 1) " square" else " squares")
            when {
                !snake.alive -> append(" · out")
                tailClearsNext(view, snake) -> append(" · tail clears next move")
                snake.growsOnNextMove -> append(" · growing next move")
                else -> Unit
            }
        },
    )
}

/** What a square of the map is called, in the vocabulary a player already has for it. */
private const val WALL = "wall"
