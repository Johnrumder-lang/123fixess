package lockon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client-only configuration. Tracers and the combat ray do not change server permissions. */
public final class LockOnSettings {
    private static final int DEFAULT_REACH_TENTHS = 30;
    private static int reachTenths = DEFAULT_REACH_TENTHS;
    private static String teleportPlayerName = "";
    private static boolean tracePlayers;
    private static boolean traceChests;
    private static boolean traceBeds;
    private static boolean traceCustomBlocks;
    private static boolean traceCustomEntities;
    private static int traceDistance = 24;
    private static boolean unlimitedTraceDistance;
    private static boolean traceServerIndex;
    private static String blockIds = "minecraft:diamond_ore";
    private static String entityIds = "minecraft:zombie";
    private static Set<String> customBlockIds = Set.of("minecraft:diamond_ore");
    private static Set<String> customEntityIds = Set.of("minecraft:zombie");

    private LockOnSettings() {}

    public static double attackReach() { return reachTenths / 10.0; }
    public static String teleportPlayerName() { return teleportPlayerName; }
    public static boolean tracePlayers() { return tracePlayers; }
    public static boolean traceChests() { return traceChests; }
    public static boolean traceBeds() { return traceBeds; }
    public static boolean traceCustomBlocks() { return traceCustomBlocks; }
    public static boolean traceCustomEntities() { return traceCustomEntities; }
    public static int traceDistance() { return traceDistance; }
    public static boolean unlimitedTraceDistance() { return unlimitedTraceDistance; }
    public static boolean traceServerIndex() { return traceServerIndex; }
    public static String traceDistanceText() {
        return unlimitedTraceDistance ? "inf" : Integer.toString(traceDistance);
    }

    // This is the text-field consumer. A numeric finite radius must NOT
    // clear the separate "Infinite" toggle when both entries are saved.
    public static void setTracerField(String text) {
        if (text == null) return;
        String value = text.trim();
        if (value.equalsIgnoreCase("inf") || value.equalsIgnoreCase("infinity") || value.equals("∞")) {
            unlimitedTraceDistance = true;
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            traceDistance = Math.max(8, Math.min(32, parsed));
        } catch (NumberFormatException ignored) {
            // Keep the last valid value.
        }
    }

    public static void setTraceDistanceText(String text) {
        if (text == null) return;
        String value = text.trim();
        if (value.equalsIgnoreCase("inf") || value.equalsIgnoreCase("infinity") || value.equals("∞")) {
            unlimitedTraceDistance = true;
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            traceDistance = Math.max(8, Math.min(32, parsed));
            unlimitedTraceDistance = false;
        } catch (NumberFormatException ignored) {
            // Retain the previous valid value if the field is invalid.
        }
    }
    public static Set<String> customBlockIds() { return customBlockIds; }
    public static Set<String> customEntityIds() { return customEntityIds; }

    /** Ensures any block-filter change restarts the scan immediately. */
    public static String scanSignature() {
        return traceChests + ":" + traceBeds + ":" + traceCustomBlocks
            + ":" + traceDistanceText() + ":" + blockIds;
    }

