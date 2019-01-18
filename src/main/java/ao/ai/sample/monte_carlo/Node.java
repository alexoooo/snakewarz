package ao.ai.sample.monte_carlo;

import ao.sw.engine.board.Direction;
import ao.util.math.rand.Rand;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 
 */
public class Node
{
    //--------------------------------------------------------------------
    private static final ExecutorService EXEC =
            Executors.newFixedThreadPool(2);

    private static final double MEAN_WEIGHT_FACTOR = 1;



    //--------------------------------------------------------------------
//    private final boolean wantMax;

    private int    visits;
    private Reward rewardSum;
    private Reward rewardSquareSum;

    private Direction act;
    private BiState   state;

    private Node kids[];

    private boolean optimize;


    //--------------------------------------------------------------------
    public Node(Direction act,
                BiState state,
                boolean optimize)
    {
        this.act   = act;
        this.state = state;

        visits          = 0;
        rewardSum       = new Reward();
        rewardSquareSum = new Reward();

        this.optimize = optimize;
    }
    public Node(BiState state, boolean optimize)
    {
        this(null, state, optimize);
    }

    //--------------------------------------------------------------------
    public int maxDepth()
    {
        if (kids == null) return 1;

        int depth = 0;
        for (Node nextChild : kids)
        {
            depth = Math.max(depth, nextChild.maxDepth());
        }
        return depth + 1;
    }

    //--------------------------------------------------------------------
//    public Node grandChildWithBiState(BiState biState)
//    {
//        for (Node nextChild  = child;
//                  nextChild != null;
//                  nextChild  = nextChild.sibling)
//        {
//            Node grandchild = child.childWithBiState( biState );
//            if (grandchild != null)
//            {
//                return grandchild;
//            }
//        }
//        return null;
//    }
    public Node childWithBiState(BiState biState)
    {
        if (kids == null) return null;

        for (Node nextChild : kids)
        {
            if (nextChild.state.equals( biState ))
            {
                return nextChild;
            }
        }
        return null;
    }
    public BiState state()
    {
        return state;
    }


    //--------------------------------------------------------------------
    public Direction act()
    {
        return act;
    }

    public int visits()
    {
        return visits;
    }


    //--------------------------------------------------------------------
    public Direction optimize()
    {
        Node best = optimizeInternal();
        return best.act;
    }
    public Node optimizeInternal()
    {
        if (kids == null) return this;

        Node   optimal       = null;
        double optimalReward = -1000000;
        for (Node nextChild : kids)
        {
//            Node   subOptimal = nextChild.optimizeInternal();
//            double reward =
//                    ( wantMax
//                      ? subOptimal.normalize()
//                      : subOptimal.normalize().compliment()
//                    ).value();

            double reward = nextChild.averageReward();
//            double reward =
//                    optimize
//                    ? nextChild.backupValue()
//                    : nextChild.averageReward();
//            double reward = nextChild.averageOfMaxes();
//            double reward = nextChild.minimizeInternal().averageReward();
//            double reward =
//                    (nextChild.child != null)
//                     ? 1.0 - nextChild.maximizeInternal().averageReward()
//                     : nextChild.averageReward();
//            double reward = nextChild.maximizeInternal().averageReward();

            if (reward > optimalReward)
            {
                optimal       = nextChild;
                optimalReward = reward;
            }
        }
        return (optimal == null) ? this : optimal;
    }

    private Reward normalize()
    {
        return rewardSum.normalize( visits + 1 );
    }


//    private Node minimizeInternal()
//    {
//        Node   least       = null;
//        double leastReward = 100000;
//        for (Node nextChild  = child;
//                  nextChild != null;
//                  nextChild  = nextChild.sibling)
//        {
//            double reward = nextChild.optimizeInternal().averageReward();
//
//            if (reward < leastReward)
//            {
//                least       = nextChild;
//                leastReward = reward;
//            }
//        }
//        return (least == null) ? this : least;
//    }

