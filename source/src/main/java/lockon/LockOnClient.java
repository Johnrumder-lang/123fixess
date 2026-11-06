package lockon;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class LockOnClient implements ClientModInitializer {
    private static final double LOCK_RANGE = 8.0;
    private static final double AIM_SPEED = 20.0;
    private static final double AIM_HEIGHT = 0.75;
    private static final double TICK_SECONDS = 0.05;
    private static boolean enabled = false;
    private static UUID targetId;
    private static ClientLevel previousLevel;
    private static KeyMapping toggleKey;
    private static KeyMapping targetKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_R, KeyMapping.Category.MISC));
        targetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.target", InputConstants.Type.KEYSYM, InputConstants.KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != previousLevel) {
                targetId = null;
                enabled = false;
                previousLevel = mc.level;
            }
            if (mc.screen == null && mc.player != null) {
                while (toggleKey.consumeClick()) {
                    enabled = !enabled;
                    hint(mc, "LockOn " + (enabled ? "ON" : "OFF") + " (weapon cooldown)");
                }
                while (targetKey.consumeClick()) {
                    if (targetId != null) {
                        targetId = null;
                        hint(mc, "Target cleared");
                    } else pickTargetNearestToCrosshair(mc);
                }
            } else {
                while (toggleKey.consumeClick()) {}
                while (targetKey.consumeClick()) {}
            }
            onTick(mc);
        });
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (!enabled || targetId == null || me == null || mc.level == null) return;
        if (mc.screen != null || !me.isAlive() || me.isSpectator()) return;
        Player target = mc.level.getPlayerByUUID(targetId);
        if (target == null || target == me || !target.isAlive() || target.isSpectator()) return;
        if (me.distanceTo(target) > LOCK_RANGE) return;
        aimAt(me, target);
        attackWhenCharged(mc, me, target);
    }

    private static void aimAt(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * AIM_HEIGHT, target.getZ());
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float yawDelta = Mth.wrapDegrees(desiredYaw - me.getYRot());
        float pitchDelta = desiredPitch - me.getXRot();
        double blend = 1.0 - Math.exp(-AIM_SPEED * TICK_SECONDS);
        me.setYRot(me.getYRot() + (float) (yawDelta * blend));
        me.setXRot(Mth.clamp(me.getXRot() + (float) (pitchDelta * blend), -90.0f, 90.0f));
    }

    private static void attackWhenCharged(Minecraft mc, LocalPlayer me, Player target) {
        if (mc.gameMode == null || !me.getMainHandItem().is(ItemTags.SWORDS)) return;
        if (me.isUsingItem()) return;
        if (me.getAttackStrengthScale(0.0f) < 1.0f) return;
        HitResult result = me.raycastHitResult(1.0f, me);
        if (!(result instanceof EntityHitResult hit) || hit.getEntity() != target) return;
        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
    }

    private static void pickTargetNearestToCrosshair(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) return;
        Vec3 eye = me.getEyePosition();
        Vec3 look = me.getViewVector(1.0f);
        Player best = null;
        double bestDot = 0.65;
        for (Player player : mc.level.players()) {
            if (player == me || !player.isAlive() || player.isSpectator()) continue;
            Vec3 toTarget = new Vec3(player.getX(), player.getY() + player.getBbHeight() * AIM_HEIGHT, player.getZ()).subtract(eye);
            double distance = toTarget.length();
            if (distance < 0.001 || distance > 32.0) continue;
            double dot = look.dot(toTarget.scale(1.0 / distance));
            if (dot > bestDot) { bestDot = dot; best = player; }
        }
        if (best == null) { hint(mc, "No player near crosshair"); return; }
        targetId = best.getUUID();
        hint(mc, "Target: " + best.getName().getString());
    }

    private static void hint(Minecraft mc, String message) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("[LockOn] " + message));
    }
}
