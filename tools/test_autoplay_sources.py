#!/usr/bin/env python3
"""Non-runtime regression checks for the autonomous Survival integration."""
from pathlib import Path

root=Path("src/main/java/lockon")
client=(root/"LockOnClient.java").read_text()
survival=(root/"LockOnSurvival.java").read_text()
bootstrap=(root/"LockOnBootstrap.java").read_text()
base=(root/"LockOnBase.java").read_text()
settings=(root/"LockOnSettings.java").read_text()

assert "LockOnSurvival.toggle(mc)" in client
assert "Phase.BOOTSTRAP" in survival
assert "LockOnBootstrap.tick(mc)" in survival
assert "LockOnBootstrap.reset(mc,base,baseFacing)" in survival
assert "LockOnSettings.survivalAutoRepeat()" in survival
assert "tunnelDirection.getClockWise()" in survival
assert "AutoplayPlan.next(" in bootstrap
assert "BlockTags.LOGS" in bootstrap
assert "Items.WOODEN_PICKAXE" in bootstrap
assert "Items.STICK" in bootstrap
assert "Items.CRAFTING_TABLE" in bootstrap
assert "case UPGRADE_TOOL" in base
assert "Items.STONE_PICKAXE" in base
assert "survivalAutoRepeat" in settings
assert "sendCommand(" not in bootstrap
assert "sendCommand(" not in survival
assert "sendCommand(" not in base
assert "phase = Phase.TUNNEL;" in survival
# Regressions from v3.0: mining without looking down, entering tunnel
# without any pickaxe, misclassifying a tool hotbar swap as failed mining.
assert "face(me,Vec3.atCenterOf(p));" in survival
assert "aimedSince" in survival
assert "mc.level.clip(new ClipContext(" in survival
assert survival.index("face(me,Vec3.atCenterOf(p));") < survival.index(
    "mc.gameMode.startDestroyBlock(p,miningFace);")
assert "if (!hasWorkingPickaxe(me))" in survival
assert "No usable pickaxe; " in survival
assert "lastToolSwapTick == ticks" in survival
assert "minecraft:iron_ore" in survival
assert "minecraft:coal_ore" in survival
assert "case UPGRADE_IRON" in base
assert "Items.IRON_INGOT" in base
assert "Items.STICK,new int[]{5,8}" in base
assert "FURNACE" in base and "SMELT" in base
assert "pickupTarget" in bootstrap
assert "Log broken: walking over to collect drops" in bootstrap
# v3.2: crafting table must be faced and clicks must be validated.
assert "lookForInteraction(player,table.below(),hit)" in bootstrap
assert "lookForInteraction(player,table,target)" in bootstrap
assert "supportHit=mc.level.clip(new ClipContext(" in bootstrap
assert "tableHit=mc.level.clip(new ClipContext(" in bootstrap
assert "aimedWood" in bootstrap and "woodAimTick" in bootstrap
assert "cleanupCraftingGrid(mc)" in bootstrap
assert "Returning unused recipe ingredients" in bootstrap
assert "Crafting grid has other items" not in bootstrap
assert "job.resultClickedAt=ticks" in bootstrap
assert "outputCount(mc.player,job.output)>job.startingCount" in bootstrap
assert "Pickaxe missing planks or sticks: returning to wood gathering" in bootstrap
assert "Recipe failed:" in bootstrap
assert "mc.gameMode.startDestroyBlock(treeBlock,digFace)" in bootstrap
print("Mining look-down, tool gate, tool swap and iron/furnace regression checks: pass")
# v3.3: The previous P default collided with Minecraft's built-in
# Social Interactions screen, and O with Friends (Java 26.3). A mod
# must migrate existing options.txt mappings, not just register new
# defaults (otherwise existing installs remain broken).
assert 'survivalKey = key("key.lockon.survival", InputConstants.KEY_F7)' in client
assert 'settingsKey = key("key.lockon.settings", InputConstants.KEY_F6)' in client
assert 'survivalKey.getKey().getValue() == InputConstants.KEY_P' in client
assert 'settingsKey.getKey().getValue() == InputConstants.KEY_O' in client
assert 'KeyMapping.resetMapping();' in client
assert 'mc.options.save();' in client
assert '"F7 ACTIVE #" in survival
assert 'getChat().addMessage(line)' in survival
assert 'getChat().addMessage(Component.literal("[LockOn] " + status))' in client
print("F7/F6 default key collision and saved-key migration checks: pass")
print("Survival wood-to-tools progression, repeat and no-special-commands: pass")
