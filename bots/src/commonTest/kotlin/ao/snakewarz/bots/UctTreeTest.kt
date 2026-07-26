package ao.snakewarz.bots

import ao.snakewarz.core.Direction
import ao.snakewarz.core.DirectionSet
import ao.snakewarz.core.MatchEnd
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UctTreeTest {
    @Test
    fun `a child is created once and found thereafter`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        assertEquals(UctTree.NO_NODE, tree.childOf(UctTree.ROOT, Direction.NORTH))

        val first = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0))
        assertNotEquals(UctTree.NO_NODE, first)
        assertEquals(first, tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0)))
        assertEquals(first, tree.childOf(UctTree.ROOT, Direction.NORTH))
        assertEquals(2, tree.size, "the root and one child")

        val second = tree.childOrCreate(UctTree.ROOT, Direction.SOUTH, SnakeId(0))
        assertNotEquals(first, second, "a different direction is a different node")
    }

    @Test
    fun `the reward is the node's own actor's, and is never complemented`() {
        // This is what replaces legacy's negamax, and the whole reason a third snake is playable.
        // `Node.propagateValue` flipped the value at every step up, so the node below would read
        // 1.0 here. That is right for two players and helps the wrong opponent with three.
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        val mine = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0))
        val theirs = tree.childOrCreate(UctTree.ROOT, Direction.SOUTH, SnakeId(1))
        val slotOneWins = MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING)

        tree.record(mine, slotOneWins)
        tree.record(theirs, slotOneWins)

        // One visit each, so the shrink-toward-zero prior halves both: 0/2 and 1/2.
        assertEquals(0.0, tree.averageOf(mine), "slot 0 did not win, so slot 0's node scores nothing")
        assertEquals(0.5, tree.averageOf(theirs), "slot 1 won, so slot 1's node scores the win")
    }

    @Test
    fun `a draw is worth half to everybody`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        val drawn = MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT)
        val first = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0))
        val second = tree.childOrCreate(UctTree.ROOT, Direction.SOUTH, SnakeId(2))

        tree.record(first, drawn)
        tree.record(second, drawn)

        assertEquals(0.25, tree.averageOf(first))
        assertEquals(0.25, tree.averageOf(second))
    }

    @Test
    fun `the root counts its visits but owns no payoff`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        assertEquals(UctTree.NO_ACTOR, tree.actorOf(UctTree.ROOT))

        tree.record(UctTree.ROOT, MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING))

        assertEquals(1, tree.visitsOf(UctTree.ROOT), "its visit count is UCB1's N")
        assertEquals(0.0, tree.averageOf(UctTree.ROOT), "nobody moved into it, so nobody is owed for it")
    }

    @Test
    fun `every move is tried once before any is tried twice`() {
        // The huge randomised score for an unvisited child is legacy's expansion policy, and this
        // is the behaviour it buys: a uniformly shuffled first pass over the whole move set.
        val tree = UctTree()
        val rng = SplitMix64(7)
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        val seen = mutableSetOf<Direction>()
        repeat(4) {
            val direction = tree.selectUcb1(UctTree.ROOT, rng, 5.0)
            assertTrue(seen.add(direction), "$direction came up twice before the others came up once")

            val child = tree.childOrCreate(UctTree.ROOT, direction, SnakeId(0))
            tree.record(child, MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING))
            tree.record(UctTree.ROOT, MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING))
        }

        assertEquals(4, seen.size)
    }

    @Test
    fun `a trapped mover is opened with one edge, not four`() {
        // Every direction from a trapped position eliminates the snake and leaves a bit-identical
        // board, so three of the four children would be duplicates of the first -- at the deepest
        // and most numerous part of the tree.
        val tree = UctTree()
        val rng = SplitMix64(3)
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.EMPTY)

        repeat(8) {
            assertEquals(Direction.NORTH, tree.selectUcb1(UctTree.ROOT, rng, 5.0))
            val child = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0))
            tree.record(child, MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING))
            tree.record(UctTree.ROOT, MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING))
        }

        assertEquals(2, tree.size, "the root and its single edge")
    }

    @Test
    fun `the best move at the root is the best average, and nothing at all before any rollout`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.of(Direction.NORTH, Direction.SOUTH))

        assertNull(tree.bestMoveAtRoot(), "nothing has been visited yet")

        val north = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(0))
        val south = tree.childOrCreate(UctTree.ROOT, Direction.SOUTH, SnakeId(0))
        assertNull(tree.bestMoveAtRoot(), "created is not visited")

        tree.record(north, MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING))
        tree.record(south, MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING))

        assertEquals(Direction.SOUTH, tree.bestMoveAtRoot())
    }

    @Test
    fun `the pool grows without losing what it already held`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)

        // A chain deeper than the initial capacity, so the arrays are reallocated several times.
        var node = UctTree.ROOT
        val chain = IntArray(3_000)
        for (depth in chain.indices) {
            tree.open(node, DirectionSet.ALL)
            node = tree.childOrCreate(node, Direction.NORTH, SnakeId(depth % 2))
            chain[depth] = node
            tree.record(node, MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING))
        }

        assertEquals(3_001, tree.size)
        for (depth in chain.indices) {
            assertEquals(1, tree.visitsOf(chain[depth]), "node at depth $depth lost its visit")
            val expected = if (depth % 2 == 0) 0.5 else 0.0
            assertEquals(expected, tree.averageOf(chain[depth]), "node at depth $depth lost its reward")
        }
    }

    @Test
    fun `a full pool stops growing instead of failing`() {
        // The ceiling is a backstop rather than a working limit, but a search that threw when it
        // hit one would take the page down rather than merely play a little worse.
        val tree = UctTree(maxNodes = 8)
        tree.reset()

        var node = UctTree.ROOT
        repeat(20) {
            tree.open(node, DirectionSet.ALL)
            val child = tree.childOrCreate(node, Direction.NORTH, SnakeId(0))
            if (child != UctTree.NO_NODE) {
                node = child
            }
        }

        assertEquals(8, tree.size)
        assertEquals(UctTree.NO_NODE, tree.childOrCreate(node, Direction.SOUTH, SnakeId(0)))
    }

    @Test
    fun `reset hands back a pool that behaves brand new`() {
        val tree = UctTree()
        tree.reset()
        tree.open(UctTree.ROOT, DirectionSet.ALL)
        val stale = tree.childOrCreate(UctTree.ROOT, Direction.NORTH, SnakeId(1))
        tree.record(stale, MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING))

        tree.reset()

        assertEquals(1, tree.size)
        assertEquals(0, tree.visitsOf(UctTree.ROOT))
        assertEquals(UctTree.NO_ACTOR, tree.actorOf(UctTree.ROOT))
        assertEquals(false, tree.isOpen(UctTree.ROOT), "the root is unopened until the turn says otherwise")

        tree.open(UctTree.ROOT, DirectionSet.ALL)
        assertEquals(UctTree.NO_NODE, tree.childOf(UctTree.ROOT, Direction.NORTH), "no child survived the reset")
        assertNull(tree.bestMoveAtRoot())
    }
}
