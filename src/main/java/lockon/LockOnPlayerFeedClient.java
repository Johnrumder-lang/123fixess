package lockon;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Receives server-PERMISSIONED and player-opted-in global positions.
 * Network messages must come from the connected server; absent a companion
 * plugin, this stays empty and only ordinary loaded players are traced.
 */
public final class LockOnPlayerFeedClient {
    private static volatile List<LockOnPlayerPositionsPayload.Position> positions = List.of();
    private static long lastReceivedNanos;
    private static ClientLevel previousLevel;

    private LockOnPlayerFeedClient() {}

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(
            LockOnPlayerPositionsPayload.TYPE, LockOnPlayerPositionsPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(
            LockOnPlayerPositionsPayload.TYPE, (payload, context) -> {
                positions = payload.players();
                lastReceivedNanos = System.nanoTime();
            });
    }

    public static void tick(Minecraft mc) {
        if (mc.level != previousLevel) {
            previousLevel = mc.level;
            clear();
        }
    }

    private static void clear() {
        positions = List.of();
        lastReceivedNanos = 0L;
    }

    public static String status() {
        if (!LockOnSettings.traceServerPlayers()) return "Server player feed OFF";
        if (lastReceivedNanos != 0L
            && System.nanoTime() - lastReceivedNanos < 4_000_000_000L) {
            return "Server player feed LIVE (" + positions.size()
                + " players sharing)";
        }
        return "NO server feed: Paper companion/permission missing";
    }

    public static List<LockOnPlayerPositionsPayload.Position> positions() {
        if (!LockOnSettings.traceServerPlayers()) return List.of();
        if (lastReceivedNanos == 0L
            || System.nanoTime() - lastReceivedNanos > 4_000_000_000L) return List.of();
        return positions;
    }
}
