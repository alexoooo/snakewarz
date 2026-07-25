package ao.sw.engine.player;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.v2.Snake;

import java.util.Collection;

public interface Player
{
    // called before first makeMove in a sequance of
    //  makeMoves. makeMove sequences are demilited
    //  by calls to stopThinking.
    public void startThinking();

    // resets and prepares for another startThinking.
    // releases any non-garbage collectable resources.
    public void stopThinking();

    public void makeMove(
            BoardArrangement  board,
            Snake             you,
            MoveSpecifier     thisMove,
            Collection<Snake> others);
}
