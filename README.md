# LockOn 1.5.0 — Minecraft 26.3 / Fabric

Private, consenting PvP testing server only. Client-side Fabric mod with Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3.

## New in v1.5.0
- **Melee ray range: 3.1 blocks** (was 3.0). This is only a client aim/attack test; a normal vanilla or Paper server may reject hits that exceed its actual reach limit.
- **Random attack timing:** once the sword's **normal full cooldown** completes and a target is in reach, schedule the hit **2 or 3 game ticks later** (approximately 100–150 ms at 20 TPS). This is an extra delay, **not** 7–10 fully powered sword attacks per second.
- **Improved air-combo pursuit:** follows opponents briefly airborne from knockback; keeps sprint active between ordinary chase hits, but stops sprint for falling critical hits. A true sprint hit and falling crit cannot occur simultaneously.
- **Bow lock-on with moving-target lead and gravity correction**, up to 54 blocks. Equip a **bow**, look toward a player and press **G**. Draw and release normally with right click; aiming is assisted while locked. Calibration assumes a fully charged ordinary bow and clear sight; distant shots, obstacles and unusual bow properties can miss.
- Preserves v1.4 short-range A* paths, 1-block jumps, combo strafing, and optional obstacle mining.

## Controls
- **R** toggle LockOn.
- **G** select/clear target near crosshair.
- **H** chase/strafe mode (on by default, inactive with bow).
- **J** timed jump crits on/off (on by default; applies to sword).
- **B** auto-break obstacles on/off (**off by default**, destructive).
- **Bow shooting:** hold/release right-click manually; LockOn aims automatically.

## Install
Remove previous LockOn jars first. Download the newly built **`LockOn-26.3-v1.5.0.jar`** from this branch only after the [GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds. Put it into your Modrinth Fabric 26.3 instance's `mods/` folder.

## Limits
The JAR must pass the Java 25/Fabric build. In-game behavior, real server reach, ping, arrow prediction, and pathfinding are not guaranteed. Test in an enclosed practice arena first.
