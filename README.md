# LockOn v2.5.0 — Minecraft Java 26.3 / Fabric

Client-side PvP training assistant with optional tracers. Requires Minecraft 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Cloth Config 26.3.159. Use only with the server owner's and other players' agreement.

## Fixed: first sword/fist hit works, follow-ups do not

Previously, the mod allowed **8-tick sword follow-ups** (and **3-tick fist follow-ups**) even though vanilla damage immunity typically lasts **10 game ticks** after a successful hit. Those early follow-up attacks could show swings or critical-hit particles without new damage or knockback.

v2.5.0 changes:

- **At least 11 ticks between attacks on the same target**, with both fists and swords. The selected weapon must also meet its actual attack cooldown threshold. The randomized **20–50 ms additional delay** remains. This favors damaging follow-ups over ineffective early attacks.
- **Combo mode targets 95% attack cooldown** rather than the earlier 86%, leaving a margin above the Minecraft 26.3 sprint-knockback threshold of 90%. Openers still prefer full charge.
- **No animation-based permanent lockout:** removed the strict `target.hurtTime > 0` condition that could postpone subsequent hits when remote animation state lagged. The 11-tick minimum replaces it.
- **No required hurt-animation signal to continue the combo:** the client may miss these signals; they are diagnostics, not a prerequisite.
- **Better pursuit:** across safe, visible flat ground, chase now closes gaps up to 8 blocks without pausing to wait for A* pathfinding after knockback.
- **Crit jumping is OFF by default** to make ordinary hits easier to verify. Press J to opt into timed critical-hit attempts after regular hits work.

Server PvP permissions, claim/region protection, anti-cheat checks, per-target immunity, knockback resistance and ping still determine whether hits succeed. A critical-hit effect is not proof of server damage.

## NEW: Teleport to a player (V)

- **Press G** while looking toward a nearby player to select them; then press **V** to request `/tp <selected-player>`.
- Alternatively, open **O → Combat**, enter a **Teleport username** (for example `Steve`), save, and press **V**. The entered name takes priority. Clear the field to return to the G-selected target.
- LockOn does not need to be enabled to request teleportation. The key stops automatic movement and sends the normal Minecraft `/tp` command; it does not alter your local position.
- **Requires Java Edition operator permission level 2 or the server's explicit command permission.** A normal player on an unmodified multiplayer server cannot teleport to another player by installing a client-only mod. If the command is denied, Minecraft reports that in chat.
- Only actual Minecraft player usernames are accepted (letters, numbers, underscore; maximum 16 characters). Repeated key presses have a short cooldown.

## Features retained

- **O → Tracers:** separate players/chests/beds/custom block/custom entity toggles, loaded-chunk `inf` mode, optional admin-exported saved-world block index for a server you administer.
- **Sword + empty-hand combat**, bow aim assist (bow manually fired), smart chase and 1-block obstacle climbing.
- **O → Combat:** vanilla Survival melee attempted reach 2.5–3.0 blocks (default 3.0).
- **U:** attack attempts, observed hurt animations and conservative out-of-reach checks.
- Optional obstacle mining remains OFF by default.

## Keys

| Key | Action |
|---|---|
| R | Toggle combat lock-on |
| G | Select/clear target |
| H | Smart chase |
| J | Toggle critical jumps (OFF by default) |
| K | Combo / Precision |
| B | Opt into destructive obstacle breaking (OFF by default) |
| O | Settings (teleport username, reach, tracers) |
| U | Combat diagnostics |
| **V** | **Request server-authorized /tp to chosen player** |

## Installation

After the [GitHub Actions build and tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) pass, remove any earlier LockOn JARs from `mods/` and install `LockOn-26.3-v2.5.0.jar` in your Fabric 26.3 Modrinth instance. Keep compatible Fabric API and Cloth Config installed.

### Test reliable combat first

With a consenting opponent, test normal manual melee first. Then use LockOn with **J OFF**, stand within 2.5 blocks, select the target using G, and enable using R. Inspect the **U** diagnostics after several hits. If manual melee hits don't work either, check the server's PvP setting and claim/arena protections. Compiling and running deterministic tests alone cannot prove server-side damage and knockback.
