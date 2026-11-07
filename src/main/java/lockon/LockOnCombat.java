package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Inventory and item-use actions shared by survival defense and PvP. */
final class LockOnCombat {
    private static long switchedAt=-1000, useStarted=-1000;
    private static boolean eating, shielding;
    private static int foodSlot=-1;

    static boolean opponentEating(LivingEntity enemy) {
        return enemy.isUsingItem() && enemy.getUseItem().has(DataComponents.FOOD);
    }

    static boolean equipWeapon(Minecraft mc,boolean shieldedOpponent) {
        if(mc.gameMode==null) return false;
        var me=mc.player;
        int selected=me.getInventory().getSelectedSlot(), best=-1, bestScore=-1;
        // Favor the selected weapon at equal tier: inventory ordering must not
        // cause repeated swaps and permanently restart the attack cooldown.
        for(int slot=0;slot<36;slot++) {
            ItemStack stack=me.getInventory().getItem(slot);
            boolean sword=stack.is(ItemTags.SWORDS), axe=stack.is(ItemTags.AXES);
            if(!sword && !axe) continue;
            int score=(shieldedOpponent ? (axe?100:0) : (sword?100:0))+tier(stack)*10;
            if(slot==selected) score++;
            if(score>bestScore) {bestScore=score;best=slot;}
        }
        if(best<0) {
            if(me.getMainHandItem().isEmpty()) return true;
            for(int slot=0;slot<9;slot++) if(me.getInventory().getItem(slot).isEmpty()) {best=slot;break;}
            // A full inventory may leave only a mining tool. Normal melee with
            // that tool is still preferable to looking at a monster forever.
            if(best<0) return true;
        }
        if(best>=9) {
            if(!(me.containerMenu instanceof InventoryMenu)) return false;
            stopUse(mc);
            mc.gameMode.handleContainerInput(me.containerMenu.containerId,best,8,ContainerInput.SWAP,me);
            me.getInventory().setSelectedSlot(8);
            switchedAt=mc.level.getGameTime();
            return false;
        }
        if(best!=selected) {
            stopUse(mc);
            me.getInventory().setSelectedSlot(best);
            switchedAt=mc.level.getGameTime();
            return false;
        }
        return mc.level.getGameTime()<switchedAt || mc.level.getGameTime()-switchedAt>=2;
    }

    private static int tier(ItemStack stack) {
        String name=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if(name.startsWith("netherite_")) return 5;
        if(name.startsWith("diamond_")) return 4;
        if(name.startsWith("iron_")) return 3;
        if(name.startsWith("copper_") || name.startsWith("stone_")) return 2;
        return 1;
    }

    static boolean eat(Minecraft mc) {
        var me=mc.player;
        if(mc.gameMode==null) return false;
        long now=mc.level.getGameTime();
        if(eating) {
            if(now-useStarted>45 || (now-useStarted>2 && !me.isUsingItem())
                || me.getInventory().getSelectedSlot()!=foodSlot) {
                stopUse(mc);
                return false;
            }
            mc.options.keyUse.setDown(true);
            return true;
        }
        int hunger=me.getFoodData().getFoodLevel();
        if(!CombatTactics.shouldEat(me.getHealth(),hunger)) return false;
        int food=-1;
        for(int slot=0;slot<36;slot++) {
            ItemStack stack=me.getInventory().getItem(slot);
            if(LockOnFood.edible(stack) || stack.is(Items.GOLDEN_APPLE)
                || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {food=slot;break;}
        }
        if(food<0 && hunger<=8) {
            for(int slot=0;slot<36;slot++) if(LockOnFood.rawMeat(me.getInventory().getItem(slot))) {food=slot;break;}
        }
        if(food<0) return false;
        stopUse(mc);
        if(food>=9) {
            if(!(me.containerMenu instanceof InventoryMenu)) return false;
            mc.gameMode.handleContainerInput(me.containerMenu.containerId,food,8,ContainerInput.SWAP,me);
            food=8;
        }
        me.getInventory().setSelectedSlot(food);
        foodSlot=food;
        mc.gameMode.useItem(me,InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        eating=true;useStarted=now;
        return true;
    }

    static void shield(Minecraft mc,LivingEntity enemy,int sinceAttack) {
        boolean want=CombatTactics.shouldShield(mc.player.distanceTo(enemy),sinceAttack,
            opponentEating(enemy),enemy.isBlocking());
        if(!want) {if(shielding) stopUse(mc);return;}
        var me=mc.player;
        if(!me.getOffhandItem().is(Items.SHIELD) && me.getOffhandItem().isEmpty()
            && me.containerMenu instanceof InventoryMenu) {
            for(int i=0;i<36;i++) if(me.getInventory().getItem(i).is(Items.SHIELD)) {
                mc.gameMode.handleContainerInput(me.containerMenu.containerId,i<9?i+36:i,
                    40,ContainerInput.SWAP,me);
                return;
            }
        }
        if(!me.getOffhandItem().is(Items.SHIELD)) return;
        if(!shielding || !me.isUsingItem()) mc.gameMode.useItem(me,InteractionHand.OFF_HAND);
        shielding=true;
        mc.options.keyUse.setDown(true);
    }

    static void stopUse(Minecraft mc) {
        if(eating || shielding) {
            mc.options.keyUse.setDown(false);
            if(mc.player!=null && mc.gameMode!=null && mc.player.isUsingItem())
                mc.gameMode.releaseUsingItem(mc.player);
        }
        eating=shielding=false;foodSlot=-1;
    }
}
