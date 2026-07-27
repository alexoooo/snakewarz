package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.TournamentFormat
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

        val config = (command as LabCommand.Play).config
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
        val config = (LabCommand.of(listOf("play", "uct", "space"), ShippedBots) as LabCommand.Play).config

        assertEquals(LabCommand.LADDER_BOARD, config.rows)
        assertEquals(LabCommand.LADDER_BOARD, config.cols)
        assertEquals(MatchSetup.DEFAULT_BUDGET_PER_TURN, config.budgetPerTurn)
        assertEquals(TournamentFormat.HEAD_TO_HEAD, config.format)
    }

    @Test
    fun `one bot at two allowances is two entrants, which is the point of the whole thing`() {
        val config = (
            LabCommand.of(listOf("play", "uct", "uct:budget=4000"), ShippedBots) as LabCommand.Play
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
        ) as LabCommand.Time

        assertEquals(BotId("uct"), command.subject.bot)
        assertEquals(500, command.subject.budgetPerTurn)
        assertEquals(8, command.rows)
        assertEquals(2, command.passes)
    }

    @Test
    fun `a subcommand nobody offers prints the usage`() {
        val failure = assertFailsWith<IllegalStateException> { LabCommand.of(listOf("race", "uct"), ShippedBots) }

        assertContains(failure.message.orEmpty(), "race")
        assertContains(failure.message.orEmpty(), "Usage:")
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
