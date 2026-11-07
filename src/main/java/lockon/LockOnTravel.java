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

    static void face(Minecraft mc, Vec3 target) {
        Vec3 d=target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(d.z,d.x))-90);
        mc.player.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
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
        double dx=target.getX()+0.5-mc.player.getX(), dz=target.getZ()+0.5-mc.player.getZ();
        if(Math.hypot(dx,dz)<0.65 && Math.abs(target.getY()-mc.player.getY())<0.85) {
            LockOnMovement.stop(mc);
            return true;
        }
        // The shared combat planner stops at melee range. Finish the last
        // short, supported approach so collection and home arrival can complete.
        if(Math.hypot(dx,dz)<1.6 && Math.abs(target.getY()-mc.player.getY())<0.5
            && Navigator.safeForward(mc.level,mc.player,dx,dz)) {
            face(mc,new Vec3(target.getX()+0.5,mc.player.getEyeY(),target.getZ()+0.5));
            LockOnMovement.forward(mc,false);
            return false;
        }
        if(openGate(mc)) {LockOnMovement.stop(mc);return false;}
        Navigator.Step step=Navigator.route(mc.level,mc.player,target.getX()+0.5,
            target.getY(),target.getZ()+0.5,false);
        if(step==null) {LockOnMovement.stop(mc);return false;}
        face(mc,new Vec3(step.position().getX()+0.5,mc.player.getEyeY(),step.position().getZ()+0.5));
        LockOnMovement.forward(mc,false);
        LockOnMovement.jump(mc,step.position().getY()>mc.player.getY()+0.35);
        return false;
    }
}
