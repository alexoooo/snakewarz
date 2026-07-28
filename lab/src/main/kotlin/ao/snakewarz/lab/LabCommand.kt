package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Path

/**
 * What the command line asked for, parsed and validated before anything is played.
 *
 * Subcommands are separate because they are separate measurements and conflating them would produce
 * a number about neither. [PlayCommand] answers "which of these is better", by playing the real
 * schedule and filling in the real matrix. [TimeCommand] answers "what does one turn of this cost",
 * which a matrix cannot: a two-bot match's elapsed time is the *sum* of both bots' thinking, so a
 * per-contestant figure taken off a shared match is a figure about the pairing.
 *
 * ### Parsing is strict, and deliberately unlike [BotKnob.Param.read]
 *
 * A knob reads its value totally — an unparseable one falls back on the default — because `Match`
 * builds its bots in a field initializer with nothing above it to catch a throw, and one route in is
 * whatever somebody pasted into the address bar. A command line is the opposite situation: there is
 * a `main` above it, and a typo in a knob name would otherwise silently measure the default and
 * waste however many minutes the batch takes. So everything here throws, naming the offending token.
 *
 * Each subcommand declares the options **it** takes, for the same reason: a name accepted everywhere
 * is a name that can be given to the wrong subcommand and quietly ignored.
 */
internal interface LabCommand {
    fun run(registry: BotRegistry, log: (String) -> Unit)

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

        private const val DEFAULT_SEED = 1L

        private val PLAY_FLAGS = setOf(
            "rows", "cols", "rounds", "seed", "budget", "format", "openings", "threads",
            "replays", "log",
        )
        private val TIME_FLAGS = setOf("rows", "cols", "seed", "budget", "passes")
        private val RATE_FLAGS = setOf("log", "board", "budget", "format", "build", "openings", "since", "pool")

        /** The `rate` options that narrow the log rather than configure the report. */
        private val RATE_FILTERS = setOf("board", "budget", "format", "build", "openings", "since")

        private val REPORT_FLAGS = setOf("log", "against", "worst")

        private val TUNE_FLAGS = setOf(
            "knobs", "rows", "cols", "seed", "budget", "openings", "threads",
            "passes", "block", "max-pairs", "journal", "elo1",
        )

        /** Enough for a coarse sweep and two halvings, which is where a stride search stops paying. */
        private const val DEFAULT_SEARCH_PASSES = 6

        /** A search runs dozens of tests, so each one stops sooner than a standalone `ab` would. */
        private const val TUNE_MAX_PAIRS = 200

        /** Enough losses to see a pattern in, few enough to actually open. */
        private const val DEFAULT_WORST = 5

        private val AB_FLAGS = setOf(
            "rows", "cols", "seed", "budget", "openings", "threads", "log",
            "elo0", "elo1", "alpha", "beta", "block", "max-pairs",
        )

        /**
         * Zero and five Elo: "no better at all" against "better by something worth having".
         *
         * A hypothesis of exactly zero cannot be tested — every change differs from zero at some
         * sample size — so the upper bound is what makes the question answerable, and five points is
         * about the smallest gain worth the compute of confirming.
         */
        private const val DEFAULT_ELO0 = 0.0
        private const val DEFAULT_ELO1 = 5.0

        /** One in twenty each way, the usual pair, and the reason a sweep needs a confirming run. */
        private const val DEFAULT_ALPHA = 0.05
        private const val DEFAULT_BETA = 0.05

        /** Boards between checks. Small enough to stop early, large enough not to test constantly. */
        private const val DEFAULT_BLOCK = 20

        /** A ceiling so an even pairing ends the session rather than running until somebody notices. */
        private const val DEFAULT_MAX_PAIRS = 2_000

        val USAGE: String = """
            Usage:
              play <entrant> <entrant> [...] [--rows N] [--cols N] [--rounds N] [--seed N]
                                            [--budget N] [--format head|ffa]
                                            [--openings mirrored|fixed] [--threads N]
                                            [--replays decisive|none|all] [--log DIR|none]
              time <entrant> [--rows N] [--cols N] [--seed N] [--budget N] [--passes N]
              rate [--log DIR] [--board RxC] [--budget N] [--format head|ffa] [--build SHA]
                   [--openings mirrored|fixed] [--since RUN] [--pool true]
              ab <baseline> <candidate> [--elo0 N] [--elo1 N] [--alpha N] [--beta N]
                                        [--block N] [--max-pairs N] [--rows N] [--cols N]
                                        [--seed N] [--budget N] [--openings ...] [--threads N]
              report <entrant> [--against <entrant>] [--worst N] [--log DIR]
              tune <slug> [--knobs a,b,c] [--passes N] [--block N] [--max-pairs N]
                          [--rows N] [--cols N] [--seed N] [--budget N] [--openings ...]
                          [--threads N] [--journal FILE]

            An entrant is <slug>[:name=value,...], where `budget` is that entrant's own allowance
            and every other name is one of that bot's declared knobs. For example:

              play puct:eval=territory puct:eval=survival --rounds 40
              play uct uct:budget=100
              time puct:eval=survival --budget 2000
              rate --board 12x12 --budget 1000
              ab uct uct:exploration=2.5
              report puct --against uct --worst 5
              tune puct --knobs cpuct,territoryWeight

            `tune` searches a bot's declared knobs and recommends; it never edits a default. Adopting
            one moves every golden move-stream hash, and that is a question for a person to answer.

            `rate` reads the log rather than playing anything, and refuses to average across runs
            that are not comparable -- a different board, allowance or build is a different
            measurement. Narrow it, or pass --pool true and read the number for less than it says.

            `ab` plays until the result is conclusive rather than for a set number of games, and
            stops as soon as the candidate is either clearly above --elo1 or clearly not. It is the
            way to decide whether a change is worth keeping; a matrix is not.

            Openings default to `mirrored`: a square drawn from the seed with the opponent at its
            image through the centre of the board. `fixed` puts them in opposite corners every
            match, which is what the engine does and what the shipped ladder was measured under --
            and which gives bots that draw no randomness the same few games over and over.

            Every batch is appended to the match log under .lab/, which is what `rate` and `report`
            read. `--log none` runs without recording anything.
        """.trimIndent()

