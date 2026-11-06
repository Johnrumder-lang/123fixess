# LockOn v1.4.0 — Minecraft 26.3 / Fabric

This client-only PvP practice mod is intended for a **private server with consent**. Requires Java 25, Minecraft 26.3, Fabric Loader 0.20.1 and Fabric API 0.165.2+26.3.

## Changes
- **Fast lock-on aiming** tracks player movement.
- **Critical-hit attempts**: when a full-charge sword attack is ready, the mod can jump and time a hit during the falling phase (normal Minecraft mechanics). Not every attack can be critical; low ceilings, terrain and movement can prevent it.
- **Improved combo spacing**: orbit strafes, preserves about 2–3 blocks of distance, and resets sprint briefly after attacks.
- **A* navigation**: short-range cost-based route planning compares a route around obstacles with a one-block jump or short drop, avoids obvious fluids/hazards and refuses large drops.
- **Optional block breaking**: compares estimated mining time with other routes and can break up to two ordinary non-container blocks on a chosen step, using vanilla mining packets. **Off by default.** Does not bypass protected server regions, tool speed, or mining permissions.
- Limited to a 12-block local navigation radius and 17-block target tracking range. It is not full-world pathfinding, can't guarantee an optimal route, and may still get stuck. Use in a flat disposable test arena first.

## Keybindings
- **R**: toggle LockOn.
- **G**: select/clear a visible player near crosshair.
- **H**: toggle smart chase and strafe (on by default).
- **J**: toggle timed critical-hit jumps (on by default).
- **B**: toggle obstacle block breaking (**off by default, destructive**).

## Install
Remove ALL previous LockOn JARs from the Modrinth profile's `mods/` folder. Install only the versioned **`LockOn-26.3-v1.4.0.jar`** after a successful GitHub Actions build. Turn off LockOn with R if you need manual control. Mining is your responsibility; only enable on worlds where you are authorized to break blocks.

## Build
[GitHub Actions](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) compiles with real Minecraft/Fabric APIs, publishes a JAR to this branch after a successful build, and provides it as a build artifact.
