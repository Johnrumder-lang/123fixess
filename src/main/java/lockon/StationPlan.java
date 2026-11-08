package lockon;

/** Confirmation/retry policy for one station in one base-processing pass. */
final class StationPlan {
    enum Action { COMPLETE, CRAFT, PLACE, WAIT, FAILED }
    private int attempts;
    private long sentAt=-1;

    void reset() { attempts=0;sentAt=-1; }
    void sent(long tick) { attempts++;sentAt=tick; }
    boolean attempted() { return attempts>0; }
    Action next(long tick,boolean present,int inventory,boolean crafting) {
        if(present) return Action.COMPLETE;
        if(crafting) return Action.WAIT;
        if(sentAt>=0 && tick-sentAt<40) return Action.WAIT;
        if(attempts>=3 || (attempts>0 && inventory==0)) return Action.FAILED;
        return inventory>0?Action.PLACE:Action.CRAFT;
    }
}
