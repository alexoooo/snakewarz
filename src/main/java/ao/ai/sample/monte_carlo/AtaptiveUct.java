package ao.ai.sample.monte_carlo;

import ao.ai.PvpAi;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.v2.Snake;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Monte Carlo Techique:
 * Upper Confidence Bounds applied to Trees 
 */
public class AtaptiveUct extends PvpAi
{
    //--------------------------------------------------------------------
    private static final int MIN_THINKING =        500;
//    private static final int MIN_THINKING =  10 * 1000;
    private static final int MAX_THINKING = 250 * 1000;


    //--------------------------------------------------------------------
    private final AbstractExecutorService exec =
            (AbstractExecutorService) Executors.newSingleThreadExecutor();

    private SimulationLoop curRunnable;
    private Future<?> preCalc = null;


    //--------------------------------------------------------------------
    private final boolean optimize;

    private volatile Node nextRoot = null;


    //--------------------------------------------------------------------
    public static PvpAi create()
    {
        return create( false );
    }

    public static PvpAi create(boolean optimize)
    {
        final AtaptiveUct ai = new AtaptiveUct( optimize );

        ai.curRunnable = new SimulationLoop( ai );
        ai.preCalc = ai.exec.submit( ai.curRunnable );

        return ai;
    }

    private static class SimulationLoop implements Runnable
    {
        private volatile boolean keepRunning = false;

        private final AtaptiveUct ai;
        public SimulationLoop(AtaptiveUct ai) {
            this.ai = ai;
        }

        @Override
        public void run()
        {
            keepRunning = true;
            while (keepRunning)
            {
                if (ai.nextRoot != null)
                {
                    if (ai.nextRoot.visits() > MAX_THINKING)
                    {
                        return;
                    }

                    ai.nextRoot.playSimulation();
                }
                else
                {
                    Thread.yield();
                }
            }
        }

        public void stop()
        {
            keepRunning = false;
        }
    }


    //--------------------------------------------------------------------
    private AtaptiveUct(boolean optimize)
    {
        this.optimize = optimize;

        if (preCalc != null)
        {
            preCalc.cancel(true);
        }
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

    private synchronized Direction utcSearch(
            BoardArrangement board,
            Snake            you,
            Snake            opp)
    {
        BiState curState = new BiState(board, you, opp, optimize);
        Node    curRoot  = null;
        if (nextRoot != null)
        {
            curRoot = nextRoot.childWithBiState( curState );
        }

        if (curRoot == null)
        {
            curRoot =
                new Node(
                        new BiState(board, you, opp, optimize),
                        optimize);
            nextRoot       = null;
//            nextPlaysSoFar[0] = 0;
        }
        else
        {
//            nextPlaysSoFar[0] = curRoot.visits();
//            System.out.println("starting with " + nextPlaysSoFar[0]);
        }

        nextRoot = curRoot;
        curRunnable.stop();
//        ai.nextRoot.playSimulation();

        while (nextRoot.visits() < MIN_THINKING)
        {
//            Thread.yield();
            nextRoot.playSimulation();
        }

//        try
//        {
//            try
//            {
//                preCalc.get(250, TimeUnit.MILLISECONDS);
//            }
//            catch (ExecutionException e)
//            {
//                e.printStackTrace();
//            }
//            catch (TimeoutException e) {/* ignored */}
//        }
//        catch (InterruptedException e)
//        {
//            e.printStackTrace();
//        }

        Node retRoot = nextRoot; // curRoot
        nextRoot  = null;

        System.out.println("acting with: " + retRoot.visits());
        Node nextRootNode = retRoot.optimizeInternal();
        nextRoot = nextRootNode;

        preCalc = exec.submit(curRunnable);
//        exec.execute(curRunnable);
        return nextRootNode.act();
    }
    

    //--------------------------------------------------------------------
    public String toString()
    {
        return super.toString() + " opt=" + optimize;
    }


    //--------------------------------------------------------------------
    protected void finalize() throws Throwable
    {
        try
        {
            exec.shutdownNow();
        }
        finally
        {
            super.finalize();
        }
    }
}
