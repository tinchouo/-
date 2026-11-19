package ar.tod5.menu.mixin;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {
	@SuppressWarnings("rawtypes")
	@Invoker("setTooltipForNextFrameInternal")
	void tod5$interno(Font f, List lineas, int x, int y, ClientTooltipPositioner pos, Identifier estilo, boolean reemplazar);
}
