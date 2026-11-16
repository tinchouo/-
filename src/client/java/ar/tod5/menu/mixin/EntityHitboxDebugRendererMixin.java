package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F3+B con la gravedad cambiada: la rayita roja (los ojos) y la flecha azul (a donde mira) salen de los ojos de verdad. */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
	@Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
	private void tod5$gravedad(Entity e, float parcial, boolean delServer, CallbackInfo ci) {
		if (!(e instanceof Player p) || GravedadCliente.de(p) == GravedadCliente.G.ABAJO) return;
		ci.cancel();
		Vec3 pos = p.getPosition(parcial);
		int color = delServer ? -16711936 : -1;
		AABB caja = p.getBoundingBox().move(pos.subtract(p.position()));
		Gizmos.cuboid(caja, GizmoStyle.stroke(color));
		Gizmos.point(pos, color, 2.0F);
		Vec3 ojo = GravedadCliente.ojosDe(p, parcial);
		Gizmos.cuboid(GravedadCliente.planoOjos(caja, GravedadCliente.de(p), ojo), GizmoStyle.stroke(-65536));
		Gizmos.arrow(ojo, ojo.add(p.getViewVector(parcial).scale(2.0)), -16776961);
	}
}
