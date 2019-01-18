package ao.ai.sample.path_finder;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;
import ao.ai.sample.WallHugAi;
import ao.ai.sample.ForkPathAi;

import java.util.Collection;
import java.util.Iterator;

/**
 * Writing this AI to try and beat me
 *  in the game.
 */
public class PathAi implements Player
{
    //-------------------------------------
    public void startThinking() {}
    public void stopThinking()  {}

    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier thisMove,
            Collection<Snake> others)
    {
        Snake other = others.iterator().next();

        Collection<BoardLocation> path =
                AStar.pathBetween(board, you.head(), other.head());

        if (path == null)
        {
            new WallHugAi().makeMove(
                    board, you, thisMove, others);
        }
        else if (path.size() < 3)
        {
            new ForkPathAi().makeMove(
                    board, you, thisMove, others);
        }
        else
        {
            Iterator<BoardLocation> pathSteps = path.iterator();

            pathSteps.next();
            BoardLocation firstStep = pathSteps.next();
//            BoardLocation firstStep = path.iterator().next();

            for (Direction available :
                    Direction.availableFrom( board, you.head() ))
            {
                if (available.translate(you.head()).equals(
                        firstStep))
                {
                    thisMove.setDirection( available );
                    return;
                }
            }
        }
    }


    public String toString()
    {
        return getClass().getName();
    }
}
