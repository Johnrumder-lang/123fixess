package lockon;

import java.util.HashSet;
import java.util.Set;

public final class MiningPlanTest {
    private static void check(boolean value,String description) {
        if(!value) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        Set<String> cleared=new HashSet<>();
        cleared.add("0:0");cleared.add("0:1");cleared.add("0:2");
        for(int step=1;step<=128;step++) {
            int previous=-MiningPlan.depth(step-1);
            int current=-MiningPlan.depth(step);
            check(previous-current>=0 && previous-current<=1,"bounded descent");
            int height=MiningPlan.clearance(previous,current);
            for(int y=0;y<height;y++) cleared.add(step+":"+(current+y));
        }
        // Walk every generated waypoint in reverse, checking feet, head and
        // space above the lower departing step for an upward jump.
        for(int step=128;step>0;step--) {
            int from=-MiningPlan.depth(step),to=-MiningPlan.depth(step-1);
            check(cleared.contains((step-1)+":"+to),"return feet clear");
            check(cleared.contains((step-1)+":"+(to+1)),"return head clear");
            if(to>from) check(cleared.contains(step+":"+(from+2)),"exit jump headroom");
        }
        check(MiningPlan.depth(8)==7,"reach stone depth sooner");
        check(MiningPlan.pickup(2,false,false,false)==MiningPlan.Pickup.WAIT,"wait for delayed item spawn");
        check(MiningPlan.pickup(12,true,true,false)==MiningPlan.Pickup.COLLECT,"walk to actual drop");
        check(MiningPlan.pickup(20,true,false,false)==MiningPlan.Pickup.WAIT,"disappearance alone isn't our pickup");
        check(MiningPlan.pickup(20,true,false,true)==MiningPlan.Pickup.DONE,"inventory confirms pickup");
        check(MiningPlan.pickup(20,false,false,true)==MiningPlan.Pickup.DONE,"immediate automatic pickup");
        check(MiningPlan.pickup(50,false,false,false)==MiningPlan.Pickup.NO_DROP,"blocks without drops don't wait forever");
        check(MiningPlan.pickup(210,true,true,false)==MiningPlan.Pickup.BLOCKED,"unreachable drop requires stop");
        check(MiningPlan.pickup(210,true,false,false)==MiningPlan.Pickup.BLOCKED,"unconfirmed pickup requires stop");
        check(MiningPlan.pickup(30,true,true,true)==MiningPlan.Pickup.COLLECT,"collect remaining drops before proceeding");
        check(!MiningPlan.canPlaceTower(64.9,64,10),"do not place inside player feet");
        check(MiningPlan.canPlaceTower(65.1,64,10),"place after rising above block");
        check(!MiningPlan.canPlaceTower(65.1,64,2),"wait between placement requests");
        System.out.println("Mining staircase geometry and pickup confirmation tests passed");
    }
}
