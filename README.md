# LockOn v2.2.0 — Minecraft 26.3 / Fabric

Client-side assisted PvP practice and optional HUD tracers for private worlds/servers where the owner and players consent. Requires Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Cloth Config API 26.3.159.

## NEW — Infinite tracer distance

Press **O → Tracers**. The **Tracer distance: 8-32 or inf** text setting now accepts the literal **`inf`** (also `infinity` or `∞`). Click Save. It persists in the Modrinth instance's `config/lockon.properties` as `traceDistance=inf`.

- **Numbers 8–32:** Keep the old finite distance limit in blocks (for example `24`).
- **`inf`:** Remove the mod's distance filtering for **all players and matching entities already loaded by your client**. For chests, beds and custom blocks, incrementally search every currently loaded chunk within the **client's render-distance footprint**, checking its entire vertical build height.
- **Important: not literally infinite world visibility.** The server only sends nearby chunks and loaded entities. The mod cannot discover distant players/blocks in chunks the server has never sent, and it does not forcibly load chunks or bypass server rendering/tracking limits.
- Tracers remain split into individually toggleable categories: red players, gold chests, blue beds, purple custom blocks, green custom entities.
- Performance safeguards: blocks are scanned by loaded chunks, nearest chunks first, and empty/irrelevant chunk sections are skipped using palette checks. Scan work is budgeted per tick; markers gradually appear as chunks are inspected. **Up to 600 block markers and 300 matching custom-entity markers** can be drawn per cycle to prevent severe frame stutters.
- Infinite mode may take longer to find distant beds, chests or custom blocks; you can lower render distance or choose a finite range for best FPS. The overlay only projects targets in front of your camera; turn around to see targets behind you.

## Other features retained

- LockOn aims at players and supports swords or bare fists, with combo-aware cooldown, critical jumps, sprint knockback, and chase/pathfinding. Bow lock-on assists aiming; you still fire manually.
- Normal unmodified vanilla Survival melee reach: up to **3.0 blocks** (O → Combat, adjustable 2.5–3.0). The server validates reach.
- A* navigation and obstacle breaking (OFF by default).
- U practice statistics.

## Controls
| Key | Function |
|---|---|
| R | LockOn on/off |
| G | Select/clear combat target |
| H | Smart chasing |
| J | Jump-crit attempts |
| K | Combo / Precision mode |
| B | Automatic obstacle breaking (OFF by default) |
| **O** | **Settings: tracer distance `inf`, categories, and combat reach** |
| U | Practice statistics |

## Install

After the [GitHub Actions build and tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) complete successfully, remove all older LockOn JARs from your Modrinth Fabric 26.3 `mods/` folder and install **`LockOn-26.3-v2.2.0.jar`**. Keep Fabric API and Cloth Config installed.

Run the game, press **O → Tracers**, set distance to **`inf`**, choose the categories to trace, then Save. Infinite tracing does **not** require combat LockOn to be active.

Java compilation and deterministic tactics tests do not establish in-game visual correctness; verify overlays and performance in an actual world first.
