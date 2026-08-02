package ao.snakewarz.app

/**
 * Where each shipped bot's picture lives, which is the half of `Portraits` that `:ui` cannot know.
 *
 * A **relative** path, because the site is served out of a GitHub Pages project subdirectory and an
 * absolute one would resolve against the domain root and 404 there.
 *
 * The list is written out rather than derived from `ShippedBots`, and that is the point of the seam
 * rather than a shortcut: it is a statement about what is in `resources/art/portrait/`, and a registry
 * cannot make it. A bot registered with no art answers `null` and `:ui` draws it an identicon, so
 * the failure mode is a working face rather than a broken image — `PortraitUrlTest` is what still
 * tells somebody the two have drifted.
 */
internal fun portraitUrl(key: String): String? =
    if (key in SHIPPED_PORTRAITS) "art/portrait/$key.webp" else null

/**
 * One generic file per shipped bot, plus regular and defeated keys for seven campaign characters.
 *
 * These are **frozen identifiers** — a released slug is what a replay URL carries — so a file named
 * after one keeps pointing at the same bot for as long as the link does.
 *
 * `internal` rather than private for one reader, `PortraitUrlTest`: a membership test cannot be
 * enumerated, and the drift worth catching runs both ways — a bot registered with no art, and a file
 * left behind by a bot that was retired.
 */
internal val SHIPPED_PORTRAITS: Set<String> = setOf(
    "random",
    "wallhug",
    "space",
    "pressure",
    "chase",
    "flat-monte-carlo",
    "uct",
    "puct",
    "alphabeta",
    "cartographer",
    "lookahead",
    "burninhell",
    "gauntlet-hunter",
    "gauntlet-hunter-defeated",
    "gauntlet-cartographer",
    "gauntlet-cartographer-defeated",
    "gauntlet-lookout",
    "gauntlet-lookout-defeated",
    "gauntlet-gambler",
    "gauntlet-gambler-defeated",
    "gauntlet-student",
    "gauntlet-student-defeated",
    "gauntlet-planner",
    "gauntlet-planner-defeated",
    "gauntlet-final-boss",
    "gauntlet-final-boss-defeated",
)
