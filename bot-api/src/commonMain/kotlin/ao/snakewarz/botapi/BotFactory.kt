package ao.snakewarz.botapi

/**
 * Makes one bot for one slot of one match.
 *
 * A `fun interface`, so a constructor reference is a factory: `register("random", ::RandomBot)`.
 */
public fun interface BotFactory {
    public fun create(setup: BotSetup): Bot
}
