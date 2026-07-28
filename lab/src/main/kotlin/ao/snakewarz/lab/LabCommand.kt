package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.Tournament
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What the command line asked for, parsed and validated before anything is played.
 *
 * Two subcommands, because strength and cost are different measurements and conflating them would
 * produce a number about neither. [Play] answers "which of these is better", by running the real
 * [Tournament] over the real [ao.snakewarz.match.tournament.TournamentTable]. [Time] answers "what does one turn
 * of this cost", which a matrix cannot: a two-bot match's elapsed time is the *sum* of both bots'
 * thinking, so a per-contestant figure taken off a shared match is a figure about the pairing.
 *
 * ### Parsing is strict, and deliberately unlike [BotKnob.Param.read]
 *
 * A knob reads its value totally — an unparseable one falls back on the default — because `Match`
 * builds its bots in a field initializer with nothing above it to catch a throw, and one route in is
 * whatever somebody pasted into the address bar. A command line is the opposite situation: there is
 * a `main` above it, and a typo in a knob name would otherwise silently measure the default and
 * waste however many minutes the batch takes. So everything here throws, naming the offending token.
 */
internal sealed interface LabCommand {
    fun run(registry: BotRegistry, log: (String) -> Unit)

    /** A batch, printed as the win matrix the sidebar shows plus what it cost to produce. */
    class Play(val config: TournamentConfig) : LabCommand {
        override fun run(registry: BotRegistry, log: (String) -> Unit) {
            log("[lab] ${config.contestants.joinToString(" vs ")}")
            log("[lab] $config")

            val started = TimeSource.Monotonic.markNow()
            val tournament = Tournament(config, registry)
            val table = tournament.runToCompletion()
            val elapsed = started.elapsedNow()

            log("")
            log(table.toString())
            log("")
            log(
                "[lab] ${tournament.matchCount} matches, ${tournament.turnsPlayed} turns " +
                    "in ${elapsed.inWholeMilliseconds} ms",
            )
        }

        override fun toString(): String = "Play($config)"
    }

    /**
     * What a turn of one bot costs, measured against an opponent handed **no allowance at all**.
     *
     * That is what makes the number about one bot: [SPARRING_PARTNER] is the strongest thing in the
     * registry that spends nothing, so it plays a real game and contributes no search time to the
     * clock. The fastest of [passes] is reported rather than the mean, following
     * `RolloutTruncationTest` — every source of noise on a wall clock only ever adds time, so the
     * minimum is the closest thing to the figure being asked for.
     */
    class Time(
        val subject: Contestant,
        val rows: Int,
        val cols: Int,
        val seed: Long,
        val budgetPerTurn: Int,
        val passes: Int,
    ) : LabCommand {
        override fun run(registry: BotRegistry, log: (String) -> Unit) {
            val allowance = subject.budgetIn(budgetPerTurn)
            log("[lab] $subject on ${rows}x$cols at an allowance of $allowance, best of $passes")

            val setup = MatchSetup.create(
                rows = rows,
                cols = cols,
                slots = listOf(subject.bot, SPARRING_PARTNER),
                seed = seed,
                budgetPerTurn = budgetPerTurn,
                budgets = intArrayOf(allowance, 0),
                slotParams = listOf(subject.params, BotParams.EMPTY),
            )

            var best = Duration.INFINITE
            var turns = 0
            repeat(passes) {
                val match = Match(setup, registry)
                val mark = TimeSource.Monotonic.markNow()
                match.runToCompletion()
                val elapsed = mark.elapsedNow()

                turns = match.stats().slots[0].movesMade
                if (elapsed < best) {
                    best = elapsed
                }
            }

            if (turns == 0) {
                log("[lab] ${subject.label} never got a turn, so there is nothing to time")
                return
            }
            log("[lab] ${subject.label}: ${best.inWholeMicroseconds / turns} us/turn over $turns turns")
        }

        override fun toString(): String = "Time($subject, ${rows}x$cols, budget=$budgetPerTurn, passes=$passes)"
    }

