package ao.sw.engine.player;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.v2.Snake;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


//------------------------------------------------------------------------
public class PlayerWrapper implements Player, Comparable<PlayerWrapper>
{
    //--------------------------------------------------------------------
    private static final AtomicInteger lastIndex = new AtomicInteger(0);


    //--------------------------------------------------------------------
    public static PlayerWrapper wrap(Player deleget)
    {
        assert deleget != null;

        return (deleget instanceof PlayerWrapper
                ? (PlayerWrapper) deleget
                : new PlayerWrapper(deleget));
    }


    //--------------------------------------------------------------------
    private final int index;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final Player deleget;


    //--------------------------------------------------------------------
    private PlayerWrapper(Player deleget)
    {
        this.index   = lastIndex.incrementAndGet();
        this.deleget = deleget;
    }


    //--------------------------------------------------------------------
    public Player deleget()
    {
        return deleget;
    }


    //--------------------------------------------------------------------
    public synchronized void startThinking()
    {
        if (! started.compareAndSet(false, true)) return;

        deleget.startThinking();
    }

    public synchronized void stopThinking()
    {
        if (started.get())
        {
            if (stopped.compareAndSet(false, true))
            {
                deleget.stopThinking();
            }
        }
    }


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement  board,
            Snake             you,
            MoveSpecifier     yourMove,
            Collection<Snake> others)
    {
        deleget.makeMove( board, you, yourMove, others);
    }


    //-----------------------------------------------------------------------------
    public int compareTo(PlayerWrapper obj)
    {
        if (obj == null) return -1;

        return (index < obj.index ? -1 :
                (index > obj.index ? 1 : 0));
    }

    public String toString()
    {
        return deleget.toString();
    }

    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (deleget.equals(obj)) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final PlayerWrapper that = (PlayerWrapper) obj;

        return index == that.index;
    }

    public int hashCode()
    {
        return index;
    }
}
