package ao.snakewarz.botapi.registry

import kotlin.jvm.JvmInline

/**
 * A bot's public name, as a stable lowercase slug.
 *
 * Ids are written into the replay format, one per slot, which makes them **permanent**: renaming a
 * released bot breaks every replay URL ever shared for it. Name for behaviour rather than for
 * lineage — `uct` and `flat-monte-carlo`, never `uct-v2` — and then leave the name alone. Tuning a
 * constant is the same bot; a different algorithm is a new slug.
 *
 * The charset is deliberately narrow so that a slug is always safe in a URL, a filename and a header
 * field without any escaping anywhere.
 */
@JvmInline
public value class BotId(public val slug: String) {
    init {
        require(slug.isNotEmpty()) { "a bot id must not be empty" }
        require(slug.length <= MAX_LENGTH) { "a bot id must be at most $MAX_LENGTH characters, was '$slug'" }
        require(slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
            "a bot id must be lowercase letters, digits and hyphens, was '$slug'"
        }
    }

    override fun toString(): String = slug

    public companion object {
        /** Bounded so a decoder can reject a corrupt payload before allocating from it. */
        public const val MAX_LENGTH: Int = 32
    }
}
