package lockon;

/** Geometry and drop-wait decisions shared with deterministic regression tests. */
final class MiningPlan {
    enum Pickup { WAIT, COLLECT, DONE, NO_DROP, BLOCKED }

    static int depth(int step) {
        int distance=Math.max(0,step);
        return Math.min(48,Math.min(distance,6)+Math.max(0,distance-6)/2);
    }
    static boolean canPlaceTower(double feetY,int blockY,int sinceLastUse) {
        return feetY>=blockY+1.02 && sinceLastUse>=4;
    }
    static int clearance(int fromY,int toY) { return toY<fromY?3:2; }

    static Pickup pickup(int elapsed,boolean seen,boolean visible,boolean gained) {
        if(visible) return elapsed>=200?Pickup.BLOCKED:Pickup.COLLECT;
        if(elapsed<10) return Pickup.WAIT;
        if(gained) return Pickup.DONE;
        if(seen) return elapsed>=200?Pickup.BLOCKED:Pickup.WAIT;
        return elapsed>=40?Pickup.NO_DROP:Pickup.WAIT;
    }
}
