package lockon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.item.Items;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Target lock, legal sword hits, optional A* obstacle navigation and mining,
 * and jump-timed critical hit attempts. Designed for authorized private testing.
 */
public final class LockOnClient implements ClientModInitializer {
    private static final double TARGET_RANGE = 17.0;
    // Aim/attack ray length is 3.1 blocks; actual hits still require server-valid reach.
    private static final double ATTACK_REACH = 3.1;
    private static final double BOW_TRACK_RANGE = 54.0;
    private static final double ARROW_SPEED = 3.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static int meleeReadyAt = -1;
    private static UUID scheduledTarget;
    private static boolean enabled;
    private static boolean chase = true;
    private static boolean criticals = true;
    // Destructive behavior is opt-in by pressing B.
    private static boolean mining = false;
    private static boolean ownsMovement;
    private static UUID targetId;
    private static ClientLevel previousLevel;
    private static KeyMapping toggleKey, selectKey, chaseKey, critKey, mineKey;
    private static int ticks;
    private static int nextPlanAt;
    private static int nextCritJumpAt;
    private static int lastCritJumpTick = -1000;
    private static int lastAttackTick = -1000;
    private static int sprintResetTicks;
    private static Navigator.Step nextStep;
    private static BlockPos miningPos;

    @Override public void onInitializeClient() {
        toggleKey = key("key.lockon.toggle", InputConstants.KEY_R);
        selectKey = key("key.lockon.target", InputConstants.KEY_G);
        chaseKey = key("key.lockon.movement", InputConstants.KEY_H);
        critKey = key("key.lockon.criticals", InputConstants.KEY_J);
        mineKey = key("key.lockon.mining", InputConstants.KEY_B);
        ClientTickEvents.END_CLIENT_TICK.register(LockOnClient::tick);
    }

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
            name, InputConstants.Type.KEYBOARD, code, KeyMapping.Category.MISC));
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level != previousLevel) {
            previousLevel = mc.level;
            enabled = false;
            targetId = null;
            resetAttackSchedule();
            clearNavigation(mc);
        }
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || mc.gui.screen() != null) {
            drainKeys();
            stopActions(mc);
            return;
        }

        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) { stopActions(mc); resetAttackSchedule(); }
            hint(mc, "LockOn " + (enabled ? "ON" : "OFF"));
        }
        while (selectKey.consumeClick()) {
            if (targetId != null) {
                targetId = null;
                resetAttackSchedule();
                stopActions(mc);
                hint(mc, "Target cleared");
            } else selectTarget(mc);
        }
        while (chaseKey.consumeClick()) {
            chase = !chase;
            if (!chase) stopActions(mc);
            hint(mc, "Smart chase " + (chase ? "ON" : "OFF"));
        }
        while (critKey.consumeClick()) {
            criticals = !criticals;
            hint(mc, "Jump crits " + (criticals ? "ON" : "OFF"));
        }
        while (mineKey.consumeClick()) {
            mining = !mining;
            if (!mining) stopMining(mc);
            nextPlanAt = 0;
            hint(mc, "Break obstacles " + (mining ? "ON" : "OFF"));
        }

        if (!enabled || targetId == null || !me.isAlive() || me.isSpectator()) {
            stopActions(mc);
            resetAttackSchedule();
            return;
        }

        boolean bowEquipped = me.getMainHandItem().is(Items.BOW);
        Player target = mc.level.getPlayerByUUID(targetId);
        double trackingRange = bowEquipped ? BOW_TRACK_RANGE : TARGET_RANGE;
        if (target == null || target == me || !target.isAlive() || target.isSpectator()
            || me.distanceTo(target) > trackingRange) {
            stopActions(mc);
            resetAttackSchedule();
            return;
        }

        // Bow: predictive aim while the user draws and shoots normally.
        // Do not auto-chase while aiming a bow; it spoils projectile shots.
        if (bowEquipped) {
            stopActions(mc);
            resetAttackSchedule();
            aimBow(me, target);
            return;
        }

        aimAt(me, target);
        if (chase && !me.isPassenger() && !me.isFallFlying() && !me.isInWater()) {
            chaseTarget(mc, me, target);
        } else {
            stopMining(mc);
            releaseMovement(mc);
        }
        attack(mc, me, target);
    }

    private static void drainKeys() {
        while (toggleKey.consumeClick()) {}
        while (selectKey.consumeClick()) {}
        while (chaseKey.consumeClick()) {}
        while (critKey.consumeClick()) {}
        while (mineKey.consumeClick()) {}
    }

    private static void aimAt(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        double distance = me.distanceTo(target);
        Vec3 vel = target.getDeltaMovement();
        double prediction = Mth.clamp(distance * 0.10, 0, 0.9);
        Vec3 center = new Vec3(
            target.getX() + Mth.clamp(vel.x * prediction, -0.13, 0.13),
            target.getY() + target.getBbHeight() * 0.70,
            target.getZ() + Mth.clamp(vel.z * prediction, -0.13, 0.13));
        Vec3 d = center.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        float yawDelta = Mth.wrapDegrees(yaw - me.getYRot());
        double f = distance < 5 ? 1.0 : 0.92;
        me.setYRot(me.getYRot() + (float) (yawDelta * f));
        me.setXRot(Mth.clamp(me.getXRot() + (float) ((pitch - me.getXRot()) * f), -90, 90));
    }

    private static void resetAttackSchedule() {
        meleeReadyAt = -1;
        scheduledTarget = null;
    }

    private static void aimBow(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        Vec3 center = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.67, target.getZ());
        Vec3 delta = center.subtract(eye);
        // Approximate flight time in ticks at a fully drawn vanilla bow's speed.
        // Draw the bow fully for best compensation, and account for target movement.
        double flightTicks = Math.min(25.0, Math.max(1.0, Math.hypot(delta.x, delta.z) / ARROW_SPEED));
        Vec3 speed = target.getDeltaMovement();
        Vec3 lead = new Vec3(
            Mth.clamp(speed.x * flightTicks * 0.85, -4.0, 4.0),
            Mth.clamp(speed.y * flightTicks * 0.30, -1.0, 1.0),
            Mth.clamp(speed.z * flightTicks * 0.85, -4.0, 4.0));
        Vec3 predicted = center.add(lead).subtract(eye);
        double horizontal = Math.hypot(predicted.x, predicted.z);
        if (horizontal < 0.01) return;
        // Low-angle ballistic trajectory with gravity; falls back to straight aim
        // if the target is too far away to reach at this velocity.
        double velocity2 = ARROW_SPEED * ARROW_SPEED;
        double discriminant = velocity2 * velocity2 - ARROW_GRAVITY
            * (ARROW_GRAVITY * horizontal * horizontal + 2.0 * predicted.y * velocity2);
        double elevation = discriminant >= 0
            ? Math.atan((velocity2 - Math.sqrt(discriminant)) / (ARROW_GRAVITY * horizontal))
            : Math.atan2(predicted.y, horizontal);
        float yaw = (float) Math.toDegrees(Math.atan2(predicted.z, predicted.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(elevation);
        me.setYRot(me.getYRot() + Mth.wrapDegrees(yaw - me.getYRot()) * 0.94f);
        me.setXRot(Mth.clamp(me.getXRot() + (pitch - me.getXRot()) * 0.94f, -90, 90));
    }

    private static void attack(Minecraft mc, LocalPlayer me, Player target) {
        if (mc.gameMode == null || me.isUsingItem() || !me.getMainHandItem().is(ItemTags.SWORDS)
            || miningPos != null || ticks - lastAttackTick < 3) {
            resetAttackSchedule();
            return;
        }
        // Ray is 3.1 blocks long; this does not enlarge Minecraft/server entity reach.
        Vec3 eye = me.getEyePosition();
        Vec3 ray = eye.add(me.getViewVector(1.0f).scale(ATTACK_REACH));
        if (!me.hasLineOfSight(target)
            || target.getBoundingBox().inflate(0.05).clip(eye, ray).isEmpty()
            || me.getAttackStrengthScale(0.0f) < 0.999f) {
            resetAttackSchedule();
            return;
        }

        // 20 TPS: random 2 or 3 tick pause (100-150 ms) once the
        // actual sword cooldown is fully charged and target is hittable.
        if (!target.getUUID().equals(scheduledTarget) || meleeReadyAt < 0) {
            scheduledTarget = target.getUUID();
            meleeReadyAt = ticks + 2 + ThreadLocalRandom.current().nextInt(2);
            return;
        }
        if (ticks < meleeReadyAt) return;

        boolean falling = !me.onGround() && me.fallDistance > 0.0f
            && me.getDeltaMovement().y < -0.01
            && !me.isInWater() && !me.onClimbable() && !me.isPassenger();
        // Airborne opponents need prompt combo follow-ups more than delayed crit hops.
        boolean opponentAirborne = !target.onGround() || target.getDeltaMovement().y > 0.08;
        boolean goodCritOpportunity = criticals && !opponentAirborne
            && me.onGround() && mc.level != null
            && me.distanceTo(target) < 2.65 && ticks >= nextCritJumpAt
            && Navigator.safeToJump(mc.level, me);

        if (goodCritOpportunity) {
            me.setSprinting(false);
            releaseSprint(mc);
            me.jumpFromGround();
            lastCritJumpTick = ticks;
            nextCritJumpAt = ticks + 18;
            // Keep this same attack scheduled until the descending phase.
            return;
        }
        if (criticals && !falling && ticks - lastCritJumpTick < 9) return;
        if (falling && criticals) {
            me.setSprinting(false);
            releaseSprint(mc);
        }

        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND, me.getMainHandItem().getAttackAnimation(), false);
        lastAttackTick = ticks;
        resetAttackSchedule();
        sprintResetTicks = opponentAirborne ? 1 : 2;
    }

    private static void chaseTarget(Minecraft mc, LocalPlayer me, Player target) {
        double d = horizontalDistance(me.getX(), me.getZ(), target.getX(), target.getZ());
        if (d < 3.05 && me.hasLineOfSight(target)) {
            stopMining(mc);
            nextStep = null;
            // Small alternating orbit and measured backing off. Never chase into a hole.
            boolean left = ((ticks / 24) & 1) == 0;
            double yaw = Math.toRadians(me.getYRot());
            double sideX = left ? -Math.cos(yaw) : Math.cos(yaw);
            double sideZ = left ? -Math.sin(yaw) : Math.sin(yaw);
            if (!safeSide(mc.level, me, sideX, sideZ)) left = !left;
            boolean airborneOpponent = !target.onGround() || target.getDeltaMovement().y > 0.08;
            // Stay close enough to follow knockback without walking directly into them.
            boolean forward = d > (airborneOpponent ? 1.95 : 2.35) && sprintResetTicks == 0;
            boolean back = !airborneOpponent && d < 1.40;
            boolean inCritWindow = ticks - lastCritJumpTick <= 9 && !me.onGround();
            // Sprint between contacts; disable it for the actual falling crit strike.
            boolean sprintNow = forward && me.onGround() && !inCritWindow;
            setMovement(mc, forward, back, left, !left, sprintNow);
            if (sprintResetTicks > 0) sprintResetTicks--;
            return;
        }

        if (ticks >= nextPlanAt || nextStep == null) {
            nextPlanAt = ticks + 8;
            nextStep = Navigator.route(mc.level, me, target, mining);
        }
        if (nextStep == null) {
            stopMining(mc);
            releaseMovement(mc);
            return;
        }

        BlockPos blocker = nextStep.nextBlockToBreak(mc.level);
        if (blocker != null) {
            if (mining && Navigator.mineable(mc.level, me, blocker)
                && me.getEyePosition().distanceTo(Vec3.atCenterOf(blocker)) < 4.3) {
                releaseMovement(mc);
                mineBlock(mc, blocker);
            } else {
                stopMining(mc);
                releaseMovement(mc);
            }
            return;
        }
        stopMining(mc);

        BlockPos goal = nextStep.position();
        double x = goal.getX() + 0.5 - me.getX();
        double z = goal.getZ() + 0.5 - me.getZ();
        double length = Math.sqrt(x * x + z * z);
        if (length < 0.24) {
            nextPlanAt = 0;
            releaseMovement(mc);
            return;
        }
        x /= length;
        z /= length;

        double yaw = Math.toRadians(me.getYRot());
        double forward = x * -Math.sin(yaw) + z * Math.cos(yaw);
        double strafe = x * Math.cos(yaw) + z * Math.sin(yaw);
        boolean shouldJump = goal.getY() > me.getY() + 0.48;
        if (shouldJump && me.onGround() && ticks >= nextCritJumpAt
            && Navigator.safeToJump(mc.level, me)) {
            me.jumpFromGround();
            nextCritJumpAt = ticks + 9;
        }
        setMovement(mc, forward > 0.25 && sprintResetTicks == 0,
            forward < -0.35, strafe < -0.35, strafe > 0.35,
            forward > 0.60 && sprintResetTicks == 0);
        if (sprintResetTicks > 0) sprintResetTicks--;
    }

    private static boolean safeSide(ClientLevel level, LocalPlayer me, double dx, double dz) {
        BlockPos dest = BlockPos.containing(me.getX() + dx * 0.7, me.getY() + 0.08,
            me.getZ() + dz * 0.7);
        return Navigator.passable(level, dest) && Navigator.passable(level, dest.above())
            && !level.getBlockState(dest.below()).getCollisionShape(level, dest.below()).isEmpty();
    }

    private static void mineBlock(Minecraft mc, BlockPos block) {
        if (mc.gameMode == null || mc.level == null) return;
        if (miningPos == null || !miningPos.equals(block)) {
            stopMining(mc);
            miningPos = block;
            mc.gameMode.startDestroyBlock(block, Direction.UP);
        } else {
            mc.gameMode.continueDestroyBlock(block, Direction.UP);
        }
        if (mc.level.getBlockState(block).isAir()) {
            stopMining(mc);
            nextPlanAt = 0;
        }
    }

    private static void stopMining(Minecraft mc) {
        if (miningPos == null) return;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        miningPos = null;
    }

    private static void setMovement(Minecraft mc, boolean f, boolean b, boolean l, boolean r, boolean sprint) {
        mc.options.keyUp.setDown(f);
        mc.options.keyDown.setDown(b);
        mc.options.keyLeft.setDown(l);
        mc.options.keyRight.setDown(r);
        mc.options.keySprint.setDown(sprint);
        ownsMovement = true;
    }

    private static void releaseSprint(Minecraft mc) {
        if (ownsMovement) mc.options.keySprint.setDown(false);
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

    private static void stopActions(Minecraft mc) {
        stopMining(mc);
        releaseMovement(mc);
    }

    private static void clearNavigation(Minecraft mc) {
        stopActions(mc);
        nextStep = null;
        nextPlanAt = 0;
        sprintResetTicks = 0;
    }

    private static double horizontalDistance(double ax, double az, double bx, double bz) {
        return Math.hypot(ax - bx, az - bz);
    }

    private static void selectTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        LocalPlayer me = mc.player;
        Vec3 eye = me.getEyePosition();
        Vec3 look = me.getViewVector(1.0f);
        Player closest = null;
        double bestDot = 0.55;
        double range = me.getMainHandItem().is(Items.BOW) ? BOW_TRACK_RANGE : TARGET_RANGE;
        for (Player p : mc.level.players()) {
            if (p == me || p.isSpectator() || !p.isAlive()
                || me.distanceTo(p) > range || !me.hasLineOfSight(p)) continue;
            Vec3 vector = new Vec3(p.getX(), p.getY() + p.getBbHeight() * 0.7, p.getZ()).subtract(eye);
            if (vector.lengthSqr() == 0) continue;
            double dot = look.dot(vector.normalize());
            if (dot > bestDot) { bestDot = dot; closest = p; }
        }
        if (closest == null) {
            hint(mc, "No player visible near the crosshair");
        } else {
            targetId = closest.getUUID();
            resetAttackSchedule();
            nextPlanAt = 0;
            hint(mc, "Target: " + closest.getName().getString());
        }
    }

    private static void hint(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("[LockOn] " + text));
    }
}
