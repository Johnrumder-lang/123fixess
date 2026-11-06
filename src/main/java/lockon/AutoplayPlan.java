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

    static Action next(int logs, int planks, int sticks, boolean pickaxe,
                       boolean tablePlaced, boolean tableItem) {
        if(pickaxe && tablePlaced) return Action.READY;
        if(tableItem && !tablePlaced) return Action.PLACE_TABLE;
        if(!tablePlaced) {
            int needed = pickaxe ? 4 : 20;
            if(planks >= needed) return Action.MAKE_TABLE;
            return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
        }
        if(sticks < 2) {
            if(planks >= 2) return Action.MAKE_STICKS;
            return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
        }
        if(pickaxe) return Action.READY;
        if(planks >= 3) return Action.MAKE_PICKAXE;
        return logs > 0 ? Action.MAKE_PLANKS : Action.GATHER_WOOD;
    }

}
