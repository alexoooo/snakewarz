package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.at
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PuctTree], mirroring `UctTreeTest` where the two trees agree and pinning the four places they do
 * not: the prior, value backup, prior-ordered first play, and most-visits at the root.
 */
class PuctTreeTest {
    private val tree = PuctTree()
    private val priors = DoubleArray(Direction.entries.size)

    @Test
    fun `a fresh tree is a root and nothing else`() {
        tree.reset()

        assertEquals(1, tree.size)
        assertEquals(PuctTree.NO_ACTOR, tree.actorOf(PuctTree.ROOT))
        assertFalse(tree.isOpen(PuctTree.ROOT), "nothing is known about a node nobody has opened")
    }

    @Test
    fun `opening a node records its moves and what the prior makes of them`() {
        tree.reset()
        uniform()
        priors[Direction.EAST.ordinal] = 0.7

        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.EAST), priors)

        assertTrue(tree.isOpen(PuctTree.ROOT))
        assertEquals(0.7, tree.priorOf(PuctTree.ROOT, Direction.EAST))
        repeat(8) {
            assertTrue(
                tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY) in
                    DirectionSet.of(Direction.NORTH, Direction.EAST),
                "selection offered a move that was never opened",
            )
        }
    }

    @Test
    fun `a trapped mover gets one edge and the whole of the prior`() {
        tree.reset()

        tree.open(PuctTree.ROOT, DirectionSet.EMPTY, priors)

        assertTrue(tree.isOpen(PuctTree.ROOT))
        assertEquals(1.0, tree.priorOf(PuctTree.ROOT, Direction.NORTH))
        assertEquals(Direction.NORTH, tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY))
    }

    @Test
    fun `a child is created once and then found`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        val first = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(1))

        assertEquals(2, tree.size)
        assertEquals(first, tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(1)))
        assertEquals(2, tree.size, "the second call found it rather than making another")
        assertEquals(first, tree.childOf(PuctTree.ROOT, Direction.SOUTH))
        assertEquals(1, tree.actorOf(first), "the child belongs to whoever moved into it")
    }

    @Test
    fun `a full pool stops growing rather than failing`() {
        val small = PuctTree(maxNodes = 4)
        small.reset()

        assertTrue(small.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)) != PuctTree.NO_NODE)
        assertTrue(small.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0)) != PuctTree.NO_NODE)
        assertTrue(small.childOrCreate(PuctTree.ROOT, Direction.EAST, SnakeId(0)) != PuctTree.NO_NODE)

        assertEquals(4, small.size)
        assertEquals(
            PuctTree.NO_NODE,
            small.childOrCreate(PuctTree.ROOT, Direction.WEST, SnakeId(0)),
            "the caller judges where it stands instead of deepening",
        )
    }

    @Test
    fun `the prior survives the pool growing, which is the field a grow written from memory forgets`() {
        tree.reset()
        uniform()
        priors[Direction.WEST.ordinal] = 0.55
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        // Past the initial capacity by enough to force more than one doubling.
        var node = PuctTree.ROOT
        repeat(3_000) {
            node = tree.childOrCreate(node, Direction.NORTH, SnakeId(0))
        }

        assertEquals(3_001, tree.size)
        assertEquals(0.55, tree.priorOf(PuctTree.ROOT, Direction.WEST))
    }

    @Test
    fun `a node is credited with its own actor's value, and the root with none`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        val child = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(1))

        val values = doubleArrayOf(0.2, 0.8, 0.5)
        tree.record(PuctTree.ROOT, values)
        tree.record(child, values)

        assertEquals(1, tree.visitsOf(PuctTree.ROOT))
        assertEquals(0.0, tree.averageOf(PuctTree.ROOT), "nobody moved into the root, so it has no payoff")
        assertEquals(0.8, tree.averageOf(child), "slot 1 moved in, so slot 1's value is the one credited")
    }

    @Test
    fun `an average is the plain mean, not legacy's shrink toward zero`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        val child = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))

        val values = doubleArrayOf(1.0)
        repeat(2) { tree.record(child, values) }

        // UctTree would say 2.0 / 3 here, carrying Node.java:286's prior on purpose.
        assertEquals(1.0, tree.averageOf(child))
    }

    @Test
    fun `among children nobody has visited, the prior decides`() {
        tree.reset()
        uniform()
        priors[Direction.SOUTH.ordinal] = 0.7
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        assertEquals(
            Direction.SOUTH,
            tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY),
            "every child is on firstPlay, so the exploration term is the whole comparison",
        )
    }

    @Test
    fun `among children visited equally often, the value decides`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        for (direction in Direction.entries) {
            val child = tree.childOrCreate(PuctTree.ROOT, direction, SnakeId(0))
            tree.record(child, doubleArrayOf(if (direction == Direction.WEST) 0.9 else 0.1))
            tree.record(PuctTree.ROOT, doubleArrayOf(0.0))
        }

        // Equal priors and equal visit counts leave the exploration term identical across all four.
        assertEquals(Direction.WEST, tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY))
    }

    @Test
    fun `the root answers with its most-visited move, in a case where the best average disagrees`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        val lucky = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        repeat(2) { tree.record(lucky, doubleArrayOf(1.0)) }

        val believed = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        repeat(5) { tree.record(believed, doubleArrayOf(0.5)) }

        assertTrue(tree.averageOf(lucky) > tree.averageOf(believed), "the fixture has to actually disagree")
        assertEquals(
            Direction.SOUTH,
            tree.bestMoveAtRoot(),
            "the visit count is what the search spent its allowance on",
        )
    }

    @Test
    fun `a root nobody got to visit has nothing to say`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        assertNull(tree.bestMoveAtRoot(), "so the bot can fall back rather than invent a move")
    }

    @Test
    fun `reset hands back a tree that behaves like a brand new one`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        repeat(50) { tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)) }
        tree.record(PuctTree.ROOT, doubleArrayOf(1.0))

        tree.reset()

        assertEquals(1, tree.size)
        assertEquals(0, tree.visitsOf(PuctTree.ROOT))
        assertFalse(tree.isOpen(PuctTree.ROOT))
        assertEquals(PuctTree.NO_NODE, tree.childOf(PuctTree.ROOT, Direction.NORTH))
    }

    private fun uniform() {
        priors.fill(1.0 / Direction.entries.size)
    }

    private companion object {
        const val EXPLORATION = 1.5
        const val FIRST_PLAY = 0.5
    }
}
