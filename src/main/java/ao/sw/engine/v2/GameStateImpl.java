package ao.sw.engine.v2;

import ao.sw.engine.board.BitSetMatrix;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.Matrix;
import ao.sw.engine.player.PlayerAvatar;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks snake locations.
 * Threadsafe.
 */
public class GameStateImpl implements GameState
{
    //-------------------------------------------
    private final Map<PlayerAvatar, Snake> SNAKES;

    private final int ROWS;
    private final int COLUMNS;


    //-------------------------------------------
    public GameStateImpl(int rows, int columns)
    {
        this( createMap(), rows, columns );
    }

    private GameStateImpl(
            Map<PlayerAvatar, Snake> snakes,
            int rows,
            int columns)
    {
        SNAKES = snakes;
        ROWS    = rows;
        COLUMNS = columns;
    }

    //-------------------------------------------
    private static Map<PlayerAvatar, Snake> createMap()
    {
        return new HashMap<PlayerAvatar, Snake>();
    }
    private static Map<PlayerAvatar, Snake> cloneMap(
            Map<PlayerAvatar, Snake> snakes)
    {
        Map<PlayerAvatar, Snake> clone = createMap();
        clone.putAll( snakes );
        return clone;
    }


    //-------------------------------------------
    public Snake snake(PlayerAvatar ofWho)
    {
        assert ofWho != null;

        return SNAKES.get( ofWho );
    }

    public Map<PlayerAvatar, Snake> snakes()
    {
        return Collections.unmodifiableMap(
                SNAKES);
    }


    //-------------------------------------------
    public GameState add(PlayerAvatar who)
    {
        if (SNAKES.containsKey( who ))
        {
            return this;
        }

        Matrix occupancy = new BitSetMatrix(ROWS, COLUMNS);
        for (Snake snake : SNAKES.values())
        {
            Snake.Util.fillOutBody(snake, occupancy);
        }
        
        BoardLocation mostDistant =
                new BoardOccupancy(occupancy).
                        mostDistant(SNAKES.values());

        Snake babySnake = new SnakeImpl(mostDistant);

        Map<PlayerAvatar, Snake> snakes = cloneMap( SNAKES );
        snakes.put(who, babySnake);

        return new GameStateImpl(snakes, ROWS, COLUMNS);
    }


    //-----------------------------------------------------------
//    public GameState remove(PlayerAvatar who)
//    {
//        if (! SNAKES.containsKey( who ))
//        {
//            return this;
//        }
//
//        Map<PlayerAvatar, Snake> snakes = cloneMap( SNAKES );
//        snakes.remove(who);
//
//        return new GameStateImpl(snakes, ROWS, COLUMNS);
//    }


    //-----------------------------------------------------------
    public GameState advance(PlayerAvatar who, Direction where)
    {
        assert SNAKES.containsKey( who );

        Map<PlayerAvatar, Snake> snakes = cloneMap( SNAKES );
        snakes.put(
                who,
                SNAKES.get(who).advance(where) );

        return new GameStateImpl(snakes, ROWS, COLUMNS);
    }
}
