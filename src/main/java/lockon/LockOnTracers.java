package lockon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Optional screen-space tracers. Never sends packets, scans unloaded worlds,
 * or modifies block/entity state. A bounded incremental block scan prevents
 * frame stalls around large groups of storage blocks or beds.
 */
public final class LockOnTracers {
    private static final int BLOCK_SCAN_BATCH = 2200;
    private static final int HEIGHT_RADIUS = 12;
    private static final int MAX_BLOCK_MARKERS = 160;
    private static final int MAX_ENTITY_MARKERS = 100;
    private static final int COLOR_PLAYERS = 0xDFFF5555;
    private static final int COLOR_CHESTS = 0xDFFFC24D;
    private static final int COLOR_BEDS = 0xDF6EC7FF;
    private static final int COLOR_CUSTOM_BLOCKS = 0xDFBB80FF;
    private static final int COLOR_CUSTOM_ENTITIES = 0xDF6CFF96;

    private record Marker(Vec3 pos, int color) {}

    private static ClientLevel scanWorld;
    private static BlockPos scanOrigin;
    private static int scanRadius;
    private static int scanCursor = -1;
    private static int tickCount;
    private static int nextScanTick;
    private static String lastScanSignature = "";
    private static final List<Marker> scanResults = new ArrayList<>();
    private static List<Marker> visibleBlocks = List.of();

    private LockOnTracers() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(LockOnTracers::scanTick);
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("lockon", "tracers"),
            (graphics, delta) -> render(graphics));
    }

    private static void resetScan() {
        scanCursor = -1;
        scanResults.clear();
        visibleBlocks = List.of();
        nextScanTick = 0;
    }

    private static void scanTick(Minecraft mc) {
        tickCount++;
        if (mc.level != scanWorld) {
            scanWorld = mc.level;
            resetScan();
        }
        if (mc.player == null || mc.level == null) return;

        boolean scanBlocks = LockOnSettings.traceChests()
            || LockOnSettings.traceBeds() || LockOnSettings.traceCustomBlocks();
        String signature = LockOnSettings.scanSignature();
        if (!signature.equals(lastScanSignature)) {
            lastScanSignature = signature;
            resetScan();
        }
        if (!scanBlocks) {
            visibleBlocks = List.of();
            scanCursor = -1;
            return;
        }
        if (scanCursor < 0) {
            if (tickCount < nextScanTick) return;
            scanRadius = LockOnSettings.traceDistance();
            scanOrigin = BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            scanResults.clear();
            scanCursor = 0;
        }

        int side = 2 * scanRadius + 1;
        int height = 2 * HEIGHT_RADIUS + 1;
        int count = side * side * height;
        Set<String> customBlocks = LockOnSettings.customBlockIds();
        int budget = BLOCK_SCAN_BATCH;

        while (budget-- > 0 && scanCursor < count) {
            int index = scanCursor++;
            int dx = index % side - scanRadius;
            int dz = (index / side) % side - scanRadius;
            int dy = index / (side * side) - HEIGHT_RADIUS;
            int bx = scanOrigin.getX() + dx;
            int by = scanOrigin.getY() + dy;
            int bz = scanOrigin.getZ() + dz;
            if (!mc.level.hasChunk(bx >> 4, bz >> 4)) continue;

            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) continue;

            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String path = id.substring(id.indexOf(':') + 1);
            int color = 0;
            if (LockOnSettings.traceChests() && path.contains("chest")) {
                color = COLOR_CHESTS;
            } else if (LockOnSettings.traceBeds() && path.endsWith("_bed")) {
                color = COLOR_BEDS;
            } else if (LockOnSettings.traceCustomBlocks() && customBlocks.contains(id)) {
                color = COLOR_CUSTOM_BLOCKS;
            }
            if (color != 0 && scanResults.size() < MAX_BLOCK_MARKERS) {
                scanResults.add(new Marker(Vec3.atCenterOf(pos), color));
            }
        }
        if (scanCursor >= count) {
            visibleBlocks = List.copyOf(scanResults);
            scanResults.clear();
            scanCursor = -1;
            nextScanTick = tickCount + 12;
        }
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gui.screen() != null) return;

        boolean players = LockOnSettings.tracePlayers();
        boolean customEntities = LockOnSettings.traceCustomEntities();
        boolean blocks = LockOnSettings.traceChests()
            || LockOnSettings.traceBeds() || LockOnSettings.traceCustomBlocks();
        if (!players && !customEntities && !blocks) return;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (width < 8 || height < 8) return;
        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getViewVector(1.0f).normalize();
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        Vec3 up = right.cross(forward).normalize();
        double fov = Math.max(35.0, Math.min(110.0, mc.options.fov().get()));
        double focal = height / (2.0 * Math.tan(Math.toRadians(fov / 2.0)));
        double maxDistanceSq = (double) LockOnSettings.traceDistance() * LockOnSettings.traceDistance();

        if (players) {
            for (Player other : mc.level.players()) {
                if (other == player || !other.isAlive() || other.isSpectator()) continue;
                if (other.distanceToSqr(player) > maxDistanceSq) continue;
                Vec3 target = other.position().add(0.0, other.getBbHeight() * 0.65, 0.0);
                drawTracer(graphics, eye, forward, right, up, focal, target, COLOR_PLAYERS);
            }
        }

        if (customEntities) {
            Set<String> entities = LockOnSettings.customEntityIds();
            int shown = 0;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (shown >= MAX_ENTITY_MARKERS) break;
                if (entity == player || entity instanceof Player || !entity.isAlive()) continue;
                if (entity.distanceToSqr(player) > maxDistanceSq) continue;
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if (!entities.contains(id)) continue;
                Vec3 target = entity.position().add(0.0, entity.getBbHeight() * 0.6, 0.0);
                drawTracer(graphics, eye, forward, right, up, focal, target, COLOR_CUSTOM_ENTITIES);
                shown++;
            }
        }

        if (blocks) {
            for (Marker marker : visibleBlocks) {
                if (marker.pos.distanceToSqr(eye) > maxDistanceSq) continue;
                // The cached markers are categorized during scanning;
                // changing a toggle immediately clears the scan cache.
                drawTracer(graphics, eye, forward, right, up, focal, marker.pos, marker.color);
            }
        }
    }

    private static void drawTracer(GuiGraphicsExtractor graphics, Vec3 eye,
                                   Vec3 forward, Vec3 right, Vec3 up,
                                   double focal, Vec3 target, int color) {
        Vec3 relative = target.subtract(eye);
        double depth = relative.dot(forward);
        // Only draw markers in front of the camera. Targets outside the
        // field of view are represented by a line toward the screen edge.
        if (depth <= 0.05) return;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double ox = relative.dot(right) * focal / depth;
        double oy = -relative.dot(up) * focal / depth;
        if (!Double.isFinite(ox) || !Double.isFinite(oy)) return;

        double boundX = width * 0.47;
        double boundY = height * 0.47;
        double scale = Math.min(1.0, Math.min(
            boundX / Math.max(Math.abs(ox), 0.001),
            boundY / Math.max(Math.abs(oy), 0.001)));
        double dx = ox * scale;
        double dy = oy * scale;
        double length = Math.hypot(dx, dy);
        if (length < 2.5) return;

        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width * 0.5f, height * 0.5f);
        pose.rotate((float) Math.atan2(dy, dx));
        graphics.fill(0, -1, Math.max(1, (int) Math.round(length)), 1, color);
        pose.popMatrix();
    }
}
