package ao.ai.sample.monte_carlo;

import ao.ai.PvpAi;
import ao.ai.sample.ForkAi;
import ao.ai.sample.RandomAi;
import ao.sw.control.GameResult;
import ao.sw.control.SimpleSnakesGame;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;
import ao.util.math.rand.Rand;

import java.util.Collection;

/**
 * Simplified Monte Carlo AI.
 */
public class MonteCarloAi extends PvpAi
{
    //--------------------------------------------------------------------
    private int     numRuns   = 64;
    private boolean useRandom = true;


    //--------------------------------------------------------------------
    public MonteCarloAi() {}
    public MonteCarloAi(int numSimRuns)
    {
        this(numSimRuns, true);
    }
    public MonteCarloAi(int numSimRuns, boolean useRandomSim)
    {
        numRuns   = numSimRuns;
        useRandom = useRandomSim;
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
        double    highestAverage   = -1000000;
        Direction highestDirection = null;

        Collection<Direction> avail =
                Direction.availableFrom(board, you.head());
        for (Direction dir : avail)
        {
            double average =
                    sumOfRuns(board, you, dir, opp, avail.size())
                        / numRuns;
            if (average > highestAverage ||
                    average == highestAverage && Rand.nextBoolean())
            {
                highestAverage   = average;
                highestDirection = dir;
            }
        }

        thisMove.setDirection( highestDirection );
    }

    private double sumOfRuns(
            BoardArrangement board,
            Snake            you,
            Direction        dir,
            Snake            opp,
            int              splitBy)
    {
        Player meSim = (useRandom ? new RandomAi() : new ForkAi(6));
        Player vsSim = (useRandom ? new RandomAi() : new ForkAi(6));

        BoardArrangement simBoard = board.advance(you, dir);
        Snake            simYou   = you.advance(dir);

        double sumOfRuns = 0;
        for (int i = 0; i < Math.round((double)numRuns/splitBy); i++)
        {
            SimpleSnakesGame sim =
                    new SimpleSnakesGame(
                            simBoard,
                            opp,    vsSim,
                            simYou, meSim);

            GameResult outcome = sim.waitUntilGameOver();
            double score = ((outcome.winner() == null)
                             ? 0
                             : meSim.equals( outcome.winner() )
                               ? 1 : -1) +
                           ((double) outcome.length() / 2000.0);
            sumOfRuns += score;
        }
        return sumOfRuns;
    }

//    private double oneRun(
//            BoardArrangement board,
//            Snake            you,
//            Direction        dir,
//            Snake            opp)
//    {
//        Player meSim = (useRandom ? new RandomAi() : new ForkAi(6));
//        Player vsSim = (useRandom ? new RandomAi() : new ForkAi(6));
//
//        BoardArrangement simBoard = board.advance(you, dir);
//        Snake            simYou   = you.advance(dir);
//
//        SimpleSnakesGame sim =
//                new SimpleSnakesGame(
//                        simBoard,
//                        opp,    vsSim,
//                        simYou, meSim);
//
//        GameResult outcome = sim.waitUntilGameOver();
//        return ((outcome.winner() == null)
//                         ? 0
//                         : meSim.equals( outcome.winner() )
//                           ? 1 : -1) +
//                       ((double) outcome.length() / 2000.0);
//    }



    //--------------------------------------------------------------------
    public String toString()
    {
        return "Monte Carlo @ " + numRuns + ", random=" + useRandom;
    }
}
