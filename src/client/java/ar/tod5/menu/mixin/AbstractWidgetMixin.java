package ar.tod5.menu.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Todos los botones de Minecraft quedan iguales (grises), pero al pasar el mouse el texto se pone verde. */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {
	private static final TextColor VERDE = TextColor.fromRgb(0x7DFFA0);

	@ModifyVariable(method = "extractScrollingStringOverContents", at = @At("HEAD"), argsOnly = true)
	private Component tod5$textoVerde(Component message) {
		AbstractWidget self = (AbstractWidget) (Object) this;
		if (self.active && self.isHoveredOrFocused()) return message.copy().withStyle(s -> s.withColor(VERDE));
		return message;
	}
}