        fun of(args: List<String>, registry: BotRegistry): LabCommand {
            require(args.isNotEmpty()) { "nothing to do.\n\n$USAGE" }

            val subcommand = args.first()
            val (entrants, options) = split(args.drop(1))

            return when (subcommand) {
                "play" -> playOf(entrants, Flags(options, PLAY_FLAGS), registry)
                "time" -> timeOf(entrants, Flags(options, TIME_FLAGS), registry)
                "rate" -> rateOf(entrants, options, Flags(options, RATE_FLAGS))
                "ab" -> abOf(entrants, Flags(options, AB_FLAGS), registry)
                "report" -> reportOf(entrants, Flags(options, REPORT_FLAGS))
                "tune" -> tuneOf(entrants, Flags(options, TUNE_FLAGS), registry)
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

        private fun playOf(entrants: List<String>, flags: Flags, registry: BotRegistry): PlayCommand {
            require(entrants.size >= 2) { "play needs at least two entrants, was ${entrants.size}.\n\n$USAGE" }

            val rounds = flags.int("rounds", TournamentConfig.DEFAULT_ROUNDS)
            require(rounds > 0) { "--rounds must be positive, was $rounds" }
            require(rounds % 2 == 0) {
                "--rounds must be even so each seed is played from both seats, was $rounds"
            }

            val threads = flags.int("threads", Arena.defaultThreads())
            require(threads > 0) { "--threads must be positive, was $threads" }

            val contestants = entrants.map { contestantOf(it, registry) }
            for (i in contestants.indices) {
                for (j in i + 1 until contestants.size) {
                    require(contestants[i] != contestants[j]) {
                        "'${entrants[i]}' and '${entrants[j]}' are the same entrant, which measures " +
                            "the seating and nothing else"
                    }
                }
            }

            return PlayCommand(
                config = TournamentConfig(
                    contestants = contestants,
                    rows = flags.int("rows", LADDER_BOARD),
                    cols = flags.int("cols", LADDER_BOARD),
                    rounds = rounds,
                    format = flags.format("format"),
                    seed = flags.long("seed", DEFAULT_SEED),
                    budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                ),
                openings = flags.openings("openings"),
                threads = threads,
                replays = flags.replays("replays"),
                logDirectory = flags.logDirectory("log"),
            )
        }

        private fun tuneOf(entrants: List<String>, flags: Flags, registry: BotRegistry): TuneCommand {
            require(entrants.size == 1) { "tune searches one bot's knobs, was ${entrants.size}.\n\n$USAGE" }

            val slug = entrants.single()
            val entry = registry[BotId(slug)]
                ?: error("no such bot: '$slug'. Known: ${registry.entries.joinToString { it.id.slug }}")
            require(entry.params.isNotEmpty()) {
                "'$slug' declares no knobs, so there is nothing to search. " +
                    "A constant becomes searchable by being declared as a knob first."
            }

            val named = flags.text("knobs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val knobs = if (named == null) {
                entry.params
            } else {
                named.map { name ->
                    entry.params.firstOrNull { it.name == name }
                        ?: error("'$slug' has no knob '$name'. Known: ${entry.params.joinToString { it.name }}")
                }
            }

            val passes = flags.int("passes", DEFAULT_SEARCH_PASSES)
            require(passes > 0) { "--passes must be positive, was $passes" }

            val threads = flags.int("threads", Arena.defaultThreads())
            require(threads > 0) { "--threads must be positive, was $threads" }

            val block = flags.int("block", DEFAULT_BLOCK)
            require(block > 0) { "--block must be positive, was $block" }

            val maxPairs = flags.int("max-pairs", TUNE_MAX_PAIRS)
            require(maxPairs >= Sprt.MINIMUM_PAIRS) {
                "--max-pairs must leave room for the ${Sprt.MINIMUM_PAIRS} boards a verdict needs, was $maxPairs"
            }

            return TuneCommand(
                subject = BotId(slug),
                knobs = knobs,
                rows = flags.int("rows", LADDER_BOARD),
                cols = flags.int("cols", LADDER_BOARD),
                seed = flags.long("seed", DEFAULT_SEED),
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                openings = flags.openings("openings"),
                threads = threads,
                passes = passes,
                blockPairs = block,
                maxPairs = maxPairs,
                searchElo1 = flags.decimal("elo1", TuneCommand.SEARCH_ELO1),
                journalFile = Path.of(flags.text("journal") ?: "${MatchLog.DEFAULT_DIRECTORY}/tune-$slug.tsv"),
            )
        }

        private fun reportOf(entrants: List<String>, flags: Flags): ReportCommand {
            require(entrants.size == 1) {
                "report diagnoses one entrant, was ${entrants.size}.\n\n$USAGE"
            }

            val worst = flags.int("worst", DEFAULT_WORST)
            require(worst >= 0) { "--worst must not be negative, was $worst" }

            val directory = flags.logDirectory("log")
                ?: error("report reads the match log, so `--log none` leaves it nothing to read")

            return ReportCommand(
                subject = entrants.single(),
                against = flags.text("against"),
                worst = worst,
                logDirectory = directory,
            )
        }

        private fun abOf(entrants: List<String>, flags: Flags, registry: BotRegistry): AbCommand {
            require(entrants.size == 2) {
                "ab compares a candidate with a baseline, so it takes two entrants, " +
                    "was ${entrants.size}.\n\n$USAGE"
            }

            val baseline = contestantOf(entrants[0], registry)
            val candidate = contestantOf(entrants[1], registry)
            require(baseline != candidate) {
                "'${entrants[0]}' and '${entrants[1]}' are the same entrant. That measures the " +
                    "seating, and a sequential test on it never stops."
            }

            val block = flags.int("block", DEFAULT_BLOCK)
            require(block > 0) { "--block must be positive, was $block" }

            val maxPairs = flags.int("max-pairs", DEFAULT_MAX_PAIRS)
            require(maxPairs >= Sprt.MINIMUM_PAIRS) {
                "--max-pairs must leave room for the ${Sprt.MINIMUM_PAIRS} boards a verdict needs, was $maxPairs"
            }

            val threads = flags.int("threads", Arena.defaultThreads())
            require(threads > 0) { "--threads must be positive, was $threads" }

            return AbCommand(
                baseline = baseline,
                candidate = candidate,
                rows = flags.int("rows", LADDER_BOARD),
                cols = flags.int("cols", LADDER_BOARD),
                seed = flags.long("seed", DEFAULT_SEED),
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                openings = flags.openings("openings"),
                threads = threads,
                sprt = Sprt(
                    elo0 = flags.decimal("elo0", DEFAULT_ELO0),
                    elo1 = flags.decimal("elo1", DEFAULT_ELO1),
                    alpha = flags.decimal("alpha", DEFAULT_ALPHA),
                    beta = flags.decimal("beta", DEFAULT_BETA),
                ),
                blockPairs = block,
                maxPairs = maxPairs,
                logDirectory = flags.logDirectory("log"),
            )
        }

        private fun rateOf(entrants: List<String>, options: Map<String, String>, flags: Flags): RateCommand {
            require(entrants.isEmpty()) {
                "rate reads the log rather than playing, so it takes no entrants: '${entrants.first()}'.\n\n$USAGE"
            }

            val directory = flags.logDirectory("log")
                ?: error("rate reads the match log, so `--log none` leaves it nothing to read")

            return RateCommand(
                logDirectory = directory,
                filters = options.filterKeys { it in RATE_FILTERS },
                pool = flags.flag("pool"),
            )
        }

        private fun timeOf(entrants: List<String>, flags: Flags, registry: BotRegistry): TimeCommand {
            require(entrants.size == 1) { "time takes exactly one entrant, was ${entrants.size}.\n\n$USAGE" }

            val passes = flags.int("passes", DEFAULT_PASSES)
            require(passes > 0) { "--passes must be positive, was $passes" }

            return TimeCommand(
                subject = contestantOf(entrants.single(), registry),
                rows = flags.int("rows", LADDER_BOARD),
                cols = flags.int("cols", LADDER_BOARD),
                seed = flags.long("seed", DEFAULT_SEED),
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                passes = passes,
            )
        }

        /**
         * Splits a tail into entrants and `--name value` options.
         *
         * Whether a name is *allowed* is [Flags]' business, because the answer depends on which
         * subcommand is being built and this does not know.
         */
        private fun split(args: List<String>): Pair<List<String>, Map<String, String>> {
            val entrants = mutableListOf<String>()
            val options = LinkedHashMap<String, String>()

            var i = 0
            while (i < args.size) {
                val token = args[i]
                if (!token.startsWith("--")) {
                    entrants += token
                    i++
                    continue
                }

                val name = token.removePrefix("--")
                require(i + 1 < args.size) { "'$token' needs a value" }
                options[name] = args[i + 1]
                i += 2
            }

            return entrants to options
        }
    }
}
