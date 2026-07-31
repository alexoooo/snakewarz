package ao.snakewarz.lab

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.gauntlet.Gauntlet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the eleven levels name bots that exist, configured in ways those bots accept.
 *
 * **This lives here rather than beside `Gauntlet` because `:match` may not see `:bots`**, and that edge
 * is not negotiable to make a test compile — SW-04. `:lab` is one of the two modules that legitimately
 * sees a registry and the match driver together, so it is where "the table and the roster agree" can
 * be asserted at all. Without it a level could name a slug nobody ships and the failure would be a
 * blank screen on the level-select page.
 *
 * How well each level plays is not here either. That is `GauntletCommand`, it takes several hundred
 * complete matches on eleven boards, and it is run from the command line rather than from a suite that
 * has to finish while somebody watches.
 */
class GauntletCommandTest {
    @Test
    fun `every level names a bot the shipped registry has`() {
        for (level in Gauntlet.levels) {
            assertNotNull(
                ShippedBots[level.opponent],
                "level ${level.index} seats '${level.opponent.slug}', which nothing registers",
            )
        }
    }

    @Test
    fun `every level's settings are knobs that bot declares, at values it accepts`() {
        // The failure this catches is silent at the point of the mistake: `BotKnob.read` is total, so
        // a misspelt name or an out-of-range value falls back on the default and the level plays a
        // different opponent from the one the table describes, with nothing anywhere saying so.
        for (level in Gauntlet.levels) {
            val entry = ShippedBots.entryOf(level.opponent)
            for (name in level.params.names) {
                val knob = entry.params.firstOrNull { it.name == name }
                assertNotNull(
                    knob,
                    "level ${level.index} sets '$name', which '${level.opponent.slug}' does not declare",
                )

                val value = level.params.string(name, "")
                assertNull(
                    knob.reject(value),
                    "level ${level.index} sets $name=$value, which '${level.opponent.slug}' refuses",
                )
            }
        }
    }

    @Test
    fun `a level reads back as an entrant spec, so a losing level can be replayed by hand`() {
        for (level in Gauntlet.levels) {
            val settings = level.params.names.joinToString(",") { "$it=${level.params.string(it, "")}" }
            val spec = "${level.opponent.slug}:budget=${level.budgetPerTurn}" +
                if (settings.isEmpty()) "" else ",$settings"

            val contestant = LabCommand.contestantOf(spec, ShippedBots)
            assertEquals(level.opponent, contestant.bot)
            assertEquals(level.budgetPerTurn, contestant.budgetPerTurn)
            assertEquals(level.params, contestant.params)
        }
    }

    @Test
    fun `the default reference is a bot that exists, and is not any level under another name`() {
        val reference = LabCommand.contestantOf(GauntletCommand.DEFAULT_REFERENCE, ShippedBots)
        val entry = ShippedBots.entryOf(reference.bot)

        // The ten shipped slugs are exactly the ten opponents the eleven levels draw on, so a
        // reference is necessarily one of them at a different allowance -- which only distinguishes
        // it if the bot declares one at all.
        assertNotNull(entry.search, "${entry.id.slug} spends nothing, so no allowance tells it from a level")

        val collisions = Gauntlet.levels.filter {
            it.opponent == reference.bot && it.params == reference.params &&
                it.budgetPerTurn == GauntletCommand.REFERENCE_BUDGET
        }
        assertTrue(collisions.isEmpty(), "the default reference is also $collisions")
    }
}