    private static Set<String> parseIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[,;\\s]+"))
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .map(v -> v.contains(":") ? v : "minecraft:" + v)
            .limit(64)
            .collect(Collectors.toUnmodifiableSet());
    }

    public static void setReach(double value) {
        if (Double.isFinite(value)) {
            reachTenths = Math.max(25, Math.min(30, (int) Math.round(value * 10)));
        }
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        Properties p = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            p.load(stream);
            // Old versions defaulted to 3.1, then 5.0. Both are migrated
            // to the vanilla survival sword reach (3.0), without ever
            // attempting to extend the server's permitted hit distance.
            setReach(Double.parseDouble(p.getProperty("attackReach", "3.0")));
            teleportPlayerName = p.getProperty("teleportPlayerName", "").trim();
            tracePlayers = Boolean.parseBoolean(p.getProperty("tracePlayers", "false"));
            traceChests = Boolean.parseBoolean(p.getProperty("traceChests", "false"));
            traceBeds = Boolean.parseBoolean(p.getProperty("traceBeds", "false"));
            traceCustomBlocks = Boolean.parseBoolean(p.getProperty("traceCustomBlocks", "false"));
            traceCustomEntities = Boolean.parseBoolean(p.getProperty("traceCustomEntities", "false"));
            traceServerIndex = Boolean.parseBoolean(p.getProperty("traceServerIndex", "false"));
            setTraceDistanceText(p.getProperty("traceDistance", "24"));
            blockIds = p.getProperty("tracerBlockIds", "minecraft:diamond_ore");
            entityIds = p.getProperty("tracerEntityIds", "minecraft:zombie");
            customBlockIds = parseIds(blockIds);
            customEntityIds = parseIds(entityIds);
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[LockOn] Invalid config; using safe defaults: " + exception.getMessage());
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("settingsVersion", "3");
        p.setProperty("attackReach", String.format(Locale.ROOT, "%.1f", attackReach()));
        p.setProperty("teleportPlayerName", teleportPlayerName);
        p.setProperty("tracePlayers", Boolean.toString(tracePlayers));
        p.setProperty("traceChests", Boolean.toString(traceChests));
        p.setProperty("traceBeds", Boolean.toString(traceBeds));
        p.setProperty("traceCustomBlocks", Boolean.toString(traceCustomBlocks));
        p.setProperty("traceCustomEntities", Boolean.toString(traceCustomEntities));
        p.setProperty("traceServerIndex", Boolean.toString(traceServerIndex));
        p.setProperty("traceDistance", traceDistanceText());
        p.setProperty("tracerBlockIds", blockIds);
        p.setProperty("tracerEntityIds", entityIds);
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream output = Files.newOutputStream(file())) {
                p.store(output, "LockOn client-side gameplay and tracer settings");
            }
        } catch (IOException exception) {
            System.err.println("[LockOn] Could not save settings: " + exception.getMessage());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("lockon.properties");
    }

    public static Screen createScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("LockOn Settings"));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory combat = builder.getOrCreateCategory(Component.literal("Combat"));
        combat.addEntry(entries.startDoubleField(
                Component.literal("Sword reach (vanilla Survival: 3.0 blocks)"), attackReach())
            .setDefaultValue(3.0)
            .setMin(2.5)
            .setMax(3.0)
            .setSaveConsumer(LockOnSettings::setReach)
            .build());
        combat.addEntry(entries.startStrField(
                Component.literal("Teleport username (blank = G target)"), teleportPlayerName)
            .setDefaultValue("")
            .setSaveConsumer(v -> teleportPlayerName = v == null ? "" : v.trim())
            .build());

        ConfigCategory tracers = builder.getOrCreateCategory(Component.literal("Tracers"));
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Lines to other players (red)"), tracePlayers)
            .setDefaultValue(false)
            .setSaveConsumer(v -> tracePlayers = v)
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Lines to chests (gold)"), traceChests)
            .setDefaultValue(false)
            .setSaveConsumer(v -> traceChests = v)
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Lines to beds (blue)"), traceBeds)
            .setDefaultValue(false)
            .setSaveConsumer(v -> traceBeds = v)
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Lines to custom blocks (purple)"), traceCustomBlocks)
            .setDefaultValue(false)
            .setSaveConsumer(v -> traceCustomBlocks = v)
            .build());
        tracers.addEntry(entries.startStrField(
                Component.literal("Custom blocks (comma-separated IDs)"), blockIds)
            .setDefaultValue("minecraft:diamond_ore")
            .setSaveConsumer(v -> {
                blockIds = v;
                customBlockIds = parseIds(v);
            })
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Lines to custom entities (green)"), traceCustomEntities)
            .setDefaultValue(false)
            .setSaveConsumer(v -> traceCustomEntities = v)
            .build());
        tracers.addEntry(entries.startStrField(
                Component.literal("Custom entities (comma-separated IDs)"), entityIds)
            .setDefaultValue("minecraft:zombie")
            .setSaveConsumer(v -> {
                entityIds = v;
                customEntityIds = parseIds(v);
            })
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Administrator world index (saved blocks, requires TSV export)"),
                traceServerIndex)
            .setDefaultValue(false)
            .setSaveConsumer(v -> traceServerIndex = v)
            .build());
        tracers.addEntry(entries.startBooleanToggle(
                Component.literal("Unlimited tracer distance (LOADED chunks/entities only)"),
                unlimitedTraceDistance)
            .setDefaultValue(false)
            .setSaveConsumer(v -> unlimitedTraceDistance = v)
            .build());
        tracers.addEntry(entries.startStrField(
                Component.literal("Finite radius: 8-32 (or enter inf)"),
                Integer.toString(traceDistance))
            .setDefaultValue("24")
            .setSaveConsumer(LockOnSettings::setTracerField)
            .build());

        builder.setSavingRunnable(LockOnSettings::save);
        return builder.build();
    }
}
