# LockOn v3.4.0 — survival automation input and mob-defense fixes

For Minecraft Java 26.3 Fabric, with Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Cloth Config API 26.3.159.

## Why it displayed tasks but didn't act

In earlier builds, a status message only meant the **task-selection code was running**. It did not show whether the movement keys actually reached Minecraft's player-input tick, whether a collision prevented movement, or whether the bot had already paused for hunger.

New in this release:
- **F7** toggles autoplay, and **F6** opens settings, avoiding the vanilla P/O social-screen key conflicts. Existing saved P/O LockOn bindings are migrated to F7/F6. Press **U** for detailed chat diagnostics.
- **Persistent movement control:** Forward, jump, and sprint decisions are replayed in the Fabric `START_CLIENT_TICK` event, **before the player samples keys**, as well as at the end of the bot's decision tick. This is normal keyboard movement, not teleportation or a server exploit.
- **Movement feedback:** **U** now prints `movement requested=true/false`, `keyDown=true/false`, the observed per-tick distance moved, and how many ticks movement has remained stuck. If the bot keeps asking to walk but makes no progress after about 70 ticks, it stops with an explicit explanation.
- **Hostile-mob defense runs before routine crafting/hunger:** Earlier code could pause due to modest hunger or go into base-menu processing before attempting combat, even with a zombie nearby. Monsters now interrupt routine work when health allows. The bot aims and waits for rotation to reach the server, equips its sword when available, and performs normal Minecraft attack + punch packet actions. Creepers are not intentionally approached at close range.
- **Hunger safety tuned:** It no longer stops all resource collection merely because the player has 14 food points (seven hunger icons). It only demands food when food is much lower.
- **Wood and tools progression retained:** Search nearby natural trees, chop logs, collect dropped wood, convert planks, craft a workbench, sticks, and a wooden pickaxe before starting tunnels; then acquire cobblestone and attempt a furnace, smelting and upgrades. Every stage still depends on actual resources and normal server block/recipe permissions.

## Testing the bot

1. **Delete all older LockOn JARs** and put only `LockOn-26.3-v3.4.0.jar` in your Minecraft 26.3 Fabric instance's `mods/` folder.
2. Start on flat land in Survival next to a natural tree, with at least 10 food points and preferably full health. Keep the game screen visible.
3. Press **F7**, not P. You should see a start message in chat and an **`[LockOn F7 ACTIVE #ticks]`** heartbeat every second.
4. Press **U**. The new debug line shows `Survival: BOOTSTRAP ...` as well as `movement requested`, `keyDown`, `moved`, and `stagnant`. This reveals whether the failure is input, pathfinding, wood visibility, recipe interaction, or hunger.
5. Try the same test with a consenting private Survival world. Disable autoplay with **F7** immediately if it approaches a dangerous area.

If F7 produces no start message, check Minecraft **Options → Controls → Key Binds** for a conflicting F7 assignment. If the heartbeat is visible but the bot doesn't move, send the **entire U chat line**; the input values tell us whether Minecraft applied its movement keys.

This is still an experimental client-side gameplay bot, **not an independently verified self-playing Minecraft agent**. Compilation and deterministic unit/source tests alone cannot establish working live GUI crafting, pathfinding, navigation or hostile combat; it can still become stuck or die. Never assume it can safely play unattended.

Survival automation **does not require OP, commands, or the Paper server plugin**. All actions use ordinary Survival player permissions. The separately packaged optional Paper companion is only for administrator-controlled, opt-in distant-player tracers.

[GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml).
