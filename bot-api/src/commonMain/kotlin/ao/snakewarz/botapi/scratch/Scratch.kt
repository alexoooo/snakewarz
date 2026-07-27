package ao.snakewarz.botapi.scratch

/**
 * A bot's private board to think on, handed out already positioned at the live match.
 *
 * There is exactly **one** playout per scratch: [playout] returns the same instance every time,
 * reset to the current position. A search does not need two at once — it descends, simulates and
 * unwinds — and a pool with no release call cannot be made safe, so it is not offered.
 *
 * **Asking for one is what spends the allowance.** A turn's budget is counted in evaluations, and an
 * evaluation is what a bot does with a playout — so the charge lands here rather than on each
 * simulated move. That keeps the accounting the same for a bot that plays a hundred moves out and a
 * bot that sweeps the board once, and it keeps a search terminating *structurally*: a loop that
 * iterates without asking for a playout has nothing to iterate on.
 */
public interface Scratch {
    /**
     * The playout, reset to the live position, with [cost] charged for it up front.
     *
     * Charging before rather than after is what makes the allowance a bound: an evaluation is paid
     * for and then runs to completion, so no iteration is ever half-charged or half-credited. When
     * there is not enough left the charge is refused, nothing is spent, and the playout comes back
     * already reporting [Playout.outcome] — so the canonical `if (playout.outcome != null) stop` is
     * also the budget check.
     *
     * [cost] is that bot's exchange rate between its own evaluation and everybody else's; see
     * `bots/search`'s `EvaluationCost`. Invalidates whatever the previous call returned.
     */
    public fun playout(cost: Int = 1): Playout
}
