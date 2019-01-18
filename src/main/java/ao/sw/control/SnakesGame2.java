package ao.sw.control;

import ao.sw.engine.MoveTracker;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.Player;
import ao.sw.engine.player.PlayerAvatar;
import ao.sw.engine.v2.Snake;
import ao.sw.engine.v2.SnakeHistory;
import ao.sw.engine.v2.SnakesGameDisplay;
import ao.util.math.rand.Rand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created Feb 1, 2007
 */
public class SnakesGame2 implements Game
{
    //--------------------------------------------------------------------
    private SnakeHistory       state;
    private List<PlayerAvatar> players;
    private MoveTracker        tracker;
    private int                nextPlayer = -1;


    //---------------------------------------------------
    public SnakesGame2(int numRows, int numCols)
    {
        tracker = new MoveTracker();
        players = new ArrayList<PlayerAvatar>();
        state   = new SnakeHistory(numRows, numCols);
    }


    //---------------------------------------------------
    // add a player to the game
    public void addPlayer(Player player)
    {
        PlayerAvatar avatar = new PlayerAvatar(player);
        
        state.add( avatar );
        players.add( avatar );
    }


    //---------------------------------------------------
    public void start()
    {
        for (PlayerAvatar player : players)
        {
            player.startThinking();
        }
    }

    public void stop()
    {
        for (PlayerAvatar player : players)
        {
            if (player != null)
            {
                player.stopThinking();
            }
        }
    }

    //--------------------------------------------------------------------
    public GameResult waitUntilGameOver()
    {
        int     stepCount = 0;
        boolean isSuicide = false;

        for (; advancePlayer(); stepCount++)
        {
            Direction dir = nextMove();
            if (dir == null)
            {
                Snake loser =
                        state.snake( players.get( nextPlayer ) );

                isSuicide = ! Direction.availableFrom(
                                 state.board(), loser.head()).isEmpty();

                players.get( nextPlayer ).stopThinking();
                players.set( nextPlayer, null );
            }
            else
            {
                state.advance(
                    players.get(nextPlayer),
                    dir);
            }
        }

        advancePlayer();
        PlayerAvatar winner =
                (nextPlayerHasOptions())
                ? players.get(nextPlayer)
                : null;
        GameResult result =
                new GameResult(
                    (winner == null ? null : winner.coreDeleget()),
                    stepCount,
                    isSuicide);

        state.highlight(winner);
        stop();
        
        return result;
    }

    private Direction nextMove()
    {
        if (! nextPlayerHasOptions()) return null;

        PlayerAvatar player = players.get( nextPlayer );
        Direction dir =
                tracker.directionOfMoveBy(
                        player,
                        state.board(),
                        state.snake( player ),
                        postNextSnakes());

        return dir != null &&
               dir.isAvailableIn(
                       state.board(),
                       state.snake(player).head())
               ? dir
               : null;
    }

    private boolean nextPlayerHasOptions()
    {
        return ! Direction.availableFrom(
                    state.board(),
                    state.snake(
                            players.get( nextPlayer )
                    ).head()
                ).isEmpty();
    }

    private Collection<Snake> postNextSnakes()
    {
        Collection<Snake> snakes = new ArrayList<Snake>();
        for (int player : postNextIndexes())
        {
            snakes.add(
                    state.snake(
                        players.get(player)) );
        }
        return snakes;
    }

    private boolean advancePlayer()
    {
        if (nextPlayer == -1)
        {
            nextPlayer = Rand.nextInt(players.size());
        }

        if (! postNextIndexes().isEmpty())
        {
            nextPlayer = postNextIndexes().get(0);
        }

        return playerCount() > 1;
    }

    private int playerCount()
    {
        int count = 0;
        for (PlayerAvatar player : players)
        {
            if (player != null) count++;
        }
        return count;
    }

    private List<Integer> postNextIndexes()
    {
        List<Integer> indexes = new ArrayList<Integer>();

        for (int player = playerAfter(nextPlayer);
                 player != nextPlayer;
                 player = playerAfter(player))
        {
            if (players.get(player) != null)
            {
                indexes.add(player);
            }
        }

        return indexes;
    }

    private int playerAfter(int playerIndex)
    {
        return (playerIndex + 1) % players.size();
    }


    //---------------------------------------------------
    public SnakesGameDisplay display()
    {
        return state;
    }
}
