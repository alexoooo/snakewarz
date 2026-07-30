package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.search.learned.PositionFeatures
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.resolveSpec
import ao.snakewarz.lab.report.Separation
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.ReplayCodec
import java.nio.file.Path
import kotlin.math.abs
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
 * ### How big the lead was, which is what the sign bucket cannot say
 *
 * A more conservative leaf enters fewer races and may enter them **further ahead**, so a conversion
 * rate read off `mine > rival` alone confounds the quality of the fill with the size of the lead. Two
 * readings settle it, and they are separate questions rather than two spellings of one:
 *
 * - **[Split.lead], the magnitude.** `(mine - rival) / (mine + rival)` over the free squares each
 *   side can reach — a margin rather than a square difference, because it has to mean the same thing
 *   on an 8x8 and a 20x20 and the field this serves is run at three sizes. It is reported *both* ways
 *   deliberately: as a median column beside every phase, which says whether two bots' AHEAD buckets
 *   are even the same population, and as [Band]s scored separately, which is the only form in which
 *   one bot's conversion can be read against another's **at a matched lead**. A median alone cannot
 *   do the second and bands alone hide how lopsided a band is inside.
 * - **[Split.usableLead], the same margin over the squares a walk can actually spend.** This is the
 *   one that answers whether "ahead on room" was ever ahead. A room reached through a one-square neck
 *   is a room entered and never left, so a raw flood over-counts by whatever the shape hides;
 *   `PositionFeatures.USABLE_MARGIN` is the same margin taken over `ChamberTree.chainWorth`, which at
 *   the settings that vector fixes is `FillableSpace`'s block-chain count with the chessboard parity
 *   cap on. Where the two columns disagree in **sign**, the raw flood called a race ahead that the
 *   shape says was behind, and the run prints how often that happened.
 *
 * **What it is not, said plainly, because the difference is a factor of two.** `chainWorth` is
 * spendable *squares* and not spendable *moves*: at the shipped `growEveryNthMove` of two the tail
 * retracts on alternating turns, so an open room is worth about twice its squares in moves while a
 * dumbbell is worth its squares plus a neck that a retracting walk can cross twice. `SurvivalHorizon`
 * is that correction and it is `internal` to `:bots` — `PositionFeatures` is the whole of what that
 * module makes public besides the registry — so no reading here is a move count. What the sign
 * disagreement bounds is the *shape* half of the gap, which is the half a fill can lose to.
 *
 * ### What the two columns say about the 93% above, which is why they exist
 *
 * The same 1,200-match log, the same two leaves, cut by how far ahead the race started:
 *
 * | band | `learned` played | `learned` score | `chamber` played | `chamber` score |
 * |---|---|---|---|---|
 * | ahead, clear | 106 | **73%** | 40 | **73%** |
 * | ahead, commanding | 510 | 94% | 480 | 97% |
 *
 * **The confound was most of the effect.** Over the whole ahead side the fitted leaf converts 91%
 * and the conservative one 95%; give the conservative one the *fitted* one's distribution of leads
 * and it converts 92.5%, so **1.9 of the 4.2 points survive** and the rest was the size of the lead.
 * The narrow band is a dead heat to the point, and 90% of `chamber`'s races start commanding against
 * 77% of `learned`'s. A portfolio priced off the sign bucket alone was buying about half of what it
 * looked like.
 *
 * **And the squares-versus-moves objection is small at the split, which was not the expectation.**
 * The two margins disagree on sign in **1%** of separated matches on a 12x12 and **2%** on a 20x20 —
 * a race the free-square flood calls the wrong way is about one in fifty, not the systematic error
 * the shape argument implies. The medians track within a few points in every band. That bounds the
 * confound; it does not retire the 1.4-2x figure, which is about *moves* and about magnitude, and no
 * reading available to `:lab` is a move count.
 *
 * **The conversion rate is close to a function of the lead alone.** `ahead, clear` reads 71-73% and
 * `ahead, commanding` 94-97% across both leaves on a 12x12 and a third field at `eval=territory` on
 * a 20x20 — three populations that share no bot, no board and no batch.
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
        val readers = LinkedHashMap<String, Reader>()
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
            val shape = "${record.setup.rows}x${record.setup.cols}/${record.setup.slotCount}"
            val split = splitOf(played, readers.getOrPut(shape) { Reader(played) }, seat)
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
        leadSize(tally, log)
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
     * **Past two snakes the conservative predicate is real, and the hindsight split is still what
     * this uses.** A corpse is a permanent wall, so from the first death onwards the flood can say
     * something — but only from the first death onwards, which is late, and it answers "is this
     * settled" where a dispatch rule needs "will this settle". The count and its rate are printed
     * either way, which is how a reader tells a structural zero from a measured one.
     *
     * The whole match is walked rather than stopped at the first split, which is exactly what makes
     * the erosion count possible.
     */
    private fun splitOf(match: Match, reader: Reader, seat: Int): Split {
        val separation = reader.separation
        var firstAt = -1
        var lastAt = -1
        var mine = 0
        var rival = 0
        var usableLead = 0.0
        var separations = 0
        var conservative = 0
        var tested = 0
        var was = false
        var at = 0

        while (match.outcome == null) {
            val view = match.view
            if (view.aliveCount > 1) {
                tested++
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
                    usableLead = reader.usableLeadAt(view, seat)
                }
                was = now
            }
            at++
            if (match.step() == StepResult.AwaitingInput) {
                // A scripted stand-in that runs out of recorded moves parks rather than forfeiting,
                // so a truncated recording ends the walk here. Asking it again throws.
                break
            }
        }

        // A board that was still shared when the last snake fell never came apart at all, whatever
        // it did in the middle of the game.
        return if (was) {
            Split(firstAt, lastAt, mine, rival, usableLead, separations, conservative, tested)
        } else {
            Split(firstAt, -1, 0, 0, 0.0, separations, conservative, tested)
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
            "  for good       ${tally.conservativeMatches} of ${tally.played} matches by the " +
                "conservative flood (${percent(tally.conservativeMatches.toDouble() / tally.played)})" +
                " -- the one that treats a living body as ground",
        )
        log(
            "  conservative   ${tally.conservative} of ${tally.tested} contested positions " +
                "(${percent(tally.conservativeRate)})",
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
        table("phase", Phase.entries.map { it.label to tally.buckets[it.ordinal] }, log)

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

    /**
     * The same matches again, cut by **how far** ahead rather than by which side of level.
     *
     * Read a band's score against the same band of another entrant: that is a conversion rate at a
     * matched lead, and it is the only reading here that a difference in how *far* ahead two bots
     * arrive cannot manufacture. The disagreement line under it is the size of the confound the
     * bands are cut on — see this class's KDoc for what the two margins are and what neither is.
     */
    private fun leadSize(tally: Tally, log: (String) -> Unit) {
        if (tally.separated == 0) {
            return
        }
        log("")
        log("how big the lead was, of the ${tally.separated} that came apart")
        table("lead at the split", Band.entries.map { it.label to tally.bands[it.ordinal] }, log)
        log(
            "  the two margins disagreed on sign in ${percent(tally.disagreementRate)} of them " +
                "-- races the free-square flood called the wrong way",
        )
    }

    /**
     * One cut of the same matches: outcomes, then the two margins the cut is being checked against.
     *
     * Both tables print the margin columns because a row of either is only readable with them. A
     * phase row without them is the sign bucket that cannot tell a one-square lead from a commanding
     * one; a band row without them cannot say whether a band cut on free squares is a band on
     * spendable ground.
     */
    private fun table(heading: String, rows: List<Pair<String, Bucket>>, log: (String) -> Unit) {
        log("  ${heading.padEnd(LABEL)}played   won  drawn  lost   score    lead  usable")
        for ((label, bucket) in rows) {
            if (bucket.played == 0) {
                continue
            }
            log(
                "  ${label.padEnd(LABEL)}${bucket.played.toString().padStart(6)}" +
                    bucket.won.toString().padStart(6) +
                    bucket.drawn.toString().padStart(7) +
                    bucket.lost.toString().padStart(6) +
                    percent(bucket.score).padStart(8) +
                    margin(median(bucket.leads)).padStart(8) +
                    margin(median(bucket.usableLeads)).padStart(8),
            )
        }
    }

    private fun <T : Comparable<T>> median(values: List<T>): T? =
        if (values.isEmpty()) null else values.sorted()[values.size / 2]

    private fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

    /** A signed share of the room the two sides hold between them, or `--` where there is no split. */
    private fun margin(value: Double?): String =
        if (value == null) "--" else "${if (value >= 0) "+" else ""}${(value * 100).roundToInt()}%"

    /** Where a match was decided, in the order the game reaches them. */
    private enum class Phase(val label: String, val blame: String) {
        CONTACT("never came apart", "ended while the snakes could still reach each other"),
        BEHIND("split, behind on room", "were already behind when the board split"),
        LEVEL("split, level on room", "split level and went either way"),
        AHEAD("split, ahead on room", "were ahead when the board split and lost the fill anyway"),
    }

    /**
     * How far ahead, in bands wide enough to hold a population and narrow enough to mean something.
     *
     * Cut on the margin rather than on a square count, so a band means the same thing on every board
     * a field is run at. The two boundaries are the two places the quantity changes character:
     *
     * - **[LEVEL_MARGIN]**, a twentieth of the shared room. A snake grows every second move, so on
     *   the boards these logs hold that is about what one growth cycle costs — a lead the fill can
     *   spend on itself, and not a lead anybody played for.
     * - **[COMMANDING_MARGIN]**, a quarter, which is the leader holding five squares to the other's
     *   three. Past that the loser has to be given the game rather than take it.
     */
    private enum class Band(val label: String) {
        FAR_BEHIND("behind, commanding"),
        BEHIND("behind, clear"),
        LEVEL("level either way"),
        AHEAD("ahead, clear"),
        FAR_AHEAD("ahead, commanding"),
        ;

        companion object {
            fun of(lead: Double): Band = when {
                lead <= -COMMANDING_MARGIN -> FAR_BEHIND
                lead <= -LEVEL_MARGIN -> BEHIND
                lead < LEVEL_MARGIN -> LEVEL
                lead < COMMANDING_MARGIN -> AHEAD
                else -> FAR_AHEAD
            }
        }
    }

    /**
     * The per-geometry state a walk needs, built once per board shape the log holds.
     *
     * [PositionFeatures] is sized from the grid *and* the seat count, which is why the cache key
     * carries both where [Separation] alone would have wanted only the geometry.
     */
    private class Reader(match: Match) {
        val separation = Separation(match.grid)
        private val features = PositionFeatures(match.grid, match.view.snakeCount)
        private val row = DoubleArray(PositionFeatures.LENGTH)

        /** [PositionFeatures.USABLE_MARGIN] for [seat]: the free-square margin over spendable ground. */
        fun usableLeadAt(board: BoardView, seat: Int): Double {
            features.measure(board)
            features.into(seat, row)
            return row[PositionFeatures.USABLE_MARGIN]
        }
    }

    private class Split(
        /** The first move the free squares came apart at, whether or not it lasted. */
        val naiveAt: Int,
        /** The last one, which is the split the rest of the game was played from, or `-1`. */
        val permanentAt: Int,
        val mine: Int,
        val rival: Int,
        /** The same lead over the squares a walk can spend, which is not the same question. */
        val usableLead: Double,
        /** How many times the board came apart, which is one more than it eroded back. */
        val separations: Int,
        /**
         * Positions the conservative predicate called separated.
         *
         * **Structurally zero at two snakes and a real count past them** — a corpse is the only
         * permanent wall there is, and a two-snake match ends at the first death. See [Separation].
         */
        val conservative: Int,
        /** Positions this walk asked at all, which is every one with more than one snake alive. */
        val tested: Int,
    ) {
        val phase: Phase
            get() = when {
                permanentAt < 0 -> Phase.CONTACT
                mine > rival -> Phase.AHEAD
                mine < rival -> Phase.BEHIND
                else -> Phase.LEVEL
            }

        /**
         * The share of the room the two sides held between them that was this seat's, over even.
         *
         * A margin rather than a difference of squares, because the same difference is a rout on an
         * 8x8 and a rounding error on a 40x40, and the field this diagnoses is run at three sizes.
         */
        val lead: Double
            get() = if (mine + rival == 0) 0.0 else (mine - rival).toDouble() / (mine + rival)
    }

    private class Bucket {
        var played = 0
        var won = 0
        var drawn = 0
        var lost = 0
        val leads = mutableListOf<Double>()
        val usableLeads = mutableListOf<Double>()
        val score: Double get() = if (played == 0) 0.0 else (won + drawn * 0.5) / played

        /** A match ends up in exactly two buckets, so both cuts are filled from one call shape. */
        fun add(split: Split, won: Boolean, drawn: Boolean) {
            played++
            when {
                won -> this.won++
                drawn -> this.drawn++
                else -> lost++
            }
            if (split.permanentAt >= 0) {
                leads += split.lead
                usableLeads += split.usableLead
            }
        }
    }

    private class Tally {
        val buckets = Array(Phase.entries.size) { Bucket() }
        val bands = Array(Band.entries.size) { Bucket() }
        val naiveAt = mutableListOf<Int>()
        val permanentAt = mutableListOf<Int>()
        val erosion = mutableListOf<Int>()
        val length = mutableListOf<Int>()
        var played = 0
        var separated = 0
        var unreadable = 0
        var conservative = 0
        var conservativeMatches = 0
        var tested = 0
        private var eroded = 0
        private var disagreed = 0

        /** How often a separation that looked final was undone by the moves after it. */
        val erodedRate: Double get() = if (separated == 0) 0.0 else eroded.toDouble() / separated

        /**
         * How much of the contested play the conservative flood called settled.
         *
         * A rate rather than the bare count this used to print, because at two snakes the count was
         * structurally zero and needed no denominator and past two it needs one to mean anything.
         */
        val conservativeRate: Double get() = if (tested == 0) 0.0 else conservative.toDouble() / tested

        /** How often the free-square lead and the spendable-ground lead pointed opposite ways. */
        val disagreementRate: Double get() = if (separated == 0) 0.0 else disagreed.toDouble() / separated

        fun add(split: Split, turnsPlayed: Int, won: Boolean, drawn: Boolean) {
            played++
            length += turnsPlayed
            conservative += split.conservative
            tested += split.tested
            if (split.conservative > 0) {
                conservativeMatches++
            }
            buckets[split.phase.ordinal].add(split, won, drawn)

            if (split.permanentAt < 0) {
                return
            }
            separated++
            permanentAt += split.permanentAt
            naiveAt += split.naiveAt
            erosion += split.permanentAt - split.naiveAt
            if (split.separations > 1) eroded++
            bands[Band.of(split.lead).ordinal].add(split, won, drawn)

            // A lead inside the level band is not a side, so a sign flip within it is arithmetic
            // rather than a race called the wrong way.
            if (split.lead * split.usableLead < 0.0 && abs(split.lead) >= LEVEL_MARGIN) {
                disagreed++
            }
        }
    }

    private companion object {
        const val LABEL = 24

        /** Inside this either way is level: about one growth cycle of the room being contested. */
        const val LEVEL_MARGIN = 0.05

        /** Five squares to three, past which the leader has to be given the game rather than take it. */
        const val COMMANDING_MARGIN = 0.25
    }
}
