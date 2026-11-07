package lockon.mixin;

import lockon.LockOnMovement;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method="tick", at=@At("RETURN"))
    private void lockon$sampleSurvivalMovement(CallbackInfo ci) {
        Input sampled=LockOnMovement.sample(this.keyPresses);
        if(sampled==this.keyPresses) return;
        this.keyPresses=sampled;
        float forward=(sampled.forward()?1:0)-(sampled.backward()?1:0);
        float left=(sampled.left()?1:0)-(sampled.right()?1:0);
        this.moveVector=new Vec2(left,forward).normalized();
    }
}
