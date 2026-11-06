#!/usr/bin/env python3
"""Regression check for Minecraft 26.3's split attack/punch protocol.

This is a source-level contract test (not a live server test). In 26.3,
LivingEntity.swing only animates locally. Vanilla startAttack sends
ServerboundAttackPacket via gameMode.attack and follows the local swing with
the separate empty ServerboundPunchPacket to reset the server attack clock.
"""
from pathlib import Path

client = Path("src/main/java/lockon/LockOnClient.java").read_text(encoding="utf-8")
assert "import net.minecraft.network.protocol.game.ServerboundPunchPacket;" in client
assert client.count("me.connection.send(ServerboundPunchPacket.INSTANCE);") == 1
attack = client.index("mc.gameMode.attack(me, target);")
swing = client.index("me.swing(InteractionHand.MAIN_HAND,", attack)
punch = client.index("me.connection.send(ServerboundPunchPacket.INSTANCE);", swing)
attempts = client.index("attempts++;", punch)
assert attack < swing < punch < attempts, "Must mirror vanilla 26.3 attack → swing → punch order"
assert "lastAttackHold = \"attack + punch packets sent\";" in client
print("Minecraft 26.3 attack/punch packet ordering contract: passed")
