package ao.sw.engine.v2;

import ao.sw.engine.board.Direction;
import ao.sw.engine.player.PlayerAvatar;

import java.util.Map;

/**
 * Snapshot of the game board.
 */
public interface GameState
{
    //-------------------------------------------
    Snake snake(PlayerAvatar ofWho);

    Map<PlayerAvatar, Snake> snakes();


    //-------------------------------------------
    GameState add(PlayerAvatar who);
//    GameState remove(PlayerAvatar who);

    GameState advance(
            PlayerAvatar who, Direction where);
}
