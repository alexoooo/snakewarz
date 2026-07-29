package ao.snakewarz.lab.log

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Naming a logged entrant, which is the one place a person types something the log wrote.
 *
 * The specs below are the real shape: `expandedSpec` writes every declared knob at the value it
 * played under, in the order the bot declares them, so the string is long, positional, and nothing
 * anybody would retype.
 */
class ResolveSpecTest {
    @Test
    fun `a knob subset names an entrant, wherever that knob sits in the declaration`() {
        // The case that made the research agenda's own documented `report` command fail: `eval` is
        // not the first knob, so no prefix of the expanded spec can name it on its own.
        assertEquals(HORIZON, resolveSpec("puct:eval=horizon", SPECS))
        assertEquals(SURVIVAL, resolveSpec("puct:eval=survival", SPECS))
    }

    @Test
    fun `a subset takes its knobs in any order, and as many as it needs`() {
        assertEquals(HORIZON, resolveSpec("puct:eval=horizon,budget=1000", SPECS))
        assertEquals(HORIZON, resolveSpec("puct:budget=1000,eval=horizon", SPECS))
        assertEquals(TERRITORY_400, resolveSpec("puct:budget=400", SPECS))
    }

    @Test
    fun `a value compares as a number where it is one`() {
        // The log writes a knob back through its own reader, so a weight somebody set as `1` is
        // written `1.0`. A name that had to reproduce the reader's spelling is one nobody can guess.
        assertEquals(HORIZON, resolveSpec("puct:eval=horizon,cpuct=1.50", SPECS))
        assertEquals(TERRITORY_400, resolveSpec("puct:budget=400.0", SPECS))
    }

    @Test
    fun `an exact spec pasted back from the log resolves to itself`() {
        assertEquals(HORIZON, resolveSpec(HORIZON, SPECS))
    }

    @Test
    fun `a bare slug resolves when there is one of it, and lists the candidates when there is not`() {
        assertEquals(UCT, resolveSpec("uct", SPECS))

        val failure = assertFailsWith<IllegalStateException> { resolveSpec("puct", SPECS) }
        for (candidate in listOf(TERRITORY, TERRITORY_400, SURVIVAL, HORIZON)) {
            assertContains(failure.message.orEmpty(), candidate)
        }
    }

    @Test
    fun `a knob value nothing played says what was played instead`() {
        val failure = assertFailsWith<IllegalArgumentException> { resolveSpec("puct:eval=mobility", SPECS) }

        assertContains(failure.message.orEmpty(), "territory")
        assertContains(failure.message.orEmpty(), "horizon")
    }

    @Test
    fun `a knob nobody declared, and a bot nobody played, are told apart`() {
        assertContains(
            assertFailsWith<IllegalArgumentException> { resolveSpec("puct:wibble=1", SPECS) }.message.orEmpty(),
            "declares no 'wibble'",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { resolveSpec("chase", SPECS) }.message.orEmpty(),
            "nothing in the log is called 'chase'",
        )
    }

    @Test
    fun `a name that is not name equals value is refused rather than guessed at`() {
        // The old prefix match accepted a string cut anywhere, so `budget=10` silently selected
        // `budget=1000`. Half a setting is not a narrower question, it is a different one.
        assertFailsWith<IllegalArgumentException> { resolveSpec("puct:budget", SPECS) }
    }

    private companion object {
        const val TERRITORY = "puct:budget=1000,cpuct=1.5,territoryWeight=1.0,eval=territory"
        const val TERRITORY_400 = "puct:budget=400,cpuct=1.5,territoryWeight=1.0,eval=territory"
        const val SURVIVAL = "puct:budget=1000,cpuct=1.5,territoryWeight=1.0,eval=survival"
        const val HORIZON = "puct:budget=1000,cpuct=1.5,territoryWeight=1.0,eval=horizon"
        const val UCT = "uct:budget=1000,exploration=3.0"

        val SPECS = linkedSetOf(TERRITORY, TERRITORY_400, SURVIVAL, HORIZON, UCT)
    }
}
