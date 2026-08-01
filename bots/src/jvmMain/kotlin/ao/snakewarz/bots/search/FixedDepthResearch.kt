package ao.snakewarz.bots.search

import ao.snakewarz.botapi.registry.BotFactory

/**
 * Temporary JVM-only access to P4's completed fixed-depth search shapes.
 *
 * These factories are research plumbing rather than released bot identities. Their `p4-` lab ids
 * may appear in local aggregate rows, but they must never be retained in a replay because the Wasm
 * application cannot resolve them.
 */
public object FixedDepthResearch {
    /** Greedy one-ply, exhaustive reply guard and completed three-ply alpha-beta, in depth order. */
    public val cases: List<FixedDepthCase> = (1..3).map { depth ->
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
