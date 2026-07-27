package ao.snakewarz.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scheduler's accumulator, driven by a clock a test controls.
 *
 * Every guard here changes how *fast* a match plays and never how it ends, which is what makes them
 * cheap to get wrong: a broken clamp does not fail an assertion anywhere else in the suite, it makes
 * the game freeze or race for somebody who is not writing tests.
 *
 * The timestamps are chosen so the arithmetic is exact in binary — 125 ms at 8 turns a second is
 * `0.125 * 8`, both powers of two — so a failure here is a real one and never a rounding artifact.
 */
class TurnSchedulerTest {
    @Test
    fun `the first frame has no interval to spend`() {
        val run = Run()

        run.scheduler.start()
        run.scheduler.frame(1_000.0)

        assertEquals(0, run.steps, "there is no predecessor to measure against yet")
        run.scheduler.stop()
    }

    @Test
    fun `a timestamp that runs backwards buys nothing and owes nothing`() {
        // The clamp its own comment calls "not decoration". An unclamped negative interval drives the
        // accumulator below zero and the match stops for however long it takes to climb back --
        // a freeze with no error and no obvious cause, which is why this is worth a test at all.
        val run = Run()

        run.scheduler.start()
        run.scheduler.frame(1_000.0)
        run.scheduler.frame(1_125.0)
        assertEquals(1, run.steps, "125 ms at 8 turns a second is one turn")

        run.scheduler.frame(925.0)
        assertEquals(1, run.steps, "a backwards frame must not consume a turn")

        run.scheduler.frame(1_050.0)
        assertEquals(2, run.steps, "and must not have left a debt to work off either")

        run.scheduler.stop()
    }

    @Test
    fun `a long stall is one frame of turns, not a backlog of them`() {
        // An alt-tab, a breakpoint, a hidden tab. Without the ceiling this is ten seconds of turns
        // fired at whoever comes back, which reads as the game having played itself.
        val run = Run()

        run.scheduler.start()
        run.scheduler.frame(0.0)
        run.scheduler.frame(10_000.0)

        assertEquals(2, run.steps, "250 ms of credit at 8 turns a second, not ten seconds of it")
        run.scheduler.stop()
    }

    @Test
    fun `waiting on a person does not consume a turn, and does not bank them either`() {
        // A player who thinks for five seconds must not have five seconds of turns fired at them on
        // the next keypress.
        val run = Run(TurnScheduler.Progress.AWAITING_INPUT)

        run.scheduler.start()
        run.scheduler.frame(0.0)
        run.scheduler.frame(250.0)
        assertEquals(1, run.steps, "it asked once and was told to wait")

        run.answer = TurnScheduler.Progress.CONTINUED
        run.scheduler.frame(375.0)

        // Two, not three: the two turns of credit standing when it parked were capped at one.
        assertEquals(3, run.steps, "one refused step, then two played")
        run.scheduler.stop()
    }

    @Test
    fun `a finished match stops the scheduler rather than being asked again`() {
        val run = Run(TurnScheduler.Progress.FINISHED)

        run.scheduler.start()
        assertTrue(run.scheduler.running)

        run.scheduler.frame(0.0)
        run.scheduler.frame(125.0)

        assertFalse(run.scheduler.running, "FINISHED parks it")
        assertEquals(1, run.steps)
        assertTrue(run.frames > 0, "and reports the frame it stopped on, so the transport can catch up")
    }

    @Test
    fun `a stopped scheduler ignores a frame that was already in flight`() {
        // stop() cancels the handle, but a callback the browser has already dispatched still arrives.
        val run = Run()

        run.scheduler.start()
        run.scheduler.stop()
        run.scheduler.frame(1_000.0)

        assertEquals(0, run.steps)
    }

    /** A scheduler wired to a counter, at a rate that divides exactly into the timestamps above. */
    private class Run(var answer: TurnScheduler.Progress = TurnScheduler.Progress.CONTINUED) {
        var steps = 0
        var frames = 0

        val scheduler = TurnScheduler(
            step = {
                steps++
                answer
            },
            onFrame = { frames++ },
        ).also { it.turnsPerSecond = 8.0 }
    }
}
