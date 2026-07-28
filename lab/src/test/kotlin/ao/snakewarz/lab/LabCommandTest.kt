package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.TournamentFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `rate reads the log rather than playing, so it takes no entrants`() {
        assertFailsWith<IllegalArgumentException> { LabCommand.of(listOf("rate", "uct"), ShippedBots) }
        assertFailsWith<IllegalStateException> { LabCommand.of(listOf("rate", "--log", "none"), ShippedBots) }

        val command = LabCommand.of(listOf("rate", "--board", "12x12"), ShippedBots) as RateCommand
        assertEquals(mapOf("board" to "12x12"), command.filters)
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
    fun `a bot with nothing to tune says so rather than searching an empty space`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LabCommand.of(listOf("tune", "space"), ShippedBots)
        }

        assertContains(failure.message.orEmpty(), "declares no knobs")
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
}
