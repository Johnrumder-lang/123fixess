# LockOn v2.8.0 — Minecraft Java 26.3 / Fabric

Client combat practice mod with optional tracers and optional Paper server companion. Intended for consenting PvP practice on private servers. Requires Minecraft 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Cloth Config API 26.3.159.

## Fixed: only the first punch damages the opponent

**Found the protocol bug:** Minecraft Java 26.3 changed how ordinary swings are sent. The client must send TWO packets in order after an automated attack:

1. `MultiPlayerGameMode.attack(player, entity)` sends `ServerboundAttackPacket` with the entity id.
2. `player.swing(..., false)` animates locally. In Minecraft 26.3 **this no longer sends the swing packet itself**.
3. `player.connection.send(ServerboundPunchPacket.INSTANCE)` sends the NEW dedicated punch packet that vanilla Minecraft sends as part of the attack. This packet is used by the server for swing animation and resetting attack-strength timing.

**Older LockOn versions sent steps 1 and 2 but omitted step 3.** This release mirrors the 26.3 vanilla attack sequence, including the missing `ServerboundPunchPacket`, for both **empty-hand fists** and **swords**.

We also added a regression test (`tools/test_punch_protocol.py`) that checks the ordering and prevents future versions from removing the packet inadvertently. Normal cooldown, target damage immunity, conservative reach validation and in-game statistics are preserved. Don't install this alongside another old LockOn JAR.

The server still decides whether a hit damages someone. This patch follows the vanilla protocol rather than increasing reach or bypassing PvP settings. Build success does NOT prove in-game hit reliability until tested against a real player or server.

### Practical validation

1. Start a consenting private match with two Survival players, PvP enabled.
2. Hold **nothing** in the main hand. Select the opponent with **G** and enable combat with **R**.
3. Remain within approximately 2 blocks. Press **U** after several seconds to see attack attempts and blocking reasons. With the fix, subsequent swings should now include the same full packet sequence as the first punch.
4. If attack attempts increase but no damage occurs, test manual punches in the same conditions and inspect server PvP/region/anti-cheat settings. If attempts stay at one, the **U** message's `hold:` value identifies whether cooldown, aim or range is blocking another attempt.

## Controls retained

| Key | Function |
|---|---|
| R | Enable/disable combat LockOn |
| G | Select/clear player target |
| H | Chase/strafe |
| J | Crit jumps (OFF by default) |
| K | Combo/Precision |
| B | Enable optional block breaking (OFF by default) |
| N | Mine targeted eligible block while B is ON |
| O | Combat, tracer and server-fed player settings |
| U | Attack attempts, hit signals and last blocking reason |

The V teleport feature remains removed.

## Tracers

Player, chest, bed, custom block/entity tracers remain available under **O**. `inf` uses client-loaded data only. Full-map live player directions require the separate authorized Paper server companion, permission `lockon.view`, and consent via `/lockonshare on`. The plugin is still packaged separately (only its release version is updated).

## Installation

After the [GitHub Actions CI build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) passes, install **`LockOn-26.3-v2.8.0.jar`** in your Minecraft 26.3 Fabric instance's `mods/` folder. Remove all previous LockOn client JARs first. Keep Fabric API and Cloth Config installed.

The optional **`LockOn-Server-Paper-26.3-v2.8.0.jar`** is for the server's `plugins/` directory, not the Fabric mods folder.

Minecraft 26.3 reference: the vanilla client sends `ServerboundAttackPacket`, locally animates the swing and then sends `ServerboundPunchPacket.INSTANCE` from `Minecraft.startAttack` (see [26.3 Minecraft source](https://mc-packet-reference.netlify.app/26.x/source/net/minecraft/client/minecraft/)).
