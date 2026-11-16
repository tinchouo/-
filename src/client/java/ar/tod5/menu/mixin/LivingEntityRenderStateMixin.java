package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import ar.tod5.menu.GravedadEstado;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Guarda en el estado de dibujo para donde tiene la gravedad (para dibujarlo girado). */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements GravedadEstado {
	@Unique private GravedadCliente.G tod5$g = GravedadCliente.G.ABAJO;
	@Override public GravedadCliente.G tod5$gravedad() { return this.tod5$g; }
	@Override public void tod5$gravedad(GravedadCliente.G g) { this.tod5$g = g; }
}
