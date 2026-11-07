package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared walking and reachable gate interactions for gathering and recovery. */
final class LockOnTravel {
    private static long gateUseTick = -100;
    private static BlockPos goal, waypoint, breaking;
    private static Vec3 lastPosition;
    private static int stagnant;
    private static long startedBreaking;
    private static java.util.Map<net.minecraft.world.item.Item,Integer> beforeBreaking;

    static void reset(Minecraft mc) {
        if(mc.gameMode!=null && breaking!=null) mc.gameMode.stopDestroyBlock();
        goal=waypoint=breaking=null;
        lastPosition=null;
        stagnant=0;
        LockOnMovement.stop(mc);
    }

    static void face(Minecraft mc, Vec3 target) {
        Vec3 d=target.subtract(mc.player.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(d.z,d.x))-90;
        float pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
        mc.player.setYRot(mc.player.getYRot()+net.minecraft.util.Mth.clamp(
            net.minecraft.util.Mth.wrapDegrees(yaw-mc.player.getYRot()),-25,25));
        mc.player.setXRot(mc.player.getXRot()+net.minecraft.util.Mth.clamp(pitch-mc.player.getXRot(),-15,15));
    }

    static boolean openGate(Minecraft mc) {
        long now=mc.level.getGameTime();
        if(now-gateUseTick>=0 && now-gateUseTick<10) return false;
        BlockPos feet=mc.player.blockPosition();
        for(BlockPos p:BlockPos.betweenClosed(feet.offset(-2,0,-2),feet.offset(2,1,2))) {
            var state=mc.level.getBlockState(p);
            if(!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN)) continue;
            BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),
                Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(p)) continue;
            face(mc,hit.getLocation());
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
            gateUseTick=now;
            return true;
        }
        return false;
    }

    static boolean walk(Minecraft mc, BlockPos target) {
        if(target==null || mc.player==null || mc.level==null || mc.gameMode==null) return false;
        if(!target.equals(goal)) {goal=target.immutable();waypoint=null;stagnant=0;}
        if(breaking!=null) {clearBlock(mc,breaking);return false;}
        Vec3 now=mc.player.position();
        if(lastPosition!=null && now.distanceToSqr(lastPosition)<0.0001) stagnant++;
        else stagnant=0;
        lastPosition=now;
        double dx=target.getX()+0.5-now.x,dz=target.getZ()+0.5-now.z;
        if(Math.hypot(dx,dz)<0.38 && Math.abs(target.getY()-now.y)<0.6) {
            waypoint=null;
            LockOnMovement.stop(mc);
            return true;
        }
        if(mc.player.isInWater()) {
            face(mc,new Vec3(target.getX()+0.5,mc.player.getEyeY(),target.getZ()+0.5));
            LockOnMovement.forward(mc,mc.player.getFoodData().getFoodLevel()>6);
            LockOnMovement.jump(mc,mc.player.getAirSupply()<200 || target.getY()>=mc.player.getY());
            return false;
        }
        if(openGate(mc)) {LockOnMovement.stop(mc);return false;}
        if(waypoint!=null && mc.player.onGround()
            && Math.hypot(waypoint.getX()+0.5-now.x,waypoint.getZ()+0.5-now.z)<0.38
            && Math.abs(waypoint.getY()-now.y)<0.6) waypoint=null;
        if(stagnant>35) {waypoint=null;stagnant=0;}
        if(waypoint==null && mc.player.blockPosition().equals(target)) waypoint=target;
        if(waypoint==null) {
            Navigator.Step step=Navigator.route(mc.level,mc.player,target.getX()+0.5,
                target.getY(),target.getZ()+0.5,true);
            if(step==null) {
                // Only a real ray-hit collision can trigger obstacle clearing.
                Vec3 end=mc.player.getEyePosition().add(new Vec3(dx,0,dz).normalize().scale(2.0));
                BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),end,
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
                if(hit.getType()==HitResult.Type.BLOCK) clearBlock(mc,hit.getBlockPos());
                else LockOnMovement.stop(mc);
                return false;
            }
            BlockPos obstruction=step.nextBlockToBreak(mc.level);
            if(obstruction!=null) {clearBlock(mc,obstruction);return false;}
            waypoint=step.position().immutable();
        }
        if(!Navigator.passable(mc.level,waypoint.above())) {clearBlock(mc,waypoint.above());return false;}
        if(!Navigator.passable(mc.level,waypoint)) {clearBlock(mc,waypoint);return false;}
        face(mc,new Vec3(waypoint.getX()+0.5,mc.player.getEyeY(),waypoint.getZ()+0.5));
        float wanted=(float)Math.toDegrees(Math.atan2(waypoint.getZ()+0.5-now.z,waypoint.getX()+0.5-now.x))-90;
        if(Math.abs(net.minecraft.util.Mth.wrapDegrees(wanted-mc.player.getYRot()))>40) {
            LockOnMovement.stop(mc);return false;
        }
        boolean jumping=mc.player.onGround() && waypoint.getY()>now.y+0.35;
        if(jumping && !Navigator.passable(mc.level,mc.player.blockPosition().above(2))) {
            clearBlock(mc,mc.player.blockPosition().above(2));return false;
        }
        LockOnMovement.forward(mc,mc.player.getFoodData().getFoodLevel()>6
            && mc.player.getHealth()>8 && Math.hypot(dx,dz)>3);
        LockOnMovement.jump(mc,jumping);
        return false;
    }

    static BlockPos standingPosition(Minecraft mc,BlockPos column) {
        for(int dy=0;dy>=-6;dy--) {
            BlockPos p=column.offset(0,dy,0);
            var floor=mc.level.getBlockState(p.below());
            if(floor.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return p;
            if(!floor.getCollisionShape(mc.level,p.below()).isEmpty()
                && floor.getFluidState().isEmpty()) return p;
        }
        return column;
    }

    private static void clearBlock(Minecraft mc,BlockPos p) {
        LockOnMovement.stop(mc);
        if(mc.level.getBlockState(p).isAir()) {
            if(breaking!=null) LockOnSurvival.waitForDrop(p,beforeBreaking);
            breaking=null;waypoint=null;
            mc.gameMode.stopDestroyBlock();
            return;
        }
        if(!equipForClearing(mc,p)) return;
        if(!Navigator.mineable(mc.level,mc.player,p)
            || p.equals(mc.player.blockPosition().below())
            || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p))>4.1) {
            breaking=null;waypoint=null;return;
        }
        BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(p),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(p)) {breaking=null;waypoint=null;return;}
        face(mc,Vec3.atCenterOf(p));
        if(breaking==null) {
            breaking=p.immutable();
            beforeBreaking=LockOnSurvival.inventoryCounts(mc.player);
            startedBreaking=mc.level.getGameTime();
            return;
        }
        if(mc.level.getGameTime()-startedBreaking<3) return;
        if(mc.level.getGameTime()-startedBreaking>240) {
            mc.gameMode.stopDestroyBlock();breaking=null;waypoint=null;return;
        }
        mc.gameMode.continueDestroyBlock(p,hit.getDirection());
    }
    private static boolean equipForClearing(Minecraft mc,BlockPos p) {
        var state=mc.level.getBlockState(p);
        int best=-1;
        float speed=0;
        for(int slot=0;slot<36;slot++) {
            var stack=mc.player.getInventory().getItem(slot);
            if(state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            if(stack.getDestroySpeed(state)>speed) {best=slot;speed=stack.getDestroySpeed(state);}
        }
        if(best<0) {breaking=null;waypoint=null;return false;}
        if(best>=9) {
            if(mc.player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,best,8,
                    net.minecraft.world.inventory.ContainerInput.SWAP,mc.player);
            return false;
        }
        mc.player.getInventory().setSelectedSlot(best);
        return true;
    }

}
