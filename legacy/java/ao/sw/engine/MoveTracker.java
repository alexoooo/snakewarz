package ao.sw.engine;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.move.SimpleMoveSpecifier;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * created: Aug 5, 2005  7:53:47 PM
 */
public class MoveTracker
{
//    private static final long TIMEOUT_MILLIS = 20000;

    private final Map<Player, MoveSpecifier> moveSpecifiers =
            new HashMap<Player, MoveSpecifier>( );

    public synchronized Direction directionOfMoveBy(
            final Player who,
            final BoardArrangement board,
            final Snake snake,
            final Collection<Snake> otherSnakes)
    {
        if (who == null) return null;

        final MoveSpecifier moveSpecifier =
                retrieveOrCreateSpecifier( who, board, snake.head() );

        if (Direction.availableFrom(board, snake.head()).isEmpty())
        {
            moveSpecifier.setDirection((Direction) null);
        }
        else
        {
//            while (true)
//            {
                who.makeMove( board, snake, moveSpecifier, otherSnakes );
//                if (Direction.availableFrom(board, snake.head())
//                        .contains(moveSpecifier.latestDirection()))
//                {
//                    break;
//                }
//            }
        }

        return moveSpecifier.latestDirection();
    }

    private MoveSpecifier retrieveOrCreateSpecifier(
            final Player forPlayer,
            final BoardArrangement board,
            final BoardLocation location )
    {
        final MoveSpecifier existing = moveSpecifiers.get( forPlayer );

        if (existing == null)
        {
            SimpleMoveSpecifier created = new SimpleMoveSpecifier();

            Iterator<Direction> dirs =
                    Direction.availableFrom(
                            board, location
                    ).iterator();
            if (dirs.hasNext())
            {
                created.setDirection( dirs.next() );
            }
            created.latestDirection();

            moveSpecifiers.put( forPlayer, created );
            return created;
        }
        return existing;
    }
}
