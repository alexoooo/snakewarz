package ao.snakewarz.match.tournament

import kotlin.math.abs
import kotlin.math.log10

/**
 * Fits a rating to every contestant in [table] by maximum likelihood.
 *
 * The model is Bradley-Terry: each contestant has a strength, and the chance one beats another is
 * that one's share of the two strengths. It is fitted by minorization-maximization — the Zermelo
 * iteration — which is half a dozen lines, needs no derivatives and no matrix, and climbs the
 * likelihood on every step rather than hoping to.
 *
 * ```
 * W_i  = wins_i + draws_i / 2
 * N_ij = games between i and j
 * pi_i <- (W_i + prior/2) / ( SUM_j N_ij / (pi_i + pi_j)  +  prior / (pi_i + 1) )
 * ```
 *
 * ### A draw is half a win, because that is what the rest of the project already says
 *
 * This is not a draw model — a proper one would give drawing its own parameter — and where draws are
 * common it shrinks the spread between ratings a little. It is still the right choice here, because
 * [TournamentTable.scoreRate] already calls a draw half a point and a ladder whose two summaries
 * disagreed about what a draw is worth would be worse than one that is slightly conservative.
 *
 * ### The phantom is what makes the answer finite
 *
 * Left alone, this fit has no answer for a contestant that never lost: its likelihood keeps climbing
 * as the strength grows, forever. [prior] adds one drawn game against an imaginary opponent of fixed
 * strength `1`, which bounds every contestant from both sides and costs a bot with a real record
 * almost nothing.
 *
 * That phantom is also why **nothing is rescaled inside the loop**. Without it the likelihood does
 * not care about the overall scale and re-centring each pass would be harmless; with it the scale
 * means something, and re-centring would walk off the fixed point and make the answer depend on how
 * many iterations were run. The centring happens once, at the end, to the Elo figures only.
 *
 * ### What it cannot tell you, it says rather than hides
 *
 * The fit is only identifiable where the results connect — strongly connect, which is a stronger
 * condition than it sounds: A having beaten B twenty times with no draws leaves the two weakly
 * connected and the pair still unbounded. So the win digraph's strongly-connected components are
 * computed, and anything alone in one, or outside the largest, is marked
 * [Ratings.priorDetermined]. Those ratings come from [prior], not from a game.
 */
public fun fitRatings(table: TournamentTable, prior: Double = DEFAULT_PRIOR): Ratings {
    require(prior > 0.0) { "the prior is what keeps an undefeated contestant finite, was $prior" }

    val size = table.size
    val strengths = DoubleArray(size) { 1.0 }
    val score = DoubleArray(size) { table.wins(it) + table.draws(it) / 2.0 }
    val played = IntArray(size) { table.played(it) }

    for (pass in 0 until MAX_ITERATIONS) {
        if (iterate(table, strengths, score, prior) < TOLERANCE) {
            break
        }
    }

    val components = stronglyConnectedComponents(table)
    return Ratings(
        contestants = table.contestants,
        strengths = strengths,
        elo = centred(strengths, played),
        played = played,
        components = components,
        determinedByPrior = BooleanArray(size) { played[it] == 0 || alone(components, it) },
    )
}

/** One drawn game against the phantom: enough to bound the fit, small enough to vanish beside a record. */
public const val DEFAULT_PRIOR: Double = 1.0

/** Far more passes than convergence needs, so the cap is a backstop and never the thing that stopped it. */
private const val MAX_ITERATIONS = 10_000

/** On `log`-scale movement, so it means the same thing at every strength. */
private const val TOLERANCE = 1e-12

/**
 * One pass, returning how far the largest strength moved on a log scale.
 *
 * Summed by index and never over a map, so the floating-point result is the same on every run and
 * every target — the ordering of a sum is not an implementation detail here (SW-01).
 */
private fun iterate(table: TournamentTable, strengths: DoubleArray, score: DoubleArray, prior: Double): Double {
    var moved = 0.0

    for (one in strengths.indices) {
        var denominator = prior / (strengths[one] + 1.0)
        for (other in strengths.indices) {
            if (other != one) {
                denominator += table.played(one, other) / (strengths[one] + strengths[other])
            }
        }

        val updated = (score[one] + prior / 2.0) / denominator
        val step = abs(updated - strengths[one]) / strengths[one]
        if (step > moved) {
            moved = step
        }
        strengths[one] = updated
    }

    return moved
}

