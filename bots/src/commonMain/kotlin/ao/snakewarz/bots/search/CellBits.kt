package ao.snakewarz.bots.search

import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView

/**
 * A set of squares held as one bit per padded square, so a whole board is a handful of `Long`s and
 * one breadth-first layer costs a shift and a mask rather than a queue.
 *
 * Every sweep in this module asks the same question of a board — spread out from here, stop at
 * anything occupied — and a queue answers it one square at a time. Sixty-four squares fit in a
 * `Long`, so the same layer of the same sweep is `(bits shl 1) or (bits ushr 1) or (bits shl stride)
 * or (bits ushr stride)`, masked by what is free. The cost stops being the number of squares reached
 * and becomes the number of layers times the number of words, which on the boards this game is
 * played on is several times less work for the same answer.
 *
 * ### The padded grid is what makes the shift legal
 *
 * A bit position **is** a [Cell] index, so the four neighbours are the index plus or minus one and
 * plus or minus [Grid.stride]. That would wrap a row into the next one — except that `Occupancy`
 * marks the whole border ring as wall, so the only squares a set ever holds are playable ones, and a
 * step of one from a playable square lands in the same padded row whichever way it goes. The wrap is
 * not masked away; it cannot happen. Vertical steps land in the padding rows, which are wall and so
 * never free.
 *
 * ### Why the shifts are written with a spare shift in them
 *
 * A step of [Grid.stride] bits crosses `stride / 64` whole words plus a remainder, and the word that
 * spills in is shifted by `64 - remainder` — which is a shift of 64 when the remainder is zero, and
 * both targets mask a `Long` shift count to six bits, so `64` would mean `0` and drag in a whole
 * word of somebody else's squares. `ushr 1 ushr (63 - remainder)` is the same shift for every
 * non-zero remainder and is exactly zero at zero, which keeps one loop where a special case would
 * otherwise need a second. A `stride` divisible by 64 is reachable — `MatchSetup.MAX_SIDE` is 256,
 * so 62, 126 and 190 columns all land on it.
 *
 * ### What it bought, in the currency that counts
 *
 * Not one move changed — every golden hash passes unedited and `OwnershipEquivalenceTest` pins the
 * two sweeps byte for byte — so the whole of the gain is iterations bought at an unchanged clock, and
 * a speedup that is never spent is worth nothing. Measured a turn at 1,000 evaluations, each seed
 * timed against the same seed on the pre-bitboard build back to back so the two see one machine
 * state, with `uct` carried through as a control it cannot touch:
 *
 * | entrant | 12x12 | 20x20 |
 * |---|---|---|
 * | `puct` ([TerritoryEval]) | **1.59x** | **2.13x** |
 * | `puct:eval=survival` | 1.18x | 1.22x |
 * | `uct`, control | 1.00x | 1.00x |
 *
 * A paired design is not a nicety here: a desktop under a batch drifts by half over an afternoon,
 * which is larger than the effect on the dearer evaluation. The control is what says the design
 * works, and an unpaired block of the same runs put `uct` at 0.82x — drift, read as a finding if
 * nothing had been holding still beside it.
 *
 * Spent as allowance rather than banked, `ab` on fresh boards against the same bot at the old one:
 *
 * | board | pairing | verdict |
 * |---|---|---|
 * | 12x12 | `puct@1,590` against `puct@1,000` | **+105 Elo ±38** over 160 boards |
 * | 20x20 | `puct@2,130` against `puct@1,000` | **+205 Elo ±65** over 100 boards |
 *
 * Both stopped at the sequential test's **upper bound**, so the sign is solid and the magnitude is
 * the generous end of the interval — "at least 10 Elo" is what each actually proved. And both
 * **understate** the gain for a reason worth knowing before quoting them: `PuctBot.CPUCT` was swept
 * at an allowance of 1,000 and an exploration constant trades against how deep the tree gets, so the
 * candidate here is playing a bigger search on a constant tuned for a smaller one. Re-tuning it is a
 * separate experiment and a separate number; it is not a correction to apply to these.
 *
 * ### Fusing the per-layer passes was tried, and it loses on the target this ships to
 *
 * The nine short passes one multi-frontier layer takes — a [copyFrom], a [clear], an [addShared] and
 * an [addAll] per frontier past the first, one more [addAll], and a [settleInto] per frontier — fold
 * into a single word loop, with the two that always follow a lone [spreadFrom] folding in the same
 * way. On the JVM that is worth **1.20-1.29x** on a [SpaceOwnership] sweep and **1.10-1.17x** on a
 * whole `puct` turn — four paired rounds against the passes as they stand, control held at 0.95-0.99x.
 *
 * **In Chrome the same code is half the speed**, and the regression grows with the words a board
 * takes — two paired browser runs, `puct` a turn at the shipped allowance:
 *
 * | | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | the fused form against these passes | 0.91x | 0.66x | **0.50x** |
 * | `uct`, which reads no sweep — the control | 1.01x | 0.96x | 1.01x |
 *
 * `alphabeta:eval=territory` reproduced `puct` to within 3% in both builds, so the leaf-pair gate
 * `AppraisalTape` describes passed on every run. `eval=chamber` and `eval=learned` read 0.93-0.98x:
 * they reach `TempoOwnership`, which at two snakes never takes a multi-frontier layer at all, so the
 * *single*-frontier half of the fusion on its own is a wash there rather than a win.
 *
 * The shape of it, since the numbers do not say it: a fused loop keeps six `LongArray`s live where
 * five short loops keep two each, and what fusing buys on the JVM — one loop instead of five, and no
 * reload of what the previous pass just wrote — is not a cost a Kotlin/Wasm loop was paying in the
 * first place. **So the passes stay separate.** Anyone rewriting them measures in the browser first
 * and on the JVM second; here the two disagreed in direction and not in degree.
 *
 * One instance per role per bot per match, sized once from [Grid.cellCount] and reused forever, so
 * nothing here allocates after the constructor. The margin words at each end are permanently zero
 * and exist so that a neighbour step can index off either end of the board without a bounds test.
 */
