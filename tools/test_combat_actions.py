#!/usr/bin/env python3
"""Run production combat inventory/use code against a deterministic client fixture.
No Minecraft server, collision physics, network latency or damage is simulated.
"""
from pathlib import Path
import tempfile
import subprocess

sources = {
'net/minecraft/tags/ItemTags.java': '''
package net.minecraft.tags;
public class ItemTags {public static final String SWORDS="sword",AXES="axe";}
''',
'net/minecraft/core/component/DataComponents.java': '''
package net.minecraft.core.component;
public class DataComponents {public static final String FOOD="food";}
''',
'net/minecraft/world/InteractionHand.java': '''
package net.minecraft.world;
public enum InteractionHand {MAIN_HAND,OFF_HAND}
''',
'net/minecraft/world/item/Items.java': '''
package net.minecraft.world.item;
public class Items {
 public static final String SHIELD="shield",GOLDEN_APPLE="golden_apple",ENCHANTED_GOLDEN_APPLE="enchanted_golden_apple";
}
''',
'net/minecraft/world/item/ItemStack.java': '''
package net.minecraft.world.item;
public class ItemStack {
 public final String name;
 public ItemStack(String name) {this.name=name;}
 public boolean is(String tag) {return name.equals(tag) || (tag.equals("sword") && name.endsWith("_sword")) || (tag.equals("axe") && name.endsWith("_axe"));}
 public boolean isEmpty() {return name.isEmpty();}
 public boolean has(String component) {return name.contains("food") || name.contains("apple");}
 public String getItem() {return name;}
}
''',
'net/minecraft/core/registries/BuiltInRegistries.java': '''
package net.minecraft.core.registries;
public class BuiltInRegistries {
 public static final Registry ITEM=new Registry();
 public static class Registry {public Key getKey(String item) {return new Key(item);}}
 public record Key(String getPath) {}
}
''',
'net/minecraft/world/entity/LivingEntity.java': '''
package net.minecraft.world.entity;
import net.minecraft.world.item.ItemStack;
public class LivingEntity {
 public boolean using,blocking;
 public ItemStack use=new ItemStack("");
 public boolean isUsingItem() {return using;}
 public boolean isBlocking() {return blocking;}
 public ItemStack getUseItem() {return use;}
}
''',
'net/minecraft/world/inventory/ContainerInput.java': '''
package net.minecraft.world.inventory;
public enum ContainerInput {SWAP}
''',
'net/minecraft/world/inventory/InventoryMenu.java': '''
package net.minecraft.world.inventory;
public class InventoryMenu {public int containerId=0;}
''',
'net/minecraft/client/Minecraft.java': '''
package net.minecraft.client;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
public class Minecraft {
 public Player player=new Player();
 public Mode gameMode=new Mode();
 public Level level=new Level();
 public Options options=new Options();
 public static class Level {public long tick;public long getGameTime() {return tick;}}
 public static class Options {public Key keyUse=new Key();}
 public static class Key {public boolean down; public void setDown(boolean d) {down=d;}}
 public static class Inventory {
  public ItemStack[] slots=new ItemStack[36];public int selected;
  public Inventory() {for(int i=0;i<36;i++) slots[i]=new ItemStack("");}
  public int getSelectedSlot() {return selected;}
  public void setSelectedSlot(int s) {selected=s;}
  public ItemStack getItem(int s) {return slots[s];}
 }
 public static class Food {public int hunger=20;public int getFoodLevel() {return hunger;}}
 public static class Player extends LivingEntity {
  public Inventory inventory=new Inventory();public Food food=new Food();
  public float hp=20,distance=3;
  public InventoryMenu containerMenu=new InventoryMenu();
  public ItemStack offhand=new ItemStack("");
  public Inventory getInventory() {return inventory;}
  public ItemStack getMainHandItem() {return inventory.slots[inventory.selected];}
  public ItemStack getOffhandItem() {return offhand;}
  public float getHealth() {return hp;}
  public Food getFoodData() {return food;}
  public float distanceTo(LivingEntity e) {return distance;}
 }
 public static class Mode {
  public int swaps,uses,releases;
  public void handleContainerInput(int id,int slot,int button,ContainerInput input,Player me) {
   swaps++;int index=slot>=36?slot-36:slot;
   ItemStack old=me.inventory.slots[index];
   if(button==40) {me.inventory.slots[index]=me.offhand;me.offhand=old;}
   else {me.inventory.slots[index]=me.inventory.slots[button];me.inventory.slots[button]=old;}
  }
  public void useItem(Player me,InteractionHand hand) {
   uses++;me.using=true;me.use=hand==InteractionHand.MAIN_HAND?me.getMainHandItem():me.offhand;
  }
  public void releaseUsingItem(Player me) {releases++;me.using=false;}
 }
}
''',
'lockon/LockOnFood.java': '''
package lockon;
import net.minecraft.world.item.ItemStack;
class LockOnFood {
 static boolean edible(ItemStack stack) {return stack.name.equals("food");}
 static boolean rawMeat(ItemStack stack) {return stack.name.equals("raw_food");}
}
''',
'lockon/CombatActionsTest.java': '''
package lockon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
public class CombatActionsTest {
 static int checks;
 static void check(boolean b,String m) {checks++;if(!b)throw new AssertionError(m);}
 static void item(Minecraft mc,int slot,String name) {mc.player.inventory.slots[slot]=new ItemStack(name);}
 public static void main(String[] args) {
  Minecraft mc=new Minecraft();mc.level.tick=100;
  item(mc,0,"iron_pickaxe");item(mc,12,"iron_sword");item(mc,16,"iron_axe");
  check(!LockOnCombat.equipWeapon(mc,false),"weapon transfer waits for synchronization");
  check(mc.player.getMainHandItem().is("sword"),"sword selected before attack cooldown checks");
  check(mc.gameMode.swaps==1,"one inventory swap");
  mc.level.tick+=2;
  for(int i=0;i<60;i++) {
   mc.level.tick++;
   check(LockOnCombat.equipWeapon(mc,false),"stable sword ready on repeated defense ticks");
  }
  check(mc.gameMode.swaps==1,"no swap loop resetting cooldown");
  check(!LockOnCombat.equipWeapon(mc,true),"shield opponent triggers axe switch");
  check(mc.player.getMainHandItem().is("axe"),"axe equipped");
  mc.level.tick+=2;check(LockOnCombat.equipWeapon(mc,true),"axe ready after switch");
  check(!LockOnCombat.equipWeapon(mc,false),"return to sword when opponent lowers shield");
  mc.level.tick+=2;check(LockOnCombat.equipWeapon(mc,false),"sword recovers after shield exchange");
  item(mc,3,"shield");LivingEntity enemy=new LivingEntity();
  LockOnCombat.shield(mc,enemy,1);
  check(mc.player.offhand.is("shield"),"hotbar shield moved to offhand using inventory slot mapping");
  LockOnCombat.shield(mc,enemy,2);
  check(mc.player.using && mc.options.keyUse.down,"shield use held between ticks");
  LockOnCombat.shield(mc,enemy,8);
  check(!mc.player.using && !mc.options.keyUse.down,"shield released in time for follow-up hit");
  enemy.using=true;enemy.use=new ItemStack("food");
  check(LockOnCombat.opponentEating(enemy),"opponent food use recognized");
  LockOnCombat.shield(mc,enemy,3);
  check(!mc.player.using,"eating opponent never stalls our pressure with shield");
  mc.player.hp=6;mc.player.food.hunger=20;item(mc,20,"food");
  check(!LockOnCombat.eat(mc),"full hunger cannot enter unfinishable eating state");
  mc.player.food.hunger=12;
  check(LockOnCombat.eat(mc),"low health starts eating from main inventory");
  int swaps=mc.gameMode.swaps;
  for(int i=0;i<32;i++) {mc.level.tick++;check(LockOnCombat.eat(mc),"eating persists without weapon swaps");}
  check(mc.gameMode.swaps==swaps,"food not repeatedly swapped");
  mc.player.using=false;mc.player.food.hunger=20;mc.level.tick++;
  check(!LockOnCombat.eat(mc),"finished consumption releases use");
  check(!mc.options.keyUse.down,"no stuck use key after eating");
  LockOnCombat.equipWeapon(mc,false);mc.level.tick+=2;
  check(LockOnCombat.equipWeapon(mc,false),"weapon restored after food");
  check(mc.player.getMainHandItem().is("sword"),"attacks resume with sword");
  mc.player.food.hunger=10;check(LockOnCombat.eat(mc),"start another recovery");
  LockOnCombat.stopUse(mc);
  check(!mc.player.using && !mc.options.keyUse.down,"disable/disconnect cancels held consumption");
  System.out.println("Combat inventory/use: "+checks+" behavioral checks passed");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='lockon-combat-tests-') as directory:
    root=Path(directory)
    for name,source in sources.items():
        path=root/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(source)
    for name in ['LockOnCombat','CombatTactics']:
        (root/f'lockon/{name}.java').write_text(Path(f'src/main/java/lockon/{name}.java').read_text())
    classes=root/'classes'
    subprocess.run(['javac','-d',str(classes),*[str(p) for p in root.rglob('*.java')]],check=True)
    subprocess.run(['java','-cp',str(classes),'lockon.CombatActionsTest'],check=True)
