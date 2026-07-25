package ao.sw.engine.v2;

import ao.sw.engine.board.*;

import java.util.Collection;

/**
 * Tracks all [not] available space on the board;
 */
public class BoardOccupancy
{
    private final Matrix OCCUPANCY;

    public BoardOccupancy(int rows, int columns)
    {
        OCCUPANCY = new BitSetMatrix(rows, columns);
    }

    public BoardOccupancy(Matrix occupancy)
    {
        OCCUPANCY = occupancy;
    }


    //-------------------------------------------
//    public void occupy(int row, int column)
//    {
//        OCCUPANCY.occupy(row, column);
//    }



    //-------------------------------------------
    public BoardLocation mostDistant(
            Collection<Snake> from)
    {
        if (OCCUPANCY.occupiedCount() == from.size())
        {
            if (from.size() == 0)
            {
                if (OCCUPANCY.isAvailable(0, 0))
                {
                    return new BoardLocationImpl(0, 0);
                }
            }
            else if (from.size() == 1)
            {
                if (OCCUPANCY.isAvailable(
                        OCCUPANCY.getRowCount() - 1,
                        OCCUPANCY.getColumnCount() - 1))
                {
                    return new BoardLocationImpl(
                            OCCUPANCY.getRowCount() - 1,
                            OCCUPANCY.getColumnCount() - 1);
                }
            }
        }

        return generalMostDistant(from);
    }

    private BoardLocation generalMostDistant(
            Collection<Snake> from)
    {
        BoardLocation mostDistant = null;
        double smallestInverseDistance = Double.MAX_VALUE;

        for (int row = 0; row < OCCUPANCY.getRowCount(); row++)
        {
            for (int col = 0; col < OCCUPANCY.getColumnCount(); col++)
            {
                if (OCCUPANCY.isAvailable(row, col))
                {
                    BoardLocation location =
                            new BoardLocationImpl( row, col );
                    double inverseDistance =
                            inverseDistance( location, from );

                    if (smallestInverseDistance > inverseDistance)
                    {
                        mostDistant = location;
                        smallestInverseDistance = inverseDistance;
                    }
                }
            }
        }

        return mostDistant;
    }

    private double inverseDistance(
            BoardLocation at,
            Collection<Snake> from)
    {
        double sum = 0;

        for (Snake snake : from)
        {
            sum += 1.0 /
                    (at.euclidDistTo(snake.head()) + 1);
        }

        return sum;
    }
}
