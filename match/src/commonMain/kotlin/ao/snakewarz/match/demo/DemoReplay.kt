package ao.snakewarz.match.demo

/**
 * A short match that shows what winning looks like, as a `ReplayCodec` payload.
 *
 * It exists because the rules of this game are not the rules people arrive expecting. Playtesters
 * read the board as Pac-Man and tried to *flee* the other snake, which is the losing move: there is
 * nothing to collect, and room is the only thing worth taking. A sentence saying so was already on
 * the page and went unread. Thirty turns of somebody being cornered does not.
 *
 * **A recording rather than a match played on the spot.** `Match.playback` fills every slot with a
 * scripted stand-in, so this costs no bot and no search — nothing a search bot does reaches the boot
 * path, and there is no arithmetic here that could read differently in a browser than on the JVM.
 * It also survives every bot in `:bots` being retuned or deleted, which is the same property that
 * makes a shared `#r=` link outlive the build it was recorded on.
 *
 * **Authored rather than played, and therefore it does not `verify`.** `MatchRecord.verify` re-runs
 * the real bots from the seed and asks whether they still play these moves; they never did. The
 * slugs name real bots so the seats would read sensibly if anything ever showed them, and nothing
 * more should be read into them. A match between two real bots would have been cheaper to obtain and
 * would have ended however the seed decided — a wall bump, a mutual crash, a turn limit — and this
 * has one job, which is too specific to leave to a seed.
 *
 * ### The story, which is the thing to preserve
 *
 * Two snakes on an empty 8x8, the board size the game already opens on. Slot 0 wins and is written
 * first so it takes the seat colour a person plays in, so the snake doing the cornering is the one
 * whose colour means *you*.
 *
 * Slot 1 runs up the right edge and turns west along the top. Slot 0 follows it up, one column
 * inside, then turns west along the second row and stays under it the whole way. Slot 1 reaches the
 * top-left corner with two walls ahead of it, its own body behind it, and slot 0's head immediately
 * below: no legal move, so it is `TRAPPED` rather than merely unlucky. Nothing on the board is a
 * mistake by slot 1 in isolation — it is a snake that ran out of room, which is the whole lesson.
 *
 * `DemoReplayTest` pins that story and not merely the syntax, so a payload swapped for a prettier
 * one has to still end with somebody boxed in.
 */
public object DemoReplay {
    /** Thirty turns; see the class comment for what they show. */
    public const val PAYLOAD: String = "BAQHBwEAAAAAAAAAAoAgAAIFc3BhY2UFY2hhc2UyLQABHqoiAMD8__8HAAEBAA"
}
