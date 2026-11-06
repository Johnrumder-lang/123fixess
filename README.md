# LockOn v1.3.0 — Minecraft 26.3 / Fabric

Client-only lock-on mod for **consensual testing on a private PvP server**. Requires Minecraft 26.3, Java 25, Fabric Loader and matching Fabric API.

## Download
**Install `LockOn-26.3-v1.3.0.jar` only after [the GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds.** If the file doesn't exist in this branch yet, it is not ready. Do not use the old untested 1.2.0 JAR.

## Changes in v1.3.0
- Predictive, faster tracking that follows moving players.
- Sword attacks target the actual entity hitbox rather than depending on a stale raycast; they require line of sight, **normal 3-block sword reach** and the **full attack cooldown**.
- New **optional movement assist** chases, strafes and briefly releases sprint between attacks to try to keep combos. This is not pathfinding and may walk into walls, hazards or off ledges. Test somewhere safe.
- The goal is reliable practice, not guaranteed wins. Server lag, knockback, ping and defense still matter.

## Controls
- **R** = toggle lock-on.
- **G** = select the visible player near the crosshair; G again clears them.
- **H** = toggle automatic chase + strafe (on by default, only acts while R is enabled and a target is selected).
- **Turn R off before manual movement**. Automatic movement temporarily controls W/A/S/D and sprint. If manual keys feel stuck, release and press them again.

## Install
1. Open Modrinth App → your Minecraft 26.3 Fabric instance → Open folder.
2. Remove older LockOn JARs from `mods/`, particularly `LockOn-26.3-v1.2.1.jar`.
3. Put `LockOn-26.3-v1.3.0.jar` into `mods/` and launch.
4. Press G facing a friend, then R to activate. Toggle H to use or disable combo movement.

## Build
With JDK 25 + Gradle 9.6.0, run `gradle build`; the normal compiled output is `build/libs/lockon-1.3.0.jar`. GitHub Actions publishes a version-named copy if compilation succeeds.
