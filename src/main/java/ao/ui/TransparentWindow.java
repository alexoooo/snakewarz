package ao.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;


public class TransparentWindow
        extends JWindow
        implements MouseMotionListener, FocusListener
{
    private Graphics tempImgGraphics;
    private Image backgroundImage,temporaryImage;
    private Point mousePointer;

    public TransparentWindow() {
        init();
        setVisible(true);
    }

    public void init(){
        addMouseMotionListener(this);
        setBounds(170,170,100,100);
        capture();
        addFocusListener(this);
    }

    public void focusGained(FocusEvent aFocusEvent) {
        Point aPoint = getLocation();
        setLocation(15000,0);
        capture();
        setLocation(aPoint);
    }

    public void focusLost(FocusEvent aFocusEvent){}

    public void capture() {
        Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
        try {
            Robot aRobot = new Robot();
            Rectangle rect = new Rectangle(0,0,dim.width,dim.height);
            backgroundImage = aRobot.createScreenCapture(rect);
        }catch(AWTException awte) {
            System.out.println("robot excepton occurred");
        }
    }

    public void mouseDragged(MouseEvent aMouseEvent) {
        Point aPoint = aMouseEvent.getPoint();
        int x = getX()+aPoint.x-mousePointer.x;
        int y = getY()+aPoint.y-mousePointer.y;
        setLocation(x,y);
        Graphics graphics = getGraphics();
        paint(graphics);
    }

    public void mouseMoved(MouseEvent aMouseEvent){
        mousePointer = aMouseEvent.getPoint();
    }

    public void paint(Graphics graphics){
        if (temporaryImage == null) {
            temporaryImage = createImage(getWidth(),getHeight());
            tempImgGraphics = temporaryImage.getGraphics();
        }
        tempImgGraphics.drawImage(backgroundImage,0,0,getWidth(),getHeight(),
                                  getX(),getY(),getX()+getWidth(),getY()+getHeight(),null);
        graphics.drawImage(temporaryImage,0,0,null);
        //tempImgGraphics.setColor(Color.red);
        //tempImgGraphics.drawRect(10,20,70,70);
        //tempImgGraphics.setColor(Color.red);
        //tempImgGraphics.drawString("Sushil",30,30);

    }

    public static void main (String[] args){
        new TransparentWindow();
    }
}