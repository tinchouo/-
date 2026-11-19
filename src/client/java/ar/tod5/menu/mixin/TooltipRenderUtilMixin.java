package ar.tod5.menu.mixin;

import ar.tod5.menu.RarezaTooltip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** El cuadro de la descripcion de un item con rareza: borde del color de la rareza. */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {
	@Inject(method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIILnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
	private static void tod5$fondo(GuiGraphics g, int x, int y, int w, int h, Identifier estilo, CallbackInfo ci) {
		if (RarezaTooltip.fondo(g, x, y, w, h, estilo)) ci.cancel();
	}
}
