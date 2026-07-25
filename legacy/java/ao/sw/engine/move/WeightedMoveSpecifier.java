package ao.sw.engine.move;

import ao.sw.engine.board.Direction;
import ao.sw.engine.player.MoveSpecifier;
import ao.util.math.rand.Rand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Highest weighted direction wins.
 */
public class WeightedMoveSpecifier
{
    //--------------------------------------------------------------------
    private MoveSpecifier DELEGET;
    private Map<Direction, Double> weightedDirs;


    //--------------------------------------------------------------------
    public WeightedMoveSpecifier(MoveSpecifier deleget)
    {
        DELEGET      = deleget;
        weightedDirs = new HashMap<Direction, Double>();
    }

    public void setDirection(Direction whereToGo, double weight)
    {
        Double current = weightedDirs.get(whereToGo);
        double currentVal = (current == null ? 0 : current);
        weightedDirs.put(whereToGo, currentVal + weight);

        commitWeights();
    }

    private void commitWeights()
    {
        List<Direction> choices = new ArrayList<Direction>(4);
        double maxChoiceWeight  = Double.MIN_VALUE;

        for (Map.Entry<Direction, Double> weightedDir :
                weightedDirs.entrySet())
        {
//            double weight = Rand.nextDouble(weightedDir.getValue());
            double weight = weightedDir.getValue();
            if (weight > maxChoiceWeight)
            {
                choices.clear();
                maxChoiceWeight = weight;
            }
            if (Math.abs(weight - maxChoiceWeight) < .0000001)
            {
                choices.add( weightedDir.getKey() );
            }
        }

        if (! choices.isEmpty())
        {
            DELEGET.setDirection(
                    Rand.fromList(choices));
        }
    }
}

