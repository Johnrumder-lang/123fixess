# LockOn 1.5.1 — Minecraft 26.3 / Fabric

Client-side LockOn for consenting tests on a private PvP server. Needs Java 25, Fabric Loader and compatible Fabric API.

## Fixes
- **Melee no longer freezes while in range:** v1.5.0 restarted the random timer whenever the target briefly missed its view ray, so the charged attack could be postponed indefinitely. In v1.5.1 the 2–3 tick delay is scheduled once when the weapon is ready, and stays ready while the opponent moves briefly out of view/range. It attacks at the next eligible moment within the server's reach.
- **Priority for combos:** The bot does not hold back ordinary full-cooldown attacks just to attempt a critical jump while the opponent is being knocked airborne. Critical jumps now require stable close distance and yield to follow-up hits.
- **One-block obstacle climbing:** Directional obstruction probes detect one-block walls even if your eyes still have line of sight to an opponent above them. The bot issues an actual jump, holds jump input briefly and continues moving toward the obstacle; pathfinding also keeps searching around close obstacles instead of prematurely concluding no route.
- **Less aim drift:** Inside sword range, aim goes to the player's actual center instead of leading movement outside the hitbox.
- Sprint pursuit and strafing avoid needlessly moving sideways while the target is nearly out of reach.

## Controls
R = toggle LockOn; G = select/clear a player; H = smart chase; J = critical jumps; B = optional block breaking (OFF by default). Bow lock-on with projectile lead is retained; draw and fire normally.

## Safety and limitations
The client attack ray is 3.1 blocks, **not** an extension of the Paper server's legitimate reach. Weapon cooldown is respected. The random attack delay is 100–150 ms **in addition** to weapon recharge at 20 TPS. Pathfinding is bounded, not complete; auto-chase can still fall in dangerous terrain. Test on a flat arena with a one-block step first. Block breaking modifies the world if enabled.

## Install
After [the real Java 25 / Fabric CI build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds, install **`LockOn-26.3-v1.5.1.jar`** from the `lockon-fixed` branch. Remove *all* older LockOn JARs from your Modrinth instance's `mods/` folder. Source-only commits are not a verified download.
