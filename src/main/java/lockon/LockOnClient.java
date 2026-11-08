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
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Target lock, legal sword hits, optional A* obstacle navigation and mining,
 * and jump-timed critical hit attempts. Designed for authorized private testing.
 */
public final class LockOnClient implements ClientModInitializer {
    private static final double TARGET_RANGE = 17.0;
    // Saved client-only attack ray. Server validates the actual reach.
    private static double attackReach() { return LockOnSettings.attackReach(); }
    private static final double BOW_TRACK_RANGE = 54.0;
    private static final double ARROW_SPEED = 3.0;
    private static final double ARROW_GRAVITY = 0.05;
    // Monotonic deadline: random 20-50 ms after the chosen hit threshold.
    // Combo follow-ups require >=86% charge for sprint knockback; openers
    // and ordinary hits prefer the full 100% cooldown.
    private static long meleeReadyNanos = -1L;
    private static UUID scheduledTarget;
    private static boolean enabled;
    private static boolean chase = true;
    // Off by default until reliable ordinary follow-up hits are confirmed.
    // Press J to opt into jump criticals after testing regular attacks.
    private static boolean criticals = false;
    private static boolean precisionMode = false; // K toggles full-charge-only attacks
    // Destructive behavior is opt-in by pressing B.
    private static boolean mining = false;
    private static boolean ownsMovement;
    private static UUID targetId;
    private static ClientLevel previousLevel;
    private static KeyMapping toggleKey, selectKey, chaseKey, critKey, mineKey, modeKey, settingsKey, statsKey, manualMineKey, survivalKey;
    private static boolean migratedConflictingKeys;
    private static UUID trackedVelocityTarget;
    private static Vec3 filteredVelocity = new Vec3(0.0, 0.0, 0.0);
    private static UUID observedHurtTarget;
    private static int lastObservedHurtTime = -1;
    private static int attempts;
    private static int hurtSignals;
    private static int outOfRangeSkips;
    private static int streak;
    private static int bestStreak;
    private static int lastHurtSignalTick = -1000;
    private static int ticks;
    private static int nextPlanAt;
    private static int nextCritJumpAt;
    private static int lastCritJumpTick = -1000;
    private static int lastAttackTick = -1000;
    private static UUID lastAttackedTarget;
    // Weapon switches invalidate the cooldown/combo timing carried from the prior item.
    // 0=sword, 1=empty hand, 2=other item.
    private static int previousWeaponMode = -1;
    private static Vec3 lastMovementSample;
    private static int lastMovementSampleTick;
    private static int recoveryUntilTick;
    private static int recoverySide = 1;
    // A connected sword contact offers a brief opportunity to use an
    // earlier sprint-knockback follow-up instead of waiting for full charge.
    // Minecraft 26.3 sprint knockback requires >0.90 attack strength.
    // Leave a margin and avoid weaker hits being rejected during immunity.
    private static final float COMBO_ATTACK_CHARGE = 0.95f;
    private static final int COMBO_WINDOW_TICKS = 22;
    private static int sprintResetTicks;
    private static int navigationJumpUntil = -1;
    private static int lastNavigationJumpTick = -1000;
    private static Navigator.Step nextStep;
    private static BlockPos miningPos;
    private static Direction miningFace = Direction.UP;
    private static boolean manualMining;
    private static int minedBlocks;
    private static int cooldownWaits;
    private static int aimRayMisses;
    private static int fistFallbacks;
    private static String lastAttackHold = "none";

