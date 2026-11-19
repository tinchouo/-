package ar.tod5.menu.mixin;

import ar.tod5.menu.RarezaEstado;
import ar.tod5.menu.RarezaPiso;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Items con rareza tirados: brillo, rayo y raya (ver RarezaPiso). */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V", at = @At("TAIL"))
	private void tod5$rareza(ItemEntity e, ItemEntityRenderState s, float parcial, CallbackInfo ci) {
		((RarezaEstado) s).tod5$rareza(ar.tod5.menu.Rareza.rareza(e.getItem()));
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"))
	private void tod5$brillo(ItemEntityRenderState s, PoseStack pose, SubmitNodeCollector col, CameraRenderState cam, CallbackInfo ci) {
		var r = ((RarezaEstado) s).tod5$rareza();
		if (r == null || s.item.isEmpty()) return;
		AABB caja = s.item.getModelBoundingBox();
		float bob = Mth.sin(s.ageInTicks / 10.0F + s.bobOffset) * 0.1F + 0.1F;
		float centro = bob + 0.0625F + (float) (caja.maxY - caja.minY) / 2f;
		RarezaPiso.dibujar(r, s.ageInTicks, centro, pose, col, cam);
	}
}
