package ar.tod5.menu.mixin;

import ar.tod5.menu.Fondo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** En todos los menus (fuera de un mundo) se dibuja el mismo fondo del menu principal, sin el agujero negro y sin ojo de pez. */
@Mixin(Screen.class)
public class ScreenMixin {
	@Shadow public int width;
	@Shadow public int height;

	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void tod5$fondo(GuiGraphicsExtractor g, float a, CallbackInfo ci) {
		Fondo.dibujarSinAgujero(g, this.width, this.height);
		ci.cancel();
	}
}
