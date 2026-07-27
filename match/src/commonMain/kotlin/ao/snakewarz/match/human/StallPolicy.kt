package ao.snakewarz.match.human

/** What an [InteractiveBot] does on a turn its player has not supplied a move for. */
public enum class StallPolicy {
    /**
     * Park the match on `StepResult.AwaitingInput` until something is pushed.
     *
     * The default, and what the shipped game plays: a match with a person in it is turn-based, the
     * keyboard is its clock, and the board is never a move ahead of the last thing they asked for.
     * That suits this game, where a single square is usually the whole difference between trapping
     * somebody and trapping yourself, and it makes the pause button unnecessary rather than absent.
     */
    WAIT_FOR_INPUT,

    /**
     * Keep going in the direction the player last chose.
     *
     * The arcade behaviour: real-time, and the match reads as alive whether or not anybody is
     * touching the keyboard. Note that it sustains a heading the player really did pick and never
     * invents one: before the first move there is no heading, so the match waits exactly as
     * [WAIT_FOR_INPUT] would. That distinction is the legacy `MoveTracker` bug in reverse — it
     * seeded an unset move with *the first available direction*, so a bot played a move nobody
     * chose and then repeated it forever.
     */
    CONTINUE_STRAIGHT,
}
