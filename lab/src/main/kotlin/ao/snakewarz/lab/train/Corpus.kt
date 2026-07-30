package ao.snakewarz.lab.train

import ao.snakewarz.bots.search.learned.PositionFeatures
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.ReplayCodec
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Positions and what became of them, replayed out of the match log.
 *
 * A logged match is a move stream, and a move stream is a whole game that can be walked again for
 * nothing: [ReplayCodec] gives back a record carrying the spawns, the turn order, the rules
 * and the allowances, and [Match.playback] drives it without consulting a bot at all. So a corpus is
 * free of search — the expensive part was paid the day the batch ran — and every phase's field logs
 * are training data nobody had to generate.
 *
 * ### One row per (position, live snake)
 *
 * The label is **did this slot go on to win**, which is exactly the quantity `LeafEval` answers on
 * and exactly what a logistic output is calibrated to. A draw is a half, which is the same reading
 * `LeafEval.EVEN` carries.
 *
 * The bias that comes with it is worth stating: `Replays.DECISIVE` is the log's default, so a run
 * recorded without `--replays all` kept only the games somebody won. Drawn games are the long ones
 * that fill the board and hit the turn limit, and a corpus without them is a corpus that has never
 * seen the position a filling endgame ends in. [drawn] counts what got through so the shortfall is
 * visible rather than assumed.
 *
 * ### Split by game, never by position
 *
 * Consecutive positions of one match differ by a single move and share an outcome, so a row-wise
 * validation split leaks the answer: a model that memorised the training half would score well on a
 * held-out row of the same game. [group] carries the match ordinal and [ValueFit] splits on it.
 *
 * ### And which board each row came off, because a fit is not board-agnostic
 *
 * The feature vector is built out of shares so that the same reading means the same thing on any
 * geometry, and that was taken to mean a fit taken on one board would serve another. It does not: the
 * `LearnedWeights` P4 replaced was fitted `--rows 12 --cols 12` and lost **0.048 of log-loss** to a
 * model of the identical shape refitted on 20x20 positions. So [board] rides along with every row,
 * and a corpus spanning more than one geometry reports its holdout **per board** — one pooled number
 * over a mixture is the reading that hid this for six phases.
 */
internal class Corpus(
    val size: Int,
    /** Readings per row — `PositionFeatures.LENGTH`, carried so nothing has to divide to find it. */
    val width: Int,
    /** [size] rows of [width], laid end to end. */
    val features: DoubleArray,
    val labels: DoubleArray,
    /** Which match each row came from, so a split can hold whole games out. */
    val group: IntArray,
    /** Which of [boards] each row was played on. */
    val board: IntArray,
    /** The geometries present, in the order they were first met — `"12x12"`, and so on. */
    val boards: List<String>,
    val matches: Int,
    val drawn: Int,
    val positionsSeen: Int,
) {
    override fun toString(): String = "Corpus($size rows, $matches matches, ${boards.joinToString("/")})"
}

/**
 * Every directory at or one level under [root] that holds replays, [root] itself included.
 *
 * A phase logs its batches under a name of its own — `.lab/chamber-field`, `.lab/prior-ab` — so a
 * corpus is the union of them rather than whichever one happened to be written last.
 */
internal fun logDirectoriesUnder(root: Path): List<Path> {
    if (!root.isDirectory()) {
        return emptyList()
    }

    val found = mutableListOf<Path>()
    if (root.resolve(REPLAYS).exists()) {
        found.add(root)
    }
    for (child in root.listDirectoryEntries().sorted()) {
        if (child.isDirectory() && child.resolve(REPLAYS).exists()) {
            found.add(child)
        }
    }
    return found
}

/**
 * Walks every replay in [directories] and extracts what will fit in [limit] rows.
 *
 * Matches are visited in an order drawn from [seed] rather than in log order, so a limit that stops
 * short takes a sample across every phase's batches instead of all of the first one. Within a match
 * every [stride]-th position is kept, from an offset that also comes from the seed — consecutive
 * positions differ by one move and carry the same label, so keeping all of them would buy correlated
 * rows at the price of the ones a different game would have contributed.
 */
