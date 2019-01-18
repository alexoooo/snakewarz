package ao.ai;

import ao.sw.engine.board.*;

import java.util.Set;
import java.util.HashSet;


/**
 * created: Aug 13, 2005  11:44:11 AM
 */
public class AiUtil {
    private AiUtil() {}

    public static Matrix availableArea(
            BoardArrangement board,
            BoardLocation inAreaOf )
    {
        return availableArea(board, inAreaOf, Integer.MAX_VALUE);
    }

    public static Matrix availableArea(
            BoardArrangement board,
            BoardLocation inAreaOf,
            int stopAt)
    {
        BitSetMatrix area =
                new BitSetMatrix(
                        board.getRowCount(),
                        board.getColumnCount() );

        Set<BoardLocation> edges = new HashSet<BoardLocation>( );

        int added = 0;
        if (addEdge( area, edges, inAreaOf )) added++;
        while ( ! edges.isEmpty() && added < stopAt)
        {
            Set<BoardLocation> nextEdges =
                    new HashSet<BoardLocation>( );

            for (BoardLocation edge : edges)
            {
                for (Direction availDir :
                        Direction.availableFrom( board, edge ))
                {
                    if (addEdge(
                            area,
                            nextEdges,
                            availDir.translate( edge ) ))
                    {
                        added++;
                    }
                }
            }

            edges = nextEdges;
        }

        return area;
    }

    private static boolean addEdge(
            Matrix area,
            Set<BoardLocation> edges,
            BoardLocation toAdd )
    {
        if (toAdd.availableIn( area ))
        {
            edges.add( toAdd );
            toAdd.occupyIn( area );
            return true;
        }
        return false;
    }
}


