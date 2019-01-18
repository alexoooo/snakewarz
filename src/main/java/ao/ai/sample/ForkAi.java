package ao.ai.sample;

import ao.ai.AiUtil;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.Matrix;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;
import ao.util.math.rand.Rand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * created: Aug 12, 2005  6:21:27 PM
 *
 * Detects forks in the road and chooses the
 *  paths that have more space available.
 */
public class ForkAi implements Player
{
    //--------------------------------------------------------------------
    private int analysisDepth = Integer.MAX_VALUE;


    //--------------------------------------------------------------------
    public ForkAi() {}
    public ForkAi(int depth)
    {
        analysisDepth = depth;
    }


    //--------------------------------------------------------------------
    public void startThinking() {}
    public void stopThinking() {}


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier yourMove,
            Collection<Snake> others)
    {
        int             mostFreeSpots     = -1;
        List<Direction> mostFreeSpotsDirs = new ArrayList<Direction>();
        for (Direction possibleMove :
                Direction.availableFrom(board, you.head() ))
        {
            Matrix available =
                    AiUtil.availableArea(
                            board.advance(you, possibleMove),
                            possibleMove.translate(you.head()),
                            analysisDepth);

            int freeSpots =
                    available.occupiedCount();
            if (freeSpots > mostFreeSpots)
            {
                mostFreeSpots    = freeSpots;
                mostFreeSpotsDirs.clear();
                mostFreeSpotsDirs.add( possibleMove );
            }
            else if(freeSpots == mostFreeSpots)
            {
                mostFreeSpotsDirs.add( possibleMove );
            }
        }

        yourMove.setDirection(
                Rand.fromList(mostFreeSpotsDirs));
    }

    public String toString()
    {
        return getClass().getName();
    }
}
