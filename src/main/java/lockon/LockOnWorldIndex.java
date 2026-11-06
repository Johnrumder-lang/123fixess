package lockon;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Optional read-only offline world index supplied by a server administrator.
 * Does not fetch any data from a multiplayer server or request unloaded chunks.
 * Markers represent the export snapshot, and can become stale.
 */
final class LockOnWorldIndex {
    record Marker(String dimension, String type, String id, Vec3 position) {}
    private static final int MAX_RECORDS = 200_000;
    private static final int MAX_VISIBLE = 900;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
        .resolve("lockon-server-index.tsv");
    private static List<Marker> markers = List.of();
    private static List<Marker> visible = List.of();
    private static long lastModified = -1L;
    private static int nextReloadTick;
    private static int nextVisibleTick;
    private static int ticks;
    private static ClientLevel previousWorld;

    private LockOnWorldIndex() {}

    static void tick(Minecraft mc) {
        ticks++;
        if (mc.level != previousWorld) {
            previousWorld = mc.level;
            visible = List.of();
            nextVisibleTick = 0;
        }
        if (!LockOnSettings.traceServerIndex() || mc.level == null || mc.player == null) {
            visible = List.of();
            return;
        }
        if (ticks >= nextReloadTick) {
            nextReloadTick = ticks + 100;
            reloadIfChanged();
        }
        if (ticks < nextVisibleTick) return;
        nextVisibleTick = ticks + 20;
        String dimension = mc.level.dimension().identifier().toString();
        Vec3 eye = mc.player.getEyePosition();
        Comparator<Marker> farthestFirst =
            Comparator.comparingDouble((Marker m) -> m.position.distanceToSqr(eye)).reversed();
        PriorityQueue<Marker> top = new PriorityQueue<>(MAX_VISIBLE + 1, farthestFirst);
        for (Marker marker : markers) {
            if (!marker.dimension.equals(dimension) || !allowed(marker)) continue;
            if (top.size() < MAX_VISIBLE) {
                top.add(marker);
            } else if (marker.position.distanceToSqr(eye)
                < top.peek().position.distanceToSqr(eye)) {
                top.poll();
                top.add(marker);
            }
        }
        visible = List.copyOf(top);
    }

    static List<Marker> visible() { return visible; }

    static int color(Marker marker) {
        return switch (marker.type) {
            case "chest" -> 0xDFFFC24D;
            case "bed" -> 0xDF6EC7FF;
            case "block" -> 0xDFBB80FF;
            default -> 0;
        };
    }

    private static boolean allowed(Marker marker) {
        return switch (marker.type) {
            case "chest" -> LockOnSettings.traceChests();
            case "bed" -> LockOnSettings.traceBeds();
            case "block" -> LockOnSettings.traceCustomBlocks()
                && LockOnSettings.customBlockIds().contains(marker.id);
            default -> false;
        };
    }

    private static void reloadIfChanged() {
        long modified = -1L;
        try {
            if (Files.isRegularFile(FILE) && Files.size(FILE) <= MAX_FILE_SIZE) {
                modified = Files.getLastModifiedTime(FILE).toMillis();
            }
        } catch (IOException exception) {
            System.err.println("[LockOn] Could not inspect server index: " + exception.getMessage());
        }
        if (modified == lastModified) return;
        lastModified = modified;
        if (modified < 0) {
            markers = List.of();
            return;
        }

        List<Marker> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            String line;
            while (loaded.size() < MAX_RECORDS && (line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\\t", 6);
                if (parts.length < 6) continue;
                String dimension = parts[0], type = parts[1], id = parts[5];
                if (!(type.equals("chest") || type.equals("bed") || type.equals("block"))) continue;
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                double z = Double.parseDouble(parts[4]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
                if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 5000) continue;
                loaded.add(new Marker(dimension, type, id, new Vec3(x, y, z)));
            }
            markers = List.copyOf(loaded);
            nextVisibleTick = 0;
            System.out.println("[LockOn] Loaded " + markers.size() + " administrator-exported world markers");
        } catch (IOException | NumberFormatException exception) {
            markers = List.of();
            System.err.println("[LockOn] Invalid world index: " + exception.getMessage());
        }
    }
}
