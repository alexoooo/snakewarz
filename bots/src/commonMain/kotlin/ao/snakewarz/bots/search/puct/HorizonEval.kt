package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * [SurvivalEval] with the one premise under it that the shipped rules do not grant taken out.
 *
 * That class is named for moves and counts squares, and says so: *"the factor is the same for
 * everybody and this reads shares and margins, so it cancels exactly and is not applied"*. The factor
 * cancels only where it is a factor. [FillableSpace] measures a walk that never revisits a square,
 * and a snake here revisits constantly — the tail retracts on alternating turns — so the gap between
 * squares and moves is a flat two in an open room, less than that down a corridor, and nothing at all
 * for a snake too long to turn round. A ratio that moves with the shape does not cancel, and the
 * shapes it moves between are the two sides of every territory decision in the middle game.
 *
 * The consequence is worth knowing before reading a batch: where the ratio *is* flat — an opening,
 * both snakes with a whole open block in front of them — this and [SurvivalEval] pick the same move,
 * and they play the same match on about two boards in five. The difference is a late-game one, so
 * measure it late or measure it against a field. `PuctBotTest` carries the count.
 *
 * So this asks [SurvivalHorizon] for moves where [SurvivalEval] asks [FillableSpace] for squares, and
 * changes nothing else: same sweep, same phase change, same four weights, same scale. **One variable**
 * is the whole point — a batch between the two settings has to be a batch about what is counted.
 *
 * ### What it is worth, measured
 *
 * **Correcting the premise made the bot worse.** Not by a little and not inside the noise:
 * [SurvivalEval], the leaf whose false premise this exists to fix, beats it **145 of 200**. It is at
 * the same time and on the same field **better than [TerritoryEval]**, which is the leaf
 * [SurvivalEval] refines — so the ordering the three of them make is not the one the argument
 * predicts, and each half of that has its own number.
 *
 * `:lab`, 12x12, 200 rounds a pairing at 1,000 evaluations each, both seatings of every seed, over
 * the seven entrants of the shared protocol — 4,200 matches, 3,524 of them distinct games, one sigma
 * about ±7 wins:
 *
 * | | horizon | survival | territory | uct | chase | pressure | space | score |
 * |---|---|---|---|---|---|---|---|---|
 * | horizon | — | 55 | 111 | 133 | 200 | 196 | 200 | 75% |
 * | survival | 145 | — | 122 | 125 | 200 | 196 | 200 | 82% |
 * | territory | 89 | 78 | — | 114 | 199 | 199 | 197 | 73% |
 * | uct | 67 | 75 | 86 | — | 171 | 177 | 198 | 65% |
 *
 * and as ratings over that field, which is what a score column cannot be read as:
 *
 * | entrant | rating | 95% | score |
 * |---|---|---|---|
 * | survival | 371 | +344..+396 | 82% |
 * | **horizon** | **290** | **+265..+317** | **75%** |
 * | territory | 275 | +249..+300 | 73% |
 * | uct | 190 | +162..+221 | 65% |
 *
 * The two sequential tests say the same thing twice as sharply, and the second is the reason this
 * value is registered rather than deleted:
 *
 * | | verdict |
 * |---|---|
 * | `ab puct:eval=survival puct:eval=horizon --elo0 0 --elo1 10` | **NO BETTER**, −185 Elo ±59, 80 boards |
 * | `ab puct puct:eval=horizon --elo0 0 --elo1 10` | **BETTER**, +34 Elo ±19, 800 boards |
 *
 * The first of those was expected to be blind, for the reason the paragraph above gives — and it was
 * not. 33 of its 80 boards split exactly, under the half that raises `AbCommand.blindness`, and on
 * the 47 that did not, this scored 8%. So the boards where the two readings differ are not boards
 * where they are each a little right: they are boards this loses.
 *
 * ### At an equal clock too, which is what makes the first row a real answer
 *
 * 4,015 µs a turn at 1,000 evaluations on the 12x12 against [SurvivalEval]'s 3,890 measured beside
 * it, and 10.9 ms against 11.7 on a 20x20 — [EvaluationCost] carries the table. The two are the same
 * price, so 55-145 is a better-informed leaf losing to a worse-informed one at the same allowance
 * *and* the same millisecond, and there is no smaller-tree excuse to look for. (`rate`'s `us/turn`
 * column on the same field puts this 12% above [SurvivalEval] rather than 3%; that figure is least
 * squares over an eighteen-thread batch and is a ratio between rungs at best, which is why `time`
 * exists and is what the table above is taken from.) Against
 * [TerritoryEval] the comparison is at equal iterations only, this being about 1.6 times its price;
 * `play` at the roughly 640 evaluations that buy the same 2,572 µs is the run that would settle
 * whether 111-89 survives being charged for, and nobody has made it.
 *
 * ### The signature predicted for it is not there, and its reverse is
 *
 * The thesis said fewer `TRAPPED` eliminations at high board fill and a **higher** median fill at the
 * point of loss: a bot that stops declining necks into space it can actually spend should die later
 * and in a fuller maze. `report` over the same field log, both against `puct` at `eval=territory`:
 *
 * | | horizon | survival |
 * |---|---|---|
 * | record against territory | 111W-89L, 56% | 122W-78L, 61% |
 * | losses, whole field | 305 of 1,200 | 212 of 1,200 |
 * | all of them `TRAPPED` | yes | yes |
 * | median moves when losing | 85 | 96 |
 * | median board fill at the loss | 60% | 68% |
 *
 * Every loss on both sides is a `TRAPPED` and neither ever blunders, so "fewer trapped" was never
 * separable from "fewer losses" — and this dies **earlier and in a more open board** than the leaf it
 * corrects. Whatever costs it the games, it is not the one the oracle was pointed at:
 * `SurvivalHorizonTest` establishes that the estimate is a genuine upper bound on moves-until-trapped
 * and tight on the shapes the claim is about, and it is still true. The likeliest reading is that a
 * leaf is read as a **comparison** and this one is a *looser* bound than the square count on exactly
 * the fragmented shapes an endgame is made of — the doubling is exact in an open room and generous
 * everywhere the walk cannot really loop, so a move that shatters a region into pieces the snake will
 * never re-enter still reads as worth two moves a square. Being right about the physics of one region
 * and wrong about the ranking of two is a whole failure mode, and it is the one to test next.
 *
 * That is a result and not a hole: it is what makes [FillableSpace]'s premise being false a thing
 * somebody has now paid to find out about, rather than an argument that gets made again.
 */
