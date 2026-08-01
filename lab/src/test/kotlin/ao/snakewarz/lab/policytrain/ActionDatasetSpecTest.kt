package ao.snakewarz.lab.policytrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActionDatasetSpecTest {
    @Test
    fun `one option carries several strictly delimited datasets`() {
        val specs = ActionDatasetSpec.parseList(
            "arena12|.lab/p1-arena12|alphabeta:budget=1000,eval=territory|800;" +
                "islands-63001|.lab/p3-source-islands-63001|puct:budget=1000,eval=territory|125",
            "train",
        )

        assertEquals(listOf("arena12", "islands-63001"), specs.map { it.label })
        assertEquals(listOf(800, 125), specs.map { it.positionsPerPhase })
        assertEquals("puct:budget=1000,eval=territory", specs.last().expertSpec)
    }

    @Test
    fun `malformed or repeated datasets fail before a log is opened`() {
        assertFailsWith<IllegalArgumentException> {
            ActionDatasetSpec.parseList("arena12|dir|expert", "train")
        }
        assertFailsWith<IllegalArgumentException> {
            ActionDatasetSpec.parseList("arena12|dir|expert|10;arena12|elsewhere|expert|10", "train")
        }
        assertFailsWith<IllegalArgumentException> {
            ActionDatasetSpec.parseList("Arena|dir|expert|10", "train")
        }
        assertFailsWith<IllegalArgumentException> {
            ActionDatasetSpec.parseList("arena|dir|expert|0", "train")
        }
    }
}
