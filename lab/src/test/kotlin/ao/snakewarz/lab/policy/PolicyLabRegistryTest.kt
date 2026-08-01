package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.reactive.policy.PolicyResearch
import ao.snakewarz.bots.search.FixedDepthResearch
import ao.snakewarz.lab.LabCommand
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PolicyLabRegistryTest {
    @Test
    fun `research cases resolve only through the lab overlay`() {
        val shippedIds = ShippedBots.entries.map { it.id }
        val registry = PolicyLabRegistry(ShippedBots)

        assertEquals(shippedIds, ShippedBots.entries.map { it.id })
        assertEquals(
            shippedIds +
                PolicyResearch.cases.map { BotId("p2-${it.key}") } +
                FixedDepthResearch.cases.map { BotId("p4-${it.key}") },
            registry.entries.map { it.id },
        )
        for (candidate in PolicyResearch.cases) {
            val id = BotId("p2-${candidate.key}")
            assertNull(ShippedBots[id])
            assertNotNull(registry[id])
        }
        for (candidate in FixedDepthResearch.cases) {
            val id = BotId("p4-${candidate.key}")
            assertNull(ShippedBots[id])
            assertNotNull(registry[id])
        }
    }

    @Test
    fun `research fields cannot retain replay payloads`() {
        val registry = PolicyLabRegistry(ShippedBots)
        val ids = listOf(
            "p2-${PolicyResearch.cases.first().key}",
            "p4-${FixedDepthResearch.cases.first().key}",
        )
        for (id in ids) {
            val play = assertFailsWith<IllegalArgumentException> {
                LabCommand.of("play chase $id --rounds 2".split(' '), registry)
            }
            assertContains(play.message.orEmpty(), "--replays none")
            LabCommand.of("play chase $id --rounds 2 --replays none".split(' '), registry)

            val ab = assertFailsWith<IllegalArgumentException> {
                LabCommand.of("ab chase $id".split(' '), registry)
            }
            assertContains(ab.message.orEmpty(), "--log none")
            LabCommand.of("ab chase $id --log none".split(' '), registry)

            val gauntlet = assertFailsWith<IllegalArgumentException> {
                LabCommand.of("gauntlet --against $id".split(' '), registry)
            }
            assertContains(gauntlet.message.orEmpty(), "--log none")
            LabCommand.of("gauntlet --against $id --log none".split(' '), registry)
        }
    }
}
