package ao.ai.da;

import ao.ai.PvpAi;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.v2.Snake;
import ao.util.math.rand.Rand;

import java.util.List;

/**
 *
 */
public class OtherSnake extends PvpAi
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
        BoardLocation me = you.head();
        List<Direction> available = Direction.availableFrom(board,me);
        Direction random = Rand.fromList(available);
        thisMove.setDirection(random);
    }
//    protected void makeMove(
//            BoardArrangement board,
//            Snake you,
//            MoveSpecifier thisMove,
//            Snake opp)
//    {
////        // this is where you think
////        thisMove.setDirection(RelDirection.LEFT);
//
//        BoardLocation me=you.head();
//
//        if(Direction.NORTH.isAvailableIn(board,me)){
//            thisMove.setDirection(Direction.NORTH);
//        }
//
//        if(Direction.SOUTH.isAvailableIn(board,me)){
//            thisMove.setDirection(Direction.SOUTH);
//        }
//
//        if(Direction.EAST.isAvailableIn(board,me)){
//            thisMove.setDirection(Direction.EAST);
//        }
//
//        if(Direction.WEST.isAvailableIn(board,me)){
//            thisMove.setDirection(Direction.WEST);
//        }
//    }
}

