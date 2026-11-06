# LockOn for Minecraft 26.3 (Fabric)

**Experimental JAR — not verified to load in Minecraft.**

[Download LockOn-26.3.jar](https://github.com/Johnrumder-lang/123fixess/raw/refs/heads/main/LockOn-26.3.jar)

This client-side test mod is intended for consensual private-server use. **R** toggles LockOn and **G** selects or clears a player near the crosshair. It attempts sword attacks only at full weapon cooldown, rather than fixed 20 CPS.

### Installation
1. Download `LockOn-26.3.jar` from the link above.
2. Open your Minecraft 26.3 Fabric instance in the Modrinth App.
3. Ensure Fabric API is installed for your version.
4. Open the instance's `mods` folder, place the JAR there and launch.

### Compatibility warning
The uploaded JAR is the **unverified earlier test build** compiled using API placeholders (not the official Fabric/Minecraft toolchain). Its ZIP structure is valid, but it **may not load or work in the game**. This is *not* a verified release. For a proper Fabric Loom build, see the `source/` directory, use Java JDK 25 and resolve any API incompatibilities before running `gradle build`.

Avoid working inside the protected WhatsApp Desktop transfer directory on Windows; copy files to a normal folder first.
