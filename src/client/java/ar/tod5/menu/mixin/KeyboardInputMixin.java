package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gravedad dada vuelta: las teclas de costado van al reves (A sigue yendo a la izquierda de la pantalla); volando, shift y espacio tambien. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void tod5$costados(CallbackInfo ci) {
		if (!GravedadCliente.controlesDadosVuelta()) return;
		this.moveVector = new Vec2(-this.moveVector.x, this.moveVector.y);
		// volando en creativo: shift sube y espacio baja (el espacio sigue siendo "para arriba de la pantalla")
		var yo = Minecraft.getInstance().player;
		if (yo != null && yo.getAbilities().flying) {
			Input k = this.keyPresses;
			this.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.shift(), k.jump(), k.sprint());
		}
	}
}
