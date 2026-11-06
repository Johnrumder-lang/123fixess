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
print("Survival wood-to-tools progression, repeat and no-special-commands: pass")
