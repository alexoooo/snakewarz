package ao.snakewarz.match

/** What an [InteractiveBot] does on a turn its player has not supplied a move for. */
public enum class StallPolicy {
    /**
     * Park the match on `StepResult.AwaitingInput` until something is pushed.
     *
     * Right for a turn-based or a stepped view, and wrong for a live one: a game that visibly
     * stops the instant you take your hand off the keyboard reads as broken rather than as patient.
     */
    WAIT_FOR_INPUT,

    /**
     * Keep going in the direction the player last chose.
     *
     * The default for the live view, and the behaviour every arcade snake has. Note that it
     * sustains a heading the player really did pick and never invents one: before the first move
     * there is no heading, so the match waits exactly as [WAIT_FOR_INPUT] would. That distinction
     * is the legacy `MoveTracker` bug in reverse — it seeded an unset move with *the first
     * available direction*, so a bot played a move nobody chose and then repeated it forever.
     */
    CONTINUE_STRAIGHT,
}
