# LockOn for Minecraft 26.3 (Fabric)

## LockOn-26.3-v1.2.1.jar — not verified yet

The original experimental version 1.2.0 crashed because `InputConstants.Type.KEYSYM` was removed in Minecraft 26.3. This branch updates keyboard mapping and screen access for 26.3, but the code must still pass a real Fabric Loom build.

**Do not use the old `LockOn-26.3.jar`.** Remove it from your Modrinth profile.

[Build using GitHub Actions](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml) — select the `lockon-fixed` branch in **Run workflow**. A successful run produces `LockOn-26.3-v1.2.1.jar` in the branch and as a workflow artifact. If this file is absent, no verified build has been produced.

### Controls
- **R**: Toggle on/off
- **G**: Choose/clear target near crosshair
- **Sword**: Only attacks after the vanilla weapon cooldown reaches 100%; no 20 CPS spam

### Installation
Use a Minecraft 26.3 / Fabric instance with matching Fabric API. Place the successfully compiled `LockOn-26.3-v1.2.1.jar` in the profile's `mods` folder; remove the old JAR first. Use only on private servers whose owners and players agree to it.

### Manual source build
Requires JDK 25 and Gradle 9.6.0:
```sh
gradle build
```
The normal output is `build/libs/lockon-1.2.1.jar`. The workflow renames it to `LockOn-26.3-v1.2.1.jar`.
