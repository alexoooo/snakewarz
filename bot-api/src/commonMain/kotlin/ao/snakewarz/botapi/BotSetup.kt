package ao.snakewarz.botapi

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId

/**
 * What a bot is told once, when it is created for a slot — the things that cannot change mid-match.
 *
 * Note what is absent: there is no clock, no other slot's [Rng], and no way back to the driver. A
 * bot is structurally unable to reach any of the three, which is what makes a match reproducible
 * without anybody having to remember to keep it so.
 */
public class BotSetup(
    public val self: SnakeId,
    public val grid: Grid,
    public val rules: RulesConfig,
    /** Slot indices of every other snake, ascending. */
    public val opponents: IntArray,
    /**
     * This slot's own stream, forked from the match seed as `matchRng.fork(slotIndex)`.
     *
     * Forking rather than sharing is deliberate: one bot drawing a different number of values must
     * never shift another bot's stream, or tuning one bot would silently change how every other bot
     * played in a recorded match.
     */
    public val rng: Rng,
    public val params: BotParams,
) {
    public val opponentCount: Int get() = opponents.size

    override fun toString(): String = "BotSetup($self of ${opponents.size + 1}, $grid, $rules)"
}
