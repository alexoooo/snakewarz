package ao.sw.engine;

import ao.sw.engine.board.Direction;
import ao.sw.engine.player.PlayerAvatar;

public class Action
{
    private final PlayerAvatar player;
    private final Direction    where;

    public Action(
            PlayerAvatar player,
            Direction where)
    {
        this.player = player;
        this.where  = where;
    }

    public PlayerAvatar player()
    {
        return player;
    }

    public Direction where()
    {
        return where;
    }
}
