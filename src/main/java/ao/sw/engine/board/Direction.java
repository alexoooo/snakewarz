package ao.sw.engine.board;

import java.util.ArrayList;
import java.util.List;

public enum Direction
{
    //-----------------------------------------------------------------------
    // top left of game grid is (0, 0)
    NORTH (-1,  0),
    SOUTH ( 1,  0),
    EAST  ( 0,  1),
    WEST  ( 0, -1);

//    public static Direction fromKeyboard( char key )
//    {
//        switch (Character.toLowerCase( key ))
//        {
//            case 'w': return NORTH;
//            case 's': return SOUTH;
//            case 'a': return WEST;
//            case 'd': return EAST;
//            default: return null;
//        }
//    }


    //-----------------------------------------------------------------------
    public static List<Direction> availableFrom(
            BoardArrangement board, BoardLocation location)
    {
        List<Direction> available = new ArrayList<Direction>( 4 );

        for (Direction direction : values())
        {
            if (direction.isAvailableIn( board, location ))
            {
                available.add( direction );
            }
        }

        return available;
    }



    //-----------------------------------------------------------------------
    //
    private final int ROW_OFFSET;
    private final int COLUMN_OFFSET;

    //-----------------------------------------------------------------------
    private Direction(int rowOffset, int columnOffset)
    {
        this.ROW_OFFSET    = rowOffset;
        this.COLUMN_OFFSET = columnOffset;
    }


    //-----------------------------------------------------------------------
    public boolean isAvailableIn(
            BoardArrangement board,
            BoardLocation relativeTo)
    {
        return board.isAvailable(
                relativeTo.offsetRow( ROW_OFFSET ),
                relativeTo.offsetColumn( COLUMN_OFFSET ));
    }

    //-----------------------------------------------------------------------
    public BoardLocation translate( BoardLocation locationToTranslate )
    {
        return translate(locationToTranslate, 1);
    }

    //-----------------------------------------------------------------------
    public BoardLocation translate(
            BoardLocation locationToTranslate,
            int distance)
    {
        return locationToTranslate.translate(
                distance * ROW_OFFSET,
                distance * COLUMN_OFFSET );
    }
}
