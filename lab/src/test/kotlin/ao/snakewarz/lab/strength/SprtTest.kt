package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprtTest {
    @Test
    fun `a candidate that wins nearly every board is accepted`() {
        val report = sprt().test(List(200) { if (it % 10 == 0) 0.5 else 1.0 })

        assertEquals(Sprt.Verdict.BETTER, report.verdict)
        assertTrue(report.llr >= report.upper)
    }

    @Test
    fun `a candidate that loses nearly every board is rejected`() {
        val report = sprt().test(List(200) { if (it % 10 == 0) 0.5 else 0.0 })

        assertEquals(Sprt.Verdict.NO_BETTER, report.verdict)
        assertTrue(report.llr <= report.lower)
    }

    @Test
    fun `an even sample keeps playing rather than calling it either way`() {
        val report = sprt().test(List(200) { if (it % 2 == 0) 1.0 else 0.0 })

        assertEquals(Sprt.Verdict.UNDECIDED, report.verdict)
        assertTrue(report.llr > report.lower && report.llr < report.upper)
        assertEquals(0.0, report.elo!!, 1e-9)
    }

    @Test
    fun `no verdict is allowed before the minimum, however clear it looks`() {
        // The trap a sequential test is most exposed to: the variance is estimated from the same
        // sample that decides, so a lucky first handful overstates the evidence twice over.
        val clear = List(Sprt.MINIMUM_PAIRS - 1) { 1.0 }

        assertEquals(Sprt.Verdict.UNDECIDED, sprt().test(clear).verdict)
        assertEquals(Sprt.Verdict.BETTER, sprt().test(clear + List(2) { 1.0 }).verdict)
    }

    @Test
    fun `a sample with no spread is conclusive rather than a division by zero`() {
        val report = sprt().test(List(Sprt.MINIMUM_PAIRS) { 1.0 })

        assertTrue(report.llr.isFinite(), "was ${report.llr}")
        assertEquals(Sprt.Verdict.BETTER, report.verdict)
        assertNull(report.elo, "winning every board puts no upper bound on the difference")
        assertNull(report.eloMargin)
    }

    @Test
    fun `nothing played is undecided rather than anything else`() {
        val report = sprt().test(emptyList())

        assertEquals(Sprt.Verdict.UNDECIDED, report.verdict)
        assertEquals(0, report.pairs)
        assertEquals(0.0, report.elo!!, 1e-9)
    }

    @Test
    fun `an Elo difference and an expected score are two ways of saying one thing`() {
        assertEquals(0.5, Sprt.scoreOf(0.0), 1e-12)
        assertEquals(0.9090909, Sprt.scoreOf(400.0), 1e-6)

        for (elo in listOf(-500.0, -100.0, -5.0, 0.0, 5.0, 100.0, 500.0)) {
            assertEquals(elo, Sprt.eloOf(Sprt.scoreOf(elo))!!, 1e-9, "$elo Elo")
        }
        assertNull(Sprt.eloOf(0.0), "losing everything has no lower bound")
        assertNull(Sprt.eloOf(1.0))
    }

    @Test
    fun `the stopping bounds are the ones the error rates ask for`() {
        val test = Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.05, beta = 0.05)

        // ln(0.05 / 0.95) and ln(0.95 / 0.05) -- symmetric because the two error rates are equal.
        assertEquals(-2.944, test.lower, 1e-3)
        assertEquals(2.944, test.upper, 1e-3)
    }

    @Test
    fun `bounds that leave no room to be undecided are refused`() {
        assertFailsWith<IllegalArgumentException> { Sprt(elo0 = 5.0, elo1 = 5.0, alpha = 0.05, beta = 0.05) }
        assertFailsWith<IllegalArgumentException> { Sprt(elo0 = 5.0, elo1 = 0.0, alpha = 0.05, beta = 0.05) }
        assertFailsWith<IllegalArgumentException> { Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.0, beta = 0.05) }
        assertFailsWith<IllegalArgumentException> { Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.05, beta = 1.0) }
    }

    @Test
    fun `the same bot on both sides splits every board exactly`() {
        // Which is a statement about the *opening*, not the test: a board and its mirror are only
        // one observation if the two sides of it are genuinely the same position. Two identical bots
        // scoring exactly half on every board is what proves the reflection is fair.
        val config = TournamentConfig(
            contestants = listOf(
                Contestant(BotId("wallhug")),
                Contestant(BotId("wallhug"), budgetPerTurn = 0),
            ),
            rows = 12,
            cols = 12,
            rounds = 12,
            budgetPerTurn = 0,
        )
        val batch = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2).run()

        val scores = pairScores(batch, 1)
        assertEquals(6, scores.size, "twelve matches is six boards")
        for (score in scores) {
            assertEquals(0.5, score, 1e-12, "a mirrored board favoured one side")
        }
    }

    @Test
    fun `a board is one observation however many matches it took`() {
        val config = TournamentConfig(
            contestants = listOf(Contestant(BotId("random")), Contestant(BotId("space"))),
            rows = 9,
            cols = 9,
            rounds = 8,
            budgetPerTurn = 0,
        )
        val batch = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2).run()

        val scores = pairScores(batch, 1)

        assertEquals(4, scores.size, "eight matches, played from both seats, is four boards")
        assertTrue(scores.all { it in 0.0..1.0 }, scores.toString())
        assertTrue(scores.all { it * 4.0 == (it * 4.0).toInt().toDouble() }, "a board scores in quarters: $scores")

        val mine = assertNotNull(Sprt.eloOf(scores.average().coerceIn(0.001, 0.999)))
        assertTrue(mine > 0.0, "the space filler should be ahead of random: $scores")
    }

    private fun sprt() = Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.05, beta = 0.05)
}
