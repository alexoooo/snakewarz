package ao.sw.engine.player;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.v2.Snake;

import java.awt.*;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a player.
 */
public class PlayerAvatar
        implements Player,
                   PlayerDisplay,
                   Comparable<PlayerAvatar>
{
    //--------------------------------------------------------------------
    private static final AtomicInteger lastIndex = new AtomicInteger(0);

    private final int index;
    private final PlayerWrapper playerDeleget;
    private final PlayerDisplay displayDeleget;


    //--------------------------------------------------------------------
    public PlayerAvatar( Player player )
    {
        this( player, BasicPlayerDisplay.nextInstance() );
    }
    public PlayerAvatar( Player player, PlayerDisplay skin )
    {
        index          = lastIndex.getAndIncrement();
        displayDeleget = skin;
        playerDeleget  = PlayerWrapper.wrap(player);
    }


    //--------------------------------------------------------------------
    public Player coreDeleget()
    {
        return playerDeleget.deleget();
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return playerDeleget.toString();
    }

    public int hashCode()
    {
        return index;
    }

    public boolean equals(Object obj)
    {
        if (obj == null) return false;
        if (playerDeleget.equals(obj)) return true;
        if (! getClass().equals( obj.getClass() )) return false;

        PlayerAvatar other = (PlayerAvatar) obj;

        return (index == other.index);
        //return (index == other.index) ||
        //        (deleget == other.deleget);
    }

    public int compareTo( PlayerAvatar other )
    {
        return (index < other.index ? -1 :
                (index > other.index ? 1 : 0));
    }


    //-----------------------------------------------------------------------------
    public void startThinking()
    {
        playerDeleget.startThinking();
    }

    public void stopThinking()
    {
        playerDeleget.stopThinking();
    }

    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier yourMove,
            Collection<Snake> others)
    {
        playerDeleget.makeMove(board, you, yourMove, others);
    }
    

    //-----------------------------------------------------------------------------
    public Image head()
    {
        return displayDeleget.head();
    }

    public Image body()
    {
        return displayDeleget.body();
    }

    public Image tail()
    {
        return displayDeleget.tail();
    }

    public Image ghost()
    {
        return displayDeleget.ghost();
    }
}
