# LockOn v3.1.0 — Survival mining and crafting reliability fixes

Minecraft Java **26.3** Fabric client mod. Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Cloth Config API 26.3.159. Existing PvP/tracers remain.

## Why 3.0 kept walking and wasn't crafting/mining

The 3.0 Survival bot had three clear defects:
1. Its miner sent a block-destroy action **before aiming the camera toward the block**. On a real Survival server this can cause repeated dig attempts to fail, especially while digging downward.
2. **Continuous mining restarted without checking that a pickaxe was still available.** A missing or nearly broken pickaxe didn't trigger a tool-crafting phase.
3. **Equipping a newly crafted pickaxe from the main inventory returned a 'can't mine' result.** The bot could treat that normal swap as a fatal obstruction instead of waiting for it to reach the hotbar.

### Changes in v3.1.0

- Every mining target is aimed at before digging. The bot turns its **yaw and pitch** toward the actual block center, waits for the vanilla look packet to be sent, raycasts to the exposed face, then issues normal start/continue mining actions. If it cannot see or reach the block, it stops and reports why rather than faking progress.
- **Hard pickaxe preflight:** A usable pickaxe must be in the inventory before each new tunnel. If it breaks midway, the bot returns to its start point to make another. It excludes tools with three or fewer durability points remaining.
- **Tool hotbar swaps:** Allow a synchronization tick after moving the correct tool from inventory to hotbar without declaring a mining failure.
- **Coal and iron automatically targeted:** Adds common coal, iron, copper and other Overworld ore IDs to the survival search regardless of old tracer custom-block settings. Correct tool-tier requirements are checked before harvesting ore.
- **Wood collection:** After breaking a tree log, the bot moves close enough to pick up dropped items. It prefers visible trunk segments rather than blindly trying to punch through leaves/other logs.
- **Movement watchdog:** A tunneling waypoint with no movement progress after roughly five seconds stops with the blocked waypoint location instead of walking forever.
- **Base upgrades:** After mining enough cobblestone, it tries to craft a stone pickaxe and an eight-cobblestone furnace. With fuel and harvested raw iron it attempts furnace smelting; it can then craft an **iron pickaxe** using three iron ingots and two sticks, making additional sticks from planks if needed. Chest storage, workbench placement and resource compaction remain available.
- **U diagnostics:** Shows the current mode and reason, such as `Aiming at x,y,z`, `Mining x,y,z`, `No usable pickaxe`, or `Block not breaking after 12s`.

### Controls

**P** starts or stops the autonomous Survival progression. **O → Survival Mining** controls auto-repeat, strip length, mob defense, auto-eating, smelting, storage and block compaction. **U** shows status. The existing PvP keybinds still work when autoplay is off.

### Requirements and limitations

The mod operates using **ordinary Survival interactions**, like a player: it does not need operator privileges or a server-side plugin for wood gathering, mining or crafting. The server still enforces region protection, chunk loading, actual block-break timing, recipes and anti-cheat rules. The optional Paper companion remains *only* for the administrator-authorized live player-position tracer.

For a practical test, stand within 5 blocks of a natural tree in a private Survival world, with full hunger, on level ground. Empty the inventory if testing from scratch; press **P**, then **U** to see it gather logs and craft a table, sticks, and a wooden pickaxe before tunneling. After stone has been mined it should attempt a furnace and stone pickaxe, and later smelt iron when raw iron and fuel have been collected. The mod does **not** create items for free and cannot smelt without fuel.

**This release compiles and includes offline regression tests, but does not claim live multiplayer end-to-end verification.** Minecraft menu transactions, pathfinding, terrain hazards and crafting can still fail or be blocked. Use it in a disposable private world first and monitor each stage. It does not guarantee that you won't die or automatically finish Minecraft.

## Installation

Remove all previous LockOn client JARs from your Fabric 26.3 `mods/` folder, then install **`LockOn-26.3-v3.1.0.jar`**. Keep Fabric API and Cloth Config installed. The separate optional `LockOn-Server-Paper-26.3-v3.1.0.jar` is not needed for Survival.

[Build and test results](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml).
