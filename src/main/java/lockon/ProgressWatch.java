package lockon;

/** Progress toward a pinned waypoint; oscillation is not forward progress. */
final class ProgressWatch {
    private Object target;
    private double best;
    private long improvedAt;
    void reset() {target=null;}
    boolean stalled(Object next,double distance,long tick) {
        if(!next.equals(target)) {target=next;best=distance;improvedAt=tick;return false;}
        if(distance<best-0.04) {best=distance;improvedAt=tick;}
        return tick-improvedAt>=35;
    }
}
