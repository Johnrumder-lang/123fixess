package lockon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

/**
 * Screen-space tracer overlay. "inf" means no custom distance filter for
 * client-loaded entities and scanning loaded chunks only for blocks.
 * Never requests unseen world data from a server.
 */
public final class LockOnTracers {
    // Bound the amount of work each client tick and the number of lines
    // each frame, especially when "inf" is used in a dense world.
    private static final int BLOCK_SCAN_BATCH = 8000;
    private static final int MAX_BLOCK_MARKERS = 1800;
    private static final int MAX_ENTITY_MARKERS = 700;
    private static final int HEIGHT_RADIUS_FINITE = 12;

    private static final int COLOR_PLAYERS = 0xDFFF5555;
    private static final int COLOR_CHESTS = 0xDFFFC24D;
    private static final int COLOR_BEDS = 0xDF6EC7FF;
    private static final int COLOR_CUSTOM_BLOCKS = 0xDFBB80FF;
    private static final int COLOR_CUSTOM_ENTITIES = 0xDF6CFF96;

    private record Marker(Vec3 pos, int color) {}

    private static ClientLevel scanWorld;
    private static BlockPos scanOrigin;
    private static int scanOriginChunkX;
    private static int scanOriginChunkZ;
    private static int scanRadius;
    private static int chunkCursor = -1;
    private static int sectionCursor;
    private static int cellCursor;
    private static int tickCount;
    private static int nextScanTick;
    private static boolean scanningUnlimited;
    private static String lastScanSignature = "";
    private static List<ChunkPos> chunks = List.of();
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
        chunkCursor = -1;
        sectionCursor = 0;
        cellCursor = 0;
        chunks = List.of();
        scanResults.clear();
        visibleBlocks = List.of();
        nextScanTick = 0;
    }

    private static void beginScan(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        scanOrigin = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        scanOriginChunkX = scanOrigin.getX() >> 4;
        scanOriginChunkZ = scanOrigin.getZ() >> 4;
        scanningUnlimited = LockOnSettings.unlimitedTraceDistance();
        scanRadius = LockOnSettings.traceDistance();

        // "inf" means ALL nearby chunks already available to the client,
        // not just the graphics render-distance setting. Server view and
        // chunk streaming still determine what exists on the client.
        // Querying hasChunk never forces missing chunks to download.
        int radiusChunks = scanningUnlimited
            ? 48
            : (scanRadius + 15) / 16 + 1;

        List<ChunkPos> found = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = scanOriginChunkX + dx;
                int cz = scanOriginChunkZ + dz;
                if (mc.level.hasChunk(cx, cz)) found.add(new ChunkPos(cx, cz));
            }
        }
        // Nearest chunks first: even a huge scan shows nearby items quickly.
        found.sort(Comparator.comparingLong(p -> {
            long dx = p.x() - (long) scanOriginChunkX;
            long dz = p.z() - (long) scanOriginChunkZ;
            return dx * dx + dz * dz;
        }));
        chunks = found;
        scanResults.clear();
        chunkCursor = 0;
        sectionCursor = 0;
        cellCursor = 0;
    }

    private static int blockColor(BlockState state, Set<String> customBlocks) {
        if (state.isAir()) return 0;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String name = id.substring(id.indexOf(':') + 1);
        if (LockOnSettings.traceChests() && name.contains("chest")) return COLOR_CHESTS;
        if (LockOnSettings.traceBeds() && name.endsWith("_bed")) return COLOR_BEDS;
        if (LockOnSettings.traceCustomBlocks() && customBlocks.contains(id)) return COLOR_CUSTOM_BLOCKS;
        return 0;
    }

    private static void scanTick(Minecraft mc) {
        LockOnWorldIndex.tick(mc);
        tickCount++;
        if (mc.level != scanWorld) {
            scanWorld = mc.level;
            resetScan();
        }
        if (mc.player == null || mc.level == null) return;

        boolean wantsBlocks = LockOnSettings.traceChests()
            || LockOnSettings.traceBeds() || LockOnSettings.traceCustomBlocks();
        String signature = LockOnSettings.scanSignature();
        if (!signature.equals(lastScanSignature)) {
            lastScanSignature = signature;
            resetScan();
        }
        if (!wantsBlocks) {
            resetScan();
            return;
        }

        // An in-progress scan should not keep following an old player position
        // after they travel into a new part of the world.
        if (scanOrigin != null && chunkCursor >= 0) {
            int chunkX = ((int) Math.floor(mc.player.getX())) >> 4;
            int chunkZ = ((int) Math.floor(mc.player.getZ())) >> 4;
            if (Math.abs(chunkX - scanOriginChunkX) > 1
                || Math.abs(chunkZ - scanOriginChunkZ) > 1) resetScan();
        }

        if (chunkCursor < 0) {
            if (tickCount < nextScanTick) return;
            beginScan(mc);
        }

        Set<String> customBlocks = LockOnSettings.customBlockIds();
        int budget = BLOCK_SCAN_BATCH;
        boolean changed = false;

        while (budget > 0 && chunkCursor < chunks.size()
               && scanResults.size() < MAX_BLOCK_MARKERS) {
            ChunkPos pos = chunks.get(chunkCursor);
            if (!mc.level.hasChunk(pos.x(), pos.z())) {
                chunkCursor++;
                sectionCursor = cellCursor = 0;
                continue;
            }
            var chunk = mc.level.getChunk(pos.x(), pos.z());
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionCursor >= sections.length) {
                chunkCursor++;
                sectionCursor = cellCursor = 0;
                continue;
            }
            LevelChunkSection section = sections[sectionCursor];
            int baseY = mc.level.getSectionYFromSectionIndex(sectionCursor) << 4;
            if (!scanningUnlimited && (baseY > scanOrigin.getY() + HEIGHT_RADIUS_FINITE
                || baseY + 15 < scanOrigin.getY() - HEIGHT_RADIUS_FINITE)) {
                sectionCursor++;
                cellCursor = 0;
                continue;
            }

            // The palette test is very cheap: skip entire 16x16x16 sections
            // that do not contain a tracked block, including most stone/air.
            if (cellCursor == 0 && !section.maybeHas(
                state -> blockColor(state, customBlocks) != 0)) {
                sectionCursor++;
                continue;
            }

            while (budget > 0 && cellCursor < 4096
                && scanResults.size() < MAX_BLOCK_MARKERS) {
                int i = cellCursor++;
                budget--;
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int localY = (i >> 8) & 15;
                int by = baseY + localY;
                if (!scanningUnlimited
                    && Math.abs(by - scanOrigin.getY()) > HEIGHT_RADIUS_FINITE) continue;

                int bx = (pos.x() << 4) + localX;
                int bz = (pos.z() << 4) + localZ;
                if (!scanningUnlimited) {
                    int dx = bx - scanOrigin.getX();
                    int dz = bz - scanOrigin.getZ();
                    if ((long) dx * dx + (long) dz * dz
                        > (long) scanRadius * scanRadius) continue;
                }

                BlockState state = section.getBlockState(localX, localY, localZ);
                int color = blockColor(state, customBlocks);
                if (color != 0) {
                    scanResults.add(new Marker(new Vec3(
                        bx + 0.5, by + 0.5, bz + 0.5), color));
                    changed = true;
                }
            }
            if (cellCursor >= 4096) {
                sectionCursor++;
                cellCursor = 0;
            }
        }

        // Publish partial discoveries, instead of making infinity mode wait
        // for every section to be visited before any tracer can appear.
        if (!scanResults.isEmpty() && tickCount % 4 == 0) {
            visibleBlocks = List.copyOf(scanResults);
        }
        if (chunkCursor >= chunks.size() || scanResults.size() >= MAX_BLOCK_MARKERS) {
            visibleBlocks = List.copyOf(scanResults);
            scanResults.clear();
            chunks = List.of();
            chunkCursor = -1;
            nextScanTick = tickCount + (scanningUnlimited ? 80 : 20);
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
        double maxDistanceSq = LockOnSettings.unlimitedTraceDistance()
            ? Double.POSITIVE_INFINITY
            : (double) LockOnSettings.traceDistance() * LockOnSettings.traceDistance();

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
                drawTracer(graphics, eye, forward, right, up, focal, marker.pos, marker.color);
            }
            // Only user-supplied, server-administrator-exported records can
            // cover saved world areas beyond the client streaming boundary.
            // Every category remains independently controllable.
            if (LockOnSettings.traceServerIndex()) {
                for (LockOnWorldIndex.Marker marker : LockOnWorldIndex.visible()) {
                    if (marker.position().distanceToSqr(eye) > maxDistanceSq) continue;
                    drawTracer(graphics, eye, forward, right, up, focal,
                        marker.position(), LockOnWorldIndex.color(marker));
                }
            }
        }
    }

    private static void drawTracer(GuiGraphicsExtractor graphics, Vec3 eye,
                                   Vec3 forward, Vec3 right, Vec3 up,
                                   double focal, Vec3 target, int color) {
        Vec3 relative = target.subtract(eye);
        double depth = relative.dot(forward);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double boundX = width * 0.47;
        double boundY = height * 0.47;
        double ox, oy;
        if (depth <= 0.05) {
            // The old mod silently skipped every player/block behind you.
            // Represent those targets by a screen-edge directional line.
            // A target directly behind the camera points toward the bottom
            // center; lateral targets point left/right across the bottom.
            double side = relative.dot(right);
            double elevation = relative.dot(up);
            ox = Math.abs(side) < 0.01 ? 0.0
                : Math.copySign(boundX, side);
            oy = elevation > relative.length() * 0.5
                ? -boundY : boundY;
        } else {
            ox = relative.dot(right) * focal / depth;
            oy = -relative.dot(up) * focal / depth;
        }
        if (!Double.isFinite(ox) || !Double.isFinite(oy)) return;
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
