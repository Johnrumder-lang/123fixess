#!/usr/bin/env python3
"""Exercise actual mixed-plank selection and reachable-face code with client fixtures."""
from pathlib import Path
import tempfile,subprocess
sources={
'net/minecraft/tags/ItemTags.java': 'package net.minecraft.tags; public class ItemTags {public static final String PLANKS="planks";}',
'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item; public record Item(String name) {}''',
'net/minecraft/world/item/ItemStack.java': '''
package net.minecraft.world.item;
public class ItemStack {
 public Item item;public int count;
 public ItemStack(Item i,int n){item=i;count=n;}
 public boolean is(String tag){return count>0 && item.name().endsWith("_planks");}
 public boolean is(Item i){return count>0 && item.equals(i);}
 public int getCount(){return count;}public Item getItem(){return item;}
}
''',
'net/minecraft/client/player/LocalPlayer.java': '''
package net.minecraft.client.player;
import net.minecraft.world.item.*;
public class LocalPlayer {
 public Inventory inventory=new Inventory();
 public Inventory getInventory(){return inventory;}
 public static class Inventory {
  public ItemStack[] items=new ItemStack[36];
  public Inventory(){for(int i=0;i<36;i++)items[i]=new ItemStack(new Item("air"),0);}
  public ItemStack getItem(int i){return items[i];}
 }
}
''',
'net/minecraft/world/inventory/AbstractContainerMenu.java': '''
package net.minecraft.world.inventory;
import java.util.*;import net.minecraft.world.item.*;
public class AbstractContainerMenu {
 public List<Slot> slots=new ArrayList<>();
 public Slot getSlot(int i){return slots.get(i);}
 public record Slot(ItemStack getItem) {}
}
''',
'net/minecraft/core/BlockPos.java': 'package net.minecraft.core; public record BlockPos(int getX,int getY,int getZ) {}',
'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {UP,DOWN,NORTH,SOUTH,WEST,EAST}',
'net/minecraft/world/phys/Vec3.java': '''
package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){public double distanceToSqr(Vec3 p){return Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2);}}
''',
'net/minecraft/world/phys/HitResult.java': 'package net.minecraft.world.phys; public class HitResult {public enum Type {BLOCK,MISS}}',
'net/minecraft/world/phys/BlockHitResult.java': '''
package net.minecraft.world.phys;
import net.minecraft.core.*;
public record BlockHitResult(Vec3 getLocation,Direction getDirection,BlockPos getBlockPos){public HitResult.Type getType(){return HitResult.Type.BLOCK;}}
''',
'net/minecraft/world/level/ClipContext.java': '''
package net.minecraft.world.level;
import net.minecraft.world.phys.Vec3;
public record ClipContext(Vec3 eye,Vec3 end,Block block,Fluid fluid,Object player){public enum Block {COLLIDER}public enum Fluid {NONE}}
''',
'net/minecraft/client/Minecraft.java': '''
package net.minecraft.client;
import net.minecraft.world.phys.*;import net.minecraft.world.level.*;import net.minecraft.core.*;
public class Minecraft {
 public Object player=new Object();public Level level=new Level();
 public static class Level {
  public boolean blocked,sideOnly;
  public BlockHitResult clip(ClipContext c){
   Vec3 p=c.end();
   int centers=(p.x()==.5?1:0)+(p.y()==.5?1:0)+(p.z()==.5?1:0);
   return new BlockHitResult(p,sideOnly?Direction.EAST:Direction.UP,
     blocked || centers>=2?new BlockPos(9,9,9):new BlockPos(0,0,0));
  }
 }
}
'''
}
base=Path('src/main/java/lockon/LockOnBase.java').read_text()
boot=Path('src/main/java/lockon/LockOnBootstrap.java').read_text()
plank=base[base.index('    private static Item findPlank('):base.index('    private static boolean hasTool(')]
slot=boot[boot.index('    private static int findSlot('):boot.index('    private static void click(')]
sources['lockon/GatheringActionsTest.java']='''
package lockon;
import net.minecraft.client.player.LocalPlayer;import net.minecraft.world.item.*;
import net.minecraft.world.inventory.AbstractContainerMenu;import net.minecraft.tags.ItemTags;
import net.minecraft.client.Minecraft;import net.minecraft.core.*;import net.minecraft.world.phys.*;
public class GatheringActionsTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
'''+plank+slot+'''
 public static void main(String[] args){
  for(int needed:new int[]{2,3,4,8}){
   LocalPlayer player=new LocalPlayer();
   player.inventory.items[0]=new ItemStack(new Item("oak_planks"),1);
   player.inventory.items[1]=new ItemStack(new Item("birch_planks"),needed-1);
   check(findPlank(player,needed)!=null,"mixed plank count satisfies recipe "+needed);
   check(findPlank(player,needed+1)==null,"missing plank still rejected");
   var menu=new AbstractContainerMenu();
   for(int i=0;i<9;i++)menu.slots.add(new AbstractContainerMenu.Slot(new ItemStack(new Item("air"),0)));
   for(var stack:player.inventory.items)menu.slots.add(new AbstractContainerMenu.Slot(stack));
   for(int cell=0;cell<needed;cell++){
    int source=findSlot(menu,null,9);
    check(source>=9,"each grid cell finds a remaining eligible plank");
    menu.getSlot(source).getItem().count--;
   }
   check(findSlot(menu,null,9)==-1,"all requested planks consumed across species");
   check(findSlot(menu,new Item("stick"),9)==-1,"stick cells never substitute planks");
  }
  Minecraft mc=new Minecraft();BlockPos table=new BlockPos(0,0,0);Vec3 eye=new Vec3(2,2,2);
  check(BlockInteraction.hit(mc,eye,table,false)!=null,"visible edge works when center is obscured");
  var top=BlockInteraction.hit(mc,eye,table,true);
  check(top!=null && top.getDirection()==Direction.UP,"placement uses a top-face sample");
  mc.level.sideOnly=true;
  check(BlockInteraction.hit(mc,eye,table,true)==null,"side hit cannot place into a different block than intended");
  check(BlockInteraction.hit(mc,eye,table,false)!=null,"opening a table can use a side face");
  mc.level.blocked=true;
  check(BlockInteraction.hit(mc,eye,table,false)==null,"cannot interact through an occluding block");
  mc.level.blocked=false;
  check(BlockInteraction.hit(mc,new Vec3(20,20,20),table,false)==null,"normal interaction reach preserved");
  System.out.println("Mixed wood and block interaction: "+checks+" behavioral checks passed");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='lockon-gathering-tests-') as directory:
 root=Path(directory)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 (root/'lockon/BlockInteraction.java').write_text(Path('src/main/java/lockon/BlockInteraction.java').read_text())
 classes=root/'classes'
 subprocess.run(['javac','-d',str(classes),*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run(['java','-cp',str(classes),'lockon.GatheringActionsTest'],check=True)
