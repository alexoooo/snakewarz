package ao.snakewarz.lab.policy

import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.MatchRecord

/** Thirds of a completed replay, used only as a hindsight diagnostic and never as a bot input. */
internal enum class PolicyPhase(val label: String) {
    EARLY("early"),
    MIDDLE("middle"),
    LATE("late"),
    ;

    companion object {
        fun at(turnIndex: Int, turnCount: Int): PolicyPhase {
            val denominator = turnCount.coerceAtLeast(1)
            val third = ((turnIndex.toLong() * entries.size) / denominator).toInt()
                .coerceAtMost(entries.lastIndex)
            return entries[third]
        }
    }
}

/** One decoded match, keyed the same way as `MatchLog.replays()`. */
internal class PolicyReplay(
    val key: String,
    /** Complete-opening identity, or the sampled run/pair block used by P1's own intervals. */
    val block: String,
    val record: MatchRecord,
)

/** One sampled decision turn. At most one is retained from a match in any [phase]. */
internal class PolicySample(
    val replay: PolicyReplay,
    val turnIndex: Int,
    val phase: PolicyPhase,
    /** A match-level rank independent of how many choice turns this replay contains. */
    internal val rank: Long,
)

private class RankedChoice(
    val sample: PolicySample,
    val rank: Long,
)

/** The cheap first pass: its sample plus honest denominators from the complete replay stream. */
internal class PolicySelection(
    val samples: List<PolicySample>,
    val forced: IntArray,
    val choices: IntArray,
)

/**
 * Chooses a bounded, deterministic sample without asking an expert to search.
 *
 * Every replay contributes at most one hash-min choice turn to each progress third. An independent
 * replay/phase hash ranks those candidates for the global cap; using the winning turn's hash there
 * would favour a replay merely because it offered more chances to draw a small hash.
 */
internal fun selectPolicyPositions(
    replays: List<PolicyReplay>,
    positionsPerPhase: Int,
    seed: Long,
): PolicySelection {
    require(positionsPerPhase > 0) { "--positions must be positive, was $positionsPerPhase" }

    val candidates = Array(PolicyPhase.entries.size) { mutableListOf<PolicySample>() }
    val forced = IntArray(PolicyPhase.entries.size)
    val choices = IntArray(PolicyPhase.entries.size)

    for (replay in replays) {
        val best = arrayOfNulls<RankedChoice>(PolicyPhase.entries.size)
        val match = Match.playback(replay.record)

        while (match.outcome == null) {
            val turnIndex = match.turnIndex
            val phase = PolicyPhase.at(turnIndex, replay.record.turnCount)
            val legal = match.view.legalMoves(match.view.toAct)
            if (legal.size < 2) {
                forced[phase.ordinal]++
            } else {
                choices[phase.ordinal]++
                val candidate = RankedChoice(
                    sample = PolicySample(
                        replay = replay,
                        turnIndex = turnIndex,
                        phase = phase,
                        rank = policyReplayRank(seed, replay.key, phase),
                    ),
                    rank = choiceRank(seed, replay.key, phase, turnIndex),
                )
                val incumbent = best[phase.ordinal]
                if (incumbent == null || candidate.before(incumbent)) {
                    best[phase.ordinal] = candidate
                }
            }

            if (match.step() == StepResult.AwaitingInput) {
                break
            }
        }

        for (phase in PolicyPhase.entries) {
            best[phase.ordinal]?.let { candidates[phase.ordinal] += it.sample }
        }
    }

    val selected = mutableListOf<PolicySample>()
    for (phase in PolicyPhase.entries) {
        candidates[phase.ordinal].sortWith(
            compareBy<PolicySample> { it.rank }
                .thenBy { it.replay.key }
                .thenBy { it.turnIndex },
        )
        selected += candidates[phase.ordinal].take(positionsPerPhase)
    }
    return PolicySelection(selected, forced, choices)
}

private fun RankedChoice.before(other: RankedChoice): Boolean =
    rank < other.rank || (rank == other.rank && sample.turnIndex < other.sample.turnIndex)

/** The global sample rank deliberately has no turn-dependent input. */
internal fun policyReplayRank(seed: Long, match: String, phase: PolicyPhase): Long =
    hashRank(seed xor REPLAY_SALT, match, phase.ordinal, 0)

private fun choiceRank(seed: Long, match: String, phase: PolicyPhase, turnIndex: Int): Long =
    hashRank(seed xor CHOICE_SALT, match, phase.ordinal, turnIndex)

/** FNV identifies the persisted match and coordinate; SplitMix supplies the final avalanche. */
private fun hashRank(seed: Long, match: String, phase: Int, coordinate: Int): Long {
    var hash = FNV_OFFSET xor seed
    for (character in match) {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    hash = (hash xor phase.toLong()) * FNV_PRIME
    hash = (hash xor coordinate.toLong()) * FNV_PRIME
    return SplitMix64(hash).nextLong()
}

private const val REPLAY_SALT = -7046029254386353131L
private const val CHOICE_SALT = -3335678366873096957L
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
