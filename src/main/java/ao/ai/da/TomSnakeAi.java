package ao.ai.da;

import ao.ai.PvpAi;
import ao.ai.sample.ForkPathAi;
import ao.ai.sample.RandomAi;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.v2.Snake;
import ao.util.math.rand.Rand;

import java.util.Collections;

/**
 *
 */
public class TomSnakeAi extends PvpAi
{
    //--------------------------------------------------------------------
    private Direction lastDir;

    //--------------------------------------------------------------------
    public void startThinking() {}
    public void stopThinking()  {}


    //--------------------------------------------------------------------
    protected void makeMove(
            BoardArrangement board,
            Snake            you,
            MoveSpecifier    thisMove,
            Snake            opp)
    {
        if (Rand.nextBoolean(2.0/10))
        {
            new ForkPathAi().makeMove(
                    board, you, thisMove,
                    Collections.singletonList(opp));
            return;
        }
       else //if (Rand.nextBoolean(9.0/10))
        {
//            new UctAi(256).makeMove(
//                    board, you, thisMove,
//                    Collections.singletonList(opp));
            new RandomAi().makeMove(
                    board, you, thisMove,
                    Collections.singletonList(opp));
            return;
        } //ForkPathAi


//        Direction dir = null;
//
//        if(lastDir != null &&
//                lastDir.isAvailableIn(board, you.head()))
//        {
//            dir = lastDir;
//        }
//        else if( Direction.NORTH.isAvailableIn(board, you.head()))
//        {
//            dir = Direction.NORTH;
//        }
//        else if(Direction.WEST.isAvailableIn(board, you.head()))
//        {
//            dir = Direction.WEST;
//        }
//        else if(Direction.SOUTH.isAvailableIn(board, you.head()))
//        {
//            dir = Direction.SOUTH;
//        }
//        else if(Direction.EAST.isAvailableIn(board, you.head()))
//        {
//            dir = Direction.EAST;
//        }
//
//        thisMove.setDirection( dir );
//        lastDir = dir;
    }
}
