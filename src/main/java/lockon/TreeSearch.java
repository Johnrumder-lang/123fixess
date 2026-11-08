package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

/** Budgeted 32-chunk-radius search over client-loaded chunk palettes. */
final class TreeSearch {
    static final int RADIUS_CHUNKS=32;
    private static final java.util.List<SearchPlan.Offset> ORDER=SearchPlan.chunks(RADIUS_CHUNKS);
    private int originX,originZ,chunk,section,cell;
    private long finishedAt=-1;
    private boolean initialized;
    void reset() {initialized=false;chunk=section=cell=0;finishedAt=-1;}

    BlockPos poll(Minecraft mc,java.util.Set<BlockPos> rejected) {
        int cx=mc.player.blockPosition().getX()>>4,cz=mc.player.blockPosition().getZ()>>4;
        long now=mc.level.getGameTime();
        if(!initialized || Math.abs(cx-originX)>2 || Math.abs(cz-originZ)>2
            || (finishedAt>=0 && now-finishedAt>=100)) {
            originX=cx;originZ=cz;chunk=section=cell=0;initialized=true;finishedAt=-1;
        }
        if(finishedAt>=0) return null;
        BlockPos best=null;double distance=Double.MAX_VALUE;
        int budget=8192,visits=0,trees=0;
        while(budget>0 && chunk<ORDER.size() && visits++<128) {
            var offset=ORDER.get(chunk);
            int x=originX+offset.x(),z=originZ+offset.z();
            if(!mc.level.hasChunk(x,z)) {chunk++;section=cell=0;continue;}
            var sections=mc.level.getChunk(x,z).getSections();
            if(section>=sections.length) {chunk++;section=cell=0;if(best!=null) return best;continue;}
            var current=sections[section];
            if(cell==0 && !current.maybeHas(s->s.is(BlockTags.LOGS))) {section++;continue;}
            int y=mc.level.getSectionYFromSectionIndex(section)<<4;
            while(budget>0 && cell<4096) {
                int index=cell++;budget--;
                int lx=index&15,lz=(index>>4)&15,ly=(index>>8)&15;
                if(!current.getBlockState(lx,ly,lz).is(BlockTags.LOGS)) continue;
                BlockPos pos=new BlockPos((x<<4)+lx,y+ly,(z<<4)+lz);
                if(rejected.contains(pos)) continue;
                double score=mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
                if(score<distance && LockOnBootstrap.naturalTree(mc.level,pos)) {distance=score;best=pos;}
                if(++trees>=32) return best;
            }
            if(cell>=4096) {section++;cell=0;}
        }
        if(chunk>=ORDER.size()) finishedAt=now;
        return best;
    }
}
