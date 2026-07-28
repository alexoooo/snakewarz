package ao.snakewarz.lab.log

/**
 * Which matches are worth keeping the moves of.
 *
 * A replay is an order of magnitude larger than everything else the log records — a game that runs
 * the turn limit out is over a kilobyte encoded — and no analysis reads one. What reads one is a
 * person, opening a single match to see what went wrong. So the default keeps the matches somebody
 * might want to watch and a sweep can turn even those off.
 */
internal enum class Replays {
    /** Keep none. For sweeps, where the log is measured in hundreds of thousands of matches. */
    NONE,

    /** Keep the matches somebody won, which are the ones a loss is diagnosed from. */
    DECISIVE,

    /** Keep everything, draws included. */
    ALL,
}
