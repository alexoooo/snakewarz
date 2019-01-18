package ao.ai;

import ao.ai.sample.path_finder.AStar;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import java.util.Collection;

/**
 *
 */
public abstract class PvpAi implements Player
{
    //--------------------------------------------------------------------
    public PvpAi() {}


    //--------------------------------------------------------------------
    public abstract void startThinking();
    public abstract void stopThinking();


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier thisMove,
            Collection<Snake> others)
    {
        if (others.size() != 1)
        {
            int   minDist      = Integer.MAX_VALUE;
            Snake minDistSnake = null;
            for (Snake opp : others)
            {
                int dist =
                        AStar.pathBetween(
                                board, you.head(), opp.head()
                        ).size();

                if (dist < minDist)
                {
                    minDist      = dist;
                    minDistSnake = opp;
                }
            }

            makeMove(board, you, thisMove,
                     minDistSnake);
        }
        else
        {
            makeMove(board, you, thisMove,
                     others.iterator().next());
        }
    }


    //--------------------------------------------------------------------
    protected abstract void makeMove(
            BoardArrangement board,
            Snake            you,
            MoveSpecifier    thisMove,
            Snake            opp);


    //--------------------------------------------------------------------
    public String toString()
    {
        return getClass().getSimpleName();
    }
}
