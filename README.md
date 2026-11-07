# LockOn v3.5.1 — movement input repair

Minecraft Java 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Cloth Config 26.3.159, Java 25.

Replace older LockOn JARs with `LockOn-26.3-v3.5.1.jar`. F7 starts/stops survival work, F6 opens settings and U prints diagnostics. Enable automatic repeat in Survival settings.

Movement repair in v3.5.1

The survival controller no longer changes the keyboard bindings. Its movement intent is applied at the end of `KeyboardInput.tick`, updating both the input flags and movement vector. Physical movement input immediately stops autoplay and is preserved in the same player tick. F7 remains the start/stop key. Idle survival ticks leave keyboard input untouched. A sustained blocked movement request stops autoplay.

Inherited survival features follow.

- Creepers, low health and groups of three monsters trigger escape along supported terrain. After the threat clears, the bot attempts to return to the interrupted work position.
- Nearby reachable fence gates can be opened during travel.
- Each mining cycle replenishes wood and sticks and replaces missing pickaxes. Subsequent passes extend the four mining tunnels.
- Mature crops and berry bushes within 12 blocks are harvested. Dropped food and seeds are collected, and harvested crops are replanted when seeds are available. Food in the main inventory can be moved to the hotbar and eaten.
- Base crafting includes bread, iron armor, sword, axe, shovel and hoe when the resources are available. Worn iron armor can be replaced and equipped. Iron for pickaxes is reserved before armor crafting.
- Furnaces accept raw food and potatoes alongside ores, and can burn planks. An empty fuel slot no longer interrupts a furnace that is still burning.
- Storage retains supplies for replacement tools. Crafting continues when its ingredients have already moved into the grid, and avoids picking up the source again when the cursor is empty.
- The movement controller releases only keys it owns, preserving manual input when autoplay is disabled.

Start on supported surface terrain near natural trees and an existing crop or berry patch. The bot harvests existing food sources. It does not create a new farm, breed animals, or explore indefinitely to find unavailable resources. Storage capacity, blocked routes, server protections, unloaded terrain and missing supplies can still prevent progress. Escape requires a traversable route and cannot guarantee survival. Live gameplay has not been verified for this release.

The GitHub workflow compiles the Minecraft mod and runs deterministic policy and source checks. These checks do not simulate a Minecraft world.
