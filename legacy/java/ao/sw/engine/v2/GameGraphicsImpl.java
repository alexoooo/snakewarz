package ao.sw.engine.v2;

import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.BoardLocationImpl;
import ao.sw.engine.player.PlayerAvatar;
import ao.sw.engine.player.PlayerDisplay;

import javax.swing.*;
import java.awt.*;
import java.awt.image.VolatileImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Swing graphics
 */
public class GameGraphicsImpl
        extends JComponent
        implements GameGraphics
{
    //-------------------------------------------------
    private static final int PREFERRED_CELL_SIZE = 20;

    private static final Color BACK_COLOR   = Color.WHITE;
//    private static final Color BORDER_COLOR = Color.BLACK;


    //-------------------------------------------------
    private final int ROWS;
    private final int COLS;

    private PlayerAvatar highlighted;

    private Collection<SnakeAndAvatar> buffer
            = new ArrayList<SnakeAndAvatar>();
    private VolatileImage backBuffer = null;

    public GameGraphicsImpl( int rows, int columns )
    {
        ROWS = rows;
        COLS = columns;

        clear();

        //setBackground(BACK_COLOR);
        setPreferredSize(
                new Dimension(
                        Toolkit.getDefaultToolkit().getScreenSize().width  / 2,
                        Toolkit.getDefaultToolkit().getScreenSize().height / 2) );
//        setMaximumSize	(
//                new Dimension(
//                        MINIMUM_CELL_SIZE   * COLS,
//                        MINIMUM_CELL_SIZE   * ROWS) );
//        setMinimumSize	(
//                new Dimension(
//                        MAXIMUM_CELL_SIZE   * COLS,
//                        MAXIMUM_CELL_SIZE   * ROWS) );
        setOpaque(  false );
        setVisible( true  );

//        addComponentListener(
//            new ComponentAdapter() {
//                public void componentResized(ComponentEvent e) {
////                    redrawBoard();
//                }
//            }
//        );
    }

    public Dimension getSize()
    {
        return new Dimension(innerSize(COLS),
                             innerSize(ROWS));
    }



    //-------------------------------------------------
    public synchronized void highlight(PlayerAvatar avatar)
    {
        highlighted = avatar;
        repaint();
    }


    //-------------------------------------------------
    private void createBackBuffer()
    {
        if (backBuffer != null)
        {
            backBuffer.flush();
            backBuffer = null;
        }

        backBuffer =
                createVolatileImage(
                        COLS * PREFERRED_CELL_SIZE,
                        ROWS * PREFERRED_CELL_SIZE);

    }

    public synchronized void paint(Graphics g)
    {
        if (backBuffer == null)
        {
            createBackBuffer();
        }
        
        do
        {
            int valCode = backBuffer.validate(getGraphicsConfiguration());
            if (valCode == VolatileImage.IMAGE_RESTORED) {
                // redraw anyways
            } else if (valCode == VolatileImage.IMAGE_INCOMPATIBLE) {
                createBackBuffer();
            }

            Graphics gBB = backBuffer.getGraphics();
            gBB.setColor( BACK_COLOR );
            gBB.fillRect(0, 0,
                         backBuffer.getWidth(),
                         backBuffer.getHeight());

            Image empty = empty();
            for (int row = 0; row < ROWS; row++)
            {
                for (int col = 0; col < COLS; col++)
                {
                    drawCell(gBB,
                             empty,
                             new BoardLocationImpl(row, col),
                             false);
                }
            }
            for (SnakeAndAvatar snakeAndAvatar : buffer)
            {
                draw(gBB, snakeAndAvatar.AVATAR, snakeAndAvatar.SNAKE);
            }

            //g.clearRect(0, 0, getWidth(), getHeight());
            g.drawImage(
                    backBuffer,
                    (getWidth()  - innerSize(COLS))/ 2,
                    (getHeight() - innerSize(ROWS))/ 2,
                    innerSize(COLS),
                    innerSize(ROWS),
                    this);
        }
        while (backBuffer.contentsLost());
    }

    private Image empty()
    {
        BufferedImage img =
                new BufferedImage(
                        32, 32,
                        BufferedImage.TYPE_INT_BGR);
        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor( Color.GRAY.darker().darker().darker() );
        g.drawRect(0, 0, 32, 32);
        return img;
    }

    private int innerSize(int numCells)
    {
        double cellSize =
                Math.min(
                        ((double) getWidth())  / COLS,
                                  getHeight()  / ROWS);

        return (int) (numCells * cellSize);
    }


    public Component proxy()
    {
        return this;
    }


    //-------------------------------------------------
    public synchronized void draw(PlayerDisplay avatar, Snake snake)
    {
        buffer.add( new SnakeAndAvatar(avatar, snake) );
    }

    public synchronized void clear()
    {
        buffer.clear();
        highlighted = null;
    }

    private void draw(
            Graphics board,
            PlayerDisplay avatar,
            Snake snake)
    {
        boolean isHighlighted =
                (highlighted != null) && highlighted.equals(avatar);

        BoardLocation tail = null;
        BoardLocation head = null;
        for (BoardLocation cell : snake.body())
        {
            if (tail == null)
            {
                tail = cell;
            }
            head = cell;

            drawCell(board, avatar.body(), cell, isHighlighted);
        }
        drawCell(board, (snake.willGrow()
                         ? avatar.tail()
                         : avatar.ghost()), tail, isHighlighted);
        drawCell(board, avatar.head(), head, isHighlighted);
    }

    private void drawCell(
            Graphics board,
            Image img,
            BoardLocation where,
            boolean isHighlighted)
    {
        board.drawImage(
                img,
                where.getColumn() * PREFERRED_CELL_SIZE,
                where.getRow()    * PREFERRED_CELL_SIZE,
                PREFERRED_CELL_SIZE,
                PREFERRED_CELL_SIZE,
                null);

        if (isHighlighted)
        {
            int lineWidth = 1;
            int sideGap   = (int)(PREFERRED_CELL_SIZE * 0.2 + 0.99);

            int halfSize = Math.round(0.5f * PREFERRED_CELL_SIZE);
            int topLeftX = where.getColumn() * PREFERRED_CELL_SIZE;
            int topLeftY = where.getRow()    * PREFERRED_CELL_SIZE;
            board.setColor( new Color(30, 30, 30, 50) );

            // vertical
            board.drawLine(topLeftX + halfSize,
                           topLeftY + sideGap,
                           topLeftX + halfSize,
                           topLeftY + PREFERRED_CELL_SIZE - sideGap);

            // horizontal
            board.drawLine(topLeftX + sideGap,
                           topLeftY + halfSize,
                           topLeftX + PREFERRED_CELL_SIZE - sideGap,
                           topLeftY + halfSize);
        }
    }

    
    //--------------------------------------------------------------------
    public void flush()
    {
        repaint();
    }


    //-------------------------------------------------
    // private struct not class
    private static class SnakeAndAvatar
    {
        public final PlayerDisplay AVATAR;
        public final Snake         SNAKE;

        public SnakeAndAvatar(PlayerDisplay avatar, Snake snake)
        {
            AVATAR = avatar;
            SNAKE  = snake;
        }
    }
}
