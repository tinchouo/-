package ar.tod5.menu.mixin;

import ar.tod5.menu.Salida;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Al cerrar desde el menu, primero hace el fundido a negro (ver Salida). */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "stop", at = @At("HEAD"), cancellable = true)
	private void tod5$salir(CallbackInfo ci) {
		if (Salida.frenar((Minecraft) (Object) this)) ci.cancel();
	}
}
