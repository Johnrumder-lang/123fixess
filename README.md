# LockOn for Minecraft 26.3 (Fabric)

**EXPERIMENTAL SOURCE — no verified Minecraft-ready JAR is uploaded.**

This project provides client-side target locking for consensual private-server testing. **R** toggles LockOn, **G** selects or clears the player nearest the crosshair. Sword attacks are attempted only when vanilla's weapon cooldown is fully charged (no fixed 20 CPS mode).

### Build
1. Install **JDK 25** and Gradle appropriate for Fabric Loom.
2. Clone this repository and enter the `source` folder.
3. Run `gradle build` with network access. The result should be under `source/build/libs` if it compiles.
4. Copy the resulting remapped Fabric mod JAR to your Modrinth **Minecraft 26.3/Fabric** instance's `mods` folder; install a matching Fabric API.

### Status
The source was copied from a prior prototype and is **not verified** against official Minecraft 26.3/Fabric APIs. Build or runtime failures may require source fixes. A prior experimental JAR was compiled against API placeholders, so it was **not** published as a reliable download. The earlier README incorrectly implied the JAR was present.

Avoid building inside Windows' WhatsApp Desktop transfer directories: these may deny filesystem access.
