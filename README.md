# LockOn v2.1.0 — Minecraft 26.3 / Fabric

A client-only assisted combat practice mod for a private server where the owner and other players consent. Requires Java 25, Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3 and Cloth Config API 26.3.159.

## New: automatic fist / empty-hand combat

**LockOn now attacks with fists when your main hand is completely empty**, using the same locked target, aim assist, chase/strafe, sprint-knockback resets, combo mode and timed critical-hit attempts as a sword.

- Empty-hand combat automatically activates when you switch from your sword to an **empty hotbar slot**. No new key is required.
- Sword timing is not forced onto fists: they have a much faster vanilla cooldown. The combo minimum is **3 ticks for bare hands** rather than 8 ticks for swords, while the real attack-strength cooldown check and randomized 20–50 ms additional delay still apply. The bot does not force damage faster than the game permits.
- Switching from a sword to fists (or back) clears the previous weapon's queued attack and combo timing.
- The mod **does not automatically attack while holding another item**, including bows, pickaxes, food or tools. Bow targeting remains manually fired as before.
- Unarmed attacks naturally do far less damage than a sword. Sprint knockback and crit conditions still depend on normal Minecraft mechanics.
- This feature is covered by new deterministic test cases in GitHub Actions.

## Existing v2.0.0 features retained

- **3.0 blocks default attempted melee reach**, matching unmodified vanilla Survival swords. Press **O → Combat** to select 2.5–3.0. The server still decides valid hit reach.
- **Tracers:** press **O → Tracers** to independently toggle lines toward loaded players, chests, beds, custom blocks and custom entity IDs. Radius 8–32 blocks, all tracer features off by default.
- **Combos:** 86% charged follow-up attacks when comboing, full-charge preference otherwise, 20–50 ms randomized additional delay, tactical target tracking and chase.
- **Pathfinding:** jumps over one-block obstacles, path replanning, optional destructive block breaking OFF by default.
- **Bow:** target prediction and drop compensation; you draw and release manually.

## Controls

| Key | Action |
|---|---|
| R | Toggle LockOn |
| G | Select or clear player target |
| H | Smart chasing |
| J | Critical-hit jump attempts |
| K | Combo / Precision mode |
| B | Optional obstacle breaking (OFF by default) |
| O | Reach and tracer settings |
| U | Practice diagnostics |

## Installation

Delete all previous LockOn JARs from your Modrinth instance's `mods/` folder. Install `LockOn-26.3-v2.1.0.jar` from the `lockon-fixed` branch **only after the [GitHub Actions build and tactics tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeed**. Keep Fabric API and Cloth Config installed. Then select an opponent with **G**, enable with **R**, and switch to an empty hotbar slot to use fists.

**Limitations:** Code compilation and unit tests do not verify in-game behavior. Test in an enclosed private practice arena; server reach limits, invulnerability frames, latency and weapon cooldown still apply.
