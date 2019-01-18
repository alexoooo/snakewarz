package ao.sw.engine.v2;

import ao.sw.engine.player.PlayerDisplay;
import ao.sw.engine.player.PlayerAvatar;

import java.awt.*;

/**
 * Draws the game.
 */
public interface GameGraphics
{
    Component proxy();

    void draw(PlayerDisplay avatar, Snake snake);

    void clear();

    void flush();

    public void highlight(PlayerAvatar avatar);
}
