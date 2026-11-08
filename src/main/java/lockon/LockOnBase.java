package lockon;

import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Back-at-home workflow. All actions are ordinary vanilla placement/use/
 * inventory inputs, never packet-spawned resources. Requires planks, stone
 * and fuel actually collected or supplied by the player.
 *
 * GUI menu ownership is tracked: only the containers opened by this class
 * may be manipulated. One inventory input per game tick at most.
 */
final class LockOnBase {
    private enum Stage { TABLE, CHEST, UPGRADE_TOOL, FURNACE, SMELT, UPGRADE_IRON, UPGRADE_COPPER, EQUIPMENT, FOOD, COMPACT, STORE, DONE }
    private static Stage stage = Stage.TABLE;
    private static BlockPos table, chest, furnace;
    private static int ticks, stageTicks, lastInput;
    private static boolean askedOpen;
    private static BlockPos openedPosition, interactionTarget;
    private static int openRequestedAt, interactionSince, storeSlot=-1, storeCount;
    private static final StationPlan placement=new StationPlan();
    private static CraftJob craftJob;
    private static int stored, smelted, compacted, placed;
    private static int smeltTimeout;
    private static String status = "";
    private static boolean missingMaterials;
    private static int openAttempts;
    private static int lastFurnaceOutput;

    private LockOnBase() {}

    static void reset() {
        stage=Stage.TABLE;
        equipmentRecipe=null;
        ticks=stageTicks=0;
        lastInput=-20;
        moveStage=0;
        table=chest=furnace=null;
        askedOpen=false;
        openedPosition=interactionTarget=null;
        openRequestedAt=interactionSince=0;
        storeSlot=-1;
        placement.reset();
        craftJob=null;
        stored=smelted=compacted=placed=0;
        smeltTimeout=0;
        status="";
        missingMaterials=false;
        openAttempts=0;
        lastFurnaceOutput=0;
    }

    static boolean hasFurnace(Minecraft mc) {
        return furnace!=null && mc.level!=null && mc.level.getBlockState(furnace).is(Blocks.FURNACE);
    }

    static boolean busyCrafting() { return craftJob!=null || moveStage>0; }

    static boolean managingContainer() { return askedOpen; }
    static String status() { return stage + ": " + status
        + " placed " + placed + " stored " + stored
        + " smelted " + smelted + " compacted " + compacted; }

    static void stop(Minecraft mc) {
        if(craftJob!=null && craftJob.inventory && (craftJob.progress>0 || craftJob.action>0))
            LockOnBootstrap.requestInventoryCleanup(mc);
        if (mc.player != null && askedOpen) close(mc);
        askedOpen=false;
        openedPosition=interactionTarget=null;
        openRequestedAt=interactionSince=0;
        storeSlot=-1;
        placement.reset();
        craftJob=null;
    }

    static boolean tick(Minecraft mc, BlockPos base, Direction direction) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return true;
        ticks++;
        if (ticks > 3600) {
            message(mc,"Base work timed out. "+status());
            stop(mc);
            return true;
        }
        // Stage-specific timeouts prevent a stalled GUI from trapping a bot.
        stageTicks++;
        if (stageTicks > 2400) advance(mc);
        // Keep utility stations in diagonal corners so none block the four
        // cardinal strip-mining lanes used by continuous survival mode.
        if(table==null) {
            BlockPos known=LockOnBootstrap.workbenchPosition();
            table=isBlock(mc,known,Blocks.CRAFTING_TABLE)?known:findStation(mc,base,Blocks.CRAFTING_TABLE);
        }
        if(chest==null) chest=findStation(mc,base,Blocks.CHEST);
        if(furnace==null) furnace=findStation(mc,base,Blocks.FURNACE);
        if (ticks - lastInput < 2) return false;

