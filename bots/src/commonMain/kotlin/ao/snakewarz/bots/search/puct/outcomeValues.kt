package ao.snakewarz.bots.search.puct

import ao.snakewarz.core.rules.MatchOutcome

/**
 * A finished position as one value per slot, on [LeafEval]'s scale.
 *
 * The bridge between the two things a leaf can be. A real result and a judgement have to arrive at
 * the tree in the same shape, or [PuctTree.record] would need to know which it was holding — the
 * same argument `SpaceOwnership.verdict` makes for phrasing its judgement as an outcome.
 */
internal fun outcomeValues(outcome: MatchOutcome, slotCount: Int, into: DoubleArray) {
    val winner = outcome.winner.index
    val draw = outcome.isDraw

    for (slot in 0 until slotCount) {
        into[slot] = when {
            draw -> LeafEval.EVEN
            slot == winner -> LeafEval.WIN
            else -> LeafEval.LOSS
        }
    }
}
