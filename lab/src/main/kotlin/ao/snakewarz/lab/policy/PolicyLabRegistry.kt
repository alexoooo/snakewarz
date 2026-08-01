package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.reactive.policy.PolicyResearch
import ao.snakewarz.bots.search.FixedDepthResearch

/**
 * The shipped registry plus JVM-only research labels.
 *
 * These entries exist only inside `:lab`. Their ids may label local match rows so `rate` can read a
 * research field, but they must never be retained in a replay: the Wasm application cannot resolve
 * the aliases, and none is a released [BotId]. The qualifying P2 `full-owned` rule separately ships
 * under `cartographer`; its temporary alias remains unstable like the other research cases.
 */
internal class PolicyLabRegistry(private val shipped: BotRegistry) : BotRegistry {
    private val policies: List<BotEntry> = PolicyResearch.cases.map { candidate ->
        BotEntry(
            id = BotId("$POLICY_PREFIX${candidate.key}"),
            displayName = "P2 ${candidate.key}",
            factory = candidate.botFactory,
        )
    }
    private val fixedDepths: List<BotEntry> = FixedDepthResearch.cases.map { candidate ->
        BotEntry(
            id = BotId("$FIXED_DEPTH_PREFIX${candidate.key}"),
            displayName = "P4 fixed depth ${candidate.depth}",
            factory = candidate.botFactory,
        )
    }

    override val entries: List<BotEntry> = shipped.entries + policies + fixedDepths

    private val byId: Map<BotId, BotEntry> = entries.associateByTo(LinkedHashMap()) { it.id }

    init {
        require(byId.size == entries.size) {
            "a research id collides with another lab bot: ${entries.map { it.id }}"
        }
    }

    override fun get(id: BotId): BotEntry? = byId[id]

    internal companion object {
        const val POLICY_PREFIX: String = "p2-"
        const val FIXED_DEPTH_PREFIX: String = "p4-"

        fun holds(id: BotId): Boolean =
            when {
                id.slug.startsWith(POLICY_PREFIX) ->
                    PolicyResearch.case(id.slug.removePrefix(POLICY_PREFIX)) != null

                id.slug.startsWith(FIXED_DEPTH_PREFIX) ->
                    FixedDepthResearch.case(id.slug.removePrefix(FIXED_DEPTH_PREFIX)) != null

                else -> false
            }
    }
}
