# LockOn for Minecraft 26.3 / Fabric

**Version 1.2.1 — source fixed for Minecraft 26.3, pending a real Gradle build.**
The original 1.2.0 experimental stub-based JAR crashes because `InputConstants.Type.KEYSYM` does not exist in Minecraft 26.3. The old code also used the removed `mc.screen` field and invalid old keyboard key codes.

This branch fixes those issues by using `InputConstants.Type.KEYBOARD`, real `InputConstants.KEY_R/KEY_G`, and `mc.gui.screen()`. The GitHub Actions build compiles against actual Minecraft 26.3 and Fabric 0.165.2+26.3. **Only install `LockOn-26.3-fixed.jar` if it appears here after a successful CI build.** Do not use `LockOn-26.3.jar` from the old `lockon-download` branch.

## Controls
- R: Enable/disable target lock.
- G: Lock on to the player near the crosshair, or clear selection.
- Sword auto-attacks only at full vanilla attack cooldown; it does not spam 20 CPS.
- A server does not need Fabric. Use only with permission on private PvP test servers.

## Installation
Open your Minecraft 26.3 Fabric instance in Modrinth, install Fabric API, then put the **compiled** `LockOn-26.3-fixed.jar` in the instance's mods folder. Remove the old JAR.

## Compile manually
Requires JDK 25 and Gradle 9.6.0:
```
gradle build
```
Look in `build/libs/lockon-1.2.1.jar`. Do not install source JARs.
