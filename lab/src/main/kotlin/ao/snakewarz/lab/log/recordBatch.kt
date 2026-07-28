package ao.snakewarz.lab.log

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.BatchResult
import ao.snakewarz.match.replay.ReplayCodec

/**
 * Writes a finished batch to [log] and hands back the run it was written under.
 *
 * The one place a played batch becomes a recorded one, so what `play` writes, what `ab` writes and
 * what `tune` writes are the same rows under the same rules — which is what makes them poolable at
 * all. Everything is derived here and nothing is counted as the batch runs, following the same rule
 * `MatchStats` follows: a statistic the runner had to be modified to collect is one that has to stay
 * correct forever.
 */
internal fun recordBatch(
    log: MatchLog,
    batch: BatchResult,
    registry: BotRegistry,
    openings: String,
    threads: Int,
    replays: Replays,
): RunHeader {
    val header = RunHeader.of(batch.config, registry, openings, threads)
    val matches = batch.reports.map { LoggedMatch.of(header.id, batch.config, registry, it) }

    val kept = LinkedHashMap<Int, String>()
    for (report in batch.reports) {
        val record = report.record ?: continue
        val wanted = when (replays) {
            Replays.NONE -> false
            Replays.DECISIVE -> report.stats.outcome?.isDraw == false
            Replays.ALL -> true
        }
        if (wanted) {
            kept[report.index] = ReplayCodec.encode(record)
        }
    }

    log.append(header, matches, kept)
    return header
}
