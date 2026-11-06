package lockon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
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

/**
 * Client-only assisted aiming on a consensual PvP testing server.
 * Sword attacks only when the vanilla cooldown has fully recharged.
 */
public final class LockOnClient implements ClientModInitializer {
    private static final double LOCK_RANGE = 8.0;
    private static final double AIM_SPEED = 20.0;
    private static final double AIM_HEIGHT = 0.75;
    private static boolean enabled;
    private static UUID targetId;
    private static ClientLevel previousLevel;
    private static KeyMapping toggleKey;
    private static KeyMapping targetKey;

    @Override
    public void onInitializeClient() {
        // KEYBOARD is the 26.3 SDL input type. InputConstants.KEY_* are SDL scan codes.
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.toggle", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R, KeyMapping.Category.MISC));
        targetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.target", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != previousLevel) {
                targetId = null;
                enabled = false;
                previousLevel = mc.level;
            }

            // In 26.3 screens are owned by Gui: mc.gui.screen(), not mc.screen.
            if (mc.gui.screen() == null && mc.player != null) {
                while (toggleKey.consumeClick()) {
                    enabled = !enabled;
                    hint(mc, "LockOn " + (enabled ? "ON" : "OFF"));
                }
                while (targetKey.consumeClick()) {
                    if (targetId != null) {
                        targetId = null;
                        hint(mc, "Target cleared");
                    } else {
                        pickTargetNearestToCrosshair(mc);
                    }
                }
            } else {
                while (toggleKey.consumeClick()) { }
                while (targetKey.consumeClick()) { }
            }
            onTick(mc);
        });
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (!enabled || targetId == null || me == null || mc.level == null) return;
        if (mc.gui.screen() != null || !me.isAlive() || me.isSpectator()) return;

        Player target = mc.level.getPlayerByUUID(targetId);
        if (target == null || target == me || !target.isAlive() || target.isSpectator()) return;
        if (me.distanceTo(target) > LOCK_RANGE) return;

        aimAt(me, target);
        attackWhenCharged(mc, me, target);
    }

    private static void aimAt(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * AIM_HEIGHT, target.getZ());
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float yawDelta = Mth.wrapDegrees(desiredYaw - me.getYRot());
        float pitchDelta = desiredPitch - me.getXRot();
        double blend = 1.0 - Math.exp(-AIM_SPEED * 0.05);
        me.setYRot(me.getYRot() + (float) (yawDelta * blend));
        me.setXRot(Mth.clamp(me.getXRot() + (float) (pitchDelta * blend), -90.0f, 90.0f));
    }

    private static void attackWhenCharged(Minecraft mc, LocalPlayer me, Player target) {
        if (mc.gameMode == null || !me.getMainHandItem().is(ItemTags.SWORDS) || me.isUsingItem()) return;
        if (me.getAttackStrengthScale(0.0f) < 1.0f) return;
        // This respects the same line-of-sight and reach rules as vanilla client targeting.
        HitResult hit = me.raycastHitResult(1.0f, me);
        if (!(hit instanceof EntityHitResult entityHit) || entityHit.getEntity() != target) return;
        mc.gameMode.attack(me, target);
        // 26.3 swing API requires the held item's animation and a sync flag.\n        me.swing(InteractionHand.MAIN_HAND, me.getMainHandItem().getAttackAnimation(), false);
    }

    private static void pickTargetNearestToCrosshair(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) return;
        Vec3 eye = me.getEyePosition();
        Vec3 look = me.getViewVector(1.0f);
        Player best = null;
        double bestDot = 0.65;
        for (Player candidate : mc.level.players()) {
            if (candidate == me || !candidate.isAlive() || candidate.isSpectator()) continue;
            Vec3 toTarget = new Vec3(candidate.getX(),
                candidate.getY() + candidate.getBbHeight() * AIM_HEIGHT,
                candidate.getZ()).subtract(eye);
            double distance = toTarget.length();
            if (distance < 0.001 || distance > 32.0) continue;
            double dot = look.dot(toTarget.scale(1.0 / distance));
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        if (best == null) {
            hint(mc, "No player in front of crosshair");
        } else {
            targetId = best.getUUID();
            hint(mc, "Target: " + best.getName().getString());
        }
    }

    private static void hint(Minecraft mc, String message) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("[LockOn] " + message));
    }
}
