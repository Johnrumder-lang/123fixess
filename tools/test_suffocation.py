#!/usr/bin/env python3
"""Run the production suffocation recovery methods in a collision/client fixture.
The method bodies are read verbatim; this does not simulate server damage/physics.
"""
from pathlib import Path
import subprocess,tempfile

sources={
'net/minecraft/core/BlockPos.java': '''
package net.minecraft.core;
import java.util.*;
public record BlockPos(int getX,int getY,int getZ) {
 public static BlockPos containing(double x,double y,double z){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
 public BlockPos immutable(){return this;}
 public static Iterable<BlockPos> betweenClosed(BlockPos a,BlockPos b){List<BlockPos> out=new ArrayList<>();for(int x=a.getX;x<=b.getX;x++)for(int y=a.getY;y<=b.getY;y++)for(int z=a.getZ;z<=b.getZ;z++)out.add(new BlockPos(x,y,z));return out;}
}
''',
'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {UP}',
'net/minecraft/world/inventory/InventoryMenu.java': 'package net.minecraft.world.inventory; public class InventoryMenu {public int containerId;}',
'net/minecraft/world/inventory/ContainerInput.java': 'package net.minecraft.world.inventory; public enum ContainerInput {SWAP}',
'net/minecraft/world/phys/Vec3.java': '''
package net.minecraft.world.phys;
import net.minecraft.core.BlockPos;
public record Vec3(double x,double y,double z){
 public static Vec3 atCenterOf(BlockPos p){return new Vec3(p.getX()+.5,p.getY()+.5,p.getZ()+.5);}
 public double distanceToSqr(Vec3 o){return Math.pow(x-o.x,2)+Math.pow(y-o.y,2)+Math.pow(z-o.z,2);}
}
''',
'net/minecraft/world/phys/AABB.java': '''
package net.minecraft.world.phys;
public class AABB {
 public double minX,minY,minZ,maxX,maxY,maxZ;
 public AABB(double x,double y,double z,double xx,double yy,double zz){minX=x;minY=y;minZ=z;maxX=xx;maxY=yy;maxZ=zz;}
 public AABB deflate(double d){return new AABB(minX+d,minY+d,minZ+d,maxX-d,maxY-d,maxZ-d);}
 public AABB move(double x,double y,double z){return new AABB(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
 public boolean intersects(AABB o){return maxX>o.minX&&minX<o.maxX&&maxY>o.minY&&minY<o.maxY&&maxZ>o.minZ&&minZ<o.maxZ;}
}
''',
'net/minecraft/client/Minecraft.java': '''
package net.minecraft.client;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.inventory.*;
import java.util.*;
public class Minecraft {
 public Player player=new Player();public Level level=new Level();public Mode gameMode=new Mode();
 public static class State {
  boolean solid,container;float hardness;
  public State(boolean s,float h,boolean c){solid=s;hardness=h;container=c;}
  public boolean hasBlockEntity(){return container;}
  public Fluid getFluidState(){return new Fluid();}
  public float getDestroySpeed(Level l,BlockPos p){return hardness;}
  public Shape getCollisionShape(Level l,BlockPos p){return new Shape(solid);}
 }
 public static class Fluid {public boolean isEmpty(){return true;}}
 public record Shape(boolean solid){public List<AABB> toAabbs(){return solid?List.of(new AABB(0,0,0,1,1,1)):List.of();}}
 public static class Stack {public float speed=1;public float getDestroySpeed(State s){return speed;}}
 public static class Inventory {
  public Stack[] slots=new Stack[36];public int selected;
  public Inventory(){for(int i=0;i<36;i++)slots[i]=new Stack();}
  public int getSelectedSlot(){return selected;}public void setSelectedSlot(int s){selected=s;}
  public Stack getItem(int i){return slots[i];}
 }
 public static class Player {
  public Inventory inv=new Inventory();public InventoryMenu containerMenu=new InventoryMenu();
  public AABB getBoundingBox(){return new AABB(.2,1,.2,.8,2.8,.8);}
  public Vec3 getEyePosition(){return new Vec3(.5,2.62,.5);}
  public Inventory getInventory(){return inv;}public Stack getMainHandItem(){return inv.slots[inv.selected];}
 }
 public static class Level {
  public long tick;public Map<BlockPos,State> states=new HashMap<>();
  public State getBlockState(BlockPos p){return states.getOrDefault(p,new State(false,0,false));}
  public long getGameTime(){return tick;}
 }
 public static class Mode {
  public int starts,continues,stops,swaps;public BlockPos broken;
  public void stopDestroyBlock(){stops++;}
  public void startDestroyBlock(BlockPos p,Direction d){starts++;broken=p;}
  public void continueDestroyBlock(BlockPos p,Direction d){continues++;broken=p;}
  public void handleContainerInput(int id,int slot,int button,ContainerInput action,Player p){swaps++;Stack old=p.inv.slots[button];p.inv.slots[button]=p.inv.slots[slot];p.inv.slots[slot]=old;}
 }
}
''',
'lockon/LockOnMovement.java': '''
package lockon;import net.minecraft.client.Minecraft;
class LockOnMovement {static void stop(Minecraft mc){}}
''',
'lockon/SuffocationTest.java': '''
package lockon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
public class SuffocationTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  Minecraft mc=new Minecraft();BlockPos head=new BlockPos(0,2,0),feet=new BlockPos(0,1,0);
  mc.level.states.put(head,new Minecraft.State(true,1,false));
  mc.level.states.put(feet,new Minecraft.State(true,1,false));
  mc.player.inv.slots[2].speed=5;
  LockOnTravel.escapeSuffocation(mc);
  check(mc.gameMode.starts==0,"wait for tool/aim synchronization");
  mc.level.tick=2;LockOnTravel.escapeSuffocation(mc);
  check(head.equals(mc.gameMode.broken),"mine actual head collision even with eyes inside it");
  check(mc.player.inv.selected==2,"choose faster tool for emergency");
  mc.level.tick++;LockOnTravel.escapeSuffocation(mc);
  check(mc.gameMode.starts==1 && mc.gameMode.continues==1,"continue break instead of restarting it each tick");
  mc.level.states.remove(head);LockOnTravel.escapeSuffocation(mc);
  mc.level.tick+=2;LockOnTravel.escapeSuffocation(mc);
  check(feet.equals(mc.gameMode.broken),"remaining body collision is cleared next");
  LockOnTravel.finishSuffocation(mc);
  check(!LockOnTravel.recoveringSuffocation(),"normal work or stop clears emergency state");
  mc.level.states.clear();mc.level.states.put(head,new Minecraft.State(true,-1,false));
  int before=mc.gameMode.starts;
  mc.level.tick+=10;LockOnTravel.escapeSuffocation(mc);
  check(mc.gameMode.starts==before && !LockOnTravel.recoveringSuffocation(),"never attempt unbreakable block");
  mc.level.states.put(head,new Minecraft.State(true,1,true));LockOnTravel.escapeSuffocation(mc);
  check(mc.gameMode.starts==before,"do not destroy containers automatically");
  mc.level.states.put(new BlockPos(1,2,0),new Minecraft.State(true,1,false));
  mc.level.states.remove(head);LockOnTravel.escapeSuffocation(mc);
  check(!LockOnTravel.recoveringSuffocation(),"adjacent non-intersecting block is not suffocation");
  System.out.println("Suffocation recovery: "+checks+" behavioral checks passed");
 }
}
'''
}
travel=Path('src/main/java/lockon/LockOnTravel.java').read_text()
methods=travel[travel.index('    static boolean recoveringSuffocation()'):travel.index('    static BlockPos standingPosition(')]
sources['lockon/LockOnTravel.java']='''package lockon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
class LockOnTravel {
 private static BlockPos suffocationBlock;
 private static long suffocationSince;
 private static boolean suffocationStarted;
 static void face(Minecraft mc,Vec3 target) {}
'''+methods+'\n}'
with tempfile.TemporaryDirectory(prefix='lockon-suffocation-tests-') as directory:
 root=Path(directory)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 classes=root/'classes'
 subprocess.run(['javac','-d',str(classes),*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run(['java','-cp',str(classes),'lockon.SuffocationTest'],check=True)
