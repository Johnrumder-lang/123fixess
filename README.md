# LockOn v2.3.0 — Minecraft Java 26.3 / Fabric

Client-side assisted combat practice and configurable screen-space tracers on a consenting private server. Requires Minecraft 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Cloth Config API 26.3.159.

## Fix: particles without damage / knockback

**Critical particles do not guarantee real damage.** Minecraft 26.3 has a documented client-side critical-particle issue (MC-211708) where a particle effect can appear even when the server rejects an attack. The client cannot force PvP damage or knockback if the server does not allow it.

Changes:
- **Attack packets run in client gameplay ticks rather than HUD render frames.** This avoids sending attacks between vanilla movement updates. It also means the chosen 20–50 ms random pause is checked at tick boundaries; the actual delay can exceed 50 ms.
- **Real hitbox reach validation:** checks eye distance to the target's original bounding box and applies a small (0.08 block) safety margin before attempting an attack. No extra hitbox inflation or server reach bypass.
- **Combo mode requires recent observed target damage animations**, rather than assuming every attack particle was a successful hit. It reverts to full-charge ordinary attacks when no evidence of accepted hits is available.
- **Critical-hit jumping waits for evidence of recent accepted combat**, reducing misleading crit effects when the server rejects attacks.
- **Respects the target's visible hurt/invulnerability animation**, so the bot does not intentionally waste follow-up attempts during the animation.
- Press **U** to display attack packets attempted, possible damage animation signals, and attempts prevented by the conservative reach check. **This is a client-side estimate, not verified server damage telemetry.**

### If attacks still do no damage

1. Stand next to the other consenting player **within 2 blocks**, with both players in Survival.
2. **Disable LockOn (R)** and hit them once manually with a sword. If the manual hit causes no damage, LockOn cannot solve it: check the server's `/gamerule pvp` (it must be `true` in 26.3), team friendly-fire settings, spawn protection/region plugins, invulnerability, Weakness, etc.
3. If manual attacks work, re-enable LockOn and select the player (G). Disable jump crits (J) initially, use Precision (K), and press U after several attempts to inspect what the client actually observed.
4. Turn jump crits back on once basic attacks are confirmed.

The mod cannot override the server's reach, PvP rules, or damage immunity. The normal vanilla Survival melee reach default remains **3.0 blocks**, adjustable 2.5–3.0 from **O → Combat**.

## Fix: "inf" tracer distance

Press **O → Tracers**. Set **Unlimited tracer distance (LOADED chunks/entities only)** to ON. Save.

- The old `inf`/ `infinity` / `∞` value is still supported in the finite-range text field, but the new dedicated toggle makes activation clear.
- Player and matching entity tracers have **no mod-imposed maximum distance** as long as those entities are available on the client.
- For chests, beds and custom blocks, **all client-loaded chunks inside a large local cache search** are scanned, including high and low elevations. Missing chunks are not loaded; the mod cannot see server data not sent to it. A client-side mod cannot provide literally infinite-world ESP.
- Tracers now display **screen-edge direction lines for targets behind the camera**, rather than silently hiding them until you face them.
- To reduce FPS impact, block scanning remains incremental and is capped at **1,800 block tracers** per scan; custom entity lines are capped at 700. Results can appear progressively, and large worlds may exceed the rendering cap.

Tracers can be enabled separately for players (red), chests (gold), beds (blue), custom block IDs (purple), and custom entity IDs (green). All are OFF by default, and no combat lock is required to use them.

### Settings persistence
These choices are saved to `config/lockon.properties` inside your Modrinth instance. Verify `traceDistance=inf` if you use the unlimited option.

## Controls

| Key | Action |
|---|---|
| R | Toggle combat lock-on |
| G | Select/clear target |
| H | Smart chasing |
| J | Toggle critical-hit jump attempts |
| K | Combo / Precision attack mode |
| B | Optional destructive obstacle breaking (OFF by default) |
| O | Settings including reach and separate unlimited tracer switch |
| U | Combat attempts and observed hurt animations |

Sword and fist combat, manual bow shooting with predictive lock-on, A* navigation, sprint knockback, and optional obstacle breaking remain.

## Installation

After a successful [GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml), remove all earlier LockOn JARs from `mods/` and install `LockOn-26.3-v2.3.0.jar` into the same Modrinth Fabric 26.3 profile as Fabric API and Cloth Config.

The build and offline tests cannot establish what a particular Paper server will accept, or guarantee in-game GUI/overlay behavior. Practice only on an agreed private server.

### References
- Mojang issue [MC-211708](https://bugs.mojang.com/browse/MC/issues/MC-211708): critical particles without actual player damage.
- Mojang 26.3 changes around player attack prediction and sprint knockback: [MC-311799](https://bugs.mojang.com/browse/MC/issues/MC-311799).
