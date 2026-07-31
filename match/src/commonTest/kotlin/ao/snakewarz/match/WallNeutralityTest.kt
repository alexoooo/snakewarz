package ao.snakewarz.match

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.replay.ReplayCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A setup handed an empty map describes the same match, records the same game and encodes to the same
 * payload as one that was never told maps exist.
 *
 * Every replay URL anybody has shared, every logged match the corpus is fitted from and every rung of
 * the ladder was recorded on a board with no map. The map field is only free to land in the header if
 * the mapless case is *identical* rather than merely equivalent — so this compares the setups, the
 * `MatchRecord`s the matches they drive produce, and the base64url the codec writes for each.
 *
 * The codec is deliberately untouched by the change this covers, which is what makes the payload arm
 * meaningful: it is the same encoder reading a header with one more field in it.
 */
class WallNeutralityTest {
    @Test
    fun `an empty map is the same setup as no map at all`() {
        forEachOpening { rows, cols, seats, seed, where ->
            val slots = List(seats) { BotId(SURVIVORS[it % SURVIVORS.size]) }
            val mapless = MatchSetup.create(rows, cols, slots, seed)
            val emptyMap = MatchSetup.create(rows, cols, slots, seed, walls = IntArray(0))

            assertEquals(mapless, emptyMap, where)
            assertEquals(mapless.hashCode(), emptyMap.hashCode(), where)
            assertEquals(mapless.spawns().toList(), emptyMap.spawns().toList(), where)
        }
    }

    @Test
    fun `and the matches they drive record and encode identically`() {
        forEachOpening { rows, cols, seats, seed, where ->
            val slots = List(seats) { BotId(SURVIVORS[it % SURVIVORS.size]) }
            val mapless = play(MatchSetup.create(rows, cols, slots, seed))
            val emptyMap = play(MatchSetup.create(rows, cols, slots, seed, walls = IntArray(0)))

            assertEquals(mapless, emptyMap, where)
            assertEquals(ReplayCodec.encode(mapless), ReplayCodec.encode(emptyMap), where)
        }
    }

    private companion object {
        val GEOMETRIES = listOf(8 to 8, 12 to 12, 20 to 20, 13 to 17)
        val SEEDS = listOf(1L, 7L, 20260730L)
        const val MAX_SEATS = 4

        /** Bots that stay alive by playing differently, so a game is long enough to be worth comparing. */
        val SURVIVORS = listOf("cycle", "last")

        fun forEachOpening(check: (rows: Int, cols: Int, seats: Int, seed: Long, where: String) -> Unit) {
            for ((rows, cols) in GEOMETRIES) {
                for (seats in 1..MAX_SEATS) {
                    for (seed in SEEDS) {
                        check(rows, cols, seats, seed, "${rows}x$cols, $seats seats, seed $seed")
                    }
                }
            }
        }

        fun play(setup: MatchSetup) = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()
    }
}
