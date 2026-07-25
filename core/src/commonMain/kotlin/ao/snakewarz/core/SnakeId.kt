package ao.snakewarz.core

import kotlin.jvm.JvmInline

/**
 * Identifies one snake within a match, as a dense slot index in `0 until snakeCount`.
 *
 * Slot indices are the engine's only notion of player identity. A bot, a colour and a name all hang
 * off the same index in higher modules, but none of them is visible here — that separation is what
 * dissolved the legacy `PlayerAvatar`, which fused identity, AI delegate and `java.awt.Image` and so
 * dragged AWT into the game state itself.
 *
 * Use [NONE] rather than `SnakeId?`: a nullable value class boxes, and ids are read on the hot path.
 */
@JvmInline
public value class SnakeId(public val index: Int) {
    public val isNone: Boolean get() = index < 0

    override fun toString(): String = if (isNone) "SnakeId(none)" else "SnakeId($index)"

    public companion object {
        /** The absent snake: no owner, or no winner. */
        public val NONE: SnakeId = SnakeId(-1)
    }
}
