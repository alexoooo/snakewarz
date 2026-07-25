package ao.sw.control;

import ao.sw.engine.player.Player;
import ao.sw.engine.v2.SnakesGameDisplay;

/**
 * Date: Oct 16, 2005
 */
public interface Game
{
    // add a player to the game
    void addPlayer( Player player );

    void start();
    void stop();

    GameResult waitUntilGameOver();

    // used to render the game on screen
    SnakesGameDisplay display();
}
