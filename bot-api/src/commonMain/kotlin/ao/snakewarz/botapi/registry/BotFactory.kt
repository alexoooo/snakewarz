package ao.snakewarz.botapi.registry

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup

/**
 * Makes one bot for one slot of one match.
 *
 * A `fun interface`, so a constructor reference is a factory: `register("random", ::RandomBot)`.
 */
public fun interface BotFactory {
    public fun create(setup: BotSetup): Bot
}
