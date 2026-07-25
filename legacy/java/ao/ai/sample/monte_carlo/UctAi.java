package ao.ai.sample.monte_carlo;

import ao.ai.PvpAi;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.v2.Snake;

/**
 * Monte Carlo Techique:
 * Upper Confidence Bounds applied to Trees
 */
public class UctAi extends PvpAi
{
    //--------------------------------------------------------------------
    private int     numRuns;
    private boolean optimize;
    private Node    prevRoot;

    //--------------------------------------------------------------------
    public UctAi(int iterations, boolean optimize)
    {
        numRuns       = iterations;
        this.optimize = optimize;
    }
    public UctAi(int iterations)
    {
        this(iterations, false);
    }
    public UctAi()
    {
        this(64);
    }


    //--------------------------------------------------------------------
    public void startThinking() {}
    public void stopThinking()  {}


    //--------------------------------------------------------------------
    protected void makeMove(
            BoardArrangement board,
            Snake            you,
            MoveSpecifier    thisMove,
            Snake            opp)
    {
        thisMove.setDirection(
                utcSearch(board, you, opp));
    }

    private Direction utcSearch(
            BoardArrangement board,
            Snake            you,
            Snake            opp)
    {
        BiState state = new BiState(board, you, opp, optimize);

        Node root = null;
        if (prevRoot != null /*&& optimize*/)
        {
            root = prevRoot.childWithBiState(state);
        }

        if (root == null)
        {
            root = new Node(state, optimize);
        }
//        else
//        {
//            System.out.println("recicling: " + root.visits());
//        }

        for (int run = 0; run < numRuns; run++)
        {
            root.playSimulation();
        }

        prevRoot = root.optimizeInternal();
//        System.out.println(prevRoot.maxDepth());
        return prevRoot.act();
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return super.toString() + " @ " + numRuns + " opt=" + optimize;
    }
}