    private double backupValue()
    {
        return averageReward();
//        if (child == null)
//        {
//            return averageReward();
//        }
//        else if (child.sibling == null)
//        {
////            return child.rewardSum.normalize( visits )
////                            .compliment().value();
//            return 1.0 - child.backupValue();
//        }
//
//        Node   mostVisitedNode   = null;
//        Node   greatestValueNode = null;
//        int    mostVisits        = -100000;
//        double greatestValue     = -100000;
//
////        double meanWeight = 2 * MEAN_WEIGHT_FACTOR;
////        if (visits > 16 * MEAN_WEIGHT_FACTOR)
////        {
////            meanWeight = (double) visits / (16 * MEAN_WEIGHT_FACTOR);
////        }
//
//        for (Node nextChild  = child;
//                  nextChild != null;
//                  nextChild  = nextChild.sibling)
//        {
//            if (mostVisits <= nextChild.visits)
//            {
//                mostVisitedNode = nextChild;
//                mostVisits      = nextChild.visits;
//            }
//            double nextBackUp = nextChild.backupValue();
//            if (greatestValue <= nextBackUp)
//            {
//                greatestValueNode = nextChild;
//                greatestValue     = nextBackUp;
//            }
//        }
//
////        double value = averageReward();
////        double weightedA = (greatestValueNode.visits * greatestValue +
////                            meanWeight * value) /
////                            (greatestValueNode.visits + meanWeight);
////        double weightedB = (mostVisits * mostVisitedNode.backupValue() +
////                            meanWeight * value) /
////                            (mostVisits + meanWeight);
////        if (mostVisitedNode != greatestValueNode)
////        {
////            if (mostVisits > greatestValueNode.visits)
////            {
////                value = weightedB;
////            }
////            else if (greatestValue < averageReward())
////            {
////                value = weightedA;
////            }
////        }
////        else
////        {
////            value = weightedA;
////        }
//        return 1.0 - greatestValueNode.averageReward();
    }


//    private double averageOfMaxes()
//    {
//        int    i   = 0;
//        Reward avg = new Reward();
//        for (Node nextChild  = child;
//                  nextChild != null;
//                  nextChild  = nextChild.sibling)
//        {
//            if (nextChild.child == null)
//            {
//                avg = avg.plus( nextChild.rewardSum );
//            }
//            else
//            {
//                Node max = nextChild.optimizeInternal();
//                avg = avg.plus( max.rewardSum );
//            }
//
//            i++;
//        }
//        return (i == 0)
//               ? 1.0 - averageReward()
//               : avg.averagedOver(i);
////        return (i == 0)
////               ? averageReward()
////               : avg.averagedOver(i);
//    }


    private double averageReward()
    {
        return rewardSum.averagedOver( visits + 1 );
    }
    private double averageRewardSquared()
    {
        double averageReward = averageReward();
        return averageReward * averageReward;
    }
    private double averageSquaredReward()
    {
        return rewardSquareSum.averagedOver( visits );
    }


    //--------------------------------------------------------------------
    public synchronized void playSimulation()
    {
        LinkedList<Node> path = new LinkedList<Node>();
        path.add(this);

        while (! path.getLast().unvisited())
        {
            Node selectedChild =
                    path.getLast().descendByUCB1();
            if (selectedChild == null) break;

            path.add( selectedChild );
        }

//        if (optimize)
//        {
//            propagateValue(path, path.getLast().duelMonteCarloValue());
//        }
//        else
//        {
            propagateValue(path, path.getLast().monteCarloValue());
//        }
    }

    private void propagateValue(LinkedList<Node> path, Reward reward)
    {
        Reward maxiMax = reward.compliment();
        for (int i = path.size() - 1; i >= 0; i--)
        {
            Node step = path.get(i);

            step.rewardSum = step.rewardSum.plus(maxiMax);
            step.rewardSquareSum =
                    step.rewardSquareSum.plus(
                            maxiMax.square());
            step.visits++;

            maxiMax = maxiMax.compliment();
        }
    }

