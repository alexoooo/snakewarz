package ao.ai.sample.path_finder;

import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.Direction;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * A link in the A* path chain.
 */
public class Path implements Comparable<Path>
{
    private final Path prev;
    private final BoardLocation step;
    private final float costToGo;
    private final float costSoFar;


    //---------------------------
    public Path(
            BoardLocation startingPoint,
            BoardLocation finalGoal)
    {
        prev      = null;
        step      = startingPoint;
        costToGo  = huristic(startingPoint, finalGoal);
        costSoFar = 0;
    }

    private Path(
            Path previous,
            BoardLocation current,
            BoardLocation finalGoal)
    {
        prev      = previous;
        step      = current;
        costToGo  = huristic(current, finalGoal);
        costSoFar = previous.costSoFar + 1;
    }

    //---------------------------
    private static float huristic(
            BoardLocation startingPoint,
            BoardLocation finalGoal)
    {
        return startingPoint.distanceTo( finalGoal );
    }


    //--------------------------
    public BoardLocation step()
    {
        return step;
    }



    //---------------------------
    public Collection<Path> successors(
            BoardArrangement inBoard,
            BoardLocation    finalGoal)
    {
        Collection<Path> successors = new ArrayList<Path>(4);

        for (Direction available :
                Direction.availableFrom(inBoard, step))
        {
            successors.add(
                    new Path(
                            this,
                            available.translate(step),
                            finalGoal));
        }

        return successors;
    }


    //--------------------------
    public Collection<BoardLocation> steps()
    {
        LinkedList<BoardLocation> steps =
                new LinkedList<BoardLocation>();

        for (Path cursor  = this;
                  cursor != null;
                  cursor  = cursor.prev)
        {
            steps.addFirst( cursor.step() );
        }

        return steps;
    }


    //--------------------------
    public boolean isShorterSoFarThan(Path o)
    {
        return (costSoFar < o.costSoFar);
    }

    public int compareTo(Path o)
    {
        int soFarCmp = Float.compare(costSoFar, o.costSoFar);

        return (soFarCmp != 0)
                ? soFarCmp
                : Float.compare(costToGo, o.costToGo);
    }


    //---------------------------
    public int hashCode()
    {
        return step.hashCode();
    }

    public boolean equals(Object other)
    {
        return  other != null         &&
                other instanceof Path &&
                step.equals(
                    ((Path) other).step);
    }
}
