package ao.sw.engine.board;

/**
 * created: Aug 13, 2005  5:24:15 PM
 */
public interface Matrix
{
    int getColumnCount();

    int getRowCount();

    boolean isAvailable( int row, int column );

    boolean occupy( int row, int column );
    void clear( int row, int column );
    void clear();

    int occupiedCount();

    Matrix prototype();

    public void logicalOr( Matrix otherMatrix );
    public void logicalSubtract( Matrix otherMatrix );
//    public void logicalCompliment();

    boolean withinBounds( int row, int column );
}
