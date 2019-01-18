package ao.sw.control;

import ao.sw.engine.player.Player;

/**
 * Result of game.
 */
public class GameResult
{
    private final Player  WINNER;
    private final int     LENGTH;
    private final boolean SUICIDE;

    public GameResult(
            Player  winner,
            int     length,
            boolean suicide)
    {
        WINNER  = winner;
        LENGTH  = length;
        SUICIDE = suicide;
    }

    public Player winner()
    {
        return WINNER;
    }

    public int length()
    {
        return LENGTH;
    }

    public boolean endedInSuicide()
    {
        return SUICIDE;
    }

    @Override
    public String toString()
    {
        return "winner: " + String.valueOf(WINNER) + "\t" +
               "length: " + LENGTH                 + "\t" +
               "isSuicide: " + SUICIDE;
    }
}