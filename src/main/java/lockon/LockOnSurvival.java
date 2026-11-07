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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
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
    private enum Phase { OFF, BOOTSTRAP, TUNNEL, RETURN, BASE, PAUSED, RECOVER }
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
    private static boolean combatActive;
    private static boolean reachedDeath;
    private static java.util.UUID mobTargetId;
    private static int mobAimSince = -1000;
    private static int mobWeaponChanged = -1000;
    private static BlockPos miningBlock;
    private static BlockPos aimedBlock;
    private static BlockPos pickupBlock;
    private static int pickupSince;
    private static boolean sawDrop;
    private static java.util.Map<Item,Integer> inventoryBeforeDig=java.util.Map.of();
    private static boolean seekingStone;
    private static BlockPos deathPosition,deathPickupPoint,pickupStanding;
    private static Object deathDimension;
    private static int deathTicks,recoveryTicks,deathQuietTicks;
    private record DropTask(BlockPos position,java.util.Map<Item,Integer> inventory) {}
    private static final java.util.ArrayDeque<DropTask> pendingDrops=new java.util.ArrayDeque<>();
    private static BlockPos towerBlock;
    private static int towerStarted, towerLastUse=-100;
    private static boolean escapeRequested;
    private static int escapeAttempts, lastEscapeStep=-1;
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

    private static BlockPos retreatOrigin;
    private static int safeTicks;

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
        deathPosition=null;
        deathPickupPoint=null;
        pendingDrops.clear();
        LockOnTravel.reset(mc);
        retreatOrigin=null;
        safeTicks=0;
        LockOnFood.reset();
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
        pickupBlock=null;
        seekingStone=false;
        towerBlock=null;
        escapeRequested=false;
        escapeAttempts=0;
        lastEscapeStep=-1;
        lastDistanceTick = 0;
        lastDistance = -1;
        lastWaypoint = null;
        phase = Phase.BOOTSTRAP;
        reason = "";
        LockOnBase.reset();
        LockOnBootstrap.reset(mc,base,baseFacing);
        notice(me, "Autoplay ON at " + base.toShortString()
            + ": gathering wood and crafting first. F7 stops anytime.");
    }

    public static void stop(Minecraft mc, String message) {
        cancelDig(mc);
        release(mc);
        LockOnBase.stop(mc);
        LockOnBootstrap.stop(mc);
        phase = Phase.OFF;
        combatActive=false;
        LockOnCombat.stopUse(mc);
        deathPosition=null;
        pendingDrops.clear();
        LockOnTravel.reset(mc);
        pickupBlock=null;
        towerBlock=null;
        escapeRequested=false;
        eating = false;
        foodSlot = -1;
        if (mc.player != null && message != null && !message.isBlank()) notice(mc.player,message);
    }

    static boolean recoveringDeath() { return deathPosition!=null; }

    static boolean handleDeath(Minecraft mc) {
        if(!enabled()) return false;
        if(mc.player==null || mc.level==null) return deathPosition!=null;
        if(!mc.player.isAlive()) {
            if(deathPosition==null) {
                deathPosition=mc.player.blockPosition();
                deathDimension=mc.level.dimension();
                deathTicks=recoveryTicks=deathQuietTicks=0;
                reachedDeath=false;
                LockOnCombat.stopUse(mc);
                deathPickupPoint=null;
                cancelDig(mc);
                release(mc);
                LockOnTravel.reset(mc);
                pendingDrops.clear();
                pickupBlock=towerBlock=null;
                phase=Phase.RECOVER;
                reason="Died: waiting to respawn and recover dropped equipment";
            }
            if(++deathTicks>=40 && deathTicks%40==0) mc.player.respawn();
            return true;
        }
        if(deathPosition!=null) {
            if(!deathDimension.equals(mc.level.dimension())) {
                stop(mc,"Death occurred in another dimension; return there manually to recover items");
                return true;
            }
            if(mc.player.isSpectator()) {
                stop(mc,"Spectator/hardcore state: ordinary respawn recovery is unavailable");
                return true;
            }
            world=mc.level;
        }
        return false;
    }

    private static void recoverDeath(Minecraft mc) {
        if(pickupBlock!=null && collectMinedDrop(mc)) return;
        if(++recoveryTicks>6000) {stop(mc,"Death recovery timed out after five minutes");return;}
        reason="Returning to death location "+deathPosition.toShortString();
        if(!reachedDeath && mc.player.position().distanceToSqr(Vec3.atCenterOf(deathPosition))>9) {
            LockOnTravel.walk(mc,LockOnTravel.standingPosition(mc,deathPosition));return;
        }
        reachedDeath=true;
        if(deathPickupPoint!=null) {
            if(LockOnTravel.walk(mc,deathPickupPoint)) {
                if(++deathQuietTicks>20) {deathPickupPoint=null;deathQuietTicks=0;}
            }
            return;
        }
        for(Entity e:mc.level.entitiesForRendering()) {
            if(e instanceof ItemEntity item && item.isAlive()
                && item.position().distanceToSqr(Vec3.atCenterOf(deathPosition))<64) {
                deathPickupPoint=LockOnTravel.standingPosition(mc,item.blockPosition());
                deathQuietTicks=0;
                return;
            }
        }
        release(mc);
        if(++deathQuietTicks<60) return;
        deathPosition=null;
        LockOnBase.reset();
        LockOnBootstrap.reset(mc,base,baseFacing);
        phase=Phase.RETURN;
        reason="Death location searched; returning home with recovered inventory";
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

        if (me.getAirSupply() < 75) {
            cancelDig(mc);
            release(mc);
            LockOnMovement.jump(mc,true);
            reason = "low air: swimming upward / paused";
            return;
        }
        if (me.isInLava()) {
            pause(mc, "Lava hazard: movement stopped");
            return;
        }

        Monster hostile = findHostile(mc, me);
        boolean enemyNear = hostile != null && hostile.distanceToSqr(me) < 36.0;
        // A hunger check MUST NOT mask immediate hostile defense. Previous
        // code ran EAT -> PAUSED before DEFEND and ignored monsters while
        // crafting or near the home base. Never run ordinary crafting work
        // when a hostile entity has reached melee approach distance.
        int threats=0;
        Monster danger=hostile;
        for(Entity entity:mc.level.entitiesForRendering()) {
            if(entity instanceof Monster monster && monster.isAlive() && monster.distanceToSqr(me)<100
                && me.hasLineOfSight(monster)) {
                threats++;
                if(monster instanceof Creeper) danger=monster;
            }
        }
        if(danger!=null && danger.distanceToSqr(me)<100
            && SurvivalPolicy.shouldFlee(me.getHealth(),danger instanceof Creeper,threats,
                LockOnSettings.survivalFightMobs())) {
            flee(mc,danger);
            return;
        }
        if (enemyNear && LockOnSettings.survivalFightMobs() && me.getHealth()>9) {
            if(!combatActive) {interruptWork(mc);combatActive=true;}
            fightHostile(mc,me,hostile);
            return;
        }
        if(combatActive) {combatActive=false;LockOnCombat.stopUse(mc);}
        if(deathPosition!=null) {recoverDeath(mc);return;}
        if(retreatOrigin!=null) {
            if(++safeTicks<40) {release(mc);LockOnCombat.stopUse(mc);tryEat(mc,me);return;}
            if(LockOnTravel.walk(mc,retreatOrigin)) retreatOrigin=null;
            reason="Returning to interrupted work after danger";
            return;
        }
        if(pickupBlock!=null && collectMinedDrop(mc)) return;
        if(LockOnMovement.stuck()) {
            if((phase==Phase.TUNNEL || phase==Phase.RETURN) && escapeAttempts<3) {
                cancelDig(mc);
                release(mc);
                phase=Phase.RETURN;
                escapeRequested=true;
                escapeAttempts++;
                reason="Trench blocked: trying to clear an exit or place a climbing block";
            } else stop(mc,"No safe exit after three recovery attempts. F7 stops/restarts autoplay.");
            return;
        }
        if(LockOnTravel.openGate(mc)) {release(mc);return;}
        if(!managingContainer() && !LockOnBootstrap.busyCrafting()
            && !LockOnBase.busyCrafting() && me.containerMenu instanceof InventoryMenu) {
            if(LockOnBase.equipArmor(mc)) return;
            if(me.getFoodData().getFoodLevel()<18 && LockOnSettings.survivalAutoEat()
                && tryEat(mc,me)) {release(mc);return;}
            if(phase!=Phase.BASE && phase!=Phase.RETURN
                && (LockOnFood.count(mc)<8 || LockOnFood.pending())
                && Math.abs(me.getY()-base.getY())<3) {
                reason="Gathering nearby mature crops/berries and replanting";
                if(LockOnFood.hasWheat(mc) || (LockOnFood.rawCount(mc)>=4
                    && (LockOnBase.hasFurnace(mc) || countItem(me,Items.COBBLESTONE)>=8))) {
                    phase=Phase.RETURN;
                    returnToBase(mc,me);
                    return;
                }
                LockOnFood.tick(mc);
                // Allow mining when food reserves are adequate and nothing is pending.
                if(me.getFoodData().getFoodLevel()<=8 || LockOnFood.pending()) return;
            }
        }
        if (phase == Phase.BASE) {
            release(mc);
            if (LockOnBase.tick(mc, base, baseFacing)) {
                if (LockOnSettings.survivalAutoRepeat()) {
                    cycles++;
                    tunnelDirection = tunnelDirection.getClockWise();
                    steps = 0;
                    startTick = ticks;
                    phase = Phase.BOOTSTRAP;
                    LockOnBootstrap.reset(mc,base,baseFacing);
                    reason="Restocking wood, sticks and tools for next cycle";
                } else {
                    stop(mc,"Mining and base processing complete. "+status());
                }
            }
            return;
        }
        int free = freeSlots(me);
        SurvivalPolicy.Decision decision = SurvivalPolicy.decide(
            me.getHealth(), me.getFoodData().getFoodLevel(),
            me.isOnFire(), me.isInLava(), me.isInWall(), enemyNear,
            free, steps, cycleLength());

        if (decision == SurvivalPolicy.Decision.STOP) {
            reason="Waiting for safe survival conditions";
            release(mc);
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
            if(steps>0) {phase=Phase.RETURN;returnToBase(mc,me);}
            else {reason="Searching nearby crops and berries for food";LockOnFood.tick(mc);}
            return;
        }
        if (eating) stopEating(mc);
        if (phase == Phase.PAUSED) {
            release(mc);
            if(ticks%100==0) {
                if(steps>0) phase=Phase.RETURN;
                else {phase=Phase.BOOTSTRAP;LockOnBootstrap.reset(mc,base,baseFacing);}
            }
            return;
        }

        if (phase == Phase.BOOTSTRAP) {
            if (LockOnBootstrap.tick(mc)) {
                LockOnBootstrap.stop(mc);
                steps = 0;
                startTick = ticks;
                phase = Phase.TUNNEL;
                seekingStone=!hasBetterPickaxe(me);
                reason = seekingStone?"Descending to gather cobblestone for a stone pickaxe":"Tools ready: strip mining";
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
            if(me.isInWater()) {
                BlockPos next=tunnelPoint(steps+1);
                if(LockOnTravel.walk(mc,next)) steps++;
                reason="Swimming toward next supported mining waypoint";
                return;
            }
            if((seekingStone && countItem(me,Items.COBBLESTONE)>=3)
                || (!hasIronPickaxe(me) && (countItem(me,Items.RAW_IRON)+countItem(me,Items.IRON_INGOT)>=3)
                    && countItem(me,Items.COBBLESTONE)>=8)) {
                cancelDig(mc);
                release(mc);
                phase=Phase.RETURN;
                reason="Upgrade materials collected: returning to smelt and craft a better pickaxe";
                return;
            }
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
                return;
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
            ) {
            if(mc.level.getBlockState(next).getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || ground.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                LockOnTravel.walk(mc,next);
                reason="Swimming through water on mining route";
                return;
            }
            reason="No supporting ground at planned step; finding a supported route";
            LockOnTravel.walk(mc,next);
            return;
        }
        if (hazardAround(mc.level, next)) {
            pause(mc, "Lava/fire/void detected near next tunnel block"); return;
        }

        int clearance=MiningPlan.clearance(tunnelPoint(steps).getY(),next.getY());
        // Clear from top to bottom, including jump headroom BEFORE descending.
        for (int height=clearance-1;height>=0;height--) {
            BlockPos pos=next.above(height);
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
        if (me.onGround() && horizontal <= 0.52 && Math.abs(me.getY() - next.getY()) < 0.75) {
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
        double heightDifference=next.getY()-me.getY();
        if(me.onGround() && Math.abs(heightDifference)>1.45) {
            pause(mc,"Unsafe vertical step on mining route");
            return;
        }
        boolean climbing=heightDifference>0.35;
        if(me.onGround() && climbing && !clearClimb(mc,me,next)) return;
        // Airborne players are already executing a checked stair jump/drop.
        // Requiring a walkable block at their mid-jump feet rejects the step.
        if(me.onGround() && !climbing && heightDifference>=-0.35
            && !Navigator.safeForward(mc.level,me,dx,dz)) {
            LockOnTravel.walk(mc,next);
            reason="Rejoining mining route around a trench obstruction";
            return;
        }
        face(me, Vec3.atCenterOf(next).add(0,0.4,0));
        moveForward(mc, false);
        LockOnMovement.jump(mc,me.onGround() && climbing);
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
                && stack.getMaxDamage()-stack.getDamageValue()<=0) continue;
            if(state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
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
            if (p.equals(at.below()) || isRouteSupport(p)
                || (base!=null && p.distSqr(base) < 3.0)) continue;
            if (!mc.level.hasChunk(p.getX()>>4,p.getZ()>>4))continue;
            BlockState s = mc.level.getBlockState(p);
            String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            boolean needsStone=countItem(me,Items.COBBLESTONE)<11 && !LockOnBase.hasFurnace(mc);
            boolean stone=(seekingStone || needsStone) && (s.is(Blocks.STONE)||s.is(Blocks.COBBLESTONE));
            boolean iron=s.is(Blocks.IRON_ORE)||s.is(Blocks.DEEPSLATE_IRON_ORE);
            if(seekingStone ? !stone : (!stone && !ids.contains(id))) continue;
            boolean harvestable=false;
            for(int slot=0;slot<36;slot++) {
                ItemStack tool=me.getInventory().getItem(slot);
                if(tool.is(ItemTags.PICKAXES) && tool.isCorrectToolForDrops(s)) harvestable=true;
            }
            if(!harvestable) continue;
            double physicalDistance=me.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
            if(physicalDistance>4.1*4.1) continue;
            double d2=physicalDistance;
            if(stone || (iron && !hasIronPickaxe(me))) d2-=20;
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
        if (!hasWorkingPickaxe(me) && phase!=Phase.RETURN) {
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
            inventoryBeforeDig=inventoryCounts(me);
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
        waitForDrop(miningBlock,inventoryBeforeDig);
        release(mc);
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
                || item.getMaxDamage()-item.getDamageValue() > 0) return true;
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
            if(combatActive && m.getUUID().equals(mobTargetId) && dist<36 && me.hasLineOfSight(m)) return m;
            if(dist<bestSq && me.hasLineOfSight(m)){best=m;bestSq=dist;}
        }
        return best;
    }

    private static void fightHostile(Minecraft mc, LocalPlayer me, Monster enemy) {
        if(enemy==null || mc.gameMode==null) return;
        String kind=enemy.getName().getString();
        if(!enemy.getUUID().equals(mobTargetId)) {mobTargetId=enemy.getUUID();mobAimSince=ticks;}
        boolean weaponReady=LockOnCombat.equipWeapon(mc,false);
        face(me,enemy.position().add(0,enemy.getBbHeight()*0.68,0));
        double dist=me.distanceTo(enemy);
        boolean safe=me.isInWater() || Navigator.safeForward(mc.level,me,
            enemy.getX()-me.getX(),enemy.getZ()-me.getZ());
        CombatTactics.Move move=CombatTactics.move(dist,3.0,0.08,true,!enemy.onGround(),
            safe,false,false,false,me.onGround() || me.isInWater(),ticks-lastMobHit==1,false);
        LockOnMovement.combat(mc,move.forward(),false,false,false,
            move.sprint() && me.getFoodData().getFoodLevel()>6,me.isInWater());
        if(ticks-lastMobHit==1) me.setSprinting(false);
        reason="Defending against "+kind+": approaching / charging";
        if(!weaponReady || ticks-mobAimSince<2) return;
        LockOnCombat.shield(mc,enemy,ticks-lastMobHit);
        if(me.isUsingItem() || !CombatTactics.readyForAttack(me.getMainHandItem().isEmpty(),
            ticks-lastMobHit,me.getAttackStrengthScale(0.0f),0.95f)) return;
        Vec3 eye=me.getEyePosition();
        var box=enemy.getBoundingBox();
        Vec3 end=eye.add(me.getViewVector(1.0f).scale(2.92));
        if(!me.hasLineOfSight(enemy) || (!box.contains(eye) && box.clip(eye,end).isEmpty())) {
            reason="Hostile outside actual hitbox reach";return;
        }
        mc.gameMode.attack(me,enemy);
        me.swing(InteractionHand.MAIN_HAND,me.getMainHandItem().getAttackAnimation(),false);
        me.connection.send(ServerboundPunchPacket.INSTANCE);
        mobsAttacked++;
        lastMobHit=ticks;
        reason="Sent normal melee attack to hostile "+kind;
    }

    private static boolean tryEat(Minecraft mc, LocalPlayer me) {
        if(!LockOnSettings.survivalAutoEat()) return false;
        if (me.getFoodData().getFoodLevel()>=20 || (me.getFoodData().getFoodLevel()>=19 && me.getHealth()>=15.0)) {
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
        for(int i=0;i<36;i++){
            ItemStack food=me.getInventory().getItem(i);
            if(food.isEmpty())continue;
            if(LockOnFood.edible(food) || (me.getFoodData().getFoodLevel()<=8 && LockOnFood.rawMeat(food))) {foodSlot=i;break;}
        }
        if(foodSlot<0)return false;
        if(foodSlot>=9) {
            if(!(me.containerMenu instanceof InventoryMenu)) return false;
            mc.gameMode.handleContainerInput(me.containerMenu.containerId,foodSlot,8,ContainerInput.SWAP,me);
            return true;
        }
        me.getInventory().setSelectedSlot(foodSlot);
        mc.gameMode.useItem(me,InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        eating=true;
        foodUsed++;
        return true;
    }

    private static void stopEating(Minecraft mc) {
        if(eating && mc.player!=null && mc.gameMode!=null && mc.player.isUsingItem())
            mc.gameMode.releaseUsingItem(mc.player);
        eating=false;
        foodSlot=-1;
        mc.options.keyUse.setDown(false);
    }

    // Fixed geometry throughout a run, so the return follows the mined staircase.
    private static BlockPos tunnelPoint(int index) {
        int availableDepth=Math.max(0,base.getY()-world.getMinY()-5);
        int depth=Math.min(availableDepth,MiningPlan.depth(index));
        return base.relative(tunnelDirection,Math.max(0,index)).below(depth);
    }

    private static void returnToBase(Minecraft mc,LocalPlayer me) {
        if (base == null) { pause(mc,"No base recorded");return; }
        if(lastEscapeStep!=steps) {
            lastEscapeStep=steps;
            escapeAttempts=0;
        }
        // Rejoin the current waypoint before decrementing. Drops can pull the
        // player into the next column before it becomes a completed waypoint.
        BlockPos destination = steps > 0 ? tunnelPoint(steps) : base;
        if(miningBlock!=null || aimedBlock!=null) {
            dig(mc,me,miningBlock!=null?miningBlock:aimedBlock);
            return;
        }
        if(towerBlock!=null || escapeRequested || (me.onGround() && destination.getY()-me.getY()>1.45)) {
            towerOut(mc,me);
            return;
        }
        double dx=destination.getX()+0.5-me.getX();
        double dz=destination.getZ()+0.5-me.getZ();
        double d=Math.hypot(dx,dz);
        if (me.onGround() && d < 0.65 && me.getY()>=destination.getY()-0.15
            && me.getY()<=destination.getY()+1.4) {
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
            LockOnTravel.walk(mc,destination);
            reason="Rejoining return route";
            return;
        }
        boolean uphill=destination.getY()>me.getY()+0.35;
        if(me.onGround() && uphill) {
            if(destination.getY()-me.getY()>1.45) {
                escapeRequested=true;
                towerOut(mc,me);
                return;
            }
            if(!clearClimb(mc,me,destination)) return;
        } else if(me.onGround() && !Navigator.safeForward(mc.level,me,dx,dz)) {
            LockOnTravel.walk(mc,destination);
            reason="Routing around return-path obstruction";
            return;
        }
        face(me,new Vec3(destination.getX()+0.5,me.getEyeY(),
            destination.getZ()+0.5));
        moveForward(mc,false);
        LockOnMovement.jump(mc,me.onGround() && uphill);
    }

    private static void towerOut(Minecraft mc,LocalPlayer me) {
        if(towerBlock==null) {
            if(!me.onGround()) {release(mc);return;}
            BlockPos feet=BlockPos.containing(me.getX(),me.getY()+0.08,me.getZ());
            if(hazardAround(mc.level,feet)
                || !mc.level.getBlockState(feet.below()).isFaceSturdy(mc.level,feet.below(),Direction.UP)) {
                stop(mc,"Cannot tower here: unsafe or missing support below");
                return;
            }
            // Clear the space needed for both the jump and the raised player.
            for(BlockPos ceiling:new BlockPos[]{feet.above(2),feet.above(3)}) {
                if(Navigator.passable(mc.level,ceiling)) continue;
                if(canMine(mc,me,ceiling)) {
                    dig(mc,me,ceiling);
                    reason="Digging overhead space before towering out";
                    return;
                }
                if(lastToolSwapTick==ticks) return;
                stop(mc,"Cannot tower: ceiling cannot be safely mined with available tools");
                return;
            }
            towerBlock=feet;
            towerStarted=ticks;
            towerLastUse=-100;
        }
        if(ticks-towerStarted>100) {
            stop(mc,"Tower placement not confirmed; check available blocks or server protection");
            return;
        }
        if(!mc.level.getBlockState(towerBlock).isAir()) {
            release(mc);
            if(me.onGround() && me.getY()>=towerBlock.getY()+0.9) {
                towerBlock=null;
                escapeRequested=false;
                reason="Climbing block confirmed: resuming route home";
            }
            return;
        }
        int material=-1;
        for(int i=0;i<36;i++) {
            ItemStack stack=me.getInventory().getItem(i);
            if(stack.is(Items.DIRT)||stack.is(Items.COBBLESTONE)) {material=i;break;}
        }
        if(material<0) {
            stop(mc,"Cannot tower out: no dirt or cobblestone in inventory");
            return;
        }
        if(!(me.containerMenu instanceof InventoryMenu)) {release(mc);return;}
        if(material>=9) {
            mc.gameMode.handleContainerInput(me.containerMenu.containerId,material,8,ContainerInput.SWAP,me);
            release(mc);
            return;
        }
        double dx=towerBlock.getX()+0.5-me.getX(),dz=towerBlock.getZ()+0.5-me.getZ();
        if(me.onGround() && Math.hypot(dx,dz)>0.18) {
            face(me,new Vec3(towerBlock.getX()+0.5,me.getEyeY(),towerBlock.getZ()+0.5));
            moveForward(mc,false);
            reason="Centering on supported block to tower out";
            return;
        }
        release(mc);
        me.getInventory().setSelectedSlot(material);
        Vec3 hit=new Vec3(towerBlock.getX()+0.5,towerBlock.getY(),towerBlock.getZ()+0.5);
        face(me,hit);
        LockOnMovement.jump(mc,true);
        if(!MiningPlan.canPlaceTower(me.getY(),towerBlock.getY(),ticks-towerLastUse)) {
            reason="Jumping to place a climbing block underneath";
            return;
        }
        BlockPos support=towerBlock.below();
        BlockHitResult ray=mc.level.clip(new ClipContext(me.getEyePosition(),hit.add(0,-0.01,0),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,me));
        if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(support)) {
            reason="Waiting for clear view of tower support";
            return;
        }
        mc.gameMode.useItemOn(me,InteractionHand.MAIN_HAND,ray);
        towerLastUse=ticks;
        reason="Placing dirt/cobblestone under jump; waiting for confirmation";
    }

    private static boolean clearClimb(Minecraft mc,LocalPlayer me,BlockPos destination) {
        for(BlockPos ceiling:new BlockPos[]{me.blockPosition().above(2),destination.above()}) {
            if(Navigator.passable(mc.level,ceiling)) continue;
            if(canMine(mc,me,ceiling)) {
                dig(mc,me,ceiling);
                reason="Clearing jump headroom out of trench";
                return false;
            }
            if(lastToolSwapTick==ticks) return false;
            pause(mc,"Trench ceiling cannot be mined safely");
            return false;
        }
        if(!Navigator.safeToJump(mc.level,me)) {
            pause(mc,"No safe support for trench exit jump");
            return false;
        }
        return true;
    }

    private static int cycleLength() {
        int length=LockOnSettings.stripLength() + (cycles/4)*16;
        return seekingStone?Math.max(64,length):length;
    }

    private static int countItem(LocalPlayer player,Item item) {
        return inventoryCounts(player).getOrDefault(item,0);
    }

    static java.util.Map<Item,Integer> inventoryCounts(LocalPlayer player) {
        java.util.Map<Item,Integer> counts=new java.util.HashMap<>();
        for(int i=0;i<36;i++) {
            ItemStack stack=player.getInventory().getItem(i);
            if(!stack.isEmpty()) counts.merge(stack.getItem(),stack.getCount(),Integer::sum);
        }
        return counts;
    }

    private static boolean hasBetterPickaxe(LocalPlayer player) {
        for(int i=0;i<36;i++) {
            ItemStack stack=player.getInventory().getItem(i);
            if(stack.is(ItemTags.PICKAXES) && !stack.is(Items.WOODEN_PICKAXE)
                && !stack.is(Items.GOLDEN_PICKAXE)
                && (!stack.isDamageableItem() || stack.getMaxDamage()-stack.getDamageValue()>0)) return true;
        }
        return false;
    }

    private static boolean isRouteSupport(BlockPos candidate) {
        for(int i=0;i<=steps+1;i++) if(candidate.equals(tunnelPoint(i).below())) return true;
        return false;
    }

    static void waitForDrop(BlockPos broken,java.util.Map<Item,Integer> before) {
        if(pickupBlock!=null && !pickupBlock.equals(broken)) {
            pendingDrops.addLast(new DropTask(broken.immutable(),java.util.Map.copyOf(before)));
            return;
        }
        pickupBlock=broken;
        pickupStanding=null;
        inventoryBeforeDig=before;
        pickupSince=ticks;
        sawDrop=false;
    }

    private static boolean collectMinedDrop(Minecraft mc) {
        LocalPlayer me=mc.player;
        ItemEntity nearest=null;
        double best=Double.MAX_VALUE;
        for(Entity entity:mc.level.entitiesForRendering()) {
            if(!(entity instanceof ItemEntity item) || !item.isAlive()
                || item.position().distanceToSqr(Vec3.atCenterOf(pickupBlock))>9) continue;
            double distance=item.distanceToSqr(me);
            if(distance<best) {best=distance;nearest=item;}
        }
        if(nearest!=null) sawDrop=true;
        boolean gained=false;
        for(var entry:inventoryCounts(me).entrySet()) {
            if(entry.getValue()>inventoryBeforeDig.getOrDefault(entry.getKey(),0)) gained=true;
        }
        MiningPlan.Pickup action=MiningPlan.pickup(ticks-pickupSince,sawDrop,nearest!=null,gained);
        switch(action) {
            case WAIT -> {
                release(mc);
                reason="Waiting for block drop / inventory pickup confirmation";
                return true;
            }
            case COLLECT -> {
                if(pickupStanding==null) pickupStanding=LockOnTravel.standingPosition(mc,pickupBlock);
                LockOnTravel.walk(mc,pickupStanding);
                reason="Collecting mined drop before continuing";
                return true;
            }
            case BLOCKED -> {
                LockOnTravel.reset(mc);
                reason="Drop unreachable after recovery attempts; continuing other work";
            }
            case DONE -> reason="Drop collected; inventory increase confirmed";
            case NO_DROP -> reason="No dropped item appeared after waiting";
        }
        pickupBlock=null;
        pickupStanding=null;
        if(!pendingDrops.isEmpty()) {
            DropTask next=pendingDrops.removeFirst();
            waitForDrop(next.position(),next.inventory());
        }
        release(mc);
        return true;
    }

    private static boolean hasIronPickaxe(LocalPlayer me) {
        return countItem(me,Items.IRON_PICKAXE)>0 || countItem(me,Items.DIAMOND_PICKAXE)>0
            || countItem(me,Items.NETHERITE_PICKAXE)>0;
    }

    private static void interruptWork(Minecraft mc) {
        cancelDig(mc);
        LockOnTravel.reset(mc);
        towerBlock=null;
        escapeRequested=false;
        stopEating(mc);
        if(phase==Phase.BOOTSTRAP) LockOnBootstrap.reset(mc,base,baseFacing);
        if(phase==Phase.BASE) {LockOnBase.stop(mc);LockOnBase.reset();}
    }

    private static void flee(Minecraft mc,Monster danger) {
        if(retreatOrigin==null) {
            retreatOrigin=mc.player.blockPosition();
            interruptWork(mc);
        }
        safeTicks=0;
        if(combatActive) {combatActive=false;LockOnCombat.stopUse(mc);}
        if(mc.player.getFoodData().getFoodLevel()<20 && mc.player.distanceTo(danger)>5)
            LockOnCombat.eat(mc);
        else LockOnCombat.stopUse(mc);
        Vec3 away=mc.player.position().subtract(danger.position());
        double angle=Math.atan2(away.z,away.x);
        for(double offset:new double[]{0,0.6,-0.6,1.2,-1.2,1.57,-1.57}) {
            double dx=Math.cos(angle+offset), dz=Math.sin(angle+offset);
            if(Navigator.safeForward(mc.level,mc.player,dx,dz)) {
                face(mc.player,mc.player.getEyePosition().add(dx,0,dz));
                moveForward(mc,mc.player.getFoodData().getFoodLevel()>6 && !mc.player.isUsingItem());
                LockOnMovement.jump(mc,mc.player.isInWater());
                reason="Fleeing danger: "+danger.getName().getString();
                return;
            }
        }
        release(mc);
        reason="Escape blocked; seeking an open gate";
        LockOnTravel.openGate(mc);
    }

    private static void face(LocalPlayer me,Vec3 target) {
        Vec3 d=target.subtract(me.getEyePosition());
        me.setYRot((float)Math.toDegrees(Math.atan2(d.z,d.x))-90.0f);
        me.setXRot(Mth.clamp((float)-Math.toDegrees(
            Math.atan2(d.y,Math.hypot(d.x,d.z))),-90.0f,90.0f));
    }

    private static void moveForward(Minecraft mc,boolean sprint) {
        LockOnMovement.forward(mc,sprint);
    }

    private static void release(Minecraft mc) {
        LockOnMovement.stop(mc);
    }

    private static void pause(Minecraft mc,String why) {
        cancelDig(mc);
        release(mc);
        LockOnCombat.stopUse(mc);
        reason=why;
        phase=Phase.PAUSED;
        if(mc.player!=null)notice(mc.player,why+" (F7 to stop)");
    }

    private static void notice(LocalPlayer player,String message) {
        Component line = Component.literal("[LockOn Survival] " + message);
        player.sendOverlayMessage(line);
        // Start/stop/failure notices go to chat, too: another vanilla HUD
        // animation or screen can erase the one-line action bar instantly.
        player.sendSystemMessage(line);
    }
}
