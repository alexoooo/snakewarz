package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderTest {
    @Test
    fun `a ladder read back off a batch is the batch's own matrix`() {
        // The rating layer must not quietly re-score anything: the matrix a batch printed and the
        // matrix a ladder rebuilds from the same matches have to be the same numbers, or every
        // rating downstream is about a game nobody played.
        val batch = batchOf(listOf("space", "wallhug", "pressure"), rounds = 6)
        val ladder = ladderOf(batch.matches)

        for (one in 0 until ladder.size) {
            for (other in 0 until ladder.size) {
                val batchOne = batch.result.config.contestants.indexOfFirst { it.bot.slug == slugOf(ladder, one) }
                val batchOther = batch.result.config.contestants.indexOfFirst { it.bot.slug == slugOf(ladder, other) }
                assertEquals(
                    batch.result.table.wins(batchOne, batchOther),
                    ladder.table.wins(one, other),
                    "${slugOf(ladder, one)} over ${slugOf(ladder, other)}",
                )
            }
        }
    }

    @Test
    fun `the strong end of the shipped ladder rates above the weak end`() {
        // The rungs, not the whole order: `wallhug` against `random` is a handful of wins either way
        // over a batch this size, and a test that pinned it would be pinning the sample.
        val ladder = ladderOf(batchOf(listOf("random", "wallhug", "space", "pressure"), rounds = 10).matches)

        val order = ladder.ranking()
        assertEquals("pressure", order.first(), order.toString())
        assertTrue(order.indexOf("space") < order.indexOf("wallhug"), order.toString())
        assertTrue(order.indexOf("space") < order.indexOf("random"), order.toString())
    }

    @Test
    fun `an entrant is labelled by what makes it different, not by every knob it has`() {
        // The log writes every knob out in full so a moved default cannot rewrite history. A ladder
        // has to undo that or its column headings are unreadable.
        val stock = entrantOf("uct:budget=1000,exploration=5.0,maxNodes=65536,rolloutDepth=0", ShippedBots)
        val tuned = entrantOf("uct:budget=1000,exploration=2.5,maxNodes=65536,rolloutDepth=0", ShippedBots)

        assertEquals("uct@1k", stock.label)
        assertEquals("uct@1k/exploration=2.5", tuned.label)
    }

    @Test
    fun `a bot nobody registers any more keeps its knobs rather than losing them`() {
        val gone = entrantOf("wasabi:budget=500,heat=9", ShippedBots)

        assertEquals(BotId("wasabi"), gone.bot)
        assertEquals(500, gone.budgetPerTurn)
        assertContains(gone.summary, "heat=9")
    }

    @Test
    fun `error bars are the same every time the same log is read`() {
        val matches = batchOf(listOf("space", "wallhug"), rounds = 10).matches
        val ladder = ladderOf(matches)

        val once = bootstrapIntervals(ladder, ShippedBots, TournamentFormat.HEAD_TO_HEAD, draws = 60)
        val again = bootstrapIntervals(ladder, ShippedBots, TournamentFormat.HEAD_TO_HEAD, draws = 60)

        for (entrant in 0 until ladder.size) {
            assertEquals(once[entrant].low, again[entrant].low, 0.0)
            assertEquals(once[entrant].high, again[entrant].high, 0.0)
        }
    }

    @Test
    fun `an error bar brackets the rating it belongs to`() {
        val ladder = ladderOf(batchOf(listOf("space", "wallhug", "pressure"), rounds = 8).matches)

        val intervals = bootstrapIntervals(ladder, ShippedBots, TournamentFormat.HEAD_TO_HEAD, draws = 80)

        for (entrant in 0 until ladder.size) {
            val rating = ladder.ratings.rating(entrant)
            assertTrue(intervals[entrant].low <= rating, "${ladder.label(entrant)}: $rating below its own bar")
            assertTrue(intervals[entrant].high >= rating, "${ladder.label(entrant)}: $rating above its own bar")
            assertTrue(intervals[entrant].width > 0.0, "${ladder.label(entrant)} claims certainty")
        }
    }

    @Test
    fun `one board is not enough evidence to draw a bar from`() {
        // A single seed group resampled produces itself every time, and an interval of zero width
        // would read as certainty about one game.
        val ladder = ladderOf(batchOf(listOf("space", "wallhug"), rounds = 2).matches)

        val intervals = bootstrapIntervals(ladder, ShippedBots, TournamentFormat.HEAD_TO_HEAD, draws = 20)

        assertTrue(intervals.all { it.low.isNaN() }, "one pair cannot be resampled into a range")
    }

    @Test
    fun `a residual says where the single ordering does not hold`() {
        val ladder = ladderOf(batchOf(listOf("space", "wallhug", "pressure"), rounds = 8).matches)

        for (one in 0 until ladder.size) {
            assertNull(ladder.residual(one, one), "nobody plays themselves")
            for (other in 0 until ladder.size) {
                if (one != other) {
                    val residual = assertNotNull(ladder.residual(one, other))
                    assertEquals(-residual, ladder.residual(other, one)!!, 1e-9, "a residual is symmetric")
                }
            }
        }
    }

    @Test
    fun `a bot that thinks costs more a turn than one that does not`() {
        // Solved out of the batch rather than read off it: a match's clock covers both seats, so a
        // reactive bot charged the whole of it would report its opponent's thinking. Three entrants,
        // because two seats playing the same number of turns cannot be told apart at all.
        //
        // A warm-up batch first, and only the *ordering* asserted. This is a wall clock on a JVM
        // that has not finished compiling itself, and a batch small enough for a test cannot
        // amortise that away -- which is the honest reason `rate` calls this column a ratio and
        // `time` exists as a separate measurement.
        val entrants = listOf("space", "wallhug", "uct")
        batchOf(entrants, rounds = 4, budget = 400)
        val ladder = ladderOf(batchOf(entrants, rounds = 4, budget = 400).matches)

        val searching = assertNotNull(ladder.microsPerTurn(ladder.specs.indexOfFirst { it.startsWith("uct") }))
        val reactive = assertNotNull(ladder.microsPerTurn(ladder.specs.indexOfFirst { it.startsWith("space") }))

        assertTrue(searching > reactive, "uct cost $searching us/turn against space's $reactive")
        assertTrue(reactive >= 0.0, "a cost cannot be negative")
    }

    @Test
    fun `a cost the batch cannot separate is not reported`() {
        // One pairing means both seats play the same number of turns in every match, and the clock
        // can be split between them any number of ways. Least squares would still pick one.
        val ladder = ladderOf(batchOf(listOf("space", "uct"), rounds = 4, budget = 400).matches)

        assertEquals(1, ladder.opponents(0))
        assertNull(ladder.microsPerTurn(0))
        assertNull(ladder.microsPerTurn(1))
    }

    private fun Ladder.ranking(): List<String> = ratings.ranking().map { specs[it].substringBefore(':') }

    private fun slugOf(ladder: Ladder, entrant: Int): String = ladder.specs[entrant].substringBefore(':')

    private fun ladderOf(matches: List<LoggedMatch>): Ladder =
        Ladder.of(matches, ShippedBots, TournamentFormat.HEAD_TO_HEAD)

    private class Batch(val result: ao.snakewarz.lab.arena.BatchResult, val matches: List<LoggedMatch>)

    private fun batchOf(slugs: List<String>, rounds: Int, budget: Int = 0): Batch {
        val config = TournamentConfig(
            contestants = slugs.map { Contestant(BotId(it)) },
            rows = 9,
            cols = 9,
            rounds = rounds,
            budgetPerTurn = budget,
        )
        val result = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2).run()
        return Batch(result, result.reports.map { LoggedMatch.of("test", config, ShippedBots, it) })
    }
}
