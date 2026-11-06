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
        check(CombatTactics.normalizeReach(3.1) == 3.1, "preserve configured reach");
        check(CombatTactics.normalizeReach(7.0) == 5.0, "cap at default vanilla creative reach");
        check(CombatTactics.normalizeReach(Double.NaN) == 5.0, "invalid reach fallback");
        check(CombatTactics.predictionTicks(3.0, 5.0) == 0.0, "no melee hitbox leading");
        check(CombatTactics.predictionTicks(12.0, 5.0) > 0.0, "lead approaching targets");
        check(CombatTactics.predictionTicks(100.0, 5.0) <= 1.2, "limit overleading");

        var approach = CombatTactics.move(5.4, 5.0, 0.0,
            false, false, true, true, true, true, true, false, false);
        check(approach.forward() && approach.sprint(), "normal approach should sprint");

        var combo = CombatTactics.move(2.4, 3.0, 0.12,
            true, true, true, true, true, false, true, false, false);
        check(combo.forward(), "combo pressure should close the knockback gap");
        check(!combo.backward() && !combo.left() && !combo.right(),
            "combo should not drift sideways");

        var reset = CombatTactics.move(5.4, 5.0, 0.0,
            true, false, true, true, true, true, true, true, false);
        check(reset.forward() && !reset.sprint(), "sprint reset keeps forward movement");

        var cliff = CombatTactics.move(6.0, 5.0, 0.1,
            false, false, false, true, true, true, true, false, false);
        check(!cliff.forward() && !cliff.sprint(), "do not sprint off an unsupported edge");

        var hover = CombatTactics.move(2.5, 3.0, 0,
            false, false, true, true, true, true, true, false, false);
        check(hover.left() && !hover.right(), "orbit only outside combos");

        var invalid = CombatTactics.move(Double.NaN, 5.0, 0,
            true, false, true, true, true, false, true, false, false);
        check(!invalid.forward() && !invalid.sprint(), "invalid input must fail closed");

        System.out.println("Combat tactics: " + cases + " checks passed");
    }
}
