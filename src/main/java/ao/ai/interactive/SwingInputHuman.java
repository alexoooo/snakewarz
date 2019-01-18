package ao.ai.interactive;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * created: Aug 8, 2005  4:12:33 AM
 */
public class SwingInputHuman implements Player
{
    //--------------------------------------------------------------------
    private static final String UP    = "up";
    private static final String DOWN  = "down";
    private static final String LEFT  = "left";
    private static final String RIGHT = "right";
    private static final String QUIT  = "quit";


    //--------------------------------------------------------------------
    private JFrame frame;

    private final List<Direction> queue = new ArrayList<Direction>();


    //--------------------------------------------------------------------
    private void createAndShowInputGui()
    {
//        JFrame.setDefaultLookAndFeelDecorated(true);

        // Create and set up the window.
        frame = new JFrame("Snake Wars Keyboard Input");
        frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );

        JLabel label = new JLabel("Use the arrows to control your snake");
        frame.getContentPane().add(label);

        setupKeyBindings( label );

        //Display the window.
        frame.pack();
        frame.setVisible(true);
    }

    private void setupKeyBindings( JLabel label )
    {
        InputMap inputMap =
                label.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW );

        inputMap.put( KeyStroke.getKeyStroke( KeyEvent.VK_UP,     0 ), UP    );
        inputMap.put( KeyStroke.getKeyStroke( KeyEvent.VK_DOWN,   0 ), DOWN  );
        inputMap.put( KeyStroke.getKeyStroke( KeyEvent.VK_LEFT,   0 ), LEFT  );
        inputMap.put( KeyStroke.getKeyStroke( KeyEvent.VK_RIGHT,  0 ), RIGHT );
        inputMap.put( KeyStroke.getKeyStroke( KeyEvent.VK_ESCAPE, 0 ), QUIT  );

        label.getActionMap().put( UP,    new DirectionPressed(Direction.NORTH) );
        label.getActionMap().put( DOWN,  new DirectionPressed(Direction.SOUTH) );
        label.getActionMap().put( LEFT,  new DirectionPressed(Direction.WEST ) );
        label.getActionMap().put( RIGHT, new DirectionPressed(Direction.EAST ) );
        label.getActionMap().put( QUIT,  new DirectionPressed(null           ) );
    }

    private class DirectionPressed extends AbstractAction
    {
        private final Direction TRACKING;

        public DirectionPressed( Direction tracking )
        {
            TRACKING = tracking;
        }

        public void actionPerformed( ActionEvent e )
        {
            queue.add( TRACKING );
        }
    }


    //--------------------------------------------------------------------
    public void startThinking()
    {
        //Schedule a job for the event-dispatching thread:
        //creating and showing .
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowInputGui();
            }
        });
    }

    public void stopThinking()
    {
        frame.dispose();
    }


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier yourMove,
            Collection<Snake> others)
    {
        try
        {
            Thread.sleep( 50 );
        }
        catch ( InterruptedException ignored )
        {
            //e.printStackTrace();
        }

        if (! queue.isEmpty())
        {
            yourMove.setDirection( queue.remove(0) );
        }
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return getClass().getName();
    }
}
