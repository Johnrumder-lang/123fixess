package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Harvests mature nearby crops and berries, collects drops and replants crops. */
final class LockOnFood {
    private static BlockPos target, replant;
    private static Item seed;
    private static int ticks, actionTick, harvestTick;
    static void reset() {target=replant=null;seed=null;ticks=actionTick=harvestTick=0;}

    static boolean edible(ItemStack s) {
        return s.is(Items.BREAD)||s.is(Items.CARROT)||s.is(Items.POTATO)
            ||s.is(Items.BAKED_POTATO)||s.is(Items.APPLE)||s.is(Items.SWEET_BERRIES)
            ||s.is(Items.GOLDEN_CARROT)||s.is(Items.GOLDEN_APPLE)
            ||s.is(Items.COOKED_BEEF)||s.is(Items.COOKED_PORKCHOP)||s.is(Items.COOKED_MUTTON)
            ||s.is(Items.COOKED_CHICKEN)||s.is(Items.COOKED_COD)||s.is(Items.COOKED_SALMON);
    }
    static int count(Minecraft mc) {
        int count=0;
        for(int i=0;i<36;i++) if(edible(mc.player.getInventory().getItem(i)))
            count+=mc.player.getInventory().getItem(i).getCount();
        return count;
    }
    static boolean pending() {return replant!=null || target!=null;}

    static void tick(Minecraft mc) {
        ticks++;
        if(ticks-actionTick<5) return;
        if(replant!=null && !mc.level.getBlockState(replant).isAir()) replant=null;
        // Collect real drops before attempting to replant or consume them.
        for(Entity e:mc.level.entitiesForRendering()) {
            if(e instanceof ItemEntity item && e.distanceToSqr(mc.player)<100
                && (edible(item.getItem()) || item.getItem().is(Items.WHEAT)
                    || item.getItem().is(Items.WHEAT_SEEDS))) {
                LockOnTravel.walk(mc,e.blockPosition());
                return;
            }
        }
        if(replant!=null) {
            if(mc.player.distanceToSqr(Vec3.atCenterOf(replant))>9) {
                LockOnTravel.walk(mc,replant);return;
            }
            for(int i=0;i<36;i++) if(mc.player.getInventory().getItem(i).is(seed)) {
                if(i>=9) {
                    mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,i,8,ContainerInput.SWAP,mc.player);
                } else {
                    mc.player.getInventory().setSelectedSlot(i);
                    Vec3 hit=Vec3.atCenterOf(replant.below()).add(0,0.5,0);
                    LockOnTravel.face(mc,hit);
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit,Direction.UP,replant.below(),false));
                }
                actionTick=ticks;
                if(ticks-harvestTick>200) replant=null;
                return;
            }
            if(ticks-harvestTick<80) return;
            replant=null;
        }
        if(target==null && ticks%20==0) {
            BlockPos feet=mc.player.blockPosition();
            double best=Double.MAX_VALUE;
            for(BlockPos p:BlockPos.betweenClosed(feet.offset(-12,-2,-12),feet.offset(12,2,12))) {
                var state=mc.level.getBlockState(p);
                boolean ripe=state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
                ripe |= state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE)>=2;
                double distance=mc.player.distanceToSqr(Vec3.atCenterOf(p));
                if(ripe && distance<best) {target=p.immutable();best=distance;}
            }
        }
        if(target==null) {LockOnMovement.stop(mc);return;}
        if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target))>3.5) {
            LockOnTravel.walk(mc,target);return;
        }
        LockOnMovement.stop(mc);
        var state=mc.level.getBlockState(target);
        BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(target),
            ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {target=null;return;}
        LockOnTravel.face(mc,hit.getLocation());
        if(state.is(Blocks.SWEET_BERRY_BUSH)) {
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
        } else if(state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
            seed=state.is(Blocks.CARROTS)?Items.CARROT:state.is(Blocks.POTATOES)?Items.POTATO:
                state.is(Blocks.BEETROOTS)?Items.BEETROOT_SEEDS:Items.WHEAT_SEEDS;
            replant=target;
            mc.gameMode.startDestroyBlock(target,hit.getDirection());
            mc.gameMode.continueDestroyBlock(target,hit.getDirection());
            harvestTick=ticks;
        }
        target=null;
        actionTick=ticks;
    }
}
