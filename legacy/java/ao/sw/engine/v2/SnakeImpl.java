package ao.sw.engine.v2;

import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snakes game character.
 * Threadsafe.
 */
public class SnakeImpl implements Snake
{
    //--------------------------------------------------------------------
    private static final String EMPTY_DISPLAY = " ";
    private static final String BODY_DISPLAY  = "#";
    private static final String HEAD_DISPLAY  = "%";
    private static final String TAIL_DISPLAY  = ".";


    //--------------------------------------------------------------------
    private final List<BoardLocation> BODY;
    private final boolean             willGrow;


    //--------------------------------------------------------------------
    public SnakeImpl(BoardLocation startAt)
    {
        assert startAt != null;

        BODY = newBody();
        BODY.add( startAt );

        willGrow = false;
    }

    private SnakeImpl(List<BoardLocation> body, boolean shouldGrow)
    {
        BODY     = body;
        willGrow = shouldGrow;
    }


    //--------------------------------------------------------------------
    public synchronized Snake advance(Direction direction)
    {
        List<BoardLocation> history = newBody();

        if (willGrow())
        {
            history.addAll(BODY);
        }
        else if (BODY.size() > 1)
        {
            history.addAll(
                    BODY.subList(1, BODY.size()));
        }

        history.add(
                direction.translate(
                        BODY.get( BODY.size() - 1 )
                )
        );

        return new SnakeImpl( history, ! willGrow() );
    }


    //--------------------------------------------------------------------
    public List<BoardLocation> body()
    {
        return Collections.unmodifiableList(BODY);
    }

    public BoardLocation head()
    {
        return BODY.get( BODY.size() - 1 );
    }

    public BoardLocation tail()
    {
        return BODY.get(0);
    }

    public int occupiedFor(BoardLocation loc)
    {
        int occ = (willGrow) ? 2 : 1;
        for (int i = 0;
             i < BODY.size();
             i++, occ += 2)
        {
            BoardLocation occLoc = BODY.get(i);
            if (occLoc.equals( loc ))
            {
                return occ;
            }
        }
        return -1;
    }


    //--------------------------------------------------------------------
    public boolean willGrow()
    {
        return willGrow;
    }


    //--------------------------------------------------------------------
    public Direction lastMoveDirection()
    {
        if (BODY.size() < 2) return null;

        BoardLocation from = BODY.get( BODY.size() - 2 );
        BoardLocation to   = BODY.get( BODY.size() - 1 );

        return from.directionTo( to );
    }


    //--------------------------------------------------------------------
    private List<BoardLocation> newBody()
    {
        return new ArrayList<BoardLocation>();
    }


    //--------------------------------------------------------------------
    public String toString(SnakeImpl opponent)
    {
        String[][] thisBoard = asciiBody();
        String[][] thatBoard = opponent.asciiBody();

        String[][] out = new String[ Math.max(thisBoard.length,
                                              thatBoard.length) ]
                                   [ Math.max(thisBoard[0].length,
                                              thatBoard[0].length) ];
        writeToBoard(thisBoard, out);
        writeToBoard(thatBoard, out);
        return asciiMatrixToString(out);
    }

    private void writeToBoard(String[][] board, String[][] out)
    {
        for (int row = 0; row < board.length; row++)
        {
            for (int col = 0; col < board[row].length; col++)
            {
                out[row][col] =
                        out[row][col] == null
                        ? board[row][col]
                        : out[row][col].equals( EMPTY_DISPLAY )
                          ? board[row][col]
                          : out[row][col];
            }
        }
    }

    public String toString()
    {
        return asciiMatrixToString(asciiBody());
    }

    private String asciiMatrixToString(
            String[][] board)
    {
        int maxRow = board.length;
        int maxCol = board[ 0 ].length;

        StringBuilder str = new StringBuilder((maxRow + 1) * maxCol + 1);
        for (int row = 0; row < maxRow; row++)
        {
            for (int col = 0; col < maxCol; col++)
            {
                str.append(
                        board[row][col] == null
                         ? EMPTY_DISPLAY
                         : board[row][col]);
            }
            if (row != maxRow)
            {
                str.append("\n");
            }
        }
        return str.toString();
    }

    private String[][] asciiBody()
    {
        int maxRow = 0;
        int maxCol = 0;

        for (BoardLocation loc : BODY)
        {
            if (loc.getRow() > maxRow)
            {
                maxRow = loc.getRow();
            }
            if (loc.getColumn() > maxCol)
            {
                maxCol = loc.getColumn();
            }
        }

        String board[][] = new String[maxRow + 1][maxCol + 1];
        for (BoardLocation loc : BODY)
        {
            board[ loc.getRow() ][ loc.getColumn() ] = BODY_DISPLAY;
        }
        board[ head().getRow() ][ head().getColumn() ] = HEAD_DISPLAY;
        board[ tail().getRow() ][ tail().getColumn() ] = TAIL_DISPLAY;
        return board;
    }


    //--------------------------------------------------------------------
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof Snake)) return false;

        Snake snake = (Snake) o;
        return willGrow() == snake.willGrow() &&
               body().equals( snake.body() );
    }

    public int hashCode()
    {
        int result;
        result = BODY.hashCode();
        result = 31 * result + (willGrow ? 1 : 0);
        return result;
    }
}
