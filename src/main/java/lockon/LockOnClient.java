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
import net.minecraft.world.phys.Vec3;

/**
 * Client-side practice lock-on for consenting friends on private servers.
 * It performs normal attacks only in vanilla sword reach at full cooldown.
 */
public final class LockOnClient implements ClientModInitializer {
    private static final double TRACK_RANGE = 16.0;
    // A normal survival-mode sword has 3 blocks of reach. This does not bypass the server's reach check.
    private static final double ATTACK_REACH = 3.0;
    private static final double HITBOX_MARGIN = 0.14;

    private static boolean enabled;
    private static boolean comboMovement = true;
    private static boolean ownsMovement;
    private static UUID targetId;
    private static ClientLevel previousLevel;
    private static KeyMapping toggleKey;
    private static KeyMapping targetKey;
    private static KeyMapping movementKey;
    private static int ticks;
    private static int releaseSprintTicks;
    private static int lastAttackTick = -1000;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.toggle", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R, KeyMapping.Category.MISC));
        targetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.target", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G, KeyMapping.Category.MISC));
        movementKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.lockon.movement", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_H, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level != previousLevel) {
            previousLevel = mc.level;
            targetId = null;
            enabled = false;
            releaseSprintTicks = 0;
            releaseMovement(mc);
        }

        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || mc.gui.screen() != null) {
            consumeKeys();
            releaseMovement(mc);
            return;
        }

        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) releaseMovement(mc);
            hint(mc, "LockOn " + (enabled ? "ON" : "OFF"));
        }
        while (targetKey.consumeClick()) {
            if (targetId != null) {
                targetId = null;
                releaseMovement(mc);
                hint(mc, "Target cleared");
            } else {
                selectTarget(mc);
            }
        }
        while (movementKey.consumeClick()) {
            comboMovement = !comboMovement;
            if (!comboMovement) releaseMovement(mc);
            hint(mc, "Chase + strafe " + (comboMovement ? "ON" : "OFF"));
        }

        if (!enabled || targetId == null || !me.isAlive() || me.isSpectator()) {
            releaseMovement(mc);
            return;
        }

        Player target = mc.level.getPlayerByUUID(targetId);
        if (target == null || !target.isAlive() || target.isSpectator()
            || target == me || me.distanceTo(target) > TRACK_RANGE) {
            releaseMovement(mc);
            return;
        }

        aimAt(me, target);
        if (comboMovement) {
            moveForCombo(mc, me, target);
        } else {
            releaseMovement(mc);
        }
        attackWhenReady(mc, me, target);
    }

    private static void consumeKeys() {
        while (toggleKey.consumeClick()) {}
        while (targetKey.consumeClick()) {}
        while (movementKey.consumeClick()) {}
    }

    private static void aimAt(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        double distance = me.distanceTo(target);
        // Short lead compensates for player motion without aiming far outside their hitbox.
        double lead = Math.min(1.3, Math.max(0.0, distance * 0.16));
        Vec3 velocity = target.getDeltaMovement();
        Vec3 aim = new Vec3(
            target.getX() + Mth.clamp(velocity.x * lead, -0.20, 0.20),
            target.getY() + target.getBbHeight() * 0.72,
            target.getZ() + Mth.clamp(velocity.z * lead, -0.20, 0.20));
        Vec3 offset = aim.subtract(eye);
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        float yaw = (float) Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(offset.y, horizontal));
        // Fast aim up close is important for moving targets and combo follow-up.
        double factor = distance < 4.0 ? 0.96 : 0.82;
        float yawDelta = Mth.wrapDegrees(yaw - me.getYRot());
        me.setYRot(me.getYRot() + (float) (yawDelta * factor));
        me.setXRot(Mth.clamp(me.getXRot() + (float) ((pitch - me.getXRot()) * factor), -90.0f, 90.0f));
    }

    private static void attackWhenReady(Minecraft mc, LocalPlayer me, Player target) {
        if (mc.gameMode == null || !me.getMainHandItem().is(ItemTags.SWORDS)
            || me.isUsingItem() || ticks - lastAttackTick < 3) return;

        // Full-strength sword attacks, not 20 CPS clicks.
        if (me.getAttackStrengthScale(0.0f) < 0.999f) return;
        if (!me.hasLineOfSight(target)) return;

        // Check the target's ACTUAL bounding box along the player's current view.
        // The previous entity raycast could miss after the camera was rotated this tick.
        Vec3 eye = me.getEyePosition();
        Vec3 end = eye.add(me.getViewVector(1.0f).scale(ATTACK_REACH));
        if (target.getBoundingBox().inflate(HITBOX_MARGIN).clip(eye, end).isEmpty()) return;

        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND, me.getMainHandItem().getAttackAnimation(), false);
        lastAttackTick = ticks;

        // Briefly release sprint to allow the next contact to re-enable sprint knockback.
        if (comboMovement) {
            releaseSprintTicks = 1;
            me.setSprinting(false);
        }
    }

    private static void moveForCombo(Minecraft mc, LocalPlayer me, Player target) {
        if (me.isPassenger() || me.isFallFlying() || me.isInWater() || !me.hasLineOfSight(target)) {
            releaseMovement(mc);
            return;
        }

        double dx = target.getX() - me.getX();
        double dz = target.getZ() - me.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // Chase to sword range, strafe around the opponent, and avoid face-hugging.
        boolean forward = horizontal > 2.35;
        boolean backward = horizontal < 1.45;
        boolean strafe = horizontal < 5.2;
        boolean left = ((ticks / 32) & 1) == 0;

        // A short W-tap between contacts assists sprint reset instead of spamming attacks.
        if (releaseSprintTicks > 0) {
            forward = false;
            releaseSprintTicks--;
        }

        mc.options.keyUp.setDown(forward);
        mc.options.keyDown.setDown(backward);
        mc.options.keyLeft.setDown(strafe && left);
        mc.options.keyRight.setDown(strafe && !left);
        mc.options.keySprint.setDown(forward);
        ownsMovement = true;
    }

    private static void releaseMovement(Minecraft mc) {
        if (!ownsMovement) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        ownsMovement = false;
    }

    private static void selectTarget(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) return;
        Vec3 eye = me.getEyePosition();
        Vec3 look = me.getViewVector(1.0f);
        Player best = null;
        double bestScore = 0.58;
        for (Player other : mc.level.players()) {
            if (other == me || !other.isAlive() || other.isSpectator()
                || me.distanceTo(other) > TRACK_RANGE || !me.hasLineOfSight(other)) continue;
            Vec3 center = new Vec3(other.getX(),
                other.getY() + other.getBbHeight() * 0.72, other.getZ());
            Vec3 to = center.subtract(eye);
            double length = to.length();
            if (length < 0.001) continue;
            double dot = look.dot(to.scale(1.0 / length));
            if (dot > bestScore) {
                bestScore = dot;
                best = other;
            }
        }
        if (best != null) {
            targetId = best.getUUID();
            hint(mc, "Target: " + best.getName().getString());
        } else {
            hint(mc, "No visible player near your crosshair");
        }
    }

    private static void hint(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("[LockOn] " + message));
        }
    }
}
