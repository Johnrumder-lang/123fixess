# LockOn v2.9.0 — Minecraft 26.3 Fabric

A **bounded, opt-in Survival Mining mode** added alongside the existing consensual PvP lock-on and tracers. Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Cloth Config API 26.3.159 required.

## New: P — survival strip mining

**P** turns the mode on/off. It disables PvP lock-on and records a **base/home at the location where P was first pressed**, facing in your current direction. This is your *run's starting location*, not necessarily the server's world-spawn coordinate.

The bot then:
- Digs a **2-block-high strip-mining tunnel** in the direction you faced when starting, for the selected length (**O → Survival Mining → Strip length**, default 32, max 64).
- Checks neighboring **client-loaded, visible ores within reach** and mines matching blocks whose IDs appear in the existing **O → Tracers → Custom blocks** field, such as `minecraft:diamond_ore`, `minecraft:iron_ore`, `minecraft:deepslate_diamond_ore` and `minecraft:gold_ore`. Use commas between IDs. If no valid pickaxe is in the hotbar, the run pauses rather than destroying valuable ore with a fist. A pickaxe is required for mining.
- Mines using vanilla start/continue block-breaking actions and ordinary walking inputs, never bypassing server protections. The strip is bounded: at the end or when the inventory is nearly full it returns along its original tunnel and starts home-base work.
- Can defend against nearby **hostile mobs only** (not players, pets, passive creatures). It uses the normal Minecraft 26.3 `ServerboundAttackPacket` + `ServerboundPunchPacket` sequence, attack cooldowns, and a hotbar sword when available.
- Tries to eat safe cooked/vegetarian food from your hotbar when hungry; pauses or retreats below low-health thresholds. Stops for nearby lava/fire, falls, empty support, fluids, inaccessible chunks and unbreakable blocks.

**Safety limitation:** These are defensive checks, **not an immortality mode**. The bot can still die due to mobs, creepers, falls, sudden lava exposure, lag, server changes, or missing supplies. Do not leave it unattended or use it around valuable builds.

## New: back-at-home storage, smelting and crafting

After a strip run, the bot returns to the location where it started and tries to:

1. Place a **crafting table, chest and furnace** in valid nearby empty spaces on supported ground, using actual items in its inventory. If a crafting table is missing but it has at least 4 planks, it attempts to craft one in its 2×2 inventory grid. If it has the crafting table and 8 planks, it attempts to craft a chest; with 8 cobblestone it attempts to craft a furnace.
2. Open the furnace and put raw iron, raw gold or raw copper ores and **coal or charcoal** into the proper menu slots, then collect smelted results. The furnace work has a bounded timeout: a large batch might not finish in one visit.
3. Use the crafting table to attempt the **9-to-1 block recipes** for iron/gold/copper ingots, diamonds, emeralds, coal, redstone and lapis lazuli, when at least 9 matching materials are together in one stack.
4. Open the chest and **deposit nonessential inventory stacks**, retaining weapons, pickaxes and food. A full chest, protected block, unavailable items or failed container interaction can prevent completion.

All placement, mining, smelting, chest and crafting interactions use ordinary Survival inventory and block actions and require legitimate resources and permissions. The bot **does not conjure a chest or furnace**. If you have no table/planks/cobblestone/fuel available, some base stages are skipped; the HUD reports missing materials. Locations are simple offsets from your start position, so start on flat, open ground rather than inside a crowded build or dangerous cave.

The base workflow uses GUI container clicks. It is **experimental** and has not been tested on a live Paper server. Other client mods or plugins can interfere with container opening and slot placement, especially around lag. Inspect your chest, furnace and resulting item stacks after each run rather than assuming the processing succeeded.

## How to use

1. Choose the ore IDs under **O → Tracers → Custom blocks**. You do **not** need to enable the visual tracer toggle for mining.
2. In **O → Survival Mining**, set tunnel length and independently enable/disable hostile defense, auto-food, chest storage, smelting and block compaction.
3. Put a suitable pickaxe, a sword (optional), food, and base materials in your hotbar/inventory. Have floor space around the starting point. **P** starts/stops the survival run.
4. **U** while the survival mode is active shows its current phase, mined blocks, attempted hostile hits and base-processing status.
5. Test with an 8-block run in an isolated, protected practice area before increasing the strip length.

**It will not mine beyond loaded chunks or detect ores the server never supplied to the client.** It does not modify world spawn or teleport. It stops after completing a bounded strip-and-return run; to mine another tunnel, face a new direction and press **P** again.

## Existing controls

| Key | Function |
|---|---|
| **P** | **Toggle bounded survival strip-miner / hostile defense / home processing** |
| R | Toggle PvP combat LockOn (disabled when P starts) |
| G | Select/clear PvP player target |
| H | Smart PvP chase |
| J | Critical-hit jump attempts (OFF by default) |
| K | PvP combo/precision mode |
| B | Optional direct PvP obstacle breaking (OFF by default) |
| N | Manually mine an aimed block when B is ON |
| O | Reach, tracers and Survival Mining settings |
| U | Current diagnostics |
| V | Unassigned; teleport functionality remains removed |

## Installation

After the [GitHub Actions Fabric build and tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeed, remove all old LockOn JARs and install **`LockOn-26.3-v2.9.0.jar`** in your Fabric profile's `mods/`. Keep Fabric API and Cloth Config installed.

The separate optional **`LockOn-Server-Paper-26.3-v2.9.0.jar`** handles authorized, opt-in server-wide player-position tracers; it is **not** needed for survival mining and belongs in the Paper server's `plugins/` directory. It uses ordinary server permission checks.

The offline Java tests cover decision thresholds and ore/material classifications, not the outcome of live mining, combat, crafting or chest placement.
