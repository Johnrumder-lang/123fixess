package lockon;

import java.util.Comparator;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
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
    private enum Phase { OFF, BOOTSTRAP, TUNNEL, RETURN, BASE, PAUSED }
    private static Phase phase = Phase.OFF;
    private static BlockPos base;
    private static ClientLevel world;
    private static Direction tunnelDirection = Direction.NORTH;
    private static Direction baseFacing = Direction.NORTH;
    private static int cycles;
    private static int steps;
    private static int ticks;
    private static int startTick;
    private static int lastMobHit = -1000;
    private static BlockPos miningBlock;
    private static BlockPos aimedBlock;
    private static int aimedSince;
    private static int lastDistanceTick;
    private static int lastToolSwapTick=-1000;
    private static double lastDistance;
    private static BlockPos lastWaypoint;
    private static Direction miningFace = Direction.UP;
    private static int miningStarted;
    private static int blocksMined;
    private static int mobsAttacked;
    private static int foodUsed;
    private static boolean eating;
    private static int foodSlot = -1;
    private static String reason = "";
    private static final int MAX_MINING_TICKS = 240;
    private static final Set<String> SURVIVAL_ORES = Set.of(
        "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
        "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
        "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
        "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
        "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore");

    private LockOnSurvival() {}

    public static boolean enabled() { return phase != Phase.OFF; }
    public static boolean managingContainer() {
        return (phase == Phase.BASE && LockOnBase.managingContainer())
            || (phase == Phase.BOOTSTRAP && LockOnBootstrap.managingContainer());
    }

    public static String status() {
        return phase + " cycle=" + cycles + " " + steps + "/" + LockOnSettings.stripLength()
            + " blocks=" + blocksMined + ", mobs=" + mobsAttacked
            + ", food=" + foodUsed
            + (phase == Phase.BOOTSTRAP ? " / " + LockOnBootstrap.status() : "")
            + (reason.isEmpty() ? "" : " (" + reason + ")");
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
        baseFacing = tunnelDirection;
        steps = 0;
        cycles = 0;
        blocksMined = mobsAttacked = foodUsed = 0;
        ticks = 0;
        startTick = 0;
        miningBlock = null;
        aimedBlock = null;
        lastDistanceTick = 0;
        lastDistance = -1;
        lastWaypoint = null;
        phase = Phase.BOOTSTRAP;
        reason = "";
        LockOnBase.reset();
        LockOnBootstrap.reset(mc,base,baseFacing);
        notice(me, "Autoplay ON at " + base.toShortString()
            + ": gathering wood and crafting first. P stops anytime.");
    }

    public static void stop(Minecraft mc, String message) {
        cancelDig(mc);
        release(mc);
        LockOnBase.stop(mc);
        LockOnBootstrap.stop(mc);
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
        // Regular heartbeat: proves P/F7 actually started the bot and that
        // the gameplay tick is running. Do not claim work finished when no
        // action has succeeded; the displayed phase and current reason
        // are live, not just the planned task.
        if (ticks % 20 == 0) {
            String operation = phase == Phase.BOOTSTRAP
                ? LockOnBootstrap.status()
                : reason;
            me.sendOverlayMessage(Component.literal(
                "[LockOn F7 ACTIVE #" + ticks + "] " + phase + ": " + operation));
        }
        // The only screen this bot is allowed to automate is its OWN chest,
        // furnace or crafting-table session. Never interact with other GUIs.
        if (mc.gui.screen() != null && !managingContainer()) {
            release(mc);
            return;
        }
        if (phase == Phase.BASE) {
            release(mc);
            if (LockOnBase.tick(mc, base, baseFacing)) {
                if (LockOnSettings.survivalAutoRepeat()) {
                    cycles++;
                    tunnelDirection = tunnelDirection.getClockWise();
                    steps = 0;
                    startTick = ticks;
                    if (!hasWorkingPickaxe(me)) {
                        phase = Phase.BOOTSTRAP;
                        LockOnBootstrap.reset(mc, base, baseFacing);
                        reason = "No pickaxe: returning to wood/sticks/tool crafting";
                        notice(me, "No usable pickaxe. Crafting a new one BEFORE mining.");
                    } else {
                        reason = "Tool checked; starting mining cycle " + (cycles + 1);
                        phase = Phase.TUNNEL;
                        notice(me, "Base processed; starting next tunnel with pickaxe.");
                    }
                } else {
                    stop(mc,"Mining and base processing complete. "+status());
                }
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

        if (phase == Phase.BOOTSTRAP) {
            if (LockOnBootstrap.tick(mc)) {
                LockOnBootstrap.stop(mc);
                steps = 0;
                startTick = ticks;
                phase = Phase.TUNNEL;
                reason = "Tools crafted: strip mining";
                notice(me,"Wood and tools ready. Starting first mining tunnel.");
            }
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
            if (!hasWorkingPickaxe(me)) {
                cancelDig(mc);
                release(mc);
                phase = steps > 0 ? Phase.RETURN : Phase.BOOTSTRAP;
                reason = "No usable pickaxe; " + (steps > 0
                    ? "returning to base for tools" : "crafting tools before mining");
                if (phase == Phase.BOOTSTRAP) LockOnBootstrap.reset(mc, base, baseFacing);
                notice(me,reason);
                return;
            }
            mineTunnel(mc, me);
        }
    }

    private static void mineTunnel(Minecraft mc, LocalPlayer me) {
        if (base == null) { pause(mc,"No home position"); return; }
        if (ticks - startTick < 3) return;
        // A target being aimed at must not be dropped between scan ticks.
        if (aimedBlock != null && miningBlock == null) {
            release(mc);
            dig(mc,me,aimedBlock);
            return;
        }
        // Detect common survival ores (especially coal and iron) even if
        // the Tracers custom-block field still contains only diamond_ore.
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
        // Swapping a tool from the inventory needs a tick to synchronize.
        // It is not a mining failure and must not trigger the stuck pause.
        if (lastToolSwapTick == ticks) { release(mc); return; }

        BlockPos next = tunnelPoint(steps + 1);
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
                    if (lastToolSwapTick == ticks) {
                        release(mc);
                        reason="Moving pickaxe from inventory into hotbar";
                        return;
                    }
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
        if (horizontal <= 0.52 && Math.abs(me.getY() - next.getY()) < 0.75) {
            steps++;
            lastWaypoint=null;
            release(mc);
            return;
        }
        if (horizontal > 3.6) {
            pause(mc, "Player left original tunnel / desynchronized"); return;
        }
        if(!next.equals(lastWaypoint)) {
            lastWaypoint=next;
            lastDistance=horizontal;
            lastDistanceTick=ticks;
        } else if(horizontal+0.12 < lastDistance) {
            lastDistance=horizontal;
            lastDistanceTick=ticks;
        } else if(ticks-lastDistanceTick > 100) {
            pause(mc, "Movement stuck: no progress toward "+next.toShortString());
            return;
        }
        // At each fourth block descend exactly one block on a supported
        // staircase. Horizontal-only safeForward() would falsely report
        // a gap at this intentional, bounded one-block drop.
        boolean descending = next.getY() < me.getY() - 0.35;
        if (descending && (me.getY() - next.getY() > 1.45
            || mc.level.getBlockState(next.below()).getCollisionShape(
                mc.level,next.below()).isEmpty())) {
            pause(mc,"Unsafe downhill step");return;
        }
        if (!descending && !Navigator.safeForward(mc.level, me, dx, dz)) {
            pause(mc, "Unsafe movement ahead"); return;
        }
        face(me, Vec3.atCenterOf(next).add(0,0.4,0));
        moveForward(mc, false);
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
        // BlockTags encode the actual vanilla harvesting tier. Don't break
        // rare ore using a wooden tool, which destroys the drop.
        return equipToolFor(mc, me, s);
    }

    private static boolean equipToolFor(Minecraft mc, LocalPlayer me, BlockState state) {
        int best = -1;
        float bestSpeed = -1;
        for (int i=0; i<36; i++) {
            ItemStack stack = me.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.PICKAXES)) continue;
            if (stack.isDamageableItem()
                && stack.getMaxDamage()-stack.getDamageValue()<=3) continue;
            if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)
                && !(stack.is(Items.DIAMOND_PICKAXE)
                    || stack.is(Items.NETHERITE_PICKAXE))) continue;
            if (state.is(BlockTags.NEEDS_IRON_TOOL)
                && !(stack.is(Items.IRON_PICKAXE)
                    || stack.is(Items.DIAMOND_PICKAXE)
                    || stack.is(Items.NETHERITE_PICKAXE))) continue;
            if (state.is(BlockTags.NEEDS_STONE_TOOL)
                && !(stack.is(Items.STONE_PICKAXE)
                    || stack.is(Items.IRON_PICKAXE)
                    || stack.is(Items.DIAMOND_PICKAXE)
                    || stack.is(Items.NETHERITE_PICKAXE))) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {best=i;bestSpeed=speed;}
        }
        if(best<0)return false;
        if(best>=9) {
            // Crafted tools can land in the main inventory. Swap the best
            // pickaxe into an empty or low-priority hotbar slot using a
            // regular player-inventory action, without opening a GUI.
            if(!(me.containerMenu instanceof InventoryMenu)) return false;
            int targetSlot=8;
            for(int i=0;i<9;i++)
                if(me.getInventory().getItem(i).isEmpty()){targetSlot=i;break;}
            mc.gameMode.handleContainerInput(me.containerMenu.containerId,
                best,targetSlot,ContainerInput.SWAP,me);
            lastToolSwapTick=ticks;
            return false;
        }
        me.getInventory().setSelectedSlot(best);
        return true;
    }

    private static BlockPos findReachableOre(Minecraft mc, LocalPlayer me) {
        Set<String> ids = new HashSet<>(SURVIVAL_ORES);
        ids.addAll(LockOnSettings.customBlockIds());
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
        if (mc.gameMode == null || mc.level == null) return;
        if (mc.level.getBlockState(p).isAir()) {
            if (miningBlock != null && miningBlock.equals(p)) finishDig(mc);
            aimedBlock = null;
            return;
        }
        if (!hasWorkingPickaxe(me)) {
            cancelDig(mc);
            reason = "Pickaxe missing: cannot mine";
            return;
        }
        release(mc);
        // Vanilla server mining is ray- and facing-sensitive. Previously the
        // bot called startDestroyBlock without ever looking DOWN or at the
        // target. Aim on the first tick, allow the normal movement packet to
        // transmit pitch/yaw, then start mining on a later gameplay tick.
        face(me,Vec3.atCenterOf(p));
        if (aimedBlock == null || !aimedBlock.equals(p)) {
            if (miningBlock != null) cancelDig(mc);
            aimedBlock = p;
            aimedSince = ticks;
            reason = "Aiming at " + p.toShortString();
            return;
        }
        if (ticks - aimedSince < 2) return;
        if (me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.2) {
            cancelDig(mc);
            pause(mc,"Dig target outside normal Survival reach");
            return;
        }
        BlockHitResult actual=mc.level.clip(new ClipContext(
            me.getEyePosition(),Vec3.atCenterOf(p),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,me));
        if(actual.getType()!=HitResult.Type.BLOCK
            || !actual.getBlockPos().equals(p)) {
            cancelDig(mc);
            pause(mc,"Dig line of sight blocked at "+p.toShortString());
            return;
        }
        if (miningBlock == null) {
            miningBlock = p;
            miningStarted = ticks;
            miningFace = actual.getDirection();
            mc.gameMode.startDestroyBlock(p,miningFace);
            reason = "Mining "+p.toShortString()+" (looking at block)";
        } else if (miningBlock.equals(p)) {
            mc.gameMode.continueDestroyBlock(p,miningFace);
            if (ticks % 5 == 0)
                me.swing(InteractionHand.MAIN_HAND,
                    me.getMainHandItem().getAttackAnimation(),false);
        }
        if (mc.level.getBlockState(p).isAir()) {
            finishDig(mc);
        } else if (ticks-miningStarted > MAX_MINING_TICKS) {
            pause(mc,"Block not breaking after 12s; protected or unsuitable tool");
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
        aimedBlock = null;
        blocksMined++;
        reason = "Mined "+blocksMined+" blocks";
    }

    private static void cancelDig(Minecraft mc) {
        if (miningBlock != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        miningBlock = null;
        aimedBlock = null;
    }

    private static boolean hasWorkingPickaxe(LocalPlayer me) {
        for (int i=0;i<36;i++) {
            ItemStack item=me.getInventory().getItem(i);
            if (!item.is(ItemTags.PICKAXES)) continue;
            if (!item.isDamageableItem()
                || item.getMaxDamage()-item.getDamageValue() > 3) return true;
        }
        return false;
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

    // A shallow staircase from the original surface home into stone.
    // Revisit every waypoint in reverse on return; no teleportation.
    private static BlockPos tunnelPoint(int index) {
        int interval = Math.max(2, 4 - cycles / 4);
        int depth = Math.min(24, Math.max(0,index) / interval);
        return base.relative(tunnelDirection,Math.max(0,index)).below(depth);
    }

    private static void returnToBase(Minecraft mc,LocalPlayer me) {
        if (base == null) { pause(mc,"No base recorded");return; }
        BlockPos destination = steps > 0 ? tunnelPoint(steps-1) : base;
        double dx=destination.getX()+0.5-me.getX();
        double dz=destination.getZ()+0.5-me.getZ();
        double d=Math.hypot(dx,dz);
        if (d < 0.65 && Math.abs(me.getY()-destination.getY()) < 0.85) {
            if (steps > 0) {
                steps--;
                release(mc);
                return;
            }
            release(mc);
            phase=Phase.BASE;
            LockOnBase.reset();
            reason="home processing (chest, furnace, materials)";
            return;
        }
        if (d > 3.0) {
            pause(mc,"Lost original return path; manual help needed");
            return;
        }
        boolean uphill=destination.getY()>me.getY()+0.35;
        if (uphill) {
            if (destination.getY()-me.getY()>1.45
                || !Navigator.safeToJump(mc.level,me)) {
                pause(mc,"Unsafe upward return step");return;
            }
        } else if(!Navigator.safeForward(mc.level,me,dx,dz)) {
            pause(mc,"Return route blocked or unsupported");return;
        }
        face(me,new Vec3(destination.getX()+0.5,me.getEyeY(),
            destination.getZ()+0.5));
        moveForward(mc,false);
        mc.options.keyJump.setDown(uphill);
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
        Component line = Component.literal("[LockOn Survival] " + message);
        player.sendOverlayMessage(line);
        // Start/stop/failure notices go to chat, too: another vanilla HUD
        // animation or screen can erase the one-line action bar instantly.
        player.sendSystemMessage(line);
    }
}
