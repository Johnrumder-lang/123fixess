# LockOn v3.0.0 — Autoplay Survival progression (Minecraft Java 26.3)

Fabric client-side Minecraft 26.3 assistant for **permitted, normal Survival gameplay**. Requires Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Cloth Config API 26.3.159.

**Survival mode does not need OP, a server-side mod, or special commands.** It uses the same movements, breaking, crafting, item-use, and container actions a regular Survival player can perform. It cannot override protected regions, server anti-cheat, blocked recipes, unloaded chunks, other players' possessions, or server rules.

## Fix: P used to finish without doing anything

Earlier versions started directly in the strip-miner and could end a single base-processing pass without first gathering tools or building anything. **v3.0.0 starts with a wood-first bootstrap**, then mines and repeatedly processes the base.

### From an empty inventory

Press **P** on safe, reasonably open land **near a natural tree (within about 13 blocks)**. The autonomous mode now:

1. **Locates a nearby natural log** in client-loaded chunks (checks for leaves, to avoid ripping apart an unrelated log house), walks toward it, and mines with the currently held item/empty hand. It gathers wood using real normal block-breaking actions, and the drops must actually enter the player's inventory.
2. **Crafts wooden planks** from collected logs via the vanilla 2×2 inventory crafting grid, using actual slot clicks.
3. **Crafts a workbench** from four planks, moves it into a hotbar slot and attempts to place it at the recorded home base.
4. **Crafts sticks** and then **a wooden pickaxe** using the real 3×3 crafting-table grid. No items are generated for free.
5. **Digs a staircase into the ground**, followed by a two-block-high strip. The default strip length is 32 blocks (8–64 configurable). It also attempts visible nearby ores matching the IDs in **O → Tracers → Custom blocks**. It checks vanilla tool harvesting tiers before breaking valuable ores, and moves better crafted pickaxes from inventory into the hotbar.
6. **Returns along the same staircase waypoints** and attempts a small **utility base** with a workbench, chest and furnace in diagonal positions beside the home marker. This avoids blocking the four cardinal mine corridors.
7. When enough materials exist, it attempts to **upgrade to a stone pickaxe**, smelt raw ores using fuel, compact suitable items into blocks, and store nonessential inventory in its chest. Then, with Auto-repeat ON, it heads out again in a new direction.
8. The four directions are visited in rotation. Later runs increase the staircase's depth, up to a bounded maximum. The run **does not stop automatically just because the first trip ended**. Press P again to stop, or turn off Auto-repeat in the settings.

The bot never teleports. The base is recorded where **P** is first pressed, **not necessarily world spawn**. This is a resource-gathering and compact workstation base, **not an automatically designed full house**.

### Survival controls and safety

- **P:** start/stop autoplay. Starting stops the PvP lock-on movement. Repeated pressing of P stops automated mining and closes its owned crafting screen.
- **O → Survival Mining:** auto-repeat (ON by default), strip length, hostile-mob defense, auto-eating, chest storage, furnace use and block compaction.
- **O → Tracers → Custom blocks:** comma-separated preferred ore IDs, e.g. `minecraft:coal_ore, minecraft:iron_ore, minecraft:deepslate_iron_ore, minecraft:diamond_ore`.
- **U:** shows which phase the bot is in (BOOTSTRAP / TUNNEL / RETURN / BASE / PAUSED), wood and recipe progress, blocks mined, hostile encounters, and the most recent reason for stopping.
- Health/hunger, lava, fire, oxygen, drops, world-loading and safe-footing checks remain. The bot attempts to defend itself from hostile mobs and eat suitable **food already in its hotbar**. It does **not** yet reliably farm new food from scratch or guarantee survival. A creeper, fall, hostile mob or unexpected server behavior can still kill it.

### Important limitations of automated play

This is **experimental** and a normal client cannot autonomously explore or build arbitrary structures from *unknown, unloaded terrain* with guaranteed success. The wood search is local; if no accessible natural tree is within about 13 blocks, the bot reports that it cannot find wood and periodically retries. It does not falsely announce a completed mining run. If the base's workstation positions are occupied or the inventory/grid contains unexpected items, it reports the obstruction instead of silently overwriting valuable blocks or items.

Inventory and container automation is inherently sensitive to server timing, plugin rules and another client's UI changes. **Build and offline tests check compilation and state rules, not whether a chest actually opens, or a tool/recipe completes in a live multiplayer world.** Test on a disposable private Survival world before using valuable gear or leaving P active unattended.

### Suggested first test

Start a clean Survival world with a tree within 5 blocks, full hunger and health, open flat dirt/grass near your starting spot, and no inventory UI open. Press P, then U to monitor progression. Watch it collect actual logs and use the crafting grid before increasing the strip length. If a recipe or path is blocked, the on-screen status explains the current step rather than reporting false completion.

## Other features retained

PvP target locking and regular 26.3 fist/sword packets (R/G/H/J/K), optional manual block mining B then N, HUD tracers including `inf` within streamed data, and the optional administrator-controlled player-location feed.

**Optional** `LockOn-Server-Paper-26.3-v3.0.0.jar` remains **only for consented server-wide player tracers**. **Autoplay Survival itself needs no server plugin and no elevated permissions.**

## Installation

After the [GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) reports success, remove all previous LockOn client JARs and install **`LockOn-26.3-v3.0.0.jar`** in the Fabric 26.3 Modrinth instance's `mods/` directory along with Fabric API and Cloth Config. Don't install multiple LockOn versions together.

Autoplay follows normal server rules; the presence of the mod never grants permission to mine protected blocks or automate gameplay where the server owner forbids bots.
