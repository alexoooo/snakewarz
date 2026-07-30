package ao.snakewarz.bots.search

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Plays [playout] to a conclusion with every snake moving by [policy], and reports how it ended.
 *
 * This is how both simulating bots simulate — legacy's `RandomAi`, minus the object. It is a function
 * rather than a [ao.snakewarz.botapi.Bot] on purpose: a `Bot` is handed a
 * [ao.snakewarz.botapi.Turn], `Turn` is a class, and constructing one per simulated move would
 * allocate millions of them per match to carry four fields that are already in reach here.
 *
 * A rollout that has been paid for runs to the end, so this is bounded by the rules' turn limit
 * rather than by an allowance — the caller charged one [EvaluationCost.ROLLOUT] when it asked for the
 * playout. The `outcome` is re-read after **every** advance, never carried over, because advancing on
 * a stale read throws.
 *
 * What each snake plays is [RolloutPolicy]'s business, including the `NORTH` a trapped one plays
 * without drawing.
 */
internal fun randomPlayout(playout: Playout, rng: Rng, policy: RolloutPolicy): MatchOutcome {
    var result = playout.outcome

    while (result == null) {
        val mover = playout.toAct
        playout.advance(policy.pick(playout.board, mover, playout.board.legalMoves(mover), rng))
        result = playout.outcome
    }

    return result
}
