package ar.tod5.menu.mixin;

import ar.tod5.menu.CartelEstado;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements CartelEstado {
	@Unique private Component tod5$vida;
	@Override public Component tod5$vida() { return this.tod5$vida; }
	@Override public void tod5$vida(Component c) { this.tod5$vida = c; }
}
