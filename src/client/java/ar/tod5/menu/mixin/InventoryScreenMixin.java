package ar.tod5.menu.mixin;

import ar.tod5.menu.MochilasCliente;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** La casilla de la mochila en el inventario (al lado del muñeco, arriba de la mano izquierda). */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(method = "renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V", at = @At("TAIL"))
	private void tod5$casilla(GuiGraphics g, float a, int mx, int my, CallbackInfo ci) {
		ContainerScreenAccessor s = (ContainerScreenAccessor) this;
		MochilasCliente.dibujarCasilla(g, s.tod5$left(), s.tod5$top());
	}
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
	private void tod5$encima(GuiGraphics g, int mx, int my, float a, CallbackInfo ci) {
		ContainerScreenAccessor s = (ContainerScreenAccessor) this;
		MochilasCliente.dibujarEncima(g, s.tod5$left(), s.tod5$top(), mx, my);
	}
}
