package ao.ai.sample;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.RelDirection;
import static ao.sw.engine.board.RelDirection.*;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import java.util.Arrays;
import java.util.Collection;

public class WallHugAi implements Player
{
    public void startThinking() {}
    public void stopThinking() {}

    
    public void makeMove(
            BoardArrangement  board,
            Snake             you,
            MoveSpecifier     yourMove,
            Collection<Snake> others)
    {
        for (RelDirection direction :
                Arrays.asList(FOREWARD, LEFT, RIGHT))
        {
            if (direction.isAvailableIn( yourMove, board, you.head() ))
            {
                yourMove.setDirection(direction);
                break;
            }
        }
    }

    public String toString()
    {
        return getClass().getName();
    }
}
