package ao.snakewarz.bots

/**
 * The allowance an unconfigured match hands every slot: `MatchSetup.DEFAULT_BUDGET_PER_TURN`, typed
 * out by hand because nothing here can read it.
 *
 * `:bots` may not depend on `:match` — not in production and not in a test either, since a test
 * dependency is still an edge in the resolved graph and `:bots:checkModulePurity` walks the test
 * classpath. Nor can the figure move down into `:core` or `:bot-api` where both sides would see it:
 * `BotKnob.Search` declines to carry a default for exactly this reason, that how much a match grants
 * is the match's policy and a bot has no business naming it.
 *
 * So it is a copy, and one copy rather than three is the point of this file — every test that plays
 * at the shipped allowance reads it from here. What holds the two ends together is
 * `MatchSetupTest."the shipped allowance is copied by hand into bots, so moving it is a two-file
 * change"`, which fails the moment the figure on the far side moves and names this constant when it
 * does. Without it the build stays green while the ladder goes on certifying a rung nobody plays.
 *
 * `ReplayCodecTest.SHIPPED_BUDGET` is **not** one of these copies: it records what the constant was
 * when that suite's payload was captured, and it must not follow this one.
 */
internal const val SHIPPED_BUDGET: Int = 1_000
