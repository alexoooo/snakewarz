package ao.sw.engine.v2;

import ao.sw.engine.board.*;
import ao.sw.engine.player.PlayerAvatar;

import java.awt.*;
import java.util.Map;

/**
 * Remembers the seriese of game states.
 */
public class SnakeHistory implements SnakesGameDisplay
{
    //--------------------------------------------------------------------
    private final GameGraphics display;
    private       GameState    lastState;
//    private       BoardArrangement lastBoard;

    private final int ROWS;
    private final int COLUMNS;

    private long minMoveTime = 20;


    //--------------------------------------------------------------------
    public SnakeHistory(int rows, int cols)
    {
        display   = new GameGraphicsImpl(rows, cols);
        lastState = new GameStateImpl(rows, cols);
//        lastBoard = new MatrixBoardArrangement(rows, cols);

        ROWS    = rows;
        COLUMNS = cols;
    }


    //--------------------------------------------------------------------
    public void highlight(PlayerAvatar who)
    {
        display.highlight( who );
    }

    
    //--------------------------------------------------------------------
    public BoardArrangement board()
    {
        Matrix occupied = new BitSetMatrix(ROWS, COLUMNS);
        for (Snake snake : lastState.snakes().values())
        {
            Snake.Util.fillOutBody(snake, occupied);
        }

        return new MatrixBoardArrangement(occupied);
    }


    //--------------------------------------------------------------------
    public Snake snake(PlayerAvatar ofWho)
    {
        return lastState.snake( ofWho );
    }


    //--------------------------------------------------------------------
    public void add(PlayerAvatar who)
    {
        lastState = lastState.add( who );

//        Snake newSnake = lastState.snake(who);
//        lastBoard = lastBoard.occupy(
//                        newSnake.head().getRow(),
//                        newSnake.head().getColumn());

        reRender();
    }

    public void advance(
            PlayerAvatar who,
            Direction    where)
    {
        lastState = lastState.advance( who, where );

//        Snake snake = lastState.snake(who);
//        lastBoard   = lastBoard.advance(snake, where);
        lastState.snake(who);

        reRender();
    }

//    public void remove(PlayerAvatar who)
//    {
//        lastState = lastState.remove( who );
//        reRender();
//    }

    //private


    //--------------------------------------------------------------------
    private void reRender()
    {
        display.clear();

        for (Map.Entry<PlayerAvatar, Snake> player : lastState.snakes().entrySet())
        {
            display.draw(player.getKey(), player.getValue());
        }

        display.flush();
        pause();
    }


    //--------------------------------------------------------------------
    public Component activeState()
    {
        return display.proxy();
    }

    //--------------------------------------------------------------------
    private void pause()
    {
        try
        {
            Thread.sleep( minMoveTime );
        }
        catch ( InterruptedException e )
        {
            e.printStackTrace();
        }
    }
}
