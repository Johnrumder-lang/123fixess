# LockOn v2.7.0 — Minecraft Java 26.3 / Fabric

Client-only consent-based PvP practice mod with Paper server player-feed option. Requires Java 25, Minecraft 26.3, Fabric API 0.165.2+26.3, Fabric Loader 0.20.1, Cloth Config API 26.3.159.

## Fix: only the first punch worked
- **Reliable fist follow-ups:** Vanilla empty-hand attacks charge much faster than the 11-tick immunity window. If the client cooldown animation fails to update after the first hit, the mod now uses a safe **11-tick elapsed-time fallback** for fists to avoid getting permanently stuck. The server still determines what deals actual damage.
- **Point-blank hitbox fix:** When the camera is inside the opponent's hitbox, the ordinary segment-to-box intersection can return empty. That case is now accepted if the opponent is in reach and there is line of sight.
- The normal target-immunity gap, sword cooldown checks, 20–50ms randomized extra delay and vanilla Survival reach (up to 3 blocks) stay intact.
- **U diagnostics** now distinguish attempts, observed hurt animations, out-of-range skips, aim-ray misses, recovered fist cooldowns and mined blocks. These are local observations rather than verified server damage.

If punches still fail, first check *manual* fist attacks against a consenting player within 2 blocks. A client-side mod cannot bypass PvP restrictions, region protection, invulnerability, armor, or server anti-cheat.

## Fix: B mining did nothing
Previously **B** only allowed mining when bounded A* *chose* a route through a block. The pathfinder strongly preferred jumping or walking around, so it could leave mining enabled without ever mining.

**New controls:**
- **B**: Enable/disable permitted block breaking (OFF by default).
- **N**: While **B is ON**, aim at a nearby eligible block (within 4.2 blocks) and press **N** to mine it until broken. No selected combat target is needed.
- With **B ON** and chase enabled, LockOn also mines a **directly obstructing eligible block** in the route toward the selected target rather than waiting for A* to choose a mined route.
- Mining now issues the start/continue block-destroy actions over successive client ticks, with an appropriate block-face direction. The bot does not mine containers/block entities, fluids, dangerous blocks, unbreakable blocks or blocks above the small hardness limit.

Blocks still obey Survival break time, tool effectiveness, and any server protection plugins. A fist will break some blocks **slowly**; bring a suitable pickaxe for stone. Block mining cannot bypass permissions. Repeated punches against players and mining blocks are separate actions: mining temporarily pauses combat.

## Existing features
- **O**: reach (2.5–3.0), local/inf tracers and the optional server-wide feed for opt-in players on an administrator-controlled Paper server.
- **R**: LockOn toggle; **G**: select target; **H**: smart chase; **J**: critical jumps (OFF by default); **K**: Combo/Precision; **U**: diagnostics.
- Predictive bow aiming (manual draw/shoot), pathfinding and stop-before-ledge checks.
- The removed teleport feature is still absent.

## Installation
After the [GitHub Actions build and tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) pass, remove old LockOn client JARs and install **`LockOn-26.3-v2.7.0.jar`** in the Fabric 26.3 Modrinth `mods/` directory. Leave Fabric API and Cloth Config installed. The Paper server companion (if you use it) is built separately and belongs in the Paper `plugins/` directory, not in `mods/`.

Automated build/testing does **not** verify real-world mining permissions or that a server accepts every attack. Test with a consenting player and a simple dirt/wood obstacle on an authorized private world.
