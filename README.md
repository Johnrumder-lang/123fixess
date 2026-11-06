# LockOn v1.9.0 — Minecraft Java 26.3 / Fabric

A client-side assisted PvP practice mod for private servers where the server owner and other players consent. Requires Minecraft 26.3, Java 25, Fabric Loader, Fabric API 0.165.2+26.3 and Cloth Config API 26.3.159.

## New in v1.9.0

### Reach — maximum default vanilla reach
Press **O** to open the settings. The default attempted melee reach is now **5.0 blocks** (the longest *default* vanilla player melee reach, normally in Creative). The editable range remains 3.0–5.0 blocks and is saved in `config/lockon.properties`. If your older settings stored the original 3.1-block default, it will automatically migrate to 5.0 the first time this version loads.

**A client-side mod does not change the server's authoritative hit reach.** Vanilla Survival typically allows 3-block melee reach; Creative normally allows 5. In Minecraft 26.3, a weapon's `minecraft:attack_range` data component can override the player's `entity_interaction_range` attribute, so server operators should check both when setting up a custom-range practice match. The underlying attribute/data-component values can support ranges beyond 5 with deliberate server changes; **5 blocks is the largest default vanilla player reach, not a technical attribute cap**. This mod does not bypass server checks.

### Combat tactics and practice diagnostics

- New **deterministic tactics planner** scores spacing based on selected reach, whether a combo is active, opponent airtime and radial movement (moving away vs. approaching). Sprint and circling are separate decisions to avoid breaking knockback follow-ups.
- **Smoothed target-velocity tracking** reduces abrupt aim changes caused by packet updates. It avoids predicting the opponent outside the hitbox when already in melee range.
- The existing 20–50ms randomized timer and 86%-charge combo follow-ups remain, with full-charge attacks in Precision mode. Critical hits and sprint knockback remain mutually exclusive on a given attack.
- Press **U** to see session **attack attempts**, **observed hurt animations** after attacks, and an approximate streak. Hurt animations are **not confirmed server hit statistics**: other sources of damage can trigger them. These are only practice signals, not a machine-learning model.
- Short-range A* navigation, one-block obstacle jumps, stuck recovery, manual bow shooting with lead, and optional mining remain.
- The GitHub build now runs additional **pure-Java tactical tests**, covering attack spacing, momentum/sprint reset, unsafe movement avoidance and reach limits.

## Controls
| Key | Function |
|---|---|
| **R** | Enable/disable LockOn |
| **G** | Select/clear target |
| **H** | Chase/strafe |
| **J** | Critical-hit jumps |
| **K** | Combo/Precision attack mode |
| **B** | Optional block breaking (OFF by default; destructive) |
| **O** | Reach settings (3.0–5.0; default 5.0) |
| **U** | Practice diagnostics |

## Installation
1. Use a Minecraft 26.3 Fabric instance in Modrinth. Install Fabric API and Cloth Config API 26.3.159.
2. Remove all previous LockOn JARs from your `mods/` folder.
3. Only after the [Java 25 GitHub Actions build and tactics tests](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) both pass, download `LockOn-26.3-v1.9.0.jar` from `lockon-fixed` and install it.
4. Practice on an enclosed arena. Start with **O** to check reach, **G** to select an opponent, **R** to start, and **U** to review diagnostics.

## About “training the bot”
This release is an engineered, testable tactics upgrade, **not** a trained neural network or reinforcement-learning system. Genuine ML training to top-level Minecraft PvP would require a reproducible match simulator or instrumented practice server, replay data, reward functions and extensive evaluation. Compilation and offline tactics tests cannot prove win rate or in-game reliability. Server ping, TPS, hit validation, weapon components and player movement still determine what actually works.

Use only with everyone's permission in private practice matches.
