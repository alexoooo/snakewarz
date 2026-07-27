package ao.snakewarz.bots.search.uct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.bots.search.randomPlayout
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Plays [playout] forward at most [depth] moves and, if it is still going, judges where it got to by
 * [SpaceOwnership] instead of playing it out.
 *
 * The trade this exists to make: a full rollout runs a hundred-odd moves for one bit of information,
 * so cutting it short at twenty and reading the position instead was meant to buy roughly five times
 * the iterations for the same allowance. Whether that is a *good* trade is a question about this
 * engine rather than about MCTS in general, and it was measured: [UctBot.ROLLOUT_DEPTH] carries the
 * table and the answer, which is no — and an allowance counted in evaluations has since taken the
 * extra iterations away too, since a truncated one costs the same unit as a full one.
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
    // wins whenever there is one.
    return result ?: space.verdict(playout.board)
}