        switch (stage) {
            case TABLE -> {
                if (isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    placed++;advance(mc);return false;
                }
                StationPlan.Action action=placement.next(ticks,false,countInventory(mc.player,Items.CRAFTING_TABLE),craftJob!=null);
                if(action==StationPlan.Action.FAILED || table==null) {
                    status="No confirmed table placement; retaining supplies and skipping this station";
                    missingMaterials=true;advance(mc);return false;
                }
                if(craftJob==null && action!=StationPlan.Action.CRAFT) {
                    if(action==StationPlan.Action.PLACE) place(mc,table,Items.CRAFTING_TABLE);
                    else status="Waiting for table placement confirmation";
                    return false;
                }
                // Craft a workbench from 4 wooden planks via the 2x2 player grid.
                Item plank = findPlank(mc.player,4);
                if (plank != null || craftJob!=null) {
                    if (craft(mc,plank,new int[]{1,2,3,4},true)) {
                        if(findHotbar(mc.player,Items.CRAFTING_TABLE)>=0) {
                            close(mc); craftJob=null;
                        }
                    }
                    return false;
                }
                status="No crafting table or four planks yet";
                missingMaterials=true;
                advance(mc);
            }
            case CHEST -> {
                if (isBlock(mc,chest,Blocks.CHEST)) {
                    placed++;advance(mc);return false;
                }
                StationPlan.Action action=placement.next(ticks,false,countInventory(mc.player,Items.CHEST),craftJob!=null);
                if(action==StationPlan.Action.FAILED || chest==null) {
                    status="No confirmed chest placement; retaining supplies and skipping this station";
                    missingMaterials=true;advance(mc);return false;
                }
                if(craftJob==null && action!=StationPlan.Action.CRAFT) {
                    if(action==StationPlan.Action.PLACE) place(mc,chest,Items.CHEST);
                    else status="Waiting for chest placement confirmation";
                    return false;
                }
                Item plank=findPlank(mc.player,8);
                if((plank!=null || craftJob!=null) && isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    if (open(mc,table,CraftingMenu.class)) {
                        if(craft(mc,plank,new int[]{1,2,3,4,6,7,8,9},false))
                            close(mc);
                    }
                    return false;
                }
                status="Chest requires 8 planks + crafting table";
                missingMaterials=true;
                advance(mc);
            }
            case UPGRADE_TOOL -> {
                if (hasTool(mc.player,Items.STONE_PICKAXE)
                    || hasTool(mc.player,Items.IRON_PICKAXE)
                    || hasTool(mc.player,Items.DIAMOND_PICKAXE)
                    || hasTool(mc.player,Items.NETHERITE_PICKAXE)) {
                    advance(mc);
                    return false;
                }
                if((craftJob!=null || countInventory(mc.player,Items.COBBLESTONE)>=3
                    && countInventory(mc.player,Items.STICK)>=2)
                    && isBlock(mc,table,Blocks.CRAFTING_TABLE)){
                    if(open(mc,table,CraftingMenu.class)){
                        if(craft(mc,Items.COBBLESTONE,new int[]{1,2,3},
                            Items.STICK,new int[]{5,8},false)){
                            close(mc);
                        }
                    }
                    return false;
                }
                status="Stone pickaxe requires 3 cobblestone and 2 sticks";
                advance(mc);
            }
            case FURNACE -> {
                if (isBlock(mc,furnace,Blocks.FURNACE)) {
                    placed++;advance(mc);return false;
                }
                StationPlan.Action action=placement.next(ticks,false,countInventory(mc.player,Items.FURNACE),craftJob!=null);
                if(action==StationPlan.Action.FAILED || furnace==null) {
                    status="No confirmed furnace placement; retaining supplies and skipping this station";
                    missingMaterials=true;advance(mc);return false;
                }
                if(craftJob==null && action!=StationPlan.Action.CRAFT) {
                    if(action==StationPlan.Action.PLACE) place(mc,furnace,Items.FURNACE);
                    else status="Waiting for furnace placement confirmation";
                    return false;
                }
                if((craftJob!=null || countInventory(mc.player,Items.COBBLESTONE)>=8)
                    && isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    if(open(mc,table,CraftingMenu.class)) {
                        if(craft(mc,Items.COBBLESTONE,new int[]{1,2,3,4,6,7,8,9},false))
                            close(mc);
                    }
                    return false;
                }
                status="Furnace requires 8 cobblestone + crafting table";
                missingMaterials=true;
                advance(mc);
            }
            case SMELT -> {
                if (!LockOnSettings.survivalSmelt()
                    || !isBlock(mc,furnace,Blocks.FURNACE)) {
                    advance(mc);return false;
                }
                if(!open(mc,furnace,FurnaceMenu.class)) return false;
                AbstractContainerMenu menu=mc.player.containerMenu;
                if(!(menu instanceof FurnaceMenu))return false;
                if(moveStage>0) {
                    moveStack(mc,-1,-1);
                    return false;
                }
                int start=menu.slots.size()-36;

                // Take any finished output first.
                if(!menu.getSlot(2).getItem().isEmpty()) {
                    input(mc,2,0,ContainerInput.QUICK_MOVE);
                    smelted++;
                    return false;
                }
                if(menu.getSlot(0).getItem().isEmpty()){
                    int src=findInventorySlot(menu,start,LockOnFood::rawMeat);
                    if(src<0) src=findInventorySlot(menu,start,s->SurvivalPolicy.shouldSmelt(itemId(s)));
                    if(src>=0) {
                        moveStack(mc,src,0);
                        return false;
                    }
                }
                if(menu.getSlot(1).getItem().isEmpty()){
                    int src=findInventorySlot(menu,start, s->
                        s.is(Items.COAL) || s.is(Items.CHARCOAL)
                            || s.is(Items.COAL_BLOCK) || s.is(ItemTags.PLANKS));
                    if(src>=0){
                        moveStack(mc,src,1);
                        return false;
                    }
                }

                boolean hasInput=!menu.getSlot(0).getItem().isEmpty();
                boolean hasFuel=!menu.getSlot(1).getItem().isEmpty()
                    || mc.level.getBlockState(furnace).getValue(net.minecraft.world.level.block.FurnaceBlock.LIT);
                if(!hasInput || !hasFuel) {
                    close(mc);
                    advance(mc);return false;
                }
                if(stageTicks>1800) {
                    close(mc);
                    advance(mc);
                }
            }
            case UPGRADE_IRON -> {
                if (hasTool(mc.player,Items.IRON_PICKAXE)
                    || hasTool(mc.player,Items.DIAMOND_PICKAXE)
                    || hasTool(mc.player,Items.NETHERITE_PICKAXE)) {
                    advance(mc);return false;
                }
                if((craftJob==null && countInventory(mc.player,Items.IRON_INGOT)<3)
                    || !isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    status="Iron upgrade requires 3 smelted iron ingots";
                    advance(mc);return false;
                }
                if(!open(mc,table,CraftingMenu.class))return false;
                if(craftJob==null && countInventory(mc.player,Items.STICK)<2) {
                    Item plank=findPlank(mc.player,2);
                    if(plank!=null) {
                        if(craft(mc,plank,new int[]{1,4},false))close(mc);
                        return false;
                    }
                    status="Iron upgrade waiting for wood planks to make sticks";
                    advance(mc);return false;
                }
                if(craft(mc,Items.IRON_INGOT,new int[]{1,2,3},
                    Items.STICK,new int[]{5,8},false))close(mc);
            }
            case UPGRADE_COPPER -> {
                if(hasTool(mc.player,Items.COPPER_PICKAXE)||hasTool(mc.player,Items.IRON_PICKAXE)
                    ||hasTool(mc.player,Items.DIAMOND_PICKAXE)||hasTool(mc.player,Items.NETHERITE_PICKAXE)) {
                    advance(mc);return false;
                }
                if(!isBlock(mc,table,Blocks.CRAFTING_TABLE)
                    || (craftJob==null && (countInventory(mc.player,Items.COPPER_INGOT)<3
                        ||countInventory(mc.player,Items.STICK)<2))) {advance(mc);return false;}
                if(!open(mc,table,CraftingMenu.class)) return false;
                if(craft(mc,Items.COPPER_INGOT,new int[]{1,2,3},Items.STICK,new int[]{5,8},false)) close(mc);
            }
            case EQUIPMENT -> {
                if(!isBlock(mc,table,Blocks.CRAFTING_TABLE)) {advance(mc);return false;}
                if(equipmentRecipe==null) equipmentRecipe=nextEquipment(mc);
                if(equipmentRecipe==null) {advance(mc);return false;}
                if(!open(mc,table,CraftingMenu.class)) return false;
                Equipment recipe=equipmentRecipe;
                if(craft(mc,recipe.material(),recipe.cells(),Items.STICK,recipe.sticks(),false)) {
                    close(mc);
                    equipmentRecipe=null;
                }
            }
            case FOOD -> {
                if(!isBlock(mc,table,Blocks.CRAFTING_TABLE)
                    || (craftJob==null && countInventory(mc.player,Items.WHEAT)<3)) {
                    advance(mc);return false;
                }
                if(!open(mc,table,CraftingMenu.class)) return false;
                if(craft(mc,Items.WHEAT,new int[]{1,2,3},false)) close(mc);
            }
            case COMPACT -> {
                if (!LockOnSettings.survivalCompact()
                    || !isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    advance(mc);return false;
                }
                Item ingot=findCompactionMaterial(mc.player);
                if(ingot==null && craftJob==null) {
                    close(mc);
                    advance(mc);return false;
                }
                if(!open(mc,table,CraftingMenu.class))return false;
                if(craft(mc,ingot,new int[]{1,2,3,4,5,6,7,8,9},false)) {
                    compacted++;
                    close(mc);
                }
            }
            case STORE -> {
                if (!LockOnSettings.survivalStore()
                    || !isBlock(mc,chest,Blocks.CHEST)) {
                    advance(mc);return false;
                }
                if(!open(mc,chest,ChestMenu.class))return false;
                AbstractContainerMenu menu=mc.player.containerMenu;
                if(!(menu instanceof ChestMenu))return false;
                if(storeSlot>=0) {
                    if(menu.getSlot(storeSlot).getItem().getCount()<storeCount) {stored++;storeSlot=-1;}
                    else if(ticks-lastInput>=20) {status="Storage transfer rejected or chest full";advance(mc);return false;}
                    else return false;
                }
                int from=menu.slots.size()-36;
                java.util.Map<Item,Integer> reserves=new java.util.HashMap<>();
                for(int i=from;i<menu.slots.size();i++){
                    ItemStack item=menu.getSlot(i).getItem();
                    if(item.isEmpty()) continue;
                    int retained=reserves.getOrDefault(item.getItem(),0);
                    if(keep(item) && retained<64) {
                        reserves.put(item.getItem(),retained+item.getCount());
                        continue;
                    }
                    // Only one shift-click every couple ticks. If chest is
                    // full the server refuses it, and this phase times out.
                    storeSlot=i;storeCount=item.getCount();
                    input(mc,i,0,ContainerInput.QUICK_MOVE);
                    return false;
                }
                close(mc);
                advance(mc);
            }
            case DONE -> {
                message(mc,"Base processing pass: "+status()
                    + (missingMaterials ? " (materials still needed; will retry)" : ""));
                stop(mc);return true;
            }
        }
        return false;
    }

    private static void advance(Minecraft mc) {
        equipmentRecipe=null;
        placement.reset();
        interactionTarget=null;
        storeSlot=-1;
        if (askedOpen)close(mc);
        craftJob=null;
        moveStage=0;
        stage=Stage.values()[Math.min(Stage.DONE.ordinal(),stage.ordinal()+1)];
        stageTicks=0;
        openAttempts=0;
    }

    private static boolean isBlock(Minecraft mc,BlockPos pos,Block type) {
        return pos!=null && mc.level.getBlockState(pos).is(type);
    }

    private static BlockPos findStation(Minecraft mc,BlockPos home,Block block) {
        BlockPos site=null;
        double best=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(home.offset(-8,-3,-8),home.offset(8,3,8))) {
            if(!mc.level.hasChunk(p.getX()>>4,p.getZ()>>4)) continue;
            if(mc.level.getBlockState(p).is(block)) return p.immutable();
            if(p.equals(table) || p.equals(chest) || p.equals(furnace)
                || p.getX()==home.getX() || p.getZ()==home.getZ()
                || !mc.level.getBlockState(p).canBeReplaced()
                || !mc.level.getBlockState(p).getFluidState().isEmpty()
                || !mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)
                || !mc.level.getBlockState(p.below()).getFluidState().isEmpty()
                || mc.level.getBlockState(p.below()).hasBlockEntity()
                || mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) continue;
            double score=p.distSqr(home);
            if(score<best) {best=score;site=p.immutable();}
        }
        return site;
    }

    private static net.minecraft.world.phys.BlockHitResult visibleHit(Minecraft mc,Vec3 eye,BlockPos block,Vec3 point) {
        boolean topOnly=point.y>block.getY()+0.9;
        return BlockInteraction.hit(mc,eye,block,topOnly);
    }

    private static BlockHitResult approach(Minecraft mc,BlockPos block,Vec3 point,BlockPos avoid) {
        var hit=visibleHit(mc,mc.player.getEyePosition(),block,point);
        if(hit==null || (avoid!=null && mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(avoid)))) {
            interactionTarget=null;
            BlockPos stand=null;double best=Double.MAX_VALUE;
            for(BlockPos p:BlockPos.betweenClosed(block.offset(-3,-1,-3),block.offset(3,2,3))) {
                if(p.equals(avoid) || !Navigator.passable(mc.level,p) || !Navigator.passable(mc.level,p.above())
                    || !mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP)) continue;
                Vec3 eye=Vec3.atBottomCenterOf(p).add(0,mc.player.getEyeHeight(),0);
                if(visibleHit(mc,eye,block,point)==null) continue;
                double score=mc.player.distanceToSqr(Vec3.atBottomCenterOf(p));
                if(score<best) {best=score;stand=p.immutable();}
            }
            if(stand!=null) LockOnTravel.walk(mc,stand);
            else LockOnMovement.stop(mc);
            status="Approaching a clear view of station/support";
            return null;
        }
        LockOnMovement.stop(mc);
        LockOnTravel.face(mc,hit.getLocation());
        if(!block.equals(interactionTarget)) {interactionTarget=block.immutable();interactionSince=ticks;}
        // Let camera rotation and held item synchronization reach the server.
        if(ticks-interactionSince<4) return null;
        Vec3 rayEnd=mc.player.getEyePosition().add(mc.player.getViewVector(1).scale(4.2));
        var aimed=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),rayEnd,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
        if(aimed.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !aimed.getBlockPos().equals(block)) return null;
        return hit;
    }

    private static boolean place(Minecraft mc,BlockPos pos,Item item) {
        LocalPlayer me=mc.player;
        if(!(me.containerMenu instanceof InventoryMenu)) {
            if(askedOpen) close(mc);
            return false;
        }
        if(!mc.level.getBlockState(pos).canBeReplaced()) {
            status="Station space occupied; skipping placement";advance(mc);return false;
        }
        int slot=findHotbar(me,item);
        if(slot<0) {moveItemToHotbar(mc,item);return false;}
        if(me.getInventory().getSelectedSlot()!=slot) {
            me.getInventory().setSelectedSlot(slot);interactionTarget=null;return false;
        }
        Vec3 point=new Vec3(pos.getX()+0.5,pos.getY()-0.01,pos.getZ()+0.5);
        BlockHitResult hit=approach(mc,pos.below(),point,pos);
        if(hit==null) return false;
        mc.gameMode.useItemOn(me,InteractionHand.MAIN_HAND,hit);
        placement.sent(ticks);
        lastInput=ticks;
        status="Placement sent; waiting for world confirmation";
        return false;
    }

    private static void moveItemToHotbar(Minecraft mc,Item item) {
        LocalPlayer player=mc.player;
        if (!(player.containerMenu instanceof InventoryMenu)) return;
        int inventorySlot=-1;
        for(int i=9;i<36;i++){
            if(player.getInventory().getItem(i).is(item)){
                inventorySlot=i;break;
            }
        }
        if(inventorySlot<0)return;
        int hotbar=8;
        for(int i=0;i<9;i++){
            if(player.getInventory().getItem(i).isEmpty()){hotbar=i;break;}
        }
        mc.gameMode.handleContainerInput(player.containerMenu.containerId,
            inventorySlot,hotbar,ContainerInput.SWAP,player);
        lastInput=ticks;
    }

    private static int findHotbar(LocalPlayer player,Item item) {
        for(int i=0;i<9;i++)
            if(player.getInventory().getItem(i).is(item))return i;
        return -1;
    }

    private static Item findPlank(LocalPlayer player,int amount) {
        int total=0;Item first=null;
        for(int i=0;i<36;i++) {
            ItemStack item=player.getInventory().getItem(i);
            if(item.is(ItemTags.PLANKS)) {total+=item.getCount();if(first==null)first=item.getItem();}
        }
        return total>=amount?first:null;
    }

    private static boolean hasTool(LocalPlayer player,Item item) {
        for(int i=0;i<36;i++) {
            ItemStack s=player.getInventory().getItem(i);
            if(s.is(item) && usable(s)) return true;
        }
        return false;
    }

    private static int countInventory(LocalPlayer player,Item item) {
        int sum=0;
        for(int i=0;i<36;i++){
            ItemStack stack=player.getInventory().getItem(i);
            if(stack.is(item))sum+=stack.getCount();
        }
        return sum;
    }

    private static Item findCompactionMaterial(LocalPlayer player){
        for(int i=0;i<36;i++){
            ItemStack s=player.getInventory().getItem(i);
            if(!s.isEmpty() && !s.is(Items.IRON_INGOT) && !s.is(Items.COAL)
                && !s.is(Items.DIAMOND) && SurvivalPolicy.canCompact(itemId(s),s.getCount()))
                return s.getItem();
        }
        return null;
    }

    private static String itemId(ItemStack stack){
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean open(Minecraft mc,BlockPos pos,Class<?> type) {
        if(askedOpen) {
            if(pos.equals(openedPosition) && type.isInstance(mc.player.containerMenu)) return true;
            if(ticks-openRequestedAt<40) return false;
            close(mc);
            if(++openAttempts>=3) {status="Station did not open; skipping after three attempts";advance(mc);}
            return false;
        }
        if(!(mc.player.containerMenu instanceof InventoryMenu)) return false;
        BlockHitResult hit=approach(mc,pos,Vec3.atCenterOf(pos),null);
        if(hit==null) return false;
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
        askedOpen=true;
        openedPosition=pos.immutable();
        openRequestedAt=lastInput=ticks;
        return false;
    }

    private static void close(Minecraft mc) {
        if(mc.player==null || !askedOpen)return;
        if(!(mc.player.containerMenu instanceof InventoryMenu)) mc.player.closeContainer();
        if(mc.gui.screen()!=null)mc.gui.setScreen(null);
        askedOpen=false;
        openedPosition=null;
        interactionTarget=null;
        lastInput=ticks;
    }

    private static void input(Minecraft mc,int slot,int button,ContainerInput kind){
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
            slot,button,kind,mc.player);
        lastInput=ticks;
    }

    // A safe two-click transfer of a single inventory stack into a furnace
    // slot, with delays for the server to acknowledge intermediate inventory.
    private static int moveFrom=-1,moveTo=-1,moveStage;
    private static void moveStack(Minecraft mc,int from,int to) {
        if(moveStage==0){
            moveFrom=from;moveTo=to;
            input(mc,moveFrom,0,ContainerInput.PICKUP);
            moveStage=1;
        } else if(moveStage==1){
            input(mc,moveTo,0,ContainerInput.PICKUP);
            moveStage=2;
        } else {
            if(!mc.player.containerMenu.getCarried().isEmpty())
                input(mc,moveFrom,0,ContainerInput.PICKUP);
            moveStage=0;
        }
    }

    @FunctionalInterface
    private interface StackPredicate { boolean matches(ItemStack stack); }

    private static int findInventorySlot(AbstractContainerMenu menu,int from,StackPredicate p){
        for(int i=from;i<menu.slots.size();i++){
            ItemStack stack=menu.getSlot(i).getItem();
            if(!stack.isEmpty()&&p.matches(stack))return i;
        }
        return -1;
    }

    private static boolean keep(ItemStack s){
        if(s.is(ItemTags.PICKAXES) || s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)
            || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES)) return usable(s);
        if(s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS)||s.is(Items.STICK)
            ||s.is(Items.COAL)||s.is(Items.CHARCOAL)||s.is(Items.IRON_INGOT)||s.is(Items.COPPER_INGOT)
            ||LockOnFood.rawMeat(s)||s.is(Items.DIRT)
            ||s.is(Items.COBBLESTONE)||s.is(Items.WHEAT_SEEDS)||s.is(Items.WHEAT)
            ||s.is(Items.BEETROOT_SEEDS)||s.is(Items.IRON_HELMET)||s.is(Items.IRON_CHESTPLATE)
            ||s.is(Items.IRON_LEGGINGS)||s.is(Items.IRON_BOOTS)||LockOnFood.edible(s)) return true;
        if(s.is(Items.BREAD)||s.is(Items.BAKED_POTATO)||s.is(Items.APPLE)
            || s.is(Items.GOLDEN_CARROT)||s.is(Items.COOKED_BEEF)
            || s.is(Items.COOKED_CHICKEN)||s.is(Items.COOKED_PORKCHOP)
            || s.is(Items.COOKED_MUTTON)||s.is(Items.GOLDEN_APPLE))return true;
        return false;
    }

    private record Equipment(Item result, Item material, int[] cells, int[] sticks, int armorSlot) {}
    private static Equipment equipmentRecipe;
    private static final Equipment[] EQUIPMENT = {
        new Equipment(Items.IRON_CHESTPLATE,Items.IRON_INGOT,new int[]{1,3,4,5,6,7,8,9},new int[]{},6),
        new Equipment(Items.IRON_LEGGINGS,Items.IRON_INGOT,new int[]{1,2,3,4,6,7,9},new int[]{},7),
        new Equipment(Items.IRON_HELMET,Items.IRON_INGOT,new int[]{1,2,3,4,6},new int[]{},5),
        new Equipment(Items.IRON_BOOTS,Items.IRON_INGOT,new int[]{4,6,7,9},new int[]{},8),
        new Equipment(Items.IRON_SWORD,Items.IRON_INGOT,new int[]{2,5},new int[]{8},-1),
        new Equipment(Items.IRON_AXE,Items.IRON_INGOT,new int[]{1,2,4},new int[]{5,8},-1),
        new Equipment(Items.IRON_SHOVEL,Items.IRON_INGOT,new int[]{2},new int[]{5,8},-1),
        new Equipment(Items.IRON_HOE,Items.IRON_INGOT,new int[]{1,2},new int[]{5,8},-1),
        new Equipment(Items.COPPER_SWORD,Items.COPPER_INGOT,new int[]{2,5},new int[]{8},-1),
        new Equipment(Items.COPPER_AXE,Items.COPPER_INGOT,new int[]{1,2,4},new int[]{5,8},-1),
        new Equipment(Items.COPPER_SHOVEL,Items.COPPER_INGOT,new int[]{2},new int[]{5,8},-1),
        new Equipment(Items.COPPER_HOE,Items.COPPER_INGOT,new int[]{1,2},new int[]{5,8},-1)
    };

    private static boolean usable(ItemStack stack) {
        return !stack.isEmpty() && (!stack.isDamageableItem()
            || stack.getMaxDamage()-stack.getDamageValue()>0);
    }

    private static Equipment nextEquipment(Minecraft mc) {
        for(Equipment e:EQUIPMENT) {
            if(e.material()==Items.COPPER_INGOT && hasTool(mc.player,ironEquivalent(e.result()))) continue;
            if(e.armorSlot()>=0 && usable(mc.player.inventoryMenu.getSlot(e.armorSlot()).getItem())) continue;
            if(!hasTool(mc.player,e.result()) && countInventory(mc.player,e.material())>=e.cells().length+3
                && countInventory(mc.player,Items.STICK)>=e.sticks().length) return e;
        }
        return null;
    }

    private static Item ironEquivalent(Item item) {
        if(item==Items.COPPER_SWORD) return Items.IRON_SWORD;
        if(item==Items.COPPER_AXE) return Items.IRON_AXE;
        if(item==Items.COPPER_SHOVEL) return Items.IRON_SHOVEL;
        return Items.IRON_HOE;
    }

    static boolean equipArmor(Minecraft mc) {
        if(!(mc.player.containerMenu instanceof InventoryMenu)) return false;
        for(Equipment e:EQUIPMENT) {
            if(e.armorSlot()<0 || usable(mc.player.inventoryMenu.getSlot(e.armorSlot()).getItem())) continue;
            for(int i=9;i<45;i++) {
                ItemStack stack=mc.player.inventoryMenu.getSlot(i).getItem();
                if(!stack.is(e.result()) || !usable(stack)) continue;
                // Swap worn armor into the inventory before equipping the replacement.
                int worn=e.armorSlot();
                if(!mc.player.inventoryMenu.getSlot(worn).getItem().isEmpty()) {
                    boolean free=false;
                    for(int j=9;j<45;j++) if(mc.player.inventoryMenu.getSlot(j).getItem().isEmpty()) free=true;
                    if(!free) return false;
                    mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,worn,0,ContainerInput.QUICK_MOVE,mc.player);
                } else mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,i,0,ContainerInput.QUICK_MOVE,mc.player);
                return true;
            }
        }
        return false;
    }

    private record Ingredient(Item item,int grid) {}
    private static final class CraftJob {
        final java.util.List<Ingredient> ingredients;
        final boolean inventory;
        int progress, action, source=-1, outputWait, resultClicked=-1, resultBefore;
        Item resultItem;
        CraftJob(java.util.List<Ingredient> ingredients,boolean inventory){
            this.ingredients=ingredients;
            this.inventory=inventory;
        }
    }

    private static boolean craft(Minecraft mc,Item material,int[] cells,boolean portable){
        return craft(mc,material,cells,null,new int[0],portable);
    }

    /**
     * Vanilla crafting with 1-2 real ingredient types and menu clicks.
     * Supports wood/sticks/chests/furnaces and stone-pickaxe advancement.
     */
    private static boolean craft(Minecraft mc,Item first,int[] firstSlots,
                                 Item second,int[] secondSlots,boolean portable) {
        AbstractContainerMenu menu=mc.player.containerMenu;
        if(portable && !(menu instanceof InventoryMenu)) return false;
        if(!portable && !(menu instanceof CraftingMenu))return false;
        if(first==null && craftJob==null)return false;
        if(craftJob==null){
            java.util.List<Ingredient> ingredients=new java.util.ArrayList<>();
            Item firstIngredient=first.getDefaultInstance().is(ItemTags.PLANKS)?null:first;
            for(int i:firstSlots)ingredients.add(new Ingredient(firstIngredient,i));
            if(second!=null)
                for(int i:secondSlots)ingredients.add(new Ingredient(second,i));
            for(Ingredient ing:ingredients)
                if(!menu.getSlot(ing.grid()).getItem().isEmpty()){
                    status="Crafting grid is not empty; won't overwrite user items";
                    return false;
                }
            craftJob=new CraftJob(java.util.List.copyOf(ingredients),portable);
        }
        CraftJob job=craftJob;
        if(job.resultClicked>=0) {
            if(countInventory(mc.player,job.resultItem)>job.resultBefore && menu.getCarried().isEmpty()) {
                craftJob=null;return true;
            }
            if(ticks-job.resultClicked>60) {status="Crafted output not confirmed; skipping stage";advance(mc);}
            return false;
        }
        if(job.progress<job.ingredients.size()){
            Ingredient ing=job.ingredients.get(job.progress);
            if(job.action==0){
                job.source=findInventorySlot(menu,portable?9:10,
                    stack->ing.item()==null?stack.is(ItemTags.PLANKS):stack.is(ing.item()));
                if(job.source<0){
                    craftJob=null;
                    status="Missing crafting ingredient "+ing.item();
                    return false;
                }
                input(mc,job.source,0,ContainerInput.PICKUP);
                job.action=1;
            }else if(job.action==1){
                input(mc,ing.grid(),1,ContainerInput.PICKUP);
                job.action=2;
            }else{
                if(!menu.getCarried().isEmpty()) input(mc,job.source,0,ContainerInput.PICKUP);
                job.action=0;
                job.progress++;
            }
            return false;
        }
        if(menu.getSlot(0).getItem().isEmpty()){
            if(++job.outputWait>80){
                craftJob=null;
                status="Recipe not available for gathered ingredients";
            }
            return false;
        }
        job.resultItem=menu.getSlot(0).getItem().getItem();
        job.resultBefore=countInventory(mc.player,job.resultItem);
        job.resultClicked=ticks;
        input(mc,0,0,ContainerInput.QUICK_MOVE);
        return false;
    }

    private static void message(Minecraft mc,String s){
        if(mc.player!=null)mc.player.sendOverlayMessage(
            Component.literal("[LockOn Base] "+s));
    }
}
