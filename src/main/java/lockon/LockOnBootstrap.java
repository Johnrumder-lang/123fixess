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
    private static BlockPos base, table, treeBlock, digTarget, pickupTarget;
    private static int pickupUntil;
    private static ClientLevel world;
    private static Direction facing;
    private static int ticks, lastClick, lastDigTick, lastMoveTick, noProgress;
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
    private static final int SEARCH_RADIUS = 13;

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
        treeBlock=digTarget=pickupTarget=null;
        pickupUntil=0;
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
        detail="Locating wood";
        step=Step.WOOD;
    }

    static String status() {
        return step+" logs="+logsMined+" recipes="+recipes
            +(detail.isBlank() ? "" : " — "+detail);
    }

    static boolean managingContainer() { return openedTable; }

    static void stop(Minecraft mc) {
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
        pickupTarget=null;
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
                pickupTarget=digTarget;
                pickupUntil=ticks+35;
                digTarget=null;
                logsMined++;
                detail="Log broken: walking over to collect drops";
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

        // Minecraft log drops land on the ground, not directly in the
        // player's inventory. Walk into the felled log location to actually
        // pick them up before trying to craft with nonexistent resources.
        if (pickupTarget != null) {
            if (ticks >= pickupUntil) {
                pickupTarget = null;
            } else {
                double dx=pickupTarget.getX()+0.5-player.getX();
                double dz=pickupTarget.getZ()+0.5-player.getZ();
                double d=Math.hypot(dx,dz);
                if(d > 0.85 && d < 4.0 && Navigator.safeForward(mc.level,player,dx,dz)) {
                    rotate(player,new Vec3(pickupTarget.getX()+0.5,
                        player.getEyeY(),pickupTarget.getZ()+0.5));
                    moveForward(mc);
                    detail="Collecting broken log on ground";
                    return false;
                }
                release(mc);
                pickupTarget=null;
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
                    if(plank!=null && count(player,plank)>=4){
                        startCraft(false,Items.CRAFTING_TABLE,
                            List.of(new Ingredient(plank,1),new Ingredient(plank,2),
                                new Ingredient(plank,3),new Ingredient(plank,4)));
                    } else {
                        detail="Need more wood for crafting table";
                        step=Step.WOOD;
                    }
                }
            }
            case STICKS -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank!=null && count(player,plank)>=2){
                    startCraft(false,Items.STICK,
                        List.of(new Ingredient(plank,1),new Ingredient(plank,3)));
                } else {detail="Need more planks for sticks";step=Step.WOOD;}
            }
            case PICKAXE -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank==null || count(player,plank)<3
                    || count(player,Items.STICK)<2) {
                    detail="Pickaxe missing planks or sticks: returning to wood gathering";
                    step=Step.WOOD;
                    return false;
                }
                if(!openWorkbench(mc,player))return false;
                startCraft(true,Items.WOODEN_PICKAXE,
                    List.of(new Ingredient(plank,1),new Ingredient(plank,2),
                        new Ingredient(plank,3),new Ingredient(Items.STICK,5),
                        new Ingredient(Items.STICK,8)));
            }
            case READY -> {return true;}
            case WAIT -> {}
        }
        return false;
    }

    private static void gatherWood(Minecraft mc,LocalPlayer player) {
        if(treeBlock==null || mc.level.getBlockState(treeBlock).isAir()
            || ticks%60==0){
            treeBlock=findTree(mc,player);
            aimedWood=null;
            woodSearches++;
        }
        if(treeBlock==null){
            release(mc);
            if(woodSearches>8) {
                step=Step.WAIT;
                detail="No natural tree within 13 blocks. Stand by a tree and restart P.";
            } else detail="Searching for a natural tree in loaded chunks";
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
            if(Math.abs(treeBlock.getY()-player.getY())>4.0
                || !Navigator.safeForward(mc.level,player,dx,dz)){
                treeBlock=null;
                detail="Tree path blocked; searching for an accessible tree";
                return;
            }
            rotate(player,new Vec3(treeBlock.getX()+0.5,player.getEyeY(),
                treeBlock.getZ()+0.5));
            moveForward(mc);
            detail="Walking to tree ("+(int)distance+" blocks)";
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
            detail="Tree log obscured; selecting a visible trunk block";
            treeBlock=null;
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
                for(int y=-2;y<=5;y++){
                    BlockPos p=new BlockPos(bx,playerPos.getY()+y,bz);
                    if(!mc.level.getBlockState(p).is(BlockTags.LOGS)
                        || !naturalTree(mc.level,p))continue;
                    double physical=player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
                    if(physical < 4.2*4.2) {
                        BlockHitResult hit=mc.level.clip(new ClipContext(
                            player.getEyePosition(),Vec3.atCenterOf(p),
                            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
                        if(hit.getType()!=HitResult.Type.BLOCK
                            || !hit.getBlockPos().equals(p))continue;
                    }
                    // Prefer lower trunk pieces so mining starts at the
                    // base and the dropped log falls close to the player.
                    double d=physical+2.0*Math.max(0,p.getY()-playerPos.getY());
                    if(d<best){best=d;closest=p;}
                }
            }
        }
        return closest;
    }

    private static boolean naturalTree(ClientLevel world,BlockPos p) {
        // Avoid stripping logs from an unrelated wooden house.
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            for(int dy=0;dy<=6;dy++){
                if(world.getBlockState(p.offset(dx,dy,dz)).is(BlockTags.LEAVES))
                    return true;
            }
        return false;
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
        if(!mc.level.getBlockState(table).isAir()
            || mc.level.getBlockState(table.below()).isAir()){
            detail="Cannot place workbench on obstructed or unsupported ground";
            step=Step.WAIT;
            release(mc);
            return;
        }
        int slot=hotbarSlot(player,Items.CRAFTING_TABLE);
        if(slot<0){
            moveItemToHotbar(mc,Items.CRAFTING_TABLE);
            detail="Moving crafted table to hotbar";
            return;
        }
        double d=player.getEyePosition().distanceTo(Vec3.atCenterOf(table));
        if(d>4.15){
            lastLookTarget=null;
            double dx=table.getX()+0.5-player.getX();
            double dz=table.getZ()+0.5-player.getZ();
            if(!Navigator.safeForward(mc.level,player,dx,dz)){
                detail="Path to workbench site blocked: cannot craft pickaxe yet";
                step=Step.WAIT;release(mc);return;
            }
            rotate(player,new Vec3(table.getX()+0.5,player.getEyeY(),table.getZ()+0.5));
            moveForward(mc);
            detail="Walking back to crafting-table location";
            return;
        }
        release(mc);
        player.getInventory().setSelectedSlot(slot);
        Vec3 hit=new Vec3(table.getX()+0.5,table.getY(),table.getZ()+0.5);
        if(!lookForInteraction(player,table.below(),hit)) {
            detail="Looking down to place crafting table";
            return;
        }
        if(ticks-lastStationUseTick<14)return;
        if(stationAttempts>=5) {
            step=Step.WAIT;
            detail="Workbench placement rejected (ground/protection/reach)";
            return;
        }
        BlockHitResult supportHit=mc.level.clip(new ClipContext(
            player.getEyePosition(),hit,
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(supportHit.getType()!=HitResult.Type.BLOCK
            || !supportHit.getBlockPos().equals(table.below())) {
            detail="Workbench placement: floor not visible from player";
            step=Step.WAIT;
            return;
        }
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,supportHit);
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
        if(player.getEyePosition().distanceTo(Vec3.atCenterOf(table))>4.0) {
            lastLookTarget=null;
            double dx=table.getX()+0.5-player.getX(),dz=table.getZ()+0.5-player.getZ();
            if(!Navigator.safeForward(mc.level,player,dx,dz)){
                step=Step.WAIT;
                detail="Workbench unreachable from current position";
                release(mc);
                return false;
            }
            rotate(player,new Vec3(table.getX()+0.5,player.getEyeY(),table.getZ()+0.5));
            moveForward(mc);
            detail="Walking to crafting table";
            return false;
        }
        release(mc);
        Vec3 target=Vec3.atCenterOf(table);
        if(!lookForInteraction(player,table,target)) {
            detail="Looking at crafting table to open it";
            return false;
        }
        if(ticks-lastStationUseTick<12)return false;
        BlockHitResult tableHit=mc.level.clip(new ClipContext(
            player.getEyePosition(),target,
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(tableHit.getType()!=HitResult.Type.BLOCK
            || !tableHit.getBlockPos().equals(table)) {
            detail="Workbench blocked by another block";
            step=Step.WAIT;
            return false;
        }
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,tableHit);
        openedTable=true;
        openTick=ticks;
        lastStationUseTick=ticks;
        detail="Opening crafting-table GUI";
        return false;
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
                    || tool.getMaxDamage()-tool.getDamageValue()>3)) return true;
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
        if(ticks-cleaningTicks>160) {
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
            if(s.is(item)&&s.getCount()>0)return i;
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
