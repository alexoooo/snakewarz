package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicySamplerTest {
    @Test
    fun `progress thirds use the completed replay length`() {
        assertEquals(PolicyPhase.EARLY, PolicyPhase.at(0, 9))
        assertEquals(PolicyPhase.EARLY, PolicyPhase.at(2, 9))
        assertEquals(PolicyPhase.MIDDLE, PolicyPhase.at(3, 9))
        assertEquals(PolicyPhase.MIDDLE, PolicyPhase.at(5, 9))
        assertEquals(PolicyPhase.LATE, PolicyPhase.at(6, 9))
        assertEquals(PolicyPhase.LATE, PolicyPhase.at(8, 9))
    }

    @Test
    fun `sampling is reproducible bounded and match balanced`() {
        val replays = List(8) { index -> played(index.toLong() + 100) }

        val first = selectPolicyPositions(replays, positionsPerPhase = 3, seed = 72_001L)
        val again = selectPolicyPositions(replays, positionsPerPhase = 3, seed = 72_001L)

        assertEquals(first.samples.map { it.identity() }, again.samples.map { it.identity() })
        for (phase in PolicyPhase.entries) {
            assertTrue(first.samples.count { it.phase == phase } <= 3)
        }
        assertTrue(first.samples.isNotEmpty())
        assertTrue(first.choices.sum() > 0)
        assertTrue(first.forced.sum() > 0)

        val perMatchPhase = first.samples.groupingBy { it.replay.key to it.phase }.eachCount()
        assertTrue(perMatchPhase.values.all { it == 1 }, perMatchPhase.toString())
        for (sample in first.samples) {
            assertEquals(policyReplayRank(72_001L, sample.replay.key, sample.phase), sample.rank)
        }
    }

    @Test
    fun `complete openings and mirrored pairs keep P1 experimental blocks`() {
        val complete = logged(run = "run-a", pair = 7, opening = "r2c3-r5c4")
        val sameOpeningAnotherRun = logged(run = "run-b", pair = 99, opening = "r2c3-r5c4")
        val mirrored = logged(run = "run-a", pair = 7, opening = null)
        val anotherPair = logged(run = "run-a", pair = 8, opening = null)

        assertEquals(policyBlock(complete), policyBlock(sameOpeningAnotherRun))
        assertEquals("opening:r2c3-r5c4", policyBlock(complete))
        assertEquals("run-a:7", policyBlock(mirrored))
        assertTrue(policyBlock(mirrored) != policyBlock(anotherPair))
    }

    private fun played(seed: Long): PolicyReplay {
        val setup = MatchSetup.create(
            rows = 8,
            cols = 8,
            slots = listOf(BotId("space"), BotId("wallhug")),
            seed = seed,
            budgetPerTurn = 0,
        )
        val match = Match(setup, ShippedBots)
        match.runToCompletion()
        return PolicyReplay("run $seed", "run:$seed", match.record())
    }

    private fun logged(run: String, pair: Int, opening: String?): LoggedMatch = LoggedMatch(
        run = run,
        index = 0,
        pairKey = pair,
        openingIdentity = opening,
        seed = 1L,
        turnOrder = listOf(0, 1),
        end = "WINNER",
        turnsPlayed = 1,
        elapsedMicros = 0,
        moveStreamHash = 0,
        slots = emptyList(),
    )

    private fun PolicySample.identity(): String = "${replay.key}:$turnIndex:${phase.label}"
}