internal class CellBits(private val grid: Grid) {
    /** Words the padded board itself needs, margins excluded. */
    private val span = (grid.cellCount + 63) ushr 6

    /** Whole words a vertical step crosses, and the bits left over after them. */
    private val strideWords = grid.stride ushr 6
    private val strideBits = grid.stride and 63

    /** The complement of [strideBits] as a pair of shifts — see the class KDoc. */
    private val strideCarry = 63 - strideBits

    /** Zero words on each side, so a vertical step off the first or last row indexes something. */
    private val margin = strideWords + 1

    private val words = LongArray(margin + span + margin)

    /** One past the last word of the board proper. */
    private val end = margin + span

    fun clear() {
        words.fill(0L, margin, end)
    }

    fun add(cell: Cell) {
        val index = cell.index
        val at = margin + (index ushr 6)
        words[at] = words[at] or (1L shl (index and 63))
    }

    fun remove(cell: Cell) {
        val index = cell.index
        val at = margin + (index ushr 6)
        words[at] = words[at] and (1L shl (index and 63)).inv()
    }

    fun contains(cell: Cell): Boolean {
        val index = cell.index
        return words[margin + (index ushr 6)] and (1L shl (index and 63)) != 0L
    }

    fun addAll(other: CellBits) {
        val from = other.words
        for (i in margin until end) {
            words[i] = words[i] or from[i]
        }
    }

    /** Adds every square [first] and [second] both hold — the squares two frontiers arrive on together. */
    fun addShared(first: CellBits, second: CellBits) {
        val a = first.words
        val b = second.words
        for (i in margin until end) {
            words[i] = words[i] or (a[i] and b[i])
        }
    }

    fun copyFrom(other: CellBits) {
        other.words.copyInto(words)
    }

    /**
     * Drops every square in [ties] from this set and adds what is left to [holder], answering
     * whether anything was left.
     *
     * One pass, where removing, crediting and testing separately are three — and a sweep does this
     * per snake per layer, which is where the passes over a four-word board add up.
     */
    fun settleInto(ties: CellBits, holder: CellBits): Boolean {
        val dropped = ties.words
        val into = holder.words

        var any = 0L
        for (i in margin until end) {
            val kept = words[i] and dropped[i].inv()
            words[i] = kept
            into[i] = into[i] or kept
            any = any or kept
        }
        return any != 0L
    }

    fun count(): Int {
        var total = 0
        for (i in margin until end) {
            total += words[i].countOneBits()
        }
        return total
    }

    /**
     * Writes the index of every square in this set into [into] in ascending order, and returns how
     * many there were.
     *
     * [into] is a caller-owned buffer of [Grid.cellCount], which no set can overflow. This is the one
     * place a bitmap has to be spelled out square by square, and it exists for the sweeps that hand a
     * per-square reading to something walking the board rather than reading it whole.
     */
    fun cellsInto(into: IntArray): Int {
        var found = 0
        for (i in margin until end) {
            var word = words[i]
            val base = (i - margin) shl 6
            while (word != 0L) {
                into[found++] = base + word.countTrailingZeroBits()
                word = word and (word - 1)
            }
        }
        return found
    }

    /**
     * Fills this with every free square of [board], and nothing else.
     *
     * A word at a time rather than a playable square at a time: the border ring is never free, so
     * walking it costs one board read apiece and buys a whole word assembled in a register instead of
     * a load, an or and a store per square. This runs once per sweep, which on a leaf evaluation is
     * once per iteration of a search.
     */
    fun freeSquaresOf(board: BoardView) {
        val cells = grid.cellCount
        var index = 0

        for (at in margin until end) {
            val stop = if (index + 64 < cells) index + 64 else cells
            var bits = 0L
            var bit = 0
            while (index < stop) {
                if (board.isFree(Cell(index))) {
                    bits = bits or (1L shl bit)
                }
                index++
                bit++
            }
            words[at] = bits
        }
    }

    /**
     * Fills this with the squares one step out of [source] that lie [within] and are not [excluding]
     * — one whole breadth-first layer, board-wide, for every square in [source] at once.
     *
     * The three masks are separate rather than pre-combined because they change on different
     * schedules: what a sweep may walk on is fixed for the whole sweep, and what it has already taken
     * grows with every layer.
     *
     * Answers whether anything landed, which is what a sweep spins on and what a contact test reads.
     */
    fun spreadFrom(source: CellBits, within: CellBits, excluding: CellBits): Boolean {
        val from = source.words
        val open = within.words
        val taken = excluding.words

        val q = strideWords
        val r = strideBits
        val carry = strideCarry

        var any = 0L
        for (i in margin until end) {
            val here = from[i]
            var layer = (here shl 1) or (from[i - 1] ushr 63)
            layer = layer or (here ushr 1) or (from[i + 1] shl 63)
            layer = layer or (from[i - q] shl r) or (from[i - q - 1] ushr 1 ushr carry)
            layer = layer or (from[i + q] ushr r) or (from[i + q + 1] shl 1 shl carry)

            val landed = layer and open[i] and taken[i].inv()
            words[i] = landed
            any = any or landed
        }
        return any != 0L
    }
}
