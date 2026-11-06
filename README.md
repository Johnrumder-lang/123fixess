# LockOn v2.0.0 — Minecraft 26.3 / Fabric

Client-only assisted PvP practice mod. Requires Java 25, Fabric API 0.165.2+26.3, Fabric Loader 0.20.1, and Cloth Config API 26.3.159. Use with the permission of the server owner and other players.

## New: Vanilla Survival reach that actually works

**Default sword attack reach is 3.0 blocks**, the normal maximum for an unmodified Survival sword. The earlier 5.0-block default was the *Creative* maximum, which a default Survival/Paper server may reject. **O → Combat** allows a conservative 2.5–3.0 block reach setting (3.0 default). Existing 3.1/5.0 saved values are automatically limited to 3.0 when you load this version. This is client-side targeting and does not bypass a server's checks.

Minecraft 26.3 also supports `minecraft:attack_range` components for custom weapons, and spears have different mechanics; this bot still attacks with swords. The 3.0 cap matches ordinary Survival swords, rather than every custom weapon configuration.

## New: crosshair-to-target tracer lines

Press **O → Tracers**. Each feature is an independent toggle:

- **Players (red)** — a red line to each other loaded player within tracer distance
- **Chests (gold)** — chest, trapped chest, Ender chest and other blocks whose registered name contains `chest`
- **Beds (blue)** — colored vanilla beds, including both bed parts
- **Custom blocks (purple)** — enable this and type comma-separated block IDs, e.g. `minecraft:diamond_ore, minecraft:spawner`
- **Custom entities (green)** — enable this and type entity IDs, e.g. `minecraft:zombie, minecraft:creeper, minecraft:armor_stand`
- **Tracer distance** — 8 to 32 blocks, default 24

Each enabled tracer is drawn from your screen center/crosshair toward the screen position of the target. Off-screen targets **in front of you** are indicated toward the edge of the screen. Targets directly behind the camera are not shown until you turn. The overlay uses only nearby entities and block states sent to the client, and may draw through walls, but **cannot reveal unloaded chunks or unknown server data**.

Block scanning is incremental and capped (160 block markers and 100 custom entity tracers) to reduce stuttering; nearby chest/bed/custom-block tracers may take a short time to update after moving. HUD indicators work best in first-person view. Tracers are **OFF by default** and can be enabled separately without turning on combat lock-on.

Custom IDs are regular registered Minecraft IDs. Multiple comma-separated IDs can be used. All settings persist in `config/lockon.properties`.

## Other controls

| Key | Action |
|---|---|
| R | Toggle combat LockOn |
| G | Select or clear a player |
| H | Smart chase and strafe |
| J | Timed critical jump attempts |
| K | Combo / Precision attack mode |
| B | Destructive obstacle breaking (OFF by default) |
| **O** | **Combat range and tracers settings** |
| U | Practice diagnostics |

All previous v1.9.0 combat, bow lead, pathfinding and testable tactics behavior is retained.

## Install

1. Open your Minecraft 26.3 Fabric instance in Modrinth. Keep Fabric API and Cloth Config installed.
2. Remove all previous LockOn JARs from the `mods/` folder.
3. After the [GitHub Actions Java 25 build and tactics tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) pass, download `LockOn-26.3-v2.0.0.jar` and copy it into `mods/`.
4. Launch, press **O**, use the **Tracers** category to enable categories individually and save. For reliable vanilla sword hits, use 3.0 reach.

The build verifies compilation, not runtime visuals. Test with a visible player, one chest and a bed in a local practice world. Fabric, render mods and camera FOV settings can affect projection accuracy. The HUD does not claim to render actual world-space lines behind the camera.
