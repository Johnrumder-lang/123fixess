package lockon;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Survival movement intents, consumed after vanilla samples physical keys. */
public final class LockOnMovement {
    private static boolean forward;
    private static boolean sprint;
    private static boolean jump;
    private static long inputSamples;
    private static int stagnantTicks;
    private static Vec3 lastPosition;
    private static double recentDelta;
    private static boolean applied;

    private LockOnMovement() {}

    static void forward(Minecraft mc, boolean wantsSprint) {
        forward=true;
        sprint=wantsSprint;
        jump=false;
    }

    static void jump(Minecraft mc, boolean wantsJump) {
        jump=wantsJump;
    }

    static void stop(Minecraft mc) {
        forward=false;
        sprint=false;
        jump=false;
        applied=false;
        stagnantTicks=0;
        lastPosition=null;
        recentDelta=0.0;
    }

    /** Called at KeyboardInput.tick RETURN, after vanilla has read the keyboard. */
    public static Input sample(Input manual) {
        Minecraft mc=Minecraft.getInstance();
        applied=false;
        if(mc.player==null || mc.level==null || !LockOnSurvival.enabled()
            || mc.gui.screen()!=null) {
            stop(mc);
            return manual;
        }
        inputSamples++;
        if(manual.forward() || manual.backward() || manual.left() || manual.right()
            || manual.jump() || manual.shift() || manual.sprint()) {
            // The original vanilla input is returned in this SAME player tick.
            // Never write KeyMapping states: they belong to physical keyboard events.
            LockOnSurvival.stop(mc,"Manual movement: autoplay stopped. F7 restarts it.");
            return manual;
        }
        applied=forward || jump || sprint;
        if(!applied) return manual;
        return new Input(forward,false,false,false,jump,false,sprint);
    }

    static void beforeClientTick(Minecraft mc) {
        if(mc==null || mc.player==null || mc.level==null || !LockOnSurvival.enabled()
            || mc.gui.screen()!=null) {
            stop(mc);
            return;
        }
        Vec3 now=mc.player.position();
        if(lastPosition!=null && forward && applied) {
            recentDelta=Math.hypot(now.x-lastPosition.x,now.z-lastPosition.z);
            if(recentDelta<0.004) stagnantTicks++;
            else stagnantTicks=0;
        } else stagnantTicks=0;
        lastPosition=now;
    }

    static String diagnostic(Minecraft mc) {
        return "movement requested="+forward+", inputApplied="+applied
            +", jump="+jump+", moved="
            +String.format(java.util.Locale.ROOT,"%.3f",recentDelta)
            +", stagnant="+stagnantTicks+"t, inputSamples="+inputSamples;
    }

    static boolean stuck() {
        return forward && stagnantTicks>70;
    }
}
