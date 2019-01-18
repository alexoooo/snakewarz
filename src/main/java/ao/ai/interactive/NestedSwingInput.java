package ao.ai.interactive;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.sw.engine.player.Player;
import ao.sw.engine.v2.Snake;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 *
 */
public class NestedSwingInput implements Player
{
    //--------------------------------------------------------------------
    private final JComponent              listenOn;
    private final List<Direction>         queue =
                                            new ArrayList<Direction>();
    private final Map<Direction, Integer> dirs;
    private final String                  name;

    private volatile boolean highPercision = true;


    //--------------------------------------------------------------------
    public NestedSwingInput(JComponent attachTo,
                            String playerName)
    {
        listenOn = attachTo;

        name = playerName;
        dirs = new HashMap<Direction, Integer>() {{
            put(Direction.NORTH, KeyEvent.VK_UP);
            put(Direction.SOUTH, KeyEvent.VK_DOWN);
            put(Direction.WEST,  KeyEvent.VK_LEFT);
            put(Direction.EAST,  KeyEvent.VK_RIGHT);
        }};
    }

    //--------------------------------------------------------------------
    private void setupKeyBindings(
            InputMap  inputMap,
            ActionMap actionMap)
    {
        for (InputCase inputCase : Arrays.asList(
                new InputCase(Direction.NORTH),
                new InputCase(Direction.SOUTH),
                new InputCase(Direction.WEST ),
                new InputCase(Direction.EAST ),
                new InputCase(KeyEvent.VK_ESCAPE, (Direction)null),
                new InputCase(KeyEvent.VK_SPACE, new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        highPercision = !highPercision;
                    }
                })))
        {
            inputCase.addTo(inputMap, actionMap);
        }
    }


    //--------------------------------------------------------------------
    private static class DirectionPressed extends AbstractAction
    {
        private final Direction        TRACKING;
        private final NestedSwingInput INPUT;

        public DirectionPressed(Direction        tracking,
                                NestedSwingInput input)
        {
            TRACKING = tracking;
            INPUT    = input;
        }

        public void actionPerformed( ActionEvent e )
        {
            if (INPUT       != null &&
                INPUT.queue != null &&
                    ( INPUT.queue.isEmpty() ||
                      TRACKING == null      ||
                    ! INPUT.queue.get(0).equals(TRACKING)))
            {
                INPUT.queue.add( TRACKING );
            }
        }
    }


    //--------------------------------------------------------------------
    public void startThinking()
    {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                setupKeyBindings(
                    listenOn.getInputMap(
                            JComponent.WHEN_IN_FOCUSED_WINDOW ),
                    listenOn.getActionMap());
            }
        });
    }

    public void stopThinking() {}


    //--------------------------------------------------------------------
    public void makeMove(
            BoardArrangement board,
            Snake you,
            MoveSpecifier yourMove,
            Collection<Snake> others)
    {
        try
        {
            while (true)
            {
                Thread.sleep( 10 );
                if (! queue.isEmpty())
                {
                    Direction dir = queue.remove(0);
                    if (dir == null ||
                        dir.isAvailableIn(board, you.head()))
                    {
                        yourMove.setDirection( dir );
                        return;
                    }
                    else
                    {
                        while (queue.isEmpty()) Thread.sleep(100);
                    }
                }
                else
                {
                    Direction dir = yourMove.latestDirection();
                    if (dir != null &&
                        dir.isAvailableIn(board, you.head()) &&
                        !highPercision)
                    {
                        yourMove.setDirection( dir );
                        return;
                    }
                }
            }
        }
        catch ( InterruptedException e )
        {
            e.printStackTrace();
        }
    }


    //--------------------------------------------------------------------
    @Override
    public String toString()
    {
        return name;
    }

    
    //--------------------------------------------------------------------
    private class InputCase
    {
        private KeyStroke keyEvent;
        private Action    toDo;
        private Direction direction;

        public InputCase(int key, Action act)
        {
            this( key );
            toDo = act;
        }
        public InputCase(Direction dir)
        {
            this(dirs.get(dir), dir);
        }
        public InputCase(int key, Direction dir)
        {
            this( key );
            direction = dir;
        }
        private InputCase(int key)
        {
            keyEvent = KeyStroke.getKeyStroke(key, 0);
        }

        public void addTo(
                InputMap  inputMap,
                ActionMap actionMap)
        {
            if (toDo == null)
            {
                inputMap.put( keyEvent, String.valueOf(direction) );
                actionMap.put(String.valueOf(direction),
                              new DirectionPressed(direction,
                                                    NestedSwingInput.this));
            }
            else
            {
                inputMap.put(  keyEvent, toDo );
                actionMap.put( toDo,     toDo );
            }
        }
    }
}

