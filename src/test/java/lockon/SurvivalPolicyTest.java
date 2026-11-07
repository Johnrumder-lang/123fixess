package lockon;

/** Offline safety and material-flow assertions. */
public final class SurvivalPolicyTest {
    private static int passed;
    private static void expect(boolean result) {
        if (!result) throw new AssertionError("SurvivalPolicy test " + passed);
        passed++;
    }

    public static void main(String[] args) {
        expect(SurvivalPolicy.decide(0,20,false,false,false,false,20,0,32)
            == SurvivalPolicy.Decision.STOP);
        expect(SurvivalPolicy.decide(20,20,false,true,false,false,20,0,32)
            == SurvivalPolicy.Decision.STOP);
        expect(SurvivalPolicy.decide(8,20,false,false,false,false,20,0,32)
            == SurvivalPolicy.Decision.RETREAT);
        expect(SurvivalPolicy.decide(20,6,false,false,false,false,20,0,32)
            == SurvivalPolicy.Decision.EAT);
        expect(SurvivalPolicy.decide(20,20,false,false,false,true,20,0,32)
            == SurvivalPolicy.Decision.DEFEND);
        expect(SurvivalPolicy.decide(20,6,false,false,false,true,20,0,32)
            == SurvivalPolicy.Decision.DEFEND);
        expect(SurvivalPolicy.decide(20,14,false,false,false,false,20,0,32)
            == SurvivalPolicy.Decision.MINE);
        expect(SurvivalPolicy.decide(20,20,false,false,false,false,2,0,32)
            == SurvivalPolicy.Decision.RETURN_BASE);
        expect(SurvivalPolicy.decide(20,20,false,false,false,false,20,32,32)
            == SurvivalPolicy.Decision.RETURN_BASE);
        expect(SurvivalPolicy.decide(20,20,false,false,false,false,20,4,32)
            == SurvivalPolicy.Decision.MINE);
        expect(!SurvivalPolicy.mineCorridorBlock(false,false,true,true));
        expect(!SurvivalPolicy.mineCorridorBlock(true,true,true,true));
        expect(!SurvivalPolicy.mineCorridorBlock(true,false,false,true));
        expect(SurvivalPolicy.mineCorridorBlock(true,false,true,true));
        expect(SurvivalPolicy.shouldSmelt("minecraft:raw_iron"));
        expect(SurvivalPolicy.shouldSmelt("minecraft:deepslate_gold_ore"));
        expect(!SurvivalPolicy.shouldSmelt("minecraft:diamond"));
        expect(SurvivalPolicy.canCompact("minecraft:iron_ingot",9));
        expect(!SurvivalPolicy.canCompact("minecraft:iron_ingot",8));
        expect(!SurvivalPolicy.canCompact("minecraft:dirt",64));
        expect(SurvivalPolicy.shouldFlee(20,true,1,true));
        expect(SurvivalPolicy.shouldFlee(10,false,1,true));
        expect(SurvivalPolicy.shouldFlee(20,false,3,true));
        expect(SurvivalPolicy.shouldFlee(20,false,1,false));
        expect(!SurvivalPolicy.shouldFlee(20,false,1,true));
        expect(SurvivalPolicy.shouldSmelt("minecraft:potato"));
        expect(SurvivalPolicy.shouldSmelt("minecraft:beef"));
        expect(!SurvivalPolicy.shouldSmelt("minecraft:poisonous_potato"));
        expect(SurvivalPolicy.safeDrop(6,20));
        expect(!SurvivalPolicy.safeDrop(6,8));
        expect(!SurvivalPolicy.safeDrop(7,20));
        expect(!SurvivalPolicy.safeDrop(-1,20));
        expect(!SurvivalPolicy.safeDrop(4,Double.NaN));
        expect(!SurvivalPolicy.shouldFlee(11,false,2,true));
        expect(SurvivalPolicy.shouldSmelt("minecraft:rabbit"));
        expect(SurvivalPolicy.shouldSmelt("minecraft:raw_copper"));
        System.out.println("Survival policy: " + passed + " tests passed");
    }
}
