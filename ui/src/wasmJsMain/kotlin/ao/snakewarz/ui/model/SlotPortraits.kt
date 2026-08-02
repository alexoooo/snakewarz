package ao.snakewarz.ui.model

import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.ui.render.GauntletVisual
import ao.snakewarz.ui.render.Theme
import ao.snakewarz.ui.render.identicon

/**
 * The face of each seat of a match: shipped art where there is any, and a drawn mark where there is
 * not.
 *
 * The pair is the point. Ten characters is worth drawing, and a bot somebody forks in tomorrow still
 * has to have a face on the day it is registered — so [Portraits] answers for what was deployed
 * beside the page and [identicon] answers for everything else, and nothing here can tell which kind
 * of bot it is looking at.
 *
 * Keyed by **slot** and not by slug, because the fallback is tinted with the seat's trail colour:
 * two unknown bots of the same kind in one match are the same mark in two colours, which is what the
 * scoreboard needs them to be.
 *
 * Built once per [MatchSetup] *and* per theme, never once per frame. A mark carries [Theme.body],
 * which is the same string under either light or dark scheme and moves only when the player picks a
 * different theme — so the sun going down does not rebuild any of these, and choosing Neon does.
 */
internal class SlotPortraits(setup: MatchSetup, portraits: Portraits, theme: Theme, level: Int? = null) {
    private val urls: List<String?> = List(setup.slotCount) { slot ->
        val slug = setup.slots[slot].slug
        if (setup.slots[slot] == PlayableRegistry.HUMAN_ID) {
            null
        } else {
            val key = if (slot == OPPONENT_SLOT) GauntletVisual.at(level)?.portraitKey ?: slug else slug
            portraits.urlFor(key) ?: identicon(slug, theme.body(slot))
        }
    }

    /** The face of the snake in [slot], or `null` where the match has no such seat. */
    operator fun get(slot: Int): String? = urls.getOrNull(slot)

    override fun toString(): String = "SlotPortraits(${urls.size})"

    private companion object {
        const val OPPONENT_SLOT = 1
    }
}
