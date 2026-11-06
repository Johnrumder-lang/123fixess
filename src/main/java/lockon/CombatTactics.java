package lockon;

/**
 * Stateless, deterministic tactical rules so we can test them without
 * launching Minecraft or requiring an external model at runtime.
 */
final class CombatTactics {
    record Move(boolean forward, boolean backward, boolean left,
                boolean right, boolean sprint) {}

    private CombatTactics() {}

    static double normalizeReach(double reach) {
        if (!Double.isFinite(reach)) return 3.0;
        return Math.max(2.5, Math.min(3.0, reach));
    }

    static double predictionTicks(double distance, double reach) {
        // Do not lead a target outside their actual melee hitbox.
        double spare = distance - normalizeReach(reach) - 0.4;
        return Math.max(0.0, Math.min(1.2, spare * 0.12));
    }

    static Move move(double distance, double reach, double recedingSpeed,
                     boolean combo, boolean airborne,
                     boolean safeForward, boolean leftSafe, boolean rightSafe,
                     boolean preferLeft, boolean grounded,
                     boolean sprintReset, boolean criticalWindow) {
        if (!Double.isFinite(distance) || distance < 0) {
            return new Move(false, false, false, false, false);
        }
        double validReach = normalizeReach(reach);
        boolean pressure = combo || airborne;
        double desired = Math.max(1.65, validReach - (pressure ? 1.05 : 0.60));
        if (recedingSpeed > 0.05) {
            desired -= Math.min(0.35, recedingSpeed * 1.4);
        }

        // Keep contact without rushing straight off an unsupported ledge.
        boolean forward = safeForward && distance > desired;
        boolean backward = !pressure && distance < 1.25;
        boolean orbit = !pressure && !forward && !backward
            && distance < Math.min(3.2, validReach - 0.15);
        boolean left = orbit && (preferLeft ? leftSafe : !rightSafe && leftSafe);
        boolean right = orbit && !left && rightSafe;

        boolean sprint = forward && grounded && !sprintReset && !criticalWindow;
        return new Move(forward, backward, left, right, sprint);
    }
}