    private Reward duelMonteCarloValue()
    {
        Future<Reward> monteCarloA =
                EXEC.submit(new MonteCarloCallable());
        Future<Reward> monteCarloB =
                EXEC.submit(new MonteCarloCallable());

        try
        {
            Reward rewardA = monteCarloA.get();
            Reward rewardB = monteCarloB.get();

            return rewardA.plus( rewardB ).averageOverTwo();
        }
        catch (Exception e)
        {
            throw new Error( e );
        }
    }
    private Reward monteCarloValue()
    {
        Rollout r = state.rollout();

//        double lengthBonus = Math.min(r.length() / 100000, 0.01);
//        return r.isTie()             ? new Reward(0.19 + lengthBonus) :
//               r.nextToActIsWinner() ? new Reward(1.00              ) :
//                                       new Reward(       lengthBonus);
        //        return wantMax
//               ? nextToActReward.compliment()
//               : nextToActReward;
        return ( r.isTie()             ? new Reward(0.5) :
                 r.nextToActIsWinner() ? new Reward(1.0) :
                                         new Reward(0.0) );
    }


    private void generateKids()
    {
        if (kids != null) return;

        List<Node> children = state.generateNodes();
        kids = children.toArray(
                new Node[children.size()]);
    }

    //--------------------------------------------------------------------
    public Node descendByUCB1()
    {
        generateKids();

        double greatestUtc   = Integer.MIN_VALUE;
        Node   greatestChild = null;
        for (Node nextChild : kids)
        {
            double utcValue;
            if (nextChild.unvisited())
            {
                utcValue = 10000 + 1000 * Rand.nextDouble();
//                utcValue =
//                        optimize
//                        ? 1.0 + Rand.nextDouble()/10000
//                        : 10000 + 1000 * Rand.nextDouble();
//                utcValue = 1.0;
//                utcValue = 1.0 + Rand.nextDouble()/10;
            }
            else
            {
                if (optimize)
                {
                    utcValue =
                        nextChild.averageReward() +
                        Math.sqrt((Math.log(visits) /
                                         nextChild.visits) *
                                   Math.min(
                                       0.25,
                                       nextChild.varianceCeiling(
                                               visits)));
                }
                else
                {
                    utcValue =
                        nextChild.averageReward() +
                        Math.sqrt(Math.log(visits) / (5*nextChild.visits));
//                    utcValue =
//                        nextChild.averageReward() +
//                        Math.sqrt(2.0 * Math.log(playsSoFar) / nextChild.visits);
                }

//                utcValue =
//                        nextChild.averageReward() +
//                        Math.sqrt((Math.log(playsSoFar) /
//                                         nextChild.visits) *
//                                   Math.min(
//                                       0.25,
//                                       nextChild.varianceCeiling(
//                                               playsSoFar)));
//                utcValue =
//                        nextChild.averageReward() +
//                        Math.sqrt(Math.log(visits) / (5*nextChild.visits));
//                utcValue =
//                        nextChild.averageReward() +
//                        Math.sqrt(2.0 * Math.log(playsSoFar) / nextChild.visits);
            }

            if (utcValue > greatestUtc)
            {
                greatestUtc   = utcValue;
                greatestChild = nextChild;
            }
        }
        
        return greatestChild;
    }

    private double varianceCeiling(int playsSoFar)
    {
        return averageSquaredReward()
               - averageRewardSquared()
               + Math.sqrt(2.0 * Math.log(playsSoFar) / visits);
    }

    private boolean unvisited()
    {
        return visits == 0;
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return toString(0);
    }
    public String toString(int indent)
    {
        if (visits == 0) return "";

        StringBuilder str = new StringBuilder();
        str.append('\n');

        str.append( indent(indent)  )
           //.append( "============"  )
           //.append( "\t"            )
            .append( "  "            )
           .append( visits          )
           .append( "  "            )
           .append( rewardSum       )
           .append( "  "            )
           .append( averageReward() )
           .append( "  "            )
           .append( act             );

        for (Node nextChild : kids)
        {
            str.append( nextChild.toString(indent + 2) );
        }

        return str.toString();
    }

    private String indent(int size)
    {
        StringBuilder indent = new StringBuilder(size);
        for (int i = 0; i < size; i++)
        {
            indent.append(' ');
        }
        return indent.toString();
    }



    //--------------------------------------------------------------------
    private class MonteCarloCallable implements Callable<Reward>
    {
        public Reward call() throws Exception
        {
            return monteCarloValue();
        }
    }
}
