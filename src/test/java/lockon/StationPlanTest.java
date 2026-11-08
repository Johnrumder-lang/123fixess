package lockon;

public final class StationPlanTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        StationPlan p=new StationPlan();
        check(p.next(0,false,0,false)==StationPlan.Action.CRAFT,"missing station requests one craft");
        check(p.next(1,false,0,true)==StationPlan.Action.WAIT,"active recipe cannot start placement or a second craft");
        check(p.next(2,false,1,false)==StationPlan.Action.PLACE,"crafted inventory item must be placed");
        p.sent(3);
        for(int tick=4;tick<43;tick++) {
            check(p.next(tick,false,0,false)==StationPlan.Action.WAIT,"consumed item waits for world acknowledgement "+tick);
            check(p.next(tick,false,1,false)==StationPlan.Action.WAIT,"rejected prediction cannot spam another placement "+tick);
        }
        check(p.next(30,true,0,false)==StationPlan.Action.COMPLETE,"world confirmation ends station stage");
        check(p.next(43,false,0,false)==StationPlan.Action.FAILED,"lost/unconfirmed item never triggers duplicate crafting");
        check(p.next(43,false,1,false)==StationPlan.Action.PLACE,"retained item allows delayed retry");
        p.sent(43);p.sent(83);
        check(p.next(123,false,1,false)==StationPlan.Action.FAILED,"three failed sends end this pass");
        p.reset();
        check(p.next(0,false,1,false)==StationPlan.Action.PLACE,"restart removes stale retry clock");
        check(p.next(0,true,5,false)==StationPlan.Action.COMPLETE,"existing station wins over spare inventory items");
        System.out.println("Station lifecycle: "+checks+" checks passed");
    }
}
