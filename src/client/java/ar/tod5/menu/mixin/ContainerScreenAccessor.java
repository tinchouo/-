package ar.tod5.menu.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("hoveredSlot") Slot tod5$hovered();
	@Accessor("leftPos") int tod5$left();
	@Accessor("topPos") int tod5$top();
	@Accessor("imageWidth") int tod5$ancho();
}
