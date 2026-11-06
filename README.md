# LockOn — Minecraft 26.3 / Fabric

## Versioned JAR: `LockOn-26.3-v1.2.1.jar` (build pending)

The previous `LockOn-26.3.jar` (mod version 1.2.0) **crashes Minecraft 26.3** with `NoSuchFieldError: InputConstants.Type.KEYSYM`. Remove it from the Modrinth instance's `mods` directory. It was built against placeholder APIs.

[Corrected source (lockon-fixed)](https://github.com/Johnrumder-lang/123fixess/tree/lockon-fixed) includes updated keyboard API usage. This source still needs to compile successfully against the actual game APIs.

To build it on GitHub: [Actions → Build LockOn Fabric 26.3](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) → **Run workflow** → select **lockon-fixed** → **Run workflow**. If successful, the workflow creates the **versioned** `LockOn-26.3-v1.2.1.jar` artifact and pushes it to the `lockon-fixed` branch. Download the JAR only after a successful build, and keep the old 1.2.0 JAR removed.

Requires Minecraft 26.3, Fabric Loader, Fabric API and Java 25. R toggles targeting; G picks or clears a target; sword attacks wait for full vanilla cooldown. For consensual private-server PvP testing.
