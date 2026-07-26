package ao.snakewarz.match

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry

/**
 * Every bot in [delegate], plus one seat a human can sit in.
 *
 * The driver takes exactly one [BotRegistry] and resolves every slot through it, so a human seat has
 * to *be* a registry entry rather than a special case in `Match`. Composing it here rather than in
 * `:bots` is deliberate twice over: the shipped registry is the thing CI runs the bot contract suite
 * against, and that suite requires that no entry claims to be interactive — a search bot that stalls
 * has malfunctioned. So the human belongs on the outside of that registry, not inside it.
 *
 * Every interactive slot reads the **same** [buffer], because there is only one keyboard. A match
 * therefore takes at most one human; `:ui` offers the seat for one slot only.
 *
 * The default [StallPolicy] is [StallPolicy.WAIT_FOR_INPUT], which is what makes a match with a
 * person in it turn-based. It is a default rather than a caller's decision because `:ui` reads
 * `Match.interactive` and stops the clock on the strength of it: a registry composed with
 * [StallPolicy.CONTINUE_STRAIGHT] and shown in a view that expects a person to park would run
 * itself.
 */
public class PlayableRegistry(
    private val delegate: BotRegistry,
    buffer: InputBuffer,
    stallPolicy: StallPolicy = StallPolicy.WAIT_FOR_INPUT,
) : BotRegistry {
    private val human = BotEntry(HUMAN_ID, HUMAN_DISPLAY_NAME, BotFactory { InteractiveBot(buffer, stallPolicy) })

    override val entries: List<BotEntry> = buildList {
        add(human)
        for (entry in delegate.entries) {
            require(entry.id != HUMAN_ID) { "'$HUMAN_ID' is reserved for the human seat" }
            add(entry)
        }
    }

    override fun get(id: BotId): BotEntry? = if (id == HUMAN_ID) human else delegate[id]

    override fun toString(): String = "PlayableRegistry(${entries.size})"

    public companion object {
        /**
         * The slug a human slot is recorded under. **Frozen**, like every released bot id: it is
         * written into the header of every replay of a game somebody played themselves.
         *
         * A replay carrying it decodes and plays back perfectly — playback substitutes a scripted
         * stand-in for every slot regardless of slug. What it will *not* do is survive
         * `MatchRecord.verify`, and rightly so: re-running a human is not a thing a registry can do.
         */
        public val HUMAN_ID: BotId = BotId("human")

        public const val HUMAN_DISPLAY_NAME: String = "You"
    }
}
