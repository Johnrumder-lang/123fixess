package lockon;

/**
 * Pure bootstrap decisions, deliberately independent of Minecraft so that
 * a fresh empty-inventory start cannot be marked completed.
 */
final class AutoplayPlan {
    enum Action {
        GATHER_WOOD, MAKE_PLANKS, MAKE_TABLE, PLACE_TABLE,
        MAKE_STICKS, MAKE_PICKAXE, READY
    }

    private AutoplayPlan() {}

    /**
     * Never hoard an arbitrary number of planks before building the table.
     * Craft only what the next recipe requires, gather more wood if short,
     * and resume the appropriate recipe as soon as supplies arrive.
     */
    static Action next(int logs, int planks, int sticks, boolean pickaxe,
                       boolean tablePlaced, boolean tableItem) {
        if (pickaxe && tablePlaced) return Action.READY;
        if (tableItem && !tablePlaced) return Action.PLACE_TABLE;
        if (!tablePlaced) {
            if (planks >= 4) return Action.MAKE_TABLE;
            return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
        }
        if (sticks < 2) {
            if (planks >= 2) return Action.MAKE_STICKS;
            return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
        }
        if (pickaxe) return Action.READY;
        if (planks >= 3) return Action.MAKE_PICKAXE;
        return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
    }

}
