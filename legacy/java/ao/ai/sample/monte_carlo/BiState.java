package ao.ai.sample.monte_carlo;

import ao.ai.sample.RandomAi;
import ao.sw.control.GameResult;
import ao.sw.control.SimpleSnakesGame;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.v2.Snake;

import java.util.ArrayList;
import java.util.List;

/**
 * State for two players only.
 */
public class BiState
{
    //--------------------------------------------------------------------
    private final BoardArrangement board;
    private final Snake            nextToAct;
    private final Snake            lastToAct;

    private final boolean          optimize;


    //--------------------------------------------------------------------
    public BiState(BoardArrangement board,
                   Snake            nextToAct,
                   Snake            lastToAct,
                   boolean          optimize)
    {
        this.board     = board;
        this.nextToAct = nextToAct;
        this.lastToAct = lastToAct;
        this.optimize  = optimize;
    }


    //--------------------------------------------------------------------
    public BiState advance(Direction act)
    {
        return new BiState(board.advance(nextToAct, act),
                           lastToAct,
                           nextToAct,
                           optimize);
    }


    //--------------------------------------------------------------------
    public List<Node> generateNodes()
    {
        List<Node> nextStates = new ArrayList<Node>(3);
        for (Direction dir :
                Direction.availableFrom(board, nextToAct.head()))
        {
            nextStates.add(
                    new Node(dir,
                             new BiState(
                                    board.advance(nextToAct, dir),
                                    lastToAct,
                                    nextToAct.advance(dir),
                                    optimize),
                             optimize));
        }
        return nextStates;
    }


    //--------------------------------------------------------------------
    public Rollout rollout()
    {
        RandomAi nextToActAi = new RandomAi();
        RandomAi lastToActAi = new RandomAi();

        SimpleSnakesGame sim =
                    new SimpleSnakesGame(
                            board,
                            nextToAct, nextToActAi,
                            lastToAct, lastToActAi);

        GameResult result = sim.waitUntilGameOver();
        RandomAi   winner = (RandomAi) result.winner();

        return new Rollout(winner == null,
                           nextToActAi == winner,
                           nextToActAi.firstAction(),
                           result.length());
    }


    //--------------------------------------------------------------------
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BiState biState = (BiState) o;

        if (board != null ? !board.equals(biState.board) : biState.board != null) return false;
//        if (lastToAct != null ? !lastToAct.equals(biState.lastToAct) : biState.lastToAct != null) return false;
//        if (nextToAct != null ? !nextToAct.equals(biState.nextToAct) : biState.nextToAct != null) return false;

        return true;
    }

    public int hashCode()
    {
        int result;
        result = (board != null ? board.hashCode() : 0);
//        result = 31 * result + (nextToAct != null ? nextToAct.hashCode() : 0);
//        result = 31 * result + (lastToAct != null ? lastToAct.hashCode() : 0);
        return result;
    }

    public String toString()
    {
        return String.valueOf( board );
    }
}
