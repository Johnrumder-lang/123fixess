package lockon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous first steps for regular Survival players, no OP commands:
 * nearby natural logs -> planks -> workbench -> sticks -> wooden pickaxe.
 * All crafting uses real 2x2/3x3 menu clicks and actual inventory items.
 * Never invents resources or breaks remote blocks.
 */
final class LockOnBootstrap {
    private enum Step { WOOD, PLANKS, TABLE, STICKS, PICKAXE, READY, WAIT }
    private record Ingredient(Item item, int slot) {}
    private static Step step = Step.WOOD;
    private static BlockPos base, table, treeBlock, digTarget, treeStand;
    private static java.util.Map<Item,Integer> woodInventoryBefore=java.util.Map.of();
    private static ClientLevel world;
    private static Direction facing;
    private static int ticks, lastClick, lastDigTick, lastMoveTick, noProgress;
    private static int lastWoodSearchAt=-1000;
    private static final java.util.Set<BlockPos> blockedTrees=new java.util.HashSet<>();
    private static int logsMined, recipes, woodSearches;
    private static String detail = "";
    private static RecipeJob recipe;
    private static boolean openedTable;
    private static int openTick;
    private static Direction digFace = Direction.UP;
    private static BlockPos aimedWood;
    private static int woodAimTick;
    private static BlockPos lastLookTarget;
    private static int lookStartTick, lastStationUseTick=-1000;
    private static int stationAttempts;
    private static boolean cleaningRecipe;
    private static int cleaningTicks;
    private static final int SEARCH_RADIUS = 8;
    private static final TreeSearch distantTrees=new TreeSearch();
    private static Direction scoutHeading;
    private static BlockPos workbenchStand,workbenchApproach;
    private static int workbenchApproachSince;
    private static BlockPos scoutTarget;
    private static LocalPlayer cleanupOwner;
    private static final StationPlan tablePlacement=new StationPlan();

    static void requestInventoryCleanup(Minecraft mc) { cleanupOwner=mc.player; }
    private static int scoutIndex, scoutSince;
    private static final java.util.Set<BlockPos> rejectedSites=new java.util.HashSet<>();

    private static final class RecipeJob {
        final boolean table;
        final Item output;
        final List<Ingredient> ingredients;
        final int createdTick;
        int ingredient, action, outputWait;
        int source = -1, startingCount, resultClickedAt=-1;
        boolean placedOwnIngredients;
        RecipeJob(boolean table, Item output, List<Ingredient> ingredients,
                  int createdTick, int startingCount) {
            this.table=table; this.output=output; this.ingredients=ingredients;
            this.createdTick=createdTick; this.startingCount=startingCount;
        }
    }

    private LockOnBootstrap() {}

    static void reset(Minecraft mc, BlockPos home, Direction direction) {
        stop(mc);
        base=home;
        facing=direction;
        world=mc.level;
        // Keep all station blocks off the four cardinal mining corridors.
        table=base.relative(direction.getOpposite(),2)
            .relative(direction.getClockWise(),2);
        rejectedSites.clear();
        tablePlacement.reset();
        table=findWorkbenchSite(mc);
        scoutTarget=null;
        scoutIndex=0;scoutHeading=facing;distantTrees.reset();
        workbenchStand=workbenchApproach=null;
        treeBlock=digTarget=treeStand=null;
        aimedWood=null;
        digFace=Direction.UP;
        woodAimTick=0;
        ticks=0;
        lastClick=-20;
        lastDigTick=0;
        lastMoveTick=0;
        noProgress=0;
        lastLookTarget=null;
        lookStartTick=0;
        lastStationUseTick=-1000;
        stationAttempts=0;
        cleaningRecipe=false;
        cleaningTicks=0;
        logsMined=recipes=woodSearches=0;
        lastWoodSearchAt=-1000;
        blockedTrees.clear();
        cleaningRecipe=cleanupOwner==mc.player;
        cleanupOwner=null;
        cleaningTicks=0;
        detail="Locating wood";
        step=Step.WOOD;
    }

    static String status() {
        return step+" logs="+logsMined+" recipes="+recipes
            +(detail.isBlank() ? "" : " — "+detail);
    }

    static boolean recoverInventory(Minecraft mc) {
        if(!cleaningRecipe) return false;
        ticks++;cleanupCraftingGrid(mc);return true;
    }

