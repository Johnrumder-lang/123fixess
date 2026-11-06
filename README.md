# LockOn (Minecraft 26.3 / Fabric)

**Experimental build – not verified in Minecraft.**

This client-side Fabric mod was prepared for private, consensual PvP testing. It selects a player target, steers the crosshair toward them, and attacks with a sword **only when the normal weapon attack cooldown is fully charged**. It does not spam at 20 CPS.

## Controls
- **R** – enable/disable LockOn.
- **G** – select the player nearest your crosshair; press again to clear the target.

## Download and install
1. Download **LockOn-26.3.jar** from the repository (open the file and click **Download raw file**).
2. In Modrinth App, open a **Minecraft 26.3 / Fabric** instance.
3. Install **Fabric API** matching that version.
4. Select **Open folder**, move the downloaded JAR into `mods/`, and launch.
5. Use it only on servers where the server owner and players agree.

## Important caveat
The included JAR was compiled against API *stubs* because Java 25 and Minecraft/Fabric dependencies were not available in the generation environment. Its archive structure was inspected, but **it has not been verified to load or work with real Minecraft 26.3**. It may fail to load. The `source/` folder contains the Java code and Gradle configuration for a proper build with **Java JDK 25** and network access.

## Source build
From the `source/` folder, use a matching Gradle installation or a verified Gradle wrapper, then run `gradle build` using JDK 25. Review the source and ensure the dependencies and mappings are correct first. The output of a real successful Fabric Loom build, not the experimental JAR above, is what should normally be installed.

Avoid building inside WhatsApp's protected `AppData\\Local\\Packages` transfer directory: Windows access may be denied there.
