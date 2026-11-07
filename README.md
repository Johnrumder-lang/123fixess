# LockOn v3.6.0 — survival recovery and combat

Minecraft Java 26.3, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Cloth Config 26.3.159, Java 25.

Replace older LockOn JARs with `LockOn-26.3-v3.6.0.jar`. F7 starts/stops survival, F6 opens settings, and U prints diagnostics. Enable automatic repeat for successive mining trips. For PvP, G selects a player, R toggles combat, H toggles chase, B allows obstacle mining, and J enables optional jump criticals. Physical movement immediately gives control back to you and stops automation.

## Changes in this release

- Combat and survival share input sampling, including forward movement, strafing, jumping and sprint requests. Normal hunger, item-use and collision restrictions still apply to sprinting. Both modes can swim and surface for air.
- Mob defense keeps its target and equips a sword before checking attack readiness. It follows knockback with forward pressure, waits for weapon charge, and sends the normal attack, swing and punch sequence. Creepers, low health and three nearby visible monsters trigger escape.
- PvP equips an available sword, switches to an axe against a blocking opponent, and returns to the sword when the shield drops. Available shields move into an empty offhand and block briefly between attacks, releasing before the next hit. The bot moves toward opponents using food and retreats while eating for its own recovery. Food and shield use are explicitly released when interrupted or disabled.
- Ordinary survival deaths trigger respawn requests, return to the death location, and pickup attempts before returning home. Recovery works within the same dimension and times out after five minutes. Hardcore/spectator states and server respawn restrictions still apply.
- Tools are used through their remaining durability. Mining checks the game's actual harvest requirements, gathers iron for an iron pickaxe, and skips ores that available tools cannot harvest. Copper pickaxes and other copper tools can be crafted when ingredients are available.
- Travel keeps a waypoint instead of replanning every tick. It can clear real head/foot obstructions, open gates, swim through water, and take supported drops up to six blocks when estimated remaining health is at least six. Existing stair clearance and dirt/cobblestone towering handle tunnel exits. Lava remains hazardous.
- Mined-item collection targets a supported standing position in the mined block's column. It waits for pickup, queues drops from clearing obstructions, and abandons an unreachable pickup after a bounded recovery period rather than spinning indefinitely.
- Food gathering harvests mature crops/berries and replants available seeds. When food is needed, it also hunts nearby unnamed adult cows, pigs, sheep, chickens and rabbits. Meat can be cooked in the base furnace using planks as fuel; raw meat is available for emergency hunger. The bot replenishes wood/sticks, builds workstations, crafts iron armor/tools, smelts and stores resources between trips.

Start on supported terrain with access to trees and food. Loaded terrain, available resources, storage capacity and server protections can limit progress. This version does not promise indefinite unattended survival or build/breed a renewable animal farm. Fall recovery uses checked landings and terrain clearing; bucket-clutch automation is not included.

## Verification

The GitHub workflow compiles the Fabric mod against Minecraft 26.3 and builds the optional Paper companion. It runs production movement and combat inventory/use code against small client fixtures, production A* routes on block-grid fixtures, deterministic combat/survival/mining/crafting policy tests, attack packet-order contracts, autonomous-flow source checks, and world-export tests. The fixtures test control ownership, sustained sprint input, weapon switching, shield release and eating completion; they do not simulate server damage, collision physics or latency. Live gameplay remains unverified.
