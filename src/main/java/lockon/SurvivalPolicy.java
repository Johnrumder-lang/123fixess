package lockon;

/** Testable survival policies; no Minecraft dependency or world mutations. */
final class SurvivalPolicy {
    enum Decision { STOP, EAT, RETREAT, DEFEND, RETURN_BASE, MINE }

    private SurvivalPolicy() {}

    static Decision decide(double health, int food, boolean burning, boolean inLava,
                           boolean suffocating, boolean hostileClose,
                           int freeSlots, int distance, int maxDistance) {
        if (!Double.isFinite(health) || health <= 0.0 || inLava || suffocating) return Decision.STOP;
        if (burning || health <= 9.0) return Decision.RETREAT;
        // Immediate hostile threats outrank routine hunger. A slightly
        // hungry beginner must be able to collect wood before eating.
        if (hostileClose) return Decision.DEFEND;
        if (food <= 8) return Decision.EAT;
        if (freeSlots <= 3 || distance >= maxDistance) return Decision.RETURN_BASE;
        return Decision.MINE;
    }

    static boolean mineCorridorBlock(boolean hasSupport, boolean fluidNearby,
                                     boolean allowedBlock, boolean currentlyLoaded) {
        return hasSupport && !fluidNearby && allowedBlock && currentlyLoaded;
    }

    static boolean shouldSmelt(String itemId) {
        return switch (itemId) {
            case "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper",
                 "minecraft:iron_ore", "minecraft:gold_ore",
                 "minecraft:copper_ore", "minecraft:deepslate_iron_ore",
                 "minecraft:deepslate_gold_ore",
                 "minecraft:deepslate_copper_ore" -> true;
            default -> false;
        };
    }

    static boolean canCompact(String itemId, int count) {
        return count >= 9 && (itemId.equals("minecraft:iron_ingot")
            || itemId.equals("minecraft:gold_ingot")
            || itemId.equals("minecraft:copper_ingot")
            || itemId.equals("minecraft:diamond")
            || itemId.equals("minecraft:emerald")
            || itemId.equals("minecraft:coal")
            || itemId.equals("minecraft:redstone")
            || itemId.equals("minecraft:lapis_lazuli"));
    }
}
