#!/usr/bin/env python3
"""Exercise the production movement controller with a minimal client fixture.

These tests validate input ownership and takeover, not Minecraft world physics.
"""
from pathlib import Path
import subprocess
import tempfile

sources = {
    'net/minecraft/world/entity/player/Input.java': '''
package net.minecraft.world.entity.player;
public record Input(boolean forward,boolean backward,boolean left,boolean right,
                    boolean jump,boolean shift,boolean sprint) {
    public static final Input EMPTY=new Input(false,false,false,false,false,false,false);
}
''',
    'net/minecraft/world/phys/Vec3.java': '''
package net.minecraft.world.phys;
public class Vec3 {
    public final double x,z;
    public Vec3(double x,double z) {this.x=x;this.z=z;}
}
''',
    'net/minecraft/client/Minecraft.java': '''
package net.minecraft.client;
import net.minecraft.world.phys.Vec3;
public class Minecraft {
    public static final Minecraft INSTANCE=new Minecraft();
    public Player player=new Player();
    public Object level=new Object();
    public Gui gui=new Gui();
    public static Minecraft getInstance() {return INSTANCE;}
    public static class Player {
        public Vec3 pos=new Vec3(0,0);
        public Vec3 position() {return pos;}
    }
    public static class Gui {
        public Object screen;
        public Object screen() {return screen;}
    }
}
''',
    'lockon/LockOnSurvival.java': '''
package lockon;
import net.minecraft.client.Minecraft;
final class LockOnSurvival {
    static boolean active=true;
    static int stopped;
    static boolean enabled() {return active;}
    static void stop(Minecraft mc,String message) {
        active=false;
        stopped++;
        LockOnMovement.stop(mc);
    }
}
''',
    'lockon/MovementInputTest.java': '''
package lockon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
public class MovementInputTest {
    static int passed;
    static void check(boolean valid,String message) {
        if(!valid) throw new AssertionError(message);
        passed++;
    }
    public static void main(String[] args) {
        Minecraft mc=Minecraft.getInstance();
        // Minecraft fixture intentionally has no Options/KeyMapping API:
        // production code must not depend on rewriting physical key states.
        LockOnMovement.stop(mc);
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"idle input identity");
        LockOnMovement.forward(mc,true);
        LockOnMovement.jump(mc,true);
        LockOnMovement.beforeClientTick(mc);
        Input bot=LockOnMovement.sample(Input.EMPTY);
        check(bot.forward() && bot.sprint() && bot.jump(),"bot intent reaches input sample");
        check(!bot.backward() && !bot.left() && !bot.right(),"no phantom directions");
        LockOnMovement.beforeClientTick(mc);
        check(LockOnMovement.sample(Input.EMPTY).forward(),"intent survives tick boundary");
        for(int key=0;key<7;key++) {
            LockOnSurvival.active=true;
            LockOnMovement.forward(mc,true);
            Input manual=new Input(key==0,key==1,key==2,key==3,key==4,key==5,key==6);
            check(LockOnMovement.sample(manual)==manual,"physical key preserved "+key);
            check(!LockOnSurvival.active,"manual key disables autoplay "+key);
            check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"no stale bot input "+key);
        }
        LockOnSurvival.active=true;
        LockOnMovement.forward(mc,true);
        mc.gui.screen=new Object();
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"no movement in menu");
        mc.gui.screen=null;
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"menu clears stale intent");
        LockOnMovement.forward(mc,true);
        mc.level=null;
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"disconnect clears intent");
        mc.level=new Object();
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"no movement in next world");
        LockOnMovement.forward(mc,true);
        for(int tick=0;tick<74;tick++) {
            LockOnMovement.sample(Input.EMPTY);
            LockOnMovement.beforeClientTick(mc);
        }
        check(LockOnMovement.stuck(),"stationary movement detected");
        mc.player.pos=new Vec3(1,0);
        LockOnMovement.beforeClientTick(mc);
        check(!LockOnMovement.stuck(),"actual progress clears watchdog");
        LockOnMovement.stop(mc);
        LockOnMovement.stop(mc);
        check(LockOnMovement.sample(Input.EMPTY)==Input.EMPTY,"repeated stop clears intent");
        System.out.println("Survival input: "+passed+" behavioral checks passed");
    }
}
'''
}
with tempfile.TemporaryDirectory(prefix='lockon-input-tests-') as directory:
    root=Path(directory)
    for name, source in sources.items():
        path=root/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(source)
    controller=root/'lockon/LockOnMovement.java'
    controller.write_text(Path('src/main/java/lockon/LockOnMovement.java').read_text())
    classes=root/'classes'
    subprocess.run(['javac','-d',str(classes),*[str(p) for p in root.rglob('*.java')]],check=True)
    subprocess.run(['java','-cp',str(classes),'lockon.MovementInputTest'],check=True)
