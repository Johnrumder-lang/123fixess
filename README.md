# LockOn Fabric 26.3

## Important: original JAR causes a Minecraft crash

The original experimental `LockOn-26.3.jar` from the `lockon-download` branch was compiled against placeholder APIs and **does not work** on Minecraft 26.3. It fails with `NoSuchFieldError: InputConstants.Type.KEYSYM`.

I prepared corrected 1.2.1 source on the [lockon-fixed branch](https://github.com/Johnrumder-lang/123fixess/tree/lockon-fixed). Changes include SDL input types and keycodes and Minecraft 26.3's new `mc.gui.screen()` access.

For a **properly compiled** JAR, open [GitHub Actions](https://github.com/Johnrumder-lang/123fixess/actions/workflows/build.yml), select **Build LockOn Fabric 26.3**, click **Run workflow**, choose **lockon-fixed**, and run it. The workflow uses Java 25, Gradle 9.6, Minecraft 26.3, and Fabric API 0.165.2+26.3. If successful it adds `LockOn-26.3-fixed.jar` to that branch and also uploads the JAR as a workflow artifact. Only install it after the build succeeds. If the job fails, inspect the compilation errors instead.

In Modrinth, remove the old JAR before adding the new one. R toggles; G selects/clears target. Attacks occur only at the fully charged sword cooldown. Use only on consenting private PvP testing servers.
