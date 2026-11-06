# LockOn v1.8.0 — Minecraft 26.3 / Fabric

A client-only combat practice mod for private servers where everyone agrees to assisted PvP. Requires Java 25, Fabric API, Fabric Loader and **Cloth Config API 26.3.159**.

## New: in-game reach setting

**Press O** in-game to open **LockOn Settings**. Edit **Attempted attack reach** from **3.0 to 5.0 blocks** (default 3.1), then save. The value is remembered in `config/lockon.properties` inside the Modrinth instance. When reach is increased, chase spacing adapts too.

**Crucial: a client-side setting does NOT extend server reach.** The server independently checks how far you can hit. For example, normal Survival/Paper uses 3 blocks, so it can reject a 5-block attack request. To permit 5 blocks on a private server you administer, an operator can set your player's entity interaction range using:

```mcfunction
/attribute YourPlayerName minecraft:entity_interaction_range base set 5
```

Replace `YourPlayerName` with your Minecraft username (no angle brackets). To revert:

```mcfunction
/attribute YourPlayerName minecraft:entity_interaction_range base reset
```

The server administrator should decide what range is allowed, and the same rules can be offered to everyone in a practice match.

## Controls

- **R:** Toggle LockOn
- **G:** Select/clear target
- **H:** Chase/strafe
- **J:** Critical-hit jumps
- **K:** Combo / Precision mode
- **B:** Enable destructive obstacle breaking (OFF by default)
- **O:** Open settings and change attempted reach (3.0–5.0)

The bow aim assist, pathfinder, sprint-knockback combo mode, and randomized 20–50ms additional delay are preserved.

## Install

Use a Minecraft 26.3 Fabric Modrinth instance with Fabric API and **Cloth Config API 26.3.159**. Remove ALL previous LockOn JARs from `mods/`; after the [successful GitHub build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml), download **`LockOn-26.3-v1.8.0.jar`** from the `lockon-fixed` branch, copy to `mods/` and launch.

A successful Java compilation does not by itself verify the settings screen or server-side reach in gameplay. Test on a consenting private practice server.