    static boolean busyCrafting() { return recipe!=null || cleaningRecipe; }

    static boolean managingContainer() { return openedTable; }

    static void stop(Minecraft mc) {
        if(recipe!=null && !recipe.table && (recipe.placedOwnIngredients || recipe.action>0))
            requestInventoryCleanup(mc);
        if(mc!=null && mc.gameMode!=null && digTarget!=null)
            mc.gameMode.stopDestroyBlock();
        if(mc!=null && mc.player!=null && openedTable
            && !(mc.player.containerMenu instanceof InventoryMenu)) {
            mc.player.closeContainer();
            if(mc.gui.screen()!=null) mc.gui.setScreen(null);
        }
        openedTable=false;
        recipe=null;
        cleaningRecipe=false;
        lastLookTarget=null;
        digTarget=null;
        aimedWood=null;
        release(mc);
    }

    static boolean tick(Minecraft mc) {
        LocalPlayer player=mc.player;
        if(player==null || mc.level==null || mc.level!=world || base==null) {
            step=Step.WAIT;
            detail="World changed";
            release(mc);
            return false;
        }
        ticks++;
        if(step==Step.WAIT) {
            release(mc);
            // The inventory or world might change while the bot is paused.
            // Rescan the actual recipe requirements every second rather
            // than remaining frozen in a one-time "make pickaxe" state.
            if(ticks%20==0 && !cleaningRecipe){
                woodSearches=0;
                treeBlock=null;
                blockedTrees.clear();
                step=Step.WOOD;
                detail="Rechecking materials and resuming progression";
            } else return false;
        }
        if(ticks%40==0) {
            // Keep the status visible during a long wood-gathering/crafting run.
            player.sendOverlayMessage(Component.literal("[LockOn Auto] "+status()));
        }
        if (cleaningRecipe) {
            cleanupCraftingGrid(mc);
            return false;
        }
        if(recipe!=null) {
            detail="Crafting "+recipe.output+": ingredient "
                +(Math.min(recipe.ingredient+1,recipe.ingredients.size()))
                +"/"+recipe.ingredients.size()
                +", action "+recipe.action;
            craftTick(mc);
            return false;
        }
        if(digTarget!=null) {
            if(mc.level.getBlockState(digTarget).isAir()){
                LockOnSurvival.waitForDrop(digTarget,woodInventoryBefore);
                digTarget=null;
                logsMined++;
                detail="Log broken: walking over to collect drops";
                return false;
            } else if(ticks-lastDigTick>180){
                mc.gameMode.stopDestroyBlock();
                digTarget=null;
                step=Step.WAIT;
                detail="Tree breaking timed out or server protected";
                release(mc);
                return false;
            }else{
                mc.gameMode.continueDestroyBlock(digTarget,digFace);
                return false;
            }
        }

        if(tablePlacement.attempted() && !hasTable(mc)) {
            StationPlan.Action pending=tablePlacement.next(ticks,false,count(player,Items.CRAFTING_TABLE),false);
            if(pending==StationPlan.Action.WAIT || pending==StationPlan.Action.FAILED) {
                release(mc);
                detail=pending==StationPlan.Action.WAIT?"Waiting for workbench placement confirmation":
                    "Workbench placement failed; retaining resources until restart";
                return false;
            }
        }
        // Every phase transition uses a deterministic plan. A fresh player
        // with no wood cannot immediately claim "mining finished".
        AutoplayPlan.Action action = AutoplayPlan.next(
            countTag(player,ItemTags.LOGS),
            countTag(player,ItemTags.PLANKS),
            count(player,Items.STICK),
            hasPickaxe(player),hasTable(mc),
            count(player,Items.CRAFTING_TABLE)>0);
        step = switch(action) {
            case GATHER_WOOD -> Step.WOOD;
            case MAKE_PLANKS -> Step.PLANKS;
            case MAKE_TABLE, PLACE_TABLE -> Step.TABLE;
            case MAKE_STICKS -> Step.STICKS;
            case MAKE_PICKAXE -> Step.PICKAXE;
            case READY -> Step.READY;
        };

        switch(step){
            case WOOD -> gatherWood(mc,player);
            case PLANKS -> {
                Item log=findItem(player,ItemTags.LOGS);
                if(log!=null) startCraft(false,Items.OAK_PLANKS,
                    List.of(new Ingredient(log,1)));
                else detail="Waiting for dropped logs";
            }
            case TABLE -> {
                if(hasTable(mc)) {detail="Workbench placed";return false;}
                if(count(player,Items.CRAFTING_TABLE)>0) {
                    placeWorkbench(mc,player);
                } else {
                    Item plank=findItem(player,ItemTags.PLANKS);
                    if(plank!=null && countTag(player,ItemTags.PLANKS)>=4){
                        startCraft(false,Items.CRAFTING_TABLE,
                            List.of(new Ingredient(null,1),new Ingredient(null,2),
                                new Ingredient(null,3),new Ingredient(null,4)));
                    } else {
                        detail="Need more wood for crafting table";
                        step=Step.WOOD;
                    }
                }
            }
            case STICKS -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank!=null && countTag(player,ItemTags.PLANKS)>=2){
                    startCraft(false,Items.STICK,
                        List.of(new Ingredient(null,1),new Ingredient(null,3)));
                } else {detail="Need more planks for sticks";step=Step.WOOD;}
            }
            case PICKAXE -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank==null || countTag(player,ItemTags.PLANKS)<3
                    || count(player,Items.STICK)<2) {
                    detail="Pickaxe missing planks or sticks: returning to wood gathering";
                    step=Step.WOOD;
                    return false;
                }
                if(!openWorkbench(mc,player))return false;
                startCraft(true,Items.WOODEN_PICKAXE,
                    List.of(new Ingredient(null,1),new Ingredient(null,2),
                        new Ingredient(null,3),new Ingredient(Items.STICK,5),
                        new Ingredient(Items.STICK,8)));
            }
            case READY -> {
                // Carry enough renewable supplies for stations and replacement tools.
                if (countTag(player,ItemTags.PLANKS)<24) {
                    Item log=findItem(player,ItemTags.LOGS);
                    if(log==null) gatherWood(mc,player);
                    else startCraft(false,Items.OAK_PLANKS,List.of(new Ingredient(log,1)));
                    return false;
                }
                if(count(player,Items.STICK)<8) {
                    Item plank=findItem(player,ItemTags.PLANKS);
                    startCraft(false,Items.STICK,List.of(new Ingredient(null,1),new Ingredient(null,3)));
                    return false;
                }
                // Resource collection can finish far from home. Walk back before mining.
                detail="Returning to home with wood and tools";
                return LockOnTravel.walk(mc,base);
            }
            case WAIT -> {}
        }
        return false;
    }

    private static void gatherWood(Minecraft mc,LocalPlayer player) {
        if((treeBlock==null || mc.level.getBlockState(treeBlock).isAir())
            && ticks-lastWoodSearchAt>=20) {
            lastWoodSearchAt=ticks;
            if(ticks%200<20) blockedTrees.clear();
            treeBlock=findTree(mc,player);
            treeStand=null;
            aimedWood=null;
            woodSearches++;
        }
        if(treeBlock==null) treeBlock=distantTrees.poll(mc,blockedTrees);
        if(treeBlock==null){
            scoutForTree(mc);
            return;
        }
        double distance=player.getEyePosition().distanceTo(Vec3.atCenterOf(treeBlock));
        double horizontalToTree=Math.hypot(treeBlock.getX()+0.5-player.getX(),
            treeBlock.getZ()+0.5-player.getZ());
        // Stand near the trunk to pick up dropped logs. Upper log blocks
        // are still mineable from beside the trunk without walking into it.
        if(distance>4.0 || (distance>2.25 && horizontalToTree>1.85)){
            double dx=treeBlock.getX()+0.5-player.getX();
            double dz=treeBlock.getZ()+0.5-player.getZ();
            if(treeStand==null) treeStand=treeApproach(mc,treeBlock);
            LockOnTravel.walk(mc,treeStand);
            detail="Walking around obstacles to tree ("+(int)distance+" blocks)";
            lastMoveTick=ticks;
            return;
        }
        release(mc);
        if(mc.level.getBlockState(treeBlock).isAir()){treeBlock=null;return;}
        rotate(player,Vec3.atCenterOf(treeBlock));
        // The nearest log can be behind leaves or another log. Vanilla
        // players cannot punch THROUGH it. Retarget rather than endlessly
        // sending break packets to an occluded block.
        BlockHitResult hit=mc.level.clip(new ClipContext(
            player.getEyePosition(),Vec3.atCenterOf(treeBlock),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.BLOCK
            || !hit.getBlockPos().equals(treeBlock)){
            if(hit.getType()==HitResult.Type.BLOCK && Navigator.mineable(mc.level,player,hit.getBlockPos())) {
                LockOnTravel.clearObstacle(mc,hit.getBlockPos());
                detail="Clearing leaves/block in front of the selected tree";
            } else {
                blockedTrees.add(treeBlock);treeBlock=null;treeStand=null;
                detail="Tree approach blocked; choosing another reachable log";
            }
            return;
        }
        if(aimedWood==null || !aimedWood.equals(treeBlock)) {
            aimedWood=treeBlock;
            woodAimTick=ticks;
            detail="Aiming at tree before punching";
            return;
        }
        if(ticks-woodAimTick<2)return;
        digTarget=treeBlock;
        digFace=hit.getDirection();
        lastDigTick=ticks;
        woodInventoryBefore=LockOnSurvival.inventoryCounts(player);
        mc.gameMode.startDestroyBlock(treeBlock,digFace);
        detail="Punching nearby tree "+treeBlock.toShortString();
    }

    private static BlockPos findTree(Minecraft mc,LocalPlayer player){
        BlockPos playerPos=BlockPos.containing(player.getX(),player.getY(),player.getZ());
        BlockPos closest=null;
        double best=Double.POSITIVE_INFINITY;
        for(int x=-SEARCH_RADIUS;x<=SEARCH_RADIUS;x++){
            for(int z=-SEARCH_RADIUS;z<=SEARCH_RADIUS;z++){
                int bx=playerPos.getX()+x,bz=playerPos.getZ()+z;
                if(!mc.level.hasChunk(bx>>4,bz>>4))continue;
                for(int y=-4;y<=12;y++){
                    BlockPos p=new BlockPos(bx,playerPos.getY()+y,bz);
                    if(blockedTrees.contains(p)
                        || !mc.level.getBlockState(p).is(BlockTags.LOGS)
                        || !naturalTree(mc.level,p))continue;
                    double physical=player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
                    // Prefer lower trunk pieces so mining starts at the
                    // base and the dropped log falls close to the player.
                    double d=physical+2.0*Math.max(0,p.getY()-playerPos.getY());
                    if(d<best){best=d;closest=p;}
                }
            }
        }
        return closest;
    }

    static boolean naturalTree(ClientLevel world,BlockPos p) {
        // Avoid stripping logs from an unrelated wooden house.
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            for(int dy=0;dy<=16;dy++){
                if(world.getBlockState(p.offset(dx,dy,dz)).is(BlockTags.LEAVES))
                    return true;
            }
        return false;
    }

    private static BlockPos treeApproach(Minecraft mc,BlockPos log) {
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(log.offset(-2,-3,-2),log.offset(2,1,2))) {
            if(!Navigator.passable(mc.level,p) || !Navigator.passable(mc.level,p.above())
                || !mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)) continue;
            Vec3 eye=Vec3.atBottomCenterOf(p).add(0,mc.player.getEyeHeight(),0);
            if(eye.distanceTo(Vec3.atCenterOf(log))>3.8
                || Math.hypot(p.getX()-log.getX(),p.getZ()-log.getZ())>1.8) continue;
            double score=mc.player.position().distanceToSqr(Vec3.atBottomCenterOf(p));
            if(score<distance) {distance=score;best=p.immutable();}
        }
        return best==null?log:best;
    }

    private static boolean lookForInteraction(LocalPlayer player,
                                              BlockPos block, Vec3 hitPoint) {
        if (lastLookTarget == null || !lastLookTarget.equals(block)) {
            lastLookTarget=block;
            lookStartTick=ticks;
        }
        rotate(player,hitPoint);
        // Vanilla server checks the player's look direction. It must arrive
        // before an item-on-block packet for table placement or interaction.
        return ticks-lookStartTick>=2;
    }

    private static void placeWorkbench(Minecraft mc,LocalPlayer player){
        if (hasTable(mc)) {
            stationAttempts=0;
            lastLookTarget=null;
            return;
        }
        if(table==null || !validWorkbenchSite(mc,table)
            || (!tablePlacement.attempted()
                && BlockInteraction.hit(mc,player.getEyePosition(),table.below(),true)==null)) {
            table=findWorkbenchSite(mc);
            stationAttempts=0;
            lastLookTarget=null;
            if(table==null) {
                detail="Searching for supported workbench space nearby";
                release(mc);
                return;
            }
        }
        int slot=hotbarSlot(player,Items.CRAFTING_TABLE);
        if(slot<0){
            moveItemToHotbar(mc,Items.CRAFTING_TABLE);
            detail="Moving crafted table to hotbar";
            return;
        }
        Vec3 supportPoint=new Vec3(table.getX()+0.5,table.getY()-0.01,table.getZ()+0.5);
        if(!approachWorkbench(mc,table.below(),supportPoint)) return;
        release(mc);
        if(player.getInventory().getSelectedSlot()!=slot) {
            player.getInventory().setSelectedSlot(slot);lastLookTarget=null;return;
        }
        BlockHitResult supportHit=BlockInteraction.hit(mc,player.getEyePosition(),table.below(),true);
        if(supportHit==null) {lastLookTarget=null;return;}
        if(!lookForInteraction(player,table.below(),supportHit.getLocation())) return;
        if(ticks-lastStationUseTick<14)return;
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,supportHit);
        tablePlacement.sent(ticks);
        stationAttempts++;
        lastStationUseTick=ticks;
        detail="Placing crafting table (attempt "+stationAttempts+")";
    }

    private static boolean openWorkbench(Minecraft mc,LocalPlayer player) {
        if(player.containerMenu instanceof CraftingMenu) {
            openedTable=true;
            stationAttempts=0;
            return true;
        }
        if(openedTable) {
            if(ticks-openTick>40) {
                openedTable=false;
                if(++stationAttempts>=4) {
                    step=Step.WAIT;
                    detail="Workbench did not open after four attempts";
                } else {
                    detail="Workbench opening not confirmed; trying again";
                }
            }
            return false;
        }
        if(table==null || !hasTable(mc)) {
            detail="Workbench missing: gathering/crafting replacement";
            step=Step.WOOD;
            return false;
        }
        if(!approachWorkbench(mc,table,Vec3.atCenterOf(table))) return false;
        release(mc);
        BlockHitResult tableHit=BlockInteraction.hit(mc,player.getEyePosition(),table,false);
        if(tableHit==null) {lastLookTarget=null;return false;}
        Vec3 target=tableHit.getLocation();
        if(!lookForInteraction(player,table,target)) {
            detail="Looking at reachable workbench face";return false;
        }
        if(ticks-lastStationUseTick<12)return false;
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,tableHit);
        openedTable=true;
        openTick=ticks;
        lastStationUseTick=ticks;
        detail="Opening crafting-table GUI";
        return false;
    }

    static BlockPos workbenchPosition() { return table; }

    private static boolean validWorkbenchSite(Minecraft mc,BlockPos p) {
        return !rejectedSites.contains(p)
            && mc.level.getBlockState(p).canBeReplaced()
            && mc.level.getBlockState(p).getFluidState().isEmpty()
            && mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)
            && mc.level.getBlockState(p.below()).getFluidState().isEmpty()
            && !mc.level.getBlockState(p.below()).hasBlockEntity()
            && !mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p));
    }

    private static BlockPos findWorkbenchSite(Minecraft mc) {
        BlockPos feet=mc.player.blockPosition(),existing=null,chosen=null;
        double nearest=Double.MAX_VALUE,best=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(feet.offset(-6,-3,-6),feet.offset(6,3,6))) {
            if(mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                double distance=mc.player.position().distanceToSqr(Vec3.atCenterOf(p));
                if(distance<nearest) {nearest=distance;existing=p.immutable();}
            }
        }
        if(existing!=null) return existing;
        if(table!=null && mc.level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) return table;
        Vec3 look=mc.player.getViewVector(1);
        for(BlockPos candidate:BlockPos.betweenClosed(feet.offset(-3,-1,-3),feet.offset(3,1,3))) {
            if(!validWorkbenchSite(mc,candidate)) continue;
            boolean visible=BlockInteraction.hit(mc,mc.player.getEyePosition(),candidate.below(),true)!=null;
            double dx=candidate.getX()+0.5-mc.player.getX(),dz=candidate.getZ()+0.5-mc.player.getZ();
            double distance=dx*dx+dz*dz;
            double score=distance+(visible?0:100)-(dx*look.x+dz*look.z)*0.7;
            if(score<best) {best=score;chosen=candidate.immutable();}
        }
        return chosen;
    }

    private static boolean clearInteraction(Minecraft mc,Vec3 eyes,BlockPos block,Vec3 point) {
        return BlockInteraction.hit(mc,eyes,block,table!=null && block.equals(table.below()))!=null;
    }

    private static boolean approachWorkbench(Minecraft mc,BlockPos block,Vec3 point) {
        if(clearInteraction(mc,mc.player.getEyePosition(),block,point)) {
            workbenchStand=workbenchApproach=null;return true;
        }
        lastLookTarget=null;
        if(block.equals(workbenchApproach) && workbenchStand!=null && ticks-workbenchApproachSince<160) {
            LockOnTravel.walk(mc,workbenchStand);
            detail="Approaching selected workbench face";return false;
        }
        workbenchApproach=block.immutable();workbenchApproachSince=ticks;
        BlockPos stand=null;
        double best=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(table.offset(-2,-1,-2),table.offset(2,1,2))) {
            if(p.equals(table) || !Navigator.passable(mc.level,p)
                || !Navigator.passable(mc.level,p.above())
                || !mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)) continue;
            Vec3 eyes=Vec3.atBottomCenterOf(p).add(0,mc.player.getEyeHeight(),0);
            if(!clearInteraction(mc,eyes,block,point)) continue;
            double distance=mc.player.position().distanceToSqr(Vec3.atBottomCenterOf(p));
            if(distance<best) {best=distance;stand=p.immutable();}
        }
        if(stand!=null) {
            workbenchStand=stand;
            LockOnTravel.walk(mc,stand);
            detail="Walking to a clear view of the workbench/floor";
        } else {
            release(mc);
            if(!hasTable(mc)) {
                rejectedSites.add(table);
                table=findWorkbenchSite(mc);
                stationAttempts=0;
            }
            detail="Looking for an unobstructed workbench approach";
        }
        return false;
    }

    private static void scoutForTree(Minecraft mc) {
        if(scoutTarget!=null) {
            if(LockOnTravel.walk(mc,scoutTarget)) scoutTarget=null;
            else if(ticks-scoutSince<240) {
                detail="Searching loaded trees within 32 chunks; walking straight "+scoutHeading;
                return;
            } else {
                // Only turn after a blocked destination has had time to recover.
                scoutHeading=scoutHeading.getClockWise();scoutTarget=null;
            }
        }
        BlockPos from=mc.player.blockPosition();
        if(scoutHeading==null) scoutHeading=facing;
        for(int length:new int[]{12,8,4,2}) {
            BlockPos ahead=from.relative(scoutHeading,length);
            if(!mc.level.hasChunk(ahead.getX()>>4,ahead.getZ()>>4)) continue;
            for(int dy=2;dy>=-3;dy--) {
                BlockPos p=ahead.offset(0,dy,0);
                if(Navigator.passable(mc.level,p) && Navigator.passable(mc.level,p.above())
                    && mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)) {
                    scoutTarget=p;scoutSince=ticks;
                    LockOnTravel.walk(mc,p);
                    detail="Continuing straight tree search "+scoutHeading;
                    return;
                }
            }
        }
        if(ticks%40==0) scoutHeading=scoutHeading.getClockWise();
        release(mc);
        detail="Searching loaded chunks; finding a supported scouting step";
    }

    private static boolean hasTable(Minecraft mc){
        return table!=null && mc.level!=null
            && mc.level.getBlockState(table).is(Blocks.CRAFTING_TABLE);
    }

    private static boolean hasPickaxe(LocalPlayer p){
        for(int i=0;i<36;i++){
            ItemStack tool=p.getInventory().getItem(i);
            if(tool.is(ItemTags.PICKAXES)
                && (!tool.isDamageableItem()
                    || tool.getMaxDamage()-tool.getDamageValue()>0)) return true;
        }
        return false;
    }

    private static int count(LocalPlayer p,Item item){
        int n=0;
        for(int i=0;i<36;i++){
            ItemStack s=p.getInventory().getItem(i);
            if(s.is(item))n+=s.getCount();
        }
        return n;
    }

    private static int countTag(LocalPlayer p,net.minecraft.tags.TagKey<Item> tag){
        int n=0;
        for(int i=0;i<36;i++){
            ItemStack s=p.getInventory().getItem(i);
            if(s.is(tag))n+=s.getCount();
        }
        return n;
    }

    private static Item findItem(LocalPlayer p,net.minecraft.tags.TagKey<Item> tag){
        for(int i=0;i<36;i++){
            ItemStack s=p.getInventory().getItem(i);
            if(s.is(tag))return s.getItem();
        }
        return null;
    }

    private static int hotbarSlot(LocalPlayer player,Item item){
        for(int i=0;i<9;i++)if(player.getInventory().getItem(i).is(item))return i;
        return -1;
    }

    private static void moveItemToHotbar(Minecraft mc,Item item) {
        if(ticks-lastClick<3)return;
        if(!(mc.player.containerMenu instanceof InventoryMenu))return;
        int source=-1, destination=8;
        for(int i=9;i<36;i++)
            if(mc.player.getInventory().getItem(i).is(item)){source=i;break;}
        if(source<0)return;
        for(int i=0;i<9;i++)
            if(mc.player.getInventory().getItem(i).isEmpty()){destination=i;break;}
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
            source,destination,ContainerInput.SWAP,mc.player);
        lastClick=ticks;
    }

    private static void startCraft(boolean tableMenu,Item result,List<Ingredient> ingredients){
        recipe=new RecipeJob(tableMenu,result,ingredients,ticks,-1);
        detail="Crafting "+result+" (0/"+ingredients.size()+" ingredients)";
    }

    private static int outputCount(LocalPlayer player,Item output) {
        return output == Items.OAK_PLANKS
            ? countTag(player,ItemTags.PLANKS) : count(player,output);
    }

    private static boolean matchesOutput(ItemStack stack,Item expected) {
        return expected == Items.OAK_PLANKS
            ? stack.is(ItemTags.PLANKS) : stack.is(expected);
    }

    private static void failCraft(Minecraft mc,String why) {
        RecipeJob failed=recipe;
        boolean touchedOwnGrid=failed!=null && failed.placedOwnIngredients;
        boolean grabbedSource=failed!=null && failed.action>0;
        recipe=null;
        cleaningRecipe=touchedOwnGrid || grabbedSource;
        cleaningTicks=ticks;
        detail=cleaningRecipe
            ? "Recipe failed: "+why+"; returning bot ingredients to inventory"
            : "Recipe failed: "+why+"; checking wood/planks/sticks";
        if(!cleaningRecipe) {
            // Do not modify an occupied grid that belonged to the player.
            step=Step.WAIT;
        }
    }

    private static void cleanupCraftingGrid(Minecraft mc) {
        if(ticks-lastClick<3) return;
        if(ticks-cleaningTicks>200) {
            cleaningRecipe=false;
            step=Step.WAIT;
            detail="Craft grid could not be recovered; clear it manually";
            closeTable(mc);
            return;
        }
        AbstractContainerMenu menu=mc.player.containerMenu;
        boolean tableMenu=menu instanceof CraftingMenu;
        if(!tableMenu && !(menu instanceof InventoryMenu)) {
            cleaningRecipe=false;
            openedTable=false;
            step=Step.WOOD;
            return;
        }
        int firstInventorySlot=tableMenu?10:9;
        if(!menu.getCarried().isEmpty()) {
            for(int i=firstInventorySlot;i<menu.slots.size();i++) {
                if(menu.getSlot(i).getItem().isEmpty()
                    && menu.getSlot(i).mayPlace(menu.getCarried())) {
                    click(mc,i,0,ContainerInput.PICKUP);
                    detail="Recovering crafting cursor";
                    return;
                }
            }
            step=Step.WAIT;
            detail="Inventory full: cannot return crafting cursor safely";
            return;
        }
        int endSlot=tableMenu?9:4;
        for(int i=1;i<=endSlot;i++) {
            if(!menu.getSlot(i).getItem().isEmpty()) {
                click(mc,i,0,ContainerInput.QUICK_MOVE);
                detail="Returning unused recipe ingredients to inventory";
                return;
            }
        }
        cleaningRecipe=false;
        if(tableMenu) closeTable(mc);
        step=Step.WOOD;
        detail="Recipe materials recovered; checking for more wood/sticks";
    }

    private static void craftTick(Minecraft mc) {
        if(ticks-lastClick<3)return;
        RecipeJob job=recipe;
        if(job==null) return;
        if(ticks-job.createdTick>260) {
            failCraft(mc,"no craft completion in 13 seconds");
            return;
        }
        AbstractContainerMenu menu=mc.player.containerMenu;
        if(job.table && !(menu instanceof CraftingMenu)) {
            failCraft(mc,"crafting table menu closed");
            return;
        }
        if(!job.table && !(menu instanceof InventoryMenu)) {
            failCraft(mc,"inventory menu unavailable");
            return;
        }
        if(job.startingCount<0)
            job.startingCount=outputCount(mc.player,job.output);

        if(job.resultClickedAt>=0) {
            if(outputCount(mc.player,job.output)>job.startingCount) {
                recipe=null;
                recipes++;
                detail="Crafted "+job.output+"; verifying next materials";
                if(job.table) closeTable(mc);
            } else if(ticks-job.resultClickedAt>50) {
                failCraft(mc,"crafted output did not enter inventory");
            }
            return;
        }

        // An input grid that already contained other items must not be
        // overwritten. Recover only grids we have started touching.
        if(job.ingredient==0 && job.action==0) {
            for(Ingredient ingredient:job.ingredients) {
                if(!menu.getSlot(ingredient.slot()).getItem().isEmpty()) {
                    failCraft(mc,"crafting grid not empty");
                    return;
                }
            }
        }

        if(job.ingredient<job.ingredients.size()) {
            Ingredient ingredient=job.ingredients.get(job.ingredient);
            if(job.action==0) {
                job.source=findSlot(menu,ingredient.item(),job.table?10:9);
                if(job.source<0) {
                    failCraft(mc,"missing "+ingredient.item()+" from inventory");
                    return;
                }
                click(mc,job.source,0,ContainerInput.PICKUP);
                job.action=1;
                detail="Crafting "+job.output+": picking ingredient "
                    +(job.ingredient+1)+"/"+job.ingredients.size();
                return;
            }
            if(job.action==1) {
                if(menu.getCarried().isEmpty()) {
                    failCraft(mc,"inventory click did not pick up ingredient");
                    return;
                }
                click(mc,ingredient.slot(),1,ContainerInput.PICKUP);
                job.placedOwnIngredients=true;
                job.action=2;
                detail="Crafting "+job.output+": placing ingredient "
                    +(job.ingredient+1)+"/"+job.ingredients.size();
                return;
            }
            // Return the rest of the cursor stack to its inventory slot.
            // If the ingredient stack was exhausted, cursor is already empty.
            if(!menu.getCarried().isEmpty())
                click(mc,job.source,0,ContainerInput.PICKUP);
            job.action=0;
            job.ingredient++;
            return;
        }

        ItemStack output=menu.getSlot(0).getItem();
        if(output.isEmpty()) {
            if(++job.outputWait>55)
                failCraft(mc,"recipe output empty (check 3x3 slot layout)");
            else detail="Waiting for crafting output: "+job.output
                +" ("+job.outputWait+"/55)";
            return;
        }
        if(!matchesOutput(output,job.output)) {
            failCraft(mc,"unexpected recipe output "+output.getItem());
            return;
        }

        click(mc,0,0,ContainerInput.QUICK_MOVE);
        job.resultClickedAt=ticks;
        detail="Collecting crafted "+job.output+" from result slot";
    }

    private static int findSlot(AbstractContainerMenu menu,Item item,int start) {
        for(int i=start;i<menu.slots.size();i++){
            ItemStack s=menu.getSlot(i).getItem();
            if((item==null?s.is(ItemTags.PLANKS):s.is(item))&&s.getCount()>0)return i;
        }
        return -1;
    }

    private static void click(Minecraft mc,int slot,int button,ContainerInput kind) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
            slot,button,kind,mc.player);
        lastClick=ticks;
    }

    private static void closeTable(Minecraft mc){
        if(mc.player!=null && mc.player.containerMenu instanceof CraftingMenu)
            mc.player.closeContainer();
        if(mc.gui.screen()!=null) mc.gui.setScreen(null);
        openedTable=false;
    }

    private static void rotate(LocalPlayer player,Vec3 target){
        Vec3 d=target.subtract(player.getEyePosition());
        player.setYRot((float)Math.toDegrees(Math.atan2(d.z,d.x))-90);
        player.setXRot(Mth.clamp((float)-Math.toDegrees(
            Math.atan2(d.y,Math.hypot(d.x,d.z))),-90,90));
    }

    private static void moveForward(Minecraft mc){
        LockOnMovement.forward(mc,false);
    }

    private static void release(Minecraft mc){
        LockOnMovement.stop(mc);
    }
}
