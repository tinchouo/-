package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gravedad dada vuelta: las teclas de costado van al reves (A sigue yendo a la izquierda de la pantalla). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void tod5$costados(CallbackInfo ci) {
		if (GravedadCliente.controlesDadosVuelta()) this.moveVector = new Vec2(-this.moveVector.x, this.moveVector.y);
	}
}
