package lockon;

public final class AutoplayPlanTest {
    private static void check(AutoplayPlan.Action expected, AutoplayPlan.Action actual) {
        if(expected != actual)
            throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        check(AutoplayPlan.Action.GATHER_WOOD,
            AutoplayPlan.next(0,0,0,false,false,false));
        check(AutoplayPlan.Action.MAKE_PLANKS,
            AutoplayPlan.next(5,0,0,false,false,false));
        check(AutoplayPlan.Action.MAKE_PLANKS,
            AutoplayPlan.next(6,0,0,false,false,false));
        check(AutoplayPlan.Action.MAKE_TABLE,
            AutoplayPlan.next(1,20,0,false,false,false));
        check(AutoplayPlan.Action.PLACE_TABLE,
            AutoplayPlan.next(1,16,0,false,false,true));
        check(AutoplayPlan.Action.MAKE_STICKS,
            AutoplayPlan.next(1,18,0,false,true,false));
        check(AutoplayPlan.Action.MAKE_PICKAXE,
            AutoplayPlan.next(1,18,4,false,true,false));
        check(AutoplayPlan.Action.READY,
            AutoplayPlan.next(0,0,0,true,true,false));
        check(AutoplayPlan.Action.PLACE_TABLE,
            AutoplayPlan.next(0,0,0,true,false,true));
        check(AutoplayPlan.Action.MAKE_STICKS,
            AutoplayPlan.next(0,5,0,false,true,false));
        check(AutoplayPlan.Action.MAKE_PICKAXE,
            AutoplayPlan.next(0,3,2,false,true,false));
        System.out.println("Autoplay progression: eleven tests passed");
    }
}
