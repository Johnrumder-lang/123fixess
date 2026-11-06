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
    private static BlockPos base, table, treeBlock, digTarget;
    private static ClientLevel world;
    private static Direction facing;
    private static int ticks, lastClick, lastDigTick, lastMoveTick, noProgress;
    private static int logsMined, recipes, woodSearches;
    private static String detail = "";
    private static RecipeJob recipe;
    private static boolean openedTable;
    private static int openTick;
    private static final int SEARCH_RADIUS = 13;

    private static final class RecipeJob {
        final boolean table;
        final Item output;
        final List<Ingredient> ingredients;
        int ingredient, action, outputWait;
        int source = -1;
        RecipeJob(boolean table, Item output, List<Ingredient> ingredients) {
            this.table=table; this.output=output; this.ingredients=ingredients;
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
        treeBlock=digTarget=null;
        ticks=0;
        lastClick=-20;
        lastDigTick=0;
        lastMoveTick=0;
        noProgress=0;
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
        digTarget=null;
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
            // A missing tree or temporary inventory delay must not produce a
            // fake "finished" message. Retry discovery periodically, while
            // leaving control with P at any time.
            if(ticks%200==0){
                woodSearches=0;
                treeBlock=null;
                step=Step.WOOD;
                detail="Retrying wood and tool progression";
            }
            return false;
        }
        if(ticks%40==0) {
            // Keep the status visible during a long wood-gathering/crafting run.
            player.sendOverlayMessage(Component.literal("[LockOn Auto] "+status()));
        }
        if(recipe!=null) {
            craftTick(mc);
            return false;
        }
        if(digTarget!=null) {
            if(mc.level.getBlockState(digTarget).isAir()){
                digTarget=null;
                logsMined++;
                detail="Wood collected "+logsMined+" logs";
            } else if(ticks-lastDigTick>180){
                mc.gameMode.stopDestroyBlock();
                digTarget=null;
                step=Step.WAIT;
                detail="Tree breaking timed out or server protected";
                release(mc);
                return false;
            }else{
                mc.gameMode.continueDestroyBlock(digTarget,Direction.UP);
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
                    if(plank!=null && count(player,plank)>=4){
                        startCraft(false,Items.CRAFTING_TABLE,
                            List.of(new Ingredient(plank,1),new Ingredient(plank,2),
                                new Ingredient(plank,3),new Ingredient(plank,4)));
                    } else {
                        detail="Not enough planks for crafting table";
                        step=Step.WAIT;
                    }
                }
            }
            case STICKS -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank!=null && count(player,plank)>=2){
                    startCraft(false,Items.STICK,
                        List.of(new Ingredient(plank,1),new Ingredient(plank,3)));
                } else {detail="Not enough planks for sticks";step=Step.WAIT;}
            }
            case PICKAXE -> {
                Item plank=findItem(player,ItemTags.PLANKS);
                if(plank==null || count(player,plank)<3) {
                    detail="Not enough planks for wooden pickaxe";
                    step=Step.WAIT;
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
        digTarget=treeBlock;
        lastDigTick=ticks;
        mc.gameMode.startDestroyBlock(treeBlock,Direction.UP);
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
                    double d=player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
                    if(d<best){best=d;closest=p;}
                }
            }
        }
        return closest;
    }

    private static boolean naturalTree(ClientLevel world,BlockPos p) {
        // Avoid stripping logs from an unrelated wooden house.
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            for(int dy=0;dy<=4;dy++){
                if(world.getBlockState(p.offset(dx,dy,dz)).is(BlockTags.LEAVES))
                    return true;
            }
        return false;
    }

    private static void placeWorkbench(Minecraft mc,LocalPlayer player){
        if(hasTable(mc))return;
        if(!mc.level.getBlockState(table).isAir()
            || mc.level.getBlockState(table.below()).isAir()){
            detail="Base workbench position obstructed; reposition P on flat ground";
            step=Step.WAIT;
            return;
        }
        int slot=hotbarSlot(player,Items.CRAFTING_TABLE);
        if(slot<0){
            moveItemToHotbar(mc,Items.CRAFTING_TABLE);
            detail="Moving crafted table into hotbar";
            return;
        }
        double d=player.getEyePosition().distanceTo(Vec3.atCenterOf(table));
        if(d>4.15){
            double dx=table.getX()+0.5-player.getX();
            double dz=table.getZ()+0.5-player.getZ();
            if(!Navigator.safeForward(mc.level,player,dx,dz)){
                detail="Can't safely reach base workbench site";
                step=Step.WAIT;release(mc);return;
            }
            rotate(player,new Vec3(table.getX()+0.5,player.getEyeY(),table.getZ()+0.5));
            moveForward(mc);
            return;
        }
        release(mc);
        player.getInventory().setSelectedSlot(slot);
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(table.below()).add(0,0.5,0),
                Direction.UP,table.below(),false));
        detail="Placing crafting table at base";
    }

    private static boolean openWorkbench(Minecraft mc,LocalPlayer player) {
        if(player.containerMenu instanceof CraftingMenu)return true;
        if(openedTable) {
            if(ticks-openTick>50) {
                openedTable=false;
                step=Step.WAIT;
                detail="Crafting table did not open";
            }
            return false;
        }
        if(player.getEyePosition().distanceTo(Vec3.atCenterOf(table))>4.3){
            double dx=table.getX()+0.5-player.getX(),dz=table.getZ()+0.5-player.getZ();
            if(!Navigator.safeForward(mc.level,player,dx,dz)){
                step=Step.WAIT;detail="Cannot approach crafting table";
                return false;
            }
            rotate(player,new Vec3(table.getX()+0.5,player.getEyeY(),table.getZ()+0.5));
            moveForward(mc);
            return false;
        }
        release(mc);
        mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(table),
                Direction.UP,table,false));
        openedTable=true;
        openTick=ticks;
        detail="Opening crafting table";
        return false;
    }

    private static boolean hasTable(Minecraft mc){
        return table!=null && mc.level!=null
            && mc.level.getBlockState(table).is(Blocks.CRAFTING_TABLE);
    }

    private static boolean hasPickaxe(LocalPlayer p){
        for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(ItemTags.PICKAXES))
            return true;
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
        recipe=new RecipeJob(tableMenu,result,ingredients);
        detail="Crafting "+result+" from inventory";
    }

    private static void craftTick(Minecraft mc) {
        if(ticks-lastClick<2)return;
        RecipeJob job=recipe;
        AbstractContainerMenu menu=mc.player.containerMenu;
        if(job.table && !(menu instanceof CraftingMenu)) {
            recipe=null;step=Step.WAIT;detail="Crafting table closed";return;
        }
        if(!job.table && !(menu instanceof InventoryMenu)) {
            recipe=null;step=Step.WAIT;detail="Player inventory not available";return;
        }
        // Crafting grids must be empty; don't overwrite the player's own work.
        if(job.ingredient==0 && job.action==0) {
            for(Ingredient ingredient:job.ingredients){
                if(!menu.getSlot(ingredient.slot()).getItem().isEmpty()){
                    recipe=null;
                    step=Step.WAIT;
                    detail="Crafting grid has other items; clear it";
                    return;
                }
            }
        }
        if(job.ingredient < job.ingredients.size()){
            Ingredient ingredient=job.ingredients.get(job.ingredient);
            if(job.action==0){
                job.source=findSlot(menu,ingredient.item(),job.table?10:9);
                if(job.source<0){
                    recipe=null;step=Step.WAIT;
                    detail="No recipe ingredient "+ingredient.item();
                    return;
                }
                click(mc,job.source,0,ContainerInput.PICKUP);
                job.action=1;
                return;
            }
            if(job.action==1){
                click(mc,ingredient.slot(),1,ContainerInput.PICKUP);
                job.action=2;
                return;
            }
            click(mc,job.source,0,ContainerInput.PICKUP);
            job.action=0;
            job.ingredient++;
            return;
        }
        if(menu.getSlot(0).getItem().isEmpty()) {
            if(++job.outputWait>60){
                recipe=null;
                step=Step.WAIT;
                detail="Recipe result did not appear. Need correct ingredients.";
            }
            return;
        }
        click(mc,0,0,ContainerInput.QUICK_MOVE);
        recipe=null;
        recipes++;
        detail="Crafted "+job.output;
        if(job.table) {
            closeTable(mc);
        }
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
        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private static void release(Minecraft mc){
        if(mc==null)return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }
}
