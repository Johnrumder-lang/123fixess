package lockon;

import java.util.Comparator;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * Explicitly enabled, bounded survival strip miner. Operates on ordinary
 * client gameplay actions: hotbar selection, movement, mining, eating, melee.
 * Does not request server chunks, teleport, or assume creative permissions.
 */
public final class LockOnSurvival {
    private enum Phase { OFF, TUNNEL, RETURN, BASE, PAUSED }
    private static Phase phase = Phase.OFF;
    private static BlockPos base;
    private static ClientLevel world;
    private static Direction tunnelDirection = Direction.NORTH;
    private static int steps;
    private static int ticks;
    private static int startTick;
    private static int lastMobHit = -1000;
    private static BlockPos miningBlock;
    private static Direction miningFace = Direction.UP;
    private static int miningStarted;
    private static int blocksMined;
    private static int mobsAttacked;
    private static int foodUsed;
    private static boolean eating;
    private static int foodSlot = -1;
    private static String reason = "";
    private static final int MAX_MINING_TICKS = 240;

    private LockOnSurvival() {}

    public static boolean enabled() { return phase != Phase.OFF; }
    public static boolean managingContainer() {
        return phase == Phase.BASE && LockOnBase.managingContainer();
    }

    public static String status() {
        return phase + " " + steps + "/" + LockOnSettings.stripLength()
            + " blocks=" + blocksMined + ", mobs=" + mobsAttacked
            + ", food=" + foodUsed + (reason.isEmpty() ? "" : " (" + reason + ")");
    }

    public static void toggle(Minecraft mc) {
        if (enabled()) {
            stop(mc, "Manually stopped");
            return;
        }
        LocalPlayer me = mc.player;
        if (mc.level == null || me == null || !me.isAlive() || me.isSpectator()) return;
        world = mc.level;
        base = BlockPos.containing(me.getX(), me.getY() + 0.08, me.getZ());
        tunnelDirection = Direction.fromYRot(me.getYRot());
        if (tunnelDirection.getAxis().isVertical()) tunnelDirection = Direction.NORTH;
        steps = 0;
        blocksMined = mobsAttacked = foodUsed = 0;
        ticks = 0;
        startTick = 0;
        miningBlock = null;
        phase = Phase.TUNNEL;
        reason = "";
        LockOnBase.reset();
        notice(me, "Survival mining ON. Base marked at " + base.toShortString()
            + ". Strip direction " + tunnelDirection + ". P stops anytime.");
    }

    public static void stop(Minecraft mc, String message) {
        cancelDig(mc);
        release(mc);
        LockOnBase.stop(mc);
        phase = Phase.OFF;
        eating = false;
        foodSlot = -1;
        if (mc.player != null && message != null && !message.isBlank()) notice(mc.player,message);
    }

    public static void tick(Minecraft mc) {
        if (!enabled()) return;
        ticks++;
        LocalPlayer me = mc.player;
        if (mc.level == null || me == null || mc.level != world
            || !me.isAlive() || me.isSpectator()) {
            stop(mc, "Stopped: changed world, died, or no player");
            return;
        }
        // The only screen this bot is allowed to automate is its OWN chest,
        // furnace or crafting-table session. Never interact with other GUIs.
        if (mc.gui.screen() != null && !managingContainer()) {
            release(mc);
            return;
        }
        if (phase == Phase.BASE) {
            release(mc);
            if (LockOnBase.tick(mc, base, tunnelDirection)) {
                stop(mc, "Strip finished and base processing complete. " + status());
            }
            return;
        }

        if (me.getAirSupply() < 75) {
            cancelDig(mc);
            release(mc);
            mc.options.keyJump.setDown(true);
            reason = "low air: swimming upward / paused";
            return;
        }
        if (me.isOnFire() || me.isInLava() || me.isInWall()
            || me.fallDistance > 2.0f) {
            pause(mc, "DANGER: fire/lava/suffocation/fall risk; disabled");
            return;
        }

        Monster hostile = findHostile(mc, me);
        boolean enemyNear = hostile != null && hostile.distanceToSqr(me) < 36.0;
        int free = freeSlots(me);
        SurvivalPolicy.Decision decision = SurvivalPolicy.decide(
            me.getHealth(), me.getFoodData().getFoodLevel(),
            me.isOnFire(), me.isInLava(), me.isInWall(), enemyNear,
            free, steps, LockOnSettings.stripLength());

        if (decision == SurvivalPolicy.Decision.STOP) {
            pause(mc, "Survival danger: paused");
            return;
        }
        if (decision == SurvivalPolicy.Decision.RETREAT) {
            cancelDig(mc);
            release(mc);
            reason = "retreat: health " + (int) me.getHealth();
            if (phase == Phase.TUNNEL) phase = Phase.RETURN;
            if (tryEat(mc, me)) return;
            returnToBase(mc,me);
            return;
        }
        if (decision == SurvivalPolicy.Decision.EAT && LockOnSettings.survivalAutoEat()) {
            cancelDig(mc);
            release(mc);
            if (tryEat(mc, me)) return;
            // Do not start a long tunnel while starving and unable to eat.
            pause(mc, "No safe food in hotbar (or low hunger)");
            return;
        }
        if (eating) stopEating(mc);
        if (phase == Phase.PAUSED) { release(mc); return; }

        if (enemyNear && LockOnSettings.survivalFightMobs()) {
            cancelDig(mc);
            fightHostile(mc, me, hostile);
            return;
        }

        if (phase == Phase.RETURN) {
            returnToBase(mc, me);
            return;
        }

        if (decision == SurvivalPolicy.Decision.RETURN_BASE) {
            cancelDig(mc);
            release(mc);
            phase = Phase.RETURN;
            reason = free <= 3 ? "inventory almost full" : "strip length reached";
            return;
        }

        if (phase == Phase.TUNNEL) {
            mineTunnel(mc, me);
        }
    }