/**
 * The strengths as Elo, shifted so those who played average zero.
 *
 * A display transform and nothing more: the fit itself lives in [strengths], and this is the only
 * place a logarithm is involved. Contestants with no games are left at zero and marked unmeasured
 * rather than being allowed to drag the mean.
 */
private fun centred(strengths: DoubleArray, played: IntArray): DoubleArray {
    val elo = DoubleArray(strengths.size) { Ratings.SCALE * log10(strengths[it]) }

    var sum = 0.0
    var counted = 0
    for (index in elo.indices) {
        if (played[index] > 0) {
            sum += elo[index]
            counted++
        }
    }
    if (counted == 0) {
        return DoubleArray(strengths.size)
    }

    val mean = sum / counted
    return DoubleArray(elo.size) { if (played[it] > 0) elo[it] - mean else 0.0 }
}

/** Whether [contestant] shares its component with nobody, or with fewer than the largest group does. */
private fun alone(components: IntArray, contestant: Int): Boolean {
    val sizes = IntArray(components.size)
    for (component in components) {
        sizes[component]++
    }

    var largest = 0
    for (index in sizes.indices) {
        if (sizes[index] > sizes[largest]) {
            largest = index
        }
    }
    return components[contestant] != largest || sizes[largest] == 1
}

/**
 * Groups of contestants every one of which can beat its way round to every other.
 *
 * Tarjan's algorithm, iterative so a deep field cannot overflow a stack, over the digraph with an
 * edge `i -> j` whenever `i` has beaten `j` — a draw giving both directions, since it is evidence
 * neither can run away from the other. Components are numbered by the lowest contestant index they
 * contain, so the numbering is a function of the results and not of the traversal.
 */
private fun stronglyConnectedComponents(table: TournamentTable): IntArray {
    val size = table.size
    val index = IntArray(size) { UNVISITED }
    val lowLink = IntArray(size)
    val onStack = BooleanArray(size)
    val stack = ArrayDeque<Int>()
    val component = IntArray(size) { UNVISITED }
    var counter = 0

    for (root in 0 until size) {
        if (index[root] != UNVISITED) {
            continue
        }

        // (vertex, next neighbour to try) -- the recursion, made explicit.
        val calls = ArrayDeque<IntArray>()
        calls.addLast(intArrayOf(root, 0))
        index[root] = counter
        lowLink[root] = counter
        counter++
        stack.addLast(root)
        onStack[root] = true

        while (calls.isNotEmpty()) {
            val frame = calls.last()
            val vertex = frame[0]

            if (frame[1] < size) {
                val next = frame[1]
                frame[1]++
                if (next == vertex || !beatsOrDraws(table, vertex, next)) {
                    continue
                }

                if (index[next] == UNVISITED) {
                    index[next] = counter
                    lowLink[next] = counter
                    counter++
                    stack.addLast(next)
                    onStack[next] = true
                    calls.addLast(intArrayOf(next, 0))
                } else if (onStack[next]) {
                    lowLink[vertex] = minOf(lowLink[vertex], index[next])
                }
                continue
            }

            calls.removeLast()
            calls.lastOrNull()?.let { parent -> lowLink[parent[0]] = minOf(lowLink[parent[0]], lowLink[vertex]) }

            if (lowLink[vertex] == index[vertex]) {
                var lowest = vertex
                val members = mutableListOf<Int>()
                while (true) {
                    val member = stack.removeLast()
                    onStack[member] = false
                    members += member
                    if (member < lowest) {
                        lowest = member
                    }
                    if (member == vertex) {
                        break
                    }
                }
                for (member in members) {
                    component[member] = lowest
                }
            }
        }
    }

    return component
}

private fun beatsOrDraws(table: TournamentTable, one: Int, other: Int): Boolean =
    table.wins(one, other) > 0 || table.draws(one, other) > 0

private const val UNVISITED = -1
