package ao.snakewarz.match.tournament

import ao.snakewarz.match.Match

/**
 * How a tournament turns its contestants into matches.
 *
 * A format is a property of the config, like everything else about the schedule: fixed the moment
 * the [TournamentConfig] exists, and branched on by the driver the way [Match.interactive] is —
 * there is no second driver.
 */
public enum class TournamentFormat {
    /**
     * Every pair of contestants meets head to head, each seed played from both seats.
     *
     * A win-rate matrix is a statement about pairs, and this is the format that fills one in
     * directly: `wins(a, b)` counts matches in which only those two were on the board.
     */
    HEAD_TO_HEAD,

    /**
     * Every contestant in every match, all on the board at once.
     *
     * What one match measures changes with the format: the matrix cell `wins(a, b)` counts matches
     * in which `a` *outlasted* `b`, with everybody else on the board interfering — which is a
     * different question from head to head, and the one a four-way game is actually asking.
     */
    FREE_FOR_ALL,
}
