package ao.snakewarz.lab.policy

import ao.snakewarz.lab.arena.moveStreamHash
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.replay.ReplayCodec
import java.nio.file.Path

/** One validated, single-board replay source and the metadata needed to identify it exactly. */
internal class PolicyReplayCorpus(
    val directory: Path,
    val run: RunHeader,
    val board: MatchSetup,
    val replays: List<PolicyReplay>,
    val encodedCount: Int,
    val unreadableCount: Int,
)

/**
 * Loads the common replay substrate used by policy agreement and action imitation.
 *
 * A replay is accepted only when its setup, schedule row, turn order, and move-stream hash all agree.
 * Keeping that validation here prevents two research commands from assigning labels to subtly
 * different reconstructions of the same logged match.
 */
internal fun loadPolicyReplayCorpus(directory: Path): PolicyReplayCorpus {
    val store = MatchLog(directory)
    val runs = store.runs()
    require(runs.size == 1) {
        "$directory has ${runs.size} run headers; policy research requires one dedicated P1 run or source run"
    }
    val run = runs.single()
    val encoded = store.replays()
    require(encoded.isNotEmpty()) {
        "no replays found under $directory -- policy research needs a batch retained with --replays all"
    }

    var unreadable = 0
    var board: MatchSetup? = null
    val replays = mutableListOf<PolicyReplay>()
    val logged = store.matches().associateBy { match -> "${match.run} ${match.index}" }
    for ((key, payload) in encoded.entries.sortedBy { it.key }) {
        val record = try {
            ReplayCodec.decode(payload)
        } catch (_: IllegalArgumentException) {
            unreadable++
            continue
        }

        val first = board
        if (first == null) {
            board = record.setup
        } else {
            require(samePolicyBoard(first, record.setup)) {
                "$directory holds more than one board; use one source map per dataset"
            }
        }

        val match = requireNotNull(logged[key]) { "replay '$key' has no complete match row under $directory" }
        require(match.run == run.id) { "replay '$key' belongs to ${match.run}, not source run ${run.id}" }
        require(record.setup.seed == match.seed) { "replay '$key' disagrees with its logged match seed" }
        require(record.setup.turnOrder().contentEquals(match.turnOrder.toIntArray())) {
            "replay '$key' disagrees with its logged turn order"
        }
        require(moveStreamHash(record.moves.toList()) == match.moveStreamHash) {
            "replay '$key' disagrees with its logged move-stream hash"
        }
        replays += PolicyReplay(key, policyBlock(match), record)
    }
    require(replays.isNotEmpty()) { "all ${encoded.size} replays under $directory were unreadable" }

    return PolicyReplayCorpus(
        directory = directory,
        run = run,
        board = checkNotNull(board),
        replays = replays,
        encodedCount = encoded.size,
        unreadableCount = unreadable,
    )
}

private fun samePolicyBoard(first: MatchSetup, second: MatchSetup): Boolean =
    first.rows == second.rows &&
        first.cols == second.cols &&
        first.rules == second.rules &&
        first.walls().contentEquals(second.walls())

internal fun policyBlock(match: LoggedMatch): String =
    match.openingIdentity?.let { "opening:$it" } ?: "${match.run}:${match.pairKey}"