    private static void mineTunnel(Minecraft mc, LocalPlayer me) {
        if (base == null) { pause(mc,"No home position"); return; }
        if (ticks - startTick < 3) return;
        // Look for configured ore IDs in nearby, actually loaded stone.
        BlockPos ore = ticks % 8 == 0 ? findReachableOre(mc, me) : null;
        if (miningBlock != null) {
            if (mc.level.getBlockState(miningBlock).isAir()) {
                finishDig(mc);
            } else {
                dig(mc, me, miningBlock);
                return;
            }
        }
        if (ore != null && canMine(mc, me, ore)) {
            release(mc);
            dig(mc, me, ore);
            return;
        }

        BlockPos next = base.relative(tunnelDirection, steps + 1);
        BlockPos support = next.below();
        if (!mc.level.hasChunk(next.getX() >> 4, next.getZ() >> 4)) {
            pause(mc, "Unloaded chunk ahead; stop"); return;
        }
        BlockState ground = mc.level.getBlockState(support);
        if (ground.isAir() || !ground.getFluidState().isEmpty()
            || mc.level.getBlockState(next).getFluidState().isEmpty() == false
            || mc.level.getBlockState(next.above()).getFluidState().isEmpty() == false) {
            pause(mc, "Dangerous ground / liquid ahead; stop"); return;
        }
        if (hazardAround(mc.level, next)) {
            pause(mc, "Lava/fire/void detected near next tunnel block"); return;
        }

        for (BlockPos pos : new BlockPos[] {next, next.above()}) {
            if (!Navigator.passable(mc.level, pos)) {
                if (!canMine(mc, me, pos)) {
                    pause(mc,"Obstruction cannot be mined safely: " + pos.toShortString());
                    return;
                }
                release(mc);
                dig(mc, me, pos);
                return;
            }
        }

        double dx = next.getX() + 0.5 - me.getX();
        double dz = next.getZ() + 0.5 - me.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= 0.38) {
            steps++;
            release(mc);
            return;
        }
        if (horizontal > 3.6) {
            pause(mc, "Player left original tunnel / desynchronized"); return;
        }
        if (!Navigator.safeForward(mc.level, me, dx, dz)) {
            pause(mc, "Unsafe movement ahead"); return;
        }
        face(me, Vec3.atCenterOf(next).add(0,0.4,0));
        moveForward(mc, true);
    }

    private static boolean canMine(Minecraft mc, LocalPlayer me, BlockPos pos) {
        if (mc.level == null) return false;
        if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir() || s.hasBlockEntity() || mc.level.getBlockEntity(pos) != null
            || !s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK)
            || s.is(Blocks.LAVA) || s.is(Blocks.WATER)
            || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)
            || s.is(Blocks.POWDER_SNOW)) return false;
        float hardness = s.getDestroySpeed(mc.level, pos);
        if (hardness < 0.0f || hardness > 12.0f) return false;
        if (me.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.25) return false;
        // Reject vanilla ore harvesting with inappropriate tools, rather
        // than mining diamond ore with a fist and losing all the drops.
        return equipToolFor(mc, me, s);
    }

    private static boolean equipToolFor(Minecraft mc, LocalPlayer me, BlockState state) {
        int selected = me.getInventory().getSelectedSlot();
        int best = -1;
        float bestSpeed = -1;
        for (int i=0; i<9; i++) {
            ItemStack stack = me.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            // Use an actual pickaxe for ores and stone; skip useless hand mining.
            if (!stack.is(ItemTags.PICKAXES)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) { best=i; bestSpeed=speed; }
        }
        if (best >= 0) {
            if (selected != best) me.getInventory().setSelectedSlot(best);
            return true;
        }
        return false;
    }

    private static BlockPos findReachableOre(Minecraft mc, LocalPlayer me) {
        Set<String> ids = LockOnSettings.customBlockIds();
        if (ids.isEmpty()) return null;
        BlockPos at = BlockPos.containing(me.getX(), me.getY(), me.getZ());
        BlockPos best=null;
        double bestSq = 4.1 * 4.1;
        for(int dx=-3;dx<=3;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-3;dz<=3;dz++){
            BlockPos p = at.offset(dx,dy,dz);
            if (p.equals(at.below()) || (base!=null && p.distSqr(base) < 3.0)) continue;
            if (!mc.level.hasChunk(p.getX()>>4,p.getZ()>>4))continue;
            BlockState s = mc.level.getBlockState(p);
            String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if(!ids.contains(id)) continue;
            double d2=me.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
            if(d2>=bestSq)continue;
            BlockHitResult hit=mc.level.clip(new ClipContext(me.getEyePosition(),
                Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,me));
            if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(p))continue;
            best=p;bestSq=d2;
        }
        return best;
    }

    private static void dig(Minecraft mc, LocalPlayer me, BlockPos p) {
        if (miningBlock != null && !miningBlock.equals(p)) cancelDig(mc);
        if (miningBlock == null) {
            miningBlock = p;
            miningStarted = ticks;
            miningFace = facingBlock(me, p);
            mc.gameMode.startDestroyBlock(p, miningFace);
        } else {
            mc.gameMode.continueDestroyBlock(p, miningFace);
            if (ticks % 4 == 0) {
                me.swing(InteractionHand.MAIN_HAND,
                    me.getMainHandItem().getAttackAnimation(), false);
            }
        }
        if (mc.level.getBlockState(p).isAir()) {
            finishDig(mc);
        } else if (ticks-miningStarted > MAX_MINING_TICKS) {
            pause(mc,"Could not break block (server protection or wrong tool)");
        }
    }

    private static Direction facingBlock(LocalPlayer me, BlockPos p) {
        Vec3 d=me.getEyePosition().subtract(Vec3.atCenterOf(p));
        if (Math.abs(d.y)>Math.abs(d.x) && Math.abs(d.y)>Math.abs(d.z))
            return d.y>0?Direction.UP:Direction.DOWN;
        if (Math.abs(d.x)>Math.abs(d.z)) return d.x>0?Direction.EAST:Direction.WEST;
        return d.z>0?Direction.SOUTH:Direction.NORTH;
    }

    private static void finishDig(Minecraft mc) {
        miningBlock = null;
        blocksMined++;
    }

    private static void cancelDig(Minecraft mc) {
        if (miningBlock != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        miningBlock = null;
    }

    private static boolean hazardAround(ClientLevel world, BlockPos point) {
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=2;dy++)for(int dz=-1;dz<=1;dz++){
            BlockState state=world.getBlockState(point.offset(dx,dy,dz));
            if(state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW))return true;
        }
        return false;
    }

    private static int freeSlots(LocalPlayer me) {
        int free=0;
        for(int i=0;i<36;i++)if(me.getInventory().getItem(i).isEmpty())free++;
        return free;
    }

    private static Monster findHostile(Minecraft mc, LocalPlayer me) {
        Monster best=null;
        double bestSq=48.0;
        for(Entity e:mc.level.entitiesForRendering()){
            if (!(e instanceof Monster m) || !m.isAlive()) continue;
            double dist=e.distanceToSqr(me);
            if(dist<bestSq){best=m;bestSq=dist;}
        }
        return best;
    }

    private static void fightHostile(Minecraft mc, LocalPlayer me, Monster enemy) {
        if (enemy == null) return;
        if (enemy instanceof Creeper && enemy.distanceToSqr(me)<15.0) {
            release(mc);
            // Avoid pursuing a live creeper into its explosion radius.
            reason = "creeper nearby: retreat";
            phase = Phase.RETURN;
            return;
        }
        face(me, enemy.position().add(0,enemy.getBbHeight()*0.65,0));
        double dist=me.distanceTo(enemy);
        release(mc);
        if(dist>2.6){
            if(dist<5.0 && me.getHealth()>13.0
                && Navigator.safeForward(mc.level, me,
                    enemy.getX()-me.getX(),enemy.getZ()-me.getZ()))
                moveForward(mc,true);
            return;
        }
        if(ticks-lastMobHit<12 || me.getAttackStrengthScale(0.0f)<0.95f) return;
        if(!me.hasLineOfSight(enemy))return;
        // Use a sword if available, otherwise the equipped item. This is
        // hostile-only defense and never deliberately attacks players/animals.
        int sword=-1;
        for(int i=0;i<9;i++)
            if(me.getInventory().getItem(i).is(ItemTags.SWORDS)){sword=i;break;}
        if(sword>=0)me.getInventory().setSelectedSlot(sword);
        mc.gameMode.attack(me,enemy);
        me.swing(InteractionHand.MAIN_HAND,me.getMainHandItem().getAttackAnimation(),false);
        me.connection.send(ServerboundPunchPacket.INSTANCE);
        mobsAttacked++;
        lastMobHit=ticks;
    }

    private static boolean tryEat(Minecraft mc, LocalPlayer me) {
        if(!LockOnSettings.survivalAutoEat()) return false;
        if (me.getFoodData().getFoodLevel()>=19 && me.getHealth()>=15.0) {
            if(eating)stopEating(mc);
            return false;
        }
        if(eating && me.isUsingItem()){
            mc.options.keyUse.setDown(true);
            return true;
        }
        if(eating && !me.isUsingItem() && foodSlot>=0 && ticks%3!=0)
            return true;
        foodSlot=-1;
        for(int i=0;i<9;i++){
            ItemStack food=me.getInventory().getItem(i);
            if(food.isEmpty())continue;
            if(food.is(Items.BREAD) || food.is(Items.BAKED_POTATO)
                || food.is(Items.COOKED_BEEF) || food.is(Items.COOKED_CHICKEN)
                || food.is(Items.COOKED_PORKCHOP) || food.is(Items.COOKED_MUTTON)
                || food.is(Items.GOLDEN_CARROT) || food.is(Items.APPLE)
                || food.is(Items.COOKED_SALMON) || food.is(Items.COOKED_COD)
                || food.is(Items.GOLDEN_APPLE)) {foodSlot=i;break;}
        }
        if(foodSlot<0)return false;
        me.getInventory().setSelectedSlot(foodSlot);
        mc.gameMode.useItem(me,InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        eating=true;
        foodUsed++;
        return true;
    }

    private static void stopEating(Minecraft mc) {
        eating=false;
        foodSlot=-1;
        mc.options.keyUse.setDown(false);
    }

    private static void returnToBase(Minecraft mc,LocalPlayer me) {
        if (base == null) { pause(mc,"No base recorded");return; }
        double dx=base.getX()+0.5-me.getX();
        double dz=base.getZ()+0.5-me.getZ();
        double d=Math.hypot(dx,dz);
        if(d<1.4){
            release(mc);
            phase=Phase.BASE;
            LockOnBase.reset();
            reason="base processing";
            return;
        }
        if(!Navigator.safeForward(mc.level, me,dx,dz)){
            pause(mc,"Return path blocked or unsafe");return;
        }
        face(me,new Vec3(base.getX()+0.5,me.getEyeY(),base.getZ()+0.5));
        moveForward(mc,false);
    }

    private static void face(LocalPlayer me,Vec3 target) {
        Vec3 d=target.subtract(me.getEyePosition());
        me.setYRot((float)Math.toDegrees(Math.atan2(d.z,d.x))-90.0f);
        me.setXRot(Mth.clamp((float)-Math.toDegrees(
            Math.atan2(d.y,Math.hypot(d.x,d.z))),-90.0f,90.0f));
    }

    private static void moveForward(Minecraft mc,boolean sprint) {
        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(false);
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private static void pause(Minecraft mc,String why) {
        cancelDig(mc);
        release(mc);
        reason=why;
        phase=Phase.PAUSED;
        if(mc.player!=null)notice(mc.player,why+" (P to stop)");
    }

    private static void notice(LocalPlayer player,String message) {
        player.sendOverlayMessage(Component.literal("[LockOn Survival] "+message));
    }
}
