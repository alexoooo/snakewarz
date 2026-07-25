package ao.sw.engine.player;



import ao.util.math.rand.Rand;

import java.awt.*;
import java.awt.image.BufferedImage;


public class BasicPlayerDisplay implements PlayerDisplay
{
    //--------------------------------------------------------------------
    private static PlayerDisplay POOL[] =
            new PlayerDisplay[64];

    private static int nextPoolIndex = 0;

    static
    {
        for (int i = 0; i < POOL.length; i++)
        {
            POOL[i] = new BasicPlayerDisplay();
        }
    }

    public static PlayerDisplay nextInstance()
    {
        int index = nextPoolIndex;
        nextPoolIndex = (nextPoolIndex + 1) % POOL.length; 
        return POOL[index];
    }


    //--------------------------------------------------------------------
    private static final int MIN_COLOUR_COMPONENT = 150;
    private static final int MAX_COLOUR_COMPONENT = 255;

    private static final int IMAGE_SIZE = 10;


    //--------------------------------------------------------------------
    private final Image headImage;
    private final Image bodyImage;
    private final Image tailImage;
    private final Image ghostImage;

    private BasicPlayerDisplay()
    {
        Color headColour = new Color(
                            randomColourComponent(),
                            randomColourComponent(),
                            randomColourComponent());
        Color  bodyColour = headColour.darker();
        Color  tailColour = bodyColour;
        Color ghostColour = new Color(bodyColour.getRed()   + 30,
                                      bodyColour.getGreen() + 30,
                                      bodyColour.getBlue()  + 30);

         headImage = colouredImage( headColour);
         bodyImage = colouredImage( bodyColour);
         tailImage = colouredImage( tailColour);
        ghostImage = colouredImage(ghostColour);

        //ghostImage.getGraphics().setColor(Color.BLACK);
        //ghostImage.getGraphics().fillRect(0,0,
        //                                  ghostImage.getWidth(null),
        //                                  ghostImage.getHeight(null));
        //ghostImage.flush();
    }

    public Image head()
    {
        return headImage;
    }

    public Image body()
    {
        return bodyImage;
    }

    public Image tail()
    {
        return tailImage;
    }

    public Image ghost()
    {
        return ghostImage;
    }


    //--------------------------------------------------------------------
    private static Image colouredImage(Color colour)
    {
        BufferedImage img =
                new BufferedImage(
                        IMAGE_SIZE, IMAGE_SIZE,
                        BufferedImage.TYPE_INT_BGR);
        Graphics2D g = (Graphics2D) img.getGraphics();

        g.setColor( colour );
        g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);

//        g.setColor(colour.darker().darker());
//        g.setStroke(new BasicStroke());
//        g.drawRect(0,0, IMAGE_SIZE-1, IMAGE_SIZE-1);
        return img;
    }

    private static int randomColourComponent()
    {
        return MIN_COLOUR_COMPONENT +
                Rand.nextInt(
                        MAX_COLOUR_COMPONENT -
                        MIN_COLOUR_COMPONENT + 1);
    }
}
