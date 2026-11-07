#!/usr/bin/env python3
"""Exercise production A* and collision classification against a block-grid fixture.
This tests route decisions, not Minecraft movement physics or server interaction.
"""
from pathlib import Path
import subprocess
import tempfile

sources={
'net/minecraft/core/BlockPos.java': '''
package net.minecraft.core;
public record BlockPos(int getX,int getY,int getZ) {
 public static BlockPos containing(double x,double y,double z) {return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
 public BlockPos offset(int x,int y,int z) {return new BlockPos(getX+x,getY+y,getZ+z);}
 public BlockPos above() {return above(1);} public BlockPos above(int n) {return offset(0,n,0);}
 public BlockPos below() {return offset(0,-1,0);}
}
''',
'net/minecraft/tags/FluidTags.java': '''
package net.minecraft.tags;
public class FluidTags {public static final String WATER="water";}
''',
'net/minecraft/world/level/block/Blocks.java': '''
package net.minecraft.world.level.block;
public class Blocks {
 public static final String LAVA="lava",FIRE="fire",SOUL_FIRE="soul_fire",CACTUS="cactus",MAGMA_BLOCK="magma",CAMPFIRE="campfire",SOUL_CAMPFIRE="soul_campfire",POWDER_SNOW="powder_snow",COBWEB="cobweb";
}
''',
'net/minecraft/world/level/block/state/BlockState.java': '''
package net.minecraft.world.level.block.state;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
public class BlockState {
 public final String name;
 public BlockState(String name) {this.name=name;}
 public boolean is(String block) {return name.equals(block);}
 public boolean isAir() {return name.equals("air");}
 public boolean hasBlockEntity() {return name.equals("chest");}
 public float getDestroySpeed(ClientLevel l,BlockPos p) {return name.equals("bedrock")?-1:1;}
 public float getDestroyProgress(LocalPlayer p,ClientLevel l,BlockPos b) {return .1f;}
 public Fluid getFluidState() {return new Fluid(name.equals("water") || name.equals("lava")?name:"");}
 public Shape getCollisionShape(ClientLevel l,BlockPos p) {return new Shape(isAir() || name.equals("water") || name.equals("lava") || name.equals("grass"));}
 public record Shape(boolean isEmpty) {}
 public record Fluid(String name) {public boolean isEmpty() {return name.isEmpty();} public boolean is(String tag) {return name.equals(tag);}}
}
''',
'net/minecraft/world/entity/player/Player.java': '''
package net.minecraft.world.entity.player;
public class Player {
 public double x=.5,y=1,z=.5;
 public double getX() {return x;} public double getY() {return y;} public double getZ() {return z;}
}
''',
'net/minecraft/client/player/LocalPlayer.java': '''
package net.minecraft.client.player;
public class LocalPlayer extends net.minecraft.world.entity.player.Player {
 public float health=20;
 public float getHealth() {return health;}
}
''',
'net/minecraft/client/multiplayer/ClientLevel.java': '''
package net.minecraft.client.multiplayer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public class ClientLevel {
 public Map<BlockPos,BlockState> blocks=new HashMap<>();public boolean loaded=true;
 public void put(int x,int y,int z,String name) {blocks.put(new BlockPos(x,y,z),new BlockState(name));}
 public BlockState getBlockState(BlockPos p) {return blocks.getOrDefault(p,new BlockState("air"));}
 public Object getBlockEntity(BlockPos p) {return null;}
 public boolean hasChunk(int x,int z) {return loaded;}
}
''',
'lockon/LockOnSurvival.java': '''
package lockon;
class LockOnSurvival {static boolean active=true;static boolean enabled() {return active;}}
''',
'lockon/NavigationTest.java': '''
package lockon;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
public class NavigationTest {
 static int checks;
 static void check(boolean value,String name) {checks++;if(!value)throw new AssertionError(name);}
 static ClientLevel corridor() {ClientLevel w=new ClientLevel();for(int x=0;x<6;x++)w.put(x,0,0,"stone");return w;}
 public static void main(String[] args) {
  LocalPlayer p=new LocalPlayer();ClientLevel w=corridor();
  var step=Navigator.route(w,p,4.5,1,.5,true);
  check(step!=null && step.position().equals(new BlockPos(1,1,0)),"open corridor progresses toward destination");
  check(step.nextBlockToBreak(w)==null,"air never produces phantom mining obstruction");
  check(Navigator.safeForward(w,p,1,0),"open supported ground is traversable");
  w.put(1,1,0,"grass");
  check(Navigator.safeForward(w,p,1,0),"noncolliding plants are not solid obstacles");
  w.put(1,1,0,"air");w.put(1,2,0,"leaves");
  check(!Navigator.safeForward(w,p,1,0),"floating head-height leaves stop direct walking");
  step=Navigator.route(w,p,4.5,1,.5,true);
  check(step!=null && new BlockPos(1,2,0).equals(step.nextBlockToBreak(w)),"route clears floating leaves");
  check(Navigator.route(w,p,4.5,1,.5,false)==null,"no mining means blocked corridor is not traversable");
  w.put(1,1,0,"stone");
  step=Navigator.route(w,p,4.5,1,.5,true);
  check(step!=null && new BlockPos(1,2,0).equals(step.nextBlockToBreak(w)),"clear head before feet in full obstruction");
  w.put(1,2,0,"air");
  check(Navigator.oneBlockAhead(w,p,1,0),"one-block ledge recognized for jumping");
  w.put(0,3,0,"leaves");
  check(!Navigator.safeToJump(w,p),"jump blocked by overhead leaves");
  w=corridor();w.put(1,1,0,"water");w.put(1,0,0,"water");
  check(Navigator.safeForward(w,p,1,0),"water is swimmable rather than a phantom liquid hazard");
  check(Navigator.route(w,p,4.5,1,.5,true)!=null,"route enters water");
  w.put(1,1,0,"lava");
  check(!Navigator.safeForward(w,p,1,0),"lava is always rejected");
  check(!Navigator.mineable(w,p,new BlockPos(1,1,0)),"lava cannot be cleared by mining");
  w=corridor();w.loaded=false;
  check(Navigator.route(w,p,4.5,1,.5,true)==null,"never route into unloaded chunks");
  w=new ClientLevel();w.put(0,6,0,"stone");w.put(1,0,0,"stone");p.y=7;
  step=Navigator.route(w,p,1.5,1,.5,false);
  check(step!=null && step.position().getY()==1,"healthy survival player can take supported six-block drop");
  p.health=8;
  check(Navigator.route(w,p,1.5,1,.5,false)==null,"low health refuses same drop");
  p.health=20;LockOnSurvival.active=false;
  check(Navigator.route(w,p,1.5,1,.5,false)==null,"combat chase keeps conservative fall limits");
  LockOnSurvival.active=true;w.put(1,0,0,"air");
  check(Navigator.route(w,p,1.5,1,.5,false)==null,"unsupported drops never become routes");
  System.out.println("Navigation block-grid: "+checks+" behavioral checks passed");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='lockon-navigation-tests-') as directory:
    root=Path(directory)
    for name,source in sources.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
    for name in ['Navigator','SurvivalPolicy']:
        (root/f'lockon/{name}.java').write_text(Path(f'src/main/java/lockon/{name}.java').read_text())
    classes=root/'classes'
    subprocess.run(['javac','-d',str(classes),*[str(p) for p in root.rglob('*.java')]],check=True)
    subprocess.run(['java','-cp',str(classes),'lockon.NavigationTest'],check=True)
