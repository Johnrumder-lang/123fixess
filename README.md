# LockOn v3.2.0 — Minecraft Java 26.3 Fabric

This release specifically addresses the **pickaxe-crafting freeze** and adds demand-driven wood gathering. It is a client-side automation mod for normal, permitted Survival gameplay. Requires Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Cloth Config API 26.3.159.

## Fixes

1. **Gather wood whenever the next recipe is short:** The bot no longer hoards a fixed 20 planks just to start building. With no crafting table it makes one as soon as it has four planks. If it runs out of planks for sticks or a pickaxe later, it converts remaining logs; if it has no logs, it searches for nearby natural trees again.
2. **Open and place the workbench properly:** v3.1 sent workbench use/placement interactions without first synchronizing the player's view direction. The bot now turns toward the support or table, waits two gameplay ticks, performs an actual raycast to the exposed block face, then makes the normal Survival interaction. Missing/unreachable/protected stations produce a specific HUD reason.
3. **Actually finish a recipe:** It tracks each crafting-grid ingredient click and whether the player is carrying an ingredient. It now verifies that the **correct output** appears and that the crafted item reaches inventory before announcing success. Planks from non-oak logs are supported.
4. **Recover rather than freeze:** Failed recipes time out, and the bot attempts to move only its own ingredients from the grid/cursor back to inventory before recalculating what it needs. The bot also rechecks available wood/planks/sticks every second while waiting; an empty grid with missing materials returns it to wood gathering rather than a permanent "crafting pickaxe" state.
5. **Fix chopping wood:** As with stone mining, the bot now aims at the actual exposed log face for two ticks before initiating block breaking, and continues digging that face until the log breaks. It attempts to collect dropped logs from the ground before crafting.

**Important:** A GUI or server may still refuse a click; the bot doesn't bypass server protections, invent items, or claim a pickaxe until one is visible in inventory. The updated `U` diagnostics show the crafting stage and ingredient number/action, and provide a specific reason if the table or recipe is blocked.

## Expected wood → tools progression

Press **P** in Survival near a natural tree (within about 13 blocks). It tries to punch a log, pick up the dropped log, make planks, craft and place a workbench, make sticks, craft a wooden pickaxe, and only then begin mining. When tools break or material supplies run out, it returns to the wood/tools progression instead of proceeding without them.

**O → Survival Mining** still controls strip length, continuous cycles, mob defense, auto-eating, smelting, chest storage and material compaction. **U** shows its current phase and any crafting/movement problem. Press **P** again to stop.

Iron/coal targeting, a furnace using real cobblestone and fuel, stone-tool upgrade, optional smelting/iron-pickaxe upgrade and small chest base remain experimental. This v3.2 patch targets the *early crafting freeze* first; it does not establish a fully reliable end-to-end self-playing bot.

## Installation

After successful CI compilation, tests and publication, remove **all** earlier LockOn JARs from the Fabric 26.3 `mods/` directory and install `LockOn-26.3-v3.2.0.jar`. Keep compatible Fabric API and Cloth Config.

**You do not need server OP or the Paper plugin for survival automation**; all actions are ordinary Survival interactions and respect the server's protections and policies. The optional Paper plugin is only for the separately authorized player-position tracer.

### Testing

Start on flat terrain next to a tree with sufficient health/food. Try from an empty inventory, **P**, then repeatedly **U**. Watch for `Looking down to place crafting table`, `Opening crafting-table GUI`, `Crafting wooden_pickaxe: ingredient X/5`, and `Collecting crafted wooden_pickaxe`. If one of those doesn't progress, report the **exact U status** and what is physically on screen.

Build and deterministic source tests cannot simulate a live Minecraft server, so in-game crafting and navigation must still be verified.
