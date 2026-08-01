package ao.snakewarz.lab

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.gauntlet.GauntletCandidate
import ao.snakewarz.lab.gauntlet.GauntletTrialLevel
import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.tournament.Contestant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the seven levels name bots that exist, configured in ways those bots accept.
 *
 * **This lives here rather than beside `Gauntlet` because `:match` may not see `:bots`**, and that edge
 * is not negotiable to make a test compile — SW-04. `:lab` is one of the two modules that legitimately
 * sees a registry and the match driver together, so it is where "the table and the roster agree" can
 * be asserted at all. Without it a level could name a slug nobody ships and the failure would be a
 * blank screen on the level-select page.
 *
 * How well each level plays is not here either. That is `GauntletCommand`, it takes several hundred
 * complete matches on seven boards, and it is run from the command line rather than from a suite that
 * has to finish while somebody watches.
 */
class GauntletCommandTest {
    @Test
    fun `the research candidate carries the post-human-gate Level 3 finalist`() {
        val actual = GauntletCandidate.levels.map { level ->
            val settings = level.opponent.params.names.joinToString(",") {
                "$it=${level.opponent.params.string(it, "")}"
            }
            listOf(
                "${level.index}",
                level.opponent.bot.slug,
                "${level.opponent.budgetPerTurn}",
                settings,
                "${level.rows}x${level.cols}",
                level.shape.slug,
                "${level.mapSeed}",
            ).joinToString("|")
        }

        assertEquals(
            listOf(
                "1|chase|0||12x12|pillars|0",
                "2|cartographer|0||16x16|rooms|0",
                "3|lookahead|1024|depth=5|12x12|arena|0",
                "4|puct|600|eval=territory|12x12|scatter|0",
                "5|puct|600|eval=territory|12x12|islands|0",
                "6|alphabeta|600|eval=territory|12x12|pinwheel|0",
                "7|alphabeta|1700|eval=territory|8x8|empty|0",
            ),
            actual,
        )
    }

    @Test
    fun `every research candidate spec is accepted by the shipped registry`() {
        for (level in GauntletCandidate.levels) {
            val entry = ShippedBots.entryOf(level.opponent.bot)
            if (level.opponent.budgetPerTurn == 0) {
                assertNull(entry.search, "candidate level ${level.index} grants zero to a search bot")
            } else {
                assertNotNull(entry.search, "candidate level ${level.index} grants an allowance to a free bot")
            }

            for (name in level.opponent.params.names) {
                val knob = entry.params.firstOrNull { it.name == name }
                assertNotNull(knob, "candidate level ${level.index} sets unknown knob '$name'")
                assertNull(
                    knob.reject(level.opponent.params.string(name, "")),
                    "candidate level ${level.index} sets an invalid '$name'",
                )
            }
        }
    }

    @Test
    fun `the research candidate preserves every shipped level geometry`() {
        val shipped = Gauntlet.levels.map(GauntletTrialLevel::shipped)

        for ((expected, actual) in GauntletCandidate.levels.zip(shipped)) {
            assertEquals(expected.index, actual.index)
            assertEquals(expected.rows, actual.rows)
            assertEquals(expected.cols, actual.cols)
            assertEquals(expected.shape, actual.shape)
            assertEquals(expected.mapSeed, actual.mapSeed)
            assertContentEquals(expected.walls(), actual.walls())
        }
    }

    @Test
    fun `trial walls read the pinned map seed and not a match seed`() {
        val level = GauntletTrialLevel(
            index = 1,
            opponent = Contestant(ShippedBots.entries.first().id, 0),
            rows = 16,
            cols = 16,
            shape = MapShape.ISLANDS,
            mapSeed = 7L,
        )
        val pinned = level.walls()
        val expected = generateMap(16, 16, MapShape.ISLANDS, seed = 7L).walls()
        val anotherMatchSeed = generateMap(16, 16, MapShape.ISLANDS, seed = 8L).walls()

        assertContentEquals(expected, pinned)
        assertFalse(pinned.contentEquals(anotherMatchSeed), "fixture seeds drew the same islands")
    }

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

        // The default reference also appears as a level, so its different allowance only
        // distinguishes it if the bot declares one at all.
        assertNotNull(entry.search, "${entry.id.slug} spends nothing, so no allowance tells it from a level")

        val collisions = Gauntlet.levels.filter {
            it.opponent == reference.bot && it.params == reference.params &&
                it.budgetPerTurn == GauntletCommand.REFERENCE_BUDGET
        }
        assertTrue(collisions.isEmpty(), "the default reference is also $collisions")
    }
}
