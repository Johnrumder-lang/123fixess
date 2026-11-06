package lockon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Saved client-side attack-ray setting. The server still validates reach. */
public final class LockOnSettings {
    private static final int DEFAULT_REACH_TENTHS = 31;
    private static int reachTenths = DEFAULT_REACH_TENTHS;

    private LockOnSettings() {}

    public static double attackReach() {
        return reachTenths / 10.0;
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
            double value = Double.parseDouble(properties.getProperty("attackReach", "3.1"));
            if (Double.isFinite(value)) setReach(value);
        } catch (IOException | NumberFormatException ignored) {
            reachTenths = DEFAULT_REACH_TENTHS;
        }
    }

    public static void setReach(double value) {
        if (Double.isFinite(value)) {
            reachTenths = Math.max(30, Math.min(50, (int) Math.round(value * 10)));
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Properties properties = new Properties();
            properties.setProperty("attackReach", String.format(java.util.Locale.ROOT, "%.1f", attackReach()));
            try (OutputStream stream = Files.newOutputStream(file())) {
                properties.store(stream, "Client attack reach; server must permit this value");
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
        ConfigCategory combat = builder.getOrCreateCategory(Component.literal("Combat"));
        ConfigEntryBuilder entries = builder.entryBuilder();
        combat.addEntry(entries.startDoubleField(
                Component.literal("Attempted attack reach (3.0 - 5.0 blocks)"), attackReach())
            .setDefaultValue(3.1)
            .setMin(3.0)
            .setMax(5.0)
            .setSaveConsumer(LockOnSettings::setReach)
            .build());
        builder.setSavingRunnable(LockOnSettings::save);
        return builder.build();
    }
}
