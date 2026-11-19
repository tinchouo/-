package ar.tod5.menu.mixin;

import ar.tod5.menu.CartelEstado;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** La mochila puesta, dibujada en la espalda de cada jugador (pegada al torso: se mueve con él). */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"))
	private void tod5$mochila(PoseStack pose, SubmitNodeCollector col, int luz, AvatarRenderState s, float yRot, float xRot, CallbackInfo ci) {
		var m = ((CartelEstado) s).tod5$mochila();
		if (m.isEmpty() || s.isInvisible) return;
		pose.pushPose();
		((HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel()).body.translateAndRotate(pose);
		pose.translate(0.0F, 0.4F, 0.26F);
		pose.mulPose(Axis.YP.rotationDegrees(180.0F));
		pose.scale(0.7F, -0.7F, -0.7F);
		m.submit(pose, col, luz, OverlayTexture.NO_OVERLAY, s.outlineColor);
		pose.popPose();
	}
}
