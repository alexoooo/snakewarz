package ao.snakewarz.bots

import ao.snakewarz.botapi.Playout
import ao.snakewarz.core.Direction
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.Rng

/**
 * Plays [playout] forward at most [depth] moves and, if it is still going, judges where it got to by
 * [SpaceOwnership] instead of playing it out.
 *
 * The trade this exists to make: a full rollout runs a hundred-odd moves for one bit of information,
 * so cutting it short at twenty and reading the position instead buys roughly five times the
 * iterations for the same allowance. Whether that is a *good* trade is a question about this engine
 * rather than about MCTS in general, and `docs/Migration.md` records the answer measured here.
 *
 * Everything [randomPlayout] does, this does — same policy, same re-read of `outcome` after every
 * advance, same `NORTH` for a trapped snake — up to the point where the cut-off lands.
 */
internal fun truncatedPlayout(
    playout: Playout,
    rng: Rng,
    depth: Int,
    space: SpaceOwnership,
): MatchOutcome {
    var result = playout.outcome
    var remaining = depth

    while (result == null && remaining > 0) {
        val legal = playout.board.legalMoves(playout.toAct)
        val move = if (legal.isEmpty) Direction.NORTH else legal.nth(rng.nextInt(legal.size))

        playout.advance(move)
        result = playout.outcome
        remaining--
    }

    // A rollout that finished on its own is worth more than a judgement of it, so the real result
    // wins whenever there is one -- including an exhausted budget, which the caller must be able to
    // recognise by identity and would lose if this judged the position instead.
    return result ?: space.verdict(playout.board)
}
