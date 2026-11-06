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
    private enum Stage { TABLE, CHEST, FURNACE, SMELT, COMPACT, STORE, DONE }
    private static Stage stage = Stage.TABLE;
    private static BlockPos table, chest, furnace;
    private static int ticks, stageTicks, lastInput;
    private static boolean askedOpen;
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
        ticks=stageTicks=0;
        lastInput=-20;
        moveStage=0;
        table=chest=furnace=null;
        askedOpen=false;
        craftJob=null;
        stored=smelted=compacted=placed=0;
        smeltTimeout=0;
        status="";
        missingMaterials=false;
        openAttempts=0;
        lastFurnaceOutput=0;
    }

    static boolean managingContainer() { return askedOpen; }
    static String status() { return stage + ": " + status
        + " placed " + placed + " stored " + stored
        + " smelted " + smelted + " compacted " + compacted; }

    static void stop(Minecraft mc) {
        if (mc.player != null && askedOpen) close(mc);
        askedOpen=false;
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
        if (table == null) table = base.relative(direction.getOpposite(),2);
        if (chest == null) chest = base.relative(direction.getClockWise(),2);
        if (furnace == null) furnace = base.relative(direction.getCounterClockWise(),2);
        if (ticks - lastInput < 2) return false;

        switch (stage) {
            case TABLE -> {
                if (isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
                    placed++;advance(mc);return false;
                }
                if (place(mc,table,Items.CRAFTING_TABLE)) {
                    advance(mc);return false;
                }
                // Craft a workbench from 4 wooden planks via the 2x2 player grid.
                Item plank = findPlank(mc.player,4);
                if (plank != null) {
                    if (craft(mc,plank,new int[]{1,2,3,4},true)) {
                        if(findHotbar(mc.player,Items.CRAFTING_TABLE)>=0) {
                            close(mc); craftJob=null;
                        }
                    }
                    return false;
                }
                status="No crafting table or 4 planks";
                missingMaterials=true;
                advance(mc);
            }
            case CHEST -> {
                if (isBlock(mc,chest,Blocks.CHEST)) {
                    placed++;advance(mc);return false;
                }
                if (place(mc,chest,Items.CHEST)) {
                    advance(mc);return false;
                }
                Item plank=findPlank(mc.player,8);
                if(plank!=null && isBlock(mc,table,Blocks.CRAFTING_TABLE)) {
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
            case FURNACE -> {
                if (isBlock(mc,furnace,Blocks.FURNACE)) {
                    placed++;advance(mc);return false;
                }
                if (place(mc,furnace,Items.FURNACE)) {
                    advance(mc);return false;
                }
                if(countInventory(mc.player,Items.COBBLESTONE)>=8
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
                    int src=findInventorySlot(menu,start, s->
                        SurvivalPolicy.shouldSmelt(itemId(s)));
                    if(src>=0) {
                        moveStack(mc,src,0);
                        return false;
                    }
                }
                if(menu.getSlot(1).getItem().isEmpty()){
                    int src=findInventorySlot(menu,start, s->
                        s.is(Items.COAL) || s.is(Items.CHARCOAL)
                            || s.is(Items.COAL_BLOCK));
                    if(src>=0){
                        moveStack(mc,src,1);
                        return false;
                    }
                }

                boolean hasInput=!menu.getSlot(0).getItem().isEmpty();
                boolean hasFuel=!menu.getSlot(1).getItem().isEmpty();
                if(!hasInput || !hasFuel) {
                    close(mc);
                    advance(mc);return false;
                }
                if(stageTicks>1800) {
                    close(mc);
                    advance(mc);
                }
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
                int from=menu.slots.size()-36;
                for(int i=from;i<menu.slots.size();i++){
                    ItemStack item=menu.getSlot(i).getItem();
                    if(item.isEmpty() || keep(item))continue;
                    // Only one shift-click every couple ticks. If chest is
                    // full the server refuses it, and this phase times out.
                    input(mc,i,0,ContainerInput.QUICK_MOVE);
                    stored++;
                    return false;
                }
                close(mc);
                advance(mc);
            }
            case DONE -> {
                message(mc,"Base processing complete: "+status()
                    + (missingMaterials ? " (some stations need materials)" : ""));
                stop(mc);return true;
            }
        }
        return false;
    }

    private static void advance(Minecraft mc) {
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

    private static boolean place(Minecraft mc,BlockPos pos,Item item) {
        LocalPlayer me=mc.player;
        if(pos==null || me.getEyePosition().distanceTo(Vec3.atCenterOf(pos))>4.3)
            return false;
        if(!mc.level.getBlockState(pos).isAir())return false;
        BlockPos floor=pos.below();
        if(mc.level.getBlockState(floor).isAir()
            || !mc.level.getBlockState(floor).getFluidState().isEmpty())return false;
        int slot=findHotbar(me,item);
        if(slot<0) {
            // Crafted outputs can arrive in the main inventory, not on
            // the hotbar. A normal SWAP click moves them into a hotbar slot.
            moveItemToHotbar(mc,item);
            return false;
        }
        me.getInventory().setSelectedSlot(slot);
        mc.gameMode.useItemOn(me,InteractionHand.MAIN_HAND,
            new BlockHitResult(new Vec3(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5),
                Direction.UP,floor,false));
        lastInput=ticks;
        return isBlock(mc,pos,Block.byItem(item));
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
        for(int i=0;i<36;i++){
            ItemStack item=player.getInventory().getItem(i);
            if(item.is(ItemTags.PLANKS) && item.getCount()>=amount)
                return item.getItem();
        }
        return null;
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
            if(!s.isEmpty() && SurvivalPolicy.canCompact(itemId(s),s.getCount()))
                return s.getItem();
        }
        return null;
    }

    private static String itemId(ItemStack stack){
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean open(Minecraft mc,BlockPos pos,Class<?> type) {
        if(type.isInstance(mc.player.containerMenu)) return true;
        if(askedOpen){
            if(ticks-lastInput<30)return false;
            // Server did not open the expected menu. Don't interact with
            // some other window, and move to the next station.
            if(mc.player.containerMenu instanceof InventoryMenu){
                askedOpen=false;
                if(++openAttempts>=3)advance(mc);
            } else {
                close(mc);
                if(++openAttempts>=3)advance(mc);
            }
            return false;
        }
        if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos))>4.3){
            advance(mc);return false;
        }
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        askedOpen=true;
        lastInput=ticks;
        return false;
    }

    private static void close(Minecraft mc) {
        if(mc.player==null)return;
        if(!(mc.player.containerMenu instanceof InventoryMenu)){
            mc.player.closeContainer();
        }
        if(mc.gui.screen()!=null)mc.gui.setScreen(null);
        askedOpen=false;
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
        if(s.is(ItemTags.PICKAXES) || s.is(ItemTags.SWORDS)) return true;
        if(s.is(Items.BREAD)||s.is(Items.BAKED_POTATO)||s.is(Items.APPLE)
            || s.is(Items.GOLDEN_CARROT)||s.is(Items.COOKED_BEEF)
            || s.is(Items.COOKED_CHICKEN)||s.is(Items.COOKED_PORKCHOP)
            || s.is(Items.COOKED_MUTTON)||s.is(Items.GOLDEN_APPLE))return true;
        return false;
    }

    private static final class CraftJob {
        final Item material;
        final int[] cells;
        final boolean inventory;
        int source=-1,progress,step;
        CraftJob(Item material,int[] cells,boolean inventory){
            this.material=material;this.cells=cells;this.inventory=inventory;
        }
    }

    /**
     * Craft one recipe using real slot inputs; one click per call. Requires
     * one inventory stack of 4, 8, or 9 ingredients, respectively. When the
     * result has been shifted into inventory, the job is complete.
     */
    private static boolean craft(Minecraft mc,Item material,int[] cells,boolean portable){
        AbstractContainerMenu menu=mc.player.containerMenu;
        if(portable && !(menu instanceof InventoryMenu)) return false;
        if(!portable && !(menu instanceof CraftingMenu))return false;
        if(material==null && craftJob==null)return false;
        if(craftJob==null){
            // Never overwrite existing crafting grids.
            for(int i:cells)if(!menu.getSlot(i).getItem().isEmpty())return false;
            int src=findInventorySlot(menu,portable?9:10,
                stack->stack.is(material)&&stack.getCount()>=cells.length);
            if(src<0)return false;
            craftJob=new CraftJob(material,cells,portable);
            craftJob.source=src;
        }
        CraftJob job=craftJob;
        if(job.progress==0){
            input(mc,job.source,0,ContainerInput.PICKUP);
            job.progress++;
            return false;
        }
        if(job.progress<=job.cells.length){
            input(mc,job.cells[job.progress-1],1,ContainerInput.PICKUP);
            job.progress++;
            return false;
        }
        if(job.progress==job.cells.length+1){
            input(mc,job.source,0,ContainerInput.PICKUP);
            job.progress++;
            return false;
        }
        if(job.progress==job.cells.length+2){
            input(mc,0,0,ContainerInput.QUICK_MOVE);
            job.progress++;
            return false;
        }
        craftJob=null;
        return true;
    }

    private static void message(Minecraft mc,String s){
        if(mc.player!=null)mc.player.sendOverlayMessage(
            Component.literal("[LockOn Base] "+s));
    }
}
