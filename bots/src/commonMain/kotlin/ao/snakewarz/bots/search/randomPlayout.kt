package ao.snakewarz.bots.search

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Plays [playout] to a conclusion with every snake moving at random, and reports how it ended.
 *
 * This is the rollout policy both simulating bots use — legacy's `RandomAi`, minus the object. It is
 * a function rather than a [ao.snakewarz.botapi.Bot] on purpose: a `Bot` is handed a
 * [ao.snakewarz.botapi.Turn], `Turn` is a class, and constructing one per simulated move would
 * allocate millions of them per match to carry four fields that are already in reach here.
 *
 * A rollout that has been paid for runs to the end, so this is bounded by the rules' turn limit
 * rather than by an allowance — the caller charged one [EvaluationCost.ROLLOUT] when it asked for the
 * playout. The `outcome` is re-read after **every** advance, never carried over, because advancing on
 * a stale read throws.
 *
 * A trapped snake plays `NORTH` without consuming randomness. Every direction from a trapped
 * position eliminates it and leaves the board in exactly the same state, so there is nothing to
 * choose between them and drawing for it would only shift the stream.
 */
internal fun randomPlayout(playout: Playout, rng: Rng): MatchOutcome {
    var result = playout.outcome

    while (result == null) {
        val legal = playout.board.legalMoves(playout.toAct)
        val move = if (legal.isEmpty) Direction.NORTH else legal.nth(rng.nextInt(legal.size))

        playout.advance(move)
        result = playout.outcome
    }

    return result
}
