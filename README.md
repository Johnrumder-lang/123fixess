# LockOn 1.6.0 — Minecraft 26.3 / Fabric

Client-side assisted lock-on for consenting PvP practice on a private server. Requires Minecraft 26.3, Java 25, Fabric Loader and matching Fabric API.

## Changed in v1.6.0
- **Combo-first attack timing:** Once a sword hit is sent, the next hit can occur when the sword has recovered **at least 86% attack strength** if the opponent remains close during a short combo window. It no longer requires 100% charge for every hit.
- **Full charge when not comboing:** Outside the 22-tick (~1.1 s) window after a sword attack, the mod prefers full-strength hits again.
- **Sprint-knockback resets:** The chase controller briefly releases sprint after each hit and attempts to re-enter sprint while moving forward for additional knockback. During a combo it also prioritizes forward momentum over circling the opponent.
- **Critical hits yield to combos:** It will not start a jump crit in the middle of a sprint-knockback combo; standing openings can still attempt critical hits when eligible.
- Preserves the **20–50 ms randomized delay after the applicable cooldown threshold** and client-side 3.1-block aiming ray. Minecraft/Paper server reach, actual cooldown and knockback rules still apply.
- Retains the earlier A* pathfinding, jumping over one-block obstacles, optional block breaking (default OFF), and manually fired bow lock-on.

### Why 86%?
Minecraft Java sprint knockback and critical hits require roughly 84.8% attack cooldown. A threshold of 86% gives a margin while responding sooner than 100%. Hits at 86% can do less damage than fully charged hits, and a successful combo depends on ping, timing, velocity and the server. Mod logic cannot force extra reach, guaranteed knockback or airtime.

## Keys
- **R** toggle LockOn
- **G** select/clear player
- **H** toggle chase and strafing (ON by default)
- **J** toggle jumping critical hits
- **B** optional block breaking (OFF by default, destructive)
- **Bow** aim assist active while bow is equipped, manually draw/release right-click

## Download and install
After the [GitHub Actions Java 25 / Fabric build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds, download `LockOn-26.3-v1.6.0.jar` from the `lockon-fixed` branch. Remove ALL older LockOn JARs from your Modrinth instance's `mods/` directory before installing. Test in a safe arena first; a successful build does not verify PvP behavior.

Use only on private practice servers where the owner and other players agree to assisted combat.
