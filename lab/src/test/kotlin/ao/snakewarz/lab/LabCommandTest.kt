package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.gauntlet.GauntletCandidate
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.policytrain.PolicyTrainCommand
import ao.snakewarz.lab.tune.SpsaJournal
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.match.tournament.TournamentSchedule
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser, which is the only logic in `:lab` worth pinning — everything below it is `:match` and
 * `:bots`, already gated by their own suites.
 *
 * It is worth pinning because it is the thing standing between a typo and a wasted batch. A run of
 * forty rounds of search bots is minutes, and a knob name that quietly fell back on its default would
 * produce a matrix that looks exactly like a real one.
 */
class LabCommandTest {
    @Test
    fun `policy train requires all three explicit dataset roles`() {
        val dataset = "board|.lab/source|alphabeta:budget=1000,eval=territory|10"
        val command = LabCommand.of(
            listOf(
                "policy-train",
                "--train",
                dataset,
                "--validation",
                dataset,
                "--holdout",
                "held|.lab/held|puct:budget=1000,eval=territory|10",
                "--threads",
                "4",
            ),
            ShippedBots,
        )

        assertIs<PolicyTrainCommand>(command)
        assertFailsWith<IllegalStateException> {
            LabCommand.of(listOf("policy-train", "--train", dataset, "--validation", dataset), ShippedBots)
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "policy-train",
                    "--train",
                    dataset,
                    "--validation",
                    dataset,
                    "--holdout",
                    "held|.lab/held|puct:budget=1000,eval=territory|10",
                    "--threads",
                    "0",
                ),
                ShippedBots,
            )
        }
    }

    @Test
    fun `an entrant is a bot, an allowance and its knobs`() {
        val contestant = LabCommand.contestantOf("uct:exploration=2.5,budget=4000", ShippedBots)

        assertEquals(BotId("uct"), contestant.bot)
        assertEquals(4000, contestant.budgetPerTurn)
        assertEquals("2.5", contestant.params.string("exploration", ""))
    }

    @Test
    fun `a bare slug takes whatever the batch grants, rather than pre-filling the default`() {
        val contestant = LabCommand.contestantOf("uct", ShippedBots)

        // Null rather than DEFAULT_BUDGET_PER_TURN: a contestant that filled it in would override
        // --budget every time and leave that option with no way to take effect.
        assertNull(contestant.budgetPerTurn)
        assertTrue(contestant.params.isEmpty)
        assertEquals("uct", contestant.label)
    }

    @Test
    fun `a bot nobody has heard of names the ones it has`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.contestantOf("nosuchbot", ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "nosuchbot")
        assertContains(failure.message.orEmpty(), "uct")
    }

    @Test
    fun `a knob nobody declared is refused rather than quietly measured at its default`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.contestantOf("uct:explortaion=2.5", ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "explortaion")
        assertContains(failure.message.orEmpty(), "exploration")
    }

    @Test
    fun `a knob value outside its declared range is refused, and the complaint is the knob's own`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.contestantOf("uct:exploration=500", ShippedBots)
        }

        // BotKnob.Decimal.reject phrases this, not the parser -- one wording, wherever it is read.
        assertContains(failure.message.orEmpty(), "0.1 to 100.0")
    }

    @Test
    fun `play builds the tournament the flags describe`() {
        val command = LabCommand.of(
            "play uct space --rows 9 --cols 11 --rounds 6 --seed 7 --budget 1234 --format ffa".split(' '),
            ShippedBots,
        )

        val config = (command as PlayCommand).config
        assertEquals(listOf(BotId("uct"), BotId("space")), config.contestants.map { it.bot })
        assertEquals(9, config.rows)
        assertEquals(11, config.cols)
        assertEquals(6, config.rounds)
        assertEquals(7L, config.seed)
        assertEquals(1234, config.budgetPerTurn)
        assertEquals(TournamentFormat.FREE_FOR_ALL, config.format)
    }

    @Test
    fun `play defaults to the ladder's board and the match's own allowance`() {
        val config = (LabCommand.of(listOf("play", "uct", "space"), ShippedBots) as PlayCommand).config

        assertEquals(LabCommand.LADDER_BOARD, config.rows)
        assertEquals(LabCommand.LADDER_BOARD, config.cols)
        assertEquals(MatchSetup.DEFAULT_BUDGET_PER_TURN, config.budgetPerTurn)
        assertEquals(TournamentFormat.HEAD_TO_HEAD, config.format)
    }

    @Test
    fun `one bot at two allowances is two entrants, which is the point of the whole thing`() {
        val config = (
            LabCommand.of(listOf("play", "uct", "uct:budget=4000"), ShippedBots) as PlayCommand
            ).config

        assertEquals(2, config.contestants.size)
        assertEquals(listOf("uct", "uct@4k"), config.contestants.map { it.label })
    }

    @Test
    fun `two identically configured entrants are refused, because that measures the seating`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("play", "uct:exploration=2.0", "uct:exploration=2.0"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "same entrant")
    }

    @Test
    fun `an odd round count is refused where the complaint can name the option`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("play", "uct", "space", "--rounds", "21"), ShippedBots)
        }

        // TournamentConfig would refuse this too, but its message cannot say "--rounds".
        assertContains(failure.message.orEmpty(), "--rounds")
    }

    @Test
    fun `an option nobody offers is refused rather than ignored`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("play", "uct", "space", "--round", "20"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "--round")
    }

    @Test
    fun `time takes exactly one entrant and play takes at least two`() {
        assertFailsWith<IllegalArgumentException> { LabCommand.of(listOf("time", "uct", "space"), ShippedBots) }
        assertFailsWith<IllegalArgumentException> { LabCommand.of(listOf("play", "uct"), ShippedBots) }
    }

    @Test
    fun `time carries the subject and its passes`() {
        val command = LabCommand.of(
            "time uct:budget=500 --rows 8 --cols 8 --passes 2".split(' '),
            ShippedBots,
        ) as TimeCommand

        assertEquals(BotId("uct"), command.subject.bot)
        assertEquals(500, command.subject.budgetPerTurn)
        assertEquals(8, command.rows)
        assertEquals(2, command.passes)
    }

    @Test
    fun `an option one subcommand takes is not thereby taken by another`() {
        // A single flat namespace would accept `--passes` on a batch and silently do nothing with
        // it, which is the same failure the strict knob parsing exists to prevent, one level up.
        LabCommand.of(listOf("time", "uct", "--passes", "2"), ShippedBots)

        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("play", "uct", "space", "--passes", "2"), ShippedBots)
        }
        assertContains(failure.message.orEmpty(), "--passes")
    }

    @Test
    fun `play carries the openings and the thread count`() {
        val command = LabCommand.of(
            "play uct space --openings fixed --threads 3 --replays none".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertEquals(Openings.FIXED, command.openings)
        assertEquals(3, command.threads)
        assertEquals(Replays.NONE, command.replays)
    }

    @Test
    fun `play defaults to mirrored openings, because fixed ones repeat the same few games`() {
        val command = LabCommand.of(listOf("play", "uct", "space"), ShippedBots) as PlayCommand

        assertEquals(Openings.MIRRORED, command.openings)
    }

    @Test
    fun `complete derives its rounds from replications`() {
        val once = LabCommand.of(
            "play space wallhug --rows 8 --cols 8 --openings complete --log none".split(' '),
            ShippedBots,
        ) as PlayCommand
        val twice = LabCommand.of(
            "play space wallhug --rows 8 --cols 8 --openings complete --replications 2 --log none".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertEquals(Openings.COMPLETE_ROUNDS_PER_REPLICATION, once.config.rounds)
        assertEquals(Openings.COMPLETE_ROUNDS_PER_REPLICATION * 2, twice.config.rounds)
    }

    @Test
    fun `complete rejects partial or different populations`() {
        val rounds = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                "play space wallhug --rows 8 --cols 8 --openings complete --rounds 80".split(' '),
                ShippedBots,
            )
        }
        assertContains(rounds.message.orEmpty(), "--rounds")

        val board = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("play space wallhug --openings complete".split(' '), ShippedBots)
        }
        assertContains(board.message.orEmpty(), "empty 8x8")

        val map = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                "play space wallhug --rows 8 --cols 8 --openings complete --map scatter".split(' '),
                ShippedBots,
            )
        }
        assertContains(map.message.orEmpty(), "empty 8x8")

        val format = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                "play space wallhug pressure --rows 8 --cols 8 --openings complete --format ffa".split(' '),
                ShippedBots,
            )
        }
        assertContains(format.message.orEmpty(), "--format head")

        val replications = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("play space wallhug --replications 2".split(' '), ShippedBots)
        }
        assertContains(replications.message.orEmpty(), "--openings complete")

        val zero = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                "play space wallhug --rows 8 --cols 8 --openings complete --replications 0".split(' '),
                ShippedBots,
            )
        }
        assertContains(zero.message.orEmpty(), "--replications")
    }

    @Test
    fun `complete prints coverage before distinct games`() {
        val command = LabCommand.of(
            "play space wallhug --rows 8 --cols 8 --openings complete --threads 2 --log none".split(' '),
            ShippedBots,
        )
        val lines = mutableListOf<String>()

        command.run(ShippedBots, lines::add)

        val coverage = lines.indexOfFirst { it.contains("40 of 40 complete openings covered") }
        val diversity = lines.indexOfFirst { it.contains("distinct games") }
        val matrix = lines.indexOfFirst { it.lineSequence().firstOrNull()?.trimEnd()?.endsWith("score") == true }
        assertTrue(coverage >= 0, lines.toString())
        assertTrue(diversity > coverage, lines.toString())
        assertTrue(matrix > diversity, lines.toString())
        assertTrue(lines.none { it.contains("FORFEITS") }, lines.toString())
    }

    @Test
    fun `a map is drawn at the run's own geometry and reaches the schedule`() {
        val command = LabCommand.of(
            "play uct space --rows 12 --cols 12 --map arena --seed 4".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertEquals(generateMap(12, 12, MapShape.ARENA).walls().toList(), command.config.walls().toList())
        assertEquals(command.config.walls().toList(), TournamentSchedule(command.config).setupFor(0).walls().toList())
    }

    @Test
    fun `a map seed can be pinned independently from the tournament seed`() {
        val command = LabCommand.of(
            "play uct space --rows 12 --cols 12 --map scatter --map-seed 0 --seed 82004".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertEquals(
            generateMap(12, 12, MapShape.SCATTER, seed = 0L).walls().toList(),
            command.config.walls().toList(),
        )
        assertEquals(82004L, command.config.seed)
    }

    @Test
    fun `a map seed without a map is refused rather than silently pinning empty`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("play uct space --map-seed 0".split(' '), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "--map-seed")
    }

    @Test
    fun `no map at all is the same run as the empty map, byte for byte`() {
        // What lets every command written before maps existed keep its meaning, and every batch
        // already in the log stay comparable with a new one that spells the default out.
        val bare = LabCommand.of("play uct space --rows 12 --cols 12".split(' '), ShippedBots) as PlayCommand
        val named = LabCommand.of(
            "play uct space --rows 12 --cols 12 --map empty".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertEquals(0, bare.config.wallCount)
        assertEquals(bare.config.walls().toList(), named.config.walls().toList())
        assertEquals(
            TournamentSchedule(bare.config).setupFor(0),
            TournamentSchedule(named.config).setupFor(0),
        )
    }

    @Test
    fun `a map nobody has heard of names the ones there are`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.of("play uct space --map crross".split(' '), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "crross")
        assertContains(failure.message.orEmpty(), "rooms")
    }

    @Test
    fun `a board too small for a shape refuses by name rather than drawing half of one`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("play uct space --map rooms --rows 12 --cols 12".split(' '), ShippedBots)
        }

        // A `main` catches this, so what a person sees is the sentence rather than a stack trace.
        assertContains(failure.message.orEmpty(), "rooms")
        assertContains(failure.message.orEmpty(), "14")
    }

    @Test
    fun `a density with no map behind it is refused rather than silently placing nothing`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("play uct space --density 0.2".split(' '), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "--density")

        val scattered = LabCommand.of(
            "play uct space --map scatter --density 0.2 --rows 12 --cols 12".split(' '),
            ShippedBots,
        ) as PlayCommand
        val shipped = LabCommand.of(
            "play uct space --map scatter --rows 12 --cols 12".split(' '),
            ShippedBots,
        ) as PlayCommand

        assertTrue(scattered.config.wallCount > shipped.config.wallCount, "a density has to reach the shape")
    }

    @Test
    fun `every subcommand that plays a board can be given a map to play it on`() {
        val time = LabCommand.of("time uct --map arena --rows 12 --cols 12".split(' '), ShippedBots) as TimeCommand
        val ab = LabCommand.of("ab uct space --map arena --rows 12 --cols 12".split(' '), ShippedBots) as AbCommand
        val tune = LabCommand.of(
            "tune puct --knobs cpuct --map arena --rows 12 --cols 12".split(' '),
            ShippedBots,
        ) as TuneCommand
        val spsa = LabCommand.of(
            "spsa puct --knobs cpuct --map arena --rows 12 --cols 12".split(' '),
            ShippedBots,
        ) as SpsaCommand

        val walls = generateMap(12, 12, MapShape.ARENA).walls().toList()
        assertEquals(walls, time.walls.toList())
        assertEquals(walls, ab.walls.toList())
        assertEquals(walls, tune.walls.toList())
        assertEquals(walls, spsa.walls.toList())
    }

    @Test
    fun `rate narrows the log by map, exactly as it narrows it by board`() {
        val command = LabCommand.of(listOf("rate", "--map", "arena"), ShippedBots) as RateCommand

        assertEquals(mapOf("map" to "arena"), command.filters)
    }

    @Test
    fun `rate reads the log rather than playing, so it takes no entrants`() {
        assertFailsWith<IllegalArgumentException> { LabCommand.of(listOf("rate", "uct"), ShippedBots) }
        assertFailsWith<IllegalStateException> { LabCommand.of(listOf("rate", "--log", "none"), ShippedBots) }

        val command = LabCommand.of(listOf("rate", "--board", "12x12"), ShippedBots) as RateCommand
        assertEquals(mapOf("board" to "12x12"), command.filters)
    }

    @Test
    fun `phases splits one entrant's logged matches and needs a log to read`() {
        assertFailsWith<IllegalArgumentException> { LabCommand.of(listOf("phases"), ShippedBots) }
        assertFailsWith<IllegalStateException> {
            LabCommand.of(listOf("phases", "puct", "--log", "none"), ShippedBots)
        }

        val command = LabCommand.of(
            "phases puct:eval=learned --against uct".split(' '),
            ShippedBots,
        ) as PhasesCommand

        assertEquals("puct:eval=learned", command.subject)
        assertEquals("uct", command.against)
    }

    @Test
    fun `ab compares two different entrants and carries its bounds`() {
        val command = LabCommand.of(
            "ab uct uct:exploration=2.5 --elo0 -2 --elo1 12".split(' '),
            ShippedBots,
        ) as AbCommand

        assertEquals(-2.0, command.sprt.elo0)
        assertEquals(12.0, command.sprt.elo1)

        // A sequential test against a copy of itself measures the seating and never settles.
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("ab", "uct", "uct"), ShippedBots)
        }
        assertContains(failure.message.orEmpty(), "same entrant")
    }

    @Test
    fun `tune searches the knobs it is given, and refuses ones nobody declared`() {
        val command = LabCommand.of("tune puct --knobs cpuct,trapPenalty".split(' '), ShippedBots) as TuneCommand

        assertEquals(BotId("puct"), command.subject)
        assertEquals(listOf("cpuct", "trapPenalty"), command.knobs.map { it.name })

        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.of(listOf("tune", "puct", "--knobs", "cpuct,wibble"), ShippedBots)
        }
        assertContains(failure.message.orEmpty(), "wibble")
    }

    @Test
    fun `spsa searches only the numeric knobs, and says which it left alone`() {
        val command = LabCommand.of("spsa puct".split(' '), ShippedBots) as SpsaCommand

        // `eval` is a choice and `solver` a flag: a perturbation has no direction to move a name in.
        assertTrue(command.knobs.none { it is BotKnob.Choice || it is BotKnob.Flag }, command.knobs.toString())
        assertContains(command.ignored, "eval")
        assertContains(command.ignored, "solver")
        assertContains(command.knobs.map { it.name }, "cpuct")
    }

    @Test
    fun `naming a knob with no gradient is refused before anything is played`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("spsa", "puct", "--knobs", "cpuct,eval"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "eval")
        assertContains(failure.message.orEmpty(), "tune")
    }

    @Test
    fun `spsa carries its schedule and its confirming bound`() {
        val command = LabCommand.of(
            "spsa puct --knobs cpuct --iterations 30 --boards 4 --spread 5 --stride 2 --elo1 12".split(' '),
            ShippedBots,
        ) as SpsaCommand

        assertEquals(30, command.iterations)
        assertEquals(4, command.boardsPerIteration)
        assertEquals(5.0, command.spread)
        assertEquals(2.0, command.stride)
        assertEquals(12.0, command.confirm.elo1)
    }

    @Test
    fun `a bot with nothing to tune says so rather than searching an empty space`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("tune", "space"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "declares no knobs")
    }

    @Test
    fun `gauntlet takes a reference and a round count, and no board of its own`() {
        val command = LabCommand.of("gauntlet --against uct:budget=100 --rounds 4".split(' '), ShippedBots)
            as GauntletCommand

        assertEquals(BotId("uct"), command.reference.bot)
        assertEquals(100, command.reference.budgetPerTurn)
        assertEquals(4, command.rounds)
        assertEquals(GauntletCommand.SHIPPED_TABLE, command.table)
        assertEquals(Gauntlet.levels.first().mapSeed, command.levels.first().mapSeed)
        assertTrue(command.seed != command.levels.first().mapSeed, "the match seed must not redraw the level")

        // Every board a gauntlet run plays comes off the level, so a `--rows` here would measure
        // seven levels on a board none of them is played on.
        val board = assertFailsWith<IllegalArgumentException> {
            LabCommand.of("gauntlet --rows 12".split(' '), ShippedBots)
        }
        assertContains(board.message.orEmpty(), "--rows")
    }

    @Test
    fun `gauntlet can select the pinned lab-only candidate table`() {
        val command = LabCommand.of(
            "gauntlet --table 2026-08-01b --against puct:budget=250 --rounds 4 --seed 81001".split(' '),
            ShippedBots,
        ) as GauntletCommand

        assertEquals(GauntletCandidate.TABLE, command.table)
        assertEquals(GauntletCandidate.levels, command.levels)
        assertTrue(command.levels.all { it.mapSeed == 0L })
        assertEquals(81_001L, command.seed)
        assertEquals(BotId("puct"), command.reference.bot)
        assertEquals(250, command.reference.budgetPerTurn)
    }

    @Test
    fun `gauntlet refuses an unknown table before buying matches`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.of("gauntlet --table future --rounds 4".split(' '), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "future")
        assertContains(failure.message.orEmpty(), GauntletCandidate.TABLE)
    }

    @Test
    fun `a gauntlet reference nobody has heard of names the ones there are`() {
        val failure = assertFailsWith<IllegalStateException> {
            LabCommand.of("gauntlet --against nosuchbot".split(' '), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "nosuchbot")
        assertContains(failure.message.orEmpty(), "uct")
    }

    @Test
    fun `a gauntlet reference left unset is pinned rather than taking each level's own allowance`() {
        val command = LabCommand.of(listOf("gauntlet"), ShippedBots) as GauntletCommand

        assertEquals(GauntletCommand.DEFAULT_REFERENCE, command.reference.bot.slug)
        assertEquals(GauntletCommand.REFERENCE_BUDGET, command.reference.budgetPerTurn)
        assertEquals(TournamentConfig.DEFAULT_ROUNDS, command.rounds)
        assertEquals(Openings.MIRRORED, command.openings)
    }

    @Test
    fun `gauntlet plays the levels rather than a field, so it takes no entrants`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("gauntlet", "uct"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "--against")
    }

    @Test
    fun `a subcommand nobody offers prints the usage`() {
        val failure = assertFailsWith<IllegalStateException> { LabCommand.of(listOf("race", "uct"), ShippedBots) }

        assertContains(failure.message.orEmpty(), "race")
        assertContains(failure.message.orEmpty(), "Usage:")
    }

    @Test
    fun `a null result says so when the two never played a different game`() {
        // A bot against a re-spelling of itself: `adjacencyFloor=0.05` is the declared default, so
        // these are the same bot under two specs and every board is bound to split. That is the
        // extreme of the case the note exists for -- a real one is a change that fires only in
        // positions an opponent playing the same way never puts it in, which reads identically here
        // and is invisible for the same reason. See `AbCommand.blindness`.
        val command = LabCommand.of(
            "ab pressure pressure:adjacencyFloor=0.05 --rows 8 --cols 8 --budget 0 --threads 1".split(' ') +
                listOf("--log", "none"),
            ShippedBots,
        )

        val lines = mutableListOf<String>()
        command.run(ShippedBots, lines::add)

        assertTrue(lines.any { it.contains("NO BETTER") }, lines.toString())
        assertTrue(lines.any { it.contains("boards split exactly") }, lines.toString())

        // The follow-up is meant to be pasted, so it has to parse. A label would not.
        val suggested = lines.single { it.contains("--rounds") }.substringAfter("play ").substringBefore(" <others")
        for (entrant in suggested.split(' ')) {
            LabCommand.contestantOf(entrant, ShippedBots)
        }
    }

    @Test
    fun `a sweep that finds nothing says whether it could have found anything`() {
        // `tune` is the same head-to-head test run dozens of times, so it inherits the blind spot
        // whole -- and this is the real case rather than a contrived one: sweeping `roomShare`
        // reports 0 Elo on every step and "leave the defaults alone", for a knob a field rates at
        // +14. Without the note that reads as a measurement of the knob.
        val journal = Files.createTempDirectory("snakewarz-lab").resolve("tune.tsv")
        val command = LabCommand.of(
            "tune chase --knobs roomShare --budget 0 --rows 8 --cols 8 --max-pairs 40".split(' ') +
                listOf("--passes", "1", "--threads", "1", "--journal", journal.toString()),
            ShippedBots,
        )

        val lines = mutableListOf<String>()
        command.run(ShippedBots, lines::add)

        assertTrue(lines.any { it.contains("nothing beat the shipped settings") }, lines.toString())
        assertTrue(lines.any { it.contains("boards split exactly") }, lines.toString())
    }

    @Test
    fun `a batch that already diversified its openings blames the entrants instead`() {
        // Advice for the wrong cause is worse than none: it sends somebody to re-run a flag they are
        // already passing. Mirrored openings cannot fix two entrants that play the same game.
        val command = LabCommand.of(
            "play pressure pressure:adjacencyFloor=0.05 --rows 8 --cols 8 --rounds 4 --budget 0".split(' ') +
                listOf("--log", "none"),
            ShippedBots,
        )

        val lines = mutableListOf<String>()
        command.run(ShippedBots, lines::add)

        val advice = lines.single { it.contains("repeated itself") }
        assertContains(advice, "the entrants rather than the schedule")
        assertTrue(!advice.contains("Try --openings mirrored"), advice)
    }

    @Test
    fun `a gradient search runs end to end, journals every iteration and demands a confirmation`() {
        // Zero allowance and a tiny board, so this is a check that the wiring holds rather than a
        // measurement -- SpsaTest is where the optimiser is verified, against a function with an
        // answer, because on the real objective a broken search and a flat knob look the same.
        val journal = Files.createTempDirectory("snakewarz-lab").resolve("spsa.tsv")
        val arguments = "spsa pressure --knobs adjacencyPenalty --iterations 4 --boards 2".split(' ') +
            "--rows 8 --cols 8 --budget 0 --threads 1 --max-pairs 40".split(' ') +
            listOf("--journal", journal.toString())

        val lines = mutableListOf<String>()
        LabCommand.of(arguments, ShippedBots).run(ShippedBots, lines::add)

        // Rule one of the shared measurement protocol: the honest sample size, always, first.
        assertTrue(lines.any { it.contains("distinct games") }, lines.toString())

        // Every iteration, plus the confirming row if the search moved anywhere at all.
        val rows = journal.readLines().drop(1)
        assertTrue(rows.size == 4 || rows.size == 5, rows.toString())
        assertEquals(listOf("0", "1", "2", "3"), rows.take(4).map { it.substringBefore('\t') })

        // And no iteration may carry a verdict: only the confirming run reaches one.
        for (row in rows.take(4)) {
            assertEquals(SpsaJournal.NONE, row.split('\t')[VERDICT])
        }

        val settled = lines.single { it.contains("nothing to confirm") || it.contains("CONFIRMED") }
        if (!settled.contains("nothing to confirm")) {
            assertTrue(lines.any { it.contains("record of attempts, not of findings") }, lines.toString())
            // The follow-up is meant to be pasted, so the entrant in it has to parse.
            val rerun = lines.single { it.contains("  ab ") }.substringAfter("  ab ").split(' ')
            LabCommand.contestantOf(rerun[0], ShippedBots)
            LabCommand.contestantOf(rerun[1], ShippedBots)
        }
    }

    @Test
    fun `a search takes a full entrant spec, and holds it still in both arms`() {
        // The gap this closes was silent by construction, which is why it is pinned end to end
        // rather than at the parser. `spsa puct:eval=chamber` used to fail on the slug outright, and
        // the settings it wrote named only the knobs it was searching -- so `eval` sat at its
        // default, where ChamberEval's three weights are not read at all, and a run would have
        // reported a perfectly well-formed answer about a bot it was not searching.
        val journal = Files.createTempDirectory("snakewarz-lab").resolve("spsa.tsv")
        val arguments = "spsa puct:eval=chamber --knobs parityWeight,frontierPenalty,sealPenalty".split(' ') +
            "--iterations 2 --boards 2 --rows 6 --cols 6 --budget 0 --threads 1 --max-pairs 40".split(' ') +
            listOf("--journal", journal.toString())

        val command = LabCommand.of(arguments, ShippedBots) as SpsaCommand
        assertEquals("chamber", command.fixed.string("eval", ""))
        assertEquals(listOf("parityWeight", "frontierPenalty", "sealPenalty"), command.knobs.map { it.name })

        // A pinned knob is not "left at its default", so it does not belong in that list either.
        assertTrue("eval" !in command.ignored, command.ignored.toString())

        command.run(ShippedBots) {}

        val rows = journal.readLines().drop(1).map { it.split('\t') }
        assertEquals(2, rows.size, rows.toString())
        for (row in rows) {
            assertContains(row[PLUS], "eval=chamber")
            assertContains(row[MINUS], "eval=chamber")
            // Held still is not the same as held identical: the weights are what moves.
            assertTrue(row[PLUS] != row[MINUS], row.toString())
        }
    }

    @Test
    fun `the spec is in the baseline a search confirms against, not only in its candidate`() {
        // The half that leaves no trace when it is wrong. A confirmation against the bare defaults
        // would measure the spec and the point together and credit the whole difference to the point.
        // `uct` at a small allowance rather than a reactive bot at none, because this needs a search
        // that actually moves: two arms of a bot that draws no randomness split every board, the
        // point never leaves its start, and there is no confirmation to inspect. Seeded throughout,
        // so which point it settles on is fixed.
        val journal = Files.createTempDirectory("snakewarz-lab").resolve("spsa.tsv")
        val arguments = "spsa uct:rolloutDepth=20 --knobs exploration".split(' ') +
            "--iterations 6 --boards 2 --rows 8 --cols 8 --budget 30 --threads 1 --max-pairs 40".split(' ') +
            listOf("--journal", journal.toString())

        val lines = mutableListOf<String>()
        LabCommand.of(arguments, ShippedBots).run(ShippedBots, lines::add)

        val confirmation = journal.readLines().drop(1).map { it.split('\t') }
            .single { it[0].toInt() == SpsaJournal.CONFIRMATION }

        // Both sides of the confirming test, and the `ab` printed for re-running it by hand.
        assertContains(confirmation[MINUS], "rolloutDepth=20")
        assertContains(confirmation[PLUS], "rolloutDepth=20")

        val rerun = lines.single { it.contains("  ab ") }.substringAfter("  ab ").split(' ')
        assertEquals("20", LabCommand.contestantOf(rerun[0], ShippedBots).params.string("rolloutDepth", ""))
        assertEquals("20", LabCommand.contestantOf(rerun[1], ShippedBots).params.string("rolloutDepth", ""))
    }

    @Test
    fun `a knob cannot be pinned by the spec and searched at the same time`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("spsa", "puct:cpuct=2.0", "--knobs", "cpuct"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "cpuct")

        // And an allowance is `--budget`, because every measurement a search makes is at the same one.
        val allowance = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("tune", "puct:budget=400", "--knobs", "cpuct"), ShippedBots)
        }
        assertContains(allowance.message.orEmpty(), "--budget")
    }

    @Test
    fun `a gradient search resumes from its journal instead of buying the same measurements twice`() {
        val journal = Files.createTempDirectory("snakewarz-lab").resolve("spsa.tsv")
        val arguments = "spsa pressure --knobs adjacencyPenalty --iterations 4 --boards 2".split(' ') +
            "--rows 8 --cols 8 --budget 0 --threads 1 --max-pairs 40".split(' ') +
            listOf("--journal", journal.toString())

        LabCommand.of(arguments, ShippedBots).run(ShippedBots) {}
        val lines = mutableListOf<String>()
        LabCommand.of(arguments, ShippedBots).run(ShippedBots, lines::add)

        assertTrue(lines.any { it.contains("resuming: 4 iterations") }, lines.toString())
        assertEquals(4, lines.count { it.contains("(replayed)") }, lines.toString())
        assertTrue(lines.any { it.contains("every iteration was replayed") }, lines.toString())
    }

    @Test
    fun `a small batch runs end to end and fills the matrix in`() {
        // Zero allowance and a tiny board, so this is a check that the wiring holds rather than a
        // measurement -- the measurements are what `:lab:run` is for, and they take minutes.
        val command = LabCommand.of(
            "play space wallhug --rows 7 --cols 7 --rounds 2 --budget 0".split(' '),
            ShippedBots,
        )

        val lines = mutableListOf<String>()
        command.run(ShippedBots, lines::add)

        assertTrue(lines.any { it.startsWith("[lab]") && it.contains("2 matches") }, lines.toString())
        assertTrue(lines.any { it.contains("wallhug") }, lines.toString())
    }

    private companion object {
        /** The `plus`, `minus` and `verdict` columns of a journal row — see `SpsaJournal`. */
        const val PLUS = 3
        const val MINUS = 4
        const val VERDICT = 8
    }
}
