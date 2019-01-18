package ao.sw.engine.board;

import ao.sw.engine.v2.Snake;

/**
 * created: Jul 28, 2005  12:45:11 AM
 */
public interface BoardArrangement
{
    public int getColumnCount();

    public int getRowCount();

    public boolean withinBounds( int row, int column );

    public boolean isAvailable( int row, int column );

    public BoardArrangement occupy(int row, int column);
    public BoardArrangement advance(Snake snake, Direction direction);

    public Matrix toMatrix();
}
