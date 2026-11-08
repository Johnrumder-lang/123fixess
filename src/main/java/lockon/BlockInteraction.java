package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Probe reachable block faces, including visible edges around an occluded center. */
final class BlockInteraction {
    static BlockHitResult hit(Minecraft mc,Vec3 eye,BlockPos block,boolean topOnly) {
        BlockHitResult nearest=null;
        double best=4.25*4.25;
        for(Direction face:Direction.values()) {
            if(topOnly && face!=Direction.UP) continue;
            for(double a:new double[]{0.5,0.2,0.8}) for(double b:new double[]{0.5,0.2,0.8}) {
                double x=a,y=b,z=0.5;
                switch(face) {
                    case UP -> {x=a;y=0.999;z=b;}
                    case DOWN -> {x=a;y=0.001;z=b;}
                    case NORTH -> {x=a;y=b;z=0.001;}
                    case SOUTH -> {x=a;y=b;z=0.999;}
                    case WEST -> {x=0.001;y=a;z=b;}
                    case EAST -> {x=0.999;y=a;z=b;}
                }
                Vec3 end=new Vec3(block.getX()+x,block.getY()+y,block.getZ()+z);
                // Far targets cannot have any reachable sample; skip expensive rays.
                if(eye.distanceToSqr(end)>best+1) continue;
                var hit=mc.level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
                if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(block)
                    || (topOnly && hit.getDirection()!=Direction.UP)) continue;
                double distance=eye.distanceToSqr(hit.getLocation());
                if(distance<best) {best=distance;nearest=hit;}
            }
        }
        return nearest;
    }
}
