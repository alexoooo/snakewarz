package ao.sw;

import ao.ai.sample.monte_carlo.AdaptiveUct;
import ao.ai.sample.monte_carlo.UctAi;
import ao.sw.control.GameResult;
import ao.sw.control.SnakesRunner;
import ao.sw.engine.player.Player;

/**
 * created: Aug 5, 2005  8:49:24 PM
 */
public class SnakesContest
{
    //----------------------------------------------------------
    public SnakesContest() {}


    //----------------------------------------------------------
    public GameResult run(Player... players)
    {
        GameResult result =
                SnakesRunner.synchDemoMatch(players);

        System.out.println(
                "game result: " + result.toString());
        pause();
        return result;
    }


    //----------------------------------------------------------
    public static void pause()
    {
        try
        {
            Thread.sleep(1000);
        }
        catch (InterruptedException ie)
        {
            ie.printStackTrace();
        }
    }


    //----------------------------------------------------------
    public static void main( String[] args )
    {
        SnakesRunner.start();
        SnakesContest contest = new SnakesContest();

//        Rand.nextInt(); // seed

//        Player players[] =
//                new Player[]{
//                    new RandomAi(),
//                    new RandomAi()};

        int    iq       = 2;
        Player adaptive = new UctAi(iq);
        for (int i = 0; i < Integer.MAX_VALUE; i++)
        {
            GameResult result = contest.run(
                    AdaptiveUct.create(),
//                    new Revan(),
//                    new Findalife3(),
//                    new SnakeThing(),
//                    new SnakeThing2(),//);
//                    adaptive,//);//,
//                    new SnakeGuy(),
//                    SnakesRunner.nestedInputPlayer(),
//                    new MonteCarloAi(64, false),
                    SnakesRunner.nestedInputPlayer());
//                    new PathAi(),
//                    new ForkPathAi());
//            contest.run(
//                    new MonteCarloAi(64),
//                    new UctAi(128*2*4),
//                    new UctAi(128*4));
//                    SnakesRunner.nestedInputPlayer());
//            contest.run(players);

            iq = (int) Math.round( iq *
                    (result.winner() == null
                     ? 1.0
                     : result.winner().equals( adaptive )
                       ? 0.75
                       : 2.00));
            adaptive = new UctAi(Math.max(iq, 1));
        }

//        SnakesRunner.end();
    }
}
