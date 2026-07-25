package ao.snakewarz.botapi

/**
 * A bot's private board to think on, handed out already positioned at the live match.
 *
 * There is exactly **one** playout per scratch: [playout] returns the same instance every time,
 * reset to the current position. A search does not need two at once — it descends, simulates and
 * unwinds — and a pool with no release call cannot be made safe, so it is not offered.
 */
public interface Scratch {
    /** The playout, reset to the live position. Invalidates whatever the previous call returned. */
    public fun playout(): Playout
}
