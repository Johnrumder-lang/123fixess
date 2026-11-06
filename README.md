# LockOn v2.6.0 — Minecraft 26.3 Fabric client + optional Paper server plugin

Client combat practice and player/block tracers, designed for consent-based private servers. Minecraft 26.3, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Cloth Config API 26.3.159.

## Removed: Teleportation
The **V** teleport key, `/tp` command dispatch, and the "Teleport username" setting have been completely removed from the client. Switching from v2.5.0 removes the old field from the config on save. V is unassigned.

## Fix: show consented players across the whole server world (live)

**The vanilla Minecraft server only streams a limited number of player entities to a client. No client-side `inf` flag can discover players the server has never sent.** The new **LockOnPlayerFeed Paper server companion** is an explicit, administrator-installed data source for real-time player locations outside the normal tracking distance.

The companion sends positions approximately **twice per second** over the Minecraft `lockon:players` plugin-message channel. No website, Internet account, external service, or insecure HTTP endpoint is required.

### Install (server owner required)

**On the Paper Minecraft 26.3 server:**

1. Download **`LockOn-Server-Paper-26.3-v2.6.0.jar`** from the `lockon-fixed` GitHub branch after the CI build succeeds.
2. Place this plugin JAR in the server's `plugins/` folder and **restart the server**. This file is NOT a Fabric client mod; do not put it in your own `mods/` folder.
3. The player whose location may be shared enters **`/lockonshare on`**. Sharing defaults to **OFF** and can be revoked anytime with **`/lockonshare off`**. `/lockonshare status` checks the setting. The server does not broadcast non-consenting players.
4. Only server viewers with **`lockon.view`** permission receive the feed (**OPs by default**). The Paper plugin also checks Bukkit's `viewer.canSee(target)` so invisible/hidden players are not exposed. Permission administration is controlled by the server owner.

**On the Fabric 26.3 client:**

1. Replace all earlier LockOn JARs with **`LockOn-26.3-v2.6.0.jar`**. Leave Cloth Config and Fabric API installed.
2. Press **O → Tracers**, turn on **Server-wide player feed (needs Paper plugin + permission)** and **Unlimited tracer distance (LOADED chunks/entities only)**, then save. The unlimited switch removes the client's *display* range check for both loaded and server-fed players.
3. In the same world/dimension, red tracer directions now show players who opted in even when their entities are not loaded by your client. Press **U** to check **Server player feed LIVE (N players sharing)**. If the HUD says **NO server feed**, check that the Paper plugin is running, the viewing account has permission, and the network connection is not blocking plugin messages.

**Important boundaries:** This is a live feed only for authorized and opted-in players, NOT an automatic way to reveal everyone on arbitrary servers. No markers can appear without the server companion, server permission, and player opt-in. Screen-space player tracer directions only make sense within the **same dimension**; a player in the Nether cannot be plotted as a direction in the Overworld. Paper servers behind proxies may need forwarding of custom plugin-message channels. The feed is limited to 120 consenting targets per snapshot and cleared if it stops arriving.

### Other tracer modes remain
- Local client-loaded players, chests, beds, custom blocks, and custom entities.
- **`inf`** still means *no extra distance filter* on the data your client receives.
- Optional administrator-exported **`lockon-server-index.tsv`** for static blocks in saved world regions (not live player locations).
- Optional incremental block scan and bounded rendering for performance.

## Combat practice
Sword and fist auto-hit at server-valid reach (default 3.0 blocks), with cooldown-aware repeated attacks and sprint-knockback chase. Critical-hit jumps remain off by default and can be enabled with J. Test with consenting players; server rules and damage immunity remain authoritative.

## Keys

| Key | Purpose |
|---|---|
| R | Toggle combat lock |
| G | Select/clear player |
| H | Chase |
| J | Toggle critical jumps |
| K | Combo / Precision |
| B | Optional obstacle mining (OFF by default) |
| O | Reach, player-feed, and tracer settings |
| U | Attack attempts + live player-feed diagnostic |
| V | **Unassigned (teleport removed)** |

## Build and limitations

The CI workflow compiles and tests the Fabric client, checks the offline world index scanner, and builds a distinct **Paper 26.3 plugin JAR** with Java 25. Passing the build does NOT verify server/client interoperability in a live multiplayer session. Verify with a private test server and two consenting players.

Download from the [project's versioned build artifacts](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml). Use only with administrator approval and participants' consent.
