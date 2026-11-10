package ar.tod5.menu.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Pantalla de carga: fondo negro (en vez del rojo de Mojang) y barra verde. */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"), index = 4)
	private int tod5$fondoNegro(int color) {
		return color & 0xFF000000;
	}

	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;setVector4fFromARGB32"), index = 1, require = 0)
	private int tod5$fondoNegroDetras(int color) {
		return 0xFF000000;
	}

	@ModifyArg(method = "extractProgressBar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"), index = 4)
	private int tod5$barraVerde(int color) {
		return (color & 0xFF000000) | 0x5CFF8A;
	}
}
