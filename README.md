# LockOn 1.5.2 — Minecraft 26.3 / Fabric

Client-side LockOn for consensual practice on a private server. Requires Minecraft 26.3, Java 25, Fabric Loader and compatible Fabric API.

## Changed in v1.5.2
- The **additional randomized sword-attack delay is now 20–50 milliseconds**, reduced from 100–150 ms.
- The 20–50 ms timer begins **after the vanilla weapon cooldown is fully charged**; the cooldown is never bypassed.
- Timing uses a monotonic nanosecond clock and a Fabric world-render callback, allowing deadline checks between Minecraft's 50 ms game ticks. The delay chosen is 20–50 ms, but actual execution can be later due to frame rate, client lag, network latency or server ticks. It is not a guaranteed 20–50 ms hit.
- It remains ready after the delay if the target briefly moves out of the sword ray, so attacks aren't postponed by repeatedly restarting the timer.
- Preserves v1.5.1 combo, jumping, A* navigation, optional block breaking, 3.1-block **client-side ray check** (not increased server reach), and predictive bow targeting.

## Controls
R toggle; G select/clear target; H chase/strafe; J critical-hit jumps; B optional breaking (OFF by default). Bow is manually drawn and fired while target aim is assisted.

## Install
Download **`LockOn-26.3-v1.5.2.jar`** from the `lockon-fixed` branch only after the [GitHub Actions build](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) succeeds. Remove previous LockOn JARs first. Test in a safe arena; compilation doesn't prove in-game behavior.

## Notes
A slower FPS can make the effective delay higher than 50 ms. Hits remain subject to vanilla or Paper server reach, protection and cooldown rules. Only use with other players' permission.
