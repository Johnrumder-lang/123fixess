package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Durable, client-side movement intents for Survival mode. A tick-end
 * autonomous decision only affects the NEXT LocalPlayer input sample;
 * replaying it in START_CLIENT_TICK avoids losing it between events.
 * This uses ordinary vanilla key mappings; never moves the player via
 * position/teleport packets and never bypasses server validation.
 */
final class LockOnMovement {
    private static boolean owned;
    private static boolean forward;
    private static boolean sprint;
    private static boolean jump;
    private static long inputSamples;
    private static int movingTicks;
    private static int stagnantTicks;
    private static Vec3 lastPosition;
    private static double recentDelta;

    private LockOnMovement() {}

    static void forward(Minecraft mc, boolean wantsSprint) {
        owned=true;
        forward=true;
        sprint=wantsSprint;
        jump=false;
        apply(mc);
    }

    static void jump(Minecraft mc, boolean wantsJump) {
        owned=true;
        jump=wantsJump;
        apply(mc);
    }

    static void stop(Minecraft mc) {
        if (!owned) return;
        forward=false;
        sprint=false;
        jump=false;
        stagnantTicks=0;
        movingTicks=0;
        lastPosition=null;
        recentDelta=0.0;
        apply(mc);
        owned=false;
    }

    static void beforeClientTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null
            || !LockOnSurvival.enabled()
            || mc.gui.screen() != null) {
            // Do not synthesize movement in menus, other worlds, or when
            // gameplay automation is off. Release stale inputs.
            stop(mc);
            return;
        }
        inputSamples++;
        Vec3 now = mc.player.position();
        if (lastPosition != null && forward) {
            recentDelta=Math.hypot(now.x-lastPosition.x,now.z-lastPosition.z);
            movingTicks++;
            if(recentDelta<0.004)stagnantTicks++;
            else stagnantTicks=0;
        } else {
            stagnantTicks=0;
            movingTicks=0;
        }
        lastPosition=now;
        apply(mc);
    }

    static String diagnostic(Minecraft mc) {
        if (mc == null || mc.player == null) return "movement: no player";
        return "movement requested=" + forward
            + ", keyDown=" + mc.options.keyUp.isDown()
            + ", jump=" + jump
            + ", moved=" + String.format(java.util.Locale.ROOT,"%.3f",recentDelta)
            + ", stagnant=" + stagnantTicks + "t"
            + ", samples=" + inputSamples;
    }

    static boolean stuck() {
        return forward && stagnantTicks>70;
    }

    private static void apply(Minecraft mc) {
        if (mc == null || mc.options == null) return;
        mc.options.keyUp.setDown(forward);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(jump);
    }
}
