package ar.tod5.menu.mixin;

import ar.tod5.menu.RarezaTooltip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anota que item esta por mostrar su descripcion (para la de las rarezas). */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow protected Slot hoveredSlot;

	@Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"))
	private void tod5$anotar(GuiGraphics g, int mx, int my, CallbackInfo ci) {
		RarezaTooltip.hover = this.hoveredSlot != null && this.hoveredSlot.hasItem() ? this.hoveredSlot.getItem() : ItemStack.EMPTY;
	}
	/** Clic en la casilla de la mochila (solo en el inventario propio). */
	@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
	private void tod5$clicMochila(net.minecraft.client.input.MouseButtonEvent ev, boolean doble, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
		ContainerScreenAccessor s = (ContainerScreenAccessor) this;
		if (ev.button() == 0 && ar.tod5.menu.MochilasCliente.adentro(s.tod5$left(), s.tod5$top(), ev.x(), ev.y())) { ar.tod5.menu.MochilasCliente.clicCasilla(); cir.setReturnValue(true); }
	}
	@Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("TAIL"))
	private void tod5$limpiar(GuiGraphics g, int mx, int my, CallbackInfo ci) { RarezaTooltip.hover = ItemStack.EMPTY; }
}
