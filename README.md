# LockOn v1.7.0 — Minecraft 26.3 / Fabric

A client-side assisted combat practice mod for a **private, consensual PvP server**. Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Java 25.

## Quality and gameplay upgrades
- **Accurate per-target combos:** Switching player targets no longer carries over the previous target's combo/cooldown state.
- **Invulnerability-aware attacks:** A combo follow-up that is otherwise ready waits for the current opponent's visible hit animation to finish, reducing attacks wasted on damage-immunity frames. Network delays and server plugins can still differ.
- **Sprint-reset without stopping forward movement:** Sprint is released on the next movement update instead of immediately after submitting an attack, allowing momentum to continue through follow-up hits and avoiding canceling sprint too early.
- **Stuck recovery:** After 12 ticks without meaningful movement while still outside attack range, the chase replans and tries a short safe sidestep instead of continually pushing the same wall.
- **Safer and smoother chasing:** Checks for a solid floor before walking straight toward the opponent. When bounded A* cannot find a route but a direct approach is safe, it continues advancing instead of freezing.
- **Earlier obstacle detection:** Checks 0.42/0.66/0.92 blocks ahead so one-block jumps trigger nearer to the wall.
- **Combat mode toggle (K):** Combo mode uses 86%-charged sword follow-ups within 22 ticks of attacking the *same target*, while normal opening hits prefer full charge. Precision mode waits for 100%-charged sword hits every time. Both retain the random **20–50ms** delay after the eligible threshold.
- The 3.1-block client-side ray check, timed jump crits (outside combos), 54-block predictive bow lock, and optional A* obstacle mining remain.

## Controls
| Key | Action |
|---|---|
| R | Toggle LockOn |
| G | Select/clear a visible player |
| H | Smart chase and strafe (ON by default) |
| J | Critical-hit jump attempts (ON by default) |
| K | Switch COMBO / PRECISION (COMBO by default) |
| B | Allow breaking route obstacles (OFF by default, destructive) |

Draw and fire the bow manually with right-click; aim assistance works while a bow is equipped.

## Installation
After the [GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds, download **`LockOn-26.3-v1.7.0.jar`** from the `lockon-fixed` branch and place it in your Modrinth Minecraft 26.3 Fabric instance's `mods/` directory. **Delete ALL old LockOn JARs** first.

## Important limitations
- The mod does **not** change server reach, knockback, PvP permission or hit cooldown validation. 3.1 blocks is a client aim ray only; servers may reject hits beyond their own limits.
- Stuck detection is heuristic. Bounded A* is not complete navigation. Never enable auto-chase around dangerous cliffs or hazards without supervision. Auto-mining modifies the world and is off by default.
- A successfully compiled mod still requires in-game testing. Hits, combo pressure and bow trajectory depend on ping, TPS, server plugins and opponent movement.
- Only use with the server owner's and other players' permission.
