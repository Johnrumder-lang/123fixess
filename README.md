# LockOn v3.3.0 — critical autoplay activation fix (Minecraft Java 26.3)

Client-side Minecraft 26.3 / Fabric mod. Requires Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Cloth Config API 26.3.159.

## FIX: Pressing P did nothing

**Identified root cause:** LockOn v2.9–v3.2 used **P** to toggle autonomous Survival mode, but Minecraft Java 26.3 **already uses P for its Social Interactions screen**. The vanilla game can open that screen before LockOn's end-of-tick handler runs. LockOn then intentionally discards key presses while a normal screen is open, so the bot never starts. Similarly, Minecraft 26.3 uses **O** for its Friends screen, conflicting with LockOn settings.

**The defaults and existing saved mappings are both fixed:**
- **F7 = Start/stop autonomous Survival mode** (was P).
- **F6 = Open LockOn settings** (was O).
- Old F7/F6 key conflicts from other mods are possible: change these under **Options → Controls → Key Binds → LockOn**.
- The mod **automatically migrates persisted P/O mappings** from older LockOn installs to F7/F6 and saves them. Merely changing the default would not repair players' old `options.txt` mappings.
- When you press F7, the mod **writes a start/stop message to chat**, not just the easily-overwritten action bar. While active, the action bar shows an **`[LockOn F7 ACTIVE #ticks] PHASE: current task`** message every second; this proves the gameplay tick is actually running.
- While active press **U** to print a long diagnostic into chat that shows current phase, last reason, wood/pickaxe progression and home-base processing.

## Expected behavior after pressing F7

Start in Survival on flat ground with a natural tree within 13 blocks, a clear area for a crafting table, and enough health/hunger.

1. F7 should **immediately show in chat** that autonomous Survival mode started.
2. You should get the one-second heartbeat with **BOOTSTRAP**: looking for a log, walking toward one, punching a tree, picking up the drop, or crafting.
3. Wood is converted to planks as needed, then a crafting table, sticks and a wooden pickaxe are crafted using regular Survival inventory/container actions.
4. The bot attempts to mine, return to its simple base, store/smelt and repeat while safe. It won't receive server-only data or bypass protected chunks and claims.

**This is still experimental autonomous gameplay**, not a fully reliable self-playing agent. Its status is designed to show whether it is running and where it gets stuck. Compilation/tests do NOT prove the in-game crafting or movement works.

### If you still see no movement

- Confirm you have installed **only one LockOn client JAR** (v3.3.0).
- Use **F7, not P**. If F7 fails, inspect Minecraft **Options → Controls → Key Binds**, search for LockOn survival, and check for other mods also using F7.
- Press **U** after F7 and look for the `[LockOn] Survival: ...` line in chat. If there is no heartbeat or chat line, the keybind/instance is still wrong; if there is a heartbeat, that phase tells us why progression is stalled.
- If F6 fails to show settings, rebind that too. **P** now remains available for Minecraft's own Social Interactions screen and **O** for Friends.

Autoplay uses normal player actions without OP permission or server plugins. The optional Paper server plugin is for separately permissioned whole-server player tracers ONLY.

## Controls

| Key | Action |
|---|---|
| **F7** | Start/stop Survival Autoplay **(changed from P)** |
| **F6** | LockOn settings **(changed from O)** |
| U | Send detailed diagnostics to chat |
| R / G | Combat LockOn and player selection |
| H / J / K | Movement / critical jumps / combo mode |
| B / N | Manual permissioned block-breaking toggle / aimed block |
| P / O | Vanilla Minecraft social/friends screens — no longer used by LockOn |

## Installation

Download `LockOn-26.3-v3.3.0.jar` from the project's `lockon-fixed` branch after the [CI build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds. Delete every older LockOn JAR from your Fabric 26.3 `mods/` folder. Fabric API and Cloth Config remain required. The separate optional Paper companion is only needed for opt-in global player tracers.

This release includes a source-level regression test for the P/O conflicts and persisted-key migration in addition to the existing offline crafting and mining policy checks.
