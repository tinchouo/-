package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gravedad dada vuelta: con la camara al reves, el mouse va al reves (asi mover a la derecha sigue mirando a la derecha). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void tod5$girar(LocalPlayer p, double y, double x) {
		if (GravedadCliente.controlesDadosVuelta()) p.turn(-y, -x);
		else p.turn(y, x);
	}
}