    @Override public void onInitializeClient() {
        LockOnSettings.load();
        LockOnPlayerFeedClient.initialize();
        LockOnTracers.initialize();
        toggleKey = key("key.lockon.toggle", InputConstants.KEY_R);
        selectKey = key("key.lockon.target", InputConstants.KEY_G);
        chaseKey = key("key.lockon.movement", InputConstants.KEY_H);
        critKey = key("key.lockon.criticals", InputConstants.KEY_J);
        mineKey = key("key.lockon.mining", InputConstants.KEY_B);
        modeKey = key("key.lockon.mode", InputConstants.KEY_K);
        // Minecraft 26.3 binds O to Friends and P to Social Interactions.
        // Both built-in screens consume the input before this mod sees it.
        settingsKey = key("key.lockon.settings", InputConstants.KEY_F6);
        statsKey = key("key.lockon.stats", InputConstants.KEY_U);
        manualMineKey = key("key.lockon.mine_target", InputConstants.KEY_N);
        survivalKey = key("key.lockon.survival", InputConstants.KEY_F7);
        ClientTickEvents.START_CLIENT_TICK.register(LockOnMovement::beforeClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(LockOnClient::tick);
        // Attack packets belong in client gameplay ticks, not in HUD render frames.
        // Rendering can run between vanilla movement packets, so a visual
        // crit effect is not evidence of a server-side hit.
    }

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
            name, InputConstants.Type.KEYBOARD, code, KeyMapping.Category.MISC));
    }

    private static void tick(Minecraft mc) {
        ticks++;
        // Old versions registered P/O, so Minecraft may have saved those
        // conflicting keys in options.txt. Correct the OLD persisted default
        // on the first real client tick as well as changing the new default.
        if (!migratedConflictingKeys && mc.options != null) {
            migratedConflictingKeys = true;
            boolean changed = false;
            if ("key.keyboard.p".equals(survivalKey.saveString())) {
                survivalKey.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F7));
                changed = true;
            }
            if ("key.keyboard.o".equals(settingsKey.saveString())) {
                settingsKey.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F6));
                changed = true;
            }
            if (changed) {
                KeyMapping.resetMapping();
                mc.options.save();
                System.out.println("[LockOn] Migrated conflicting P/O keys to F7/F6.");
            }
        }
        if(LockOnSurvival.handleDeath(mc)) return;
        if (mc.level != previousLevel) {
            if (LockOnSurvival.enabled() && !LockOnSurvival.recoveringDeath())
                LockOnSurvival.stop(mc, "World changed: survival mode stopped");
            previousLevel = mc.level;
            enabled = false;
            targetId = null;
            resetAttackSchedule();
            clearNavigation(mc);
            lastAttackedTarget = null;
            previousWeaponMode = -1;
            resetTracking();
            attempts = 0;
            hurtSignals = 0;
            outOfRangeSkips = 0;
            cooldownWaits = 0;
            aimRayMisses = 0;
            fistFallbacks = 0;
            minedBlocks = 0;
            lastAttackHold = "none";
            streak = 0;
            bestStreak = 0;
            lastHurtSignalTick = -1000;
        }
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) {
            drainKeys();
            stopActions(mc);
            return;
        }
        if (mc.gui.screen() != null && !LockOnSurvival.managingContainer()) {
            drainKeys();
            stopActions(mc);
            return;
        }

        while (survivalKey.consumeClick()) {
            hint(mc, LockOnSurvival.enabled()
                ? "F7: stopping survival autoplay"
                : "F7: starting survival autoplay (press U for live status)");
            if (!LockOnSurvival.enabled()) {
                // Survival and PvP automation never take control at once.
                enabled = false;
                targetId = null;
                stopActions(mc);
                resetAttackSchedule();
            }
            LockOnSurvival.toggle(mc);
        }
        if (LockOnSurvival.enabled()) {
            while (statsKey.consumeClick()) {
                String status = "Survival: " + LockOnSurvival.status()
                    + " | Base: " + LockOnBase.status()
                    + " | " + LockOnMovement.diagnostic(mc);
                hint(mc, status);
                // U also sends it to chat so long troubleshooting messages
                // are not cut off by Minecraft's one-line action bar.
                mc.player.sendSystemMessage(Component.literal("[LockOn] " + status));
            }
            LockOnSurvival.tick(mc);
            return;
        }

        while (settingsKey.consumeClick()) {
            stopActions(mc);
            resetAttackSchedule();
            mc.gui.setScreen(LockOnSettings.createScreen(mc.gui.screen()));
            return;
        }
        while (statsKey.consumeClick()) {
            hint(mc, "Attacks " + attempts + ", hurt " + hurtSignals
                + ", mined " + minedBlocks + ", range " + outOfRangeSkips
                + ", aim " + aimRayMisses + ", fist retries " + fistFallbacks
                + ", hold: " + lastAttackHold);
        }
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if(enabled) LockOnMovement.begin(mc);
            if (!enabled) {
                stopActions(mc);
                resetAttackSchedule();
                lastAttackTick = -1000;
                lastAttackedTarget = null;
            }
            hint(mc, "LockOn " + (enabled ? "ON" : "OFF"));
        }
        while (selectKey.consumeClick()) {
            if (targetId != null) {
                targetId = null;
                resetTracking();
                resetAttackSchedule();
                lastAttackTick = -1000;
                lastAttackedTarget = null;
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
            hint(mc, "Block breaking " + (mining ? "ON (N mines aimed block)" : "OFF"));
        }
        while (manualMineKey.consumeClick()) {
            if (!mining) {
                hint(mc, "Press B to enable block breaking, then N at a block.");
            } else if (mc.hitResult instanceof BlockHitResult hit
                && me.getEyePosition().distanceTo(Vec3.atCenterOf(hit.getBlockPos())) < 4.2
                && Navigator.mineable(mc.level, me, hit.getBlockPos())) {
                stopMining(mc);
                stopActions(mc);
                mineBlock(mc, hit.getBlockPos(), hit.getDirection());
                manualMining = miningPos != null;
                hint(mc, "Mining " + hit.getBlockPos().toShortString()
                    + " (server permission and suitable tool required)");
            } else {
                hint(mc, "Aim at a breakable nearby block (no containers/protected blocks).");
            }
        }
        while (modeKey.consumeClick()) {
            precisionMode = !precisionMode;
            resetAttackSchedule();
            hint(mc, "Combat: " + (precisionMode ? "PRECISION (full charge)" : "COMBO (95% charge, damage-safe spacing)"));
        }

        // Dedicated one-block manual mining works without choosing a PvP target.
        // Once started, keep progressing every tick until the block breaks.
        if (manualMining) {
            if (miningPos == null || !mining || !me.isAlive()
                || me.getEyePosition().distanceTo(Vec3.atCenterOf(miningPos)) > 4.3) {
                stopMining(mc);
            } else {
                BlockPos activeBlock = miningPos;
                mineBlock(mc, activeBlock, miningFace);
                return;
            }
        }

        if (!enabled || targetId == null || !me.isAlive() || me.isSpectator()) {
            stopActions(mc);
            resetAttackSchedule();
            return;
        }

        boolean bareHand = me.getMainHandItem().isEmpty();
        boolean swordEquipped = me.getMainHandItem().is(ItemTags.SWORDS) || me.getMainHandItem().is(ItemTags.AXES);
        int weaponMode = bareHand ? 1 : (swordEquipped ? 0 : 2);
        if (weaponMode != previousWeaponMode) {
            previousWeaponMode = weaponMode;
            resetAttackSchedule();
            lastAttackTick = -1000;
            lastAttackedTarget = null;
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
        updateTracking(target);
        if (bowEquipped) {
            stopActions(mc);
            resetAttackSchedule();
            aimBow(me, target);
            return;
        }

        if(LockOnCombat.eat(mc)) {
            stopMining(mc);
            retreatWhileEating(mc,me,target);
            lastAttackHold="eating for recovery";
            return;
        }
        boolean weaponReady=LockOnCombat.equipWeapon(mc,target.isBlocking());
        aimAt(me, target);
        if (chase && me.isInWater()) {
            stopMining(mc);
            setMovement(mc,true,false,false,false,me.getFoodData().getFoodLevel()>6);
            LockOnMovement.jump(mc,me.getAirSupply()<200 || target.getY()>=me.getY());
        } else if (chase && !me.isPassenger() && !me.isFallFlying() && !me.isInWater()) {
            updateStuckRecovery(me, target);
            chaseTarget(mc, me, target);
        } else {
            stopMining(mc);
            releaseMovement(mc);
        }
        if(weaponReady) {
            LockOnCombat.shield(mc,target,ticks-lastAttackTick);
            attack(mc, me, target);
        }
    }

    private static void retreatWhileEating(Minecraft mc,LocalPlayer me,Player target) {
        Vec3 away=me.position().subtract(target.position());
        double angle=Math.atan2(away.z,away.x);
        for(double offset:new double[]{0,0.65,-0.65,1.2,-1.2}) {
            double dx=Math.cos(angle+offset),dz=Math.sin(angle+offset);
            if(Navigator.safeForward(mc.level,me,dx,dz)) {
                LockOnTravel.face(mc,me.getEyePosition().add(dx,0,dz));
                setMovement(mc,true,false,false,false,false);
                LockOnMovement.jump(mc,me.isInWater());
                return;
            }
        }
        releaseMovement(mc);
    }

    private static void drainKeys() {
        while (toggleKey.consumeClick()) {}
        while (selectKey.consumeClick()) {}
        while (chaseKey.consumeClick()) {}
        while (critKey.consumeClick()) {}
        while (mineKey.consumeClick()) {}
        while (modeKey.consumeClick()) {}
        while (settingsKey.consumeClick()) {}
        while (statsKey.consumeClick()) {}
        while (manualMineKey.consumeClick()) {}
        while (survivalKey.consumeClick()) {}
    }

    private static void resetTracking() {
        trackedVelocityTarget = null;
        observedHurtTarget = null;
        filteredVelocity = new Vec3(0.0, 0.0, 0.0);
        lastObservedHurtTime = -1;
    }

    private static void updateTracking(Player target) {
        UUID id = target.getUUID();
        if (!id.equals(trackedVelocityTarget)) {
            trackedVelocityTarget = id;
            observedHurtTarget = id;
            filteredVelocity = target.getDeltaMovement();
            lastObservedHurtTime = target.hurtTime;
            streak = 0;
            return;
        }
        Vec3 speed = target.getDeltaMovement();
        // Low-pass filtering reduces jitter from packet interpolation.
        filteredVelocity = filteredVelocity.scale(0.60).add(speed.scale(0.40));

        if (id.equals(observedHurtTarget)) {
            // These are observations, NOT server-verified hit confirmations.
            // Another attacker or environmental damage can also cause them.
            if (target.hurtTime > lastObservedHurtTime
                && ticks - lastAttackTick <= 24 && lastAttackTick >= 0) {
                hurtSignals++;
                streak = ticks - lastHurtSignalTick <= 26 ? streak + 1 : 1;
                bestStreak = Math.max(bestStreak, streak);
                lastHurtSignalTick = ticks;
            }
            lastObservedHurtTime = target.hurtTime;
        }
    }

    private static void aimAt(LocalPlayer me, Player target) {
        Vec3 eye = me.getEyePosition();
        double distance = me.distanceTo(target);
        Vec3 vel = filteredVelocity;
        // For close-range strikes, avoid leading outside the enemy hitbox.
        // At longer distances, use a smoothed velocity sample.
        double prediction = CombatTactics.predictionTicks(distance, attackReach());
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
        Vec3 speed = filteredVelocity;
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

    private static boolean comboWindow(LocalPlayer me, Player target) {
        int elapsed = ticks - lastAttackTick;
        return chase && !precisionMode && lastAttackedTarget != null
            && lastAttackedTarget.equals(target.getUUID())
            && elapsed >= 0 && elapsed <= COMBO_WINDOW_TICKS
            // Don't require a remote hurt-animation event. Those are
            // observational signals and can be missed over the network.
            && me.distanceTo(target) <= 4.6;
    }

    private static void attack(Minecraft mc, LocalPlayer me, Player target) {
        // An empty main hand is a real vanilla melee weapon (fists).
        // Keep sword and fist support, without triggering automated attacks
        // for bows, pickaxes, food, or any other non-sword item.
        boolean bareHand = me.getMainHandItem().isEmpty();
        boolean swordEquipped = me.getMainHandItem().is(ItemTags.SWORDS) || me.getMainHandItem().is(ItemTags.AXES);
        if (mc.gameMode == null || me.isUsingItem()
            || (!swordEquipped && !bareHand) || miningPos != null) {
            resetAttackSchedule();
            lastAttackHold = miningPos != null ? "mining" : "not holding sword/fist";
            return;
        }
        // The 26.3 server needs >90% charge for sprint knockback; keep
        // combo follow-ups at 95%. Fully charged openers maximize damage.
        // Both sword and bare fist attacks must also respect the target's
        // roughly 10-tick damage immunity to avoid non-damaging follow-ups.
        boolean combo = comboWindow(me, target);
        float requiredCharge = combo ? COMBO_ATTACK_CHARGE : 0.999f;
        int elapsed = ticks - lastAttackTick;
        // Faster fist cooldown does not shorten the target's 10-tick
        // post-damage immunity. Enforce 11 ticks between attacks at a single
        // target for consistent damage and knockback rather than fake combos.
        int minimumTicks = CombatTactics.minimumAttackTicks(bareHand, combo);
        float measuredCharge = me.getAttackStrengthScale(0.0f);
        // An empty hand normally fully recharges in about 5 ticks.
        // After 11 ticks the victim immunity has also passed. Do not let
        // stale local cooldown animation prevent every follow-up punch.
        if (!CombatTactics.readyForAttack(
                bareHand, elapsed, measuredCharge, requiredCharge)) {
            cooldownWaits++;
            lastAttackHold = "cooldown " + (int) (measuredCharge * 100.0f) + "%";
            return;
        }
        if (bareHand && measuredCharge < requiredCharge) fistFallbacks++;

        // Preserve the randomized 20-50 ms delay after the appropriate
        // attack threshold, never scheduling it a second time because
        // the opponent momentarily escaped reach or camera aim.
        if (scheduledTarget != null && !scheduledTarget.equals(target.getUUID())) {
            resetAttackSchedule();
        }
        if (meleeReadyNanos < 0L) {
            scheduledTarget = target.getUUID();
            meleeReadyNanos = System.nanoTime()
                + ThreadLocalRandom.current().nextLong(20_000_000L, 50_000_001L);
        }
        if (System.nanoTime() < meleeReadyNanos) return;

        // The local hurtTime animation is NOT a trustworthy authoritative
        // server cooldown. It can remain stale or be restarted by other
        // damage sources. Use the 11-tick interval above, not a potentially
        // permanent animation-based attack lockout.

        // Keep the attack ready when just outside reach; hit immediately
        // on re-entry. Still enforce line of sight and actual hitbox aim.
        Vec3 eye = me.getEyePosition();
        // Real server hits require a valid reach to the actual hitbox, not
        // merely the target's center or an inflated visual bounding box.
        var box = target.getBoundingBox();
        double nx = Mth.clamp(eye.x, box.minX, box.maxX);
        double ny = Mth.clamp(eye.y, box.minY, box.maxY);
        double nz = Mth.clamp(eye.z, box.minZ, box.maxZ);
        double ex = eye.x - nx, ey = eye.y - ny, ez = eye.z - nz;
        double safeReach = Math.max(0.0, attackReach() - 0.08);
        if (ex * ex + ey * ey + ez * ez > safeReach * safeReach) {
            outOfRangeSkips++;
            lastAttackHold = "out of reach";
            return;
        }
        Vec3 ray = eye.add(me.getViewVector(1.0f).scale(safeReach));
        // At point-blank range the camera can be INSIDE the enemy's
        // bounding box. Standard clip() can then return empty even though
        // the target is directly in front of the player.
        if (!me.hasLineOfSight(target)
            || (!box.contains(eye) && box.clip(eye, ray).isEmpty())) {
            aimRayMisses++;
            lastAttackHold = "ray / line of sight";
            return;
        }

        boolean falling = !me.onGround() && me.fallDistance > 0.0f
            && me.getDeltaMovement().y < -0.01
            && !me.isInWater() && !me.onClimbable() && !me.isPassenger();
        boolean airborneOpponent = !target.onGround() || target.getDeltaMovement().y > 0.08;

        // Prioritize immediate follow-up hits in a combo. A jump crit is
        // worthwhile only if the opponent is grounded and nearly stationary.
        boolean standingInCritRange = me.distanceTo(target) < 2.20
            && Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z) < 0.16;
        // Wait for a recent observed server hurt animation before initiating
        // optional crit jumps. The 26.3 client can show false crit particles.
        if (criticals && ticks - lastHurtSignalTick < 100
            && !combo && !airborneOpponent
            && !me.isSprinting() && me.onGround() && mc.level != null
            && standingInCritRange && ticks >= nextCritJumpAt
            && Navigator.safeToJump(mc.level, me)) {
            me.setSprinting(false);
            releaseSprint(mc);
            me.jumpFromGround();
            lastCritJumpTick = ticks;
            nextCritJumpAt = ticks + 22;
            return;
        }

        // Never delay a follow-up sprint hit while waiting for a crit jump.
        // Falling crits and sprint knockback are separate attack types.
        if (!combo && criticals && !falling
            && ticks - lastCritJumpTick < 6 && !airborneOpponent) return;
        if (!combo && falling && criticals) {
            me.setSprinting(false);
            releaseSprint(mc);
        }

        // Minecraft 26.3 uses TWO serverbound packets for an ordinary hit:
        // 1) gameMode.attack sends ServerboundAttackPacket (entity id)
        // 2) vanilla Minecraft.startAttack also sends ServerboundPunchPacket
        //    after the client-side swing animation. LivingEntity.swing(...)
        //    no longer sends this packet! Omitting it breaks the vanilla
        //    server-side swing / attack-strength timing sequence.
        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND, me.getMainHandItem().getAttackAnimation(), false);
        me.connection.send(ServerboundPunchPacket.INSTANCE);
        attempts++;
        lastAttackHold = "attack + punch packets sent";
        lastAttackTick = ticks;
        lastAttackedTarget = target.getUUID();
        resetAttackSchedule();

        // Reset sprint on NEXT movement tick, not immediately after sending
        // the hit. Keep forward input active so knockback isn't followed by
        // a full stop; sprint is reacquired after a short off interval.
        sprintResetTicks = chase ? 1 : 0;
    }

    private static void updateStuckRecovery(LocalPlayer me, Player target) {
        if (ticks - lastMovementSampleTick < 12) return;
        if (lastMovementSample != null && ownsMovement && miningPos == null
            && me.onGround() && me.distanceTo(target) > 3.3) {
            double moved = Math.hypot(me.getX() - lastMovementSample.x,
                me.getZ() - lastMovementSample.z);
            if (moved < 0.14) {
                recoveryUntilTick = ticks + 8;
                recoverySide = -recoverySide;
                nextStep = null;
                nextPlanAt = 0;
            }
        }
        lastMovementSample = me.position();
        lastMovementSampleTick = ticks;
    }

    private static void chaseTarget(Minecraft mc, LocalPlayer me, Player target) {
        double d = horizontalDistance(me.getX(), me.getZ(), target.getX(), target.getZ());
        // Do not switch to close-range strafing while the target is above a
        // step or a solid 1-block obstacle still separates the two players.
        double dxToTarget = target.getX() - me.getX();
        double dzToTarget = target.getZ() - me.getZ();
        boolean oneBlockAhead = Navigator.oneBlockAhead(mc.level, me, dxToTarget, dzToTarget);
        boolean sameHeight = Math.abs(target.getY() - me.getY()) < 0.58;

        // B is an explicit opt-in to break safe blocks in our way. The old
        // A* planner almost always chose walking/jumping and never actually
        // invoked mining; now a direct obstruction is mined before routing.
        if (mining && !manualMining && d < 7.5) {
            BlockPos obstacle = miningPos;
            if (obstacle == null || mc.level.getBlockState(obstacle).isAir()) {
                if (obstacle != null) stopMining(mc);
                obstacle = Navigator.directMiningObstacle(mc.level, me, target);
            }
            if (obstacle != null && Navigator.mineable(mc.level, me, obstacle)
                && me.getEyePosition().distanceTo(Vec3.atCenterOf(obstacle)) < 4.2) {
                releaseMovement(mc);
                mineBlock(mc, obstacle, faceTowardPlayer(me, obstacle));
                return;
            } else if (miningPos != null && !manualMining) {
                stopMining(mc);
            }
        }
        if (ticks < recoveryUntilTick && !oneBlockAhead && d > 3.0) {
            boolean left = recoverySide < 0;
            double yaw = Math.toRadians(me.getYRot());
            boolean safeSide = safeSide(mc.level, me,
                left ? -Math.cos(yaw) : Math.cos(yaw),
                left ? -Math.sin(yaw) : Math.sin(yaw));
            boolean safeForward = Navigator.safeForward(mc.level, me, dxToTarget, dzToTarget);
            setMovement(mc, safeForward, false, safeSide && left,
                safeSide && !left, safeForward);
            return;
        }
        if (d < Math.max(3.05, attackReach() - 0.12) && sameHeight && !oneBlockAhead && me.hasLineOfSight(target)) {
            stopMining(mc);
            nextStep = null;
            // Adaptive radial tracking. Follow knockback without orbiting
            // sideways mid-combo; protect the forward path from cliff edges.
            boolean preferLeft = ((ticks / 24) & 1) == 0;
            double yaw = Math.toRadians(me.getYRot());
            boolean leftSafe = safeSide(mc.level, me, -Math.cos(yaw), -Math.sin(yaw));
            boolean rightSafe = safeSide(mc.level, me, Math.cos(yaw), Math.sin(yaw));
            boolean airborneOpponent = !target.onGround()
                || target.getDeltaMovement().y > 0.08;
            boolean combo = comboWindow(me, target) || LockOnCombat.opponentEating(target);
            double radialSpeed = 0.0;
            if (d > 0.01) {
                Vec3 relative = filteredVelocity.subtract(me.getDeltaMovement());
                radialSpeed = (relative.x * dxToTarget + relative.z * dzToTarget) / d;
            }
            boolean safeForward = Navigator.safeForward(
                mc.level, me, dxToTarget, dzToTarget);
            boolean inCritWindow = ticks - lastCritJumpTick <= 8 && !me.onGround();
            CombatTactics.Move plan = CombatTactics.move(
                d, attackReach(), radialSpeed, combo, airborneOpponent,
                safeForward, leftSafe, rightSafe, preferLeft,
                me.onGround(), sprintResetTicks > 0, inCritWindow);
            setMovement(mc, plan.forward(), plan.backward(),
                plan.left(), plan.right(), plan.sprint());
            if (sprintResetTicks > 0) {
                me.setSprinting(false);
                sprintResetTicks--;
            } else if (plan.sprint() && !me.isSprinting()) {
                me.setSprinting(true);
            }
            return;
        }

        // Immediately pursue knockback across safe, visible terrain.
        // Earlier versions handed control to A* as soon as d exceeded 3.05,
        // allowing the victim to escape beyond actual attack reach.
        if (d < 8.0 && sameHeight && !oneBlockAhead
            && me.hasLineOfSight(target)
            && Navigator.safeForward(mc.level, me, dxToTarget, dzToTarget)) {
            stopMining(mc);
            nextStep = null;
            boolean sprintNow = sprintResetTicks == 0 && me.onGround();
            setMovement(mc, true, false, false, false, sprintNow);
            if (sprintResetTicks > 0) {
                me.setSprinting(false);
                sprintResetTicks--;
            } else if (sprintNow && !me.isSprinting()) {
                me.setSprinting(true);
            }
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
            // If bounded A* cannot find a waypoint, do not freeze on flat
            // terrain. Fall back to safe direct pursuit when supported.
            if (d > Math.max(3.05, attackReach() - 0.12) && Navigator.safeForward(mc.level, me, dxToTarget, dzToTarget)) {
                setMovement(mc, true, false, false, false, sprintResetTicks == 0);
            } else {
                releaseMovement(mc);
            }
            if (sprintResetTicks > 0) {
                me.setSprinting(false);
                sprintResetTicks--;
            }
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
        boolean moveForward = forward > 0.25;
        boolean sprintNow = forward > 0.60 && sprintResetTicks == 0;
        setMovement(mc, moveForward, forward < -0.35,
            strafe < -0.35, strafe > 0.35, sprintNow);
        if (sprintResetTicks > 0) {
            me.setSprinting(false);
            sprintResetTicks--;
        } else if (sprintNow && me.onGround() && !me.isSprinting()) {
            me.setSprinting(true);
        }
    }

    private static boolean safeSide(ClientLevel level, LocalPlayer me, double dx, double dz) {
        BlockPos dest = BlockPos.containing(me.getX() + dx * 0.7, me.getY() + 0.08,
            me.getZ() + dz * 0.7);
        return Navigator.passable(level, dest) && Navigator.passable(level, dest.above())
            && !level.getBlockState(dest.below()).getCollisionShape(level, dest.below()).isEmpty();
    }

    private static Direction faceTowardPlayer(LocalPlayer me, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = me.getX() - center.x;
        double dy = me.getEyeY() - center.y;
        double dz = me.getZ() - center.z;
        if (Math.abs(dy) > Math.max(Math.abs(dx), Math.abs(dz))) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void mineBlock(Minecraft mc, BlockPos block) {
        mineBlock(mc, block, faceTowardPlayer(mc.player, block));
    }

    private static void mineBlock(Minecraft mc, BlockPos block, Direction face) {
        if (mc.gameMode == null || mc.level == null || mc.player == null) return;
        if (mc.level.getBlockState(block).isAir()) {
            if (miningPos != null && miningPos.equals(block)) {
                minedBlocks++;
                stopMining(mc);
                nextPlanAt = 0;
            }
            return;
        }
        if (!Navigator.mineable(mc.level, mc.player, block)
            || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(block)) > 4.3) {
            stopMining(mc);
            return;
        }
        if (miningPos == null || !miningPos.equals(block)) {
            stopMining(mc);
            miningPos = block;
            miningFace = face;
            mc.gameMode.startDestroyBlock(block, miningFace);
        } else {
            // Important: startDestroyBlock only starts a dig. Vanilla
            // Survival needs a CONTINUE packet each tick until completion.
            mc.gameMode.continueDestroyBlock(block, miningFace);
            mc.player.swing(InteractionHand.MAIN_HAND,
                mc.player.getMainHandItem().getAttackAnimation(), false);
        }
        if (mc.level.getBlockState(block).isAir()) {
            minedBlocks++;
            stopMining(mc);
            nextPlanAt = 0;
        }
    }

    private static void stopMining(Minecraft mc) {
        manualMining = false;
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
        LockOnMovement.combat(mc,f,b,l,r,sprint,ticks<=navigationJumpUntil);
        ownsMovement=true;
    }

    private static void releaseSprint(Minecraft mc) {
        if(ownsMovement) LockOnMovement.sprint(false);
    }

    private static void releaseMovement(Minecraft mc) {
        if(!ownsMovement) return;
        LockOnMovement.stop(mc);
        navigationJumpUntil=-1;
        ownsMovement=false;
    }

    static boolean combatEnabled() { return enabled && targetId!=null; }

    static void manualTakeover(Minecraft mc) {
        enabled=false;
        stopActions(mc);
        hint(mc,"Manual movement: combat stopped. R enables it again.");
    }

    private static void stopActions(Minecraft mc) {
        stopMining(mc);
        releaseMovement(mc);
        LockOnCombat.stopUse(mc);
    }

    private static void clearNavigation(Minecraft mc) {
        stopActions(mc);
        nextStep = null;
        nextPlanAt = 0;
        sprintResetTicks = 0;
        lastAttackTick = -1000;
        lastAttackedTarget = null;
        previousWeaponMode = -1;
        lastMovementSample = null;
        lastMovementSampleTick = 0;
        recoveryUntilTick = 0;
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
            resetTracking();
            lastAttackTick = -1000;
            lastAttackedTarget = null;
            resetAttackSchedule();
            nextPlanAt = 0;
            hint(mc, "Target: " + closest.getName().getString());
        }
    }

    private static void hint(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("[LockOn] " + text));
    }
}
