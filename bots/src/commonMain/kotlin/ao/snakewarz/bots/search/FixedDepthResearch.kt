package ao.snakewarz.bots.search

import ao.snakewarz.botapi.registry.BotFactory

/**
 * Temporary cross-target access to completed fixed-depth search shapes.
 *
 * These factories are research plumbing rather than released bot identities. Depths one through
 * three reproduce the shipped implementation; depths four and five exist only for the 2026-08-01b
 * exact-level experiment. Lab ids may label local aggregate rows, but they must never be retained in
 * a replay or enter `ShippedBots`.
 */
public object FixedDepthResearch {
    /** Completed paranoid searches at depths one through five, in depth order. */
    public val cases: List<FixedDepthCase> = (1..5).map { depth ->
        FixedDepthCase(
            key = "depth-$depth",
            depth = depth,
            botFactory = BotFactory { setup -> FixedDepthBot(setup, depth) },
        )
    }

    /** The case named [key], or `null` when the unstable research key is unknown. */
    public fun case(key: String): FixedDepthCase? {
        for (candidate in cases) {
            if (candidate.key == key) {
                return candidate
            }
        }
        return null
    }

    /** One unreleased fixed shape and its seat-local factory. */
    public class FixedDepthCase internal constructor(
        public val key: String,
        public val depth: Int,
        public val botFactory: BotFactory,
    )
}
