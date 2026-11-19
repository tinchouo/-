package ar.tod5.menu.mixin;

import ar.tod5.menu.RarezaSlot;
import ar.tod5.menu.RarezaTooltip;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rarezas: brillo detras de los items y la descripcion propia (nombre, rareza con estrellas, Único, borde). */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
	private void tod5$brillo(LivingEntity e, Level l, ItemStack it, int x, int y, int seed, CallbackInfo ci) {
		RarezaSlot.atras((GuiGraphics) (Object) this, it, x, y);
	}
	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("TAIL"))
	private void tod5$chispitas(LivingEntity e, Level l, ItemStack it, int x, int y, int seed, CallbackInfo ci) {
		ar.tod5.menu.AvisoDurabilidad.dibujar((GuiGraphics) (Object) this, it, x, y);
		RarezaSlot.adelante((GuiGraphics) (Object) this, it, x, y);
	}

	@Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"))
	private void tod5$anotar(Font f, ItemStack it, int x, int y, CallbackInfo ci) { RarezaTooltip.hover = it; }

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
	private void tod5$descripcion(Font f, List textos, Optional imagen, int x, int y, Identifier estilo, CallbackInfo ci) {
		ItemStack it = RarezaTooltip.hover;
		RarezaTooltip.hover = ItemStack.EMPTY;
		if (it.isEmpty()) return;
		List<ClientTooltipComponent> l = RarezaTooltip.armar(f, it, (List<Component>) textos, (Optional<TooltipComponent>) imagen);
		if (l == null) return;
		Identifier propio = RarezaTooltip.estilo(it);
		((GuiGraphicsInvoker) this).tod5$interno(f, l, x, y, DefaultTooltipPositioner.INSTANCE, propio != null ? propio : estilo, false);
		ci.cancel();
	}
}
