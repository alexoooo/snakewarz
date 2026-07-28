package ao.snakewarz.lab.arena

import ao.snakewarz.core.grid.Direction

/**
 * A fold over a move stream, so two matches can be told apart without keeping either.
 *
 * The same FNV-1a construction the golden move-stream tests use, and spelled out again rather than
 * shared: `:lab` may not reach into another module's test sources, and what it is *for* here is not
 * pinning a number but counting distinct games — cheaply enough to do it for every match of a batch.
 *
 * Spelled out rather than borrowed from the standard library for the reason the golden tests give:
 * what computes a number that gets compared must not quietly change underneath it.
 */
internal fun moveStreamHash(moves: List<Direction>): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (move in moves) {
        hash = (hash xor (move.ordinal + 1).toLong()) * 0x100000001b3L
    }
    return hash
}
