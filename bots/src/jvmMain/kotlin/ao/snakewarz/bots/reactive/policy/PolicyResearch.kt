package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet

/**
 * Temporary JVM-only access to P2's policy family and ablations.
 *
 * This is research plumbing, not a bot registry: its keys are unstable, never enter a replay, and
 * are absent from the Wasm application. `full-owned` also has the permanent shipped identity
 * `cartographer`; the `p2-` key remains only a lab label. The bridge exposes factories rather than
 * implementations so every match and every diagnostic probe still gets one seat-local mutable bot
 * instance.
 */
public object PolicyResearch {
    /** The complete current family, in declaration order. */
    public val cases: List<PolicyCase> = PolicyVariant.entries.map { variant ->
        PolicyCase(
            key = variant.key,
            botFactory = BotFactory { setup -> PolicyBot(setup, variant) },
            probeFactory = PolicyProbeFactory { setup -> PolicyProbe(PolicyBot(setup, variant)) },
        )
    }

    /** The case named [key], or `null` when the unstable research key is unknown. */
    public fun case(key: String): PolicyCase? {
        for (candidate in cases) {
            if (candidate.key == key) {
                return candidate
            }
        }
        return null
    }

    /** One unreleased configuration and the two seat-local ways the JVM lab may construct it. */
    public class PolicyCase internal constructor(
        public val key: String,
        public val botFactory: BotFactory,
        public val probeFactory: PolicyProbeFactory,
    )

    /** Makes a fresh mutable diagnostic probe for one [BotSetup]. */
    public fun interface PolicyProbeFactory {
        public fun create(setup: BotSetup): PolicyProbe
    }

    /**
     * A seat-local view of one policy bot that exposes its tie set before the hash tie-break.
     *
     * [choose] returns the move, then [rawMaxima] names every direction tied on all enabled policy
     * readings. The same probe must not be shared between seats or matches.
     */
    public class PolicyProbe internal constructor(private val bot: PolicyBot) {
        public var rawMaxima: DirectionSet = DirectionSet.EMPTY
            private set

        public fun choose(turn: Turn): Direction {
            val selected = bot.chooseDirection(turn)
            rawMaxima = bot.rawMaxima
            return selected
        }
    }
}
