package ao.snakewarz.ui.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The motion clock, driven by timestamps a test hands over.
 *
 * **That it can never play a turn is a fact about its type rather than an assertion here**: a
 * [Ticker] is built from one function taking a clock reading and answering a boolean, so there is no
 * seam through which a turn could be reached. What is worth pinning is the other half — that it
 * stops itself, and that its clock measures *motion somebody saw* rather than time that passed.
 *
 * The timestamps below are whole milliseconds and the sums are exact in binary, so a failure is a
 * real one and never a rounding artifact.
 */
class TickerTest {
    @Test
    fun `the first frame has no motion behind it`() {
        val run = Run()

        run.ticker.start()
        run.ticker.frame(1_000.0)

        assertEquals(1, run.paints, "it paints on the frame it starts on")
        assertEquals(0.0, run.clock, "and there is no predecessor to measure against yet")
        run.ticker.stop()
    }

    @Test
    fun `the frame the last effect ends on is the last frame`() {
        // The whole of "it stops itself": nothing else in the module knows when a death has finished
        // settling, so an effect saying so is the only thing that can end the loop.
        val run = Run()

        run.ticker.start()
        run.ticker.frame(0.0)
        run.ticker.frame(16.0)
        assertTrue(run.ticker.running, "something is still moving")

        run.live = false
        run.ticker.frame(32.0)
        assertFalse(run.ticker.running, "and then nothing is")

        val painted = run.paints
        run.ticker.frame(48.0)
        assertEquals(painted, run.paints, "a frame already in flight when it stopped paints nothing")
    }

    @Test
    fun `the clock measures motion, not time, so a stop and a start carry on from where it was`() {
        // A death is stamped with this clock while the loop is *stopped*, because a turn is what kills
        // a snake and the loop only starts afterwards. A clock that restarted at zero would put every
        // stamp taken before it in the future, and every corpse would settle instantly.
        val run = Run()

        run.ticker.start()
        run.ticker.frame(1_000.0)
        run.ticker.frame(1_100.0)
        assertEquals(100.0, run.clock)

        run.ticker.stop()
        run.ticker.start()
        // Ten seconds of a page nobody touched, and none of it is motion anybody watched.
        run.ticker.frame(11_000.0)
        assertEquals(100.0, run.clock, "the gap across a stop is not motion")

        run.ticker.frame(11_050.0)
        assertEquals(150.0, run.clock, "and the clock picks up where it left off")
        run.ticker.stop()
    }

    @Test
    fun `a timestamp that runs backwards buys nothing and owes nothing`() {
        // [TurnScheduler]'s clamp, for the same reason: an unclamped negative interval would wind an
        // effect back into its own past, and a corpse already settled would flash a second time.
        val run = Run()

        run.ticker.start()
        run.ticker.frame(1_000.0)
        run.ticker.frame(1_100.0)
        assertEquals(100.0, run.clock)

        run.ticker.frame(900.0)
        assertEquals(100.0, run.clock, "a backwards frame moves nothing")

        run.ticker.frame(1_000.0)
        assertEquals(200.0, run.clock, "and leaves no debt: a hundred on from the frame before it")
        run.ticker.stop()
    }

    @Test
    fun `a long stall is one frame of motion, not every effect finishing at once`() {
        // An alt-tab, a breakpoint, a hidden tab. Without the ceiling everything on the board is over
        // by the frame the page comes back, which is the one moment somebody is looking at it.
        val run = Run()

        run.ticker.start()
        run.ticker.frame(0.0)
        run.ticker.frame(10_000.0)

        assertEquals(250.0, run.clock, "a quarter second of credit, not ten seconds of it")
        run.ticker.stop()
    }

    @Test
    fun `a stopped ticker ignores a frame that was already in flight`() {
        val run = Run()

        run.ticker.start()
        run.ticker.stop()
        run.ticker.frame(1_000.0)

        assertEquals(0, run.paints)
    }

    /** A ticker wired to a counter and a switch the test throws when the effects are meant to end. */
    private class Run(var live: Boolean = true) {
        var paints = 0
        var clock = 0.0

        val ticker = Ticker { motionMillis ->
            paints++
            clock = motionMillis
            live
        }
    }
}
