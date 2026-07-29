package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.resolveSpec
import ao.snakewarz.lab.report.Separation
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.ReplayCodec
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * *When* an entrant's games are decided, rather than how they end.
 *
 * `report` says what a loss looked like; a rating says how many there were. Neither says which half
 * of the match the points went missing in, and a snakes match is two games in sequence: while the
 * snakes can still reach each other every move is about who gets where first, and once they cannot
 * the rest is a solo filling race that whoever holds the most room wins. Those two ask for entirely
 * different play, and nothing here measured which of them a bot is losing.
 *
 * This does, by replaying the log — free, since the search was paid for the day the batch ran — and
 * splitting each match at the move the board comes apart for good. Then, for the losses:
 *
 * - **never separated** — the game ended while the snakes still shared ground. A contact loss.
 * - **behind on room at the split** — the race was already lost when it started. The points went
 *   missing *before* the split, in the play that decided who got which side.
 * - **ahead on room at the split** — the race was won on paper and lost anyway, which is a filling
 *   loss and the only one a better endgame would fix.
 *
 * ### The split is the conservative one, and the gap is reported beside it
 *
 * [Separation] answers two questions: whether the free squares connect, which is what every
 * evaluation in `:bots` means by *isolated*, and whether they connect once every **living** body is
 * treated as ground. A living snake's tail retracts every second move, so a barrier made of one
 * erodes and the first answer can come apart again; only the second is a statement about the rest of
 * the game. The distance between them is printed because it is the size of the mistake the cheap
 * predicate makes.
 *
 * ### What it found about the strongest bot, which is the reason it exists
 *
 * `puct:eval=learned` over 1,200 logged matches on a 12x12 at the shipped allowance:
 *
 * | | figure |
 * |---|---|
 * | matches that came apart for good | 85% |
 * | positions the **conservative** flood called separated | **0** |
 * | the final split, of a 165-move game | move 136 |
 * | the *first* separation | move 66 — 58 moves earlier |
 * | separated matches that came apart and rejoined at least once | **81%** |
 *
 * | phase | played | won | lost | score |
 * |---|---|---|---|---|
 * | never came apart | 175 | 141 | 34 | 81% |
 * | split, behind on room | 339 | 56 | 283 | 17% |
 * | split, level on room | 26 | 11 | 15 | 42% |
 * | split, ahead on room | 660 | 572 | 88 | 87% |
 *
 * **Two thirds of its losses were already lost when the race started** — 283 of 420 — and a fifth
 * were races it entered ahead and lost anyway. So the leverage is in the contact game that decides
 * who gets which side, not in the fill; a specialist endgame filler could recover at most the 21%,
 * and only by never losing a race it enters ahead.
 *
 * **And `isolated` is a statement about this move four times in five.** The first separation lands 58
 * moves before the one that holds, and 81% of them come apart again — which is what a barrier made of
 * a retracting body does, measured under the shipped rules for the first time.
 *
 * ### The same run on `eval=chamber`, which is what makes it a diagnostic rather than a description
 *
 * The two leaves are 26 rating points apart and are losing *different games*:
 *
 * | | `eval=learned` | `eval=chamber` |
 * |---|---|---|
 * | races entered ahead, of those it entered | **64%** | 54% |
 * | races entered ahead and then won | 87% | **93%** |
 * | losses that were races it had already lost | 67% | 77% |
 * | losses that were races it entered ahead | **21%** | 8% |
 *
 * The fitted leaf is better at the contact game and worse at converting what it wins there. Held
 * against each other that is worth about **3.7 points of score, or roughly 25 Elo**, to a bot that
 * could take the first row from one and the second from the other — which is what a phase-dispatch
 * portfolio would be for, and the only honest bound anybody has put on one.
 *
 * The confound is worth stating with it: a more conservative leaf enters fewer races and may enter
 * them **further ahead**, so part of that 93% is the size of the lead rather than the quality of the
 * fill. Nothing here separates the two, and a portfolio built on the number without settling that
 * would be inheriting the confound.
 */