    companion object {
        /**
         * The ladder's board, so a figure from here is comparable with `BotLadderTest`'s.
         *
         * `:lab` may not import `:bots`' test constants any more than `:bots` may import `:match`'s
         * `DEFAULT_BUDGET_PER_TURN`, so this is the third copy of the same twelve. It is a default on
         * a command line rather than a claim about anything, and `--rows`/`--cols` overrides it.
         */
        const val LADDER_BOARD: Int = 12

        /** Enough passes for the fastest of them to be about the code rather than about the machine. */
        const val DEFAULT_PASSES: Int = 5

        /** Strongest of the bots that spend nothing, so it plays a real game and costs no clock. */
        private val SPARRING_PARTNER = BotId("space")

        val USAGE: String = """
            Usage:
              play <entrant> <entrant> [...] [--rows N] [--cols N] [--rounds N] [--seed N]
                                            [--budget N] [--format head|ffa]
              time <entrant> [--rows N] [--cols N] [--seed N] [--budget N] [--passes N]

            An entrant is <slug>[:name=value,...], where `budget` is that entrant's own allowance
            and every other name is one of that bot's declared knobs. For example:

              play puct:eval=territory puct:eval=survival --rounds 40
              play uct uct:budget=100
              time puct:eval=survival --budget 2000
        """.trimIndent()

        fun of(args: List<String>, registry: BotRegistry): LabCommand {
            require(args.isNotEmpty()) { "nothing to do.\n\n$USAGE" }

            val subcommand = args.first()
            val (entrants, flags) = split(args.drop(1))

            return when (subcommand) {
                "play" -> playOf(entrants, flags, registry)
                "time" -> timeOf(entrants, flags, registry)
                else -> error("no such subcommand: '$subcommand'.\n\n$USAGE")
            }
        }

        /**
         * One entrant: which bot, what it may spend, and how it is tuned.
         *
         * `budget` is spelled the way it is spelled everywhere else — [BotKnob.Search.NAME] — rather
         * than invented here, so the same word means the same thing on the command line, in a replay
         * URL and on the sidebar. It stays **null** when nobody said otherwise, so
         * [TournamentConfig.budgetPerTurn] still has something to do.
         */
        fun contestantOf(spec: String, registry: BotRegistry): Contestant {
            val slug = spec.substringBefore(':')
            val entry = registry[BotId(slug)]
                ?: error("no such bot: '$slug'. Known: ${registry.entries.joinToString { it.id.slug }}")

            var budget: Int? = null
            val params = LinkedHashMap<String, String>()

            for (pair in spec.substringAfter(':', "").split(',').filter { it.isNotBlank() }) {
                require('=' in pair) { "'$slug': expected name=value, was '$pair'" }
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()

                if (name == BotKnob.Search.NAME) {
                    budget = value.toIntOrNull() ?: error("'$slug': budget must be a whole number, was '$value'")
                    require(budget >= 0) { "'$slug': budget must not be negative, was $budget" }
                    continue
                }

                val knob = entry.params.firstOrNull { it.name == name }
                    ?: error(
                        "'$slug' has no knob '$name'. Known: " +
                            entry.params.joinToString { it.name }.ifEmpty { "none" },
                    )
                knob.reject(value)?.let { error("'$slug' knob '$name' wants $it, was '$value'") }
                params[name] = value
            }

            return Contestant(BotId(slug), budget, BotParams(params))
        }

        private fun playOf(entrants: List<String>, flags: Flags, registry: BotRegistry): Play {
            require(entrants.size >= 2) { "play needs at least two entrants, was ${entrants.size}.\n\n$USAGE" }

            val rounds = flags.int("rounds", TournamentConfig.DEFAULT_ROUNDS)
            require(rounds > 0) { "--rounds must be positive, was $rounds" }
            require(rounds % 2 == 0) {
                "--rounds must be even so each seed is played from both seats, was $rounds"
            }

            val contestants = entrants.map { contestantOf(it, registry) }
            for (i in contestants.indices) {
                for (j in i + 1 until contestants.size) {
                    require(contestants[i] != contestants[j]) {
                        "'${entrants[i]}' and '${entrants[j]}' are the same entrant, which measures " +
                            "the seating and nothing else"
                    }
                }
            }

            return Play(
                TournamentConfig(
                    contestants = contestants,
                    rows = flags.int("rows", LADDER_BOARD),
                    cols = flags.int("cols", LADDER_BOARD),
                    rounds = rounds,
                    format = flags.format("format"),
                    seed = flags.long("seed", DEFAULT_SEED),
                    budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                ),
            )
        }

        private fun timeOf(entrants: List<String>, flags: Flags, registry: BotRegistry): Time {
            require(entrants.size == 1) { "time takes exactly one entrant, was ${entrants.size}.\n\n$USAGE" }

            val passes = flags.int("passes", DEFAULT_PASSES)
            require(passes > 0) { "--passes must be positive, was $passes" }

            return Time(
                subject = contestantOf(entrants.single(), registry),
                rows = flags.int("rows", LADDER_BOARD),
                cols = flags.int("cols", LADDER_BOARD),
                seed = flags.long("seed", DEFAULT_SEED),
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                passes = passes,
            )
        }

        private const val DEFAULT_SEED = 1L

        private fun split(args: List<String>): Pair<List<String>, Flags> {
            val entrants = mutableListOf<String>()
            val flags = LinkedHashMap<String, String>()

            var i = 0
            while (i < args.size) {
                val token = args[i]
                if (!token.startsWith("--")) {
                    entrants += token
                    i++
                    continue
                }

                val name = token.removePrefix("--")
                require(name in KNOWN_FLAGS) { "no such option: '$token'. Known: ${KNOWN_FLAGS.joinToString()}" }
                require(i + 1 < args.size) { "'$token' needs a value" }
                flags[name] = args[i + 1]
                i += 2
            }

            return entrants to Flags(flags)
        }

        private val KNOWN_FLAGS = setOf("rows", "cols", "rounds", "seed", "budget", "format", "passes")
    }
}

/** Options as typed, refused rather than coerced — see [LabCommand]'s note on strictness. */
private class Flags(private val values: Map<String, String>) {
    fun int(name: String, default: Int): Int {
        val text = values[name] ?: return default
        return text.toIntOrNull() ?: error("--$name wants a whole number, was '$text'")
    }

    fun long(name: String, default: Long): Long {
        val text = values[name] ?: return default
        return text.toLongOrNull() ?: error("--$name wants a whole number, was '$text'")
    }

    fun format(name: String): TournamentFormat {
        val text = values[name] ?: return TournamentFormat.HEAD_TO_HEAD
        return when (text) {
            "head", "head-to-head" -> TournamentFormat.HEAD_TO_HEAD
            "ffa", "free-for-all" -> TournamentFormat.FREE_FOR_ALL
            else -> error("--$name wants head or ffa, was '$text'")
        }
    }
}
