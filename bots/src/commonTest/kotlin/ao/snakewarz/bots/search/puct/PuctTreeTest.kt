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

        val settled = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        tree.proveTerminal(settled, 0)

        // Past the initial capacity by enough to force more than one doubling.
        var node = settled
        repeat(3_000) {
            node = tree.childOrCreate(node, Direction.NORTH, SnakeId(0))
        }

        assertEquals(3_002, tree.size)
        assertEquals(0.55, tree.priorOf(PuctTree.ROOT, Direction.WEST))
        assertTrue(tree.isProven(settled), "and so does what the solver settled")
        assertEquals(0, tree.provenWinnerOf(settled))
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
        val child = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        tree.proveTerminal(child, 0)
        tree.record(PuctTree.ROOT, doubleArrayOf(1.0))

        tree.reset()

        assertEquals(1, tree.size)
        assertEquals(0, tree.visitsOf(PuctTree.ROOT))
        assertFalse(tree.isOpen(PuctTree.ROOT))
        assertEquals(PuctTree.NO_NODE, tree.childOf(PuctTree.ROOT, Direction.NORTH))

        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        assertFalse(
            tree.isProven(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))),
            "the pool hands the same node back, and a stale certainty on it would be a wrong answer",
        )
    }

    // -- the solver

    @Test
    fun `one winning reply settles a node before the rest are even tried`() {
        // The asymmetry that makes a solver worth having: the mover plays the win, so what the three
        // moves nobody has looked at would have done cannot change the answer.
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)), 0)

        assertTrue(tree.proveFromChildren(PuctTree.ROOT))
        assertEquals(0, tree.provenWinnerOf(PuctTree.ROOT), "slot 0 is to act and has a move that wins")
    }

    @Test
    fun `anything short of a win needs every reply, so one untried move leaves the node open`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)), 1)

        assertFalse(tree.proveFromChildren(PuctTree.ROOT), "three moves are unexamined and one of them may save it")
    }

    @Test
    fun `every reply losing settles the node as the loss it is`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)), 1)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0)), 1)

        assertTrue(tree.proveFromChildren(PuctTree.ROOT))
        assertEquals(1, tree.provenWinnerOf(PuctTree.ROOT))
    }

    @Test
    fun `with three snakes the mover takes what is best for itself, not what is worst for somebody`() {
        // max^n, and the shape the assumption is about. Slot 0 is to act; one reply hands the game to
        // slot 2 and the other wins it outright, and a backup that reasoned about who is hurt most
        // rather than about who is to act would answer the first.
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)), 2)
        tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0)), 0)

        assertTrue(tree.proveFromChildren(PuctTree.ROOT))
        assertEquals(0, tree.provenWinnerOf(PuctTree.ROOT))
    }

    @Test
    fun `a settled draw beats a settled loss and loses to a settled win`() {
        for ((winner, expected) in listOf(1 to -1, 0 to 0)) {
            tree.reset()
            uniform()
            tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)
            tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0)), -1)
            tree.proveTerminal(tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0)), winner)

            assertTrue(tree.proveFromChildren(PuctTree.ROOT))
            assertEquals(expected, tree.provenWinnerOf(PuctTree.ROOT), "against a reply won by $winner")
        }
    }

    @Test
    fun `selection leaves a settled child alone, however good it looks`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)

        val settled = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        val open = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        repeat(20) {
            tree.record(settled, doubleArrayOf(1.0))
            tree.record(open, doubleArrayOf(0.0))
            tree.record(PuctTree.ROOT, doubleArrayOf(0.0))
            tree.record(PuctTree.ROOT, doubleArrayOf(0.0))
        }
        assertEquals(
            Direction.NORTH,
            tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY),
            "the fixture is only interesting if selection wants the one about to be settled",
        )

        tree.proveTerminal(settled, 0)

        assertEquals(
            Direction.SOUTH,
            tree.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY),
            "an iteration spent on a settled child buys nothing",
        )
    }

    @Test
    fun `the root answers with a settled win rather than with the move it happened to spend most on`() {
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.ALL, priors)

        val popular = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        repeat(40) { tree.record(popular, doubleArrayOf(0.9)) }

        val won = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        tree.record(won, doubleArrayOf(1.0))
        tree.proveTerminal(won, 0)

        assertEquals(Direction.NORTH, tree.bestMoveAtRoot(), "one visit is enough when the visit settled it")
    }

    @Test
    fun `a move settled as a loss is not the answer while another move exists`() {
        // The case a visit count on its own gets wrong: a child settled on its fortieth visit keeps
        // all forty and then stops collecting, so it stays the most-visited move forever.
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)

        val lost = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        repeat(40) { tree.record(lost, doubleArrayOf(0.5)) }
        tree.proveTerminal(lost, 1)

        val open = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        tree.record(open, doubleArrayOf(0.1))

        assertEquals(Direction.SOUTH, tree.bestMoveAtRoot())
    }

    @Test
    fun `with every move settled as a loss the root still names one`() {
        // Doomed is not the same as trapped. The mover has moves and has to play one of them.
        tree.reset()
        uniform()
        tree.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)

        val early = tree.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        repeat(3) { tree.record(early, doubleArrayOf(0.0)) }
        tree.proveTerminal(early, 1)

        val later = tree.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        repeat(9) { tree.record(later, doubleArrayOf(0.0)) }
        tree.proveTerminal(later, 1)

        assertEquals(Direction.SOUTH, tree.bestMoveAtRoot(), "among equals the visit count still decides")
    }

    @Test
    fun `a tree not asked for RAVE builds none of it`() {
        // The same guarantee the solver has, and for the same reason: at the shipped default the
        // AMAF arrays are length zero, so a match that never sets the knob pays nothing for it and
        // plays the move stream `GoldenMoveStreamTest` pins.
        assertFalse(tree.raving)
    }

    @Test
    fun `a child nobody has tried is judged by AMAF rather than by the first-play constant`() {
        // The whole mechanism in one assertion. Both moves are unvisited and equally liked by the
        // prior, so without AMAF the choice falls to the lower ordinal; with one direction recorded
        // as worthless and the other as winning, selection must take the second.
        val raving = PuctTree(raveEquivalence = EQUIVALENCE)
        raving.reset()
        priors.fill(1.0 / Direction.entries.size)
        raving.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)

        repeat(4) { raving.recordRave(PuctTree.ROOT, Direction.NORTH.ordinal, 0.0) }
        repeat(4) { raving.recordRave(PuctTree.ROOT, Direction.SOUTH.ordinal, 1.0) }

        assertEquals(4, raving.raveVisitsOf(PuctTree.ROOT, Direction.SOUTH))
        assertEquals(1.0, raving.raveAverageOf(PuctTree.ROOT, Direction.SOUTH))
        assertEquals(Direction.SOUTH, raving.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY))
    }

    @Test
    fun `what really happened outweighs AMAF once enough of it has happened`() {
        // The other end of the schedule. AMAF says north is worthless and south wins; the real
        // statistic says the opposite, and past the equivalence parameter the real one has to win --
        // otherwise a move the search has actually refuted stays selected forever.
        val raving = PuctTree(raveEquivalence = EQUIVALENCE)
        raving.reset()
        priors.fill(1.0 / Direction.entries.size)
        raving.open(PuctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH), priors)

        val north = raving.childOrCreate(PuctTree.ROOT, Direction.NORTH, SnakeId(0))
        val south = raving.childOrCreate(PuctTree.ROOT, Direction.SOUTH, SnakeId(0))
        repeat(REFUTING) { raving.record(north, doubleArrayOf(1.0)) }
        repeat(REFUTING) { raving.record(south, doubleArrayOf(0.0)) }
        repeat(REFUTING) { raving.recordRave(PuctTree.ROOT, Direction.NORTH.ordinal, 0.0) }
        repeat(REFUTING) { raving.recordRave(PuctTree.ROOT, Direction.SOUTH.ordinal, 1.0) }

        assertEquals(Direction.NORTH, raving.selectPuct(PuctTree.ROOT, EXPLORATION, FIRST_PLAY))
    }

    @Test
    fun `the AMAF arrays survive the pool growing, which is the field a grow written from memory forgets`() {
        // The sibling of the prior's own case above: `grow` copies eight arrays and two of them
        // exist only while raving, so a copy written from the shape of the class rather than from
        // its fields drops exactly these and loses every statistic past the first block.
        val raving = PuctTree(raveEquivalence = EQUIVALENCE)
        raving.reset()
        priors.fill(1.0 / Direction.entries.size)

        var node = PuctTree.ROOT
        repeat(2_048) {
            raving.open(node, DirectionSet.of(Direction.NORTH), priors)
            raving.recordRave(node, Direction.NORTH.ordinal, 1.0)
            node = raving.childOrCreate(node, Direction.NORTH, SnakeId(0))
        }

        assertEquals(1, raving.raveVisitsOf(PuctTree.ROOT, Direction.NORTH))
        assertEquals(1.0, raving.raveAverageOf(PuctTree.ROOT, Direction.NORTH))
    }

    private fun uniform() {
        priors.fill(1.0 / Direction.entries.size)
    }

    private companion object {
        const val EXPLORATION = 1.5
        const val FIRST_PLAY = 0.5

        /** Visits at which the two estimates carry comparable weight, in the tests that need one. */
        const val EQUIVALENCE = 10.0

        /** Well past [EQUIVALENCE], which is what makes the real statistic the one that decides. */
        const val REFUTING = 200
    }
}
