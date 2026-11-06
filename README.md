# LockOn v2.4.0 — Minecraft Java 26.3 / Fabric

A client-side combat practice mod with optional tracers. Intended for private worlds or servers where the owner and participants consent. Requires Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Cloth Config 26.3.159.

## Searching the *whole server*

**A client-only mod cannot discover players or blocks in chunks that the multiplayer server has not sent it.** Selecting `inf` removes LockOn's own range limit, but does not grant access to the server's full world. There is no legitimate purely client-side setting that overrides this.

### New in v2.4.0: administrator-exported world-wide block markers

For **a server you administer**, v2.4.0 includes a standard-library Python exporter that reads **saved Anvil region files** and produces an index of saved chest, bed, and selected custom-block locations across the explored server map. The client can draw their tracer directions even when the original chunks are no longer loaded.

**Server administrator procedure** (requires access to server files and permission to inspect/share the map):

1. Stop the server, or use a consistent backup of its save directory.
2. From the cloned GitHub repository, run Python 3 against the saved world directory:
   ```bash
   python3 tools/export_server_world.py /path/to/server/world --output lockon-server-index.tsv --block minecraft:diamond_ore
   ```
   The optional `--block` flag is repeatable for additional custom blocks; chests and beds are indexed automatically. For a normal vanilla world, the exporter scans `region/`, `DIM-1/region/`, `DIM1/region/`, and namespaced `dimensions/.../region/` save directories. Servers such as Paper may store dimensions in separate world directories; export those separately if needed.
3. Copy `lockon-server-index.tsv` to your **Modrinth instance's** `config/lockon-server-index.tsv`.
4. In Minecraft, press **O → Tracers**, switch on **Administrator world index (saved blocks, requires TSV export)** and individually enable **Chests**, **Beds**, and/or **Custom blocks**. Enable **Unlimited tracer distance** as well, then save.
5. Imported markers update from the TSV file periodically. Re-export after significant world changes.

**Important limitations:** This index is a read-only *snapshot*, not live surveillance. It does **not** reveal real-time positions of players or moving mobs anywhere in the server. Obtaining those from beyond entity tracking distance would require an explicit, authorized server-side live-data integration. It does not search unexplored/generated-on-demand chunks, other people's files, or any inaccessible server. Some alternative region compression formats (for example LZ4) may not be processed by the standard-library exporter. Sharing the index exposes saved structure locations, so treat the file as administrator-controlled data.

### Performance and capabilities

- The importer accepts a maximum of **200,000 saved-world markers** from an index file up to **32 MB**.
- It projects the **900 nearest matching imported markers** per screen refresh to avoid rendering enormous numbers of lines.
- Existing client-streamed markers still appear separately using the original chunk scanner.
- All categories remain individually toggleable, including players (red), chests (gold), beds (blue), and configurable blocks/entities (purple/green).
- The existing `inf` mode traces **client-loaded players and mobs without a mod distance cap**. The optional saved-world index extends **static block** coverage only.
- Imported markers match the current Minecraft dimension. They do not automatically identify which multiplayer server the index came from. Enable the imported index only in the matching server/profile.

## Combat and movement retained

- Vanilla Survival sword/fist reach setting **2.5–3.0 blocks** (3.0 default); no bypass of server validation.
- Sword and empty-fist combat, 86%-charged follow-ups when a recent hurt animation is observed, full-charge normal hits, 20–50 ms randomized wait checked on game ticks, optional timed crits, sprint chase.
- A* route finding, one-block step jumps, opt-in block mining (off by default), predictive bow aim (manual draw/fire).
- **U** displays attempted packets and observed damage animations. Client-side crit particles do **not** prove the server accepted a hit.

## Controls

| Key | Action |
|---|---|
| R | Toggle combat LockOn |
| G | Select / clear target |
| H | Chase / strafe |
| J | Jump-crit attempts |
| K | Combo / Precision |
| B | Optional obstacle breaking (off by default) |
| **O** | Settings: tracer categories, `inf`, admin world index |
| U | Practice diagnostics |

## Install

Remove older LockOn JARs from your Modrinth instance's `mods/` folder. After the [GitHub Actions build and tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) pass, install **`LockOn-26.3-v2.4.0.jar`** alongside compatible Fabric API and Cloth Config.

A successful compilation and automated scanner tests do not substitute for testing the imported markers inside Minecraft. Use only when the server administrator has explicitly authorized access to the world index.
