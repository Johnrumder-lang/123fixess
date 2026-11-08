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
    private static BlockPos goal, waypoint, breaking, shore, suffocationBlock;
    private static long shoreSince, suffocationSince;
    private static boolean suffocationStarted;
    private static final java.util.Set<BlockPos> rejectedShores=new java.util.HashSet<>();
    private static final ProgressWatch progress=new ProgressWatch();
    private static final java.util.Map<BlockPos,Long> rejectedSteps=new java.util.HashMap<>();
    private static Vec3 lastPosition;
    private static int stagnant;
    private static long startedBreaking;
    private static java.util.Map<net.minecraft.world.item.Item,Integer> beforeBreaking;

    static void reset(Minecraft mc) {
        if(mc.gameMode!=null && breaking!=null) mc.gameMode.stopDestroyBlock();
        finishSuffocation(mc);
        goal=waypoint=breaking=shore=null;
        rejectedShores.clear();
        gateUseTick=-100;
        progress.reset();rejectedSteps.clear();
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
        if(!target.equals(goal)) {goal=target.immutable();waypoint=null;stagnant=0;progress.reset();rejectedSteps.clear();}
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
            swimToShore(mc,target);
            return false;
        }
        shore=null;rejectedShores.clear();
        if(openGate(mc)) {LockOnMovement.stop(mc);return false;}
        if(waypoint!=null && mc.player.onGround()
            && Math.hypot(waypoint.getX()+0.5-now.x,waypoint.getZ()+0.5-now.z)<0.38
            && Math.abs(waypoint.getY()-now.y)<0.6) waypoint=null;
        long time=mc.level.getGameTime();
        rejectedSteps.entrySet().removeIf(e->time>=e.getValue());
        if(waypoint!=null && progress.stalled(waypoint,now.distanceTo(Vec3.atBottomCenterOf(waypoint)),time)) {
            rejectWaypoint(mc);LockOnMovement.stop(mc);
        }
        if(stagnant>35) {rejectWaypoint(mc);stagnant=0;}
        if(waypoint==null && rejectedSteps.isEmpty()) {
            double length=Math.hypot(dx,dz),reach=Math.min(3,length);
            if(length>0.01) {
                BlockPos direct=BlockPos.containing(now.x+dx/length*reach,now.y+0.08,now.z+dz/length*reach);
                if(Navigator.straightWalk(mc.level,mc.player,direct.getX()+0.5,target.getY(),direct.getZ()+0.5))
                    waypoint=direct;
            }
        }
        if(waypoint==null && mc.player.blockPosition().equals(target)) waypoint=target;
        if(waypoint==null) {
            Navigator.Step step=Navigator.route(mc.level,mc.player,target.getX()+0.5,
                target.getY(),target.getZ()+0.5,true,rejectedSteps.keySet());
            if(step==null) {
                Vec3 direction=new Vec3(dx,0,dz).normalize();
                for(double height:new double[]{0.15,1.2}) {
                    Vec3 start=mc.player.position().add(0,height,0),end=start.add(direction.scale(1.5));
                    BlockHitResult hit=mc.level.clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
                    if(hit.getType()==HitResult.Type.BLOCK && Navigator.mineable(mc.level,mc.player,hit.getBlockPos())) {
                        clearBlock(mc,hit.getBlockPos());return false;
                    }
                }
                // A stable lateral step can escape a collision at a grid corner.
                for(int sign:new int[]{1,-1}) {
                    double sideX=-direction.z*sign,sideZ=direction.x*sign;
                    if(Navigator.safeForward(mc.level,mc.player,sideX,sideZ)) {
                        waypoint=BlockPos.containing(now.x+sideX,now.y+0.08,now.z+sideZ);
                        break;
                    }
                }
                if(waypoint==null) LockOnMovement.stop(mc);
                return false;
            }
            waypoint=step.position().immutable();
            BlockPos obstruction=step.nextBlockToBreak(mc.level);
            if(obstruction!=null) {clearBlock(mc,obstruction);return false;}
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

    private static void swimToShore(Minecraft mc,BlockPos target) {
        long now=mc.level.getGameTime();
        if(shore!=null && (now-shoreSince>180 || !Navigator.passable(mc.level,shore)
            || !Navigator.passable(mc.level,shore.above()))) {
            rejectedShores.add(shore);shore=null;
        }
        if(shore==null) {shore=Navigator.shore(mc.level,mc.player,target,rejectedShores);shoreSince=now;}
        BlockPos destination=shore==null?target:shore;
        double distance=Math.hypot(destination.getX()+0.5-mc.player.getX(),destination.getZ()+0.5-mc.player.getZ());
        // Looking upward, holding jump and releasing sprint near a bank lets
        // vanilla leave its swimming pose and climb a normal one-block shore.
        face(mc,new Vec3(destination.getX()+0.5,Math.max(mc.player.getEyeY()+0.8,destination.getY()+1.2),destination.getZ()+0.5));
        boolean climbing=shore!=null && distance<3;
        if(climbing) mc.player.setSprinting(false);
        LockOnMovement.forward(mc,!climbing && mc.player.getFoodData().getFoodLevel()>6);
        LockOnMovement.jump(mc,true);
    }

    static boolean recoveringSuffocation() { return suffocationBlock!=null; }

    static void finishSuffocation(Minecraft mc) {
        if(suffocationBlock!=null && mc.gameMode!=null) mc.gameMode.stopDestroyBlock();
        suffocationBlock=null;suffocationStarted=false;
    }

    static void escapeSuffocation(Minecraft mc) {
        LockOnMovement.stop(mc);
        var me=mc.player;
        var body=me.getBoundingBox().deflate(0.01);
        BlockPos chosen=null;
        // Prefer the block around the eyes, then other intersecting body blocks.
        double best=Double.MAX_VALUE;
        for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),
            BlockPos.containing(body.maxX,body.maxY,body.maxZ))) {
            var state=mc.level.getBlockState(p);
            if(state.hasBlockEntity() || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(mc.level,p)<0) continue;
            boolean intersects=false;
            for(var box:state.getCollisionShape(mc.level,p).toAabbs())
                if(box.move(p.getX(),p.getY(),p.getZ()).intersects(body)) {intersects=true;break;}
            if(!intersects) continue;
            double distance=me.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
            if(distance<best) {best=distance;chosen=p.immutable();}
        }
        if(chosen==null) {finishSuffocation(mc);return;}
        if(!chosen.equals(suffocationBlock)) {
            finishSuffocation(mc);suffocationBlock=chosen;suffocationSince=mc.level.getGameTime();
        }
        int slot=me.getInventory().getSelectedSlot();float speed=me.getMainHandItem().getDestroySpeed(mc.level.getBlockState(chosen));
        for(int i=0;i<36;i++) {
            float candidate=me.getInventory().getItem(i).getDestroySpeed(mc.level.getBlockState(chosen));
            if(candidate>speed) {speed=candidate;slot=i;}
        }
        if(slot>=9) {
            if(me.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)
                mc.gameMode.handleContainerInput(me.containerMenu.containerId,slot,8,
                    net.minecraft.world.inventory.ContainerInput.SWAP,me);
            return;
        }
        me.getInventory().setSelectedSlot(slot);
        face(mc,Vec3.atCenterOf(chosen));
        if(mc.level.getGameTime()-suffocationSince<2) return;
        // A ray starting inside a solid block can miss its surface. Use the
        // actual intersecting block with ordinary survival breaking packets.
        if(!suffocationStarted) {
            mc.gameMode.startDestroyBlock(chosen,net.minecraft.core.Direction.UP);
            suffocationStarted=true;
        } else mc.gameMode.continueDestroyBlock(chosen,net.minecraft.core.Direction.UP);
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

    static void clearObstacle(Minecraft mc,BlockPos block) { clearBlock(mc,block); }

    private static void rejectWaypoint(Minecraft mc) {
        if(waypoint!=null) rejectedSteps.put(waypoint,mc.level.getGameTime()+160);
        waypoint=null;progress.reset();
    }

    private static void clearBlock(Minecraft mc,BlockPos p) {
        LockOnMovement.stop(mc);
        if(mc.level.getBlockState(p).isAir()) {
            if(breaking!=null) LockOnSurvival.waitForDrop(p,beforeBreaking);
            breaking=null;waypoint=null;progress.reset();
            mc.gameMode.stopDestroyBlock();
            return;
        }
        if(!equipForClearing(mc,p)) return;
        if(!Navigator.mineable(mc.level,mc.player,p)
            || p.equals(mc.player.blockPosition().below())
            || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p))>4.1) {
            breaking=null;rejectWaypoint(mc);return;
        }
        BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(p),
            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(p)) {breaking=null;rejectWaypoint(mc);return;}
        face(mc,Vec3.atCenterOf(p));
        if(breaking==null) {
            breaking=p.immutable();
            beforeBreaking=LockOnSurvival.inventoryCounts(mc.player);
            startedBreaking=mc.level.getGameTime();
            return;
        }
        if(mc.level.getGameTime()-startedBreaking<3) return;
        if(mc.level.getGameTime()-startedBreaking>240) {
            mc.gameMode.stopDestroyBlock();breaking=null;rejectWaypoint(mc);return;
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
        if(best<0) {breaking=null;rejectWaypoint(mc);return false;}
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
