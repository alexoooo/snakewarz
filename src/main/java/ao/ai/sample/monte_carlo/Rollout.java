package ao.ai.sample.monte_carlo;

import ao.sw.engine.board.Direction;

/**
 *
 */
public class Rollout
{
    //--------------------------------------------------------------------
    private final boolean   isTie;
    private final boolean   nextToActIsWinner;
    private final Direction firstActionOfNextToAct;
    private final int       length;


    //--------------------------------------------------------------------
    public Rollout(boolean   isTie,
                   boolean   nextToActIsWinner,
                   Direction firstActionOfNextToAct,
                   int       length)
    {
        this.isTie                  = isTie;
        this.nextToActIsWinner      = nextToActIsWinner;
        this.firstActionOfNextToAct = firstActionOfNextToAct;
        this.length                 = length;
    }


    //--------------------------------------------------------------------
    public int length()
    {
        return length;
    }

    public boolean isTie()
    {
        return isTie;
    }

    public boolean nextToActIsWinner()
    {
        return nextToActIsWinner;
    }

    public Direction firstActionOfNextToAct()
    {
        return firstActionOfNextToAct;
    }
}
