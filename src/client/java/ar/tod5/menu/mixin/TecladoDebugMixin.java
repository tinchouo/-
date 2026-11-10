package ar.tod5.menu.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Solo para la prueba automatica: anota en el log cuando llega una tecla al juego. */
@Mixin(KeyboardHandler.class)
public class TecladoDebugMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), require = 0)
	private void tod5$tecla(CallbackInfo ci) {
		if (Boolean.getBoolean("tod5.debugTeclas")) org.slf4j.LoggerFactory.getLogger("TOD5").info("TOD5 llego una tecla");
	}
}