internal fun corpusFrom(
    directories: List<Path>,
    rows: Int?,
    cols: Int?,
    stride: Int,
    limit: Int,
    seed: Long,
    log: (String) -> Unit,
): Corpus {
    require(stride > 0) { "a stride of $stride keeps nothing" }
    require(limit > 0) { "a corpus of $limit rows is not one" }

    val encoded = mutableListOf<String>()
    for (directory in directories) {
        val logged = MatchLog(directory)
        val replays = logged.replays()
        if (replays.isEmpty()) {
            continue
        }
        encoded += replays.values
        log("[train] ${replays.size} replays under $directory")
    }
    require(encoded.isNotEmpty()) { "no replays found -- a corpus needs a batch logged with --replays all or decisive" }

    val order = IntArray(encoded.size) { it }
    shuffle(order, SplitMix64(seed))

    val width = PositionFeatures.LENGTH
    val features = DoubleArray(limit * width)
    val labels = DoubleArray(limit)
    val group = IntArray(limit)
    val board = IntArray(limit)
    val boards = mutableListOf<String>()
    val readers = LinkedHashMap<String, PositionFeatures>()
    val row = DoubleArray(width)

    var size = 0
    var matches = 0
    var drawn = 0
    var positions = 0
    var skipped = 0

    for (index in order) {
        if (size == limit) {
            break
        }

        val record = try {
            ReplayCodec.decode(encoded[index])
        } catch (malformed: IllegalArgumentException) {
            // A torn final line in a log is a thing that happens to a batch left running overnight,
            // and one unreadable payload is not a reason to abandon fifty thousand good ones.
            skipped++
            continue
        }
        if (rows != null && record.setup.rows != rows) continue
        if (cols != null && record.setup.cols != cols) continue

        val outcome = record.outcome ?: continue
        if (outcome.isDraw) {
            drawn++
        }

        val slots = record.setup.slotCount
        val match = Match.playback(record)
        val reader = readers.getOrPut("${record.setup.rows}x${record.setup.cols}x$slots") {
            PositionFeatures(match.grid, slots)
        }

        val geometry = "${record.setup.rows}x${record.setup.cols}"
        var geometryAt = boards.indexOf(geometry)
        if (geometryAt < 0) {
            geometryAt = boards.size
            boards.add(geometry)
        }

        val offset = SplitMix64(seed + index).nextInt(stride)
        var at = 0
        matches++

        while (match.outcome == null && size < limit) {
            if ((at % stride) == offset) {
                positions++
                reader.measure(match.view)
                for (slot in 0 until slots) {
                    if (!match.view.snake(SnakeId(slot)).alive || size == limit) {
                        continue
                    }
                    reader.into(slot, row)
                    row.copyInto(features, size * width)
                    labels[size] = labelOf(outcome.winner, slot)
                    group[size] = matches
                    board[size] = geometryAt
                    size++
                }
            }
            at++
            if (match.step() == StepResult.AwaitingInput) {
                // A scripted stand-in that has run out of recorded moves *parks* rather than
                // forfeiting, so that a mid-match share link plays back instead of ending in a
                // fabricated loss. Here that is a recording shorter than the outcome it claims, and
                // stopping is the whole of what there is to do about it: the driver refuses a
                // second ask rather than letting this loop run on.
                break
            }
        }
    }

    if (skipped > 0) {
        log("[train] $skipped replays did not decode and were dropped")
    }
    return Corpus(
        size = size,
        width = width,
        features = features.copyOf(size * width),
        labels = labels.copyOf(size),
        group = group.copyOf(size),
        board = board.copyOf(size),
        boards = boards.toList(),
        matches = matches,
        drawn = drawn,
        positionsSeen = positions,
    )
}

/** A win, a loss, or the half a draw is worth — the scale `LeafEval` already answers on. */
private fun labelOf(winner: SnakeId, slot: Int): Double = when {
    winner.isNone -> 0.5
    winner.index == slot -> 1.0
    else -> 0.0
}

/** Fisher-Yates from a seeded stream, because a corpus has to be reproducible from its seed. */
private fun shuffle(order: IntArray, rng: SplitMix64) {
    for (i in order.size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val swap = order[i]
        order[i] = order[j]
        order[j] = swap
    }
}

private const val REPLAYS = "replays.tsv"
