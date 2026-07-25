package ao.ai.da;

import ao.ai.PvpAi;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.v2.Snake;

/**
 *
 */
public class Burninhell extends PvpAi
{
    //--------------------------------------------------------------------
    public void startThinking()
    {

    }

    public void stopThinking()
    {

    }


    //--------------------------------------------------------------------
    protected void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier thisMove,
            Snake opp)
    {
        BoardLocation head = you.head();
        if (Direction.NORTH.isAvailableIn(board, head))
        {
            thisMove.setDirection(Direction.NORTH);
        }
        else if (Direction.SOUTH.isAvailableIn(board, head))
        {
            thisMove.setDirection(Direction.SOUTH);
        }
        else if (Direction.EAST.isAvailableIn(board, head))
        {
            thisMove.setDirection(Direction.EAST);
        }
        else if (Direction.WEST.isAvailableIn(board, head))
        {
            thisMove.setDirection(Direction.WEST);
        }
    }
}
