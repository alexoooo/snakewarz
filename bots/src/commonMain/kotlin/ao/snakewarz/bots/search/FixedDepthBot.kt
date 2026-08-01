package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.reactive.policy.PolicyRanker
import ao.snakewarz.bots.search.puct.LeafEval
import ao.snakewarz.bots.search.puct.PuctBot
import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.MatchOutcome

/**
 * P4's deliberately small bridge between a no-tree policy and the full search bots.
 *
 * The configured shape is fixed: one greedy ply, an exhaustive reply guard, or a three-ply paranoid
 * alpha-beta. It never keeps a partial answer. [ao.snakewarz.botapi.scratch.Scratch.playout] refusing
 * one required static leaf resets the arena and invalidates the whole tree, so the exact root
 * Cartographer choice computed before searching is returned instead. Terminal leaves are the game's
 * answer and cost nothing; every nonterminal leaf is one [TerritoryEval]. Depths four and five are
 * unreleased research shapes; the shipped bot still accepts only one through three.
 */
internal class FixedDepthBot(
    setup: BotSetup,
    internal val requestedDepth: Int,
    private val eval: LeafEval = TerritoryEval(
        setup.grid,
        setup.opponentCount + 1,
        PuctBot.TERRITORY_WEIGHT.default,
        PuctBot.MOBILITY_WEIGHT.default,
        PuctBot.TRAP_PENALTY.default,
        PuctBot.SEPARATION_BONUS.default,
    ),
) : Bot {
    private val self = setup.self.index
    private val slotCount = setup.opponentCount + 1
    private val ranker = PolicyRanker(setup.grid, slotCount)

    private val values = DoubleArray(slotCount)
    private val ordered = IntArray(MAX_DEPTH * DIRECTIONS)
    private val path = IntArray(MAX_DEPTH)

    private var ply = 0
    private var aborted = false

    /** Configured depth, but only after that whole logical search completed. */
    internal var lastCompletedDepth: Int = 0
        private set

    /** Whether an unaffordable static leaf made this decision use the root policy. */
    internal var lastFallbackUsed: Boolean = false
        private set

    /** Whether the live root had zero or one legal direction and therefore needed no ranking. */
    internal var lastForced: Boolean = false
        private set

    /** Paid [TerritoryEval] leaves completed during the last decision. */
    internal var lastStaticLeaves: Int = 0
        private set

    /** Game outcomes read for free during the last decision. */
    internal var lastTerminalLeaves: Int = 0
        private set

    init {
        require(requestedDepth in 1..MAX_DEPTH) {
            "fixed depth must be in 1..$MAX_DEPTH, was $requestedDepth"
        }
    }

    override fun chooseMove(turn: Turn): Decision {
        resetInstrumentation()

        val legal = turn.legalMoves
        if (legal.isEmpty) {
            lastForced = true
            return Decision.Move(Direction.NORTH)
        }
        legal.singleOrNull()?.let {
            lastForced = true
            return Decision.Move(it)
        }

        val rootCount = ranker.orderInto(turn.board, turn.self, legal, ordered, 0)
        val fallback = Direction.entries[ordered[0]]
        val arena = turn.scratch.playout(FREE)
        check(arena.outcome == null) { "a live root could not open a free scratch playout" }

        aborted = false
        ply = 0
        val chosen = searchRoot(turn, arena, rootCount, fallback)
        if (aborted) {
            lastFallbackUsed = true
            return Decision.Move(fallback)
        }

        lastCompletedDepth = requestedDepth
        return Decision.Move(chosen)
    }

    override fun toString(): String = "FixedDepthBot($requestedDepth)"

    private fun resetInstrumentation() {
        lastCompletedDepth = 0
        lastFallbackUsed = false
        lastForced = false
        lastStaticLeaves = 0
        lastTerminalLeaves = 0
    }

    private fun searchRoot(
        turn: Turn,
        arena: Playout,
        count: Int,
        fallback: Direction,
    ): Direction {
        var best = fallback
        var alpha = -INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[i]]
            arena.advance(direction)
            path[0] = direction.ordinal
            ply = 1

            val result = arena.outcome
            val value = if (result != null) {
                lastTerminalLeaves++
                terminalValue(result)
            } else {
                search(
                    turn = turn,
                    arena = arena,
                    remainingDepth = requestedDepth - 1,
                    alphaIn = alpha,
                    betaIn = INFINITE,
                    prune = requestedDepth >= SHIPPED_MAX_DEPTH,
                )
            }

            ply = 0
            if (aborted) {
                return fallback
            }
            arena.undo()

            if (value > alpha) {
                alpha = value
                best = direction
            }
        }
        return best
    }

    private fun search(
        turn: Turn,
        arena: Playout,
        remainingDepth: Int,
        alphaIn: Double,
        betaIn: Double,
        prune: Boolean,
    ): Double {
        if (remainingDepth == 0) {
            return appraise(turn)
        }

        val mover = arena.toAct
        val maximizing = mover.index == self
        val here = ply
        val base = here * DIRECTIONS
        val count = ranker.orderInto(arena.board, mover, arena.board.legalMoves(mover), ordered, base)

        var alpha = alphaIn
        var beta = betaIn
        var best = if (maximizing) -INFINITE else INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[base + i]]
            arena.advance(direction)
            path[here] = direction.ordinal
            ply = here + 1

            val result = arena.outcome
            val value = if (result != null) {
                lastTerminalLeaves++
                terminalValue(result)
            } else {
                search(turn, arena, remainingDepth - 1, alpha, beta, prune)
            }

            ply = here
            if (aborted) {
                return DRAW
            }
            arena.undo()

            if (maximizing) {
                if (value > best) {
                    best = value
                    if (value > alpha) {
                        alpha = value
                    }
                }
            } else if (value < best) {
                best = value
                if (value < beta) {
                    beta = value
                }
            }

            if (prune && alpha >= beta) {
                break
            }
        }
        return best
    }

    /** Pays at the live root, then replays the path that the reset discarded. */
    private fun appraise(turn: Turn): Double {
        val arena = turn.scratch.playout(eval.cost)
        if (arena.outcome != null) {
            aborted = true
            return DRAW
        }

        for (i in 0 until ply) {
            arena.advance(Direction.entries[path[i]])
        }
        eval.valuesInto(arena, values)
        lastStaticLeaves++
        return paranoidMargin()
    }

    private fun paranoidMargin(): Double {
        var rival = LeafEval.LOSS
        for (slot in 0 until slotCount) {
            if (slot != self && values[slot] > rival) {
                rival = values[slot]
            }
        }
        return values[self] - rival
    }

    private fun terminalValue(outcome: MatchOutcome): Double = when {
        outcome.isDraw -> DRAW
        outcome.winner.index == self -> MATE - ply
        else -> -(MATE - ply)
    }

    private companion object {
        const val SHIPPED_MAX_DEPTH = 3
        const val MAX_DEPTH = 5
        val DIRECTIONS = Direction.entries.size

        const val FREE = 0
        const val DRAW = 0.0
        const val MATE = 1000.0
        const val INFINITE = 2000.0
    }
}
