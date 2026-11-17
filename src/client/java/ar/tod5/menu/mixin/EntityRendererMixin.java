package ar.tod5.menu.mixin;

import ar.tod5.menu.PlataformasCliente;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Las piezas de las plataformas que se mueven se dibujan donde dice su horario, en cada cuadro (asi se ven lisas aunque vayan rapido). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
	private void tod5$plataforma(Entity e, EntityRenderState s, float parcial, CallbackInfo ci) {
		Vec3 p = PlataformasCliente.dibujo(e, parcial);
		if (p != null) { s.x = p.x; s.y = p.y; s.z = p.z; }
	}
}
