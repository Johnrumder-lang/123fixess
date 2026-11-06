package lockon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.item.Items;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
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
    // Monotonic deadline: random 20-50 ms after fully charged.
    private static long meleeReadyNanos = -1L;
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
    private static int navigationJumpUntil = -1;
    private static int lastNavigationJumpTick = -1000;
    private static Navigator.Step nextStep;
    private static BlockPos miningPos;

    @Override public void onInitializeClient() {
        toggleKey = key("key.lockon.toggle", InputConstants.KEY_R);
        selectKey = key("key.lockon.target", InputConstants.KEY_G);
        chaseKey = key("key.lockon.movement", InputConstants.KEY_H);
        critKey = key("key.lockon.criticals", InputConstants.KEY_J);
        mineKey = key("key.lockon.mining", InputConstants.KEY_B);
        ClientTickEvents.END_CLIENT_TICK.register(LockOnClient::tick);
        // Rendering runs between client ticks, letting us check the 20-50ms
        // deadline more often than Minecraft's normal 50ms gameplay tick.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lockon", "attack_timing"), (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!enabled || targetId == null || mc.level == null || mc.player == null
                || mc.gui.screen() != null || !mc.player.isAlive()) return;
            if (mc.player.getMainHandItem().is(Items.BOW)) return;
            Player target = mc.level.getPlayerByUUID(targetId);
            if (target != null && target.isAlive() && !target.isSpectator()
                && mc.player.distanceTo(target) <= TARGET_RANGE) {
                attack(mc, mc.player, target);
            }
        });
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
        // In actual melee range, center the ray INSIDE the hitbox.
        // Predictive leading is only useful while approaching from a distance.
        double prediction = distance < 3.8 ? 0.0 : Mth.clamp(distance * 0.10, 0, 0.9);
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
        meleeReadyNanos = -1L;
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
        if (mc.gameMode == null || me.isUsingItem()
            || !me.getMainHandItem().is(ItemTags.SWORDS) || miningPos != null) {
            resetAttackSchedule();
            return;
        }
        if (ticks - lastAttackTick < 2 || me.getAttackStrengthScale(0.0f) < 0.999f) {
            return;
        }

        // Start a random 20-50 millisecond delay ONLY ONCE after a fully
        // charged attack becomes available. Keep the deadline if the target
        // briefly moves out of reach, and re-check it on render frames too.
        if (scheduledTarget != null && !scheduledTarget.equals(target.getUUID())) {
            resetAttackSchedule();
        }
        if (meleeReadyNanos < 0L) {
            scheduledTarget = target.getUUID();
            meleeReadyNanos = System.nanoTime()
                + ThreadLocalRandom.current().nextLong(20_000_000L, 50_000_001L);
        }
        if (System.nanoTime() < meleeReadyNanos) return;

        // Keep the attack ready when just outside reach; hit immediately
        // on re-entry. Still enforce line of sight and actual hitbox aim.
        Vec3 eye = me.getEyePosition();
        Vec3 ray = eye.add(me.getViewVector(1.0f).scale(ATTACK_REACH));
        if (!me.hasLineOfSight(target)
            || target.getBoundingBox().inflate(0.08).clip(eye, ray).isEmpty()) return;

        boolean falling = !me.onGround() && me.fallDistance > 0.0f
            && me.getDeltaMovement().y < -0.01
            && !me.isInWater() && !me.onClimbable() && !me.isPassenger();
        boolean airborneOpponent = !target.onGround() || target.getDeltaMovement().y > 0.08;

        // Prioritize immediate follow-up hits in a combo. A jump crit is
        // worthwhile only if the opponent is grounded and nearly stationary.
        boolean standingInCritRange = me.distanceTo(target) < 2.20
            && Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z) < 0.16;
        if (criticals && !airborneOpponent && me.onGround() && mc.level != null
            && standingInCritRange && ticks >= nextCritJumpAt
            && Navigator.safeToJump(mc.level, me)) {
            me.setSprinting(false);
            releaseSprint(mc);
            me.jumpFromGround();
            lastCritJumpTick = ticks;
            nextCritJumpAt = ticks + 22;
            return;
        }

        // Allow a very short falling-crit window, then hit normally
        // rather than holding a fully charged sword without attacking.
        if (criticals && !falling && ticks - lastCritJumpTick < 6 && !airborneOpponent) return;
        if (falling && criticals) {
            me.setSprinting(false);
            releaseSprint(mc);
        }

        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND, me.getMainHandItem().getAttackAnimation(), false);
        lastAttackTick = ticks;
        resetAttackSchedule();
        sprintResetTicks = airborneOpponent ? 1 : 2;
    }

    private static void chaseTarget(Minecraft mc, LocalPlayer me, Player target) {
        double d = horizontalDistance(me.getX(), me.getZ(), target.getX(), target.getZ());
        // Do not switch to close-range strafing while the target is above a
        // step or a solid 1-block obstacle still separates the two players.
        double dxToTarget = target.getX() - me.getX();
        double dzToTarget = target.getZ() - me.getZ();
        boolean oneBlockAhead = Navigator.oneBlockAhead(mc.level, me, dxToTarget, dzToTarget);
        boolean sameHeight = Math.abs(target.getY() - me.getY()) < 0.58;
        if (d < 3.05 && sameHeight && !oneBlockAhead && me.hasLineOfSight(target)) {
            stopMining(mc);
            nextStep = null;
            // Small alternating orbit and measured backing off. Never chase into a hole.
            boolean left = ((ticks / 24) & 1) == 0;
            double yaw = Math.toRadians(me.getYRot());
            double sideX = left ? -Math.cos(yaw) : Math.cos(yaw);
            double sideZ = left ? -Math.sin(yaw) : Math.sin(yaw);
            boolean leftSafe = safeSide(mc.level, me, -Math.cos(yaw), -Math.sin(yaw));
            boolean rightSafe = safeSide(mc.level, me, Math.cos(yaw), Math.sin(yaw));
            if (left && !leftSafe) left = false;
            else if (!left && !rightSafe) left = true;
            boolean airborneOpponent = !target.onGround() || target.getDeltaMovement().y > 0.08;
            // Close the gap quickly after knockback; only orbit once genuinely inside range.
            boolean forward = d > (airborneOpponent ? 1.82 : 2.22) && sprintResetTicks == 0;
            boolean back = !airborneOpponent && d < 1.30;
            boolean orbit = d < 2.6 && !forward && (left ? leftSafe : rightSafe);
            boolean inCritWindow = ticks - lastCritJumpTick <= 8 && !me.onGround();
            boolean sprintNow = forward && me.onGround() && !inCritWindow;
            setMovement(mc, forward, back, orbit && left, orbit && !left, sprintNow);
            if (sprintResetTicks > 0) sprintResetTicks--;
            return;
        }

        // Direct one-block step-up is faster and more reliable than recalculating
        // a route every eight ticks at point-blank range. Jump into the obstacle.
        if (oneBlockAhead && Navigator.safeToJump(mc.level, me)
            && d < 5.0 && !me.isInWater()) {
            stopMining(mc);
            nextStep = null;
            requestNavigationJump(mc, me);
            setMovement(mc, true, false, false, false, me.onGround());
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
        boolean shouldJump = goal.getY() > me.getY() + 0.48
            || Navigator.oneBlockAhead(mc.level, me, x, z);
        if (shouldJump) requestNavigationJump(mc, me);
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

    private static void requestNavigationJump(Minecraft mc, LocalPlayer me) {
        if (!me.onGround() || ticks - lastNavigationJumpTick < 11
            || mc.level == null || !Navigator.safeToJump(mc.level, me)) return;
        me.jumpFromGround();
        lastNavigationJumpTick = ticks;
        navigationJumpUntil = ticks + 3;
    }

    private static void setMovement(Minecraft mc, boolean f, boolean b, boolean l, boolean r, boolean sprint) {
        mc.options.keyJump.setDown(ticks <= navigationJumpUntil);
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
        mc.options.keyJump.setDown(false);
        navigationJumpUntil = -1;
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
        navigationJumpUntil = -1;
        lastNavigationJumpTick = -1000;
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
