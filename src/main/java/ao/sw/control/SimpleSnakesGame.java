package ao.sw.control;

import ao.sw.engine.MoveTracker;
import ao.sw.engine.board.*;
import ao.sw.engine.player.Player;
import ao.sw.engine.player.PlayerAvatar;
import ao.sw.engine.v2.MutableSnake;
import ao.sw.engine.v2.Snake;
import ao.sw.engine.v2.SnakeImpl;
import ao.sw.engine.v2.SnakesGameDisplay;

import java.util.Arrays;

/**
 * Fast but limited functionality.
 */
public class SimpleSnakesGame implements Game
{
    //--------------------------------------------------------------------
    private final PlayerAvatar players[] = {null, null};

    private final Matrix      obsticles;
    private final Matrix      bodies[];
    private final Snake       snakes[];
    private final MoveTracker tracker;

    private final int rows;
    private final int cols;

    private int nextToActIndex = 0;


    //--------------------------------------------------------------------
    public SimpleSnakesGame(int numRows, int numCols)
    {
        rows = numRows;
        cols = numCols;

        snakes = new Snake[]{
                    new SnakeImpl(
                            new BoardLocationImpl(0, 0)),
                    new SnakeImpl(
                            new BoardLocationImpl(rows - 1, cols - 1))};

        tracker = new MoveTracker();

        bodies = new Matrix[]{
                    new BitSetMatrix(rows, cols),
                    new BitSetMatrix(rows, cols)};
        obsticles = new BitSetMatrix(rows, cols);
    }

    public SimpleSnakesGame(
            BoardArrangement board,
            Snake            nestToAct, Player nextToActBrain,
            Snake            lastToAct, Player lastToActBrain)
    {
        rows = board.getRowCount();
        cols = board.getColumnCount();

        snakes = new Snake[]{new MutableSnake(nestToAct),
                             new MutableSnake(lastToAct)};

        tracker = new MoveTracker();

        players[0] = new PlayerAvatar(nextToActBrain);
        players[1] = new PlayerAvatar(lastToActBrain);

        bodies = new Matrix[]{
                    new BitSetMatrix(rows, cols),
                    new BitSetMatrix(rows, cols)};
        Snake.Util.fillOutBody(snakes[0], bodies[0]);
        Snake.Util.fillOutBody(snakes[1], bodies[1]);

        obsticles = board.toMatrix().prototype();
        obsticles.logicalSubtract( bodies[0] );
        obsticles.logicalSubtract( bodies[1] );
    }



    //--------------------------------------------------------------------
    // add a player to the game
    public void addPlayer(Player contestant)
    {
        assert players[ 0 ] == null ||
                players[ 1 ] == null;

        players[nextToActIndex] = new PlayerAvatar(contestant);
        advancePlayer();
    }


    //--------------------------------------------------------------------
    public void start() {}
    public void stop()  {}


    //--------------------------------------------------------------------
    public GameResult waitUntilGameOver()
    {
        assert players[0] != null;
        assert players[1] != null;
        
        Snake.Util.fillOutBody(snakes[0], bodies[0]);
        Snake.Util.fillOutBody(snakes[1], bodies[1]);

        for (int numMoves = 0;;
                advancePlayer(), numMoves++)
        {
//            if (nextToActIndex == 0)
//            {
//                System.out.println("====================="); // XXX
//                System.out.println(toString()             ); // XXX
//            }

            Direction dir = askForMove();

            if (dir != null)
            {
                if (! snakes[nextToActIndex].willGrow())
                {
                    BoardLocation tail = snakes[nextToActIndex].tail();
                    bodies[nextToActIndex].clear(
                            tail.getRow(), tail.getColumn());
                }

                snakes[nextToActIndex] =
                        snakes[nextToActIndex].advance(dir);

                BoardLocation head = snakes[nextToActIndex].head();
                bodies[nextToActIndex].occupy(
                        head.getRow(), head.getColumn());
            }
            else
            {
                boolean isSuicide =
                        ! Direction.availableFrom(
                                new MatrixBoardArrangement(commonBoard()),
                                snakes[nextToActIndex].head()).isEmpty();
       
                advancePlayer();

                Direction nextMove = askForMove();
//                System.out.println("next to act dies:"); // XXX
//                System.out.println(toString()         ); // XXX

                return nextMove == null
                        ? new GameResult( null, numMoves, isSuicide )
                        : new GameResult(
                                players[nextToActIndex].coreDeleget(),
                                numMoves, isSuicide);
            }
        }
    }


    //--------------------------------------------------------------------
    private Direction askForMove()
    {
        Matrix       thisBoard = commonBoard();
        PlayerAvatar nextToAct = players[nextToActIndex];

        Direction dir =
                tracker.directionOfMoveBy(
                        nextToAct,
                        new MatrixBoardArrangement(thisBoard),
                        snakes[nextToActIndex],
                        Arrays.asList(snakes[nextNextToActIndex()]));
        
        return (dir == null ||
                    ! dir.translate(
                            snakes[nextToActIndex].head()
                    ).availableIn(thisBoard))
                ? null
                : dir;
    }

    private Matrix commonBoard()
    {
        Matrix thisBoard = new BitSetMatrix(rows, cols);
        thisBoard.logicalOr( obsticles );
        thisBoard.logicalOr( bodies[0]  );
        thisBoard.logicalOr( bodies[1]  );
        return thisBoard;
    }


    //--------------------------------------------------------------------
    public SnakesGameDisplay display()
    {
        return null;
    }


    //--------------------------------------------------------------------
    private void advancePlayer()
    {
        nextToActIndex = nextNextToActIndex();
    }

    private int nextNextToActIndex()
    {
        return (nextToActIndex + 1) % 2;
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return ((SnakeImpl) snakes[ 0 ])
                    .toString( (SnakeImpl) snakes[1] ); 
    }
}