internal class PhasesCommand(
    val subject: String,
    val against: String?,
    val logDirectory: Path,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val store = MatchLog(logDirectory)
        val everything = store.matches()
        require(everything.isNotEmpty()) { "nothing has been played into $logDirectory yet. Run `play` first." }

        val specs = everything.flatMapTo(LinkedHashSet()) { match -> match.slots.map { it.spec } }
        val spec = resolveSpec(subject, specs)
        val opponent = against?.let { resolveSpec(it, specs) }
        val mine = everything.filter { match ->
            match.slots.any { it.spec == spec } &&
                (opponent == null || match.slots.any { it.spec == opponent })
        }
        require(mine.isNotEmpty()) {
            "$spec has played nothing" + if (opponent == null) " in $logDirectory" else " against $opponent"
        }

        val tally = Tally()
        val readers = LinkedHashMap<String, Separation>()
        for (match in mine) {
            val payload = store.replay(match.run, match.index) ?: continue
            val record = try {
                ReplayCodec.decode(payload)
            } catch (malformed: IllegalArgumentException) {
                // One torn line in a log is not a reason to abandon the rest of a batch.
                tally.unreadable++
                continue
            }

            val seat = match.slots.first { it.spec == spec }.seat
            val played = Match.playback(record)
            val geometry = "${record.setup.rows}x${record.setup.cols}"
            val split = splitOf(played, readers.getOrPut(geometry) { Separation(played.grid) }, seat)
            tally.add(split, match.turnsPlayed, won = match.of(spec)?.winner == true, drawn = match.isDraw)
        }

        log("[lab] $spec" + if (opponent == null) "" else " against $opponent")
        log("[lab] ${tally.played} matches with a replay, of ${mine.size} logged")
        if (tally.unreadable > 0) {
            log("[lab] ${tally.unreadable} replays did not decode and were dropped")
        }
        if (tally.played == 0) {
            log("[lab] nothing to split -- that batch ran with --replays none")
            return
        }

        separation(tally, log)
        phases(tally, log)
    }

    override fun toString(): String = "Phases($subject, against=$against)"

    // -- internals

    /**
     * Walks one replayed match and reports where — and whether — the board came apart for good.
     *
     * **The split is taken with hindsight, and it has to be.** The conservative predicate this was
     * meant to use is vacuous at two snakes ([Separation] carries why), so what stands in for it is
     * the *last* time the free squares came apart before the game ended. Every earlier separation
     * that the following moves undid is counted as erosion instead. That makes this a diagnostic and
     * not a dispatch rule: a bot deciding what to play cannot know which of the two it is looking at.
     *
     * The whole match is walked rather than stopped at the first split, which is exactly what makes
     * the erosion count possible.
     */
    private fun splitOf(match: Match, separation: Separation, seat: Int): Split {
        var firstAt = -1
        var lastAt = -1
        var mine = 0
        var rival = 0
        var separations = 0
        var conservative = 0
        var was = false
        var at = 0

        while (match.outcome == null) {
            val view = match.view
            if (view.aliveCount > 1) {
                val now = separation.naive(view)
                if (now && separation.permanent(view)) {
                    conservative++
                }
                if (now && !was) {
                    separations++
                    if (firstAt < 0) {
                        firstAt = at
                    }
                    lastAt = at
                    mine = separation.roomOf(view, seat)
                    rival = 0
                    for (slot in 0 until view.snakeCount) {
                        if (slot != seat) {
                            val room = separation.roomOf(view, slot)
                            if (room > rival) rival = room
                        }
                    }
                }
                was = now
            }
            at++
            if (match.step() == StepResult.AwaitingInput) {
                // A scripted stand-in that runs out of recorded moves parks rather than forfeiting,
                // so a truncated recording would otherwise spin here forever.
                break
            }
        }

        // A board that was still shared when the last snake fell never came apart at all, whatever
        // it did in the middle of the game.
        return if (was) {
            Split(firstAt, lastAt, mine, rival, separations, conservative)
        } else {
            Split(firstAt, -1, 0, 0, separations, conservative)
        }
    }

    private fun separation(tally: Tally, log: (String) -> Unit) {
        log("")
        log("when the board came apart")
        log(
            "  for good       ${tally.separated} of ${tally.played} matches " +
                "(${percent(tally.separated.toDouble() / tally.played)})",
        )
        log(
            "  conservative   ${tally.conservative} positions -- the flood that treats a living body " +
                "as ground",
        )
        if (tally.separated == 0) {
            return
        }

        log("  at move        ${median(tally.permanentAt)} at the median, of ${median(tally.length)} played")
        log(
            "  first said at  move ${median(tally.naiveAt)}, " +
                "${median(tally.erosion)} moves earlier at the median",
        )
        log("  came apart and rejoined at least once in ${percent(tally.erodedRate)} of them")
    }

    /**
     * The table this command exists for: won, lost and drawn inside each phase.
     *
     * Read the **loss** column against the total: a phase holding most of the losses is where the
     * points are, whatever the score rate in it says. A bot that wins every race it enters ahead is
     * not thereby good at races.
     */
    private fun phases(tally: Tally, log: (String) -> Unit) {
        log("")
        log("where the games were decided")
        log("  phase                     played   won  drawn  lost   score")
        for (phase in Phase.entries) {
            val bucket = tally.buckets[phase.ordinal]
            if (bucket.played == 0) {
                continue
            }
            log(
                "  ${phase.label.padEnd(LABEL)}${bucket.played.toString().padStart(6)}" +
                    bucket.won.toString().padStart(6) +
                    bucket.drawn.toString().padStart(7) +
                    bucket.lost.toString().padStart(6) +
                    percent(bucket.score).padStart(8),
            )
        }

        val losses = tally.buckets.sumOf { it.lost }
        if (losses == 0) {
            return
        }
        log("")
        log("  Of $losses losses:")
        for (phase in Phase.entries) {
            val lost = tally.buckets[phase.ordinal].lost
            if (lost > 0) {
                log("    ${percent(lost.toDouble() / losses).padStart(4)}  ${phase.blame}")
            }
        }
    }

    private fun median(values: MutableList<Int>): Int = if (values.isEmpty()) 0 else values.sorted()[values.size / 2]

    private fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

    /** Where a match was decided, in the order the game reaches them. */
    private enum class Phase(val label: String, val blame: String) {
        CONTACT("never came apart", "ended while the snakes could still reach each other"),
        BEHIND("split, behind on room", "were already behind when the board split"),
        LEVEL("split, level on room", "split level and went either way"),
        AHEAD("split, ahead on room", "were ahead when the board split and lost the fill anyway"),
    }

    private class Split(
        /** The first move the free squares came apart at, whether or not it lasted. */
        val naiveAt: Int,
        /** The last one, which is the split the rest of the game was played from, or `-1`. */
        val permanentAt: Int,
        val mine: Int,
        val rival: Int,
        /** How many times the board came apart, which is one more than it eroded back. */
        val separations: Int,
        /** Positions the conservative predicate called separated. Structurally zero at two snakes. */
        val conservative: Int,
    ) {
        val phase: Phase
            get() = when {
                permanentAt < 0 -> Phase.CONTACT
                mine > rival -> Phase.AHEAD
                mine < rival -> Phase.BEHIND
                else -> Phase.LEVEL
            }
    }

    private class Bucket {
        var played = 0
        var won = 0
        var drawn = 0
        var lost = 0
        val score: Double get() = if (played == 0) 0.0 else (won + drawn * 0.5) / played
    }

    private class Tally {
        val buckets = Array(Phase.entries.size) { Bucket() }
        val naiveAt = mutableListOf<Int>()
        val permanentAt = mutableListOf<Int>()
        val erosion = mutableListOf<Int>()
        val length = mutableListOf<Int>()
        var played = 0
        var separated = 0
        var unreadable = 0
        var conservative = 0
        private var eroded = 0

        /** How often a separation that looked final was undone by the moves after it. */
        val erodedRate: Double get() = if (separated == 0) 0.0 else eroded.toDouble() / separated

        fun add(split: Split, turnsPlayed: Int, won: Boolean, drawn: Boolean) {
            played++
            length += turnsPlayed
            conservative += split.conservative
            val bucket = buckets[split.phase.ordinal]
            bucket.played++
            when {
                won -> bucket.won++
                drawn -> bucket.drawn++
                else -> bucket.lost++
            }

            if (split.permanentAt >= 0) {
                separated++
                permanentAt += split.permanentAt
                naiveAt += split.naiveAt
                erosion += split.permanentAt - split.naiveAt
                if (split.separations > 1) eroded++
            }
        }
    }

    private companion object {
        const val LABEL = 24
    }
}
