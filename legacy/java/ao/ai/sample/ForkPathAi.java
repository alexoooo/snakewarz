package ao.ai.sample;

import ao.ai.AiUtil;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.Matrix;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Tries to be a bit smarter.
 */
public class ForkPathAi implements Player
{
    //---------------------------------------------------
    public void startThinking() {}
    public void stopThinking() {}


    //---------------------------------------------------
    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier yourMove,
            Collection<Snake> others)
    {
        SortedMap<MoveChoice, Direction> moves =
                new TreeMap<MoveChoice, Direction>( );

        for (Direction possibleMove :
                Direction.availableFrom(board, you.head()))
        {
            BoardLocation fromHere =
                    possibleMove.translate(you.head());

            MoveChoice appraisal =
                    new MoveChoice(board, fromHere, others);

            if ( (! moves.containsKey(appraisal)) || Math.random() < 0.5 )
            {
                moves.put( appraisal, possibleMove );
            }
        }

        yourMove.setDirection( moves.get(moves.lastKey()) );
    }


    //---------------------------------------------------
    public String toString()
    {
        return getClass().getName();
    }


    //---------------------------------------------------
    private static class MoveChoice implements Comparable<MoveChoice>
    {
        private double percentAvail;
        private double enemyDistance;

        public MoveChoice(
                BoardArrangement board,
                BoardLocation yourLocation,
                Collection<Snake> locationsOfOthers)
        {
            Matrix available =
                    AiUtil.availableArea(
                            board,
                            yourLocation);

            percentAvail =
                    ((double) available.occupiedCount()) /
                    (board.getRowCount() * board.getColumnCount());

            enemyDistance = 0;
            for (Snake enemy : locationsOfOthers)
            {
                enemyDistance +=
                        distancePercent(board, yourLocation, enemy.head());
            }
            enemyDistance /= locationsOfOthers.size();
        }

        private double distancePercent(
                BoardArrangement outOf,
                BoardLocation from,
                BoardLocation to)
        {
            int colDelta = (from.getColumn() - to.getColumn());
            int rowDelta = (from.getRow() - to.getRow());

            double delta =
                    Math.sqrt(colDelta * colDelta + rowDelta * rowDelta);
            double maxDelta =
                    Math.sqrt(
                            outOf.getRowCount() * outOf.getRowCount() +
                            outOf.getColumnCount() * outOf.getColumnCount());

            return delta / maxDelta;
        }


        public int compareTo(MoveChoice o)
        {
            int availCmp = Double.compare(percentAvail, o.percentAvail);

            return (availCmp == 0)
                    ? compareDistances(enemyDistance, o.enemyDistance)
                    : availCmp;
        }

        private int compareDistances(double myDist, double otherDist)
        {
            double myScore =
                    (myDist < 0.05)
                    ? 0.1
                    : myDist;
            double otherScore =
                    (otherDist < 0.05)
                    ? 0.1
                    : otherDist;

            return -Double.compare(myScore, otherScore);
        }
    }
}
