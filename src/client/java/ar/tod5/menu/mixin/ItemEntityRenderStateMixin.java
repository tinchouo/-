package ar.tod5.menu.mixin;

import ar.tod5.menu.Rareza;
import ar.tod5.menu.RarezaEstado;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements RarezaEstado {
	@Unique private Rareza tod5$rareza;
	@Override public Rareza tod5$rareza() { return this.tod5$rareza; }
	@Override public void tod5$rareza(Rareza r) { this.tod5$rareza = r; }
}
