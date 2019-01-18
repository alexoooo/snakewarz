package ao.sw.control;

import ao.ai.interactive.NestedSwingInput;
import ao.sw.engine.player.Player;
import ao.sw.engine.player.PlayerAvatar;
import ao.sw.engine.v2.SnakesGameDisplay;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs snakes games.
 */
public class SnakesRunner
{
    //--------------------------------------------------------------------
    private static final AtomicBoolean inDemoMatch =
                                        new AtomicBoolean(false);

    //--------------------------------------------------------------------
    private SnakesRunner() {}


    //--------------------------------------------------------------------
    private static int nextNumRows()
    {
//        return 8;// + Rand.nextInt(5);
        return 20;
//        return 30;
    }

    private static int nextNumCols()
    {
//        return 8;// + Rand.nextInt(5);
        return 20;
//        return 30;
    }


    //--------------------------------------------------------------------
    // returns winner.
    public static void asynchDemoMatch(final Player... players)
    {
        asynchDemoMatch(nextNumRows(), nextNumCols(), players);
    }
    public static void asynchDemoMatch(
            final int       rows,
            final int       cols,
            final Player... players)
    {
        if (! inDemoMatch.compareAndSet(false, true)) return;

        new Thread(new Runnable()
        {
            public void run()
            {
                synchDemoMatch(rows, cols, players);
                inDemoMatch.set(false);
            }
        }).start();
    }

    public synchronized static
            GameResult synchDemoMatch(Player ... players)
    {
        return synchDemoMatch(nextNumRows(), nextNumCols(), players);
    }
    public synchronized static GameResult synchDemoMatch(
            int rows, int cols,
            Player ... players)
    {
        start();
        Game game = setupGame( rows, cols, players );

        game.start();
        mainDisplay( game.display() );

        return game.waitUntilGameOver();
    }


    //--------------------------------------------------------------------
    // returns winner.
    public static GameResult run(Player ... players)
    {
        return run(nextNumRows(), nextNumCols(), players);
    }
    public static GameResult run(
            int rows, int cols, Player ... players)
    {
        Game game = setupGame( rows, cols, players );
        game.start();

//        try     { return game.waitUntilGameOver(); }
//        finally { SnakesContest.pause();           }
        return game.waitUntilGameOver();
    }


    //--------------------------------------------------------------------
    private static Game setupGame(
            int rows, int cols,
            Player... players)
    {
        Game game = new SnakesGame2( rows, cols );

        for (Player player : players)
        {
            game.addPlayer(new PlayerAvatar(player));
        }

        return game;
    }


    //--------------------------------------------------------------------
//    private static TransparentWindow frame;
    private static JFrame frame;

    public static void start()
    {
        if (frame == null)
        {
//            frame = new TransparentWindow();
            frame = new JFrame("Snake Wars Display");
            frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );

            frame.setVisible(true);
            //((JComponent) frame.getContentPane()).setOpaque(false);
        }
    }

    public static void display(String message)
    {

    }

    public static void end()
    {
        frame.dispose();
        frame = null;
    }

    public static Player nestedInputPlayer()
    {
        return nestedInputPlayer("anon");
    }
    public static Player nestedInputPlayer(String name)
    {
        return new NestedSwingInput(
                    (JComponent) frame.getContentPane(), name);
    }

//
//    public static InputMap inputBinding

    private static void mainDisplay(SnakesGameDisplay display)
    {
        boolean hasGameDisplay =
                (frame.getContentPane().getComponentCount() > 0);

        if (hasGameDisplay)
        {
            frame.getContentPane().removeAll();
        }

//        frame.getContentPane().setLayout(
//                new FlowLayout(FlowLayout.RIGHT));
        frame.getContentPane().add(
                display.activeState());
//        frame.getContentPane().add( new JLabel("testing"), 0 );
        if (! hasGameDisplay)
        {
            frame.pack();
        }
        
        frame.getContentPane().setVisible(false);
        frame.getContentPane().setVisible(true);
    }
}
