package ao.ai.sample;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;
import ao.util.math.rand.Rand;

import java.util.Collection;

public class RandomAi implements Player
{
    //--------------------------------------------------------------------
    private static int nextId = 0;


    //--------------------------------------------------------------------
    private final int id = nextId++;

    private       Direction firstAction;


    //--------------------------------------------------------------------
    public RandomAi() {}


    //--------------------------------------------------------------------
    public void startThinking() {}
    public void stopThinking()  {}


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement  board,
            Snake             you,
            MoveSpecifier     yourMove,
            Collection<Snake> others)
    {
        Direction move =
                Rand.fromList(
                        Direction.availableFrom( board, you.head() ));

        if (firstAction == null)
        {
            firstAction = move;
        }

        yourMove.setDirection( move );
    }


    //--------------------------------------------------------------------
    public Direction firstAction()
    {
        return firstAction;
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return getClass().getName() + "(" + id + ")";
    }
}
