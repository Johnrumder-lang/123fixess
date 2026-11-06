package lockon;

/**
 * Self-contained assertions run by GitHub Actions with Java 25.
 * These validate the decisions; actual PvP performance needs live testing.
 */
public final class CombatTacticsTest {
    private static int cases;

    private static void check(boolean condition, String message) {
        cases++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(CombatTactics.minimumAttackTicks(false, true) == 11,
            "sword combo waits out victim immunity");
        check(CombatTactics.minimumAttackTicks(true, true) == 11,
            "fists also wait out victim immunity");
        check(CombatTactics.minimumAttackTicks(true, false) == 11,
            "fist openers do not bypass victim immunity between hits");
        check(CombatTactics.minimumAttackTicks(false, false) == 11,
            "precision mode cannot waste packets inside immunity");
        check(!CombatTactics.readyForAttack(true, 10, 0.0f, 0.999f),
            "fists cannot attack before target immunity expires");
        check(CombatTactics.readyForAttack(true, 11, 0.0f, 0.999f),
            "fist attack must recover from stale client cooldown after immunity");
        check(!CombatTactics.readyForAttack(false, 11, 0.5f, 0.999f),
            "sword must still wait for weapon charge");
        check(CombatTactics.readyForAttack(false, 13, 1.0f, 0.999f),
            "charged swords can follow up normally");
        check(CombatTactics.normalizeReach(2.7) == 2.7, "preserve configured sub-3 reach");
        check(CombatTactics.normalizeReach(7.0) == 3.0, "cap at vanilla survival sword reach");
        check(CombatTactics.normalizeReach(Double.NaN) == 3.0, "invalid reach fallback");
        check(CombatTactics.predictionTicks(3.0, 3.0) == 0.0, "no melee hitbox leading");
        check(CombatTactics.predictionTicks(12.0, 3.0) > 0.0, "lead approaching targets");
        check(CombatTactics.predictionTicks(100.0, 3.0) <= 1.2, "limit overleading");

        var approach = CombatTactics.move(5.4, 3.0, 0.0,
            false, false, true, true, true, true, true, false, false);
        check(approach.forward() && approach.sprint(), "normal approach should sprint");

        var combo = CombatTactics.move(2.4, 3.0, 0.12,
            true, true, true, true, true, false, true, false, false);
        check(combo.forward(), "combo pressure should close the knockback gap");
        check(!combo.backward() && !combo.left() && !combo.right(),
            "combo should not drift sideways");

        var reset = CombatTactics.move(5.4, 3.0, 0.0,
            true, false, true, true, true, true, true, true, false);
        check(reset.forward() && !reset.sprint(), "sprint reset keeps forward movement");

        var cliff = CombatTactics.move(6.0, 3.0, 0.1,
            false, false, false, true, true, true, true, false, false);
        check(!cliff.forward() && !cliff.sprint(), "do not sprint off an unsupported edge");

        var hover = CombatTactics.move(2.35, 3.0, 0,
            false, false, true, true, true, true, true, false, false);
        check(hover.left() && !hover.right(), "orbit only outside combos");

        var invalid = CombatTactics.move(Double.NaN, 3.0, 0,
            true, false, true, true, true, false, true, false, false);
        check(!invalid.forward() && !invalid.sprint(), "invalid input must fail closed");

        System.out.println("Combat tactics: " + cases + " checks passed");
    }
}
