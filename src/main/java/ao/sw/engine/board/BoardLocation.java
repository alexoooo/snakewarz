package ao.sw.engine.board;

/**
 * created: Jul 28, 2005  12:43:22 AM
 */
public interface BoardLocation
{
    // exclusively for use by AI writers
    public int getRow();
    public int getColumn();

    public int offsetRow(int byHowMuch);
    public int offsetColumn(int byHowMuch);

    public boolean availableIn( BoardArrangement board );
    public boolean availableIn( Matrix matrix          );

    public BoardArrangement occupyIn( BoardArrangement board );
    public void             occupyIn( Matrix matrix          );

    public BoardLocation translate( int howManyRows, int howManyColumns );

    public int    distanceTo(BoardLocation other);
    public double euclidDistTo(BoardLocation other);

    public Direction directionTo(BoardLocation other);
}
