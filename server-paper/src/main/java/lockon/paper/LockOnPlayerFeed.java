package lockon.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A server-owner-installed, permission-controlled source of LIVE player
 * positions. Bukkit plugin messaging transports positions to a connected
 * Fabric client; this does not grant unprivileged clients server access.
 *
 * Privacy: target players must opt in with /lockonshare on. The viewing
 * player must have the lockon.view permission (OP by default). A target
 * hidden with Bukkit's canSee() remains hidden.
 */
public final class LockOnPlayerFeed extends JavaPlugin {
    private static final String CHANNEL = "lockon:players";
    private static final int MAX_SHARED_PLAYERS = 120;
    private static final int PROTOCOL = 1;
    private NamespacedKey sharingKey;

    @Override
    public void onEnable() {
        sharingKey = new NamespacedKey(this, "share_location");
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getScheduler().runTaskTimer(this, this::sendSnapshots, 20L, 10L);
        getLogger().info("LockOn player feed enabled. Only /lockonshare on players "
            + "are visible to viewers with lockon.view permission.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lockonshare")) return false;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run /lockonshare on|off as a player.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            player.sendMessage("LockOn sharing: " + (shares(player) ? "ON" : "OFF")
                + ". Only permitted viewers can see your position.");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                player.getPersistentDataContainer().set(
                    sharingKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage("LockOn: your position is shared with authorized viewers.");
            }
            case "off" -> {
                player.getPersistentDataContainer().remove(sharingKey);
                player.sendMessage("LockOn: your position is no longer shared.");
            }
            default -> player.sendMessage("Usage: /lockonshare on|off|status");
        }
        return true;
    }

    private boolean shares(Player player) {
        return Objects.equals(
            player.getPersistentDataContainer().get(sharingKey, PersistentDataType.BYTE),
            (byte) 1);
    }

    private void sendSnapshots() {
        for (Player viewer : getServer().getOnlinePlayers()) {
            if (!viewer.hasPermission("lockon.view")) continue;
            // Only permitted viewers receive coordinates. Send on the
            // registered channel even if the client's subscription list is
            // empty; some compatible Fabric clients don't advertise it there.
            List<Player> targets = new ArrayList<>();
            for (Player target : getServer().getOnlinePlayers()) {
                if (target == viewer || !target.isOnline() || !shares(target)
                    || !viewer.canSee(target)) continue;
                targets.add(target);
                if (targets.size() >= MAX_SHARED_PLAYERS) break;
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(PROTOCOL);
                out.writeInt(targets.size());
                for (Player target : targets) {
                    writeSmallUtf8(out, target.getName(), 16);
                    writeSmallUtf8(out, target.getWorld().getKey().toString(), 96);
                    out.writeDouble(target.getLocation().getX());
                    out.writeDouble(target.getLocation().getY());
                    out.writeDouble(target.getLocation().getZ());
                }
                out.flush();
                viewer.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
            } catch (IOException | IllegalArgumentException exception) {
                getLogger().warning("Player position packet failed: " + exception.getMessage());
            }
        }
    }

    private static void writeSmallUtf8(DataOutputStream out, String value,
                                       int limit) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > limit) throw new IOException("Player/dimension name too long");
        out.writeByte(utf8.length);
        out.write(utf8);
    }
}
