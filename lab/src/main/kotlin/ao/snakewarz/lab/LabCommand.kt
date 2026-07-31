package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.lab.tune.KnobSpace
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
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

        /** What `--density` means when nobody said: whatever the shape ships with. */
        private const val SHIPPED_DENSITY = 0.0

        private val PLAY_FLAGS = setOf(
            "rows", "cols", "rounds", "seed", "budget", "format", "openings", "threads",
            "replays", "log", "map", "density",
        )
        private val TIME_FLAGS = setOf("rows", "cols", "seed", "budget", "passes", "map", "density")

        /**
         * No geometry here, and that is the point: every board a `ladder` run plays comes off the
         * level rather than off the command line. A `--rows` accepted here would measure ten levels
         * on a board none of them is played on.
         */
        private val LADDER_FLAGS = setOf("against", "rounds", "seed", "openings", "threads", "log")

        private val RATE_FLAGS = setOf(
            "log", "board", "budget", "format", "build", "openings", "since", "pool", "map",
        )

        /** The `rate` options that narrow the log rather than configure the report. */
        private val RATE_FILTERS = setOf("board", "budget", "format", "build", "openings", "since", "map")

        private val REPORT_FLAGS = setOf("log", "against", "worst")

        private val PHASES_FLAGS = setOf("log", "against")

        private val TUNE_FLAGS = setOf(
            "knobs", "rows", "cols", "seed", "budget", "openings", "threads", "map", "density",
            "passes", "block", "max-pairs", "journal", "elo1",
        )

        private val SPSA_FLAGS = setOf(
            "knobs", "rows", "cols", "seed", "budget", "openings", "threads", "map", "density",
            "iterations", "boards", "spread", "stride", "max-pairs", "journal", "elo1",
        )

        private val TRAIN_FLAGS = setOf(
            "log", "rows", "cols", "stride", "positions", "hidden", "epochs", "rate", "decay",
            "batch", "seed", "out", "model",
        )

        /**
         * Gradient steps by default. With [DEFAULT_BOARDS] that is 1,200 boards, about an `ab` run.
         *
         * **Iterations and boards buy the same total evidence and are not interchangeable.** Doubling
         * either doubles the games; doubling the iterations also halves the noise left in the
         * averaged tail, because those are more independent draws to average, while doubling the
         * boards only sharpens a step the next one overwrites. So when a search has not settled,
         * this is the setting to raise.
         */
        private const val DEFAULT_ITERATIONS = 200

        /**
         * Boards behind one gradient estimate.
         *
         * Small on purpose, and it is meant to look too small. An iteration needs a direction that
         * is right on average, not a verdict — six boards give a gap whose sign is wrong a third of
         * the time, and the schedule is what turns two hundred of those into a point.
         */
        private const val DEFAULT_BOARDS = 6

        /** Declared steps between the two arms and the centre — `TuneCommand`'s coarse stride. */
        private const val DEFAULT_SPREAD = 8.0

        /**
         * The most a knob may move on the first iteration, in its own declared steps.
         *
         * A ceiling, so the typical move is far smaller: on [DEFAULT_BOARDS] boards a gap is about
         * `0.18` even between two arms of equal strength, so six steps of ceiling is about one step
         * of noise a move. That is the balance point, and it was measured rather than picked —
         * `SpsaTest` walks a synthetic bowl of the curvature a knob here has, and at
         * [DEFAULT_ITERATIONS] the point finishes 12 of a hundred steps from the answer at a stride
         * of 1, 7 at 6, and 8 again at 12. Below the balance the run never arrives; above it, the
         * run arrives and then wanders.
         */
        private const val DEFAULT_STRIDE = 6.0

        /** The confirming run gets far more boards than a search step, and needs them. */
        private const val SPSA_CONFIRM_PAIRS = 800

        /**
         * The narrowest range a perturbation fits in, in the knob's own declared steps.
         *
         * The two arms sit half the spread either side of a centre, and the spread floors at one
         * step because below that they snap to the same entrant spec. So a range of two is the least
         * that can hold both of them.
         */
        private const val MINIMUM_KNOB_STEPS = 2.0

        /** Enough for a coarse sweep and two halvings, which is where a stride search stops paying. */
        private const val DEFAULT_SEARCH_PASSES = 6

        /** A search runs dozens of tests, so each one stops sooner than a standalone `ab` would. */
        private const val TUNE_MAX_PAIRS = 200

        /** Enough losses to see a pattern in, few enough to actually open. */
        private const val DEFAULT_WORST = 5

        private val AB_FLAGS = setOf(
            "rows", "cols", "seed", "budget", "openings", "threads", "log", "map", "density",
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
                                            [--map SHAPE] [--density F]
                                            [--replays decisive|none|all] [--log DIR|none]
              time <entrant> [--rows N] [--cols N] [--seed N] [--budget N] [--passes N]
                             [--map SHAPE] [--density F]
              rate [--log DIR] [--board RxC] [--budget N] [--format head|ffa] [--build SHA]
                   [--openings mirrored|fixed] [--map SHAPE] [--since RUN] [--pool true]
              ab <baseline> <candidate> [--elo0 N] [--elo1 N] [--alpha N] [--beta N]
                                        [--block N] [--max-pairs N] [--rows N] [--cols N]
                                        [--seed N] [--budget N] [--openings ...] [--threads N]
                                        [--map SHAPE] [--density F]
              report <entrant> [--against <entrant>] [--worst N] [--log DIR]
              phases <entrant> [--against <entrant>] [--log DIR]
              tune <entrant> [--knobs a,b,c] [--passes N] [--block N] [--max-pairs N]
                             [--rows N] [--cols N] [--seed N] [--budget N] [--openings ...]
                             [--threads N] [--map SHAPE] [--density F] [--journal FILE]
              spsa <entrant> [--knobs a,b,c] [--iterations N] [--boards N] [--spread N] [--stride N]
                             [--rows N] [--cols N] [--seed N] [--budget N] [--openings ...]
                             [--threads N] [--map SHAPE] [--density F] [--journal FILE]
                             [--elo1 N] [--max-pairs N]
              train [--log DIR] [--rows N] [--cols N] [--stride N] [--positions N] [--hidden N]
                    [--epochs N] [--rate N] [--decay N] [--batch N] [--seed N] [--out FILE]
                    [--model FILE]
              ladder [--against <entrant>] [--rounds N] [--seed N] [--openings ...] [--threads N]
                     [--log DIR|none]

            An entrant is <slug>[:name=value,...], where `budget` is that entrant's own allowance
            and every other name is one of that bot's declared knobs. For example:

              play puct:eval=territory puct:eval=survival --rounds 40
              play uct uct:budget=100
              play uct puct --map cross --rows 12 --cols 12 --rounds 100
              time puct:eval=survival --budget 2000
              rate --board 12x12 --budget 1000 --map cross
              ab uct uct:exploration=2.5
              report puct:eval=horizon --against puct --worst 5
              phases puct:eval=learned --log .lab/rave-field
              tune puct --knobs cpuct,territoryWeight
              spsa puct:eval=chamber --knobs parityWeight,sealPenalty --budget 1000
              train --rows 12 --cols 12 --hidden 8 --epochs 40
              train --log .lab/p2b-field-20 --rows 20 --cols 20 --model .lab/shipped-model.txt
              ladder --rounds 40
              ladder --rounds 40 --against uct:budget=100

            `tune` and `spsa` both search a bot's declared knobs and recommend; neither ever edits a
            default. Adopting one moves every golden move-stream hash, and that is a question for a
            person to answer.

            Both take a full entrant spec, and everything it names is held still -- in both arms of
            every measurement *and* in the baseline the confirming run is against. A weight that
            lives under a choice, as ChamberEval's three live under `eval=chamber`, is otherwise
            searched at a setting where it does nothing at all.

            `tune` is coordinate descent: one knob at a time, each step decided by a sequential test.
            Read every step, right shape up to about three knobs. `spsa` estimates a gradient over
            every numeric knob at once from two measurements, so it costs the same per step at ten
            knobs as at one -- and it refuses a choice or a flag, which have no gradient. Either way
            the only number worth acting on is the confirming run over fresh boards it ends with.

            `report` names an entrant by any subset of its knobs -- `puct:eval=horizon` finds the
            logged entrant playing that evaluation, whatever else it was set to. An ambiguous name
            lists what it could have meant. `phases` names one the same way.

            `phases` plays nothing either: it replays the log and splits each match at the move the
            board comes apart for good -- the flood that treats a *living* snake's body as ground,
            since that barrier erodes -- and then reports the wins and losses on each side of that
            line. It answers the question a rating cannot: whether the points are going missing
            while the snakes still share ground, or in the filling race afterwards.

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

            `--map` draws interior walls, one map for the whole run, at its --rows/--cols and --seed:
            ${MapShape.entries.joinToString { it.slug }}. `empty` is the default and is a bare
            rectangle, so a run that names it is identical to one that says nothing. A shape refuses
            a board too small to express it rather than drawing a degenerate version. `--density`
            is the fraction of the board `scatter` fills; the other shapes are a function of the
            geometry alone and ignore it.

            Every rung of the shipped ladder was certified on an empty rectangle, and a rating is
            conditioned on the geometry as much as on the field -- so a map is a new measurement and
            not a variation on an old one. `rate --map <shape>` narrows the log to it, and refuses
            to pool it with anything else, for the reason a different board size is refused.

            `train` plays nothing at all: it replays the move streams already in the log, reads each
            position as a feature vector and fits the value function `puct:eval=learned` uses. It
            prints a literal for `LearnedWeights` and never edits one, for the reason `tune` does
            not edit a default. Everything it reports is over games it held out whole. With
            `--model FILE` it fits nothing and scores that literal over the whole corpus instead,
            which is how a fit taken on one board is read on another.

            `ladder` takes no board at all: it plays each of the ten single-player levels on that
            level's own geometry, map and allowance, against one fixed reference, and prints the
            reference's score per level. The order is right when that score falls -- and a level that
            comes out easier than the one below it is a table to reorder, not a run to repeat. The
            reference is `--against`, and its allowance is held still across every level, which is
            what makes the ten readings comparable.

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
                "phases" -> phasesOf(entrants, Flags(options, PHASES_FLAGS))
                "tune" -> tuneOf(entrants, Flags(options, TUNE_FLAGS), registry)
                "spsa" -> spsaOf(entrants, Flags(options, SPSA_FLAGS), registry)
                "train" -> trainOf(entrants, Flags(options, TRAIN_FLAGS))
                "ladder" -> ladderOf(entrants, Flags(options, LADDER_FLAGS), registry)
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

            val rows = flags.int("rows", LADDER_BOARD)
            val cols = flags.int("cols", LADDER_BOARD)
            val seed = flags.long("seed", DEFAULT_SEED)

            return PlayCommand(
                config = TournamentConfig(
                    contestants = contestants,
                    rows = rows,
                    cols = cols,
                    rounds = rounds,
                    format = flags.format("format"),
                    seed = seed,
                    budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                    walls = flags.walls(rows, cols, seed),
                ),
                openings = flags.openings("openings"),
                threads = threads,
                replays = flags.replays("replays"),
                logDirectory = flags.logDirectory("log"),
            )
        }

        /**
         * A search's subject: the bot it walks, and the knobs an entrant spec pins around it.
         *
         * Both searches take a full **entrant spec** rather than a bare slug, and it is not a
         * convenience. A weight can live under a `Choice` — `ChamberEval`'s three do nothing
         * whatever unless `eval=chamber` — so a search handed only the slug would perturb them at a
         * setting where they are dead code and report a perfectly well-formed answer about a bot it
         * was not searching. What is pinned here is held still in **both arms of every measurement
         * and in the baseline the confirming run is against**; getting the second half wrong leaves
         * no trace at all in the output, which is what makes it worth a type.
         */
        private class Subject(val entry: BotEntry, val bot: BotId, val fixed: BotParams)

        /** The spec, parsed once, with the one thing a search may not read off it refused. */
        private fun subjectOf(spec: String, registry: BotRegistry): Subject {
            val contestant = contestantOf(spec, registry)
            require(contestant.budgetPerTurn == null) {
                "'$spec': a search's allowance is `--budget`, because every measurement it makes has " +
                    "to be at the same one. Take `budget=` off the spec."
            }
            return Subject(checkNotNull(registry[contestant.bot]), contestant.bot, contestant.params)
        }

        /**
         * Which knobs a run searches: what `--knobs` names, or [offered] with the pinned ones out.
         *
         * A knob the spec pins is not searchable. It is held still or it is moved, and a run doing
         * both would have to write two values for one name into one entrant spec.
         */
        private fun searched(
            subject: Subject,
            named: List<String>?,
            offered: List<BotKnob.Param<*>>,
        ): List<BotKnob.Param<*>> {
            val slug = subject.bot.slug
            val knobs = named?.map { name ->
                subject.entry.params.firstOrNull { it.name == name }
                    ?: error(
                        "'$slug' has no knob '$name'. Known: " +
                            subject.entry.params.joinToString { it.name }.ifEmpty { "none" },
                    )
            } ?: offered.filter { it.name !in subject.fixed.names }

            val both = knobs.filter { it.name in subject.fixed.names }
            require(both.isEmpty()) {
                "'$slug': ${both.joinToString { it.name }} is pinned by the entrant spec and named " +
                    "for searching. A knob is one or the other -- a run doing both would write two " +
                    "values for it into one spec."
            }
            return knobs
        }

        private fun Flags.knobNames(): List<String>? =
            text("knobs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

        /**
         * The map every match of the run is played on: `--map <shape>` at this run's own geometry.
         *
         * One map per run rather than one per match, so a batch is a comparison on a board rather
         * than a comparison across boards. The seed is the run's, so `--map scatter` is reproducible
         * from the command line alone and two runs at different seeds are two different scatterings.
         *
         * `--map empty` is the default and produces no walls at all, so a run that names it is
         * byte-identical to a run that says nothing — which is what lets every existing command and
         * every logged batch keep its meaning.
         *
         * A shape nobody offers lists the ones that exist, the way [contestantOf] does for a bot id.
         * A shape that cannot be drawn at this size refuses by name from [generateMap], because a
         * cross with no arms is a bug in the game rather than a small cross.
         */
        private fun Flags.walls(rows: Int, cols: Int, seed: Long): IntArray {
            val slug = text("map") ?: MapShape.EMPTY.slug
            val shape = MapShape.ofSlug(slug)
                ?: error("no such map: '$slug'. Known: ${MapShape.entries.joinToString { it.slug }}")

            // A density with no map behind it draws nothing and would be read back as a setting that
            // took effect. Which shapes read one is `generateMap`'s business, not this parser's.
            require(text("density") == null || text("map") != null) {
                "--density asks a map for a fraction of the board, so it needs --map. " +
                    "The default map is '${MapShape.EMPTY.slug}' and has no walls to place."
            }

            return generateMap(rows, cols, shape, decimal("density", SHIPPED_DENSITY), seed).walls()
        }

        private fun tuneOf(entrants: List<String>, flags: Flags, registry: BotRegistry): TuneCommand {
            require(entrants.size == 1) { "tune searches one bot's knobs, was ${entrants.size}.\n\n$USAGE" }

            val subject = subjectOf(entrants.single(), registry)
            val slug = subject.bot.slug
            require(subject.entry.params.isNotEmpty()) {
                "'$slug' declares no knobs, so there is nothing to search. " +
                    "A constant becomes searchable by being declared as a knob first."
            }

            val knobs = searched(subject, flags.knobNames(), subject.entry.params)
            require(knobs.isNotEmpty()) {
                "'${entrants.single()}' pins every knob '$slug' has, so there is nothing left to search."
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

            val rows = flags.int("rows", LADDER_BOARD)
            val cols = flags.int("cols", LADDER_BOARD)
            val boardSeed = flags.long("seed", DEFAULT_SEED)

            return TuneCommand(
                subject = subject.bot,
                fixed = subject.fixed,
                knobs = knobs,
                rows = rows,
                cols = cols,
                seed = boardSeed,
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                walls = flags.walls(rows, cols, boardSeed),
                openings = flags.openings("openings"),
                threads = threads,
                passes = passes,
                blockPairs = block,
                maxPairs = maxPairs,
                searchElo1 = flags.decimal("elo1", TuneCommand.SEARCH_ELO1),
                journalFile = Path.of(flags.text("journal") ?: "${MatchLog.DEFAULT_DIRECTORY}/tune-$slug.tsv"),
            )
        }

        /**
         * A gradient search over the numeric knobs, which is the one thing `tune` cannot scale to.
         *
         * The two refusals here are the whole of the type checking a stochastic optimiser needs, and
         * both are made before a single match is played. A `Choice` or a `Flag` has no direction to
         * be perturbed along, so naming one is an error rather than a coordinate quietly built out
         * of the order its values were declared in. And a knob narrower than [MINIMUM_KNOB_STEPS] of
         * its own steps cannot hold two arms a step apart, so its two arms would write the same
         * entrant spec and the batch would measure the seating.
         */
        private fun spsaOf(entrants: List<String>, flags: Flags, registry: BotRegistry): SpsaCommand {
            require(entrants.size == 1) { "spsa searches one bot's knobs, was ${entrants.size}.\n\n$USAGE" }

            val subject = subjectOf(entrants.single(), registry)
            val slug = subject.bot.slug
            val entry = subject.entry

            val chosen = searched(subject, flags.knobNames(), entry.params.filter { KnobSpace.span(it) != null })

            val flat = chosen.filter { KnobSpace.span(it) == null }
            require(flat.isEmpty()) {
                "'$slug': ${flat.joinToString { it.name }} is not a number, and a perturbation has no " +
                    "direction to move a name in. `tune` enumerates a choice or a flag; name only " +
                    "numeric knobs here."
            }
            require(chosen.isNotEmpty()) {
                "'$slug' declares no numeric knob, so there is no gradient to walk. It has " +
                    "${entry.params.joinToString { it.name }.ifEmpty { "none" }} -- `tune` searches those."
            }
            for (knob in chosen) {
                val steps = KnobSpace.span(knob)?.steps ?: 0.0
                require(steps >= MINIMUM_KNOB_STEPS) {
                    "'$slug' knob '${knob.name}' declares $steps steps of range, and two arms need " +
                        "$MINIMUM_KNOB_STEPS between them or they write the same entrant. Leave it out."
                }
            }

            val iterations = flags.int("iterations", DEFAULT_ITERATIONS)
            require(iterations > 0) { "--iterations must be positive, was $iterations" }

            val boards = flags.int("boards", DEFAULT_BOARDS)
            require(boards > 0) { "--boards must be positive, was $boards" }

            val threads = flags.int("threads", Arena.defaultThreads())
            require(threads > 0) { "--threads must be positive, was $threads" }

            val maxPairs = flags.int("max-pairs", SPSA_CONFIRM_PAIRS)
            require(maxPairs >= Sprt.MINIMUM_PAIRS) {
                "--max-pairs must leave room for the ${Sprt.MINIMUM_PAIRS} boards a verdict needs, was $maxPairs"
            }

            val rows = flags.int("rows", LADDER_BOARD)
            val cols = flags.int("cols", LADDER_BOARD)
            val boardSeed = flags.long("seed", DEFAULT_SEED)

            return SpsaCommand(
                subject = subject.bot,
                fixed = subject.fixed,
                knobs = chosen,
                ignored = entry.params
                    .filter { KnobSpace.span(it) == null && it.name !in subject.fixed.names }
                    .map { it.name },
                rows = rows,
                cols = cols,
                seed = boardSeed,
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                walls = flags.walls(rows, cols, boardSeed),
                openings = flags.openings("openings"),
                threads = threads,
                iterations = iterations,
                boardsPerIteration = boards,
                spread = flags.decimal("spread", DEFAULT_SPREAD),
                stride = flags.decimal("stride", DEFAULT_STRIDE),
                confirm = Sprt(
                    elo0 = DEFAULT_ELO0,
                    elo1 = flags.decimal("elo1", TuneCommand.CONFIRM_ELO1),
                    alpha = DEFAULT_ALPHA,
                    beta = DEFAULT_BETA,
                ),
                confirmPairs = maxPairs,
                journalFile = Path.of(flags.text("journal") ?: "${MatchLog.DEFAULT_DIRECTORY}/spsa-$slug.tsv"),
            )
        }

        /**
         * The one subcommand that plays nothing: it reads the log and fits a model to it.
         *
         * `--rows`/`--cols` here narrow the *corpus* rather than configuring a board, because the
         * log holds batches from several board sizes and a value function fitted across them is
         * fitted on a mixture nobody chose. Left off, everything logged is used, which is the right
         * default for a feature vector built out of shares.
         */
        private fun trainOf(entrants: List<String>, flags: Flags): TrainCommand {
            require(entrants.isEmpty()) {
                "train reads the log rather than playing, so it takes no entrants: " +
                    "'${entrants.first()}'.\n\n$USAGE"
            }

            // A list, where every other subcommand takes one directory: a corpus is the union of
            // whatever batches are worth learning from, and which those are is a judgement about the
            // *play* in them. A field of reactive bots and an `ab` between two searchers are both
            // logged matches and are not both training data for a leaf that will only ever be asked
            // about positions a search reached.
            val directories = (flags.text("log") ?: MatchLog.DEFAULT_DIRECTORY)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
            require(directories.isNotEmpty()) { "--log names no directory to read a corpus from" }

            val stride = flags.int("stride", TrainCommand.DEFAULT_STRIDE)
            require(stride > 0) { "--stride must be positive, was $stride" }

            val positions = flags.int("positions", TrainCommand.DEFAULT_POSITIONS)
            require(positions > 0) { "--positions must be positive, was $positions" }

            val hidden = flags.int("hidden", TrainCommand.DEFAULT_HIDDEN)
            require(hidden >= 0) { "--hidden must not be negative, was $hidden" }

            val epochs = flags.int("epochs", TrainCommand.DEFAULT_EPOCHS)
            require(epochs > 0) { "--epochs must be positive, was $epochs" }

            val batch = flags.int("batch", TrainCommand.DEFAULT_BATCH)
            require(batch > 0) { "--batch must be positive, was $batch" }

            val rate = flags.decimal("rate", TrainCommand.DEFAULT_RATE)
            require(rate > 0.0) { "--rate must be positive, was $rate" }

            val decay = flags.decimal("decay", TrainCommand.DEFAULT_DECAY)
            require(decay >= 0.0) { "--decay must not be negative, was $decay" }

            return TrainCommand(
                logDirectories = directories,
                rows = flags.text("rows")?.toIntOrNull(),
                cols = flags.text("cols")?.toIntOrNull(),
                stride = stride,
                positions = positions,
                hiddenUnits = hidden,
                epochs = epochs,
                learningRate = rate,
                decay = decay,
                batch = batch,
                seed = flags.long("seed", DEFAULT_SEED),
                out = flags.text("out")?.let { Path.of(it) },
                model = flags.text("model")?.let { Path.of(it) },
            )
        }

        /**
         * The one subcommand whose boards are not the caller's to choose.
         *
         * `--against` takes a full entrant spec so the reference can be retuned without a code
         * change, and its allowance is filled in from [LadderCommand.REFERENCE_BUDGET] when the spec
         * leaves it out — because a reference that took each level's own figure would grow with the
         * ladder and the column would stop being about the ladder.
         */
        private fun ladderOf(entrants: List<String>, flags: Flags, registry: BotRegistry): LadderCommand {
            require(entrants.isEmpty()) {
                "ladder plays the levels rather than a field, so it takes no entrants: " +
                    "'${entrants.first()}'. The reference is `--against`.\n\n$USAGE"
            }

            val rounds = flags.int("rounds", TournamentConfig.DEFAULT_ROUNDS)
            require(rounds > 0) { "--rounds must be positive, was $rounds" }
            require(rounds % 2 == 0) {
                "--rounds must be even so each seed is played from both seats, was $rounds"
            }

            val threads = flags.int("threads", Arena.defaultThreads())
            require(threads > 0) { "--threads must be positive, was $threads" }

            val named = contestantOf(flags.text("against") ?: LadderCommand.DEFAULT_REFERENCE, registry)

            return LadderCommand(
                reference = Contestant(
                    bot = named.bot,
                    budgetPerTurn = named.budgetPerTurn ?: LadderCommand.REFERENCE_BUDGET,
                    params = named.params,
                ),
                rounds = rounds,
                seed = flags.long("seed", DEFAULT_SEED),
                openings = flags.openings("openings"),
                threads = threads,
                logDirectory = flags.logDirectory("log"),
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

        private fun phasesOf(entrants: List<String>, flags: Flags): PhasesCommand {
            require(entrants.size == 1) {
                "phases splits one entrant's matches, was ${entrants.size}.\n\n$USAGE"
            }

            val directory = flags.logDirectory("log")
                ?: error("phases replays the match log, so `--log none` leaves it nothing to read")

            return PhasesCommand(
                subject = entrants.single(),
                against = flags.text("against"),
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

            val rows = flags.int("rows", LADDER_BOARD)
            val cols = flags.int("cols", LADDER_BOARD)
            val seed = flags.long("seed", DEFAULT_SEED)

            return AbCommand(
                baseline = baseline,
                candidate = candidate,
                rows = rows,
                cols = cols,
                seed = seed,
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                walls = flags.walls(rows, cols, seed),
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

            val rows = flags.int("rows", LADDER_BOARD)
            val cols = flags.int("cols", LADDER_BOARD)
            val seed = flags.long("seed", DEFAULT_SEED)

            return TimeCommand(
                subject = contestantOf(entrants.single(), registry),
                rows = rows,
                cols = cols,
                seed = seed,
                budgetPerTurn = flags.int("budget", MatchSetup.DEFAULT_BUDGET_PER_TURN),
                walls = flags.walls(rows, cols, seed),
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
