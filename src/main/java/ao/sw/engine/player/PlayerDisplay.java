package ao.sw.engine.player;

import java.awt.*;

/**
 * Visual representation of a player.
 */
public interface PlayerDisplay
{
    public Image head();
    public Image body();
    public Image tail();
    public Image ghost();
}