internal class HorizonEval(
    grid: Grid,
    private val slotCount: Int,
    /** How far a share of the moves left moves the reading away from even, while contested. */
    private val territoryWeight: Double,
    /** How far having more ways out than the average moves it, on the same board. */
    private val mobilityWeight: Double,
    /** Taken off a snake with nothing legal left, which is a move from dead however much it owns. */
    private val trapPenalty: Double,
    /** How far toward decided a snake that nobody can reach any more is pushed. */
    private val separationBonus: Double,
) : LeafEval {
    private val space = TempoOwnership(grid, slotCount)
    private val horizon = SurvivalHorizon(grid)

    /** Moves each live snake could still make, refilled every call. */
    private val usable = IntArray(slotCount)

    /** A sweep, and then every region taken apart — and one unit of allowance, like everything else. */
    override val cost: Int get() = EvaluationCost.HORIZON

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        val board = playout.board
        space.measure(board)

        var live = 0
        var totalUsable = 0
        var bestUsable = -1
        var secondUsable = -1

        for (slot in 0 until slotCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                usable[slot] = 0
                continue
            }
            live++

            // The regions are disjoint, so the whole loop walks the free area once between them --
            // which is what keeps this a constant factor over the sweep rather than a factor of the
            // number of snakes.
            val held = horizon.measure(space, slot, snake.head, snake.length, snake.growsOnNextMove)
            usable[slot] = held

            totalUsable += held
            if (held > bestUsable) {
                secondUsable = bestUsable
                bestUsable = held
            } else if (held > secondUsable) {
                secondUsable = held
            }
        }

        if (live <= 1) {
            // PuctBot never asks -- the board's own outcome is non-null the moment one snake is left,
            // and a real result is read directly. A unit test can call this, and an answer beats
            // dividing by the number of survivors.
            for (slot in 0 until slotCount) {
                into[slot] = if (board.snake(SnakeId(slot)).alive) LeafEval.WIN else LeafEval.LOSS
            }
            return
        }

        val fair = 1.0 / live

        for (slot in 0 until slotCount) {
            val id = SnakeId(slot)
            if (!board.snake(id).alive) {
                into[slot] = LeafEval.LOSS
                continue
            }

            val liberties = board.legalMoves(id).size

            val ground = if (space.isolated(slot)) {
                // Nobody can reach this snake any more, so the only question left is whose room runs
                // out first -- and that is a race in moves, which is the one quantity here that is
                // measured rather than stood in for. Graded rather than a step, for the reason
                // TerritoryEval records: a step leaves the search no gradient in exactly the phase
                // this is a space-filling puzzle.
                val rival = if (usable[slot] == bestUsable) secondUsable else bestUsable
                val pool = usable[slot] + rival
                val margin = if (pool == 0) 0.0 else (usable[slot] - rival).toDouble() / pool

                separationBonus * margin
            } else {
                // Still a fight. An even split reads even whatever the field size, so this is a
                // departure from fair rather than an absolute quantity.
                val share = if (totalUsable == 0) fair else usable[slot].toDouble() / totalUsable

                territoryWeight * ((share - fair) * live).coerceIn(-1.0, 1.0)
            }

            // Every term is a signed fraction of the half-range, so a weight reads as "how much of
            // the distance from even to decided this term may claim" and the two branches are on one
            // scale -- TerritoryEval's convention, kept so that a weight means the same thing at
            // every setting of `eval`.
            val mobility = mobilityWeight * (liberties / LIBERTIES * 2.0 - 1.0)
            var value = LeafEval.EVEN + LeafEval.EVEN * (ground + mobility)

            if (liberties == 0) {
                // A snake hemmed in now does not get to spend what it owns. A penalty rather than an
                // outright loss, because it is not certain -- an adjacent tail retracting can hand a
                // square back before this snake's turn comes.
                value -= trapPenalty
            }

            into[slot] = value.coerceIn(LeafEval.LOSS, LeafEval.WIN)
        }
    }

    override fun toString(): String = "HorizonEval"

    private companion object {
        /** Ways out of a square, so `liberties / LIBERTIES` is a fraction of the most there can be. */
        val LIBERTIES = Direction.entries.size.toDouble()
    }
}
