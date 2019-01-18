package ao.ai.sample.path_finder;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;

import java.util.*;

/**
 * Search algorithm.
 */
public class AStar
{
    //--------------------------------
    private AStar() {}


    //--------------------------------
    public static Collection<BoardLocation> pathBetween(
            BoardArrangement board,
            BoardLocation from,
            BoardLocation to)
    {
        Map<BoardLocation, Path> closed = new HashMap<BoardLocation, Path>();
        PriorityQueue<Path>      q      = new PriorityQueue<Path>();

        q.add( new Path(from, to) );
        while (! q.isEmpty())
        {
            Path p = q.poll();

            Path shortestSoFar = closed.get( p.step() );
            if (shortestSoFar != null)
            {
                if (p.isShorterSoFarThan( shortestSoFar ))
                {
                    closed.put( p.step(), p );
                }
                continue;
            }
            else if (p.step().distanceTo( to ) <= 1)
            {
                return p.steps();
            }

            closed.put( p.step(), p );
            for (Path successor : p.successors(board, to))
            {
                if (! closed.containsKey( successor.step() ))
                {
                    q.add( successor );
                }
            }
        }

        return Collections.emptyList();
    }
}
